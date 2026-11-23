package com.company.floorplan.application.service;

import com.company.floorplan.application.dto.RecordValuationCmd;
import com.company.floorplan.domain.exception.BusinessException;
import com.company.floorplan.domain.exception.ErrorCode;
import com.company.floorplan.domain.model.PrudentValueRule;
import com.company.floorplan.domain.model.UsedConfigKeys;
import com.company.floorplan.domain.model.ValuationInput;
import com.company.floorplan.domain.model.ValuationPurpose;
import com.company.floorplan.domain.model.ValuationSource;
import com.company.floorplan.domain.model.ValuationStatus;
import com.company.floorplan.domain.model.VehicleValuation;
import com.company.floorplan.domain.repository.SupportRepository;
import com.company.floorplan.domain.repository.UsedConfigRepository;
import com.company.floorplan.domain.repository.VehicleUnitRepository;
import com.company.floorplan.domain.repository.VehicleValuationRepository;
import com.company.floorplan.domain.service.PrudentValueCalculator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 多源审慎估值用例编排（USED R3.1／R3.3／R3.4，D08 §3、D07 §7.2）。
 *
 * <p><b>本切片的红线是「没有基准就没有放款」</b>。LEGACY 链路上
 * {@code advance_amount = invoice_amount × ratio} 让「有发票就能借钱」成为默认，
 * 而 D08 §1 把这条列为要破除的现状。所以本类的三个写入口都遵循同一条形状：
 * <b>先算出结论、再决定能不能落库</b>，而不是先落库再由闸门挑毛病。
 * 反过来的话，一张不合格的估值行会在库里以 ACTIVE 存在一段时间，
 * 期间任何一条读到它的链路都得自己重算一遍才知道不能用。</p>
 *
 * <p><b>审慎值算法不接受请求指定</b>：算法读自配置键
 * {@code valuation.default-prudent-rule}（D08 §3.2）。让调用方挑算法，
 * 等于让每一笔业务自己决定自己的基准有多高。</p>
 *
 * <p><b>本包不产生 DRAFT 估值行</b>：不合格当场拒绝并给出拒因，
 * 而不是留一行「待补」。D03 §3.7 的 DRAFT 语义是「已录但未生效」，
 * 而在有唯一 ACTIVE 索引的表里，一行 DRAFT 既不占位也不告警，
 * 它唯一的作用是让运营以为「稍后补个源就能用」——实际补不了，重录是新行。</p>
 *
 * <p>事务边界只在本类（AGENTS.md §10）；审慎值与覆盖上限的判定都是
 * {@code domain/service} 里的纯函数。</p>
 */
@Service
public class UsedValuationApplicationService {

    private static final Logger log = LoggerFactory.getLogger(UsedValuationApplicationService.class);

    /** 审计目标类型；改名要同步前端「操作日志」筛选项。 */
    private static final String TARGET_TYPE = "VEHICLE_VALUATION";
    private static final String ACTION_RECORD = "VALUATION_RECORD";
    private static final String ACTION_OVERRIDE = "VALUATION_OVERRIDE";
    private static final String ACTION_EXPIRE = "VALUATION_EXPIRE";

    /** D08 §3.4：覆盖理由至少 10 字符。少于此长度等于没写（"重新看一下"这种）。 */
    private static final int MIN_REASON_LENGTH = 10;

    /** {@code valuation_inputs.ref} 是 VARCHAR(128)，理由要塞进去（D08 §3.4 留痕），上限留出前缀余量。 */
    private static final int MAX_REASON_LENGTH = 118;

    /** 覆盖留痕的 ref 前缀：让审计一眼区分「这是理由」还是「这是源侧引用号」。 */
    private static final String OVERRIDE_REF_PREFIX = "REASON:";

    private final VehicleValuationRepository valuations;
    private final VehicleUnitRepository vehicleUnits;
    private final UsedConfigRepository configs;
    private final SupportRepository supportRepository;
    private final UsedSodAuditRecorder sodAudit;

    public UsedValuationApplicationService(VehicleValuationRepository valuations,
                                           VehicleUnitRepository vehicleUnits,
                                           UsedConfigRepository configs,
                                           SupportRepository supportRepository,
                                           UsedSodAuditRecorder sodAudit) {
        this.valuations = valuations;
        this.vehicleUnits = vehicleUnits;
        this.configs = configs;
        this.supportRepository = supportRepository;
        this.sodAudit = sodAudit;
    }

    // ====================================================================
    // UC-R3-1 估值录入（D07 §7.2 POST /api/valuations）
    // ====================================================================

    /**
     * 录入一次估值结论。
     *
     * <p><b>校验顺序</b>：身份（vin／purpose／车在不在账）→ 入参形状（源、金额、重复键）
     * → 算法（配置键）→ 结论（审慎值计算）→ 有效期。
     * 顺序不是风格问题：把「源里有 {@code INTERNAL_DEGRADED}」放在结论之前，
     * 报错就是「这个源不能从这里进来」；放在之后，报错会变成「无有效估值」，
     * 调用方会去补源而不是删掉它——方向完全相反。</p>
     *
     * <p><b>同事务两笔写</b>：先把该 VIN 现有 ACTIVE 行置 {@code SUPERSEDED}，再插新行。
     * 顺序不能颠倒（{@code uk_val_active} 一 VIN 只许一行 ACTIVE），
     * 也不能不 supersede（重录会撞索引变成 409）。
     * 「重估写新行、旧行置替代」是 V55 表注 P20 的存储保证，历史行一律不改写。</p>
     *
     * @param cmd      命令
     * @param operator 操作人（令牌自然人，来自 {@code AuditActor}，不由请求体自报）
     * @param traceId  链路号，可空
     * @return 估值视图
     */
    @Transactional
    public Map<String, Object> record(RecordValuationCmd cmd, String operator, String traceId) {
        if (cmd == null) {
            throw new BusinessException(ErrorCode.VALID_001, "请求体不能为空");
        }
        String vin = requireText(cmd.vin(), "vin 必填：估值必须挂在具体车台上");
        ValuationPurpose purpose = parse("purpose", cmd.purpose(), ValuationPurpose::from,
                "purpose 必填，取值 " + Arrays.toString(ValuationPurpose.values()));
        if (vehicleUnits.findByVin(vin).isEmpty()) {
            throw new BusinessException(ErrorCode.VEHICLE_001, "估值未生效：VIN 不在账 " + vin,
                    Map.of("vin", vin));
        }
        LocalDate valuationDate = cmd.valuationDate() != null ? cmd.valuationDate() : LocalDate.now();
        List<ValuationInput> inputs = buildInputs(cmd, vin, valuationDate);

        // 显式带键的重放：先认旧结论，一个字都不写（AGENTS.md §10「幂等键 + 唯一约束兜底」的前半）。
        // 不这么做的后果不是重复插（唯一索引拦住了），而是那一次 409 会把同事务里的
        // supersede 一起回滚——调用方重试三次都在「替代旧行 → 插新行失败」之间打转，
        // 看起来像系统坏了，实际是它自己把幂等键当流水号用了。
        String givenKey = trim(cmd.valuationKey());
        if (givenKey != null) {
            VehicleValuation existing = valuations.findByKey(givenKey).orElse(null);
            if (existing != null) {
                if (!vin.equals(existing.getVin())
                        || (existing.getPurpose() != null && existing.getPurpose() != purpose)) {
                    // 同一个键被拿去描述另一台车/另一种用途：这不是重放，是键复用，必须拒。
                    throw new BusinessException(ErrorCode.CONFLICT_001,
                            "valuation_key 已被另一笔估值占用，不得复用",
                            Map.of("valuationKey", givenKey, "existingVin", existing.getVin(),
                                    "existingPurpose", String.valueOf(existing.getPurpose())));
                }
                log.info("[USED R3] 估值重放 key={} → id={} prudent={}，不重复写",
                        givenKey, existing.getId(), existing.getPrudentValue());
                Map<String, Object> replay = view(existing);
                replay.put("idempotent", true);
                replay.put("supersededCount", 0);
                return replay;
            }
        }

        // 人工覆盖二人：只在真的带了 MANUAL_OVERRIDE 时才要求第二人，
        // 否则纯事实源录入也会被一个必填项卡住（D07 §7.2 的括注只针对含覆盖的情形）。
        boolean hasManual = inputs.stream().anyMatch(in -> in.source() == ValuationSource.MANUAL_OVERRIDE);
        String coSignedBy = trim(cmd.coSignedBy());
        if (hasManual) {
            if (coSignedBy == null) {
                throw new BusinessException(ErrorCode.VALU_004,
                        "含人工覆盖的估值必须给第二签认人 co_signed_by（D08 §3.4）",
                        Map.of("vin", vin));
            }
            if (coSignedBy.equalsIgnoreCase(operator)) {
                throw new BusinessException(ErrorCode.AUTH_003,
                        "人工覆盖的第二签认人不能是操作人自己（D08 §3.4）",
                        Map.of("operator", String.valueOf(operator)));
            }
        } else if (coSignedBy != null) {
            // 没有人工值却给了第二人：库里 ck_val_override_pair 会因为 overridden_by 为空而放过它，
            // 但这条痕迹是假的（看上去像二人复核过，其实没有任何被复核的数）。拒掉。
            throw new BusinessException(ErrorCode.VALID_001,
                    "co_signed_by 只在 sources 含 MANUAL_OVERRIDE 时才允许出现");
        }

        PrudentValueRule rule = resolveRule();
        PrudentValueCalculator.Decision decision =
                PrudentValueCalculator.calculate(rule, purpose, inputs, hasManual);
        if (!decision.clean()) {
            throw reject(decision, vin, purpose, inputs);
        }

        LocalDate validUntil = cmd.validUntil() != null
                ? cmd.validUntil()
                : valuationDate.plusDays(resolveValidDays());
        if (validUntil.isBefore(valuationDate)) {
            // V55 的 ck_val_draft_clean 只管 DRAFT 行；ACTIVE 行反向区间不会被库拦住，
            // 却会让这笔估值一出生就是"已过期"，闸门读起来是 VALU_002 而不是"参数错"。
            throw new BusinessException(ErrorCode.VALID_001,
                    "valid_until 不得早于 valuation_date（当前 " + valuationDate + " → " + validUntil + "）");
        }

        VehicleValuation v = new VehicleValuation();
        v.setVin(vin);
        v.setPurpose(purpose);
        v.setPrudentRule(rule);
        v.setPrudentValue(decision.prudentValue());
        v.setPrimarySource(decision.primarySource());
        v.setValuationDate(valuationDate);
        v.setValidUntil(validUntil);
        v.setStatus(ValuationStatus.ACTIVE);
        v.setAuctionLotId(cmd.auctionLotId());
        v.setMarketSnapshotId(cmd.marketSnapshotId());
        v.setAppraisalProvider(trim(cmd.appraisalProvider()));
        v.setAppraisalNo(trim(cmd.appraisalNo()));
        v.setReportPath(trim(cmd.reportPath()));
        v.setReportDigest(trim(cmd.reportDigest()));
        v.setCreatedBy(operator);
        v.setUpdatedBy(operator);
        if (hasManual) {
            v.setOverriddenBy(operator);
            v.setCoSignedBy(coSignedBy);
        }
        for (ValuationInput input : inputs) {
            v.addInput(input);   // 同时回填四个来源列（见 VehicleValuation#addInput）
        }
        // 键由调用方显式给出时沿用（上面已做过重放判定），缺省则由仓储按 VIN + 估值日数序号
        // （VAL-{vin}-{yyyyMMdd}-{seq}，D00 §13.1）。
        v.setValuationKey(givenKey != null ? givenKey : valuations.nextValuationKey(vin, valuationDate));

        int superseded = valuations.supersedeActive(vin, operator);
        try {
            valuations.insert(v);
        } catch (DuplicateKeyException ex) {
            // 只剩两种可能：valuation_key 与并发请求撞了，或该 VIN 刚被并发插入一行 ACTIVE。
            // 两种都回 CONFLICT_001 让调用方重读重试；事务回滚会连同上面的 supersede 一起撤销，
            // 所以不会出现「旧行已替代、新行没进来」的基准断层。
            throw new BusinessException(ErrorCode.CONFLICT_001,
                    "估值未写入：valuation_key 重复或该 VIN 正被并发估值，请重读后重试",
                    Map.of("vin", vin, "valuationKey", String.valueOf(v.getValuationKey())));
        }

        supportRepository.writeAudit(operator, ACTION_RECORD, TARGET_TYPE, vin,
                "superseded=" + superseded,
                "id=" + v.getId() + " key=" + v.getValuationKey() + " purpose=" + purpose
                        + " rule=" + rule + " prudent=" + decision.prudentValue()
                        + " primary=" + decision.primarySource() + " sources=" + decision.eligibleSourceCount()
                        + " validUntil=" + validUntil
                        + (hasManual ? " override=" + operator + "/" + coSignedBy : ""),
                traceId);
        log.info("[USED R3] 估值 vin={} id={} prudent={} rule={} primary={} sources={} superseded={} by={}",
                vin, v.getId(), decision.prudentValue(), rule, decision.primarySource(),
                decision.eligibleSourceCount(), superseded, operator);

        Map<String, Object> data = view(v);
        data.put("supersededCount", superseded);
        data.put("eligibleSourceCount", decision.eligibleSourceCount());
        data.put("weightedApplied", decision.weightedApplied());
        return data;
    }

    // ====================================================================
    // UC-R3-2 人工覆盖（D07 §7.2 POST /api/valuations/{id}/override）
    // ====================================================================

    /**
     * 对一条已生效的估值做人工覆盖。
     *
     * <p><b>覆盖改写的是结论，不是事实</b>：仓储的 UPDATE 清单里没有三个自动源列，
     * {@code manual_value} 与 {@code prudent_value} 会被改写，
     * 原始报价与全部输入明细留在原处（D08 §3.4「留痕」+ V55 表注 P20）。</p>
     *
     * <p><b>为什么允许改写一条已经 ACTIVE 的行</b>：覆盖的业务含义是
     * 「刚才那个结论判低了」，不是新增一次估值。真要留两个结论，走 {@link #record}
     * 重录一条——那条路径会把本行置 {@code SUPERSEDED}。</p>
     *
     * <p><b>不改写历史</b>的边界要说清楚：如果这笔估值已被某条垫款引用，
     * V58 的 {@code advances.prudent_value} 是<b>当时快照</b>，
     * 本行改写不会回溯改变已放款那笔的依据（V58 列注释正是为此把快照存成列）。
     * 所以这里不需要「被引用即锁定」的额外查询；需要的是把每一次覆盖都记进审计。</p>
     *
     * @param valuationId  被覆盖的估值行
     * @param manualValue  人工值（整数日元）
     * @param reason       理由（10〜118 字符，同时进 {@code valuation_inputs.ref} 与审计）
     * @param coSignedBy   第二签认人（必填，且不能是操作人自己）
     * @param operator     操作人
     * @param traceId      链路号
     */
    @Transactional
    public Map<String, Object> override(long valuationId, Long manualValue, String reason,
                                        String coSignedBy, String operator, String traceId) {
        if (manualValue == null) {
            // 缺字段与显式 0 差一个数量级：0 会把这台车的放款基准直接清零（VALU_006 会拦住，
            // 但拦住的是一条「审慎值为 0」的结论，而不是一句「你没给人工值」）。
            throw new BusinessException(ErrorCode.VALID_001,
                    "manual_value 必填：人工覆盖必须给出明确的人工值，缺字段不等于 0");
        }
        VehicleValuation v = loadForUpdate(valuationId);
        if (v.getStatus() != ValuationStatus.ACTIVE) {
            // 只对现行结论做覆盖。改 SUPERSEDED/EXPIRED 历史行会把"当时的结论"变成"今天的结论"，
            // 而审计已经按当时的结论解释过一笔放款。
            throw new BusinessException(ErrorCode.VALU_002,
                    "估值已失效或已被替代，不得再作覆盖对象（当前状态 " + v.getStatus() + "）",
                    Map.of("valuationId", String.valueOf(valuationId), "status", v.getStatus().name()));
        }
        String recorder = v.getCreatedBy();
        if (recorder != null && recorder.equalsIgnoreCase(operator)) {
            // D10 §4 的 D-4 对（估值录入 ↔ 人工覆盖）：录入人自己把基准抬上去＝无人复核。
            // created_by 为空（迁移或人工修数的历史行）时无从判定「同不同人」，只能放行——
            // 这条留痕缺口已登记在 PROGRESS §4，不用猜身份来假装拦住。
            sodAudit.blocked("D-4 估值录入↔人工覆盖", operator, recorder, v.getVin(), traceId);
            throw new BusinessException(ErrorCode.AUTH_003,
                    "估值录入人不得同时作为该估值的人工覆盖人（D10 §4 D-4），需换人",
                    Map.of("vin", v.getVin(), "recorder", recorder));
        }
        String trimmedReason = requireText(reason, "覆盖理由必填");
        if (trimmedReason.length() < MIN_REASON_LENGTH) {
            throw new BusinessException(ErrorCode.VALID_001,
                    "覆盖理由至少 " + MIN_REASON_LENGTH + " 字符（D08 §3.4），当前 " + trimmedReason.length());
        }
        if (trimmedReason.length() > MAX_REASON_LENGTH) {
            throw new BusinessException(ErrorCode.VALID_001,
                    "覆盖理由不得超过 " + MAX_REASON_LENGTH + " 字符（要落进 valuation_inputs.ref）");
        }
        if (manualValue < 0) {
            throw new BusinessException(ErrorCode.VALID_002, "金额非法：人工覆盖值不得为负");
        }
        String coSigner = requireText(coSignedBy, "co_signed_by 必填：人工覆盖必须二人（D08 §3.4）");
        if (coSigner.equalsIgnoreCase(operator)) {
            throw new BusinessException(ErrorCode.AUTH_003,
                    "人工覆盖的第二签认人不能是操作人自己", Map.of("operator", String.valueOf(operator)));
        }

        Long maxAuto = PrudentValueCalculator.maxAutoSourceValue(v.getInputs());
        if (maxAuto == null) {
            // 没有任何自动源，"自动源最大值的 115%" 就没有分母。
            // 这里不能退化成"不设上限"——那正是把硬约束软掉的形状（AGENTS.md §8）。
            throw new BusinessException(ErrorCode.VALU_001,
                    "该估值没有可用自动源，覆盖上限无法确定，拒绝覆盖（请改走含自动源的重录）",
                    Map.of("valuationId", String.valueOf(valuationId)));
        }
        BigDecimal uplift = resolveUplift();
        long ceiling = PrudentValueCalculator.overrideCeiling(maxAuto, uplift);
        if (manualValue > ceiling) {
            throw new BusinessException(ErrorCode.VALU_005,
                    "人工覆盖幅度超出允许上限：上限 " + ceiling + " = " + maxAuto + " × " + uplift,
                    Map.of("valuationId", String.valueOf(valuationId),
                            "manualValue", manualValue, "maxAutoSource", maxAuto,
                            "uplift", uplift.toPlainString(), "ceiling", ceiling));
        }

        LocalDate today = LocalDate.now();
        // 同日第二次覆盖必须挡在写之前：uk_val_input 是 (valuation_id, source, provider, as_of)，
        // rank 不在键里，第二条当日人工覆盖会被 ON CONFLICT DO NOTHING 静默丢弃，
        // 于是主表 manual_value 是新值、明细里那条还是旧值——审慎值就不再能由明细复算出来
        // （D11 A6 的验收点正是这一条）。挡住的代价是「今天想再改一次要等到明天」，
        // 而设计给的这条路是重录（POST /api/valuations，新行 + supersede），留痕更完整。
        boolean alreadyOverriddenToday = v.getInputs().stream().anyMatch(in ->
                in.source() == ValuationSource.MANUAL_OVERRIDE && today.equals(in.asOf()));
        if (alreadyOverriddenToday) {
            throw new BusinessException(ErrorCode.CONFLICT_001,
                    "该估值今天已经人工覆盖过一次，同日重复覆盖会被明细唯一索引静默丢弃；"
                            + "要改结论请走 POST /api/valuations 重录（D08 §3.3 重估走新行）",
                    Map.of("valuationId", String.valueOf(valuationId), "asOf", today.toString()));
        }

        long before = v.getPrudentValue();
        String beforeSource = String.valueOf(v.getPrimarySource());
        ValuationInput manual = new ValuationInput(ValuationSource.MANUAL_OVERRIDE, manualValue,
                OVERRIDE_REF_PREFIX + trimmedReason, null, today, null, null).normalizedProvider();
        v.addInput(manual);
        v.setOverriddenBy(operator);
        v.setCoSignedBy(coSigner);

        PrudentValueRule rule = v.getPrudentRule() != null ? v.getPrudentRule() : resolveRule();
        PrudentValueCalculator.Decision decision =
                PrudentValueCalculator.calculate(rule, v.getPurpose(), v.getInputs(), true);
        if (!decision.clean()) {
            throw reject(decision, v.getVin(), v.getPurpose(), v.getInputs());
        }
        v.setPrudentValue(decision.prudentValue());
        v.setPrimarySource(decision.primarySource());
        v.setPrudentRule(rule);
        v.setUpdatedBy(operator);
        // 明细先落、再改结论：两条都在同一事务里，顺序不影响原子性，
        // 但这样 updateConclusion 的 version 守卫失败时，日志里看到的是「明细已写、结论未改」，
        // 与真正的失败原因（并发）对得上；反过来会把「结论已改」写在前面误导排查。
        valuations.appendInput(valuationId, manual);
        if (valuations.updateConclusion(v) == 0) {
            throw new BusinessException(ErrorCode.CONFLICT_001,
                    "估值覆盖未生效：该行正被并发变更（version 不匹配），请重读后重试",
                    Map.of("valuationId", String.valueOf(valuationId)));
        }
        // 响应里的 version 由仓储在 UPDATE 成功后 +1（JdbcVehicleValuationRepository#updateConclusion），
        // 这里不能再加一次，否则回显的 version 比库里大一，前端下一次改必然冲突。

        supportRepository.writeAudit(operator, ACTION_OVERRIDE, TARGET_TYPE, v.getVin(),
                "id=" + valuationId + " prudent=" + before + " primary=" + beforeSource,
                "id=" + valuationId + " manual=" + manualValue + " prudent=" + decision.prudentValue()
                        + " primary=" + decision.primarySource() + " ceiling=" + ceiling
                        + " override_by=" + operator + " co_signed_by=" + coSigner
                        + " reason=" + trimmedReason,
                traceId);
        log.info("[USED R3] 覆盖 id={} vin={} {}→{} by={}/{} ceiling={}", valuationId, v.getVin(),
                before, decision.prudentValue(), operator, coSigner, ceiling);

        Map<String, Object> data = view(v);
        data.put("previousPrudentValue", before);
        data.put("overrideCeiling", ceiling);
        data.put("maxAutoSource", maxAuto);
        data.put("uplift", uplift);
        return data;
    }

    // ====================================================================
    // UC-R3-3 人工置失效（D07 §7.2 POST /api/valuations/{id}/expire）
    // ====================================================================

    /**
     * 人工把一条估值置 {@code EXPIRED}。
     *
     * <p>与到期作业的关系：到期是「{@code valid_until} 已过、由扫描作业落状态」，
     * 本方法是「今天还没到日子，但这个数我知道不能再用」（查定机构撤回报告、
     * 车辆出重大事故、行情源被判定污染）。两者目标态相同、触发者不同，
     * 所以都写 {@code audit_events}，但只有本方法带理由。</p>
     *
     * <p>置失效<b>不补录</b>新估值：失效后该车没有放款基准，闸门按 G2 拒付，
     * 需要钱的正确动作是重录而不是把状态改回来——D03 §3.7 没有 EXPIRED→ACTIVE 这条边，
     * 本方法也不提供。</p>
     */
    @Transactional
    public Map<String, Object> expire(long valuationId, String reason, String operator, String traceId) {
        VehicleValuation v = loadForUpdate(valuationId);
        if (v.getStatus() != ValuationStatus.ACTIVE) {
            throw new BusinessException(ErrorCode.VALU_002,
                    "估值当前状态是 " + v.getStatus() + "，只有 ACTIVE 可置失效",
                    Map.of("valuationId", String.valueOf(valuationId), "status", v.getStatus().name()));
        }
        String trimmedReason = requireText(reason, "置失效必须给理由（该值会被闸门与审计引用）");
        ValuationStatus before = v.getStatus();
        v.setStatus(ValuationStatus.EXPIRED);
        v.setUpdatedBy(operator);
        if (valuations.updateConclusion(v) == 0) {
            throw new BusinessException(ErrorCode.CONFLICT_001,
                    "估值置失效未生效：该行正被并发变更（version 不匹配）",
                    Map.of("valuationId", String.valueOf(valuationId)));
        }
        supportRepository.writeAudit(operator, ACTION_EXPIRE, TARGET_TYPE, v.getVin(),
                "id=" + valuationId + " status=" + before,
                "id=" + valuationId + " status=" + ValuationStatus.EXPIRED + " reason=" + trimmedReason,
                traceId);
        log.info("[USED R3] 置失效 id={} vin={} {}→EXPIRED by={}", valuationId, v.getVin(), before, operator);
        Map<String, Object> data = view(v);
        data.put("previousStatus", before.name());
        data.put("reason", trimmedReason);
        return data;
    }

    // ====================================================================
    // 读口（D07 §7.2 GET /api/valuations/active?vin=）
    // ====================================================================

    /**
     * 某车当前的放款基准估值。
     *
     * <p><b>无 ACTIVE 行回 404 {@code NOT_FOUND_001}，不回「空对象」</b>：
     * 后者会让调用方把「这台车没有估值」读成「有一个估值但字段是空的」，
     * 而这两种情况在闸门上的处置完全不同（前者要去做估值，后者要查数据为什么坏）。</p>
     */
    public Map<String, Object> activeFor(String vin) {
        String v = requireText(vin, "vin 必填");
        VehicleValuation found = valuations.findAdvanceBase(v)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND_001,
                        "该车没有 ACTIVE 放款基准估值", Map.of("vin", v)));
        Map<String, Object> data = view(found);
        PrudentValueCalculator.Decision recalc = PrudentValueCalculator.calculate(
                found.getPrudentRule(), found.getPurpose(), found.getInputs(),
                found.getOverriddenBy() != null && found.getCoSignedBy() != null);
        // 复算不是不信库里的数，而是这一行的两个来源（主表列 / 明细行）本就可能不一致：
        // 人工修数、迁移遗漏都会留下断裂。把它显式暴露出来，比让闸门各取一侧好。
        data.put("recalculatedPrudentValue", recalc.prudentValue());
        data.put("consistentWithInputs", recalc.prudentValue() == found.getPrudentValue());
        data.put("usableAsAdvanceBase", found.usableAsAdvanceBase()
                && !found.expiredOn(LocalDate.now()));
        return data;
    }

    // ====================================================================
    // 内部
    // ====================================================================

    private VehicleValuation loadForUpdate(long valuationId) {
        return valuations.findById(valuationId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND_001,
                        "估值不存在 id=" + valuationId, Map.of("valuationId", String.valueOf(valuationId))));
    }

    /** 命令里的源清单 → 领域输入，顺带做请求内查重（见方法内注释）。 */
    private static List<ValuationInput> buildInputs(RecordValuationCmd cmd, String vin, LocalDate valuationDate) {
        if (cmd.sources() == null || cmd.sources().isEmpty()) {
            throw new BusinessException(ErrorCode.VALU_001,
                    "至少需要一条估值输入源：没有输入的估值不能存在", Map.of("vin", vin));
        }
        List<ValuationInput> inputs = new ArrayList<>(cmd.sources().size());
        Set<String> seen = new LinkedHashSet<>();
        for (RecordValuationCmd.SourceLine line : cmd.sources()) {
            if (line == null) {
                throw new BusinessException(ErrorCode.VALID_001, "sources[] 里不允许有 null 元素");
            }
            ValuationSource source = parse("source", line.source(), ValuationSource::from,
                    "source 必填，取值 " + Arrays.toString(ValuationSource.values()));
            if (source == ValuationSource.INTERNAL_DEGRADED) {
                // D07 §7.2 明写禁止 + U-12 裁决「不提供人工直录降级估值的入口」。
                // 放在结论计算之前拒：走结论层会得到 VALU_003，语义是「这车只有降级推算」，
                // 而真正要告诉调用方的是「这个值不允许从这里进来」。
                throw new BusinessException(ErrorCode.VALID_001,
                        "INTERNAL_DEGRADED 只能由 I-12 询价失败路径产生，不接受人工直录（D07 §7.2 / U-12）",
                        Map.of("vin", vin));
            }
            if (line.value() == null) {
                throw new BusinessException(ErrorCode.VALID_001,
                        "sources[].value 必填：0 要显式写 0，缺字段不等于 0（V55 把两者定为两种事实）");
            }
            if (line.value() < 0) {
                throw new BusinessException(ErrorCode.VALID_002, "金额非法：源报价不得为负");
            }
            String provider = trim(line.provider());
            if (provider == null && source != ValuationSource.MANUAL_OVERRIDE) {
                // V55 的 uk_val_input 是普通唯一索引，NULL provider 彼此不等 ⟹
                // 「同一天两个未署名的行情价」能插成两行，而审慎值取哪一条取决于写入次序。
                // 规整成空串只解决了人工类来源（见 ValuationInput#normalizedProvider），
                // 事实源必须自报机构，否则这条证据没有出处。
                throw new BusinessException(ErrorCode.VALID_001,
                        "sources[].provider 必填（" + source + "）：缺机构的报价无法溯源");
            }
            ValuationInput input = new ValuationInput(source, line.value(), trim(line.ref()),
                    provider, line.asOf() != null ? line.asOf() : valuationDate,
                    line.weight(), line.rank()).normalizedProvider();
            // 请求内查重必须在这里做：仓储的 INSERT ... ON CONFLICT DO NOTHING 会静默丢弃
            // 第二条同 (source, provider, as_of) 的行，于是"报了两个价"变成"只留下先到的那个"，
            // 审慎值随插入顺序漂移且无任何痕迹。
            String key = input.source().name() + "|" + input.provider() + "|" + input.asOf();
            if (!seen.add(key)) {
                throw new BusinessException(ErrorCode.VALID_001,
                        "同一请求内出现重复的 (source, provider, as_of)：" + key
                                + "，两条不同报价会让审慎值随写入顺序漂移",
                        Map.of("vin", vin));
            }
            inputs.add(input);
        }
        return inputs;
    }

    /** 审慎算法：只读配置键，缺值或脏值 fail-closed（V41 注释的第三段）。 */
    private PrudentValueRule resolveRule() {
        String raw = configs.findActiveValue(UsedConfigKeys.VALUATION_DEFAULT_PRUDENT_RULE)
                .orElseThrow(() -> new BusinessException(ErrorCode.VALID_001,
                        "参数 " + UsedConfigKeys.VALUATION_DEFAULT_PRUDENT_RULE
                                + " 缺失或非法，无法确定审慎值算法（D06 §16 / D08 §3.2）"));
        PrudentValueRule rule;
        try {
            rule = PrudentValueRule.from(raw);
        } catch (IllegalArgumentException ex) {
            rule = null;
        }
        if (rule == null) {
            throw new BusinessException(ErrorCode.VALID_001,
                    "参数 " + UsedConfigKeys.VALUATION_DEFAULT_PRUDENT_RULE + " 取值非法: " + raw
                            + "（合法值 " + Arrays.toString(PrudentValueRule.values()) + "）");
        }
        return rule;
    }

    /** 有效期天数：请求显式给值优先，其次配置键，都没有 ⟹ 拒绝（不给隐式 45 天）。 */
    private int resolveValidDays() {
        return configs.findInt(UsedConfigKeys.VALUATION_VALID_DAYS)
                .filter(days -> days > 0)
                .orElseThrow(() -> new BusinessException(ErrorCode.VALID_001,
                        "未给 valid_until，且参数 " + UsedConfigKeys.VALUATION_VALID_DAYS
                                + " 缺失或非正数：有效期必须有出处，不写死在代码里"));
    }

    /** 覆盖上浮上限：缺值 ⟹ 拒绝覆盖（上限本身不可知时，"不限制"是最危险的解释）。 */
    private BigDecimal resolveUplift() {
        return configs.findDecimal(UsedConfigKeys.VALUATION_MANUAL_OVERRIDE_MAX_UPLIFT)
                .filter(v -> v.signum() > 0)
                .orElseThrow(() -> new BusinessException(ErrorCode.VALID_001,
                        "参数 " + UsedConfigKeys.VALUATION_MANUAL_OVERRIDE_MAX_UPLIFT
                                + " 缺失或非正数，覆盖上限无法确定，拒绝覆盖"));
    }

    /** 拒因 → 业务异常（码取第一个拒因，全部拒因进 details 让前端一次看全）。 */
    private static BusinessException reject(PrudentValueCalculator.Decision decision, String vin,
                                            ValuationPurpose purpose, List<ValuationInput> inputs) {
        List<String> texts = new ArrayList<>(decision.rejects().size());
        for (PrudentValueCalculator.Reject r : decision.rejects()) {
            texts.add(r.name() + ":" + r.text());
        }
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("vin", vin);
        details.put("purpose", purpose.name());
        details.put("rejects", texts);
        details.put("eligibleSourceCount", decision.eligibleSourceCount());
        details.put("inputSources", inputs.stream().map(in -> in.source().name()).toList());
        return new BusinessException(decision.firstCode(),
                "估值不生效：" + String.join("；", texts), details);
    }

    /** 统一响应形状（列表/详情/写操作三处共用，避免同一个对象在三个端点字段名不同）。 */
    private static Map<String, Object> view(VehicleValuation v) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("valuationId", v.getId());
        data.put("valuationKey", v.getValuationKey());
        data.put("vin", v.getVin());
        data.put("purpose", v.getPurpose() == null ? null : v.getPurpose().name());
        data.put("prudentValue", v.getPrudentValue());
        data.put("prudentRule", v.getPrudentRule() == null ? null : v.getPrudentRule().name());
        data.put("primarySource", v.getPrimarySource() == null ? null : v.getPrimarySource().name());
        data.put("auctionWonPrice", v.getAuctionWonPrice());
        data.put("appraisedValue", v.getAppraisedValue());
        data.put("marketPrice", v.getMarketPrice());
        data.put("manualValue", v.getManualValue());
        data.put("sourceColumnCount", v.eligibleSourceCount());
        data.put("inputs", v.getInputs().stream().map(UsedValuationApplicationService::viewInput).toList());
        data.put("valuationDate", v.getValuationDate());
        data.put("validUntil", v.getValidUntil());
        data.put("status", v.getStatus() == null ? null : v.getStatus().name());
        data.put("overriddenBy", v.getOverriddenBy());
        data.put("coSignedBy", v.getCoSignedBy());
        data.put("overrideFullySigned", v.overrideFullySigned());
        data.put("version", v.getVersion());
        return data;
    }

    private static Map<String, Object> viewInput(ValuationInput in) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("source", in.source().name());
        m.put("value", in.value());
        m.put("ref", in.ref());
        m.put("provider", in.provider());
        m.put("asOf", in.asOf());
        m.put("weight", in.weight());
        m.put("rank", in.rank());
        return m;
    }

    private static String requireText(String value, String message) {
        if (value == null || value.trim().isEmpty()) {
            if (message == null) {
                return null;
            }
            throw new BusinessException(ErrorCode.VALID_001, message);
        }
        return value.trim();
    }

    private static String trim(String value) {
        return value == null || value.trim().isEmpty() ? null : value.trim();
    }

    /** 枚举词解析的统一收口：非法取值必须是 400，不能一路冒到 SYS_001（同 R2 的 parseWord）。 */
    private static <E> E parse(String field, String raw, java.util.function.Function<String, E> parser,
                               String hint) {
        if (raw == null || raw.isBlank()) {
            throw new BusinessException(ErrorCode.VALID_001, hint);
        }
        E value;
        try {
            value = parser.apply(raw);
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(ErrorCode.VALID_001, "非法的 " + field + " 取值: " + raw);
        }
        if (value == null) {
            throw new BusinessException(ErrorCode.VALID_001, hint);
        }
        return value;
    }
}

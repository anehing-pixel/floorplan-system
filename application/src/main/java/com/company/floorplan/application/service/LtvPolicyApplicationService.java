package com.company.floorplan.application.service;

import com.company.floorplan.application.dto.CreateLtvPolicyCmd;
import com.company.floorplan.application.dto.LtvBandDraft;
import com.company.floorplan.domain.exception.BusinessException;
import com.company.floorplan.domain.exception.ErrorCode;
import com.company.floorplan.domain.model.LtvBand;
import com.company.floorplan.domain.model.LtvPolicy;
import com.company.floorplan.domain.model.LtvPolicyStatus;
import com.company.floorplan.domain.model.LtvTierInput;
import com.company.floorplan.domain.model.UsedVehicleAttrsView;
import com.company.floorplan.domain.model.ValuationStatus;
import com.company.floorplan.domain.model.VehicleUnit;
import com.company.floorplan.domain.model.VehicleValuation;
import com.company.floorplan.domain.repository.LtvPolicyRepository;
import com.company.floorplan.domain.repository.SupportRepository;
import com.company.floorplan.domain.repository.UsedVehicleMasterRepository;
import com.company.floorplan.domain.repository.VehicleUnitRepository;
import com.company.floorplan.domain.repository.VehicleValuationRepository;
import com.company.floorplan.domain.service.LtvPolicyValidator;
import com.company.floorplan.domain.service.LtvTierInputs;
import com.company.floorplan.domain.service.LtvTierResolver;
import com.company.floorplan.domain.service.UsedAdvanceCeilingCalculator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 分档 LTV 政策用例编排（USED R3.2，D08 §4、D07 §7.2）。
 *
 * <p><b>本切片守的是「禁止无政策运行」（D08 §5.1）＋「政策不可变」（L5）</b>。
 * 政策表空着时放款必须全线拒付，而不是自己找一个「行业惯例成数」顶上——
 * {@code valuation.default-max-ratio-fallback} 那个键正是这个自造数，
 * 所以本类不读它（差异已在 V79 头注与 PROGRESS §4 登记）。</p>
 *
 * <p><b>三代际动作分开</b>：起草（{@link #create}）与改带（{@link #replaceBands}）
 * 只能碰 {@code DRAFT}；生效（{@link #approve}）是唯一的 ACTIVE 入口，
 * 且必须换人（D10 §4 D-5：自己改 LTV 自己生效 ⟹ 分档上限可以一个人说了算）。
 * 仓储的 {@code insertDraft} 已经把状态钉成 DRAFT，所以「创建即生效」这条路
 * 在库层就不存在，本类的职责是别开出第二条。</p>
 *
 * <p><b>校验时机</b>：L1〜L4 在改带时跑一次、批准时再跑一次。
 * 第二次不是重复劳动——{@code default_ratio} 可以在改带之后被单独改写（政策头的列，
 * 不在带集合里），只在前一次校验会让「带与兜底」这对约束有机会分叉。</p>
 */
@Service
public class LtvPolicyApplicationService {

    private static final Logger log = LoggerFactory.getLogger(LtvPolicyApplicationService.class);

    private static final String TARGET_TYPE = "LTV_POLICY";
    private static final String ACTION_CREATE = "LTV_POLICY_CREATE";
    private static final String ACTION_BANDS = "LTV_POLICY_BANDS";
    private static final String ACTION_APPROVE = "LTV_POLICY_APPROVE";

    /** 试算响应里最多回显多少条带的判定明细：政策可以有几十档，评审只关心命中与最近的几档。 */
    private static final int MAX_BAND_ECHO = 30;

    /** SOD 对名称（D10 §4 表 D-5），与审计里的 pair 字段一致，便于反查规则。 */
    private static final String SOD_PAIR_MANAGE_APPROVE = "D-5 政策维护↔政策审批";

    private final LtvPolicyRepository policies;
    private final UsedVehicleMasterRepository usedVehicles;
    private final VehicleUnitRepository vehicleUnits;
    private final VehicleValuationRepository valuations;
    private final SupportRepository supportRepository;
    private final UsedSodAuditRecorder sodAudit;

    public LtvPolicyApplicationService(LtvPolicyRepository policies,
                                       UsedVehicleMasterRepository usedVehicles,
                                       VehicleUnitRepository vehicleUnits,
                                       VehicleValuationRepository valuations,
                                       SupportRepository supportRepository,
                                       UsedSodAuditRecorder sodAudit) {
        this.policies = policies;
        this.usedVehicles = usedVehicles;
        this.vehicleUnits = vehicleUnits;
        this.valuations = valuations;
        this.supportRepository = supportRepository;
        this.sodAudit = sodAudit;
    }

    // ====================================================================
    // 读口（D07 §7.2 GET /api/ltv-policies?status=）
    // ====================================================================

    /**
     * 政策列表（带集合内联）。
     *
     * <p>带条数与带集合本体都跟着每一行给出，两个原因：
     * ① 评审找「政策建档了但还没配带」的状态就是 {@code bandCount=0}；
     * ② {@code PUT /bands} 是<b>全量替换</b>，客户端必须能读到当前带集合才谈得上替换——
     * D07 §7.2 没有单独的 {@code GET /{id}}，把带集内联进列表是这份契约自带的唯一底本来源。
     * 政策是几十行量级的配置表，不是流水表，内联不会把响应撑爆。</p>
     */
    public List<Map<String, Object>> list(String status) {
        String filter = trim(status);
        if (filter != null) {
            filter = parseStatus(filter);
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (LtvPolicy p : policies.find(filter)) {
            Map<String, Object> row = header(p);
            row.put("bands", p.getBands().stream().map(LtvPolicyApplicationService::bandView).toList());
            row.put("validation", validationView(LtvPolicyValidator.validate(p)));
            rows.add(row);
        }
        return rows;
    }

    // ====================================================================
    // 起草（D07 §7.2 POST /api/ltv-policies）
    // ====================================================================

    /**
     * 新建一份草稿政策。
     *
     * <p><b>版本号由服务端算，不接受请求传入</b>（{@code max(version)+1}）：
     * 让调用方挑版本就会出现两个人各写一版 {@code v3}、其中一版被唯一索引拒掉，
     * 而失败的那一方以为自己的政策已经存在。</p>
     *
     * <p><b>本方法不接收带集合</b>：D07 把建政策与配带分成两个端点，
     * 一次 POST 就写出一整套带会让「改一档」和「建一版」混成同一个动作，
     * 审计里看不出哪一次是真正的定案。</p>
     */
    @Transactional
    public Map<String, Object> create(CreateLtvPolicyCmd cmd, String operator, String traceId) {
        if (cmd == null) {
            throw new BusinessException(ErrorCode.VALID_001, "请求体不能为空");
        }
        String code = requireText(cmd.policyCode(), "policy_code 必填，如 USED-MAIN（同码多版）");
        String name = requireText(cmd.policyName(), "policy_name 必填：列表页要靠它区分这一版是干什么的");
        LocalDate from = cmd.effectiveFrom();
        if (from == null) {
            throw new BusinessException(ErrorCode.VALID_001,
                    "effective_from 必填：没有生效起点的政策在闸门上是「何时开始算」都无法回答");
        }
        LocalDate to = cmd.effectiveTo();
        if (to != null && to.isBefore(from)) {
            // V57 有 ck_policy_window 兜底，但那时已经写进库了一部分（同事务回滚能兜住，
            // 错误码却会变成 500/DB 层消息）；在应用层先拦，报错才有业务含义。
            throw new BusinessException(ErrorCode.VALID_001, "effective_to 不得早于 effective_from");
        }
        BigDecimal defaultRatio = cmd.defaultRatio();
        if (defaultRatio != null) {
            assertRatioShape("default_ratio", defaultRatio);
        }

        LtvPolicy p = new LtvPolicy();
        p.setPolicyCode(code);
        p.setPolicyName(name);
        p.setVersion(policies.nextVersion(code));
        p.setStatus(LtvPolicyStatus.DRAFT);
        p.setEffectiveFrom(from);
        p.setEffectiveTo(to);
        p.setDefaultRatio(defaultRatio);
        p.setCreatedBy(operator);
        try {
            policies.insertDraft(p);
        } catch (DuplicateKeyException ex) {
            // 只可能是并发下两个人同时拿到同一个 version；重读一次版本号即可，不用换码。
            throw new BusinessException(ErrorCode.CONFLICT_001,
                    "政策版本冲突：该 policy_code 正被并发起草，请重读版本号后重试",
                    Map.of("policyCode", code, "version", p.getVersion()));
        }
        supportRepository.writeAudit(operator, ACTION_CREATE, TARGET_TYPE, String.valueOf(p.getId()), null,
                "policyCode=" + code + " version=" + p.getVersion() + " status=" + LtvPolicyStatus.DRAFT
                        + " window=" + from + ".." + to + " defaultRatio=" + defaultRatio,
                traceId);
        log.info("[USED R3] 政策起草 id={} code={} v={} by={}", p.getId(), code, p.getVersion(), operator);
        return header(p);
    }

    // ====================================================================
    // 全量替换带集合（D07 §7.2 PUT /api/ltv-policies/{id}/bands）
    // ====================================================================

    /**
     * 用请求给出的带集合整体替换某一版草稿的带。
     *
     * <p><b>为什么是全量替换而不是逐条增删</b>：L1（唯一命中）与 L2（全覆盖）都是
     * <b>集合级</b>性质，逐条改会让「删掉中间那一档」和「补上后面那一档」变成两次请求，
     * 两次之间这一版政策存在缺口——而缺口只在放款时才说话（表现是 LTV_002 或兜底），
     * 政策本身看起来一直是好的。全量替换让中间态不存在。</p>
     *
     * <p>校验失败回 {@code VALID_001} 并把 L1〜L4 的逐条违例放进 {@code details.violations[]}。
     * 这里<b>不用</b> {@code LTV_002}：那个码的语义是运行时「未唯一命中」，
     * 是拒付＋工单（D00 §9 表 A），政策编辑期的形状问题不该产生一张工单。</p>
     *
     * <p>L5（生效中不得改带）用 {@code LTV_004}，与 D08 §4 不变量表一致。</p>
     */
    @Transactional
    public Map<String, Object> replaceBands(long policyId, List<LtvBandDraft> drafts,
                                            String operator, String traceId) {
        LtvPolicy p = load(policyId);
        assertBandsEditable(p);
        if (drafts == null) {
            throw new BusinessException(ErrorCode.VALID_001,
                    "bands 必填（可为空数组＝清空带集合）：缺字段不等于清空，两者后果不同");
        }
        List<LtvBand> bands = new ArrayList<>(drafts.size());
        for (LtvBandDraft d : drafts) {
            bands.add(toBand(d));
        }
        LtvPolicy candidate = copyHeaderWithBands(p, bands);
        LtvPolicyValidator.Report report = LtvPolicyValidator.validate(candidate);
        if (!report.valid()) {
            throw invalid("分档带集合未通过 L1〜L4 校验", report);
        }

        int written = policies.replaceBands(policyId, bands);
        int before = p.getBands().size();
        supportRepository.writeAudit(operator, ACTION_BANDS, TARGET_TYPE, String.valueOf(policyId),
                "bandCount=" + before,
                "bandCount=" + written + " priorities=" + bands.stream().map(b -> String.valueOf(b.priority())).toList(),
                traceId);
        log.info("[USED R3] 带集合替换 policyId={} {}→{} by={}", policyId, before, written, operator);

        Map<String, Object> data = header(candidate);
        data.put("previousBandCount", before);
        data.put("bandCount", written);
        data.put("bands", bands.stream().map(LtvPolicyApplicationService::bandView).toList());
        data.put("validation", validationView(report));
        return data;
    }

    // ====================================================================
    // 批准生效（D07 §7.2 POST /api/ltv-policies/{id}/approve）
    // ====================================================================

    /**
     * 把草稿批准为 ACTIVE，并退役同码的旧 ACTIVE 版本。
     *
     * <p><b>顺序：先退役旧版，再启用新版</b>。反过来会在同一事务里出现
     * 「两版同时 ACTIVE」的一瞬间，而 {@code uk_policy_active} 是部分唯一索引，
     * 那一瞬间若被并发读到就是两个答案；正过来的最坏结果是「这一码暂时没有 ACTIVE 政策」，
     * 落在闸门上是拒付（保守侧），与 D08 §5.1 同向。</p>
     *
     * <p><b>换人是硬要求，不是流程约定</b>（D10 §4 D-5）：起草人自批等于
     * 一个人同时握着「定上限」和「批上限」。触发时回 {@code AUTH_003} 并独立留痕，
     * 见 {@link UsedSodAuditRecorder}。</p>
     *
     * <p>零带政策不得生效：没有档的政策一旦 ACTIVE，所有输入都落兜底或 {@code LTV_002}，
     * 而闸门读到的却是「有生效政策」——这是最容易被读成「政策已上线」的空壳。</p>
     */
    @Transactional
    public Map<String, Object> approve(long policyId, String operator, String traceId) {
        LtvPolicy p = load(policyId);
        if (p.getStatus() == LtvPolicyStatus.ACTIVE) {
            throw new BusinessException(ErrorCode.VALID_001,
                    "该政策已经是 ACTIVE，无需重复批准（要改带请新建版本）",
                    Map.of("policyId", String.valueOf(policyId)));
        }
        if (!p.getStatus().canMoveTo(LtvPolicyStatus.ACTIVE)) {
            // 只有 DRAFT→ACTIVE 合法（LtvPolicyStatus.canMoveTo）；RETIRED 是终态，
            // 复活一版退役政策会让历史笔的定价依据随运维操作漂移。
            throw new BusinessException(ErrorCode.VALID_001,
                    "政策状态 " + p.getStatus() + " 不可批准为 ACTIVE（只有 DRAFT 可以）",
                    Map.of("policyId", String.valueOf(policyId), "status", p.getStatus().name()));
        }
        String creator = p.getCreatedBy();
        if (creator != null && creator.equalsIgnoreCase(operator)) {
            sodAudit.blocked(SOD_PAIR_MANAGE_APPROVE, operator, creator, String.valueOf(policyId), traceId);
            throw new BusinessException(ErrorCode.AUTH_003,
                    "政策起草人不得批准自己起草的政策（D10 §4 D-5），需换人",
                    Map.of("policyId", String.valueOf(policyId), "createdBy", creator));
        }
        if (p.getBands().isEmpty()) {
            throw new BusinessException(ErrorCode.VALID_001,
                    "该版本还没有任何分档带，不得生效（D08 §5.1 禁止无政策运行，空政策同样是无政策）",
                    Map.of("policyId", String.valueOf(policyId)));
        }
        LtvPolicyValidator.Report report = LtvPolicyValidator.validate(p);
        if (!report.valid()) {
            throw invalid("批准被拒：政策未通过 L1〜L4 校验", report);
        }

        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        Long retiredId = null;
        LtvPolicy current = policies.findActive(p.getPolicyCode()).orElse(null);
        if (current != null && !current.getId().equals(policyId)) {
            if (policies.updateStatus(current.getId(), LtvPolicyStatus.ACTIVE, LtvPolicyStatus.RETIRED,
                    null, null) == 0) {
                throw new BusinessException(ErrorCode.CONFLICT_001,
                        "旧版本状态已被并发改变，本次批准整体回滚",
                        Map.of("retiringPolicyId", String.valueOf(current.getId())));
            }
            retiredId = current.getId();
        }
        try {
            if (policies.updateStatus(policyId, LtvPolicyStatus.DRAFT, LtvPolicyStatus.ACTIVE,
                    operator, now) == 0) {
                throw new BusinessException(ErrorCode.CONFLICT_001,
                        "批准未生效：该政策状态被并发改变（含并发批准同一版），请重读后重试",
                        Map.of("policyId", String.valueOf(policyId)));
            }
        } catch (DuplicateKeyException ex) {
            // uk_policy_active：并发的另一版先落地了。本事务连同上面的 RETIRED 一起回滚，
            // 所以不会出现「旧版已退役、新版没生效」的政策断层。
            throw new BusinessException(ErrorCode.CONFLICT_001,
                    "同 policy_code 已有另一版并发生效，本次批准整体回滚",
                    Map.of("policyCode", p.getPolicyCode()));
        }

        // 内存对象跟着状态机走：响应由 header(p) 装配，不改这里就会返回「批准成功但 status=DRAFT」，
        // bandsFrozen 也会跟着说谎（它读的是 status）。库已经写对了，错的只是这一份回显。
        p.setStatus(LtvPolicyStatus.ACTIVE);
        p.setApprovedBy(operator);
        p.setApprovedAt(now);

        supportRepository.writeAudit(operator, ACTION_APPROVE, TARGET_TYPE, String.valueOf(policyId),
                "status=" + LtvPolicyStatus.DRAFT + (retiredId == null ? "" : " activeVersion=" + retiredId),
                "status=" + LtvPolicyStatus.ACTIVE + " policyCode=" + p.getPolicyCode()
                        + " version=" + p.getVersion() + " bandCount=" + p.getBands().size()
                        + " defaultRatio=" + p.getDefaultRatio()
                        + (retiredId == null ? "" : " retiredPolicyId=" + retiredId),
                traceId);
        log.info("[USED R3] 政策生效 id={} code={} v={} by={} retired={}", policyId, p.getPolicyCode(),
                p.getVersion(), operator, retiredId);

        Map<String, Object> data = header(p);
        data.put("approvedBy", operator);
        data.put("approvedAt", now);
        data.put("retiredPolicyId", retiredId);
        data.put("bandCount", p.getBands().size());
        data.put("validation", validationView(report));
        return data;
    }

    // ====================================================================
    // 试算（D07 §7.2 GET /api/ltv-policies/{id}/simulation?vin=）
    // ====================================================================

    /**
     * 用指定政策的带集合对一台在库车做「会定成什么档」的推演，<b>只算不落</b>。
     *
     * <p>为什么这个端点存在（D08 §5.2 的评审诉求）：改一档的影响面是存量敞口，
     * 政策生效之前没人能回答「这一档改动会让多少台车的可借额变化」。
     * 本方法因此成为 {@code LtvTierResolver} 与 {@code UsedAdvanceCeilingCalculator}
     * 在生产代码里的第一个真实调用方——不是「为了有调用方而写的假调用」，
     * 它与放款闸门用的是同一段判定，这也是唯一能让试算结果与实放结果不撒谎的保证。</p>
     *
     * <p><b>试算不抛业务码</b>：未命中（{@code NO_TIER}）与多重命中（{@code AMBIGUOUS}）
     * 在放款时各有一个码（{@code LTV_002}），但那是拒付动作的留痕；
     * 评审要的恰恰是「这一版会让哪些车被拒」，把它变成 HTTP 422 就等于只回答「能过的车」。
     * 所以结论以 {@code outcome.status} 字段回 200，由调用方自己数缺口。
     * {@code requested_amount} 是否超上限同理（{@code LTV_001} 的判定结果放在字段里），
     * 真正的拒付发生在放款闸门那一层（R6，尚未接线）。</p>
     *
     * <p><b>{@code dealer_grade} 不自己合成</b>：双维分 → 字母档的映射是 U-11，未签字。
     * 缺省传 {@code null}，此时带集合里凡是设了 {@code dealer_grade_in} 的档都不命中
     * （{@code LtvBand} 的语义：带约束而输入缺失＝不满足），
     * 响应里 {@code dealerGradeMissing} 会说明这一点，免得评审把「没命中」读成「政策有洞」。</p>
     *
     * @param policyId        被推演的政策版本（ACTIVE 或 DRAFT 都可，评审就是要在生效前跑）
     * @param vin             车台
     * @param dealerGrade     车商档（可选，缺省不参与判定）
     * @param asOf            判定基准日（可选，缺省今天）
     * @param requestedAmount 假想申请额（可选，给了才算是否超上限）
     */
    public Map<String, Object> simulate(long policyId, String vin, String dealerGrade,
                                        LocalDate asOf, Long requestedAmount) {
        LtvPolicy p = load(policyId);
        String theVin = requireText(vin, "vin 必填：试算要针对具体车台");
        LocalDate today = asOf != null ? asOf : LocalDate.now();
        UsedVehicleAttrsView attrs = usedVehicles.findUsedAttributes(theVin)
                .orElseThrow(() -> new BusinessException(ErrorCode.VEHICLE_001,
                        "该车没有 USED 扩展属性（未走 UC-R2-1 建账），无法分档", Map.of("vin", theVin)));
        String grade = trim(dealerGrade);

        LtvTierInput input = LtvTierInputs.fromVehicleMaster(attrs, today, grade);
        LtvTierResolver.Outcome outcome = LtvTierResolver.resolve(input, p.getBands(), p.getDefaultRatio());

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("policyId", p.getId());
        data.put("policyCode", p.getPolicyCode());
        data.put("policyVersion", p.getVersion());
        data.put("policyStatus", p.getStatus() == null ? null : p.getStatus().name());
        data.put("effectiveOnThisDate", p.effectiveOn(today));
        data.put("vin", theVin);
        data.put("asOf", today);
        data.put("tierInput", tierView(input));
        data.put("dealerGradeMissing", grade == null);
        data.put("outcome", outcome.status().name());
        data.put("hitPriority", outcome.band() == null ? null : outcome.band().priority());
        data.put("maxRatio", outcome.maxRatio());
        data.put("rateAddonBps", outcome.rateAddonBps());
        data.put("breachThreshold", outcome.breachThreshold());
        data.put("hitCount", outcome.hits() == null ? 0 : outcome.hits().size());
        data.put("usedFallback", outcome.usedFallback());
        data.put("bandEvaluations", bandEvaluations(p, input));

        VehicleValuation valuation = valuations.findAdvanceBase(theVin).orElse(null);
        Long ceiling = null;
        String noCeilingReason = null;
        if (valuation == null) {
            // 「政策算得出档，但没有基准」也是评审要知道的结论：这一台车现在放不了款。
            data.put("valuationState", "NO_ACTIVE_ADVANCE_BASE");
            noCeilingReason = "VALU_001";
        } else {
            boolean usable = valuation.usableAsAdvanceBase() && !valuation.expiredOn(today);
            data.put("valuationState", usable ? "USABLE" : "NOT_USABLE");
            data.put("valuationId", valuation.getId());
            data.put("valuationKey", valuation.getValuationKey());
            data.put("valuationStatus", valuation.getStatus() == null ? null : valuation.getStatus().name());
            data.put("valuationPurpose", valuation.getPurpose() == null ? null : valuation.getPurpose().name());
            data.put("primarySource", valuation.getPrimarySource() == null
                    ? null : valuation.getPrimarySource().name());
            data.put("validUntil", valuation.getValidUntil());
            data.put("prudentValue", valuation.getPrudentValue());
            if (usable) {
                ceiling = UsedAdvanceCeilingCalculator.maxAdvance(valuation.getPrudentValue(),
                        outcome.maxRatio());
            } else {
                // 状态不是 ACTIVE ⇒ 基准失效（VALU_002）；主源是降级推算 ⇒ VALU_003。
                // 闸门上这两个码不同，试算也不该合并成一个「不可用」。
                noCeilingReason = valuation.getStatus() == ValuationStatus.ACTIVE ? "VALU_003" : "VALU_002";
            }
        }
        data.put("maxAdvance", ceiling);
        legacyComparison(theVin, data);

        if (requestedAmount != null) {
            data.put("requestedAmount", requestedAmount);
            if (ceiling == null) {
                // 没有基准就没有可比较的上限：这一步在闸门上是估值类错误码，不是 LTV_001。
                // 把这个区分留在字段里，评审才不会把「没估值」读成「额度不够」。
                data.put("wouldRejectCode", noCeilingReason);
                data.put("ceilingExceeded", null);
            } else {
                boolean within = UsedAdvanceCeilingCalculator.withinCeiling(requestedAmount, ceiling);
                data.put("ceilingExceeded", !within);
                data.put("exceedAmount", within ? 0L
                        : UsedAdvanceCeilingCalculator.exceedAmount(requestedAmount, ceiling));
                boolean tierUsable = outcome.status() == LtvTierResolver.Status.HIT
                        || outcome.status() == LtvTierResolver.Status.FALLBACK;
                data.put("wouldRejectCode", tierUsable ? (within ? null : "LTV_001") : "LTV_002");
            }
        }
        return data;
    }

    // ====================================================================
    // 内部
    // ====================================================================

    /** 旧口径对照（D08 §5.1 表的「现状链」列）：{@code invoice_amount × advance_ratio}。 */
    private void legacyComparison(String vin, Map<String, Object> data) {
        VehicleUnit unit = vehicleUnits.findByVin(vin).orElse(null);
        if (unit == null) {
            data.put("legacyBasisAvailable", false);
            return;
        }
        data.put("legacyBasisAvailable", true);
        data.put("invoiceAmount", unit.getInvoiceAmount());
        data.put("advanceRatio", unit.getAdvanceRatio());
        BigDecimal ratio = unit.getAdvanceRatio();
        // 只作对照展示，不参与任何判定：这条链正是 D08 §1 点名要破除的放款基准来源。
        data.put("legacyMaxAdvance", ratio == null ? null
                : BigDecimal.valueOf(unit.getInvoiceAmount()).multiply(ratio)
                        .setScale(0, java.math.RoundingMode.FLOOR).longValue());
        data.put("legacyNote", "invoice_amount × advance_ratio 为 LEGACY 口径，禁止作放款基准（D08 §5.4）");
    }

    /** 逐带判定明细（最多 {@value #MAX_BAND_ECHO} 条）：评审要的是「为什么没命中这一档」。 */
    private static List<Map<String, Object>> bandEvaluations(LtvPolicy p, LtvTierInput input) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (LtvBand b : p.getBands()) {
            if (rows.size() >= MAX_BAND_ECHO) {
                rows.add(Map.of("truncated", true, "remainingBands", p.getBands().size() - rows.size()));
                break;
            }
            List<String> unmet = b.unmetDimensions(input);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("priority", b.priority());
            row.put("matched", unmet.isEmpty());
            row.put("unmetDimensions", unmet);
            row.put("maxRatio", b.maxRatio());
            rows.add(row);
        }
        return rows;
    }

    private LtvPolicy load(long policyId) {
        return policies.findById(policyId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND_001,
                        "LTV 政策不存在 id=" + policyId, Map.of("policyId", String.valueOf(policyId))));
    }

    /** L5 的应用层表达。 */
    private static void assertBandsEditable(LtvPolicy p) {
        if (!p.getStatus().canEditBands()) {
            throw new BusinessException(ErrorCode.LTV_004,
                    p.getStatus() == LtvPolicyStatus.ACTIVE
                            ? "生效中的政策不得改分档带（L5），请新建版本再批准"
                            : "已退役的政策不得改分档带（L5）",
                    Map.of("policyId", String.valueOf(p.getId()), "status", p.getStatus().name()));
        }
    }

    /** 草稿态带集合校验失败：VALID_001 + 逐条违例（不用 LTV_002，那是运行时拒付码）。 */
    private static BusinessException invalid(String headline, LtvPolicyValidator.Report report) {
        Map<String, Object> details = validationView(report);
        return new BusinessException(ErrorCode.VALID_001, headline + details.get("summary"), details);
    }

    /** 校验结论 → 响应片段（列表页与试算共用同一形状，避免同一个校验两套字段名）。 */
    private static Map<String, Object> validationView(LtvPolicyValidator.Report report) {
        List<Map<String, Object>> violations = new ArrayList<>();
        for (LtvPolicyValidator.Violation v : report.violations()) {
            violations.add(Map.of("invariant", v.invariant(), "detail", v.detail()));
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("valid", report.valid());
        m.put("violations", violations);
        m.put("samplesChecked", report.samplesChecked());
        m.put("exhaustive", report.exhaustive());
        m.put("uncoveredCount", report.uncoveredCount());
        m.put("uncoveredExamples", report.uncoveredExamples());
        m.put("summary", violations.isEmpty() ? "" : "；" + violations.stream()
                .map(v -> String.valueOf(v.get("invariant"))).distinct().toList());
        return m;
    }

    /** 用请求草稿装配一条带：字段名与 V57 列名一一对应，不在 DTO 上发明「友好名」。 */
    private static LtvBand toBand(LtvBandDraft d) {
        if (d == null) {
            throw new BusinessException(ErrorCode.VALID_001, "bands[] 不允许 null 元素");
        }
        if (d.priority() == null) {
            throw new BusinessException(ErrorCode.VALID_001,
                    "bands[].priority 必填（> 0，同政策内不得重复）：缺字段不等于排在最后");
        }
        if (d.priority() <= 0) {
            throw new BusinessException(ErrorCode.VALID_001, "bands[].priority 必须 > 0，当前 " + d.priority());
        }
        if (d.maxRatio() == null) {
            throw new BusinessException(ErrorCode.VALID_001,
                    "bands[].max_ratio 必填：留空＝这一档没有上限，而不是「不设限」（不设限的是维度列）");
        }
        assertRatioShape("bands[" + d.priority() + "].max_ratio", d.maxRatio());
        if (d.breachThreshold() != null) {
            BigDecimal bt = d.breachThreshold();
            if (bt.signum() < 0 || bt.compareTo(BigDecimal.valueOf(100)) > 0) {
                // V57 的 CHECK 是 0..100（百分比数值而非比率），这里同口径先拦，报错才可读。
                throw new BusinessException(ErrorCode.VALID_001,
                        "breach_threshold 必须在 0〜100（百分比数值），当前 " + bt.toPlainString());
            }
        }
        if (d.rateAddonBps() != null && d.rateAddonBps() < 0) {
            throw new BusinessException(ErrorCode.VALID_001, "rate_addon_bps 不得为负，当前 " + d.rateAddonBps());
        }
        return new LtvBand(null, d.priority(), d.ageBandMinMonths(), d.ageBandMaxMonths(),
                d.mileageBandMinKm(), d.mileageBandMaxKm(), csv(d.repairHistoryIn()),
                d.gradeMin(), d.gradeMax(), d.inspectionRemainingMin(),
                upper(d.modelHeat()), csv(d.dealerGradeIn()), d.maxRatio(),
                d.rateAddonBps(), d.breachThreshold(), trim(d.note()));
    }

    private static void assertRatioShape(String field, BigDecimal ratio) {
        if (ratio.signum() <= 0) {
            throw new BusinessException(ErrorCode.VALID_001, field + " 必须 > 0，当前 " + ratio.toPlainString());
        }
        if (ratio.compareTo(BigDecimal.ONE) > 0) {
            throw new BusinessException(ErrorCode.VALID_001,
                    field + " 不得超过 1.0（L4：禁止融资本息之外的超额融资）");
        }
    }

    /** 只用于校验的候选政策：头字段原样、带集合换成待校验的那套。 */
    private static LtvPolicy copyHeaderWithBands(LtvPolicy p, List<LtvBand> bands) {
        LtvPolicy c = new LtvPolicy();
        c.setId(p.getId());
        c.setPolicyCode(p.getPolicyCode());
        c.setVersion(p.getVersion());
        c.setPolicyName(p.getPolicyName());
        c.setStatus(p.getStatus());
        c.setEffectiveFrom(p.getEffectiveFrom());
        c.setEffectiveTo(p.getEffectiveTo());
        c.setDefaultRatio(p.getDefaultRatio());
        c.setCreatedBy(p.getCreatedBy());
        c.setApprovedBy(p.getApprovedBy());
        c.setApprovedAt(p.getApprovedAt());
        for (LtvBand b : bands) {
            c.addBand(b);
        }
        return c;
    }

    private static Map<String, Object> header(LtvPolicy p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("policyId", p.getId());
        m.put("policyCode", p.getPolicyCode());
        m.put("policyVersion", p.getVersion());
        m.put("policyName", p.getPolicyName());
        m.put("status", p.getStatus() == null ? null : p.getStatus().name());
        m.put("effectiveFrom", p.getEffectiveFrom());
        m.put("effectiveTo", p.getEffectiveTo());
        m.put("defaultRatio", p.getDefaultRatio());
        m.put("bandCount", p.getBands().size());
        m.put("createdBy", p.getCreatedBy());
        m.put("approvedBy", p.getApprovedBy());
        m.put("approvedAt", p.getApprovedAt());
        m.put("bandsFrozen", p.bandsFrozen());
        return m;
    }

    private static Map<String, Object> bandView(LtvBand b) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("bandId", b.id());
        m.put("priority", b.priority());
        m.put("ageBandMinMonths", b.ageBandMinMonths());
        m.put("ageBandMaxMonths", b.ageBandMaxMonths());
        m.put("mileageBandMinKm", b.mileageBandMinKm());
        m.put("mileageBandMaxKm", b.mileageBandMaxKm());
        m.put("repairHistoryIn", b.repairHistoryIn());
        m.put("gradeMin", b.gradeMin());
        m.put("gradeMax", b.gradeMax());
        m.put("inspectionRemainingMin", b.inspectionRemainingMin());
        m.put("modelHeat", b.modelHeat());
        m.put("dealerGradeIn", b.dealerGradeIn());
        m.put("maxRatio", b.maxRatio());
        m.put("rateAddonBps", b.rateAddonBps());
        m.put("breachThreshold", b.breachThreshold());
        m.put("note", b.note());
        return m;
    }

    /** 分档输入回显：七维都给，评审才知道「没命中」是数据缺还是政策缺。 */
    private static Map<String, Object> tierView(LtvTierInput in) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ageMonths", in.ageMonths());
        m.put("mileageKm", in.mileageKm());
        m.put("repairHistory", in.repairHistory() == null ? null : in.repairHistory().name());
        m.put("grade", in.grade());
        m.put("inspectionRemainingMonths", in.inspectionRemainingMonths());
        m.put("modelHeat", in.modelHeat());
        m.put("dealerGrade", in.dealerGrade());
        return m;
    }

    private static String parseStatus(String raw) {
        try {
            return LtvPolicyStatus.from(raw).name();
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(ErrorCode.VALID_001,
                    "非法的 status 过滤值: " + raw + "，合法值 "
                            + Arrays.toString(LtvPolicyStatus.values()));
        }
    }

    /** CSV 集合列的规整：去空片段、大写、去重保序；空串归一成 null（＝该维不设限）。 */
    private static String csv(String raw) {
        String t = trim(raw);
        if (t == null) {
            return null;
        }
        List<String> parts = new ArrayList<>();
        for (String piece : t.split(",")) {
            String p = trim(piece);
            if (p != null && !parts.contains(p.toUpperCase())) {
                parts.add(p.toUpperCase());
            }
        }
        return parts.isEmpty() ? null : String.join(",", parts);
    }

    private static String upper(String raw) {
        String t = trim(raw);
        return t == null ? null : t.toUpperCase();
    }

    private static String requireText(String value, String message) {
        String t = trim(value);
        if (t == null) {
            throw new BusinessException(ErrorCode.VALID_001, message);
        }
        return t;
    }

    private static String trim(String value) {
        return value == null || value.trim().isEmpty() ? null : value.trim();
    }
}

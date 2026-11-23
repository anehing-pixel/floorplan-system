package com.company.floorplan.application.service;

import com.company.floorplan.application.dto.CertificateDraft;
import com.company.floorplan.application.dto.RecordConditionReportCmd;
import com.company.floorplan.application.dto.RegisterUsedVehicleCmd;
import com.company.floorplan.domain.exception.BusinessException;
import com.company.floorplan.domain.exception.ErrorCode;
import com.company.floorplan.domain.model.CertificateCustodyEvent;
import com.company.floorplan.domain.model.CertificateCustodyEventType;
import com.company.floorplan.domain.model.CertificateSecurityType;
import com.company.floorplan.domain.model.CertificateStatus;
import com.company.floorplan.domain.model.ConditionReportType;
import com.company.floorplan.domain.model.EncumbranceType;
import com.company.floorplan.domain.model.MileageSample;
import com.company.floorplan.domain.model.MileageSource;
import com.company.floorplan.domain.model.RepairHistory;
import com.company.floorplan.domain.model.UsedConfigKeys;
import com.company.floorplan.domain.model.UsedVehicleAttributes;
import com.company.floorplan.domain.model.UsedVehicleAttrsView;
import com.company.floorplan.domain.model.VehicleCertificate;
import com.company.floorplan.domain.model.VehicleConditionReport;
import com.company.floorplan.domain.model.VehicleStatus;
import com.company.floorplan.domain.model.VehicleUnit;
import com.company.floorplan.domain.model.WmsDiffSeverity;
import com.company.floorplan.domain.model.WmsDiffType;
import com.company.floorplan.domain.repository.SupportRepository;
import com.company.floorplan.domain.repository.UsedVehicleMasterRepository;
import com.company.floorplan.domain.repository.VehicleUnitRepository;
import com.company.floorplan.domain.repository.UsedConfigRepository;
import com.company.floorplan.domain.service.CertificateStateMachine;
import com.company.floorplan.domain.service.EncumbrancePolicy;
import com.company.floorplan.domain.service.MileageResolutionPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
// 数据库约束的翻译放在用例层：仓储按本仓约定不抛 BusinessException（infrastructure 层零处抛过），
// 而 DuplicateKeyException 属于 spring-tx，和这里的 @Transactional 同一个依赖，不需要新加坐标。
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * USED 车辆主数据与車検証用例编排（USED R2，D06 §2.2 的
 * {@code UsedVehicleMasterApplicationService}）。
 *
 * <p><b>本切片的一条主线是「里程不可静默改」</b>。所有能改
 * {@code vehicle_units.mileage} 的路径只有一个（{@link #applyMileage}），
 * 而它只接受 {@link MileageResolutionPolicy} 的判定结果。这样做的不是代码洁癖：
 * 里程直接进 R3 估值取带，一次没有出处的覆盖就会变成一次没有证据的放款基准变更。</p>
 *
 * <p><b>建账走 LEGACY 的 insert，USED 列走自己的 UPDATE</b>。
 * 理由见 {@link UsedVehicleAttributes} 的类注释——V50 头注的硬约束 1 是
 * 「既有新车建账的默认行为一条都不改」，复用 {@code VehicleUnitRepository.save}
 * 能让 L4 回归（新车建账）与 USED 建账共用同一条已验证的插入路径，
 * 而 USED 属性单独一条 UPDATE 写，两边互不污染。</p>
 *
 * <p><b>不用 {@code AdvanceApplicationService.ingestVehicles} 建 USED 账</b>：
 * 那条路径在建账时就按 {@code invoice_amount × advance_ratio} 算出 {@code advance_amount}，
 * 而 V50 列注释与 D08 §5.4 明写 USED 域<b>不得</b>以发票额作放款基准。
 * 复用它等于让第一个 USED 数字就是错的。</p>
 *
 * <p>事务边界只在本类（AGENTS.md §10）；domain 的三个策略都是纯函数。</p>
 */
@Service
public class UsedVehicleMasterApplicationService {

    private static final Logger log = LoggerFactory.getLogger(UsedVehicleMasterApplicationService.class);

    /** 审计动作名；改名要同步前端「操作日志」筛选项（AGENTS.md §10 错误码/文案同步的同型要求）。 */
    private static final String TARGET_TYPE = "USED_VEHICLE";
    private static final String ACTION_REGISTER = "USED_VEHICLE_REGISTER";
    private static final String ACTION_ATTRS = "USED_VEHICLE_ATTRS_UPDATE";
    private static final String ACTION_REPORT = "USED_CONDITION_REPORT";
    private static final String ACTION_MILEAGE = "USED_MILEAGE_RESOLVE";
    private static final String ACTION_CERT = "USED_CERTIFICATE_RECORD";
    private static final String ACTION_REBIND = "USED_CERTIFICATE_REBIND";

    /**
     * USED 域合法的购入渠道（D00 §8.1）。
     *
     * <p>库内 {@code ck_vu_purchase_channel} 允许五值（含 OEM / OTHER），
     * 那是为了兼容 LEGACY 存量行；<b>USED 建账只接受三值</b>——
     * 二手車不可能从厂家 OEM 直供，写进来会让 R3 的「按渠道取估值源」拿到一个不存在的组合。
     * 收窄发生在应用层而不是加 CHECK，是因为 CHECK 会连 LEGACY 的写入一起管住（L4 回归）。</p>
     */
    private static final List<String> USED_PURCHASE_CHANNELS = List.of("AUCTION", "TRADE_IN", "WHOLESALE");

    private final VehicleUnitRepository vehicleUnitRepository;
    private final UsedVehicleMasterRepository usedVehicleRepository;
    private final UsedConfigRepository configRepository;
    private final SupportRepository supportRepository;

    public UsedVehicleMasterApplicationService(VehicleUnitRepository vehicleUnitRepository,
                                               UsedVehicleMasterRepository usedVehicleRepository,
                                               UsedConfigRepository configRepository,
                                               SupportRepository supportRepository) {
        this.vehicleUnitRepository = vehicleUnitRepository;
        this.usedVehicleRepository = usedVehicleRepository;
        this.configRepository = configRepository;
        this.supportRepository = supportRepository;
    }

    // ====================================================================
    // UC-R2-1 USED 车辆建账
    // ====================================================================

    /**
     * 建账（D06 §2.1 UC-R2-1）。
     *
     * <p><b>必填判定（D06 §2 验收点「缺 vehicle_type / 非法 purchase_channel 被 400 拦」）</b>
     * 全部集中在本方法开头，顺序是「身份 → 分流 → 渠道 → 里程自洽」：
     * 分流键错了后面全错，所以 {@code vehicle_type} 先判。</p>
     *
     * <p><b>里程不在 INSERT 里写</b>：基础行先落（里程留 NULL），再由 {@link #applyMileage}
     * 按判定结果写三列。这样「主数据里程」在整条链上只有一个写入点，
     * 冒烟才能断言「里程来源可追溯」而不是「看起来对」。</p>
     *
     * @param cmd      建账命令
     * @param operator 操作人（令牌自然人，来自 {@code AuditActor}，不由请求体自报）
     * @return 建账视图（含里程采信结论）
     */
    @Transactional
    public Map<String, Object> registerUsedVehicle(RegisterUsedVehicleCmd cmd, String operator) {
        String vin = requireText(cmd.vin(), "VIN 不能为空");
        if (cmd.dealerId() == null) {
            throw new BusinessException(ErrorCode.VALID_001, "dealerId 不能为空");
        }

        // 分流键：缺失即拒（D06 §2 验收点）。V50 把存量 NULL 回填成了 NEW_STOCK，
        // 所以「NULL」在新写入里只可能是没传，不可能是历史数据。
        String vehicleType = requireText(cmd.vehicleType(),
                "vehicle_type 不能为空（USED 建账必须显式声明分流键，V50 列注释 / D00 §1.2 P-2）");
        if (!"USED".equalsIgnoreCase(vehicleType.trim())) {
            throw new BusinessException(ErrorCode.VALID_001,
                    "本端点只建 USED 账，vehicle_type 必须为 USED（实际: " + vehicleType + "）；"
                            + "新车渠道走 /api/vehicles/batch-ingest");
        }

        String channel = requireText(cmd.purchaseChannel(), "purchase_channel 不能为空").toUpperCase();
        if (!USED_PURCHASE_CHANNELS.contains(channel)) {
            throw new BusinessException(ErrorCode.VALID_001,
                    "USED 域的 purchase_channel 只能是 " + USED_PURCHASE_CHANNELS + "（实际: " + channel + "）",
                    Map.of("allowed", USED_PURCHASE_CHANNELS, "given", channel));
        }

        if (vehicleUnitRepository.existsByVin(vin)) {
            // 幂等键 VEH:{vin} 在库里就是这条自然键。重复建账不做「静默返回既有行」：
            // 建账会带金额与渠道，同号二次提交更可能是误操作或另一台车抢号，
            // 静默成功会让操作员以为第二台车已经入账。
            throw new BusinessException(ErrorCode.VEHICLE_003,
                    "VIN 已存在，重复建账: " + vin,
                    Map.of("vin", vin, "idempotencyKey", "VEH:" + vin));
        }
        if (cmd.invoiceAmount() != null && cmd.invoiceAmount() < 0) {
            throw new BusinessException(ErrorCode.VALID_002, "发票金额不得为负: " + cmd.invoiceAmount());
        }
        // 里程三元组的自洽校验：库内 ck_vu_mileage_unknown 要求 UNKNOWN ⟹ mileage IS NULL，
        // 在这里先拦，错误信息才能落到「哪个字段矛盾」，而不是等数据库回一句 constraint violation。
        MileageSource declared = parseWord("mileage_source", cmd.mileageSource(),
                MileageSource::from, MileageSource.values());
        if (declared == MileageSource.UNKNOWN && cmd.mileageKm() != null) {
            throw new BusinessException(ErrorCode.VALID_001,
                    "mileage_source=UNKNOWN 时不得同时给出里程值: " + cmd.mileageKm()
                            + "（V50 ck_vu_mileage_unknown；UNKNOWN 的正确表达是「来源 UNKNOWN ＋ mileage NULL」）");
        }
        if (declared != MileageSource.UNKNOWN && cmd.mileageKm() != null && cmd.mileageKm() < 0) {
            throw new BusinessException(ErrorCode.VALID_001, "里程不得为负: " + cmd.mileageKm());
        }
        // 修復歴：null（从未采集）与 UNKNOWN（采集过但判不清）在 V50 是两件事，
        // 所以这里不做「空白回落 UNKNOWN」，原样把 null 传下去。
        RepairHistory repair = parseWord("repair_history", cmd.repairHistory(),
                RepairHistory::from, RepairHistory.values());

        VehicleUnit unit = new VehicleUnit();
        unit.setVin(vin);
        unit.setDealerId(cmd.dealerId());
        unit.setBrand(cmd.brand());
        unit.setModel(cmd.model());
        unit.setModelYear(cmd.modelYear());
        unit.setColor(cmd.color());
        unit.setVehicleType("USED");
        unit.setSupplierName(cmd.supplierName());
        unit.setPurchaseChannel(channel);
        unit.setInvoiceAmount(cmd.invoiceAmount() == null ? 0L : cmd.invoiceAmount());
        // 建账不占额度、不产生垫款：advance_ratio / advance_amount 留空，
        // 放款基准由 R3 的审慎值 × LTV 档在放款闸门时算（D08 §5.4 禁止用 invoice_amount 推放款额）。
        unit.setAdvanceAmount(0L);
        unit.setPrincipalBalance(0L);
        unit.setMileage(null);
        unit.registerLedger();
        unit.setAgingDays(0);
        unit.setOverdueDays(0);
        unit.setAuditMismatchCount(0);
        vehicleUnitRepository.save(unit);

        UsedVehicleAttributes attrs = new UsedVehicleAttributes(
                trim(cmd.registrationPlate()), cmd.firstRegisteredOn(), cmd.inspectionExpiryDate(),
                cmd.inspectionRemainingMonths(), repair, trim(cmd.repairHistoryDesc()),
                cmd.accidentHistoryFlag(), cmd.auctionGrade(), upper(cmd.saleChannel()),
                trim(cmd.warehouseCustodianCode()), trim(cmd.binLocation()), cmd.auctionLotId());
        int attrsRows = usedVehicleRepository.updateUsedAttributes(vin, attrs);

        MileageSample master = new MileageSample(null, null, null, null);
        MileageResolutionPolicy.Decision decision = applyMileage(vin, master,
                List.of(new MileageSample(declared, cmd.mileageKm(), cmd.mileageCapturedOn(), "REGISTER")),
                operator, ACTION_REGISTER);

        supportRepository.writeAudit(operator, ACTION_REGISTER, TARGET_TYPE, vin, null,
                "vehicleType=USED channel=" + channel + " invoice=" + cmd.invoiceAmount()
                        + " attrsRows=" + attrsRows + " mileage=" + decision.outcome()
                        + "(" + decision.source() + "," + decision.mileageKm() + ")", null);
        log.info("[USED R2] 建账 vin={} dealer={} channel={} mileageSource={} outcome={}",
                vin, cmd.dealerId(), channel, declared, decision.outcome());

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("vin", vin);
        data.put("status", VehicleStatus.CREATED.name());
        putMileageDecision(data, decision);
        return data;
    }

    // ====================================================================
    // UC-R2-2 车况票登记 + UC-R2-3/6 里程勾稽与来源切换
    // ====================================================================

    /**
     * 登记车况票，并对该票做里程勾稽（UC-R2-2 + UC-R2-3 的票侧入口）。
     *
     * <p><b>票可以先于建账到达</b>：V51 刻意不给 {@code vin} 建物理 FK，注释理由是
     * 「検査票在落札当晚就推送，建 FK 会让合法票被拒收」。所以本方法<b>不</b>要求车已在账——
     * 车不在账上时票照存，返回 {@code vehiclePending=true}，等建账后由
     * {@link #reconcileMileage} 补勾稽。</p>
     */
    @Transactional
    public Map<String, Object> recordConditionReport(RecordConditionReportCmd cmd, String operator) {
        String vin = requireText(cmd.vin(), "VIN 不能为空");
        ConditionReportType type = parseReportType(cmd.reportType());
        if (cmd.inspectedOn() == null) {
            throw new BusinessException(ErrorCode.VALID_001, "検査日不能为空（取最新票按 inspected_on 排序）");
        }
        if (cmd.odometerKm() != null && cmd.odometerKm() < 0) {
            throw new BusinessException(ErrorCode.VALID_001, "里程观测值不得为负: " + cmd.odometerKm());
        }
        // 票面修復歴只解析一次：下面既要写票（report 行），又要拿它和主数据取严，
        // 分别调 from() 会让「同一个非法词」在两处报出不同的错（第一处 400、第二处 500）。
        RepairHistory reported = parseWord("repair_history", cmd.repairHistory(),
                RepairHistory::from, RepairHistory.values());

        VehicleConditionReport report = new VehicleConditionReport(
                null, vin, trim(cmd.reportNo()), type, cmd.inspectedOn(), trim(cmd.inspector()),
                cmd.structureCondition(), reported, cmd.accidentHistoryFlag(),
                cmd.modificationFlag(), cmd.interiorCondition(), cmd.exteriorCondition(),
                cmd.odometerKm(), cmd.inspectionExpiryDate(), cmd.recycleTicketFlag(),
                cmd.compulsoryInsuranceExpiry(), cmd.automobileTaxStatus(), cmd.photoPaths(),
                cmd.attachmentPath(), cmd.attachmentDigest(), null, null, operator);
        UsedVehicleMasterRepository.ReportIngestion ingestion =
                usedVehicleRepository.insertConditionReport(report);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("vin", vin);
        data.put("reportType", type.name());
        data.put("reportId", ingestion.reportId());
        // created=false ⟹ 票号已在账（uk_vcr_report_no），本次是重推；控制器据此回 200。
        data.put("idempotent", !ingestion.created());
        boolean onBook = vehicleUnitRepository.existsByVin(vin);
        data.put("vehiclePending", !onBook);
        if (!onBook) {
            data.put("reconciled", false);
            data.put("note", "车尚未建账：票已入账，勾稽留到建账后或 UC-R2-3 重算时做（V51 不建 FK 的理由）");
            supportRepository.writeAudit(operator, ACTION_REPORT, TARGET_TYPE, vin, null,
                    "report_type=" + type + " 先于建账入账 report_no=" + cmd.reportNo(), null);
            return data;
        }

        // 修復歴取严（D06 §2.3「MAJOR 优先、UNKNOWN 不优于已知」）：票面说 MAJOR 而主数据不是，
        // 就把主数据抬到 MAJOR。这是唯一允许由票面改主数据属性的方向——它只会让估值更保守。
        // 反向（票面说 NONE、主数据是 MINOR）不回退：降级结论需要人工认定，属 R6 的差异处置。
        Optional<UsedVehicleAttrsView> current = usedVehicleRepository.findUsedAttributes(vin);
        if (current.isPresent() && reported != null) {
            UsedVehicleAttrsView v = current.get();
            RepairHistory merged = v.repairHistory() == null ? reported : v.repairHistory().stricterThan(reported);
            if (merged != v.repairHistory()) {
                usedVehicleRepository.updateUsedAttributes(vin, new UsedVehicleAttributes(
                        v.registrationPlate(), v.firstRegisteredOn(), v.inspectionExpiryDate(),
                        v.inspectionRemainingMonths(), merged, v.repairHistoryDesc(),
                        cmd.accidentHistoryFlag() != null ? cmd.accidentHistoryFlag() : v.accidentHistoryFlag(),
                        v.auctionGrade(), v.saleChannel(), v.warehouseCustodianCode(), v.binLocation(),
                        v.auctionLotId()));
                data.put("repairHistoryUpgradedTo", merged.name());
                supportRepository.writeAudit(operator, ACTION_ATTRS, TARGET_TYPE, vin,
                        "repair_history=" + v.repairHistory(), "repair_history=" + merged
                                + "（票面取严，D06 §2.3）", null);
            }
        }

        MileageResolutionPolicy.Decision decision = reconcileMileageLocked(vin, operator, ACTION_REPORT);
        data.put("reconciled", true);
        putMileageDecision(data, decision);
        return data;
    }

    /**
     * 重算某车的里程（UC-R2-3 / UC-R2-6 的独立入口）。
     *
     * <p>为什么单独给一个入口：票可以先于建账到（{@link #recordConditionReport} 里那条
     * {@code vehiclePending} 分支），WMS 入库确认也走同一张差异表——
     * 需要一个「把该车所有已知观测重新判一次」的动作，而不是每来一条数据就各自判一次。</p>
     */
    @Transactional
    public Map<String, Object> reconcileMileage(String vin, String operator) {
        if (!vehicleUnitRepository.existsByVin(vin)) {
            throw new BusinessException(ErrorCode.VEHICLE_001, "VIN 不在账上: " + vin);
        }
        MileageResolutionPolicy.Decision decision = reconcileMileageLocked(vin, operator, ACTION_MILEAGE);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("vin", vin);
        putMileageDecision(data, decision);
        return data;
    }

    /**
     * 把一次里程判定摊平成响应字段。
     *
     * <p>建账、票登记、手动重算三个入口共用同一组键名。之前各写一套
     * （{@code outcome} / {@code mileageOutcome}、{@code diffKm} / {@code mileageDiffKm}、
     * {@code exceedsThreshold} / {@code diffOpened}）：同一个概念在三个端点上叫三个名字，
     * 前端要写三套取值，冒烟也没法用一条断言覆盖「里程判定」这件事——
     * 而漏掉的那个键会安静地取到 {@code None}，看起来像「没有差异」。</p>
     */
    private static void putMileageDecision(Map<String, Object> data, MileageResolutionPolicy.Decision decision) {
        data.put("mileageOutcome", decision.outcome().name());
        data.put("mileage", decision.mileageKm());
        data.put("mileageSource", decision.source() == null ? null : decision.source().name());
        data.put("mileageDiffKm", decision.diffKm());
        data.put("mileageExceedsThreshold", decision.exceedsThreshold());
        // D06 §2.4「全为 UNKNOWN 时允许建账但禁止放款」——建账照成，阻断标记先给出，
        // 真正的闸门在 R5 的放款前置读它（本切片不假装闸门已经存在）。
        data.put("advanceBlockedByMileage", decision.blocksAdvance());
        data.put("mileageReasons", decision.reasons());
    }

    /** 把一辆车的全部里程证据（车况票）拿来判一次并落库。 */
    private MileageResolutionPolicy.Decision reconcileMileageLocked(String vin, String operator, String action) {
        UsedVehicleAttrsView view = usedVehicleRepository.findUsedAttributes(vin)
                .orElseThrow(() -> new BusinessException(ErrorCode.VEHICLE_001, "VIN 不在账上: " + vin));
        List<MileageSample> candidates = new ArrayList<>();
        for (VehicleConditionReport r : usedVehicleRepository.findConditionReports(vin)) {
            if (r.hasOdometerReading() && r.reportType() != null) {
                candidates.add(new MileageSample(r.reportType().mileageSourceOf(), r.odometerKm(),
                        r.inspectedOn(), r.idempotencySegment() == null ? "CRR#" + r.id() : r.idempotencySegment()));
            }
        }
        return applyMileage(vin, view.mileageSample(), candidates, operator, action);
    }

    /**
     * 里程判定 + 落库 + 差异 + 审计，一次做完。
     *
     * <p><b>不允许在别处写 {@code vehicle_units.mileage}</b>：本方法是唯一出口。
     * 差异只在「超阈值」时开（阈值来自 {@code used_config_params}，冒烟 U-R2 会改这个值验它真被读到）；
     * 采信与开差异是两件事——高优先实物观测胜出即回写，同时若差异超阈值照样开单，
     * 让「数被改了」和「改得有多大」都留下记录。</p>
     */
    private MileageResolutionPolicy.Decision applyMileage(String vin, MileageSample master,
                                                          List<MileageSample> candidates,
                                                          String operator, String action) {
        // 键缺失时取 0 而不是回落一个「看起来合理」的 1000：0 是最严的一侧（任何差异都开单），
        // 且库里没这行本来就该在冒烟里露出来，而不是被 Java 常量悄悄补上（V41 反对的就是这种补法）。
        int threshold = configRepository.findInt(UsedConfigKeys.MILEAGE_DIFF_THRESHOLD_KM).orElse(0);
        MileageResolutionPolicy.Decision decision =
                MileageResolutionPolicy.resolve(master, candidates, threshold);

        boolean wrote = decision.outcome() == MileageResolutionPolicy.Outcome.ADOPTED
                || decision.outcome() == MileageResolutionPolicy.Outcome.UNDETERMINED
                || (decision.outcome() == MileageResolutionPolicy.Outcome.KEPT_MASTER && master.mileageKm() == null);
        if (wrote) {
            usedVehicleRepository.applyMileageMaster(vin,
                    decision.source() == null ? MileageSource.UNKNOWN.name() : decision.source().name(),
                    decision.mileageKm(), decision.capturedOn());
        }

        if (decision.exceedsThreshold()) {
            UsedVehicleAttrsView v = usedVehicleRepository.findUsedAttributes(vin).orElse(null);
            String warehouse = v == null || v.warehouseCustodianCode() == null ? "UNKNOWN" : v.warehouseCustodianCode();
            int opened = usedVehicleRepository.openReconcileDiff(LocalDate.now(), warehouse, vin,
                    WmsDiffType.ODOMETER_DIFF, WmsDiffSeverity.WARNING,
                    "master=" + master.mileageKm() + "/" + master.source(),
                    "observed=" + decision.mileageKm() + "/" + decision.source()
                            + " ref=" + decision.evidenceRef() + " diff=" + decision.diffKm() + "km",
                    operator);
            log.info("[USED R2] 里程差异 vin={} diff={}km 阈值={} 新开差异={}（ODOMETER_DIFF）",
                    vin, decision.diffKm(), threshold, opened);
        }

        supportRepository.writeAudit(operator, action, TARGET_TYPE, vin,
                "mileage=" + master.mileageKm() + " source=" + master.source(),
                decision.outcome() + " mileage=" + decision.mileageKm() + " source=" + decision.source()
                        + " | " + String.join(" / ", decision.reasons()), null);
        return decision;
    }

    // ====================================================================
    // UC-R2-5 一车一证与换证链
    // ====================================================================

    /**
     * 首次登记車検証（UC-R2-5 的前半）。
     *
     * <p><b>一车一证的冲突在这里判</b>：V53 的 {@code uk_cert_active_vin} 是兜底，
     * 但直接让索引炸出来，接口只能回一句 duplicate key，操作员看不出是「同车已有证」还是「证号重号」。
     * 所以先查同号是否挂在别的 VIN 上 → {@code CERT_003}（証書番号与 VIN 不一致，D06 G-D1），
     * 同号同车则按幂等重放回 200，最后才查 {@code findActiveCertificate} → {@code CERT_002}。</p>
     *
     * <p><b>顺序不能倒</b>：先查在场证的话，同车同号的第二次提交会先撞上 CERT_002，
     * 幂等重放这条分支永远走不到——冒烟 R2「同证重推 → 200」实测到的就是这个（上一轮回 409）。</p>
     *
     * <p><b>有瑕疵不拒登记</b>：登记是记账，拒登记等于让这台车在系统里不存在，
     * 反而更容易被绕过（线下处理）。瑕疵照实入账并回 {@code pledgeBlocked=true}，
     * 阻断发生在设质与放款（{@code EncumbrancePolicy} / R5 闸门）。
     * 这是 D06 §2.1 I2「准入闸门」在本切片的落点，与红线 3「无質押不放款」不冲突。</p>
     */
    @Transactional
    public Map<String, Object> attachCertificate(String vin, CertificateDraft draft, String operator) {
        requireVehicleOnBook(vin);
        // 流水的核查来源在第一次写之前校验：非法词只能在 ck_cce_check_source 上撞出约束违例，
        // 那时证已经插进去了，回 400 还是回 500 取决于事务边界，操作员看到的是一句「系统错误」。
        String checkSource = checkSourceOf(draft.checkSource());
        VehicleCertificate cert = validateAndBuild(vin, draft, null, CertificateStatus.RECEIVED);

        Optional<VehicleCertificate> sameNo = usedVehicleRepository.findCertificateByNo(cert.getCertificateNo());
        if (sameNo.isPresent() && !vin.equals(sameNo.get().getVin())) {
            throw new BusinessException(ErrorCode.CERT_003,
                    "証書番号 " + cert.getCertificateNo() + " 已挂在 VIN " + sameNo.get().getVin()
                            + " 上，与本车 VIN " + vin + " 不一致",
                    Map.of("certificateNo", cert.getCertificateNo(), "boundVin", sameNo.get().getVin()));
        }
        if (sameNo.isPresent()) {
            // 同车同号重复提交 ⟹ 幂等：cert_key 唯一索引（uk_cert_key）本来就只允许一行。
            // 这里不区分「在场」与「已退场」：退场证同号重推同样是重放，回它的真实状态，
            // 由调用方决定要不要走 rebind——把它翻成 CERT_002 会让人以为能新建，而 uk_cert_key 不许。
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("idempotent", true);
            data.put("certificateId", sameNo.get().getId());
            data.put("certificateNo", sameNo.get().getCertificateNo());
            data.put("status", sameNo.get().getStatus() == null ? null : sameNo.get().getStatus().name());
            return data;
        }
        Optional<VehicleCertificate> active = usedVehicleRepository.findActiveCertificate(vin);
        if (active.isPresent()) {
            throw new BusinessException(ErrorCode.CERT_002,
                    "该 VIN 已有在场車検証: certificate_no=" + active.get().getCertificateNo()
                            + " status=" + active.get().getStatus() + "（换发走 rebind，不要重复登记）",
                    Map.of("vin", vin, "existingCertificateId", String.valueOf(active.get().getId()),
                            "existingCertificateNo", String.valueOf(active.get().getCertificateNo())));
        }

        VehicleCertificate stored;
        try {
            stored = usedVehicleRepository.insertCertificate(cert);
        } catch (DuplicateKeyException ex) {
            // 上面两次查询挡不住并发：两个会话同时判定「本车无在场证」，后插的那个撞
            // uk_cert_active_vin（或同号撞 uk_cert_key）。仓储层按本仓约定不翻译异常（它不抛
            // BusinessException），翻译只能用例做；不翻译的代价是冒烟实测的 500 SYS_001。
            // @Transactional 会回滚这次 insert——本方法在 insert 之前没有任何写，不存在半状态。
            throw new BusinessException(ErrorCode.CERT_002,
                    "该 VIN 已有在场車検証（并发登记，uk_cert_active_vin 挡住）: " + vin, Map.of("vin", vin));
        }
        appendCustody(stored, CertificateCustodyEventType.RECEIVED, null, CertificateStatus.RECEIVED,
                null, checkSource, operator, draft.reason());
        usedVehicleRepository.bindCertificate(vin, stored.getId());

        EncumbrancePolicy.Verdict verdict = EncumbrancePolicy.evaluate(List.of(cert.getEncumbranceStatus()));
        supportRepository.writeAudit(operator, ACTION_CERT, TARGET_TYPE, vin, null,
                "cert=" + stored.getCertificateNo() + " id=" + stored.getId()
                        + " encumbrance=" + stored.getEncumbranceStatus() + " clear=" + verdict.clear(), null);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("certificateId", stored.getId());
        data.put("certificateNo", stored.getCertificateNo());
        data.put("status", stored.getStatus().name());
        data.put("encumbrance", stored.getEncumbranceStatus() == null
                ? EncumbranceType.UNKNOWN.name() : stored.getEncumbranceStatus().name());
        data.put("pledgeBlocked", !verdict.clear());
        data.put("blockReasons", verdict.blockers());
        return data;
    }

    /**
     * 换发車検証（UC-R2-5 后半，D06 §2.2 签名里的 {@code operatorA} / {@code operatorB}）。
     *
     * <p><b>为什么换证要两个人</b>：D06 §2.3 末行「人工覆盖：任何静默覆盖 ＝ 禁止；
     * 覆盖必须走 UC-R2-5 / 二人 / 审计」。証書番号是权属核验的检索键，
     * 单人可改就等于单人可把一台有瑕疵的车换到一张干净的证上。</p>
     *
     * <p>同人自证回 {@code AUTH_003}，与 R1 的合规签认、双维评分同一条码（AGENTS.md §10 权限/文案同步）。</p>
     */
    @Transactional
    public Map<String, Object> rebindCertificate(String vin, CertificateDraft draft,
                                                 String operatorA, String operatorB) {
        requireVehicleOnBook(vin);
        if (operatorA == null || operatorB == null || operatorA.trim().equalsIgnoreCase(operatorB.trim())) {
            throw new BusinessException(ErrorCode.AUTH_003,
                    "换发車検証需要第二人签认，且不得与操作人相同（D06 §2.3 禁止静默覆盖）");
        }
        String reason = requireText(draft.reason(), "换发事由不能为空（无原因的换证在审计上等同于改数）");
        // 来源词与新证内容全部在写之前校验完：换发是「旧证退场 + 新证入账」两步写，
        // 任何一步之后才发现字段非法，都得靠事务回滚兜——回滚是对的，但错误信息会带上
        // 「为什么刚才那次写没生效」这种操作员不需要理解的噪音。
        String checkSource = checkSourceOf(draft.checkSource());

        VehicleCertificate old = usedVehicleRepository.findActiveCertificate(vin)
                .orElseThrow(() -> new BusinessException(ErrorCode.CERT_001,
                        "本车没有在场車検証，谈不上换发: " + vin));
        CertificateStateMachine.assertTransition(old.getStatus(), CertificateStatus.REISSUED);

        // 链的方向只有这一个：新证指向旧证（V52 predecessor_id，V53 禁止自引用）。
        // 同証書番号的「記載変更」不在本用例：uk_cert_key 会让第二行根本插不进来，
        // 那类变更必须 UPDATE 原行，属 R5 的登记委托范围，此处显式拒绝而不是静默当成新证。
        if (old.getCertificateNo().equals(trimToEmpty(draft.certificateNo()))) {
            throw new BusinessException(ErrorCode.VALID_001,
                    "换发必须给出新的証書番号；同番号变更属記載変更（UPDATE 原行），不在 UC-R2-5 范围",
                    Map.of("certificateNo", old.getCertificateNo()));
        }
        VehicleCertificate fresh = validateAndBuild(vin, draft, old.getId(), CertificateStatus.RECEIVED);

        // 先让旧证退场，再插新证。反过来的话新证 insert 当场撞 uk_cert_active_vin
        // ——旧行此刻仍是「在场」，同一 VIN 上等于有两张在场证（冒烟实测 DuplicateKeyException：
        // Key (vin)=… already exists）。这个顺序也是业务语义：换发是「以旧换新」，
        // 旧证不先让位，新证就没有入账的位置。
        // 先取快照再改状态：appendCustody 的 from_status 要写「换发之前」那张证处在什么状态，
        // 而 old 是同一个对象引用，setStatus 之后再也读不到旧值（上一轮就踩过这个自指错误）。
        CertificateStatus before = old.getStatus();
        old.setStatus(CertificateStatus.REISSUED);
        int updated = usedVehicleRepository.updateCertificate(old);
        if (updated == 0) {
            // 乐观锁挡住＝有人正在并发动这张证（换发/设质/灭失）。整个事务必须由 @Transactional 回滚，
            // 不能只回一句「没更新成功」——否则流水和审计会记下一次没有发生的换发。
            throw new BusinessException(ErrorCode.CONFLICT_001,
                    "旧証書并发变更，换发未生效（version 不匹配），请重新读取后重试",
                    Map.of("certificateId", String.valueOf(old.getId()), "vin", vin));
        }

        VehicleCertificate stored;
        try {
            stored = usedVehicleRepository.insertCertificate(fresh);
        } catch (DuplicateKeyException ex) {
            // 走到这里只剩两种可能：并发有人给本车收了另一张在场证（uk_cert_active_vin），
            // 或新証書番号与既有行重复（uk_cert_key，同番号記載変更已在上面 VALID_001 拦过）。
            // 两种都是「这张新证进不来」，回 CERT_002 并让事务把旧证的 REISSUED 一起回滚，
            // 保证不会出现「旧证已退、新证没来」的证链断层。
            throw new BusinessException(ErrorCode.CERT_002,
                    "换发未生效：本车已并发出现新的在场证，或新証書番号与既有行重复（uk_cert_active_vin / uk_cert_key）",
                    Map.of("vin", vin, "certificateNo", String.valueOf(fresh.getCertificateNo())));
        }
        appendCustody(old, CertificateCustodyEventType.AMENDED, before, before,
                null, checkSource, operatorA, reason);
        appendCustody(old, CertificateCustodyEventType.REISSUED, before, CertificateStatus.REISSUED,
                null, checkSource, operatorA, reason + "（第二人 " + operatorB + "）");
        appendCustody(stored, CertificateCustodyEventType.RECEIVED, null, CertificateStatus.RECEIVED,
                null, checkSource, operatorB, "承继 " + old.getCertificateNo());
        usedVehicleRepository.bindCertificate(vin, stored.getId());

        supportRepository.writeAudit(operatorA, ACTION_REBIND, TARGET_TYPE, vin,
                "certificate_no=" + old.getCertificateNo() + " status=" + before + " id=" + old.getId(),
                "certificate_no=" + stored.getCertificateNo() + " id=" + stored.getId()
                        + " predecessor=" + old.getId() + " coSignedBy=" + operatorB + " reason=" + reason, null);
        log.info("[USED R2] 换证 vin={} old={} new={} {}→{} by={}/{}", vin, old.getId(), stored.getId(),
                before, CertificateStatus.REISSUED, operatorA, operatorB);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("predecessorCertificateId", old.getId());
        data.put("predecessorCertificateNo", old.getCertificateNo());
        data.put("predecessorStatus", before.name());
        data.put("certificateId", stored.getId());
        data.put("certificateNo", stored.getCertificateNo());
        data.put("status", stored.getStatus().name());
        // 旧证若已设质，换发<b>不会</b>把质权搬过来——日本实务上要么重新取得对抗要件，要么做变更登记。
        // 这里只把「需要人工接上」这件事说清楚，不在 R2 自决质权承继（属 R5 登记委托）。
        data.put("pledgeCarryOverRequired", before == CertificateStatus.PLEDGED || wasPledged(old));
        return data;
    }

    /** 旧证是否曾经设质（时间戳比状态更稳：换成 REISSUED 之后 status 已不再是 PLEDGED）。 */
    private static boolean wasPledged(VehicleCertificate old) {
        return old.getPledgedAt() != null;
    }

    // ====================================================================
    // 详情（只读）
    // ====================================================================

    /**
     * 一辆 USED 车的完整视图：主数据 + 在场证 + 证链历史 + 车况票 + 瑕疵结论 + 未决差异。
     *
     * <p>{@code GET} 但带写语义的地方只有一处——不写。任何「读的时候顺手重算」的实现
     * 都会让这个端点变成并发写点，R6 的账实比对已经吃过一次这种亏。</p>
     */
    public Map<String, Object> detail(String vin) {
        VehicleUnit unit = vehicleUnitRepository.findByVin(vin)
                .orElseThrow(() -> new BusinessException(ErrorCode.VEHICLE_001, "VIN 不在账上: " + vin));
        UsedVehicleAttrsView attrs = usedVehicleRepository.findUsedAttributes(vin).orElse(null);
        VehicleCertificate active = usedVehicleRepository.findActiveCertificate(vin).orElse(null);
        List<VehicleCertificate> history = usedVehicleRepository.findCertificatesByVin(vin);
        List<VehicleConditionReport> reports = usedVehicleRepository.findConditionReports(vin);

        List<EncumbranceType> facts = new ArrayList<>();
        if (active != null) {
            facts.add(active.getEncumbranceStatus());
            for (CertificateCustodyEvent e : usedVehicleRepository.findCustodyEvents(active.getId())) {
                facts.add(e.encumbranceType());
            }
        }
        EncumbrancePolicy.Verdict verdict = EncumbrancePolicy.evaluate(facts);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("vin", vin);
        data.put("dealerId", unit.getDealerId());
        data.put("status", unit.getStatus() == null ? null : unit.getStatus().name());
        data.put("vehicleType", unit.getVehicleType());
        data.put("purchaseChannel", unit.getPurchaseChannel());
        data.put("invoiceAmount", unit.getInvoiceAmount());
        data.put("brand", unit.getBrand());
        data.put("model", unit.getModel());
        data.put("modelYear", unit.getModelYear());
        data.put("attributes", attrs);
        data.put("certificate", active);
        data.put("certificateChainCount", history.size());
        data.put("certificateHistory", history.stream().map(c -> Map.of(
                "id", String.valueOf(c.getId()),
                "certificateNo", String.valueOf(c.getCertificateNo()),
                "status", String.valueOf(c.getStatus()),
                "predecessorId", String.valueOf(c.getPredecessorId()))).toList());
        data.put("conditionReports", reports);
        data.put("encumbrance", verdict.conclusion().name());
        data.put("pledgeBlocked", !verdict.clear());
        data.put("blockReasons", verdict.blockers());
        data.put("openDiffCount", usedVehicleRepository.countOpenDiffs(vin));
        MileageSource ms = attrs == null ? null : MileageSource.from(attrs.mileageSource());
        data.put("mileageSource", ms == null ? null : ms.name());
        data.put("advanceBlockedByMileage", ms == null || ms == MileageSource.UNKNOWN);
        return data;
    }

    // ====================================================================
    // 内部工具
    // ====================================================================

    /** 校验命令并组装实体；状态与 predecessor 由调用方决定（首次登记 / 换发共用）。 */
    private VehicleCertificate validateAndBuild(String vin, CertificateDraft draft,
                                                Long predecessorId, CertificateStatus status) {
        String certNo = requireText(draft.certificateNo(), "証書番号不能为空");
        if (draft.inspectionExpiryDate() == null) {
            throw new BusinessException(ErrorCode.VALID_001,
                    "車検満了日不能为空（V52 列 NOT NULL：无车検到期日的証書不能作为建账依据）");
        }
        if (draft.issueDate() == null) {
            throw new BusinessException(ErrorCode.VALID_001, "発行年月日不能为空（V52 列 NOT NULL）");
        }
        if (draft.issueDate().isAfter(draft.inspectionExpiryDate())) {
            throw new BusinessException(ErrorCode.VALID_001,
                    "発行日晚于車検満了日: issue=" + draft.issueDate() + " expiry=" + draft.inspectionExpiryDate());
        }
        String draftVin = trimToEmpty(draft.vin());
        if (!draftVin.isEmpty() && !vin.equals(draftVin)) {
            throw new BusinessException(ErrorCode.CERT_003,
                    "路径 VIN " + vin + " 与报文 VIN " + draftVin + " 不一致",
                    Map.of("pathVin", vin, "bodyVin", draftVin));
        }
        VehicleCertificate c = new VehicleCertificate();
        c.setCertKey("CERT-" + certNo);
        c.setCertificateNo(certNo);
        c.setVin(vin);
        c.setRegistrationPlate(trim(draft.registrationPlate()));
        c.setOwnerName(trim(draft.ownerName()));
        c.setOwnerAddress(trim(draft.ownerAddress()));
        c.setUserName(trim(draft.userName()));
        c.setUserAddress(trim(draft.userAddress()));
        c.setFirstRegisteredOn(draft.firstRegisteredOn());
        c.setInspectionExpiryDate(draft.inspectionExpiryDate());
        c.setIssueDate(draft.issueDate());
        c.setSecurityType(parseWord("security_type", draft.securityType(),
                CertificateSecurityType::from, CertificateSecurityType.values()));
        c.setMortgageFlag(draft.mortgageFlag());
        // 未给结论时按 UNKNOWN 落库，<b>不落 NONE</b>：NONE 是「查过且干净」的结论，
        // 让缺字段自动变成放行结论，等于把 P-7 的举证责任倒置。
        // 非法词必须由这里翻成 VALID_001——EncumbranceType.from 抛的 IllegalArgumentException
        // 冒到全局处理器就是 500，操作员看到的是「系统错误」而不是「这个字段填错了」。
        EncumbranceType encumbrance = parseWord("encumbrance_status", draft.encumbranceStatus(),
                EncumbranceType::from, EncumbranceType.values());
        c.setEncumbranceStatus(encumbrance == null ? EncumbranceType.UNKNOWN : encumbrance);
        c.setHolderCustodianCode(trim(draft.holderCustodianCode()));
        c.setHolderType(trim(draft.holderType()));
        c.setCustodyLocation(trim(draft.custodyLocation()));
        c.setStatus(status);
        c.setPredecessorId(predecessorId);
        c.setEvidencePath(trim(draft.evidencePath()));
        c.setEvidenceDigest(trim(draft.evidenceDigest()));
        return c;
    }

    /** 追加一条 custody 流水（actor 固定 USER，外部方回执那条线属 R7 的 PARTNER 分支）。 */
    private void appendCustody(VehicleCertificate cert, CertificateCustodyEventType type,
                               CertificateStatus from, CertificateStatus to,
                               EncumbranceType encumbrance, String checkSource,
                               String actor, String reason) {
        usedVehicleRepository.appendCustodyEvent(new CertificateCustodyEvent(
                null, cert.getId(), type, from, to, encumbrance,
                checkSourceOf(checkSource),
                "USER", actor, OffsetDateTime.now(ZoneOffset.UTC),
                cert.getEvidencePath(), reason, null));
    }

    private void requireVehicleOnBook(String vin) {
        if (!vehicleUnitRepository.existsByVin(vin)) {
            throw new BusinessException(ErrorCode.VEHICLE_001, "VIN 不在账上: " + vin);
        }
    }

    private static ConditionReportType parseReportType(String raw) {
        ConditionReportType type;
        try {
            type = ConditionReportType.from(raw);
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(ErrorCode.VALID_001,
                    "非法的车况票类型: " + raw + "（合法值 AUCTION_INSPECTION / WMS_INBOUND_RECHECK / OWN_AUDIT）");
        }
        if (type == null) {
            throw new BusinessException(ErrorCode.VALID_001, "report_type 不能为空");
        }
        return type;
    }

    /**
     * 枚举词解析的统一收口：非法取值必须是 400 {@code VALID_001}，不能是 500。
     *
     * <p>不做这层包装的代价是冒烟实测出来的：非法词走到 {@code MileageSource.from} 抛
     * {@link IllegalArgumentException}，一路出 {@code GlobalExceptionHandler} 变成
     * {@code SYS_001}——操作员既看不出是哪个字段错，也不知道能填什么，只能翻服务端日志
     * （AGENTS §10「Controller 里不要 try-catch 成 200」说的是另一件事：这里换的是<b>码</b>，不是把错误吞掉）。</p>
     *
     * <p>各枚举自己的 {@code from()} 语义（空白回落 UNKNOWN、还是返回 null 表示「从未采集」）
     * 原样保留，这里只翻译异常，不重复实现字典。</p>
     */
    private static <E> E parseWord(String field, String raw, Function<String, E> parser, E[] allowed) {
        try {
            return parser.apply(raw);
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(ErrorCode.VALID_001,
                    "非法的 " + field + " 取值: " + raw + "（合法值 " + Arrays.toString(allowed) + "）");
        }
    }

    /** 保管流水的核查来源：库内 {@code ck_cce_check_source} 只有三值，写错会让整条换证事务回滚成 500。 */
    private static final List<String> CHECK_SOURCES = List.of("MANUAL", "REGISTRY_AGENT", "REGISTRY_API");

    private static String checkSourceOf(String raw) {
        if (raw == null || raw.isBlank()) {
            return "MANUAL";
        }
        String value = raw.trim().toUpperCase();
        if (!CHECK_SOURCES.contains(value)) {
            throw new BusinessException(ErrorCode.VALID_001,
                    "非法的 check_source 取值: " + raw + "（合法值 " + CHECK_SOURCES + "）");
        }
        return value;
    }

    private static String requireText(String value, String message) {
        if (value == null || value.trim().isEmpty()) {
            throw new BusinessException(ErrorCode.VALID_001, message);
        }
        return value.trim();
    }

    private static String trim(String value) {
        return value == null ? null : value.trim().isEmpty() ? null : value.trim();
    }

    private static String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    private static String upper(String value) {
        return value == null || value.isBlank() ? null : value.trim().toUpperCase();
    }
}

package com.company.floorplan.application.service;

import com.company.floorplan.domain.exception.BusinessException;
import com.company.floorplan.domain.exception.ErrorCode;
import com.company.floorplan.domain.model.CertificateCustodyEvent;
import com.company.floorplan.domain.model.CertificateStatus;
import com.company.floorplan.domain.model.ConditionReportType;
import com.company.floorplan.domain.model.CustodyActorType;
import com.company.floorplan.domain.model.CustodyEventType;
import com.company.floorplan.domain.model.Dealer;
import com.company.floorplan.domain.model.DealerStatus;
import com.company.floorplan.domain.model.InspectionCertificate;
import com.company.floorplan.domain.model.MileageSample;
import com.company.floorplan.domain.model.MileageSource;
import com.company.floorplan.domain.model.PurchaseChannel;
import com.company.floorplan.domain.model.RepairHistoryFlag;
import com.company.floorplan.domain.model.SaleChannel;
import com.company.floorplan.domain.model.UsedVehicleAttributes;
import com.company.floorplan.domain.model.VehicleConditionReport;
import com.company.floorplan.domain.model.VehicleUnit;
import com.company.floorplan.domain.repository.DealerRepository;
import com.company.floorplan.domain.repository.InspectionCertificateRepository;
import com.company.floorplan.domain.repository.SupportRepository;
import com.company.floorplan.domain.repository.UsedConfigRepository;
import com.company.floorplan.domain.repository.UsedVehicleAttributesRepository;
import com.company.floorplan.domain.repository.VehicleConditionReportRepository;
import com.company.floorplan.domain.repository.VehicleUnitRepository;
import com.company.floorplan.domain.service.CertificateStateMachine;
import com.company.floorplan.domain.service.MileageResolutionPolicy;
import com.company.floorplan.domain.service.RepairHistoryResolutionPolicy;
import com.company.floorplan.domain.service.RoleSeparationPolicy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * USED 车辆主数据用例（应用层；R2.1–R2.3 ＋ R2.5 换证链，UC-R2-1/2-2/2-3/2-5）。
 *
 * <p><b>入参形态与 D06 §2.2 的差异（已登记）</b>：D06 把入参写成
 * {@code RegisterUsedVehicleCmd}／{@code RecordConditionReportCmd}。本包不再造这两个 Cmd，
 * 而用既有领域视图 {@link UsedVehicleAttributes}（USED 列集，与 V50 一一对应）与
 * {@link VehicleConditionReport}（V51 票面）承载多数字段，其余 LEGACY 主数据列用原始参数。
 * 理由：Cmd 的字段与这两张视图完全重合，再造一份只是多一处需要同步的清单；
 * 而二十个原始参数会退化成一串位置参数（错位只静默写错值，不报错）。
 * 仓内既有应用服务全部是原始参数签名，本类与之相容。</p>
 *
 * <p><b>列所有权（并行开发的硬边界）</b>：金额基准列（{@code valuation_id}／
 * {@code prudent_value}／{@code ltv_*}／{@code topup_required_amount}）与
 * {@code wms_confirmed_at} 属 W-U2／W-U6，本类<b>一条都不写</b>；
 * {@code JdbcUsedVehicleAttributesRepository} 的 SET 清单已按同一边界裁剪，
 * 两边同时改才会漏，所以边界写在类注释里作为改动的检查项。</p>
 *
 * <p><b>禁止静默覆盖（R2.3 的核心验收点）</b>：三方数据不一致时，只有
 * "更可信来源胜出"这一种情况才写主数据，且写之前必建工单、必写审计；
 * 其余不一致一律只落工单（见 {@link #applyMileageObservation} 与
 * {@link #reconcileConditionReport}）。工单落点是既有 {@code alerts} 表——
 * 正式差异台账 {@code wms_reconcile_diffs} 属 W-U4 的 V66，跨包表本包不写（已登记为待收口项）。</p>
 *
 * <p><b>建账端点未开</b>：D07 §7.1 给本包的端点里没有
 * {@code POST /api/vehicles/used}（R2.1 的 API 侧属 R8 范围，卡面未指派给 W-U1），
 * 因此 {@link #registerUsedVehicle} 只有服务入口、没有 HTTP 入口。
 * 这是<b>未接线项而非占位实现</b>：方法本身完整可用，缺的只是端点、权限点与请求体，
 * 已在实现说明列为阻塞项。</p>
 *
 * <p><b>一次勾稽只写一次属性行</b>：里程与修復歴都可能改 {@code attributes}，
 * 而 {@code updateAttributes} 是整行 CAS。分两次写会让第二次拿到的版本号落在第一次之后，
 * 逻辑复杂且多一次冲突窗口——故勾稽内部先改内存对象、末尾统一落库一次
 * （{@link #reconcileConditionReport}）。</p>
 */
@Service
public class UsedVehicleMasterApplicationService {

    /** 分流键的 USED 取值（V40 的 {@code ck_vu_vehicle_type} 两值之一）。 */
    private static final String VEHICLE_TYPE_USED = "USED";

    /** 里程单位：V50 列注释定源"本期恒 KM，不做换算"。 */
    private static final String MILEAGE_UNIT_KM = "KM";

    private final VehicleUnitRepository vehicleUnitRepository;
    private final UsedVehicleAttributesRepository attributesRepository;
    private final VehicleConditionReportRepository conditionReportRepository;
    private final InspectionCertificateRepository certificateRepository;
    private final DealerRepository dealerRepository;
    private final UsedConfigRepository usedConfigRepository;
    private final SupportRepository supportRepository;

    /** 里程来源优先级（纯函数，无状态）。 */
    private final MileageResolutionPolicy mileagePolicy = new MileageResolutionPolicy();
    /** 修復歴取严（纯函数，无状态）。 */
    private final RepairHistoryResolutionPolicy repairPolicy = new RepairHistoryResolutionPolicy();

    public UsedVehicleMasterApplicationService(VehicleUnitRepository vehicleUnitRepository,
                                               UsedVehicleAttributesRepository attributesRepository,
                                               VehicleConditionReportRepository conditionReportRepository,
                                               InspectionCertificateRepository certificateRepository,
                                               DealerRepository dealerRepository,
                                               UsedConfigRepository usedConfigRepository,
                                               SupportRepository supportRepository) {
        this.vehicleUnitRepository = vehicleUnitRepository;
        this.attributesRepository = attributesRepository;
        this.conditionReportRepository = conditionReportRepository;
        this.certificateRepository = certificateRepository;
        this.dealerRepository = dealerRepository;
        this.usedConfigRepository = usedConfigRepository;
        this.supportRepository = supportRepository;
    }

    /**
     * USED 车辆建账（UC-R2-1）。
     *
     * <p><b>两次写、一个事务</b>：LEGACY 主数据列走既有
     * {@code VehicleUnitRepository.save}（那份 INSERT 的列清单一个字不改，L4 回归不破），
     * USED 专属列走 {@code UsedVehicleAttributesRepository.updateAttributes}
     * （乐观锁 CAS 于第一条写入后的 {@code version=0}）。拆成两条是既有表结构决定的：
     * USED 列在 V50 之后才存在，而既有 INSERT 属 LEGACY 路径，不能为了 USED 去改它。</p>
     *
     * <p><b>必填项按 D06 §2.1 的显式列举，不按"14 项"这个数字</b>：
     * 原文写「必填 14 项（…）」而括号里的清单以省略号收尾，需求书 R2.2 又写
     * 「字段范围以法务/业务确认结果裁剪」⟹ 确切项数未签字。本方法因此只强制
     * <em>清单里明确点名的</em>：登録番号／初度登録年月／車検満了日／里程三元组／修復歴，
     * 外加 R2.3 第一行（"VIN 不在落札数据里 → 拒建账"）在<b>本包可实现的形式</b>：
     * 采购渠道为拍卖来源时必须带落札引用（{@code auction_lot_id}）。
     * 評価点（{@code auction_grade}）<b>不设必填</b>：WHOLESALE／TRADE_IN 来源根本没有它。</p>
     *
     * <p><b>幂等</b>：D06 §2.5 的键是 {@code VEH:{vin}}，而 {@code vehicle_units} 的主键就是 VIN，
     * 没有 {@code idempotency_key}／指纹列 ⟹ 同 VIN 二次进来只能判重复建账
     * （{@code VEHICLE_003}），做不到"内容相同就回首次结果"。这是键语义与表结构的既有落差，
     * 已登记；真要做建账级重放需要在 USED 段加两列，本包不自决扩列。</p>
     *
     * @param attributes      USED 属性（含 vin；里程三元组、車検、修復歴按上面口径校验）
     * @param certificate     随行車検証（可空；非空时同事务建首证 {@code RECEIVED} ＋一条收证事件并回绑）
     * @param dealerId        所属车商（必须 {@code ACTIVE}）
     * @param brand           品牌（LEGACY 列）
     * @param model           车型（LEGACY 列）
     * @param modelYear       年款（LEGACY 列）
     * @param color           颜色（LEGACY 列）
     * @param supplierName    供应商名称（LEGACY 列，可空）
     * @param invoiceAmount   发票／落札金额（JPY 整数，可空按 0 记）。
     *                        仍写既有 {@code invoice_amount}，但 USED 域不得拿它作放款基准（V50 列注释）
     * @param purchaseChannel 采购渠道（D00 §8.1 五值；R2.1 要求强枚举校验）
     * @param channelNote     渠道备注（{@code OTHER} 时用；只进审计，见 {@link #requireVehicleFacts}）
     * @param actor           操作人
     * @param traceId         链路号
     * @return 建账后的 USED 属性视图（含库侧最新 version）
     */
    @Transactional
    public UsedVehicleAttributes registerUsedVehicle(UsedVehicleAttributes attributes,
                                                     InspectionCertificate certificate,
                                                     Long dealerId, String brand, String model,
                                                     Integer modelYear, String color, String supplierName,
                                                     Long invoiceAmount, String purchaseChannel,
                                                     String channelNote, String actor, String traceId) {
        if (attributes == null || blankToNull(attributes.getVin()) == null) {
            throw new BusinessException(ErrorCode.VALID_001, "USED 建账缺少 vin");
        }
        String vin = attributes.getVin().trim().toUpperCase();
        attributes.setVin(vin);
        Dealer dealer = requireActiveDealer(dealerId);
        PurchaseChannel channel = parseEnum(PurchaseChannel.class, purchaseChannel, "采购渠道");
        requireVehicleFacts(attributes, channel);
        if (vehicleUnitRepository.existsByVin(vin)) {
            throw new BusinessException(ErrorCode.VEHICLE_003, Map.of(
                    "vin", vin,
                    "reason", "DUPLICATE_VEHICLE_REGISTRATION",
                    "detail", "同 VIN 已存在台账；VEH:{vin} 的幂等语义在 vehicle_units 没有幂等列时"
                            + "只能判重复建账（见类注释）"));
        }

        // ---- 第一步：LEGACY 主数据行（复用既有 save，不改它的列清单）----
        VehicleUnit unit = new VehicleUnit();
        unit.setVin(vin);
        unit.setDealerId(dealer.getId());
        unit.setBrand(blankToNull(brand));
        unit.setModel(blankToNull(model));
        unit.setModelYear(modelYear);
        // UNKNOWN 里程在库里的表达是 mileage IS NULL（V50 的 ck_vu_mileage_unknown），
        // 所以主数据里程列只写"可信的读数"；不可信时留空而不是写 0——
        // "0 km"与"不知道"在 D08 §4.1 取带时是两个结论。
        unit.setMileage(attributes.getMileageSource() == MileageSource.UNKNOWN ? null : attributes.getMileage());
        unit.setColor(blankToNull(color));
        unit.setVehicleType(VEHICLE_TYPE_USED);
        unit.setSupplierName(blankToNull(supplierName));
        unit.setPurchaseChannel(channel.name());
        unit.setInvoiceAmount(invoiceAmount == null ? 0L : invoiceAmount);
        // 三个 NOT NULL 计数器必须显式给 0：既有 INSERT 把它们列进了列清单，
        // 显式传 NULL 时库侧 DEFAULT 不生效，会直接撞非空约束（AdvanceApplicationService 同样处理）。
        unit.setAgingDays(0);
        unit.setOverdueDays(0);
        unit.setAuditMismatchCount(0);
        unit.registerLedger();
        vehicleUnitRepository.save(unit);

        // ---- 第二步：随行車検証（先建证，才能把 id 绑进属性行）----
        Long certificateId = null;
        if (certificate != null) {
            certificateId = createFirstCertificate(vin, certificate, attributes, actor, traceId).getId();
            attributes.setInspectionCertificateId(certificateId);
        }

        // ---- 第三步：USED 专属列（一条 UPDATE，CAS 于 version=0）----
        attributes.setVehicleType(VEHICLE_TYPE_USED);
        attributes.setMileageUnit(MILEAGE_UNIT_KM);
        if (attributes.getSaleChannel() == null) {
            // 销售侧缺省记"批发转售"（USED 库存融资的主去向）；这是枚举缺省值不是数值口径，
            // 调用方显式给了就按给的落。
            attributes.setSaleChannel(SaleChannel.WHOLESALE_RESELL);
        }
        // 主数据里程与属性视图同源：属性行写的是既有 mileage 列本身（D04 §5.1「唯一落点」），
        // 第一步已经落过值，这里只是让属性视图与之一致，不产生第二次写。
        attributes.setMileage(unit.getMileage());
        boolean written = attributesRepository.updateAttributes(attributes, unit.getVersion());
        if (!written) {
            // 刚插完的行版本必然为 0；这里失败只可能是同 VIN 的并发建账先走了一步。
            // 整个事务回滚，不留"有主数据行、没 USED 属性列"的半条账。
            throw new BusinessException(ErrorCode.CONFLICT_001, Map.of(
                    "vin", vin, "reason", "USED_ATTRIBUTES_WRITE_FAILED",
                    "expectedVersion", String.valueOf(unit.getVersion())));
        }

        supportRepository.writeAudit(actor, "REGISTER_USED_VEHICLE", "VEHICLE", vin, null,
                "dealerId=" + dealer.getId() + ",dealerCode=" + dealer.getDealerCode()
                        + ",channel=" + channel.name()
                        + ",invoiceAmount=" + unit.getInvoiceAmount()
                        + ",plate=" + attributes.getRegistrationPlate()
                        + ",firstRegisteredOn=" + attributes.getFirstRegisteredOn()
                        + ",inspectionExpiry=" + attributes.getInspectionExpiryDate()
                        + ",mileage=" + attributes.getMileage() + "(" + attributes.getMileageSource() + ")"
                        + ",repairHistory=" + nameOf(attributes.getRepairHistory())
                        + ",saleChannel=" + nameOf(attributes.getSaleChannel())
                        + (channel != PurchaseChannel.OTHER ? ""
                        : ",channelNote=" + requireText(channelNote, "OTHER 渠道备注"))
                        + (certificateId == null ? "" : ",certificateId=" + certificateId),
                traceId);
        return attributesRepository.findByVin(vin).orElse(attributes);
    }

    /**
     * 登记一张車況票（UC-R2-2），并在同一事务里做 R2.3 的属性勾稽。
     *
     * <p><b>票是事实，不是更正工具</b>：{@code vehicle_condition_reports} 是 append-only，
     * 更正＝补录新票（仓储没有 update）。本方法对主数据只做 D06 §2.3 明文许可的两件写：
     * 里程按来源优先级采纳、修復歴取严；其余不一致只建工单。</p>
     *
     * <p><b>幂等的两条路径（V51 的列形状决定的）</b>：
     * {@code report_no} 在库里<b>可空</b>（列注释：自盘票常无外部票号），
     * {@code uk_vcr_report_no} 也因此做成部分唯一索引（{@code WHERE report_no IS NOT NULL}）。于是：
     * <ul>
     *   <li>有票号 → 键 {@code CRR:{report_no}}：先探测，同票面回首次结果，
     *       异票面抛 {@code CONFLICT_001}；并发穿过探测时由索引兜底成 {@code CONFLICT_001}。</li>
     *   <li>无票号（自盘）→ 库里<b>没有任何去重约束可兜</b>，只能用
     *       {@code (vin, report_type, inspected_on)} 最近一票做应用层比对。
     *       这是"尽力去重"，不是幂等保证：两个并发提交会各落一行。缺口已登记。</li>
     * </ul>
     * 重放路径<b>不重复勾稽</b>——同一张票建两次差异工单，会把工单池变成计数器。</p>
     *
     * @param report  票面（vin／reportType／inspectedOn 必填，reportNo 可空）
     * @param actor   操作人
     * @param traceId 链路号
     * @return 票行＋本次勾稽结论
     */
    @Transactional
    public ReportOutcome recordConditionReport(VehicleConditionReport report, String actor, String traceId) {
        if (report == null || blankToNull(report.getVin()) == null) {
            throw new BusinessException(ErrorCode.VALID_001, "車況票缺少 vin");
        }
        String vin = report.getVin().trim().toUpperCase();
        report.setVin(vin);
        ConditionReportType reportType = report.getReportType();
        if (reportType == null) {
            throw new BusinessException(ErrorCode.VALID_001, Map.of(
                    "vin", vin, "reason", "REPORT_TYPE_REQUIRED",
                    "detail", "report_type 三值必填（V51 ck_vcr_report_type NOT NULL）：検査票／入库复核／自盘"));
        }
        // inspected_on 在 V51 是可空列，但勾稽的"同优先级取最新"（D06 §2.4）没有検査日就无从排起，
        // 且无票号时的应用层去重也靠它——故应用层收紧为必填（与 V50 里程列同一手法：列可空、写入必填）。
        if (report.getInspectedOn() == null) {
            throw new BusinessException(ErrorCode.VALID_001, Map.of(
                    "vin", vin, "reason", "INSPECTED_ON_REQUIRED",
                    "detail", "検査日缺失时无法参与优先级排序与去重"));
        }
        String reportNo = blankToNull(report.getReportNo());
        report.setReportNo(reportNo);
        requireVehicle(vin);

        Optional<VehicleConditionReport> replay = findReplayCandidate(report, reportNo);
        if (replay.isPresent()) {
            VehicleConditionReport first = replay.get();
            if (Objects.equals(fingerprintOf(first), fingerprintOf(report))) {
                return new ReportOutcome(first, List.of(), false);
            }
            throw new BusinessException(ErrorCode.CONFLICT_001, Map.of(
                    "idempotencyKey", reportNo != null ? "CRR:" + reportNo
                            : "VIN+TYPE+DATE:" + vin + ":" + reportType.name() + ":" + report.getInspectedOn(),
                    "reason", "SAME_REPORT_KEY_DIFFERENT_CONTENT",
                    "vin", vin,
                    "detail", reportNo == null
                            ? "同日同票种已有另一张自盘票，内容不同——请给出 report_no 以区分"
                            : "同票号不同票面"));
        }

        report.setCreatedBy(blankToNull(actor) == null ? "system" : actor.trim());
        VehicleConditionReport saved = conditionReportRepository.insert(report);
        supportRepository.writeAudit(actor, "CONDITION_REPORT_REGISTERED", "VEHICLE", vin, null,
                "reportId=" + saved.getId() + ",reportNo=" + reportNo + ",type=" + reportType.name()
                        + ",inspectedOn=" + report.getInspectedOn()
                        + ",odometerKm=" + report.getOdometerKm()
                        + ",repairHistory=" + nameOf(report.getRepairHistory())
                        + ",accidentHistory=" + report.getAccidentHistory(), traceId);

        List<ReconcileDiff> diffs = reconcileConditionReport(saved, actor, traceId);
        boolean masterUpdated = diffs.stream().anyMatch(ReconcileDiff::appliedToMaster);
        return new ReportOutcome(saved, diffs, masterUpdated);
    }

    /**
     * 换证：把有効車検証换成新証書（UC-R2-5，R2.4 的换绑落点）。
     *
     * <p><b>一个写入集四步，缺一不可</b>（V52/V53 的不变式决定的）：
     * 旧证 {@code →REISSUED}（腾出 {@code uk_cert_active_vin}）→ 新证以 {@code RECEIVED}
     * 插入并带 {@code predecessor_id} → 两条 custody 事件 → {@code vehicle_units} 的
     * {@code inspection_certificate_id} 与 {@code inspection_expiry_date} 换绑。
     * 少第二步会撞一车一证唯一索引；少第三条事件，"旧证现在在哪"永远没有答案；
     * 少第四步，主数据还指着废证。</p>
     *
     * <p><b>二人是硬要求</b>：D06 §2.3「人工覆盖禁止；覆盖必须走 UC-R2-5／二人／审计」。
     * 这里是自然人级 SOD，复用 {@link RoleSeparationPolicy#assertNoSelfApproval}
     * （AGENTS.md §8：不新建平行实现）。该校验对 {@code approver == null} 不拦，
     * 所以复核人先做非空校验，否则"没填复核人"会绕过二人规则。</p>
     *
     * <p><b>换证时限不做判定</b>：届出期限属 {@code [待定源]}，本包按"无超时"实现——
     * 不拒绝、不预警，只留链路（已登记为待签字项）。</p>
     *
     * @param vin                  车辆 VIN
     * @param newCertificateNo     新証書番号
     * @param issueDate            新証交付日（V52 NOT NULL）
     * @param inspectionExpiryDate 新証的車検満了日（V52 NOT NULL，同步进主数据）
     * @param ownerName            所有者氏名
     * @param userName             使用者氏名
     * @param evidencePath         扫描件路径
     * @param reason               换证原因（必填，进事件与审计）
     * @param operatorA            发起人
     * @param operatorB            复核人（不得与发起人同一人）
     * @param traceId              链路号（同时作事件 {@code trace_id}）
     * @return 新証書行
     */
    @Transactional
    public InspectionCertificate rebindCertificate(String vin, String newCertificateNo, LocalDate issueDate,
                                                   LocalDate inspectionExpiryDate, String ownerName,
                                                   String userName, String evidencePath, String reason,
                                                   String operatorA, String operatorB, String traceId) {
        String normalizedVin = requireText(vin, "VIN").toUpperCase();
        String newNo = requireText(newCertificateNo, "新車検証証書番号");
        String why = requireText(reason, "换证原因");
        String initiator = requireText(operatorA, "换证发起人");
        String approver = requireText(operatorB, "换证复核人");
        if (issueDate == null || inspectionExpiryDate == null) {
            throw new BusinessException(ErrorCode.VALID_001, Map.of(
                    "vin", normalizedVin, "reason", "CERTIFICATE_DATES_REQUIRED",
                    "detail", "新証的交付日与車検満了日是 V52 的 NOT NULL 列，换证必须给全"));
        }
        RoleSeparationPolicy.assertNoSelfApproval(initiator, approver, "車検証换证");
        VehicleUnit unit = requireVehicle(normalizedVin);

        InspectionCertificate current = certificateRepository.findActiveByVin(normalizedVin)
                .orElseThrow(() -> new BusinessException(ErrorCode.CERT_001, Map.of(
                        "vin", normalizedVin,
                        "reason", "NO_ACTIVE_CERTIFICATE_TO_REBIND",
                        "detail", "换证要求已有有効証書；首次收证走建账随行証書，或由 W-U4 的收证入口")));
        if (newNo.equalsIgnoreCase(current.getCertificateNo())) {
            // 同号"换证"实际是記載変更（AMENDED）：新行会撞 uk_cert_key。
            // 提前给可分辨的错，比让唯一索引抛一个泛化冲突更容易排障。
            throw new BusinessException(ErrorCode.CONFLICT_001, Map.of(
                    "vin", normalizedVin, "reason", "SAME_CERTIFICATE_NO",
                    "detail", "証書番号未变的情形属記載変更，不是换证"));
        }

        CertificateStateMachine.requireTransition(current.getStatus(), CertificateStatus.REISSUED);
        boolean superseded = certificateRepository.transitStatus(current.getId(),
                current.getStatus().name(), CertificateStatus.REISSUED.name(),
                current.getVersion() == null ? 0 : current.getVersion());
        if (!superseded) {
            throw new BusinessException(ErrorCode.CONFLICT_001, Map.of(
                    "certificateId", current.getId(), "reason", "CERT_STATE_CHANGED",
                    "expectedVersion", String.valueOf(current.getVersion())));
        }

        InspectionCertificate next = new InspectionCertificate();
        next.setCertKey("CERT-" + newNo);
        next.setCertificateNo(newNo);
        next.setVin(normalizedVin);
        // 登録番号默认沿用旧証（换証通常不换牌；真换了牌属登録事項変更，由 R4 的登记线改）。
        next.setRegistrationPlate(current.getRegistrationPlate());
        next.setOwnerName(blankToNull(ownerName));
        next.setUserName(blankToNull(userName));
        next.setInspectionExpiryDate(inspectionExpiryDate);
        next.setIssueDate(issueDate);
        next.setStatus(CertificateStatus.RECEIVED);
        next.setPredecessorId(current.getId());
        next.setEvidencePath(blankToNull(evidencePath));
        InspectionCertificate saved = certificateRepository.insert(next);

        appendCustodyEvent(current.getId(), CustodyEventType.REISSUED, current.getStatus(),
                CertificateStatus.REISSUED, initiator, "换证：" + why, evidencePath, traceId);
        appendCustodyEvent(saved.getId(), CustodyEventType.RECEIVED, null,
                CertificateStatus.RECEIVED, initiator,
                "换证新証書收讫，前手 certificateId=" + current.getId() + "；" + why, evidencePath, traceId);

        UsedVehicleAttributes attributes = attributesRepository.findByVin(normalizedVin).orElse(null);
        if (attributes == null) {
            // LEGACY 车辆没有 USED 属性行（V50 列全可空）：証書链照建，主数据换绑无从下手。
            // 这不是错误——给存量新车补 USED 列不在本包范围，但必须留痕，
            // 否则"换证成功而台账仍指旧証"这件事只在人肉对账时才暴露。
            supportRepository.writeAudit(approver, "CERTIFICATE_REBOUND_WITHOUT_ATTRIBUTES", "VEHICLE",
                    normalizedVin, "certificateId=" + current.getId(),
                    "certificateId=" + saved.getId() + ",detail=无 USED 属性行，未换绑 inspection_certificate_id",
                    traceId);
        } else {
            attributes.setInspectionCertificateId(saved.getId());
            attributes.setInspectionExpiryDate(inspectionExpiryDate);
            boolean rebound = attributesRepository.updateAttributes(attributes,
                    attributes.getVersion() == null ? 0 : attributes.getVersion());
            if (!rebound) {
                throw new BusinessException(ErrorCode.CONFLICT_001, Map.of(
                        "vin", normalizedVin, "reason", "VEHICLE_CERT_REBIND_CONFLICT",
                        "expectedVersion", String.valueOf(attributes.getVersion())));
            }
        }

        supportRepository.writeAudit(approver, "CERTIFICATE_REBOUND", "VEHICLE", normalizedVin,
                "certificateId=" + current.getId() + ",no=" + current.getCertificateNo()
                        + ",status=" + current.getStatus().name() + ",expiry=" + current.getInspectionExpiryDate(),
                "certificateId=" + saved.getId() + ",no=" + newNo + ",status=RECEIVED"
                        + ",predecessorId=" + current.getId() + ",expiry=" + inspectionExpiryDate
                        + ",reason=" + why + ",initiator=" + initiator + ",dealerId=" + unit.getDealerId(),
                traceId);
        return saved;
    }

    /**
     * 里程观测勾稽（R2.3 的里程行；D06 §2.2 的
     * {@code syncFromAuctionLot}/{@code syncFromWmsInbound} 的共用内核）。
     *
     * <p><b>为什么签名只到"给一条观测"这一层</b>：D06 那两个方法要先按 {@code lotKey}／
     * {@code inboundConfirmId} 读 {@code auction_lots}（W-U4 写）与
     * {@code wms_inventory_*}（W-U6 写）——都不是本包号段内的表。跨包读别人的写入口
     * 会让同一份事实在两个包各写一次，本方法改为把"已取到的观测"作为入参，
     * 取数那一段等两边表就绪后由调用方接上（实现说明已登记为阻塞项）。</p>
     *
     * <p><b>覆盖规则（不猜）</b>：只有当本次观测经 {@link MileageResolutionPolicy}
     * 胜出、且胜者就是本次观测时才写主数据；主数据（或另一条更高优先来源）胜出时只建工单。
     * 差异是否"过大"读参数表 {@code floorplan.used.mileage-diff-threshold-km}；
     * 参数未签字时<b>只判来源降级、不判数值差</b>，不为 D06 §2.3 的 1,000km 造 Java 常量。</p>
     *
     * @param vin        车辆 VIN
     * @param source     观测来源（必填；{@code UNKNOWN} 表示"这次采集不可信"）
     * @param mileageKm  观测读数（{@code UNKNOWN} 时必须为 null）
     * @param capturedOn 观测时点
     * @param bizDate    业务日（取参数表用；缺省取 {@code capturedOn}）
     * @param actor      操作人
     * @param traceId    链路号
     * @return 本次勾稽结论（空列表＝主数据与观测一致，无事发生）
     */
    @Transactional
    public List<ReconcileDiff> applyMileageObservation(String vin, MileageSource source, Integer mileageKm,
                                                       LocalDate capturedOn, LocalDate bizDate,
                                                       String actor, String traceId) {
        if (blankToNull(vin) == null) {
            throw new BusinessException(ErrorCode.VALID_001, "里程勾稽缺少 vin");
        }
        String normalizedVin = vin.trim().toUpperCase();
        UsedVehicleAttributes attributes = attributesRepository.findByVin(normalizedVin)
                .orElseThrow(() -> new BusinessException(ErrorCode.VEHICLE_001,
                        "车辆不存在或未建 USED 属性，无法勾稽里程：" + normalizedVin));
        LocalDate date = bizDate != null ? bizDate : capturedOn;
        List<ReconcileDiff> diffs = new ArrayList<>();
        boolean changed = resolveMileageInto(attributes, source, mileageKm, capturedOn,
                thresholdKm(date), diffs, normalizedVin);
        writeAttributesIfChanged(attributes, changed, normalizedVin, actor, traceId,
                "VEHICLE_MILEAGE_RESOLVED", "mileage=" + attributes.getMileage()
                        + "(" + nameOf(attributes.getMileageSource()) + "),capturedOn=" + capturedOn);
        raiseAlerts(diffs, normalizedVin, alertEnabled(date), actor, traceId);
        return diffs;
    }

    // =====================================================================
    // 建账校验与内部步骤
    // =====================================================================

    /**
     * R2.1／R2.2 的必填校验（清单口径见 {@link #registerUsedVehicle} 注释）。
     *
     * <p><b>{@code OTHER} 的"必须带备注"落不到列上</b>：V40 的列注释把这条写成 R2.1 验收点，
     * 但 {@code vehicle_units} 没有任何备注／说明列（D04 §5.1 未给，本包不自造列）。
     * 现在的处理是：备注作必填参数收下、进审计（{@code REGISTER_USED_VEHICLE} 的
     * {@code channelNote}），同时要求 {@code bin_location} 作为最低限度的实控说明。
     * "渠道备注列"是表结构缺口，已登记待收口方决定是否补列。</p>
     *
     * @param attributes 属性视图
     * @param channel    采购渠道
     */
    private void requireVehicleFacts(UsedVehicleAttributes attributes, PurchaseChannel channel) {
        List<String> missing = new ArrayList<>();
        if (blankToNull(attributes.getRegistrationPlate()) == null) {
            missing.add("registration_plate（登録番号）");
        }
        if (attributes.getFirstRegisteredOn() == null) {
            missing.add("first_registered_on（初度登録年月）");
        }
        if (attributes.getInspectionExpiryDate() == null) {
            missing.add("inspection_expiry_date（車検満了日）");
        }
        if (attributes.getMileageSource() == null) {
            missing.add("mileage_source（里程来源）");
        }
        if (attributes.getMileageCapturedOn() == null && attributes.getMileageSource() != MileageSource.UNKNOWN) {
            missing.add("mileage_captured_on（里程采集时点）");
        }
        if (attributes.getRepairHistory() == null) {
            missing.add("repair_history（修復歴）");
        }
        if (channel.isAuctionSourced() && attributes.getAuctionLotId() == null) {
            missing.add("auction_lot_id（落札引用，R2.3「VIN 不在落札数据里 → 拒建账」）");
        }
        if (channel == PurchaseChannel.OTHER && blankToNull(attributes.getBinLocation()) == null) {
            missing.add("bin_location（OTHER 渠道的最低实控说明，见方法注释）");
        }
        if (!missing.isEmpty()) {
            throw new BusinessException(ErrorCode.VALID_001, Map.of(
                    "reason", "USED_REGISTRATION_FIELDS_MISSING",
                    "missing", String.join("、", missing)));
        }
        validateMileageTriple(attributes.getMileageSource(), attributes.getMileage(), attributes.getVin());
    }

    /**
     * 里程三元组的内部一致性：{@code UNKNOWN} 必须没有读数（V50 的 {@code ck_vu_mileage_unknown}）。
     *
     * <p>应用层先拦一条，是为了让调用方拿到 400 而不是库侧约束错。
     * 同一口径两处实现是有意的：库侧是旁路 SQL 的最后防线，应用侧是给用户的解释。</p>
     */
    private void validateMileageTriple(MileageSource source, Integer mileage, String vin) {
        if (source == null) {
            throw new BusinessException(ErrorCode.VALID_001, Map.of(
                    "vin", String.valueOf(vin), "reason", "MILEAGE_SOURCE_REQUIRED",
                    "detail", "无来源的读数不参与优先级判定（给裸数值配一个虚拟来源＝伪造里程可追溯，"
                            + "正是 R2.3 要拦的写法）"));
        }
        if (source == MileageSource.UNKNOWN && mileage != null) {
            throw new BusinessException(ErrorCode.VALID_001, Map.of(
                    "vin", String.valueOf(vin), "reason", "UNKNOWN_MILEAGE_MUST_BE_NULL",
                    "detail", "来源为 UNKNOWN 时里程必须留空（V50 ck_vu_mileage_unknown）",
                    "incoming", String.valueOf(mileage)));
        }
        if (source != MileageSource.UNKNOWN && mileage == null) {
            throw new BusinessException(ErrorCode.VALID_001, Map.of(
                    "vin", String.valueOf(vin), "reason", "MILEAGE_REQUIRED",
                    "detail", "来源为 " + source.name() + " 时必须给出读数；没有读数请如实记 UNKNOWN"));
        }
    }

    /**
     * 建账随行証書：插入 {@code RECEIVED} 証書 ＋一条收证事件。
     *
     * @param vin        车辆 VIN
     * @param source     调用方给的証書草票
     * @param attributes 同批属性（登録番号／車検満了日的回退来源）
     * @param actor      操作人
     * @param traceId    链路号
     * @return 带 ID 的証書
     */
    private InspectionCertificate createFirstCertificate(String vin, InspectionCertificate source,
                                                         UsedVehicleAttributes attributes,
                                                         String actor, String traceId) {
        String certificateNo = requireText(source.getCertificateNo(), "車検証証書番号");
        LocalDate expiry = source.getInspectionExpiryDate() != null
                ? source.getInspectionExpiryDate() : attributes.getInspectionExpiryDate();
        if (source.getIssueDate() == null || expiry == null) {
            // V52 把 issue_date / inspection_expiry_date 定成 NOT NULL：
            // 没有満了日的証書无法参与任何判定（CERT_004 的输入），所以在这里就拒。
            throw new BusinessException(ErrorCode.VALID_001, Map.of(
                    "vin", vin, "certificateNo", certificateNo, "reason", "CERTIFICATE_DATES_REQUIRED",
                    "detail", "随行証書缺交付日或車検満了日（V52 NOT NULL）"));
        }
        InspectionCertificate certificate = new InspectionCertificate();
        certificate.setCertKey("CERT-" + certificateNo);
        certificate.setCertificateNo(certificateNo);
        certificate.setVin(vin);
        certificate.setRegistrationPlate(blankToNull(source.getRegistrationPlate()) != null
                ? source.getRegistrationPlate().trim() : attributes.getRegistrationPlate());
        certificate.setOwnerName(blankToNull(source.getOwnerName()));
        certificate.setUserName(blankToNull(source.getUserName()));
        certificate.setInspectionExpiryDate(expiry);
        certificate.setIssueDate(source.getIssueDate());
        certificate.setStatus(CertificateStatus.RECEIVED);
        certificate.setPredecessorId(null);
        certificate.setEvidencePath(blankToNull(source.getEvidencePath()));
        InspectionCertificate saved = certificateRepository.insert(certificate);
        appendCustodyEvent(saved.getId(), CustodyEventType.RECEIVED, null, CertificateStatus.RECEIVED,
                actor, "USED 建账随行收证", source.getEvidencePath(), traceId);
        return saved;
    }

    /**
     * 追加一条 custody 事件。
     *
     * <p>{@code actor_type} 固定 {@code USER}：本包的收证／换证只有自然人入口，
     * {@code PARTNER} 那条通道属 W-U4 的令牌回执侧，不在本号段。</p>
     */
    private void appendCustodyEvent(Long certificateId, CustodyEventType eventType,
                                    CertificateStatus fromStatus, CertificateStatus toStatus,
                                    String actor, String reason, String evidencePath, String traceId) {
        CertificateCustodyEvent event = new CertificateCustodyEvent();
        event.setCertificateId(certificateId);
        event.setEventType(eventType);
        event.setFromStatus(fromStatus);
        event.setToStatus(toStatus);
        event.setActorType(CustodyActorType.USER);
        event.setActor(blankToNull(actor) == null ? "system" : actor.trim());
        event.setReason(blankToNull(reason));
        event.setEvidencePath(blankToNull(evidencePath));
        event.setTraceId(blankToNull(traceId));
        certificateRepository.appendCustodyEvent(event);
    }

    /**
     * 票面 → 主数据的勾稽（D06 §2.3 表逐行落；属性行只在末尾写一次）。
     *
     * <p>本包可实现的四行：里程（{@link #resolveMileageInto}）、修復歴（取严写入）、
     * 車検満了日（不覆盖只建单）、事故歴（不覆盖只建单）。</p>
     *
     * <p><b>{@code inspection_certificate_no} 那一行实现不了</b>：V51 的票面没有証書番号列
     * （D04 §3.8 未给），{@code CERT_MISMATCH} 因此无从判定；同理評価点的差异也无从判定
     * （票面无 {@code auction_grade} 列，只有自由文本的 {@code structure_condition}）。
     * 两处都是表结构缺口，已登记，不在本包自造列。</p>
     *
     * @param report  已落库的票
     * @param actor   操作人
     * @param traceId 链路号
     * @return 差异清单
     */
    private List<ReconcileDiff> reconcileConditionReport(VehicleConditionReport report, String actor,
                                                         String traceId) {
        UsedVehicleAttributes attributes = attributesRepository.findByVin(report.getVin())
                .orElse(null);
        if (attributes == null) {
            // LEGACY 车辆（没有 USED 属性行）不参与 USED 勾稽；票照留档，只留一条审计。
            supportRepository.writeAudit(actor, "CONDITION_REPORT_NOT_RECONCILED", "VEHICLE", report.getVin(),
                    null, "reportId=" + report.getId() + ",reason=NO_USED_ATTRIBUTES_ROW", traceId);
            return List.of();
        }
        LocalDate bizDate = report.getInspectedOn();
        boolean alertEnabled = alertEnabled(bizDate);
        Integer threshold = thresholdKm(bizDate);
        List<ReconcileDiff> diffs = new ArrayList<>();

        // ---- 里程：票面读数按来源优先级参与竞争 ----
        boolean mileageChanged = report.getOdometerKm() != null
                && resolveMileageInto(attributes, mileageSourceOf(report.getReportType()),
                report.getOdometerKm(), report.getInspectedOn(), threshold, diffs, report.getVin());

        // ---- 修復歴：取严（D03 §2.3「MAJOR 优先，UNKNOWN 不优于已知」）----
        boolean repairChanged = false;
        if (report.getRepairHistory() != null) {
            RepairHistoryFlag before = attributes.getRepairHistory();
            RepairHistoryFlag strictest = repairPolicy.strictest(
                    List.of(before, report.getRepairHistory()));
            if (strictest != null && strictest != before) {
                attributes.setRepairHistory(strictest);
                repairChanged = true;
                diffs.add(new ReconcileDiff(report.getVin(), "repair_history", nameOf(before),
                        nameOf(report.getRepairHistory()), "REPAIR_HISTORY_DIFF", true));
                supportRepository.writeAudit(actor, "VEHICLE_REPAIR_HISTORY_STRICTEST", "VEHICLE",
                        report.getVin(), "repairHistory=" + nameOf(before),
                        "repairHistory=" + strictest.name() + "(取严，票面="
                                + nameOf(report.getRepairHistory()) + "),reportId=" + report.getId(), traceId);
            }
        }

        // ---- 不覆盖的两行：車検満了日、事故歴（保留双值，人工裁决）----
        if (report.getInspectionExpiryDate() != null
                && !report.getInspectionExpiryDate().equals(attributes.getInspectionExpiryDate())) {
            diffs.add(new ReconcileDiff(report.getVin(), "inspection_expiry_date",
                    str(attributes.getInspectionExpiryDate()), str(report.getInspectionExpiryDate()),
                    "MASTER_DATA_DIFF", false));
        }
        if (report.getAccidentHistory() != null
                && !report.getAccidentHistory().equals(attributes.getAccidentHistoryFlag())) {
            // 事故歴没有取严定源（D03 §2.3 只给了修復歴的序），所以这里不写主数据。
            // "事故歴是否也该从严"已列入待签字清单。
            diffs.add(new ReconcileDiff(report.getVin(), "accident_history_flag",
                    str(attributes.getAccidentHistoryFlag()), str(report.getAccidentHistory()),
                    "MASTER_DATA_DIFF", false));
        }

        if (mileageChanged || repairChanged) {
            writeAttributesIfChanged(attributes, true, report.getVin(), actor, traceId,
                    "VEHICLE_ATTRIBUTES_RECONCILED", "reportId=" + report.getId()
                            + ",mileage=" + attributes.getMileage() + "("
                            + nameOf(attributes.getMileageSource()) + "),repairHistory="
                            + nameOf(attributes.getRepairHistory()));
        }
        raiseAlerts(diffs, report.getVin(), alertEnabled, actor, traceId);
        if (!diffs.isEmpty() && !alertEnabled) {
            // 工单开关未签字 ⟹ 差异只进审计，不静默丢弃。
            supportRepository.writeAudit(actor, "MASTER_DATA_DIFF_NOT_ALERTED", "VEHICLE", report.getVin(),
                    null, "diffs=" + diffs.size() + ",fields=" + diffs.stream().map(ReconcileDiff::field).toList()
                            + ",reportId=" + report.getId() + ",reason="
                            + UsedConfigRepository.MASTER_DATA_DIFF_ALERT_ENABLED + " 未配置", traceId);
        }
        return diffs;
    }

    /**
     * 里程优先级判定的内核：<b>只改内存对象，不落库</b>（落库由调用方统一做一次）。
     *
     * @param attributes 主数据属性视图（原地更新里程三元组）
     * @param source     观测来源；{@code null} 表示该票种不产出里程读数
     * @param mileageKm  观测读数
     * @param capturedOn 观测时点
     * @param threshold  差异阈值（{@code null}＝未签字，不判数值差）
     * @param diffs      差异收集器（原地追加）
     * @param vin        车辆 VIN
     * @return 是否改了内存对象（决定调用方要不要落库）
     */
    private boolean resolveMileageInto(UsedVehicleAttributes attributes, MileageSource source, Integer mileageKm,
                                       LocalDate capturedOn, Integer threshold, List<ReconcileDiff> diffs,
                                       String vin) {
        if (source == null || mileageKm == null) {
            // 该票种没有里程读数（或读数为空）——不参与，也不建"里程缺失"工单。
            return false;
        }
        validateMileageTriple(source, mileageKm, vin);
        MileageSample master = new MileageSample(attributes.getMileageSource(),
                attributes.getMileage(), attributes.getMileageCapturedOn());
        MileageSample incoming = new MileageSample(source, mileageKm, capturedOn);
        MileageSample winner = mileagePolicy.resolve(List.of(master, incoming));
        boolean downgraded = attributes.getMileageSource() != null
                && mileagePolicy.rankOf(source) < mileagePolicy.rankOf(attributes.getMileageSource());
        long delta = master.getMileageKm() != null ? Math.abs((long) mileageKm - master.getMileageKm()) : -1L;
        boolean numericDiff = threshold != null && delta >= 0 && delta > threshold;

        // resolve 返回的是入参列表里的同一个引用，所以用 == 判"本次观测胜出"，
        // 不比字段值比（同档同日的两条同值观测，比字段分不出是哪条赢）。
        if (winner == incoming && !Objects.equals(master.getMileageKm(), mileageKm)) {
            attributes.setMileage(mileageKm);
            attributes.setMileageSource(source);
            attributes.setMileageCapturedOn(capturedOn);
            attributes.setMileageUnit(MILEAGE_UNIT_KM);
            diffs.add(new ReconcileDiff(vin, "mileage", str(master.getMileageKm()), str(mileageKm),
                    "ODOMETER_DIFF", true));
            // 采纳之后差值仍超阈值时差异工单照建：
            // "已按更可信来源改过"与"这个差值需要人看一眼"是两件事（D06 §2.3 同一行两个动作）。
            if (numericDiff) {
                diffs.add(new ReconcileDiff(vin, "mileage_delta", str(master.getMileageKm()), str(mileageKm),
                        "ODOMETER_DIFF", false));
            }
            return true;
        }
        if (winner == incoming) {
            // 胜出但数值相同：无覆盖、无工单，只有采集时点可能需要前移（更晚的同值观测）。
            boolean capturedNewer = capturedOn != null
                    && (master.getCapturedOn() == null || capturedOn.isAfter(master.getCapturedOn()));
            if (capturedNewer) {
                attributes.setMileageCapturedOn(capturedOn);
                attributes.setMileageSource(source);
            }
            return capturedNewer;
        }
        // 主数据胜出（含"同档同日、时点不新"的情形——D06 §2.4 对这种并列没有定义，
        // 本包的处理是不覆盖、只建工单，并列语义待业务确认）。
        boolean valueDiff = !Objects.equals(master.getMileageKm(), mileageKm);
        if (valueDiff || downgraded) {
            diffs.add(new ReconcileDiff(vin, "mileage", str(master.getMileageKm()), str(mileageKm),
                    downgraded ? "ODOMETER_DIFF" : "MASTER_DATA_DIFF", false));
        }
        return false;
    }

    /** 属性行落库（一次），失败即冲突；{@code changed=false} 时什么都不做。 */
    private void writeAttributesIfChanged(UsedVehicleAttributes attributes, boolean changed, String vin,
                                          String actor, String traceId, String action, String afterSummary) {
        if (!changed) {
            return;
        }
        boolean ok = attributesRepository.updateAttributes(attributes,
                attributes.getVersion() == null ? 0 : attributes.getVersion());
        if (!ok) {
            throw new BusinessException(ErrorCode.CONFLICT_001, Map.of(
                    "vin", vin, "reason", "ATTRIBUTE_WRITE_CONFLICT",
                    "expectedVersion", String.valueOf(attributes.getVersion())));
        }
        supportRepository.writeAudit(actor, action, "VEHICLE", vin, null,
                afterSummary + ",version=" + attributes.getVersion() + "→"
                        + ((attributes.getVersion() == null ? 0 : attributes.getVersion()) + 1)
                        + ",trace=" + traceId, traceId);
    }

    /** 票种 → 里程来源（D06 §2.4 的三类读数通道）。 */
    private static MileageSource mileageSourceOf(ConditionReportType reportType) {
        if (reportType == null) {
            return null;
        }
        return switch (reportType) {
            case AUCTION_INSPECTION -> MileageSource.AUCTION_REPORT;
            case WMS_INBOUND_RECHECK -> MileageSource.WMS_INBOUND;
            case OWN_AUDIT -> MileageSource.OWN_AUDIT;
        };
    }

    /** 差异是否建工单：参数缺失按 false（不告警），但调用方会把差异落进审计。 */
    private boolean alertEnabled(LocalDate bizDate) {
        return usedConfigRepository
                .findBoolean(UsedConfigRepository.MASTER_DATA_DIFF_ALERT_ENABLED, bizDate)
                .orElse(Boolean.FALSE);
    }

    /** 里程差异阈值：未签字时返回 {@code null}，本包不写死 D06 §2.3 的 1,000km。 */
    private Integer thresholdKm(LocalDate bizDate) {
        return usedConfigRepository
                .findInt(UsedConfigRepository.MILEAGE_DIFF_THRESHOLD_KM, bizDate).orElse(null);
    }

    /**
     * 把差异变成工单（走既有 {@code alerts} 通道；正式差异台账属 W-U4/V66，见类注释）。
     *
     * <p>等级取 HIGH 的理由：D06 §2.3 对同族的 {@code CERT_MISMATCH} 明写"HIGH 工单"，
     * 而这些都是"两个事实互相矛盾、要人裁决"的信号；用 MEDIUM 会低估。
     * 阈值本身（什么算差异）来自参数表，不在这里发明。</p>
     */
    private void raiseAlerts(List<ReconcileDiff> diffs, String vin, boolean alertEnabled,
                             String actor, String traceId) {
        if (!alertEnabled || diffs.isEmpty()) {
            return;
        }
        Long dealerId = vehicleUnitRepository.findByVin(vin).map(VehicleUnit::getDealerId).orElse(null);
        for (ReconcileDiff d : diffs) {
            supportRepository.createAlert(dealerId, vin, d.alertType(), "HIGH",
                    "字段=" + d.field() + "，主数据=" + d.masterValue() + "，观测=" + d.observedValue()
                            + "，处置=" + (d.appliedToMaster() ? "按可信来源已采纳" : "保留双值待人工裁决")
                            + "，操作人=" + actor + "，trace=" + traceId);
        }
    }

    /**
     * 找出"这张票是不是已经录过"。
     *
     * <p>有票号走 {@code uk_vcr_report_no} 的探测（幂等键 {@code CRR:{report_no}}）；
     * 无票号（自盘）退化到 {@code (vin, report_type, inspected_on)} 的最近一票比对，
     * 只是尽力去重、不是库侧保证（详见 {@link #recordConditionReport} 注释）。</p>
     */
    private Optional<VehicleConditionReport> findReplayCandidate(VehicleConditionReport report, String reportNo) {
        if (reportNo != null) {
            return conditionReportRepository.findByReportNo(reportNo);
        }
        return conditionReportRepository
                .findLatestByType(report.getVin(), report.getReportType().name(), report.getInspectedOn())
                .filter(latest -> report.getInspectedOn().equals(latest.getInspectedOn()));
    }

    /** 票面指纹：参与勾稽与判重的那几个字段，任一不同就不是同一张票。 */
    private static String fingerprintOf(VehicleConditionReport r) {
        return String.join("|", String.valueOf(r.getVin()), nameOf(r.getReportType()),
                String.valueOf(r.getInspectedOn()), String.valueOf(r.getOdometerKm()),
                String.valueOf(nameOf(r.getRepairHistory())), String.valueOf(r.getAccidentHistory()),
                String.valueOf(r.getInspectionExpiryDate()), String.valueOf(r.getAttachmentDigest()));
    }

    // =====================================================================
    // 返回结构与工具
    // =====================================================================

    /** 一条字段级差异（D06 §2.3 表里的一行）。 */
    public record ReconcileDiff(String vin, String field, String masterValue, String observedValue,
                                String alertType, boolean appliedToMaster) {
    }

    /** 一次票登记的结论。 */
    public record ReportOutcome(VehicleConditionReport report, List<ReconcileDiff> diffs,
                                boolean masterUpdated) {
    }

    /** 车商必须存在且 {@code ACTIVE}（暂停/终止的车商不承接新增业务，见 {@link DealerStatus} 语义）。 */
    private Dealer requireActiveDealer(Long dealerId) {
        if (dealerId == null) {
            throw new BusinessException(ErrorCode.VALID_001, "车商 ID 不能为空");
        }
        Dealer dealer = dealerRepository.findById(dealerId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND_001, "车商不存在：" + dealerId));
        if (dealer.getStatus() != DealerStatus.ACTIVE) {
            throw new BusinessException(ErrorCode.VALID_001, Map.of(
                    "dealerId", dealerId,
                    "status", nameOf(dealer.getStatus()),
                    "reason", "DEALER_NOT_ACTIVE",
                    "detail", "非合作中车商不得新增车辆台账；D07 §8 无对应错误码（缺口已登记）"));
        }
        return dealer;
    }

    private VehicleUnit requireVehicle(String vin) {
        return vehicleUnitRepository.findByVin(vin)
                .orElseThrow(() -> new BusinessException(ErrorCode.VEHICLE_001, "车辆不存在：" + vin));
    }

    private static String requireText(String value, String label) {
        String v = blankToNull(value);
        if (v == null) {
            throw new BusinessException(ErrorCode.VALID_001, label + "不能为空");
        }
        return v;
    }

    private static <E extends Enum<E>> E parseEnum(Class<E> type, String raw, String label) {
        String v = blankToNull(raw);
        if (v == null) {
            throw new BusinessException(ErrorCode.VALID_001, label + "不能为空");
        }
        try {
            return Enum.valueOf(type, v.toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(ErrorCode.VALID_001, label + "取值非法：" + raw);
        }
    }

    private static String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static String nameOf(Enum<?> value) {
        return value == null ? null : value.name();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}

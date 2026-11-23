package com.company.floorplan.domain.repository;

import com.company.floorplan.domain.model.CertificateCustodyEvent;
import com.company.floorplan.domain.model.VehicleCertificate;
import com.company.floorplan.domain.model.VehicleConditionReport;
import com.company.floorplan.domain.model.UsedVehicleAttributes;
import com.company.floorplan.domain.model.UsedVehicleAttrsView;
import com.company.floorplan.domain.model.WmsDiffSeverity;
import com.company.floorplan.domain.model.WmsDiffType;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * USED 车辆主数据与車検証仓储（USED R2）。
 *
 * <p><b>与 LEGACY {@link VehicleUnitRepository} 的分工</b>：建账（插 {@code vehicle_units} 基础行）
 * 仍走 {@code VehicleUnitRepository.save}，本接口只写 V50 那批 USED 列、車検証两张表、
 * 车况票与账实差异。<b>不提供任何「改 LEGACY 业务列」的方法</b>——
 * 一旦这里能改 {@code advance_amount}／{@code status}，USED 就绕过了 LEGACY 的状态机与利息链路，
 * 那是拆库当天最先炸的地方（AGENTS.md §12「靠共库跨域直写维持原子性」）。</p>
 *
 * <p><b>里程只有一个写入口</b>（{@link #applyMileageMaster}），且参数是
 * {@code MileageResolutionPolicy} 的判定结果而不是裸数值：判定与落库绑在一起，
 * 想绕过取严规则就只能自己去改那个纯函数。</p>
 */
public interface UsedVehicleMasterRepository {

    // ── vehicle_units 的 USED 属性 ────────────────────────────────────────

    /** 写 V50 那批主数据属性列；按 VIN 定位。返回影响行数（0＝VIN 不存在）。 */
    int updateUsedAttributes(String vin, UsedVehicleAttributes attributes);

    /**
     * 按里程判定结果回写主数据（{@code mileage} / {@code mileage_source} / {@code mileage_captured_on}）。
     *
     * <p>{@code UNDETERMINED} 时写 {@code mileage=NULL, mileage_source='UNKNOWN'}，
     * 以满足 V50 的 {@code ck_vu_mileage_unknown}；{@code KEPT_MASTER} 时本方法<b>不应被调用</b>
     * （实现里也做了防御：结论与主数据一致时返回 0 而不写）。</p>
     */
    int applyMileageMaster(String vin,
                           String mileageSource,
                           Integer mileageKm,
                           LocalDate capturedOn);

    /**
     * 读一辆车 USED 侧的全部列（含里程三列），供勾稽与详情端点。
     *
     * <p>返回 {@code empty} ＝ VIN 不在账上；<b>不返回「全 null 的视图」</b>——
     * 后者会让上层把「车不存在」读成「车存在但什么都没采集」，
     * 而这两种情况在放款闸门上是完全不同的处置。</p>
     */
    Optional<UsedVehicleAttrsView> findUsedAttributes(String vin);

    /**
     * 把 VIN 与当前在场证关联（V50 列 {@code inspection_certificate_id}，V53 有物理 FK）。
     *
     * <p>只有两个调用方：首次登记与换发。传 {@code null} 表示解除关联，
     * 仅用于「证被申报灭失且尚未再発行」这一种态——所以本方法参数可为 null 而方法名不叫 bind。</p>
     */
    int bindCertificate(String vin, Long certificateId);

    // ── inspection_certificates（V52/V53）────────────────────────────────

    /** 插入一张证；幂等锚点 {@code cert_key} 冲突时抛 {@code DuplicateKeyException} 由上层翻成 CERT_002/200。 */
    VehicleCertificate insertCertificate(VehicleCertificate certificate);

    /** 更新证（状态 + 时间戳 + 保管信息），带 {@code version} 乐观锁；返回 0 表示并发覆盖。 */
    int updateCertificate(VehicleCertificate certificate);

    /** 当前「在场」的证（{@code status NOT IN ('REISSUED','LOST')}，与 V53 唯一索引同谓词）。 */
    Optional<VehicleCertificate> findActiveCertificate(String vin);

    /** 按証書番号取（换发链查重用）。 */
    Optional<VehicleCertificate> findCertificateByNo(String certificateNo);

    /** 某 VIN 的全部证，含已退场的历史证（换发链展示与 R12 留痕）。 */
    List<VehicleCertificate> findCertificatesByVin(String vin);

    // ── certificate_custody_events（V52，append-only）───────────────────

    /** 追加一条保管/核查流水；本表只 INSERT，没有更新方法（库表语义就是流水）。 */
    void appendCustodyEvent(CertificateCustodyEvent event);

    /** 某张证的流水，按时间倒序。 */
    List<CertificateCustodyEvent> findCustodyEvents(Long certificateId);

    // ── vehicle_condition_reports（V51，append-only）────────────────────

    /**
     * 插入一张车况票（幂等键 {@code report_no}，对应 {@code uk_vcr_report_no} 部分唯一索引）。
     *
     * <p>返回类型带 {@code created} 而不是只返回实体：调用方必须能区分「这次真的入账」和
     * 「撞上既有票号」，否则重推只能一律回 201，冒烟就没法证明确实幂等
     * （R1 的许可/会员 upsert 同理）。判据来自 {@code INSERT ... ON CONFLICT DO NOTHING RETURNING id}
     * 有无返回行，不需要先查后插（那是并发下的 TOCTOU）。</p>
     */
    ReportIngestion insertConditionReport(VehicleConditionReport report);

    /**
     * 车况票写入结果：{@code reportId} 是落库行 id（重推时为既有行），
     * {@code created} 表示本次是否真新增。
     */
    record ReportIngestion(Long reportId, boolean created) {
    }

    /** 某 VIN 的车况票，按 {@code inspected_on DESC}（取最新＝列表第一行，走 {@code idx_vcr_vin_date}）。 */
    List<VehicleConditionReport> findConditionReports(String vin);

    // ── wms_reconcile_diffs（V66）───────────────────────────────────────

    /**
     * 开一条账实差异（勾稽结果落点）。
     *
     * <p>唯一键是 {@code (biz_date, warehouse_custodian_code, vin, diff_type)}
     * （V66 的 {@code uk_wms_diff_daily_vin}）——<b>同日重算不堆差异</b>，
     * 所以实现走 {@code ON CONFLICT DO NOTHING} 并由返回值告诉调用方这次是不是新开。</p>
     *
     * @return 1＝新增；0＝同日同类差异已存在
     */
    int openReconcileDiff(LocalDate bizDate,
                          String warehouseCustodianCode,
                          String vin,
                          WmsDiffType diffType,
                          WmsDiffSeverity severity,
                          String ourSnapshot,
                          String observedSnapshot,
                          String actor);

    /** 某 VIN 未关闭的差异（{@code status IN ('OPEN','ACKNOWLEDGED')}），供出库前置判定。 */
    int countOpenDiffs(String vin);
}

package com.company.floorplan.domain.repository;

import com.company.floorplan.domain.model.CertificateCustodyEvent;
import com.company.floorplan.domain.model.InspectionCertificate;

import java.util.List;
import java.util.Optional;

/**
 * 有効車検証仓储（R2.5 一车一证与换证链；表 {@code inspection_certificates}
 * ＋ {@code certificate_custody_events}，V52 / V53）。
 *
 * <p><b>証書与 custody 事件同一仓储</b>：换证链（UC-R2-5）要求
 * 「旧证置 {@code REISSUED} → 新证插入（带 {@code predecessor_id}）→ 两条事件落库」
 * 是一个不可拆的写入集；拆开给两个仓储，就会出现"新证进去了、旧证还是 ACTIVE"
 * 这种破坏 {@code uk_cert_active_vin} 语义的中间态（虽然同一事务内不会可见，
 * 但缺一条事件就再也补不回来）。</p>
 *
 * <p><b>不变式的兜底位置</b>：
 * <pre>
 *   一车一证      → uk_cert_active_vin（V53 部分唯一，status NOT IN (REISSUED, LOST)）
 *   幂等          → uk_cert_key（V52，键 = CERT-{certificate_no}，D00 §13）
 *   链不自引用    → ck_cert_predecessor_not_self（V53）
 *   状态与时间戳  → ck_cert_status_timestamps（V53：PLEDGED 要有 pledged_at 等）
 * </pre>
 * 撞唯一约束时仓储抛错（成因可分辨时分别是 {@code CERT_002}／{@code CONFLICT_001}），
 * CAS 未命中时 {@link #transitStatus} 返回 false 由上层翻码。
 * 之所以不再用"返回空让上层判断"：PostgreSQL 撞约束后当前事务已中止，
 * 上层拿着空值再查一次只会得到 "current transaction is aborted"，
 * 409 会被 500 覆盖——这条口径与 {@code DealerQualificationRepository}、
 * {@code VehicleConditionReportRepository} 统一。</p>
 */
public interface InspectionCertificateRepository {

    /**
     * 取某 VIN 当前的有効証書（ {@code uk_cert_active_vin} 保证至多一条）。
     *
     * @param vin 车辆 VIN
     * @return 証書
     */
    Optional<InspectionCertificate> findActiveByVin(String vin);

    /**
     * 取某 VIN 的全部証書行（含 REISSUED / LOST 历史，按 {@code issue_date} 正序）。
     *
     * @param vin 车辆 VIN
     * @return 换证链
     */
    List<InspectionCertificate> findChainByVin(String vin);

    /**
     * 按 ID 取証書。
     *
     * @param id 主键
     * @return 証書
     */
    Optional<InspectionCertificate> findById(Long id);

    /**
     * 插入証書行。
     *
     * <p>{@code cert_key} 必传（键公式 {@code CERT-{certificate_no}} 的定源在 D00 §13.1，
     * 由应用层拼装，仓储不另算一份）。</p>
     *
     * @param certificate 証書
     * @return 带 ID 的行
     */
    InspectionCertificate insert(InspectionCertificate certificate);

    /**
     * 状态迁移（乐观锁）。
     *
     * <p>合法迁移由 {@code CertificateStateMachine.requireTransition} 在调用前判定；
     * 仓储不重复判（避免两处白名单漂移），但必须把 {@code version} 带进 WHERE。</p>
     *
     * @param id              主键
     * @param from            当前状态（写入 WHERE 条件，防"读后被人改过"）
     * @param to              目标状态
     * @param expectedVersion 期望版本
     * @return 是否更新成功
     */
    boolean transitStatus(Long id, String from, String to, int expectedVersion);

    /**
     * 追加一条 custody 事件。
     *
     * @param event 事件
     * @return 带 ID 的事件
     */
    CertificateCustodyEvent appendCustodyEvent(CertificateCustodyEvent event);

    /**
     * 按証書取事件链（时间正序）。
     *
     * @param certificateId 証書 ID
     * @return 事件列表
     */
    List<CertificateCustodyEvent> findCustodyEvents(Long certificateId);
}

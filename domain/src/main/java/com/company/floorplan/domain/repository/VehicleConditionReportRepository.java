package com.company.floorplan.domain.repository;

import com.company.floorplan.domain.model.VehicleConditionReport;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * 車況票仓储（R2.2 / R2.3；表 {@code vehicle_condition_reports}，V51）。
 *
 * <p><b>只有插入，没有更新</b>：本表是 append-only 事实台账，更正＝补录新票。
 * 仓储不提供 update/delete，是为了让"改掉旧票"在编译期就不可表达。</p>
 */
public interface VehicleConditionReportRepository {

    /**
     * 插入一张票。
     *
     * <p><b>撞约束的处理口径（与 {@code DealerQualificationRepository} 一致）</b>：
     * 幂等 {@code CRR:{report_no}} 的正常路径是应用层先 {@link #findByReportNo} 探测，
     * 命中则比票面指纹决定"回首次结果"还是抛 {@code CONFLICT_001}；
     * 两个事务同时穿过探测时，后者会撞 {@code uk_vcr_report_no}，
     * 本方法把 {@code DuplicateKeyException} 翻成 {@code CONFLICT_001} 抛出。
     * 这里<b>不在同一事务里回查已存在行</b>——PostgreSQL 撞唯一约束后当前事务已中止，
     * 事务内再发任何语句都只会报 "current transaction is aborted"，
     * 真正的 409 会被 500 覆盖。因此返回类型是实体而不是 Optional：
     * "返回空让上层再查一次"这种写法在 PG 上是走不通的（原注释已据此更正）。</p>
     *
     * @param report 票
     * @return 带 ID 的票
     */
    VehicleConditionReport insert(VehicleConditionReport report);

    /**
     * 按票号取票（幂等探测）。
     *
     * @param reportNo 票号
     * @return 票
     */
    Optional<VehicleConditionReport> findByReportNo(String reportNo);

    /**
     * 按 VIN 取最近若干张票（{@code inspected_on DESC}，走 {@code idx_vcr_vin_date}）。
     *
     * @param vin   车辆 VIN
     * @param limit 条数上限
     * @return 票列表
     */
    List<VehicleConditionReport> findLatestByVin(String vin, int limit);

    /**
     * 取某来源在某日对某车给出的里程观测（R2.3 勾稽的输入之一）。
     *
     * @param vin        车辆 VIN
     * @param reportType 票种（{@code ConditionReportType} 的 name）
     * @param from       起始检查日（含）
     * @return 票；无则为空
     */
    Optional<VehicleConditionReport> findLatestByType(String vin, String reportType, LocalDate from);
}

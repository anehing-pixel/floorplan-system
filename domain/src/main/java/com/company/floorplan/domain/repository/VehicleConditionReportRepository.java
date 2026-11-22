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
     * <p>幂等由 {@code uk_vcr_report_no}（部分唯一，{@code report_no} 为空的人工票不受限）兜底：
     * {@code report_no} 命中已存在行时返回空，由应用层按 D00 §13 的
     * {@code CRR:{report_no}} 语义决定"回首次结果"还是抛 {@code CONFLICT_001}。</p>
     *
     * @param report 票
     * @return 带 ID 的票；{@code report_no} 重复时为空
     */
    Optional<VehicleConditionReport> insert(VehicleConditionReport report);

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

package com.company.floorplan.domain.repository;

import com.company.floorplan.domain.model.UsedVehicleAttributes;

import java.util.Optional;

/**
 * USED 车辆属性仓储（R2.1 建账；只读写 V50 给 {@code vehicle_units} 新增的列）。
 *
 * <p><b>与 {@code VehicleUnitRepository} 的分工</b>：既有仓储负责 LEGACY 全链路要用的那些列，
 * 本仓储只碰 USED 新增列，两者按 {@code vin} 定位同一行。这样分的唯一理由是 L4
 * 「LEGACY 建账回归不破」——本轮不编译、不冒烟、不连库，
 * 不去改那份共用的 INSERT/SELECT 列表，就是不去动那条没被验证过的路径。</p>
 *
 * <p><b>里程的唯一落点是既有列 {@code vehicle_units.mileage}</b>（D04 §5.1）：
 * 写的是那列本身，库里不存在、本包也不写任何 {@code mileage_km} 列。
 * {@code mileage} / {@code mileage_source} / {@code mileage_captured_on}
 * 三列必须在同一条语句里写——V50 的 {@code ck_vu_mileage_unknown}
 * 要求 {@code UNKNOWN} 时 {@code mileage} 为 NULL，拆成两条就会在中途留下违反约束的可见状态。</p>
 *
 * <p><b>没有删除</b>：主数据不物理删除（AGENTS 数据纪律）；建账后的属性更正走
 * {@link #updateAttributes} ＋审计，不留"删了重建"的空窗。</p>
 */
public interface UsedVehicleAttributesRepository {

    /**
     * 按 VIN 读取 USED 属性（含里程三元组与既有列 {@code vehicle_type}）。
     *
     * @param vin 车辆 VIN
     * @return 属性视图；行不存在时为空
     */
    Optional<UsedVehicleAttributes> findByVin(String vin);

    /**
     * 写入 USED 属性（乐观锁 CAS）。
     *
     * <p>{@code expectedVersion} 取自既有列 {@code vehicle_units.version}，由调用方
     * 从 {@code VehicleUnitRepository} 读来传入——本仓储不重复读整行，
     * 以免两个仓储对同一行给出两套版本。</p>
     *
     * @param attributes      属性视图（{@code vin} 必填）
     * @param expectedVersion 期望版本
     * @return 是否更新成功（false＝版本冲突）
     */
    boolean updateAttributes(UsedVehicleAttributes attributes, int expectedVersion);
}

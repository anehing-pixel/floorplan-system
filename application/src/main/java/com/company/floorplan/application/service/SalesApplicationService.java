package com.company.floorplan.application.service;

import com.company.floorplan.domain.exception.BusinessException;
import com.company.floorplan.domain.exception.ErrorCode;
import com.company.floorplan.domain.model.SecurityStatus;
import com.company.floorplan.domain.model.SecurityType;
import com.company.floorplan.domain.model.VehicleStatus;
import com.company.floorplan.domain.model.VehicleUnit;
import com.company.floorplan.domain.repository.SupportRepository;
import com.company.floorplan.domain.repository.VehicleUnitRepository;
import com.company.floorplan.domain.service.BusinessCalendar;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 售出与担信用例（业务闭环第 ④ 与第 ⑥ 环节，以及第 ⑦ 环节的解除担保）。
 *
 * <p><b>第 ⑥ 环节（零售成交）</b>：标记售出后，车辆进入"已售待还"，
 * 并计算结算到期日 = 售出日 + N 个营业日（预设 2 个营业日，按营业日历计算）。
 * 倒计时一旦逾期，结算窗口扫描作业会判定为逾期并触发脱信销售告警。</p>
 *
 * <p><b>第 ④ 环节（设定担保）</b>：逐台设定。<b>待确认</b>——
 * 新车未上牌时所有权保留登记无登记对象，担保落地路径需客户与当地合规确认。
 * 若确认无可行路径，本环节相应接口会移除，security 字段置为 NONE 留痕。</p>
 *
 * <p><b>幂等考虑</b>：解除担保重复调用不报错（本就无担保时直接返回）。</p>
 *
 * <p><b>业务参数</b>：结算窗口天数来自配置 {@code floorplan.settle-window-business-days}（预设值）。</p>
 */
@Service
public class SalesApplicationService {

    private final VehicleUnitRepository vehicleUnitRepository;
    private final SupportRepository supportRepository;
    private final BusinessCalendar calendar;

    /** 结算窗口（预设 2 个营业日，需客户确认）。 */
    @Value("${floorplan.settle-window-business-days:2}")
    private int settleWindowBusinessDays;

    /** 担保物台账服务：售出扣减清单、担保登记同步。 */
    private final CollateralLedgerApplicationService collateralService;

    public SalesApplicationService(VehicleUnitRepository vehicleUnitRepository,
                                   SupportRepository supportRepository,
                                   BusinessCalendar calendar,
                                   CollateralLedgerApplicationService collateralService) {
        this.vehicleUnitRepository = vehicleUnitRepository;
        this.supportRepository = supportRepository;
        this.calendar = calendar;
        this.collateralService = collateralService;
    }

    /** 零售成交：进入结算窗口倒计时（售出即还）。 */
    @Transactional
    public Map<String, Object> markSold(String vin, LocalDate soldAt, String actor) {
        VehicleUnit unit = vehicleUnitRepository.findByVinForUpdate(vin)
                .orElseThrow(() -> new BusinessException(ErrorCode.VEHICLE_001, "车辆不存在：" + vin));
        LocalDate sold = soldAt == null ? LocalDate.now() : soldAt;
        LocalDate due = calendar.plusBusinessDays(sold, settleWindowBusinessDays);
        unit.markSold(sold, due);
        vehicleUnitRepository.save(unit);

        supportRepository.writeAudit(actor, "MARK_SOLD", "VEHICLE", vin,
                "status=" + VehicleStatus.IN_STOCK, "status=SOLD_PENDING,due=" + due, null);

        // 库存释放规则：售出即自动扣减担保物清单，并建立"回款须落指定账户/用于还款"的义务。
        // 注意：扣减的是"在库担保物"口径，担保权益本身在回款+抹消前仍然存在。
        collateralService.markSold(vin, sold.atStartOfDay(), actor);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("vin", vin);
        data.put("vehicleStatus", unit.getStatus().name());
        data.put("soldAt", sold.toString());
        data.put("settleDueDate", due.toString());
        return data;
    }

    /** 设定担保（第 ④ 环节；是否落地取决于担保路径确认）。 */
    @Transactional
    public Map<String, Object> setSecurity(String vin, SecurityType type, String regNo, String ownerName, String actor) {
        VehicleUnit unit = vehicleUnitRepository.findByVinForUpdate(vin)
                .orElseThrow(() -> new BusinessException(ErrorCode.VEHICLE_001, "车辆不存在：" + vin));
        unit.setSecurity(type);
        vehicleUnitRepository.save(unit);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("vin", vin);
        data.put("securityType", unit.getSecurityType().name());
        data.put("securityStatus", unit.getSecurityStatus().name());
        supportRepository.writeAudit(actor, "SET_SECURITY", "VEHICLE", vin,
                null, "type=" + type.name() + ",regNo=" + regNo, null);
        return data;
    }

    /** 解除担保（第 ⑦ 环节，通常由结清自动触发）。 */
    @Transactional
    public Map<String, Object> releaseSecurity(String vin, String actor) {
        VehicleUnit unit = vehicleUnitRepository.findByVinForUpdate(vin)
                .orElseThrow(() -> new BusinessException(ErrorCode.VEHICLE_001, "车辆不存在：" + vin));
        if (unit.getSecurityStatus() == null || unit.getSecurityStatus() == SecurityStatus.NONE) {
            throw new BusinessException(ErrorCode.SECURITY_001, "该车辆无担保记录，无需解除");
        }
        unit.releaseSecurity();
        vehicleUnitRepository.save(unit);
        supportRepository.writeAudit(actor, "RELEASE_SECURITY", "VEHICLE", vin,
                "status=" + SecurityStatus.REGISTERED, "status=RELEASED", null);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("vin", vin);
        data.put("securityStatus", unit.getSecurityStatus().name());
        return data;
    }
}

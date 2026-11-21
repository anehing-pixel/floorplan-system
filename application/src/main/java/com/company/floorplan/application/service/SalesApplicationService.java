package com.company.floorplan.application.service;

import com.company.floorplan.domain.exception.BusinessException;
import com.company.floorplan.domain.exception.ErrorCode;
import com.company.floorplan.domain.model.CollateralRegistrationBatch;
import com.company.floorplan.domain.model.SecurityStatus;
import com.company.floorplan.domain.model.SecurityType;
import com.company.floorplan.domain.model.VehicleStatus;
import com.company.floorplan.domain.model.VehicleUnit;
import com.company.floorplan.domain.repository.CollateralRegistrationBatchRepository;
import com.company.floorplan.domain.repository.SupportRepository;
import com.company.floorplan.domain.repository.VehicleUnitRepository;
import com.company.floorplan.domain.service.BusinessCalendar;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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
    /** T1-3（M9）：老接口登记编号落到 V10 批次表，需按编号查重。 */
    private final CollateralRegistrationBatchRepository batchRepository;

    public SalesApplicationService(VehicleUnitRepository vehicleUnitRepository,
                                   SupportRepository supportRepository,
                                   BusinessCalendar calendar,
                                   CollateralLedgerApplicationService collateralService,
                                   CollateralRegistrationBatchRepository batchRepository) {
        this.vehicleUnitRepository = vehicleUnitRepository;
        this.supportRepository = supportRepository;
        this.calendar = calendar;
        this.collateralService = collateralService;
        this.batchRepository = batchRepository;
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

    /**
     * T2-8（M11）终端客户支付确认：金融公司确认终端客户已向经销商付款。
     *
     * <p>业务闭环第 ⑦ 环节"客户付款 → 金融确认 → 出库指令"的中间一步。
     * 修复前只有标记售出，没有金融确认环节。本用例以审计事件留痕确认事实，
     * 并校验车辆处于已售待还且本金未结清。</p>
     *
     * <p><b>TODO(M11)</b>：确认记录（金额、付款人、凭证、幂等键）需新表
     * {@code customer_payment_confirmations} 持久化；B2 出库指令上线后，
     * 应以本确认为前置门禁（资金未到账不得放车）。当前迁移文件禁改，故先留接缝。</p>
     */
    @Transactional
    public Map<String, Object> confirmCustomerPayment(String vin, long amount, String payerName, String actor) {
        if (amount <= 0) {
            throw new BusinessException(ErrorCode.VALID_002, "客户支付金额必须为正整数日元");
        }
        VehicleUnit unit = vehicleUnitRepository.findByVinForUpdate(vin)
                .orElseThrow(() -> new BusinessException(ErrorCode.VEHICLE_001, "车辆不存在：" + vin));
        if (!unit.canConfirmCustomerPayment()) {
            throw new BusinessException(ErrorCode.VEHICLE_002, Map.of(
                    "vin", vin, "currentStatus", String.valueOf(unit.getStatus()),
                    "reason", "仅已售待还且未结清的车可确认客户支付"));
        }
        String confirmNo = "PAYCONF-" + java.util.UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        supportRepository.writeAudit(actor, "CUSTOMER_PAYMENT_CONFIRMED", "VEHICLE", vin,
                null, "confirmNo=" + confirmNo + ",amount=" + amount + ",payer=" + payerName, null);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("confirmNo", confirmNo);
        data.put("vin", vin);
        data.put("amount", amount);
        data.put("payerName", payerName);
        data.put("vehicleStatus", unit.getStatus().name());
        data.put("note", "金融已确认客户支付；TODO(M11)：待新表持久化确认凭证并对接出库指令门禁");
        return data;
    }

    /**
     * 设定担保（第 ④ 环节）。
     *
     * <p><b>T1-3（M9）</b>：老接口收到的 {@code regNo}/{@code ownerName} 不再丢弃——
     * 委托 V10 担保登记批次表落库（按法务省登记编号可查），并把车辆担保状态推进到
     * REGISTERED。新路径 {@code registerCollateralBatch} 是批次级权威写入点，
     * 这里复用它，避免两套登记逻辑各自演化。</p>
     */
    @Transactional
    public Map<String, Object> setSecurity(String vin, SecurityType type, String regNo, String ownerName, String actor) {
        VehicleUnit unit = vehicleUnitRepository.findByVinForUpdate(vin)
                .orElseThrow(() -> new BusinessException(ErrorCode.VEHICLE_001, "车辆不存在：" + vin));
        unit.setSecurity(type, regNo);
        vehicleUnitRepository.save(unit);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("vin", vin);
        data.put("securityType", unit.getSecurityType().name());
        data.put("securityStatus", unit.getSecurityStatus().name());

        if (regNo != null && !regNo.isBlank()) {
            Long batchId;
            String batchNo;
            Optional<CollateralRegistrationBatch> existing = batchRepository.findByRegistrationNo(regNo);
            if (existing.isPresent()) {
                // 幂等：同一法务省登记编号重复回填不重复建批次
                batchId = existing.get().getId();
                batchNo = existing.get().getBatchNo();
            } else {
                Map<String, Object> reg = collateralService.registerCollateralBatch(
                        List.of(vin), regNo, type, null, null, LocalDateTime.now(), null,
                        null, null, null, null, null, actor);
                batchId = (Long) reg.get("batchId");
                batchNo = (String) reg.get("batchNo");
            }
            // TODO(Q27): ownerName（担保权人）在 V10 批次表无专用列，暂记 co_signed_by。
            // Q27 定权威源后再归一（collateral-reg-service 权属表已有 dedicated ownerName）。
            batchRepository.findById(batchId).ifPresent(b -> {
                b.setCoSignedBy(ownerName);
                batchRepository.save(b);
            });
            data.put("registrationNo", regNo);
            data.put("batchNo", batchNo);
            data.put("ownerName", ownerName);
        }

        supportRepository.writeAudit(actor, "SET_SECURITY", "VEHICLE", vin,
                null, "type=" + type.name() + ",regNo=" + regNo + ",ownerName=" + ownerName, null);
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

package com.company.floorplan.application.service;

import com.company.floorplan.domain.exception.BusinessException;
import com.company.floorplan.domain.exception.ErrorCode;
import com.company.floorplan.domain.model.PayoffCertificate;
import com.company.floorplan.domain.model.VehicleStatus;
import com.company.floorplan.domain.model.VehicleUnit;
import com.company.floorplan.domain.repository.InterestLedgerRepository;
import com.company.floorplan.domain.repository.PayoffCertificateRepository;
import com.company.floorplan.domain.repository.SupportRepository;
import com.company.floorplan.domain.repository.VehicleUnitRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 结清证明用例（业务闭环 P1：出具给车商的书面凭证）。
 *
 * <p><b>业务定位</b>：整台结清（还款核销 + 解除担保 + 额度回补）后，金融公司出具结清证明，
 * 载明本金、利息、合计与结清日，供车商办理解押、过户。这是"闭环最后一里"。</p>
 *
 * <p><b>前置</b>：仅整台结清（{@code SETTLED}）车辆可出具，否则抛 {@code PAYOFF_001}。
 * 本金取已还本金 {@code paidPrincipal}，利息取计息流水汇总（与结清口径一致）。</p>
 */
@Service
public class PayoffApplicationService {

    private final PayoffCertificateRepository payoffCertificateRepository;
    private final VehicleUnitRepository vehicleUnitRepository;
    private final InterestLedgerRepository interestLedgerRepository;
    private final SupportRepository supportRepository;

    public PayoffApplicationService(PayoffCertificateRepository payoffCertificateRepository,
                                    VehicleUnitRepository vehicleUnitRepository,
                                    InterestLedgerRepository interestLedgerRepository,
                                    SupportRepository supportRepository) {
        this.payoffCertificateRepository = payoffCertificateRepository;
        this.vehicleUnitRepository = vehicleUnitRepository;
        this.interestLedgerRepository = interestLedgerRepository;
        this.supportRepository = supportRepository;
    }

    /** 出具结清证明。 */
    @Transactional
    public Map<String, Object> issue(String vin, String actor) {
        VehicleUnit unit = vehicleUnitRepository.findByVin(vin)
                .orElseThrow(() -> new BusinessException(ErrorCode.VEHICLE_001, "车辆不存在：" + vin));
        if (unit.getStatus() != VehicleStatus.SETTLED) {
            throw new BusinessException(ErrorCode.PAYOFF_001, "仅整台结清车辆可出具，当前状态=" + unit.getStatus());
        }
        long principal = unit.getPaidPrincipal();
        long interest = interestLedgerRepository.sumInterestByVin(vin);
        long total = principal + interest;

        PayoffCertificate c = new PayoffCertificate();
        c.setCertNo("PAY-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
        c.setVin(vin);
        c.setPrincipal(principal);
        c.setInterest(interest);
        c.setTotalAmount(total);
        c.setIssuedAt(LocalDateTime.now());
        c.setOperator(actor);
        payoffCertificateRepository.save(c);
        supportRepository.writeAudit(actor, "PAYOFF_ISSUE", "VEHICLE", vin,
                null, "certNo=" + c.getCertNo() + ",total=" + total, null);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("certNo", c.getCertNo());
        data.put("vin", vin);
        data.put("principal", principal);
        data.put("interest", interest);
        data.put("totalAmount", total);
        data.put("issuedAt", c.getIssuedAt().toString());
        return data;
    }
}

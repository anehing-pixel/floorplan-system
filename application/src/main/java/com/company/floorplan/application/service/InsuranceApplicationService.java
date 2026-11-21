package com.company.floorplan.application.service;

import com.company.floorplan.domain.exception.BusinessException;
import com.company.floorplan.domain.exception.ErrorCode;
import com.company.floorplan.domain.model.InsurancePolicy;
import com.company.floorplan.domain.repository.InsurancePolicyRepository;
import com.company.floorplan.domain.repository.SupportRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 车辆保险用例（业务闭环 P1：损毁 / 盗抢保障）。
 *
 * <p><b>为什么需要</b>：在库车辆可能因事故、火灾、盗抢导致担保物减损。
 * 要求车商投保、以金融公司为受益人；出险后理赔款优先冲抵垫款
 * （理赔登记后由风控评估是否直接冲抵，本服务只负责"投保 + 理赔登记"）。</p>
 */
@Service
public class InsuranceApplicationService {

    private final InsurancePolicyRepository insurancePolicyRepository;
    private final SupportRepository supportRepository;

    public InsuranceApplicationService(InsurancePolicyRepository insurancePolicyRepository,
                                       SupportRepository supportRepository) {
        this.insurancePolicyRepository = insurancePolicyRepository;
        this.supportRepository = supportRepository;
    }

    /** 投保（绑定保单到某台车）。 */
    @Transactional
    public Map<String, Object> bind(String vin, String policyNo, String insurer, long insuredAmount,
                                    LocalDate startDate, LocalDate endDate, String actor) {
        if (policyNo == null || policyNo.isBlank()) {
            throw new BusinessException(ErrorCode.VALID_001, "保单号不能为空");
        }
        InsurancePolicy p = new InsurancePolicy();
        p.setPolicyNo(policyNo);
        p.setVin(vin);
        p.setInsurer(insurer);
        p.setInsuredAmount(insuredAmount);
        p.setStartDate(startDate);
        p.setEndDate(endDate);
        p.setStatus("ACTIVE");
        insurancePolicyRepository.save(p);
        supportRepository.writeAudit(actor, "INSURANCE_BIND", "VEHICLE", vin,
                null, "policyNo=" + policyNo + ",insured=" + insuredAmount, null);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("policyNo", policyNo);
        data.put("vin", vin);
        data.put("status", "ACTIVE");
        return data;
    }

    /** 理赔登记：标记保单已理赔，并告警提示风控评估冲抵垫款。 */
    @Transactional
    public Map<String, Object> claim(String policyNo, long claimedAmount, String actor) {
        InsurancePolicy p = insurancePolicyRepository.findByPolicyNo(policyNo)
                .orElseThrow(() -> new BusinessException(ErrorCode.INSURANCE_001, "保单不存在：" + policyNo));
        p.setStatus("CLAIMED");
        p.setClaimedAmount(claimedAmount);
        insurancePolicyRepository.save(p);
        supportRepository.createAlert(null, p.getVin(), "INSURANCE_CLAIM", "MEDIUM",
                "保险理赔 " + claimedAmount + "，请评估是否冲抵垫款");
        supportRepository.writeAudit(actor, "INSURANCE_CLAIM", "VEHICLE", p.getVin(),
                "ACTIVE", "CLAIMED,claimed=" + claimedAmount, null);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("policyNo", policyNo);
        data.put("vin", p.getVin());
        data.put("claimedAmount", claimedAmount);
        data.put("status", "CLAIMED");
        return data;
    }
}

package com.company.floorplan.application.service;

import com.company.floorplan.domain.model.FinancingLienCheck;
import com.company.floorplan.domain.repository.FinancingLienCheckRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 权属 / 抵押 / 查封核对录入（业务闭环 P0-③ 重复融资拦截的数据来源）。
 *
 * <p><b>为什么需要</b>：建账前必须确认车辆未在"他处"被抵押/查封。日本本地多数无开放接口，
 * 由人工或厂家门户核对后将结果录入 {@code financing_lien_checks}；若 {@code encumbered=true}，
 * 建账（{@code AdvanceApplicationService.ingestVehicles}）会拒绝（抛 VEHICLE_005）。</p>
 *
 * <p><b>本服务只负责"记录核对结果"</b>：没有状态机、没有金额，是一条审计型数据；
 * 拦截逻辑统一在垫款建账处执行，避免规则散落多处。</p>
 */
@Service
public class LienCheckApplicationService {

    private final FinancingLienCheckRepository financingLienCheckRepository;

    public LienCheckApplicationService(FinancingLienCheckRepository financingLienCheckRepository) {
        this.financingLienCheckRepository = financingLienCheckRepository;
    }

    /**
     * 录入一条权属核对结果。
     *
     * @param vin         车架号
     * @param checkSource 核对来源：MANUAL / OEM_PORTAL / REGISTRY_API
     * @param encumbered  是否在他处存在权属负担（true = 禁止重复融资）
     * @param note        备注（如登记编号、来源系统单号）
     * @param operator    操作人
     */
    @Transactional
    public void record(String vin, String checkSource, boolean encumbered, String note, String operator) {
        FinancingLienCheck c = new FinancingLienCheck();
        c.setVin(vin);
        c.setCheckSource(checkSource == null ? "MANUAL" : checkSource);
        c.setEncumbered(encumbered);
        c.setNote(note);
        c.setOperator(operator);
        c.setCheckedAt(LocalDateTime.now());
        financingLienCheckRepository.save(c);
    }
}

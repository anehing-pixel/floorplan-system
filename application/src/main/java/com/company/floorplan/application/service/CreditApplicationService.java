package com.company.floorplan.application.service;

import com.company.floorplan.domain.exception.BusinessException;
import com.company.floorplan.domain.exception.ErrorCode;
import com.company.floorplan.domain.model.CreditLine;
import com.company.floorplan.domain.repository.CreditLineRepository;
import com.company.floorplan.domain.repository.DealerRepository;
import com.company.floorplan.domain.repository.SupportRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * 额度用例（业务闭环第 ① 环节，应用层）。
 *
 * <p><b>职责边界（重要）</b>：本用例只负责额度的<b>核定、冻结、解冻</b>；
 * 额度的<b>占用与释放</b>不在这里直接操作——
 * 它们分别由垫款用例与结清用例在行锁下原子完成。
 * 这样设计是为了防止绕过业务闭环直接改额度，造成账实不符。</p>
 *
 * <p><b>内控要求</b>：冻结动作会同时产生 HIGH 级告警并写入审计事件，
 * 满足"可追溯、可审计"的合规要求。</p>
 */
@Service
public class CreditApplicationService {

    private final CreditLineRepository creditLineRepository;
    private final DealerRepository dealerRepository;
    private final SupportRepository supportRepository;

    public CreditApplicationService(CreditLineRepository creditLineRepository,
                                    DealerRepository dealerRepository,
                                    SupportRepository supportRepository) {
        this.creditLineRepository = creditLineRepository;
        this.dealerRepository = dealerRepository;
        this.supportRepository = supportRepository;
    }

    @Transactional
    public CreditLine createCreditLine(Long dealerId, long approvedLimit, String actor) {
        if (approvedLimit <= 0) {
            throw new BusinessException(ErrorCode.VALID_002, "核定额度必须为正整数日元");
        }
        if (dealerRepository.findById(dealerId).isEmpty()) {
            throw new BusinessException(ErrorCode.NOT_FOUND_001, "经销商不存在：" + dealerId);
        }
        CreditLine line = new CreditLine();
        line.setDealerId(dealerId);
        line.setApprovedLimit(approvedLimit);
        line.setUsedAmount(0L);
        line.setFrozenAmount(0L);
        line.setStatus("ACTIVE");
        CreditLine saved = creditLineRepository.save(line);

        supportRepository.writeAudit(actor, "CREATE_CREDIT_LINE", "CREDIT_LINE",
                String.valueOf(saved.getId()), null, "limit=" + approvedLimit, null);
        return saved;
    }

    @Transactional
    public CreditLine freeze(Long creditLineId, long amount, String reason, String actor) {
        CreditLine line = creditLineRepository.findByIdForUpdate(creditLineId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND_001, "额度不存在：" + creditLineId));
        line.freeze(amount);
        CreditLine saved = creditLineRepository.save(line);
        supportRepository.writeAudit(actor, "FREEZE_CREDIT_LINE", "CREDIT_LINE",
                String.valueOf(creditLineId), "frozen=" + (line.getFrozenAmount() - amount),
                "frozen=" + line.getFrozenAmount(), null);
        supportRepository.createAlert(line.getDealerId(), null, "CREDIT_FROZEN", "HIGH", reason);
        return saved;
    }

    @Transactional
    public CreditLine unfreeze(Long creditLineId, long amount, String actor) {
        CreditLine line = creditLineRepository.findByIdForUpdate(creditLineId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND_001, "额度不存在：" + creditLineId));
        line.unfreeze(amount);
        CreditLine saved = creditLineRepository.save(line);
        supportRepository.writeAudit(actor, "UNFREEZE_CREDIT_LINE", "CREDIT_LINE",
                String.valueOf(creditLineId), null, "frozen=" + line.getFrozenAmount(), null);
        return saved;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> summary(Long creditLineId) {
        CreditLine line = creditLineRepository.findById(creditLineId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND_001, "额度不存在：" + creditLineId));
        return Map.of(
                "creditLineId", line.getId(),
                "dealerId", line.getDealerId(),
                "approvedLimit", line.getApprovedLimit(),
                "usedAmount", line.getUsedAmount(),
                "frozenAmount", line.getFrozenAmount(),
                "available", line.available(),
                "status", line.getStatus());
    }

    @Transactional(readOnly = true)
    public List<CreditLine> listByDealer(Long dealerId) {
        return creditLineRepository.findByDealerId(dealerId);
    }
}

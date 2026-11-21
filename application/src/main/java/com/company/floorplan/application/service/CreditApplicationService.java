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

    /**
     * T1-4（M12 授信审批链）第一段：发起授信。
     *
     * <p>额度落地即置 {@code PENDING_APPROVAL}，<b>不可用于放款</b>——
     * {@link CreditLine#occupy(long)} 会抛 {@code CREDIT_003}。
     * 必须由另一人调用 {@link #completeCreditLine(Long, boolean, String)} 复核后才能激活。</p>
     */
    @Transactional
    public CreditLine initiateCreditLine(Long dealerId, long approvedLimit, String actor) {
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
        line.setStatus("PENDING_APPROVAL");
        CreditLine saved = creditLineRepository.save(line);

        supportRepository.writeAudit(actor, "CREDIT_INITIATE", "CREDIT_LINE",
                String.valueOf(saved.getId()), null, "limit=" + approvedLimit + ",status=PENDING_APPROVAL", null);
        return saved;
    }

    /**
     * T1-4（M12）第二段：授信复核（发起与执行分离的内控要求）。
     *
     * @param approved true 复核通过激活额度；false 驳回并关闭额度
     */
    @Transactional
    public CreditLine completeCreditLine(Long creditLineId, boolean approved, String actor) {
        CreditLine line = creditLineRepository.findByIdForUpdate(creditLineId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND_001, "额度不存在：" + creditLineId));
        if (approved) {
            line.approve();
        } else {
            line.rejectApproval();
        }
        CreditLine saved = creditLineRepository.save(line);

        supportRepository.writeAudit(actor, "CREDIT_COMPLETE", "CREDIT_LINE",
                String.valueOf(creditLineId), "status=PENDING_APPROVAL",
                "approved=" + approved + ",status=" + line.getStatus(), null);
        return saved;
    }

    /**
     * 兼容入口：credit-service 现有 {@code POST /api/credit-lines} 与存量冒烟脚本调用。
     *
     * <p>内部仍走 initiate → complete 双段并留两条审计，因此"未经 complete 不可用于放款"
     * 的不变量在领域层始终成立。TODO(M12)：生产应禁用此单人自动审批入口，
     * 由 credit-service 暴露独立审批端点并强制发起人与复核人不同（SOD）。</p>
     */
    @Transactional
    public CreditLine createCreditLine(Long dealerId, long approvedLimit, String actor) {
        CreditLine initiated = initiateCreditLine(dealerId, approvedLimit, actor);
        return completeCreditLine(initiated.getId(), true, actor);
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

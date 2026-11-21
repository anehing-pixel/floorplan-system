package com.company.floorplan.application.service;

import com.company.floorplan.domain.exception.BusinessException;
import com.company.floorplan.domain.exception.ErrorCode;
import com.company.floorplan.domain.model.AnnualReview;
import com.company.floorplan.domain.model.CreditLine;
import com.company.floorplan.domain.repository.AnnualReviewRepository;
import com.company.floorplan.domain.repository.CreditLineRepository;
import com.company.floorplan.domain.repository.SupportRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 额度年审用例（业务闭环 ⑪ 额度年审与冻结恢复）。
 *
 * <p><b>业务背景</b>：循环授信额度需定期（年度/半年度）复核经销商经营与信用状况。
 * 年审启动时<b>冻结额度</b>（{@code CreditLine.freeze}），复核通过则解冻恢复，
 * 不通过则维持冻结或下调。这是"额度生命周期"的常规闭环，区别于违约冻结。</p>
 *
 * <p><b>原子性</b>：冻结/解冻与年审状态在同一事务内完成；
 * 冻结量来自发起时的 {@code usedAmount}，解冻时按记录量回补，避免多退/少退。</p>
 */
@Service
public class AnnualReviewApplicationService {

    private final AnnualReviewRepository annualReviewRepository;
    private final CreditLineRepository creditLineRepository;
    private final SupportRepository supportRepository;

    public AnnualReviewApplicationService(AnnualReviewRepository annualReviewRepository,
                                          CreditLineRepository creditLineRepository,
                                          SupportRepository supportRepository) {
        this.annualReviewRepository = annualReviewRepository;
        this.creditLineRepository = creditLineRepository;
        this.supportRepository = supportRepository;
    }

    /** 启动年审：冻结当前占用额度，建立年审任务。 */
    @Transactional
    public Map<String, Object> initiate(Long dealerId, Long creditLineId, String cycle, String actor) {
        CreditLine line = creditLineRepository.findByIdForUpdate(creditLineId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND_001, "额度不存在：" + creditLineId));
        long freezeAmount = line.getUsedAmount();
        line.freeze(freezeAmount);
        creditLineRepository.save(line);

        AnnualReview review = new AnnualReview();
        review.setReviewNo("AR-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
        review.setDealerId(dealerId);
        review.setCreditLineId(creditLineId);
        review.setReviewCycle(cycle == null ? "ANNUAL" : cycle);
        review.setStatus("IN_PROGRESS");
        review.setFrozenAmount(freezeAmount);
        review.setInitiatedAt(LocalDateTime.now());
        annualReviewRepository.save(review);

        supportRepository.writeAudit(actor, "ANNUAL_REVIEW_INIT", "CREDIT_LINE", String.valueOf(creditLineId),
                "used=" + freezeAmount, "frozen=" + freezeAmount + ",reviewNo=" + review.getReviewNo(), null);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("reviewNo", review.getReviewNo());
        data.put("creditLineId", creditLineId);
        data.put("frozenAmount", freezeAmount);
        data.put("status", "IN_PROGRESS");
        return data;
    }

    /**
     * 完成年审：通过则解冻恢复，不通过则维持冻结。
     *
     * @param reviewNo   年审单号
     * @param passed     复核是否通过
     * @param resultNote 结论备注
     * @param actor      操作人
     */
    @Transactional
    public Map<String, Object> complete(String reviewNo, boolean passed, String resultNote, String actor) {
        AnnualReview review = annualReviewRepository.findByReviewNo(reviewNo)
                .orElseThrow(() -> new BusinessException(ErrorCode.ANNUAL_REVIEW_001, "年审任务不存在：" + reviewNo));
        CreditLine line = creditLineRepository.findByIdForUpdate(review.getCreditLineId())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND_001, "额度不存在"));

        if (passed) {
            line.unfreeze(review.getFrozenAmount());
            creditLineRepository.save(line);
            review.setStatus("PASSED");
        } else {
            review.setStatus("REJECTED"); // 维持冻结
        }
        review.setResultNote(resultNote);
        review.setCompletedAt(LocalDateTime.now());
        annualReviewRepository.save(review);

        supportRepository.writeAudit(actor, "ANNUAL_REVIEW_DONE", "CREDIT_LINE", String.valueOf(review.getCreditLineId()),
                "status=" + (passed ? "IN_PROGRESS" : "REJECTED"),
                "passed=" + passed + ",status=" + review.getStatus(), null);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("reviewNo", reviewNo);
        data.put("passed", passed);
        data.put("creditLineStatus", line.getStatus());
        data.put("status", review.getStatus());
        return data;
    }
}

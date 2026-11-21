package com.company.floorplan.domain.repository;

import com.company.floorplan.domain.model.AnnualReview;

import java.util.List;
import java.util.Optional;

/**
 * 额度年审任务仓储接口。
 */
public interface AnnualReviewRepository {

    /** 保存（按 review_no 幂等）。 */
    AnnualReview save(AnnualReview review);

    /** 按年审单号查询。 */
    Optional<AnnualReview> findByReviewNo(String reviewNo);

    /** 按经销商查询年审任务列表（最新在前）。 */
    List<AnnualReview> findByDealerId(Long dealerId);

    /** 按额度查询进行中/待处理的年审（用于冻结恢复）。 */
    Optional<AnnualReview> findPendingByCreditLine(Long creditLineId);

    /** 按额度查询最新一条年审（含已完成），用于判断复核周期是否到期。 */
    Optional<AnnualReview> findLatestByCreditLine(Long creditLineId);
}

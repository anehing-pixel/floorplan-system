package com.company.floorplan.credit.dto;

import java.math.BigDecimal;

/**
 * 请求体契约（只保留 credit-service 需要的部分）。
 * 从 web 模块的 ApiRequests 精简迁入，字段名保持一致，前端无需改动。
 */
public final class ApiRequests {

    private ApiRequests() {
    }

    /** 新增经销商（POST /api/dealers）。 */
    public static class CreateDealerRequest {
        public String dealerCode;
        public String name;
        public String riskGrade;
        public Integer businessYears;
        public String contactPerson;
        public String contactPhone;
        public String address;
        public String remark;
    }

    /** 编辑经销商档案（PUT /api/dealers/{id}）。null = 不修改（PATCH 语义）。 */
    public static class UpdateDealerRequest {
        public String dealerCode;
        public String name;
        public String riskGrade;
        public Integer businessYears;
        public String status;
        public String contactPerson;
        public String contactPhone;
        public String address;
        public String remark;
    }

    /** 经销商授信评级调整（PUT /api/dealers/{id}/risk-grade）。 */
    public static class DealerGradeRequest {
        public String riskGrade;
    }

    /** 授信额度建账（POST /api/credit-lines）。 */
    public static class CreateCreditLineRequest {
        public Long dealerId;
        public Long approvedLimit;
    }

    /** 额度冻结/解冻（POST /api/credit-lines/{id}/freeze|unfreeze）。 */
    public static class FreezeRequest {
        public Long amount;
        public String reason;
    }

    /** 评级口径变更（PUT /api/rating-configs/{grade}）。 */
    public static class RatingConfigRequest {
        public String gradeName;
        public BigDecimal rateSurcharge;
        public boolean allowed;
        public String remark;
    }

    /** 额度年审启动（POST /api/annual-reviews/initiate）。 */
    public static class AnnualReviewInitiateRequest {
        public Long dealerId;
        public Long creditLineId;
        public String cycle;
    }

    /** 额度年审完成（POST /api/annual-reviews/{reviewNo}/complete）。 */
    public static class AnnualReviewCompleteRequest {
        public boolean passed;
        public String resultNote;
    }
}

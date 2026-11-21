package com.company.floorplan.risk.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * 请求体契约（只保留 risk-service 需要的部分，从 web 模块迁入）。
 * 字段名与原版一致，前端无需改动。
 */
public final class ApiRequests {

    private ApiRequests() {
    }

    /** 实地盘点提交（POST /api/inventory/audit）。 */
    public static class AuditRequest {
        public Long dealerId;
        public String auditDate;
        public List<String> scannedVins;
    }

    /** 告警处置（POST /api/alerts/{alertId}/handle）。 */
    public static class AlertHandleRequest {
        public String status;
        public String note;
    }

    /** 违约处置开单（POST /api/disposals/open）。 */
    public static class DisposalOpenRequest {
        public String vin;
        public String reason;
    }

    /** 违约处置结案（POST /api/disposals/{caseNo}/close）。 */
    public static class DisposalCloseRequest {
        public Long proceeds;
    }

    /** 权属核对录入（POST /api/vehicles/{vin}/lien-check）。 */
    public static class LienCheckRequest {
        public String checkSource;
        public Boolean encumbered;
        public String note;
    }

    /** 担保登记批次（POST /api/collateral/registration-batches）。 */
    public static class CollateralBatchRegistrationRequest {
        public List<String> vins;
        public String registrationNo;
        public String registrationType;
        public String perfectionMethod;
        public String registryOffice;
        public String registeredAt;
        public String expiresAt;
        public Long contractAttachmentId;
        public String contractNo;
        public String contractSignedAt;
        public Long certificateAttachmentId;
        public String certificateNo;
    }

    /** 发起抹消登记待办。 */
    public static class CancellationRequest {
        public String note;
    }

    /** 抹消登记完成。 */
    public static class CancellationCompleteRequest {
        public String cancellationNo;
        public Long certificateAttachmentId;
    }

    /** 指定回款账户（只传引用码，不传明文账号）。 */
    public static class RequiredAccountRequest {
        public String accountRef;
    }
}

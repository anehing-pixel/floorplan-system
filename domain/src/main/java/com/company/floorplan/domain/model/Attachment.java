package com.company.floorplan.domain.model;

import java.time.LocalDateTime;

/**
 * 附件元数据（文件本体存对象存储，数据库<b>不存 BLOB</b>）。
 *
 * <p><b>为什么不入库</b>：把凭证文件塞进数据库会撑爆库、让备份膨胀、
 * 让流式能力归零。数据库只负责回答"这个业务对象挂了哪些凭证、
 * 它们的内容指纹是什么"，文件本体交给对象存储。</p>
 *
 * <p><b>不可变性</b>：凭证类附件登记后不可删除，只能追加新版本——
 * 这是审计的要求，不是技术限制。</p>
 */
public class Attachment {

    /** 附件挂载的业务类型。 */
    public enum BizType {
        /** 采购订单原始凭证（契约・発注書・請求書）。 */
        PURCHASE_ORDER,

        /**
         * 让渡担保合同（譲渡担保契約）。
         * <p>审核人员与经销商签署的担保合同正本，须存对象存储（S3）
         * 并与登记批次关联——它是担保权成立的<b>实体依据</b>，
         * 而登记编号只是<b>公示依据</b>，两者缺一不可。</p>
         */
        COLLATERAL_CONTRACT,

        /**
         * 登记事項証明書（謄本）。
         * <p>法务局出具的登记事项证明，证明让渡担保登记已设立。
         * 与登记编号配套归档，构成公示证据。</p>
         */
        REGISTRATION_CERTIFICATE,

        /**
         * 抹消登记凭证（謄本）。
         * <p>债务还清后办理抹消登记，法务局出具的抹消证明——
         * 证明担保已在登记簿上解除。</p>
         */
        CANCELLATION_CERTIFICATE,

        /**
         * 盘点证据（现场扫描清单与照片）。
         * <p>日本渠道以实地盘点为主要监管手段，盘点证据是担保物
         * 在库状态的直接证明。</p>
         */
        INVENTORY_EVIDENCE,

        /**
         * 处置证据（收车单・变现凭证・损失认定书）。
         */
        DISPOSAL_EVIDENCE
    }

    private Long id;
    private BizType bizType;
    private Long bizId;
    /** 对象键（UUID/哈希，不用原始文件名）。 */
    private String objectKey;
    private String bucket;
    private String region;
    /** 原始文件名仅供展示，不参与寻址。 */
    private String originalFilename;
    private String contentType;
    private long contentLength;
    /** 内容指纹：自证与防替换。 */
    private String sha256;
    private String etag;
    /** Object Lock 模式：COMPLIANCE / GOVERNANCE。 */
    private String objectLockMode;
    private String uploadedBy;
    private LocalDateTime uploadedAt;
    private LocalDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public BizType getBizType() { return bizType; }
    public void setBizType(BizType bizType) { this.bizType = bizType; }

    public Long getBizId() { return bizId; }
    public void setBizId(Long bizId) { this.bizId = bizId; }

    public String getObjectKey() { return objectKey; }
    public void setObjectKey(String objectKey) { this.objectKey = objectKey; }

    public String getBucket() { return bucket; }
    public void setBucket(String bucket) { this.bucket = bucket; }

    public String getRegion() { return region; }
    public void setRegion(String region) { this.region = region; }

    public String getOriginalFilename() { return originalFilename; }
    public void setOriginalFilename(String originalFilename) { this.originalFilename = originalFilename; }

    public String getContentType() { return contentType; }
    public void setContentType(String contentType) { this.contentType = contentType; }

    public long getContentLength() { return contentLength; }
    public void setContentLength(long contentLength) { this.contentLength = contentLength; }

    public String getSha256() { return sha256; }
    public void setSha256(String sha256) { this.sha256 = sha256; }

    public String getEtag() { return etag; }
    public void setEtag(String etag) { this.etag = etag; }

    public String getObjectLockMode() { return objectLockMode; }
    public void setObjectLockMode(String objectLockMode) { this.objectLockMode = objectLockMode; }

    public String getUploadedBy() { return uploadedBy; }
    public void setUploadedBy(String uploadedBy) { this.uploadedBy = uploadedBy; }

    public LocalDateTime getUploadedAt() { return uploadedAt; }
    public void setUploadedAt(LocalDateTime uploadedAt) { this.uploadedAt = uploadedAt; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}

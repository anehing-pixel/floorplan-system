package com.company.floorplan.application.service;

import com.company.floorplan.domain.exception.BusinessException;
import com.company.floorplan.domain.exception.ErrorCode;
import com.company.floorplan.domain.model.Attachment;
import com.company.floorplan.domain.repository.AttachmentRepository;
import com.company.floorplan.domain.service.AttachmentStorage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 附件用例（应用层，ADR-007 决策十）。
 *
 * <p><b>流程</b>：</p>
 * <pre>
 * presign  → 客户端拿上传授权 → 直传对象存储（文件不过应用）
 * complete → 回读对象元数据校验（大小/类型/ETag）→ 落库元数据
 * getUrl   → 签发短时效访问 URL（库里不存永久公开链接）
 * </pre>
 *
 * <p><b>两条不可让步的纪律</b>：</p>
 * <ol>
 *   <li><b>原始文件必须留存</b>——只存解析结果无法自证"当初收到的凭证是什么"；</li>
 *   <li><b>数据库只存元数据</b>——不存 BLOB/Base64，避免撑爆库与备份膨胀。</li>
 * </ol>
 */
@Service
public class AttachmentApplicationService {

    /** 类型白名单：只允许凭证类文件，附件不得落地为可执行文件。 */
    private static final List<String> ALLOWED_TYPES = List.of(
            "application/pdf",
            "image/jpeg",
            "image/png",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "text/csv");

    /** 单文件大小上限：20 MB。 */
    private static final long MAX_SIZE = 20L * 1024 * 1024;

    private final AttachmentRepository attachmentRepository;
    private final AttachmentStorage storage;
    private final Duration uploadExpiry;
    private final Duration downloadExpiry;

    public AttachmentApplicationService(AttachmentRepository attachmentRepository,
                                       AttachmentStorage storage,
                                       @Value("${floorplan.attachments.upload-expire-seconds:900}") long uploadExpireSeconds,
                                       @Value("${floorplan.attachments.download-expire-seconds:300}") long downloadExpireSeconds) {
        this.attachmentRepository = attachmentRepository;
        this.storage = storage;
        this.uploadExpiry = Duration.ofSeconds(uploadExpireSeconds);
        this.downloadExpiry = Duration.ofSeconds(downloadExpireSeconds);
    }

    /**
     * 申请上传授权。
     *
     * @param bizType       业务类型
     * @param bizId         业务对象 ID
     * @param filename      原始文件名
     * @param contentType   内容类型
     * @param contentLength 内容长度
     * @return 上传授权（含对象键）
     */
    public Map<String, Object> presign(Attachment.BizType bizType, Long bizId, String filename,
                                       String contentType, long contentLength) {
        if (bizType == null) {
            throw new BusinessException(ErrorCode.VALID_001, "附件业务类型不能为空");
        }
        if (bizId == null) {
            throw new BusinessException(ErrorCode.VALID_001, "附件业务对象 ID 不能为空");
        }
        if (!ALLOWED_TYPES.contains(contentType)) {
            throw new BusinessException(ErrorCode.VALID_001,
                    "不支持的附件类型：" + contentType + "。允许值：" + String.join(", ", ALLOWED_TYPES));
        }
        if (contentLength <= 0) {
            throw new BusinessException(ErrorCode.VALID_001, "附件大小必须大于 0");
        }
        if (contentLength > MAX_SIZE) {
            throw new BusinessException(ErrorCode.VALID_001,
                    "附件大小超过上限（" + (MAX_SIZE / 1024 / 1024) + " MB）");
        }

        String objectKey = buildObjectKey(bizType, filename);
        AttachmentStorage.PresignedUpload upload =
                storage.presignUpload(objectKey, contentType, contentLength, uploadExpiry);

        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("uploadUrl", upload.uploadUrl());
        result.put("objectKey", upload.objectKey());
        result.put("expiresInSeconds", upload.expiresIn().getSeconds());
        result.put("contentType", contentType);
        result.put("contentLength", contentLength);
        result.put("provider", storage.providerName());
        return result;
    }

    /**
     * 上传完成登记。
     *
     * <p><b>必须回读对象元数据校验</b>——否则"上传 A 登记 B"就成立：
     * 攻击者可以拿合法授权上传一份无害文件，却在库里登记另一份。</p>
     *
     * @param objectKey   对象键
     * @param filename    原始文件名
     * @param contentType 内容类型
     * @param length      声明长度
     * @param bizType     业务类型
     * @param bizId       业务对象 ID
     * @param actor       操作人
     * @return 附件元数据
     */
    public Attachment complete(String objectKey, String filename, String contentType, long length,
                               Attachment.BizType bizType, Long bizId, String actor) {
        AttachmentStorage.ObjectMetadata metadata = storage.head(objectKey);
        if (metadata == null) {
            throw new BusinessException(ErrorCode.VALID_001,
                    "对象不存在或尚未上传成功，请确认已通过上传授权完成直传");
        }
        if (metadata.contentLength() != length) {
            throw new BusinessException(ErrorCode.VALID_001,
                    "上传文件大小与登记声明不一致（实际 " + metadata.contentLength() + "，声明 " + length + "）");
        }
        if (contentType != null && metadata.contentType() != null
                && !contentType.equals(metadata.contentType())) {
            throw new BusinessException(ErrorCode.VALID_001,
                    "上传文件类型与登记声明不一致（实际 " + metadata.contentType() + "，声明 " + contentType + "）");
        }

        Attachment attachment = new Attachment();
        attachment.setBizType(bizType);
        attachment.setBizId(bizId);
        attachment.setObjectKey(objectKey);
        attachment.setOriginalFilename(filename);
        attachment.setContentType(contentType);
        attachment.setContentLength(metadata.contentLength());
        attachment.setEtag(metadata.eTag());
        attachment.setSha256(metadata.sha256());
        attachment.setObjectLockMode("COMPLIANCE");
        attachment.setUploadedBy(actor);
        attachment.setUploadedAt(LocalDateTime.now());
        return attachmentRepository.save(attachment);
    }

    /**
     * 签发访问授权。
     *
     * @param attachmentId 附件 ID
     * @return 访问 URL（短时效）
     */
    public Map<String, Object> accessUrl(Long attachmentId) {
        Attachment attachment = attachmentRepository.findById(attachmentId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND_001, "附件不存在：" + attachmentId));
        String url = storage.presignGet(attachment.getObjectKey(), downloadExpiry);
        if (url == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND_001, "附件对象已不存在，无法访问");
        }
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("url", url);
        result.put("expiresInSeconds", downloadExpiry.getSeconds());
        result.put("filename", attachment.getOriginalFilename());
        result.put("contentType", attachment.getContentType());
        result.put("contentLength", attachment.getContentLength());
        return result;
    }

    /** 列出某业务对象的全部附件。 */
    public List<Attachment> listByBiz(Attachment.BizType bizType, Long bizId) {
        return attachmentRepository.findByBiz(bizType.name(), bizId);
    }

    /** 按 ID 查附件。 */
    public Attachment findById(Long id) {
        return attachmentRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND_001, "附件不存在：" + id));
    }

    /**
     * 生成对象键。
     *
     * <p><b>不用原始文件名</b>——文件名含客户名与车架号会泄露，且可能冲突。</p>
     */
    private String buildObjectKey(Attachment.BizType bizType, String filename) {
        String ext = "";
        if (filename != null && filename.lastIndexOf('.') >= 0) {
            ext = filename.substring(filename.lastIndexOf('.')).toLowerCase(java.util.Locale.ROOT);
            // 白名单校验扩展名，杜绝 .jsp/.sh 之类的可执行文件落地
            if (!List.of(".pdf", ".jpg", ".jpeg", ".png", ".xlsx", ".csv").contains(ext)) {
                ext = "";
            }
        }
        String ym = java.time.LocalDate.now().toString().substring(0, 7).replace("-", "/");
        return bizType.name().toLowerCase(java.util.Locale.ROOT) + "/" + ym + "/"
                + java.util.UUID.randomUUID().toString().replace("-", "") + ext;
    }
}

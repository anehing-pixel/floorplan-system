package com.company.floorplan.web.controller;

import com.company.floorplan.application.service.AttachmentApplicationService;
import com.company.floorplan.domain.model.Attachment;
import com.company.floorplan.web.config.AuditActor;
import com.company.floorplan.web.dto.ApiResponse;
import com.company.floorplan.web.dto.ApiRequests;
import com.company.floorplan.domain.model.Permission;
import com.company.floorplan.web.security.RequirePermission;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 附件接口（ADR-007 决策十）。
 *
 * <p><b>预签名直传三段式</b>：</p>
 * <pre>
 * POST /api/attachments/presign   → 签发上传授权（对象键 + 类型 + 长度被锁定）
 * 客户端直传对象存储              → 文件不过应用（JVM 堆 / 临时目录都不沾）
 * POST /api/attachments/complete  → 回读对象元数据校验后落库元数据
 * </pre>
 *
 * <p><b>为什么要这样绕</b>：文件过应用会让上传带宽打满、multipart 吃内存易 OOM、
 * 应用成为吞吐瓶颈与故障点、临时文件在重启时丢失。绕这一圈换来的是应用无状态。</p>
 *
 * <p><b>权限</b>：签发授权与登记均为 {@code ADMIN}（凭证上传属资金相关写操作）。</p>
 */
@RestController
@RequestMapping("/api/attachments")
public class AttachmentController {

    private final AttachmentApplicationService service;

    public AttachmentController(AttachmentApplicationService service) {
        this.service = service;
    }

    /**
     * 申请上传授权（采购录入岗 / 采购审核岗：随采购链路上传原始凭证）。
     */
    @PostMapping("/presign")
    @RequirePermission(Permission.ATTACHMENT_UPLOAD)
    public ResponseEntity<ApiResponse<Map<String, Object>>> presign(
            @RequestBody ApiRequests.AttachmentPresignRequest body, HttpServletRequest request) {
        Attachment.BizType bizType;
        try {
            bizType = Attachment.BizType.valueOf(body.bizType);
        } catch (Exception e) {
            bizType = Attachment.BizType.PURCHASE_ORDER;
        }
        Map<String, Object> result = service.presign(bizType, body.bizId, body.filename,
                body.contentType, body.contentLength);
        return ResponseEntity.ok(ApiResponse.ok(result, request.getAttribute("traceId") + ""));
    }

    /**
     * 上传完成登记（管理员专属）。
     *
     * <p>服务端会<b>回读对象元数据</b>校验大小与类型，防止"上传 A 登记 B"。</p>
     */
    @PostMapping("/complete")
    @RequirePermission(Permission.ATTACHMENT_UPLOAD)
    public ResponseEntity<ApiResponse<Map<String, Object>>> complete(
            @RequestBody ApiRequests.AttachmentCompleteRequest body, HttpServletRequest request) {
        Attachment.BizType bizType;
        try {
            bizType = Attachment.BizType.valueOf(body.bizType);
        } catch (Exception e) {
            bizType = Attachment.BizType.PURCHASE_ORDER;
        }
        Attachment a = service.complete(body.objectKey, body.filename, body.contentType,
                body.contentLength, bizType, body.bizId, AuditActor.resolve(request));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", a.getId());
        m.put("objectKey", a.getObjectKey());
        m.put("filename", a.getOriginalFilename());
        m.put("contentType", a.getContentType());
        m.put("contentLength", a.getContentLength());
        m.put("sha256", a.getSha256());
        m.put("etag", a.getEtag());
        m.put("objectLockMode", a.getObjectLockMode());
        return ResponseEntity.ok(ApiResponse.ok(m, request.getAttribute("traceId") + ""));
    }

    /**
     * 签发访问授权（短时效）。
     */
    @GetMapping("/{id}/url")
    public ResponseEntity<ApiResponse<Map<String, Object>>> accessUrl(@PathVariable Long id,
                                                                      HttpServletRequest request) {
        Map<String, Object> result = service.accessUrl(id);
        return ResponseEntity.ok(ApiResponse.ok(result, request.getAttribute("traceId") + ""));
    }

    /**
     * 列出某业务对象的附件。
     */
    @GetMapping
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> list(
            @RequestParam("bizType") String bizType,
            @RequestParam("bizId") Long bizId,
            HttpServletRequest request) {
        Attachment.BizType type;
        try {
            type = Attachment.BizType.valueOf(bizType);
        } catch (Exception e) {
            type = Attachment.BizType.PURCHASE_ORDER;
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Attachment a : service.listByBiz(type, bizId)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", a.getId());
            m.put("bizType", a.getBizType() == null ? null : a.getBizType().name());
            m.put("bizId", a.getBizId());
            m.put("filename", a.getOriginalFilename());
            m.put("contentType", a.getContentType());
            m.put("contentLength", a.getContentLength());
            m.put("sha256", a.getSha256());
            m.put("objectLockMode", a.getObjectLockMode());
            m.put("uploadedBy", a.getUploadedBy());
            m.put("uploadedAt", a.getUploadedAt());
            rows.add(m);
        }
        return ResponseEntity.ok(ApiResponse.ok(rows, request.getAttribute("traceId") + ""));
    }

}

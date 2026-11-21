package com.company.floorplan.web.controller;

import com.company.floorplan.domain.service.DirectUploadEndpoint;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 本地存储的上传 / 下载端点（开发与验证环境）。
 *
 * <p><b>仅当附件存储实现为本地磁盘时才有意义</b>——S3 模式下客户端直接
 * 直传 S3 的预签名 URL，不经过本应用的任何端点。</p>
 *
 * <p><b>依赖注入方式</b>：用 {@link ObjectProvider} 而非直接注入，
 * 这样 web 层不依赖 infrastructure 的具体实现类——切换到 S3 时，
 * provider 返回空，本类自动退化为 404。</p>
 */
@RestController
@RequestMapping("/api/attachments")
public class LocalAttachmentEndpointController {

    private final ObjectProvider<DirectUploadEndpoint> endpointProvider;

    public LocalAttachmentEndpointController(ObjectProvider<DirectUploadEndpoint> endpointProvider) {
        this.endpointProvider = endpointProvider;
    }

    /**
     * 接收直传内容。
     *
     * @param key           对象键
     * @param contentType   内容类型
     * @param contentLength 声明长度
     * @param expiresAt     授权过期时刻
     * @param signature     授权签名
     * @param body          文件内容
     */
    @PutMapping("/local-upload")
    public ResponseEntity<Map<String, Object>> upload(
            @RequestParam("key") String key,
            @RequestParam("contentType") String contentType,
            @RequestParam("contentLength") long contentLength,
            @RequestParam("expiresAt") long expiresAt,
            @RequestParam("signature") String signature,
            @RequestBody byte[] body) {

        DirectUploadEndpoint endpoint = endpointProvider.getIfAvailable();
        if (endpoint == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "当前存储实现不支持本端点（请直传对象存储）"));
        }
        if (!endpoint.verifyUploadSignature(key, contentType, contentLength, expiresAt, signature)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "上传授权无效或已过期"));
        }
        if (body.length != contentLength) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("error", "文件大小与授权声明不一致"));
        }
        String sha256 = endpoint.put(key, body);
        return ResponseEntity.ok(Map.of("objectKey", key, "sha256", sha256, "size", body.length));
    }

    /**
     * 凭签名下载。
     */
    @GetMapping("/local-download")
    public ResponseEntity<byte[]> download(
            @RequestParam("key") String key,
            @RequestParam("expiresAt") long expiresAt,
            @RequestParam("signature") String signature) {

        DirectUploadEndpoint endpoint = endpointProvider.getIfAvailable();
        if (endpoint == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        if (!endpoint.verifyGetSignature(key, expiresAt, signature)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        byte[] content = endpoint.get(key);
        if (content == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + key + "\"")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(content);
    }
}

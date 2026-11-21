package com.company.floorplan.infrastructure.integration;

import com.company.floorplan.domain.service.AttachmentStorage;
import com.company.floorplan.domain.service.DirectUploadEndpoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Conditional;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Base64;
import java.util.UUID;

/**
 * 本地磁盘附件存储（开发/验证用实现）。
 *
 * <p><b>定位</b>：本机没有 AWS 凭证，S3 实现无法实际连通。
 * 本实现与 {@link S3AttachmentStorage} 实现<b>同一个 {@link AttachmentStorage} 接口</b>，
 * 因此上层业务、接口契约、前端页面完全一致——切换存储只改配置，不动业务。</p>
 *
 * <p><b>与 S3 的差异（必须知道）</b>：</p>
 * <ul>
 *   <li>无 Object Lock：本地文件可被删除，<b>不具备 S3 的不可删改强保证</b>；</li>
 *   <li>预签名 URL 指向应用自身的上传端点，而非对象存储直传。</li>
 * </ul>
 *
 * <p><b>生产环境必须切换到 S3</b>——金融凭证的留存要求依赖 Object Lock，
 * 本地磁盘提供不了这个保证。</p>
 */
@Component
@Conditional(S3BucketNotConfiguredCondition.class)
public class LocalAttachmentStorage implements AttachmentStorage, DirectUploadEndpoint {

    private static final Logger log = LoggerFactory.getLogger(LocalAttachmentStorage.class);

    private final Path root;
    private final String tokenSecret;

    public LocalAttachmentStorage(
            @Value("${floorplan.attachments.local.root:./data/attachments}") String root,
            @Value("${floorplan.attachments.local.token-secret:dev-local-attachment-token}") String tokenSecret) {
        this.root = Paths.get(root).toAbsolutePath().normalize();
        this.tokenSecret = tokenSecret;
        try {
            Files.createDirectories(this.root);
        } catch (IOException e) {
            throw new IllegalStateException("无法创建附件目录：" + this.root, e);
        }
        log.info("本地附件存储已启用，根目录={}（生产环境应切换为 S3）", this.root);
    }

    @Override
    public PresignedUpload presignUpload(String objectKey, String contentType, long contentLength, Duration expiresIn) {
        // 用 HMAC 风格的签名 token 把授权绑定到 objectKey + contentType + contentLength + 过期时刻，
        // 与 S3 预签名的约束力对齐：授权 A 不能用来传 B。
        long expiresAt = System.currentTimeMillis() + expiresIn.toMillis();
        String signature = sign(objectKey + "|" + contentType + "|" + contentLength + "|" + expiresAt);
        String url = "/api/attachments/local-upload?key=" + urlEncode(objectKey)
                + "&contentType=" + urlEncode(contentType)
                + "&contentLength=" + contentLength
                + "&expiresAt=" + expiresAt
                + "&signature=" + signature;
        return new PresignedUpload(url, objectKey, expiresIn, contentType, contentLength);
    }

    /**
     * 校验上传授权签名。
     *
     * @param objectKey     对象键
     * @param contentType   内容类型
     * @param contentLength 内容长度
     * @param expiresAt     过期时刻（毫秒）
     * @param signature     签名
     * @return true 表示授权有效
     */
    @Override
    public boolean verifyUploadSignature(String objectKey, String contentType, long contentLength,
                                         long expiresAt, String signature) {
        if (expiresAt < System.currentTimeMillis()) {
            return false;
        }
        String expected = sign(objectKey + "|" + contentType + "|" + contentLength + "|" + expiresAt);
        // 定长比较，避免时序侧信道
        if (expected.length() != signature.length()) {
            return false;
        }
        int diff = 0;
        for (int i = 0; i < expected.length(); i++) {
            diff |= expected.charAt(i) ^ signature.charAt(i);
        }
        return diff == 0;
    }

    /**
     * 写入对象（由本地上传端点调用）。
     *
     * @param objectKey 对象键
     * @param bytes     内容
     * @return 内容 SHA-256
     */
    @Override
    public String put(String objectKey, byte[] bytes) {
        try {
            Path target = resolve(objectKey);
            Files.createDirectories(target.getParent());
            Files.write(target, bytes);
            return sha256(bytes);
        } catch (IOException e) {
            throw new IllegalStateException("附件写入失败：" + objectKey, e);
        }
    }

    /**
     * 读取对象。
     *
     * @param objectKey 对象键
     * @return 内容字节；不存在时返回 null
     */
    @Override
    public byte[] get(String objectKey) {
        try {
            Path target = resolve(objectKey);
            if (!Files.exists(target)) {
                return null;
            }
            return Files.readAllBytes(target);
        } catch (IOException e) {
            return null;
        }
    }

    @Override
    public ObjectMetadata head(String objectKey) {
        try {
            Path target = resolve(objectKey);
            if (!Files.exists(target)) {
                return null;
            }
            byte[] bytes = Files.readAllBytes(target);
            return new ObjectMetadata(objectKey, bytes.length, guessContentType(objectKey),
                    Base64.getEncoder().encodeToString(sha256Bytes(bytes)), sha256(bytes));
        } catch (IOException e) {
            return null;
        }
    }

    @Override
    public String presignGet(String objectKey, Duration expiresIn) {
        Path target = resolve(objectKey);
        if (!Files.exists(target)) {
            return null;
        }
        // 本地实现直接返回下载端点（同样带时效校验）
        long expiresAt = System.currentTimeMillis() + expiresIn.toMillis();
        String signature = sign(objectKey + "|GET|" + expiresAt);
        return "/api/attachments/local-download?key=" + urlEncode(objectKey)
                + "&expiresAt=" + expiresAt + "&signature=" + signature;
    }

    /**
     * 校验下载授权签名。
     *
     * @param objectKey 对象键
     * @param expiresAt 过期时刻
     * @param signature 签名
     * @return true 表示授权有效
     */
    @Override
    public boolean verifyGetSignature(String objectKey, long expiresAt, String signature) {
        if (expiresAt < System.currentTimeMillis()) {
            return false;
        }
        String expected = sign(objectKey + "|GET|" + expiresAt);
        if (expected.length() != signature.length()) {
            return false;
        }
        int diff = 0;
        for (int i = 0; i < expected.length(); i++) {
            diff |= expected.charAt(i) ^ signature.charAt(i);
        }
        return diff == 0;
    }

    @Override
    public String providerName() {
        return "local";
    }

    @Override
    public String uploadEndpointPath() {
        return "/api/attachments/local-upload";
    }

    @Override
    public String downloadEndpointPath() {
        return "/api/attachments/local-download";
    }

    private Path resolve(String objectKey) {
        Path p = root.resolve(objectKey).normalize();
        // 防目录穿越：解析后必须仍在根目录内
        if (!p.startsWith(root)) {
            throw new IllegalArgumentException("非法的对象键：" + objectKey);
        }
        return p;
    }

    private String sign(String payload) {
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(
                    tokenSecret.getBytes(java.nio.charset.StandardCharsets.UTF_8), "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(mac.doFinal(payload.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("签名计算失败", e);
        }
    }

    private static String guessContentType(String objectKey) {
        String lower = objectKey.toLowerCase(java.util.Locale.ROOT);
        if (lower.endsWith(".pdf")) {
            return "application/pdf";
        }
        if (lower.endsWith(".png")) {
            return "image/png";
        }
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
            return "image/jpeg";
        }
        if (lower.endsWith(".xlsx")) {
            return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
        }
        if (lower.endsWith(".csv")) {
            return "text/csv";
        }
        return "application/octet-stream";
    }

    private static String urlEncode(String s) {
        return java.net.URLEncoder.encode(s, java.nio.charset.StandardCharsets.UTF_8);
    }

    private static String sha256(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(sha256Bytes(bytes));
    }

    private static byte[] sha256Bytes(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    /** 生成对象键：按年月分目录 + UUID，不用原始文件名（含客户名与车架号会泄露）。 */
    public static String buildObjectKey(String bizType, String extension) {
        String ym = java.time.LocalDate.now().toString().substring(0, 7).replace("-", "/");
        return bizType.toLowerCase(java.util.Locale.ROOT) + "/" + ym + "/"
                + UUID.randomUUID().toString().replace("-", "")
                + (extension == null || extension.isBlank() ? "" : extension);
    }
}

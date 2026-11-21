package com.company.floorplan.infrastructure.integration;

import com.company.floorplan.domain.service.AttachmentStorage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Conditional;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * Amazon S3 附件存储实现（ADR-007 决策十）。
 *
 * <p><b>为什么是 S3 而不是自建 MinIO</b>：核心依据不是"便宜好用"，
 * 而是 <b>Object Lock 在存储层强制"一次写入、不可删改"</b>——
 * 金融凭证要的是这个强保证，而不是应用层写一句"禁止删除"。
 * 运维误删、脚本清空、生命周期规则误配，都删不掉已锁定的对象。</p>
 *
 * <p><b>桶的推荐配置</b>（由运维在 AWS 侧配置，本类只负责读写）：</p>
 * <ul>
 *   <li>Region：东京 / 大阪（数据驻留）；</li>
 *   <li>Versioning：开启（可追加版本，凭证类附件只能追加不能替换）；</li>
 *   <li>Object Lock：COMPLIANCE 模式（合规保留期到期内不可删改）；</li>
 *   <li>服务端加密：SSE-KMS；</li>
 *   <li>MFA Delete：开启；</li>
 *   <li><b>桶不公开读</b>——访问一律由应用签发短时效预签名 GET URL。</li>
 * </ul>
 *
 * <p><b>凭证纪律</b>：走 IAM Role 或环境变量注入，<b>不入库不入代码</b>
 * （与既有 {@code PAYMENT_WEBHOOK_SECRET} 同一纪律）。</p>
 */
@Component
@Conditional(S3BucketConfiguredCondition.class)
public class S3AttachmentStorage implements AttachmentStorage {

    private final S3Client s3;
    private final S3Presigner presigner;
    private final String bucket;
    private final String region;

    public S3AttachmentStorage(
            @Value("${floorplan.attachments.s3.bucket:}") String bucket,
            @Value("${floorplan.attachments.s3.region:ap-northeast-1}") String region) {
        this.bucket = bucket;
        this.region = region;
        // 默认凭证链：环境变量 → 系统属性 → 实例 Profile → 容器角色。
        // 不在代码里写 AccessKey，也不在配置里存明文密钥。
        this.s3 = S3Client.builder()
                .region(Region.of(region))
                .build();
        this.presigner = S3Presigner.builder()
                .region(Region.of(region))
                .build();
    }

    @Override
    public PresignedUpload presignUpload(String objectKey, String contentType, long contentLength, Duration expiresIn) {
        Map<String, String> metadata = new HashMap<>();
        // 把内容类型与长度写进签名条件：授权只能用于上传这一份特定类型与尺寸的对象。
        metadata.put("content-type", contentType);
        metadata.put("content-length", String.valueOf(contentLength));

        PutObjectRequest putRequest = PutObjectRequest.builder()
                .bucket(bucket)
                .key(objectKey)
                .contentType(contentType)
                .contentLength(contentLength)
                .metadata(metadata)
                .build();

        PutObjectPresignRequest presignRequest = PutObjectPresignRequest.builder()
                .signatureDuration(expiresIn)
                .putObjectRequest(putRequest)
                .build();

        String url = presigner.presignPutObject(presignRequest).url().toString();
        return new PresignedUpload(url, objectKey, expiresIn, contentType, contentLength);
    }

    @Override
    public ObjectMetadata head(String objectKey) {
        try {
            HeadObjectResponse resp = s3.headObject(HeadObjectRequest.builder()
                    .bucket(bucket).key(objectKey).build());
            return new ObjectMetadata(objectKey, resp.contentLength(),
                    resp.contentType(), resp.eTag(), null);
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public String presignGet(String objectKey, Duration expiresIn) {
        GetObjectRequest getRequest = GetObjectRequest.builder()
                .bucket(bucket).key(objectKey).build();
        GetObjectPresignRequest presignRequest = GetObjectPresignRequest.builder()
                .signatureDuration(expiresIn)
                .getObjectRequest(getRequest)
                .build();
        return presigner.presignGetObject(presignRequest).url().toString();
    }

    /**
     * 直接写入对象（供测试与内部流程使用）。
     *
     * @param objectKey 对象键
     * @param bytes     内容
     */
    public void put(String objectKey, byte[] bytes) {
        s3.putObject(PutObjectRequest.builder().bucket(bucket).key(objectKey).build(),
                RequestBody.fromBytes(bytes));
    }

    @Override
    public String providerName() {
        return "s3";
    }

    public String getBucket() {
        return bucket;
    }

    public String getRegion() {
        return region;
    }
}

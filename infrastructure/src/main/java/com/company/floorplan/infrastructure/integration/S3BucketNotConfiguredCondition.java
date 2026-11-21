package com.company.floorplan.infrastructure.integration;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * 条件装配判定：{@code floorplan.attachments.s3.bucket} <b>未配置或为空</b>。
 *
 * <p>此时装配本地磁盘实现，保证开发与验证环境无需任何 AWS 凭证即可跑通附件链路。
 * 与 {@link S3BucketConfiguredCondition} 严格互补——两者同时存在，
 * 保证 {@code AttachmentStorage} 任何时候都有且只有一个实现，
 * 不会出现注入歧义。</p>
 */
public class S3BucketNotConfiguredCondition implements Condition {

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        Environment env = context.getEnvironment();
        String bucket = env.getProperty(S3BucketConfiguredCondition.BUCKET_PROPERTY);
        return bucket == null || bucket.isBlank();
    }
}

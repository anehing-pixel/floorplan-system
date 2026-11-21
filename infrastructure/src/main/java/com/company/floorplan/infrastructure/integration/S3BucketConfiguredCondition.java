package com.company.floorplan.infrastructure.integration;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.core.env.Environment;

/**
 * 条件装配判定：{@code floorplan.attachments.s3.bucket} 是否为<b>非空</b>值。
 *
 * <p><b>为什么不能用 {@code @ConditionalOnProperty}</b>：</p>
 * <p>配置文件里写的是 {@code bucket: ${ATTACHMENTS_S3_BUCKET:}}——未设环境变量时
 * 它解析成<b>空字符串</b>，而属性<b>仍然存在</b>。
 * {@code @ConditionalOnProperty(name = "bucket")} 的语义是"属性存在即匹配"，
 * 于是 S3 实现被装配；与此同时 Local 实现用 {@code matchIfMissing = true}
 * 也被装配（因为从 Spring 的角度看属性是存在的，不是 missing）——
 * <b>两个 Bean 同时存在，注入时抛歧义错误</b>。</p>
 *
 * <p>本条件按<b>值</b>判断而不是按<b>存在性</b>判断：空串视为"未配置"，
 * 于是 S3 不装配、Local 装配，行为与配置语义一致。</p>
 */
public class S3BucketConfiguredCondition implements Condition {

    /** 属性名。 */
    public static final String BUCKET_PROPERTY = "floorplan.attachments.s3.bucket";

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        Environment env = context.getEnvironment();
        String bucket = env.getProperty(BUCKET_PROPERTY);
        return bucket != null && !bucket.isBlank();
    }
}

package com.company.floorplan.domain.service;

import java.time.Duration;

/**
 * 附件存储接缝（ADR-007 决策十）。
 *
 * <p><b>核心约束：预签名 URL 直传，文件不过应用。</b>
 * 若让文件流经应用（JVM 堆 / 临时目录），会带来四个问题：</p>
 * <ol>
 *   <li>大文件打满带宽，文件上传会拖垮整个应用；</li>
 *   <li>multipart 解析吃内存，多并发下易 OOM；</li>
 *   <li>应用成为吞吐瓶颈与单点故障——扩容应用才能扩容上传；</li>
 *   <li>临时文件在应用重启时丢失。</li>
 * </ol>
 *
 * <p>因此本接口只负责三件事：<b>签发上传授权、回读对象元数据、签发访问授权</b>。
 * 文件本体由对象存储直接与客户端交互。</p>
 */
public interface AttachmentStorage {

    /** 上传授权。 */
    record PresignedUpload(String uploadUrl, String objectKey, Duration expiresIn, String contentType, long contentLength) {
    }

    /** 对象元数据（回读校验用）。 */
    record ObjectMetadata(String objectKey, long contentLength, String contentType, String eTag, String sha256) {
    }

    /**
     * 签发上传授权。
     *
     * <p><b>签名参数必须锁定单对象 key + Content-Type + Content-Length</b>，
     * 否则"授权 A 传 B"就成立——攻击者拿到 A 的授权可以传任意内容。</p>
     *
     * @param objectKey     对象键（UUID/哈希，不用原始文件名）
     * @param contentType   内容类型
     * @param contentLength 内容长度（字节）
     * @param expiresIn     有效期
     * @return 上传授权
     */
    PresignedUpload presignUpload(String objectKey, String contentType, long contentLength, Duration expiresIn);

    /**
     * 回读对象元数据（上传完成登记时校验）。
     *
     * @param objectKey 对象键
     * @return 对象元数据；对象不存在时返回 {@code null}
     */
    ObjectMetadata head(String objectKey);

    /**
     * 签发访问授权（短时效预签名 GET URL）。
     *
     * @param objectKey 对象键
     * @param expiresIn 有效期
     * @return 访问 URL；对象不存在时返回 {@code null}
     */
    String presignGet(String objectKey, Duration expiresIn);

    /** 存储实现标识（如 {@code s3} / {@code local}），用于后台展示与排查。 */
    String providerName();
}

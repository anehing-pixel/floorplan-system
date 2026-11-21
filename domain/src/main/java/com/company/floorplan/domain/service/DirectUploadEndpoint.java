package com.company.floorplan.domain.service;

import java.time.Duration;

/**
 * 直传端点能力（可选接缝）。
 *
 * <p><b>为什么单独拆一个接口</b>：本地磁盘实现没有真正的"对象存储直传"——
 * 它必须由应用提供一个端点来接收文件；而 S3 实现不需要（客户端直传 S3）。
 * 若把这两个端点写进 {@link AttachmentStorage}，web 层就必须依赖
 * infrastructure 层的具体实现类，分层就破了。</p>
 *
 * <p>拆出本接口后：web 层只依赖领域接口，由 Spring 条件装配决定注入哪个实现；
 * 切换 S3 时，本地端点的 Bean 整体不装配，客户端自然改为直传 S3。</p>
 */
public interface DirectUploadEndpoint {

    /**
     * 本地上传端点的路径（仅本地实现提供）。
     *
     * @return 端点路径；不支持时返回 null
     */
    String uploadEndpointPath();

    /**
     * 本地下载端点的路径（仅本地实现提供）。
     *
     * @return 端点路径；不支持时返回 null
     */
    String downloadEndpointPath();

    /** 校验上传授权签名。 */
    boolean verifyUploadSignature(String objectKey, String contentType, long contentLength,
                                  long expiresAt, String signature);

    /** 校验下载授权签名。 */
    boolean verifyGetSignature(String objectKey, long expiresAt, String signature);

    /** 写入对象。 */
    String put(String objectKey, byte[] bytes);

    /** 读取对象。 */
    byte[] get(String objectKey);
}

package com.company.floorplan.domain.repository;

import com.company.floorplan.domain.model.Attachment;

import java.util.List;
import java.util.Optional;

/**
 * 附件元数据仓储（文件本体在对象存储，库里只有元数据）。
 */
public interface AttachmentRepository {

    Optional<Attachment> findById(Long id);

    /** 按对象键查询（预签名完成登记时按 key 回查）。 */
    Optional<Attachment> findByObjectKey(String objectKey);

    /** 按业务对象查询全部附件。 */
    List<Attachment> findByBiz(String bizType, Long bizId);

    Attachment save(Attachment attachment);
}

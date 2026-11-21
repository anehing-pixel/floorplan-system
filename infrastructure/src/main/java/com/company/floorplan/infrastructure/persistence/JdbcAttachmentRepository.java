package com.company.floorplan.infrastructure.persistence;

import com.company.floorplan.domain.model.Attachment;
import com.company.floorplan.domain.repository.AttachmentRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 附件元数据仓储实现（基础设施层）。
 *
 * <p>只存元数据，不存文件本体。</p>
 */
@Repository
public class JdbcAttachmentRepository implements AttachmentRepository {

    private final JdbcTemplate jdbc;

    private static final RowMapper<Attachment> MAPPER = (rs, i) -> {
        Attachment a = new Attachment();
        a.setId(rs.getLong("id"));
        String bizType = rs.getString("biz_type");
        a.setBizType(bizType == null ? null : Attachment.BizType.valueOf(bizType));
        a.setBizId(rs.getLong("biz_id"));
        a.setObjectKey(rs.getString("object_key"));
        a.setBucket(rs.getString("bucket"));
        a.setRegion(rs.getString("region"));
        a.setOriginalFilename(rs.getString("original_filename"));
        a.setContentType(rs.getString("content_type"));
        a.setContentLength(rs.getLong("content_length"));
        a.setSha256(rs.getString("sha256"));
        a.setEtag(rs.getString("etag"));
        a.setObjectLockMode(rs.getString("object_lock_mode"));
        a.setUploadedBy(rs.getString("uploaded_by"));
        a.setUploadedAt(rs.getTimestamp("uploaded_at") == null ? null
                : rs.getTimestamp("uploaded_at").toLocalDateTime());
        a.setCreatedAt(rs.getTimestamp("created_at") == null ? null
                : rs.getTimestamp("created_at").toLocalDateTime());
        return a;
    };

    public JdbcAttachmentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<Attachment> findById(Long id) {
        return jdbc.query("SELECT * FROM attachments WHERE id = ?", MAPPER, id).stream().findFirst();
    }

    @Override
    public Optional<Attachment> findByObjectKey(String objectKey) {
        return jdbc.query("SELECT * FROM attachments WHERE object_key = ?", MAPPER, objectKey)
                .stream().findFirst();
    }

    @Override
    public List<Attachment> findByBiz(String bizType, Long bizId) {
        return jdbc.query("SELECT * FROM attachments WHERE biz_type = ? AND biz_id = ? ORDER BY id",
                MAPPER, bizType, bizId);
    }

    @Override
    public Attachment save(Attachment a) {
        if (a.getId() == null) {
            Long id = jdbc.queryForObject(
                    "INSERT INTO attachments (biz_type, biz_id, object_key, bucket, region, original_filename, "
                            + "content_type, content_length, sha256, etag, object_lock_mode, uploaded_by, uploaded_at) "
                            + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?) RETURNING id",
                    Long.class,
                    a.getBizType() == null ? null : a.getBizType().name(), a.getBizId(),
                    a.getObjectKey(), a.getBucket(), a.getRegion(), a.getOriginalFilename(),
                    a.getContentType(), a.getContentLength(), a.getSha256(), a.getEtag(),
                    a.getObjectLockMode(), a.getUploadedBy(),
                    a.getUploadedAt() == null ? null : java.sql.Timestamp.valueOf(a.getUploadedAt()));
            a.setId(id);
        } else {
            jdbc.update("UPDATE attachments SET biz_type=?, biz_id=?, object_key=?, bucket=?, region=?, "
                            + "original_filename=?, content_type=?, content_length=?, sha256=?, etag=?, "
                            + "object_lock_mode=?, uploaded_by=?, uploaded_at=? WHERE id=?",
                    a.getBizType() == null ? null : a.getBizType().name(), a.getBizId(),
                    a.getObjectKey(), a.getBucket(), a.getRegion(), a.getOriginalFilename(),
                    a.getContentType(), a.getContentLength(), a.getSha256(), a.getEtag(),
                    a.getObjectLockMode(), a.getUploadedBy(),
                    a.getUploadedAt() == null ? null : java.sql.Timestamp.valueOf(a.getUploadedAt()), a.getId());
        }
        return a;
    }
}

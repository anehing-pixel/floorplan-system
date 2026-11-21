package com.company.floorplan.infrastructure.persistence;

import com.company.floorplan.domain.model.RatingConfig;
import com.company.floorplan.domain.repository.RatingConfigRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 授信评级分级口径仓储实现（PostgreSQL / JdbcTemplate）。
 *
 * <p><b>为什么用 ON CONFLICT upsert</b>：口径变更要求"要么整行写入成功、
 * 要么不变"，{@code INSERT ... ON CONFLICT (grade) DO UPDATE} 在单条 SQL
 * 内完成新增或修改，天然规避并发下的主键冲突报错。</p>
 */
@Repository
public class JdbcRatingConfigRepository implements RatingConfigRepository {

    private final JdbcTemplate jdbc;

    /** 行映射：下划线列名 → 驼峰字段。 */
    private static final RowMapper<RatingConfig> MAPPER = (rs, i) -> {
        RatingConfig c = new RatingConfig();
        c.setGrade(rs.getString("grade"));
        c.setGradeName(rs.getString("grade_name"));
        c.setRateSurcharge(rs.getBigDecimal("rate_surcharge"));
        c.setAllowed(rs.getBoolean("allowed"));
        c.setSortOrder(rs.getInt("sort_order"));
        c.setRemark(rs.getString("remark"));
        Timestamp updated = rs.getTimestamp("updated_at");
        c.setUpdatedAt(updated == null ? null : updated.toLocalDateTime());
        c.setUpdatedBy(rs.getString("updated_by"));
        return c;
    };

    public JdbcRatingConfigRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<RatingConfig> findAll() {
        return jdbc.query("SELECT * FROM rating_configs ORDER BY sort_order, grade", MAPPER);
    }

    @Override
    public Optional<RatingConfig> findByGrade(String grade) {
        return jdbc.query("SELECT * FROM rating_configs WHERE grade = ?", MAPPER, grade)
                .stream().findFirst();
    }

    @Override
    public RatingConfig save(RatingConfig c) {
        jdbc.update("INSERT INTO rating_configs (grade, grade_name, rate_surcharge, allowed, "
                        + "sort_order, remark, updated_at, updated_by) VALUES (?,?,?,?,?,?,now(),?) "
                        + "ON CONFLICT (grade) DO UPDATE SET grade_name=EXCLUDED.grade_name, "
                        + "rate_surcharge=EXCLUDED.rate_surcharge, allowed=EXCLUDED.allowed, "
                        + "sort_order=EXCLUDED.sort_order, remark=EXCLUDED.remark, "
                        + "updated_at=now(), updated_by=EXCLUDED.updated_by",
                c.getGrade(), c.getGradeName(), c.getRateSurcharge(), c.isAllowed(),
                c.getSortOrder(), c.getRemark(), c.getUpdatedBy());
        return c;
    }
}

package com.company.floorplan.infrastructure.persistence;

import com.company.floorplan.domain.model.UsedConfigParam;
import com.company.floorplan.domain.repository.UsedConfigRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;

/**
 * USED 域参数读取实现（表 {@code used_config_params}，V41）。
 *
 * <p><b>只做读，且只做"当前生效"这一件事</b>：V41 用 {@code uk_ucp_active_key}
 * 保证同键同时最多一条 {@code ACTIVE}，所以按键取 ACTIVE 行最多一条；
 * 在此之上再用实体的 {@link UsedConfigParam#isActiveOn(LocalDate)} 复核生效区间
 * （半开区间 {@code [effective_on, expired_on)}，口径来自 V41 的列注释）。
 * 区间不满足就返回空，让调用方走"缺参数"分支——
 * 拿一条还没生效或已经失效的值，等于用参数表伪装成"配置已签字"。</p>
 *
 * <p><b>缺行不猜默认值</b>：解析全部走实体方法，实体内部对"值与声明类型不符"抛
 * {@code SYS_001}（那是配置事故）。本类不缓存、不做兜底常量：
 * 缓存会让"参数改完立刻生效"这条验收变成发版问题，兜底常量则直接违反
 * {@code [待定源]} 数值不得进代码的纪律。</p>
 */
@Repository
public class JdbcUsedConfigRepository implements UsedConfigRepository {

    private final JdbcTemplate jdbc;

    public JdbcUsedConfigRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 参数行映射：值类型、状态都用字符串原样带出，解析规则集中在实体里。 */
    private static final RowMapper<UsedConfigParam> MAPPER = (rs, i) -> {
        UsedConfigParam p = new UsedConfigParam();
        p.setId(rs.getLong("id"));
        p.setParamKey(rs.getString("param_key"));
        p.setParamValue(rs.getString("param_value"));
        p.setValueType(rs.getString("value_type"));
        p.setUnit(rs.getString("unit"));
        p.setStatus(rs.getString("status"));
        p.setEffectiveOn(toLocalDate(rs.getDate("effective_on")));
        p.setExpiredOn(toLocalDate(rs.getDate("expired_on")));
        p.setSourceRef(rs.getString("source_ref"));
        p.setApprovedBy(rs.getString("approved_by"));
        p.setNote(rs.getString("note"));
        p.setCreatedAt(toInstant(rs.getTimestamp("created_at")));
        p.setUpdatedAt(toInstant(rs.getTimestamp("updated_at")));
        p.setCreatedBy(rs.getString("created_by"));
        p.setUpdatedBy(rs.getString("updated_by"));
        int version = rs.getInt("version");
        p.setVersion(rs.wasNull() ? null : version);
        return p;
    };

    @Override
    public Optional<UsedConfigParam> findActive(String paramKey, LocalDate bizDate) {
        if (paramKey == null || paramKey.isBlank()) {
            return Optional.empty();
        }
        // idempotent 读取：同键多行历史（DRAFT/RETIRED）都在表里，ACTIVE 唯一由索引保证。
        Optional<UsedConfigParam> row = jdbc.query(
                "SELECT * FROM used_config_params WHERE param_key = ? AND status = 'ACTIVE'",
                MAPPER, paramKey.trim()).stream().findFirst();
        // 区间复核放在内存里做：SQL 里写 (effective_on <= ? AND (expired_on IS NULL OR expired_on > ?))
        // 也能得到同样结果，但"哪天算生效"的口径就同时活在 SQL 和实体两处，改一侧必漏另一侧。
        return row.filter(p -> p.isActiveOn(bizDate));
    }

    @Override
    public Optional<Integer> findInt(String paramKey, LocalDate bizDate) {
        // 只接受声明为整数族的行：拿 DECIMAL "1.5" 去 parseInt 会炸在运行期。
        return findActive(paramKey, bizDate)
                .filter(JdbcUsedConfigRepository::isIntegerType)
                .map(UsedConfigParam::asInt);
    }

    @Override
    public Optional<BigDecimal> findDecimal(String paramKey, LocalDate bizDate) {
        return findActive(paramKey, bizDate)
                .filter(JdbcUsedConfigRepository::isDecimalType)
                .map(UsedConfigParam::asDecimal);
    }

    @Override
    public Optional<Boolean> findBoolean(String paramKey, LocalDate bizDate) {
        return findActive(paramKey, bizDate)
                .filter(p -> "BOOLEAN".equals(p.getValueType()))
                .map(UsedConfigParam::asBoolean);
    }

    /** INT 与 BIGINT 都算整数族（V41 的 ck_ucp_value_type 就是这么分的）。 */
    private static boolean isIntegerType(UsedConfigParam p) {
        return "INT".equals(p.getValueType()) || "BIGINT".equals(p.getValueType());
    }

    /** DECIMAL 用于比例/阈值；整数族也允许按十进制读，避免"1"被当成解析失败。 */
    private static boolean isDecimalType(UsedConfigParam p) {
        return "DECIMAL".equals(p.getValueType()) || isIntegerType(p);
    }

    private static LocalDate toLocalDate(Date value) {
        return value == null ? null : value.toLocalDate();
    }

    private static Instant toInstant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }
}

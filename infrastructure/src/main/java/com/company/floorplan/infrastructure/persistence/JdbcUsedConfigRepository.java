package com.company.floorplan.infrastructure.persistence;

import com.company.floorplan.domain.repository.UsedConfigRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * USED 可调参数仓储 JDBC 实现（{@code used_config_params}，V41 建表、V75 起逐步种值）。
 *
 * <p><b>不做进程内缓存</b>：一次带 {@code uk_ucp_active_key} 部分索引的点查，
 * 成本远低于「参数改了但某个实例还在用旧值」的排查成本。风控参数的调整通常发生在营业中，
 * 缓存会让「改了没生效」变成新一轮口头争论——而这张表存在的唯一理由就是让参数可运行时改。</p>
 *
 * <p><b>读不到值时返回 empty，不抛异常、不回落默认</b>：V41 的读侧链条是
 * 「库值 → 请求显式值 → fail-closed」，回落硬编码默认值那一支属于调用方的 fail-closed 策略，
 * 不在这里做。若在仓储里塞默认值，参数表就退化成「可选覆盖」，
 * 而未签字数值又回到了代码里——正是 V41 反对的事。</p>
 */
@Repository
public class JdbcUsedConfigRepository implements UsedConfigRepository {

    private static final Logger log = LoggerFactory.getLogger(JdbcUsedConfigRepository.class);

    private final JdbcTemplate jdbc;

    public JdbcUsedConfigRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<String> findActiveValue(String paramKey) {
        if (paramKey == null || paramKey.isBlank()) {
            return Optional.empty();
        }
        // 生效区间用「区间包含今天」判定：effective_on 为 NULL 表示立即生效，
        // expired_on 为 NULL 表示尚未失效（本表禁 DELETE，失效只靠置 RETIRED 或填 expired_on）。
        List<String> values = jdbc.queryForList(
                "SELECT param_value FROM used_config_params "
                        + "WHERE param_key = ? AND status = 'ACTIVE' "
                        + "AND (effective_on IS NULL OR effective_on <= ?) "
                        + "AND (expired_on IS NULL OR expired_on >= ?) "
                        + "ORDER BY id DESC LIMIT 1",
                String.class, paramKey, Date.valueOf(LocalDate.now()), Date.valueOf(LocalDate.now()));
        return values.stream().findFirst();
    }

    @Override
    public Optional<Integer> findInt(String paramKey) {
        Optional<String> raw = findActiveValue(paramKey);
        if (raw.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(Integer.valueOf(raw.get().trim()));
        } catch (NumberFormatException ex) {
            // 脏值必须出声：它会让调用方走 fail-closed 分支，看起来像「参数没配」，
            // 而真实原因是有人把 180 写成了 180天。没有这行日志就得靠猜。
            log.error("USED 参数值非法，按未配置处理 key={} value={}", paramKey, raw.get());
            return Optional.empty();
        }
    }

    @Override
    public List<ConfigParam> list(String status) {
        if (status == null || status.isBlank()) {
            return jdbc.query("SELECT * FROM used_config_params ORDER BY param_key, id DESC", MAPPER);
        }
        return jdbc.query("SELECT * FROM used_config_params WHERE status = ? "
                + "ORDER BY param_key, id DESC", MAPPER, status.trim().toUpperCase(java.util.Locale.ROOT));
    }

    @Override
    public void putActive(ConfigParam param, String operator) {
        String key = param.paramKey();
        // 1) 旧 ACTIVE 行转 RETIRED 并补失效日——本表禁 DELETE，历史值必须留得住（D04 §11）。
        //    不先降级就 INSERT 会撞 uk_ucp_active_key 部分唯一索引。
        int retired = jdbc.update("UPDATE used_config_params SET status = 'RETIRED', "
                + "expired_on = COALESCE(expired_on, ?), updated_by = ?, updated_at = now() "
                + "WHERE param_key = ? AND status = 'ACTIVE'",
                Date.valueOf(LocalDate.now()), operator, key);
        // 2) 新值入 ACTIVE。
        jdbc.update("INSERT INTO used_config_params (param_key, param_value, value_type, unit, status, "
                        + "effective_on, source_ref, approved_by, note, created_by, updated_by) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                key, param.paramValue(),
                param.valueType() == null ? "STRING" : param.valueType(),
                param.unit(), "ACTIVE",
                param.effectiveOn() == null ? Date.valueOf(LocalDate.now()) : Date.valueOf(param.effectiveOn()),
                param.sourceRef(), param.approvedBy() == null ? operator : param.approvedBy(),
                param.note(), operator, operator);
        // 调整痕迹要出声：这是「谁在什么时候把风控口径改了」的唯一现场记录（表里没有单独的变更日志）。
        log.warn("USED 参数调整 key={} 旧行数={} 新值={} operator={}", key, retired, param.paramValue(), operator);
    }

    /** 行映射：值一律按字符串返回，类型解释交给接口里的 default 方法（避免脏值在建库时就抛）。 */
    private static final RowMapper<ConfigParam> MAPPER = (rs, i) -> new ConfigParam(
            rs.getString("param_key"),
            rs.getString("param_value"),
            rs.getString("value_type"),
            rs.getString("unit"),
            rs.getString("status"),
            date(rs, "effective_on"),
            date(rs, "expired_on"),
            rs.getString("source_ref"),
            rs.getString("approved_by"),
            rs.getString("note"));

    private static LocalDate date(ResultSet rs, String column) throws SQLException {
        Date d = rs.getDate(column);
        return d == null ? null : d.toLocalDate();
    }
}

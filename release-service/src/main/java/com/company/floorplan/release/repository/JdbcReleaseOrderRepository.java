package com.company.floorplan.release.repository;

import com.company.floorplan.release.model.ReleaseOrder;
import com.company.floorplan.release.model.ReleaseOrderItem;
import com.company.floorplan.release.model.ReleaseStateHistory;
import com.company.floorplan.release.model.ReleaseStatus;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

/**
 * 出库指令仓储（V19 六表 + V24 新增列的读写）。
 *
 * <p><b>为什么用 NamedParameterJdbcTemplate</b>：与
 * {@code JdbcPaymentTransactionRepository} 同风格——SQL 显式可读，
 * 命名参数避免 {@code ?} 顺序错位，且本服务没有任何 ORM 映射需求。
 * 类放在自己的包内，不依赖 infrastructure 里的任何既有 Java 文件。</p>
 *
 * <p><b>依赖 V24 已执行</b>：本服务 {@code spring.flyway.enabled=false}，
 * 建表与加列由 web 服务的 Flyway 完成（V19 → V24）。这与既有约定一致：
 * 只有 web 跑迁移，其余服务共库不建表。</p>
 *
 * <p><b>时间统一转 {@link Timestamp} 写入</b>：PostgreSQL 的 TIMESTAMPTZ
 * 用 {@code Timestamp.from(Instant)} 传参最稳，读回用 {@code getTimestamp().toInstant()}。
 * 展示时区（Asia/Tokyo）由 Jackson 在 Controller 层处理，领域层只存绝对时间点。</p>
 *
 * <p><b>指令编号用数据库序列</b>：{@code nextval('release_order_no_seq')} 天然并发安全。
 * 用 {@code count(*)+1} 生成编号在并发下会撞号，而指令是法律凭证，撞号不可接受。</p>
 */
public class JdbcReleaseOrderRepository {

    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Tokyo");

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcReleaseOrderRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<ReleaseOrder> ORDER_MAPPER = (rs, i) -> {
        ReleaseOrder o = new ReleaseOrder();
        o.setId(rs.getLong("id"));
        o.setOrderNo(rs.getString("order_no"));
        o.setRedemptionId(rs.getLong("redemption_id"));
        o.setDealerId(rs.getLong("dealer_id"));
        o.setVin(rs.getString("vin"));
        o.setCertificateNo(rs.getString("certificate_no"));
        Object custodianId = rs.getObject("custodian_id");
        o.setCustodianId(custodianId == null ? null : ((Number) custodianId).longValue());
        o.setReleaseItems(rs.getString("release_items"));
        o.setValidUntil(toInstant(rs.getTimestamp("valid_until")));
        o.setStatus(ReleaseStatus.valueOf(rs.getString("status")));
        o.setSentAt(toInstant(rs.getTimestamp("sent_at")));
        o.setConfirmedAt(toInstant(rs.getTimestamp("confirmed_at")));
        o.setReleasedAt(toInstant(rs.getTimestamp("released_at")));
        o.setLienDischargedAt(toInstant(rs.getTimestamp("lien_discharged_at")));
        o.setCancelledAt(toInstant(rs.getTimestamp("cancelled_at")));
        o.setIssuedAt(toInstant(rs.getTimestamp("issued_at")));
        o.setIssuedBy(rs.getString("issued_by"));
        o.setAcknowledgedAt(toInstant(rs.getTimestamp("acknowledged_at")));
        o.setRejectedReason(rs.getString("rejected_reason"));
        o.setDeliveryChannel(rs.getString("delivery_channel"));
        o.setDeliveredAt(toInstant(rs.getTimestamp("delivered_at")));
        Object receiptId = rs.getObject("receipt_id");
        o.setReceiptId(receiptId == null ? null : ((Number) receiptId).longValue());
        o.setCreatedAt(toInstant(rs.getTimestamp("created_at")));
        return o;
    };

    private static final RowMapper<ReleaseOrderItem> ITEM_MAPPER = (rs, i) -> {
        ReleaseOrderItem item = new ReleaseOrderItem();
        item.setId(rs.getLong("id"));
        item.setOrderId(rs.getLong("order_id"));
        item.setVin(rs.getString("vin"));
        item.setItemType(com.company.floorplan.release.model.ReleaseItemType.valueOf(rs.getString("item_type")));
        item.setItemNo(rs.getString("item_no"));
        Object custodianId = rs.getObject("custodian_id");   // V24
        item.setCustodianId(custodianId == null ? null : ((Number) custodianId).longValue());
        item.setReleased(rs.getBoolean("released"));
        item.setReleasedAt(toInstant(rs.getTimestamp("released_at")));
        item.setCreatedAt(toInstant(rs.getTimestamp("created_at")));
        return item;
    };

    private static final RowMapper<ReleaseStateHistory> HISTORY_MAPPER = (rs, i) -> {
        ReleaseStateHistory h = new ReleaseStateHistory();
        h.setId(rs.getLong("id"));
        h.setOrderId(rs.getLong("order_id"));
        String from = rs.getString("from_status");
        h.setFromStatus(from == null ? null : ReleaseStatus.valueOf(from));
        h.setToStatus(ReleaseStatus.valueOf(rs.getString("to_status")));
        h.setAction(rs.getString("action"));
        h.setOperator(rs.getString("operator"));
        h.setReason(rs.getString("reason"));
        h.setConfirmationAuthority(rs.getString("confirmation_authority"));  // V24
        h.setCreatedAt(toInstant(rs.getTimestamp("created_at")));
        return h;
    };

    private static Instant toInstant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }

    /** 生成指令编号 RO-{YYYY}-{6位序号}，序号来自数据库序列。 */
    public String nextOrderNo(Instant now) {
        Long seq = jdbc.queryForObject("SELECT nextval('release_order_no_seq')",
                new MapSqlParameterSource(), Long.class);
        int year = LocalDate.ofInstant(now, BUSINESS_ZONE).getYear();
        return String.format("RO-%d-%06d", year, seq == null ? 0L : seq);
    }

    /** 落单并返回生成的主键；status 由调用方设为 PENDING_RELEASE。 */
    public long save(ReleaseOrder order) {
        String sql = "INSERT INTO release_orders "
                + "(order_no, redemption_id, dealer_id, vin, certificate_no, custodian_id, "
                + " release_items, valid_until, status, issued_at, issued_by, delivery_channel) "
                + "VALUES (:orderNo, :redemptionId, :dealerId, :vin, :certificateNo, :custodianId, "
                + " :releaseItems, :validUntil, :status, :issuedAt, :issuedBy, :deliveryChannel)";
        MapSqlParameterSource ps = new MapSqlParameterSource()
                .addValue("orderNo", order.getOrderNo())
                .addValue("redemptionId", order.getRedemptionId())
                .addValue("dealerId", order.getDealerId())
                .addValue("vin", order.getVin())
                .addValue("certificateNo", order.getCertificateNo())
                .addValue("custodianId", order.getCustodianId())
                .addValue("releaseItems", order.getReleaseItems())
                .addValue("validUntil", toTimestamp(order.getValidUntil()))
                .addValue("status", order.getStatus().name())
                .addValue("issuedAt", toTimestamp(order.getIssuedAt()))
                .addValue("issuedBy", order.getIssuedBy())
                .addValue("deliveryChannel", order.getDeliveryChannel());
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(sql, ps, keyHolder, new String[]{"id"});
        long id = keyHolder.getKey() == null ? 0L : keyHolder.getKey().longValue();
        order.setId(id);
        return id;
    }

    /**
     * 批量写释放项明细（V24：逐项带 {@code custodian_id}）。
     * 为 {@code null} 时落 NULL，语义是「继承主表主接收方」。
     */
    public void saveItems(long orderId, List<ReleaseOrderItem> items) {
        if (items.isEmpty()) {
            return;
        }
        String sql = "INSERT INTO release_order_items (order_id, vin, item_type, item_no, custodian_id) "
                + "VALUES (:orderId, :vin, :itemType, :itemNo, :custodianId) "
                + "ON CONFLICT (order_id, item_type) DO NOTHING";
        MapSqlParameterSource[] batch = items.stream()
                .map(item -> new MapSqlParameterSource()
                        .addValue("orderId", orderId)
                        .addValue("vin", item.getVin())
                        .addValue("itemType", item.getItemType().name())
                        .addValue("itemNo", item.getItemNo())
                        .addValue("custodianId", item.getCustodianId()))
                .toArray(MapSqlParameterSource[]::new);
        jdbc.batchUpdate(sql, batch);
    }

    /**
     * 人工转交后代录回执（Q10 起步口径：无外部 WMS，MANUAL 为主）。
     *
     * <p>{@code receipt_no = "RR-" + orderNo}：指令编号全局唯一，一张指令
     * 只能 CONFIRM 一次（状态机 CAS 保证），因此回执号天然唯一；
     * {@code ON CONFLICT DO NOTHING} 让重试幂等，不产生第二条证据。</p>
     */
    public void saveReceipt(String orderNo, long orderId, String releasedItems, Instant now,
                            String confirmedBy, String source, String confirmationAuthority) {
        String sql = "INSERT INTO release_receipts "
                + "(receipt_no, release_order_id, released_items, released_at, confirmed_by, "
                + " source, confirmation_authority) "
                + "VALUES (:receiptNo, :orderId, :releasedItems, :releasedAt, :confirmedBy, "
                + " :source, :confirmationAuthority) "
                + "ON CONFLICT (receipt_no) DO NOTHING";
        jdbc.update(sql, new MapSqlParameterSource()
                .addValue("receiptNo", "RR-" + orderNo)
                .addValue("orderId", orderId)
                .addValue("releasedItems", releasedItems)
                .addValue("releasedAt", toTimestamp(now))
                .addValue("confirmedBy", confirmedBy)
                .addValue("source", source)
                .addValue("confirmationAuthority", confirmationAuthority));
    }

    /**
     * 推进状态并写入对应的时间戳列（CAS 式并发防线）。
     *
     * <p>时间戳列名来自对状态的封闭 switch，不接受外部字符串，无 SQL 注入面。
     * 状态合法性由调用方先经 {@code ReleaseStateMachine.requireTransition} 校验。</p>
     *
     * <p><b>release 并发竞态修复（CORE_VERIFIER_REPORT）</b>：旧实现是
     * {@code UPDATE ... WHERE id = :id}，两个并发请求若都读到 CONFIRMED，
     * 都会通过应用层红线校验并双双写成 RELEASED，理论上突破"未确认不放车"。
     * 这里把读到的 {@code expectedFrom} 作为 CAS 条件放进 WHERE，只有当前状态
     * 仍等于读时状态的那一个请求能更新成功；另一个影响行数为 0，由调用方抛异常回滚。</p>
     *
     * @param expectedFrom 调用方读到的当前状态（CAS 比较基准）
     * @param target       目标状态
     * @return 是否确实推进了一条记录（false 表示已被并发操作抢先推进）
     */
    public boolean updateStatus(long id, ReleaseStatus expectedFrom, ReleaseStatus target, Instant now) {
        String timeColumn = switch (target) {
            case PENDING_RELEASE -> "created_at";
            case SENT -> "sent_at";
            case CONFIRMED -> "confirmed_at";
            case RELEASED -> "released_at";
            case LIEN_DISCHARGED -> "lien_discharged_at";
            case CANCELLED -> "cancelled_at";
        };
        String sql = "UPDATE release_orders SET status = :status, " + timeColumn + " = :ts "
                + "WHERE id = :id AND status = :expectedFrom";
        int affected = jdbc.update(sql, new MapSqlParameterSource()
                .addValue("status", target.name())
                .addValue("ts", toTimestamp(now))
                .addValue("id", id)
                .addValue("expectedFrom", expectedFrom.name()));
        return affected == 1;
    }

    /** 放车时把该指令下所有明细标记为已释放。 */
    public void markItemsReleased(long orderId, Instant now) {
        jdbc.update("UPDATE release_order_items SET released = TRUE, released_at = :ts WHERE order_id = :id",
                new MapSqlParameterSource()
                        .addValue("ts", toTimestamp(now))
                        .addValue("id", orderId));
    }

    /** 追加一条状态历史。只追加不修改；V24 起可带确认权威。 */
    public void appendHistory(ReleaseStateHistory h) {
        String sql = "INSERT INTO release_state_history "
                + "(order_id, from_status, to_status, action, operator, reason, confirmation_authority) "
                + "VALUES (:orderId, :fromStatus, :toStatus, :action, :operator, :reason, "
                + " :confirmationAuthority)";
        jdbc.update(sql, new MapSqlParameterSource()
                .addValue("orderId", h.getOrderId())
                .addValue("fromStatus", h.getFromStatus() == null ? null : h.getFromStatus().name())
                .addValue("toStatus", h.getToStatus().name())
                .addValue("action", h.getAction())
                .addValue("operator", h.getOperator())
                .addValue("reason", h.getReason())
                .addValue("confirmationAuthority", h.getConfirmationAuthority()));
    }

    public Optional<ReleaseOrder> findById(long id) {
        List<ReleaseOrder> rows = jdbc.query("SELECT * FROM release_orders WHERE id = :id",
                new MapSqlParameterSource("id", id), ORDER_MAPPER);
        return rows.stream().findFirst();
    }

    public List<ReleaseOrderItem> findItems(long orderId) {
        return jdbc.query("SELECT * FROM release_order_items WHERE order_id = :orderId ORDER BY id",
                new MapSqlParameterSource("orderId", orderId), ITEM_MAPPER);
    }

    public List<ReleaseStateHistory> findHistory(long orderId) {
        return jdbc.query("SELECT * FROM release_state_history WHERE order_id = :orderId ORDER BY id",
                new MapSqlParameterSource("orderId", orderId), HISTORY_MAPPER);
    }

    private static Timestamp toTimestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }
}

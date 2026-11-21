package com.company.floorplan.infrastructure.persistence;

import com.company.floorplan.domain.model.SecurityStatus;
import com.company.floorplan.domain.model.SecurityType;
import com.company.floorplan.domain.model.VehicleStatus;
import com.company.floorplan.domain.model.VehicleUnit;
import com.company.floorplan.domain.repository.VehicleUnitRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * 车辆单元仓储实现（基础设施层）。
 *
 * <p><b>职责</b>：将 {@link VehicleUnit} 聚合根持久化到 PostgreSQL，
 * 使用 JdbcTemplate 手写 SQL —— 这样金额与状态流转的 SQL 完全可控，
 * 避开 JPA 懒加载与 N+1 问题（设计文档：复杂账务走手写 SQL）。</p>
 *
 * <p><b>并发设计</b>：状态流转与还款场景必须通过 {@link #findByVinForUpdate(String)}
 * 加行锁（{@code SELECT ... FOR UPDATE}），禁止"先读、Java 里改、再写回"的无锁模式。</p>
 *
 * <p><b>幂等设计</b>：VIN 为业务主键，{@link #save(VehicleUnit)} 按"是否已存在"
 * 自动选择 INSERT 或 UPDATE；UPDATE 时 version 自增以支持乐观锁。</p>
 */
@Repository
public class JdbcVehicleUnitRepository implements VehicleUnitRepository {

    /**
     * 车辆建账 INSERT 语句。
     *
     * <p><b>为什么把 SQL 抽成常量并加自检</b>：手写长 INSERT 时，
     * 占位符个数与列数极易错位（多写一个 {@code ?} 或漏一个）。
     * 错位不会编译报错，只在运行时抛
     * {@code "栏位索引超过许可范围"}——即 500，而不是明确的开发期信号。
     * 这里在类加载时自检：列数必须等于占位符数 + 字面量数，
     * 不等就<b>直接抛异常让应用启动失败</b>，把问题暴露在部署那一刻，
     * 而不是等到第一条业务数据进来才炸。</p>
     */
    private static final String INSERT_SQL =
            "INSERT INTO vehicle_units (vin, dealer_id, credit_line_id, advance_id, brand, model, model_year, "
                    + "mileage, color, vehicle_type, supplier_name, purchase_channel, invoice_amount, advance_amount, "
                    + "advance_ratio, advanced_at, principal_balance, paid_principal, annual_rate, rate_type, "
                    + "accrued_interest, last_interest_date, stock_in_date, sold_at, settle_due_date, settled_at, "
                    + "aging_days, turnover_days, status, security_type, security_status, overdue_days, risk_level, "
                    + "oot_suspected, last_audit_date, audit_mismatch_count, purchase_order_id, version) "
                    + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,0)";

    /**
     * INSERT 参数个数：38 列中 version 传字面 0，其余 37 列各一个占位符。
     */
    private static final int INSERT_PARAM_COUNT = 37;

    static {
        // 列数：INSERT 的列清单（第一个 '(' 到 ') VALUES' 之间）
        int columnCount = INSERT_SQL.substring(INSERT_SQL.indexOf('(') + 1, INSERT_SQL.indexOf(") VALUES")).split(",").length;
        // 值元素：从 'VALUES (' 之后到与之配对的 ')' 之前。
        // 注意不能用 lastIndexOf(')')——那样会把整串结尾的括号算进来，
        // 导致多算元素、自检误报。
        int valuesStart = INSERT_SQL.indexOf("VALUES (") + "VALUES (".length();
        int valuesEnd = INSERT_SQL.indexOf(')', valuesStart);
        String valuesPart = INSERT_SQL.substring(valuesStart, valuesEnd);
        int valueCount = valuesPart.split(",").length;
        // 占位符数 == save() 传入的实参数个数
        int placeholderCount = (int) valuesPart.chars().filter(c -> c == '?').count();
        if (columnCount != valueCount || placeholderCount != INSERT_PARAM_COUNT) {
            throw new IllegalStateException(String.format(
                    "vehicle_units INSERT 语句列数与参数数不一致：列=%d 值=%d 占位符=%d 期望参数=%d。"
                            + "这会让每条建账都失败——请同步修改 SQL 与 save() 的参数列表。",
                    columnCount, valueCount, placeholderCount, INSERT_PARAM_COUNT));
        }
    }

    private final JdbcTemplate jdbc;

    private static final RowMapper<VehicleUnit> MAPPER = (rs, i) -> {
        VehicleUnit v = new VehicleUnit();
        v.setVin(rs.getString("vin"));
        v.setDealerId(rs.getLong("dealer_id"));
        Object creditLineId = rs.getObject("credit_line_id");
        v.setCreditLineId(creditLineId == null ? null : ((Number) creditLineId).longValue());
        Object advanceId = rs.getObject("advance_id");
        v.setAdvanceId(advanceId == null ? null : ((Number) advanceId).longValue());
        v.setBrand(rs.getString("brand"));
        v.setModel(rs.getString("model"));
        v.setModelYear(getInt(rs.getObject("model_year")));
        v.setMileage(getInt(rs.getObject("mileage")));
        v.setColor(rs.getString("color"));
        v.setVehicleType(rs.getString("vehicle_type"));
        v.setSupplierName(rs.getString("supplier_name"));
        v.setPurchaseChannel(rs.getString("purchase_channel"));
        Object purchaseOrderId = rs.getObject("purchase_order_id");
        v.setPurchaseOrderId(purchaseOrderId == null ? null : ((Number) purchaseOrderId).longValue());
        v.setInvoiceAmount(rs.getLong("invoice_amount"));
        v.setAdvanceAmount(rs.getLong("advance_amount"));
        BigDecimal ratio = rs.getBigDecimal("advance_ratio");
        v.setAdvanceRatio(ratio);
        v.setAdvancedAt(getDate(rs.getDate("advanced_at")));
        v.setPrincipalBalance(rs.getLong("principal_balance"));
        v.setPaidPrincipal(rs.getLong("paid_principal"));
        v.setAnnualRate(rs.getBigDecimal("annual_rate"));
        v.setRateType(rs.getString("rate_type"));
        v.setAccruedInterest(rs.getLong("accrued_interest"));
        v.setLastInterestDate(getDate(rs.getDate("last_interest_date")));
        v.setStockInDate(getDate(rs.getDate("stock_in_date")));
        v.setSoldAt(getDate(rs.getDate("sold_at")));
        v.setSettleDueDate(getDate(rs.getDate("settle_due_date")));
        v.setSettledAt(getDate(rs.getDate("settled_at")));
        v.setAgingDays(getInt(rs.getObject("aging_days")));
        v.setTurnoverDays(getInt(rs.getObject("turnover_days")));
        v.setStatus(VehicleStatus.valueOf(rs.getString("status")));
        String securityType = rs.getString("security_type");
        v.setSecurityType(securityType == null ? null : SecurityType.valueOf(securityType));
        String securityStatus = rs.getString("security_status");
        v.setSecurityStatus(securityStatus == null ? null : SecurityStatus.valueOf(securityStatus));
        v.setOverdueDays(getInt(rs.getObject("overdue_days")));
        v.setRiskLevel(rs.getString("risk_level"));
        v.setOotSuspected(rs.getBoolean("oot_suspected"));
        v.setLastAuditDate(getDate(rs.getDate("last_audit_date")));
        v.setAuditMismatchCount(getInt(rs.getObject("audit_mismatch_count")));
        v.setVersion(rs.getInt("version"));
        return v;
    };

    private static Integer getInt(Object o) {
        return o == null ? null : ((Number) o).intValue();
    }

    private static LocalDate getDate(Date d) {
        return d == null ? null : d.toLocalDate();
    }

    public JdbcVehicleUnitRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<VehicleUnit> findByVin(String vin) {
        return jdbc.query("SELECT * FROM vehicle_units WHERE vin = ?", MAPPER, vin).stream().findFirst();
    }

    /**
     * 以<b>行锁</b>读取车辆台账（{@code SELECT ... FOR UPDATE}）。
     *
     * <p><b>使用场景</b>：垫款、售出标记、还款结清、担保变更等状态流转操作。
     * 必须在事务内调用，否则锁随语句结束即释放，失去保护意义。</p>
     *
     * <p><b>为什么必须加锁</b>：若两个请求同时读到同一台车并各自改状态，
     * 后提交的会覆盖前者，导致状态机被绕过（例如重复结清）。行锁把并发串行化。</p>
     *
     * @param vin 车架号
     * @return 加锁后的车辆单元
     */
    @Override
    public Optional<VehicleUnit> findByVinForUpdate(String vin) {
        return jdbc.query("SELECT * FROM vehicle_units WHERE vin = ? FOR UPDATE", MAPPER, vin).stream().findFirst();
    }

    @Override
    public List<VehicleUnit> findByDealerId(Long dealerId) {
        return jdbc.query("SELECT * FROM vehicle_units WHERE dealer_id = ? ORDER BY vin", MAPPER, dealerId);
    }

    @Override
    public List<VehicleUnit> findByStatus(VehicleStatus status) {
        return jdbc.query("SELECT * FROM vehicle_units WHERE status = ? ORDER BY vin", MAPPER, status.name());
    }

    @Override
    public List<VehicleUnit> findInterestBearing(LocalDate bizDate) {
        return jdbc.query(
                "SELECT * FROM vehicle_units WHERE status IN ('IN_STOCK','SOLD_PENDING') "
                        + "AND (last_interest_date IS NULL OR last_interest_date < ?) ORDER BY vin",
                MAPPER, bizDate);
    }

    @Override
    public List<VehicleUnit> findSettlementOverdue(LocalDate today) {
        return jdbc.query(
                "SELECT * FROM vehicle_units WHERE status = 'SOLD_PENDING' AND settle_due_date IS NOT NULL "
                        + "AND settle_due_date < ? ORDER BY vin",
                MAPPER, today);
    }

    @Override
    public List<VehicleUnit> findByPurchaseOrderId(Long purchaseOrderId) {
        return jdbc.query("SELECT * FROM vehicle_units WHERE purchase_order_id = ? ORDER BY vin",
                MAPPER, purchaseOrderId);
    }

    @Override
    public boolean existsByVin(String vin) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM vehicle_units WHERE vin = ?", Integer.class, vin);
        return count != null && count > 0;
    }

    @Override
    public int countByDealerAndStatus(Long dealerId, VehicleStatus status) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM vehicle_units WHERE dealer_id = ? AND status = ?",
                Integer.class, dealerId, status.name());
        return count == null ? 0 : count;
    }

    @Override
    public int countByDealerId(Long dealerId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM vehicle_units WHERE dealer_id = ?", Integer.class, dealerId);
        return count == null ? 0 : count;
    }

    @Override
    public VehicleUnit save(VehicleUnit v) {
        if (!existsByVin(v.getVin())) {
            jdbc.update(INSERT_SQL,
                    v.getVin(), v.getDealerId(), v.getCreditLineId(), v.getAdvanceId(), v.getBrand(), v.getModel(),
                    v.getModelYear(), v.getMileage(), v.getColor(), v.getVehicleType(), v.getSupplierName(),
                    v.getPurchaseChannel(),
                    v.getInvoiceAmount(), v.getAdvanceAmount(), v.getAdvanceRatio(),
                    v.getAdvancedAt(), v.getPrincipalBalance(), v.getPaidPrincipal(), v.getAnnualRate(), v.getRateType(),
                    v.getAccruedInterest(), v.getLastInterestDate(), v.getStockInDate(), v.getSoldAt(),
                    v.getSettleDueDate(), v.getSettledAt(), v.getAgingDays(), v.getTurnoverDays(),
                    v.getStatus() == null ? null : v.getStatus().name(),
                    v.getSecurityType() == null ? null : v.getSecurityType().name(),
                    v.getSecurityStatus() == null ? null : v.getSecurityStatus().name(),
                    v.getOverdueDays(), v.getRiskLevel(), v.isOotSuspected(), v.getLastAuditDate(),
                    v.getAuditMismatchCount(), v.getPurchaseOrderId());
        } else {
            jdbc.update("UPDATE vehicle_units SET credit_line_id=?, advance_id=?, purchase_order_id=?, principal_balance=?, paid_principal=?, "
                            + "accrued_interest=?, last_interest_date=?, stock_in_date=?, sold_at=?, settle_due_date=?, "
                            + "settled_at=?, aging_days=?, turnover_days=?, status=?, security_type=?, security_status=?, "
                            + "overdue_days=?, risk_level=?, oot_suspected=?, last_audit_date=?, audit_mismatch_count=?, "
                            + "version=version+1, updated_at=now() WHERE vin=?",
                    v.getCreditLineId(), v.getAdvanceId(), v.getPurchaseOrderId(),
                    v.getPrincipalBalance(), v.getPaidPrincipal(),
                    v.getAccruedInterest(), v.getLastInterestDate(), v.getStockInDate(), v.getSoldAt(),
                    v.getSettleDueDate(), v.getSettledAt(), v.getAgingDays(), v.getTurnoverDays(),
                    v.getStatus() == null ? null : v.getStatus().name(),
                    v.getSecurityType() == null ? null : v.getSecurityType().name(),
                    v.getSecurityStatus() == null ? null : v.getSecurityStatus().name(),
                    v.getOverdueDays(), v.getRiskLevel(), v.isOotSuspected(), v.getLastAuditDate(),
                    v.getAuditMismatchCount(), v.getVin());
        }
        return v;
    }
}

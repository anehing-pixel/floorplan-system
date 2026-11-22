package com.company.floorplan.infrastructure.persistence;

import com.company.floorplan.domain.exception.BusinessException;
import com.company.floorplan.domain.exception.ErrorCode;
import com.company.floorplan.domain.model.MileageSource;
import com.company.floorplan.domain.model.RepairHistoryFlag;
import com.company.floorplan.domain.model.SaleChannel;
import com.company.floorplan.domain.model.UsedVehicleAttributes;
import com.company.floorplan.domain.repository.UsedVehicleAttributesRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * USED 车辆属性读写实现（{@code vehicle_units} 的 V50 新增列，R2.1–R2.4）。
 *
 * <p><b>列所有权边界（本类最要紧的一条）</b>：{@code UPDATE} 只写 W-U1 归属的列——
 * 身份与车况属性、里程三元组、以及 R2.4 换绑用的 {@code inspection_certificate_id}。
 * 金额基准列（{@code valuation_id} / {@code prudent_value} / {@code ltv_*} /
 * {@code topup_required_amount}）与 {@code wms_confirmed_at} 由 W-U2 / W-U6 写入，
 * 本类只读不写：读是为了给出完整属性视图，不写是因为
 * 把这些列放进同一条 UPDATE 时，本包没装载的那一份会被写成 NULL，
 * 把别的包刚写好的权威值抹掉（并行开发里最难查的一类数据踩踏）。</p>
 *
 * <p><b>里程三列同一条语句</b>：{@code mileage} / {@code mileage_source} /
 * {@code mileage_captured_on} 一起写，理由是 V50 的
 * {@code ck_vu_mileage_unknown}（{@code UNKNOWN} 时 {@code mileage} 必须为 NULL）
 * 是行级约束——拆成两条 UPDATE 时，两条之间那一瞬间的行可能违反约束，
 * 而 {@code UNKNOWN} 降级（先置来源再清数值）恰好就是要经过那个状态。
 * 单条语句让约束只在语句结束时被检查一次。</p>
 *
 * <p><b>乐观锁</b>：CAS 用既有列 {@code vehicle_units.version}（V1 建表即有），
 * 版本值由调用方从 {@code VehicleUnitRepository} 读来传入，本类不再自读整行，
 * 避免两个仓储对同一行给出两套版本。</p>
 */
@Repository
public class JdbcUsedVehicleAttributesRepository implements UsedVehicleAttributesRepository {

    private final JdbcTemplate jdbc;

    public JdbcUsedVehicleAttributesRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * USED 属性视图的读取列清单。
     *
     * <p>写全列名而不是 {@code SELECT *}：既有 {@code vehicle_unit} 会被别的包继续加列，
     * 通配读取会让本类的列下标随无关迁移漂移。</p>
     */
    private static final String SELECT_COLUMNS =
            "vin, vehicle_type, registration_plate, first_registered_on, inspection_expiry_date, "
                    + "inspection_remaining_months, mileage, mileage_unit, mileage_source, mileage_captured_on, "
                    + "repair_history, repair_history_desc, accident_history_flag, auction_grade, auction_lot_id, "
                    + "sale_channel, inspection_certificate_id, warehouse_custodian_code, bin_location, "
                    + "wms_confirmed_at, valuation_id, prudent_value, ltv_ratio_snapshot, ltv_policy_code, "
                    + "ltv_policy_version, version";

    private static final RowMapper<UsedVehicleAttributes> MAPPER = (rs, i) -> {
        UsedVehicleAttributes a = new UsedVehicleAttributes();
        a.setVin(rs.getString("vin"));
        a.setVehicleType(rs.getString("vehicle_type"));
        a.setRegistrationPlate(rs.getString("registration_plate"));
        a.setFirstRegisteredOn(toLocalDate(rs.getDate("first_registered_on")));
        a.setInspectionExpiryDate(toLocalDate(rs.getDate("inspection_expiry_date")));
        a.setInspectionRemainingMonths(intOrNull(rs, "inspection_remaining_months"));
        a.setMileage(intOrNull(rs, "mileage"));
        a.setMileageUnit(rs.getString("mileage_unit"));
        // 里程来源列可空：NULL=LEGACY 存量从未采集，UNKNOWN=采集过但不可信，
        // 两者语义不同（V50 列注释），这里原样保留 NULL，不做 UNKNOWN 归一。
        String source = rs.getString("mileage_source");
        a.setMileageSource(source == null ? null : MileageSource.valueOf(source));
        a.setMileageCapturedOn(toLocalDate(rs.getDate("mileage_captured_on")));
        String repair = rs.getString("repair_history");
        a.setRepairHistory(repair == null ? null : RepairHistoryFlag.valueOf(repair));
        a.setRepairHistoryDesc(rs.getString("repair_history_desc"));
        a.setAccidentHistoryFlag(booleanOrNull(rs, "accident_history_flag"));
        a.setAuctionGrade(decimalOrNull(rs, "auction_grade"));
        a.setAuctionLotId(longOrNull(rs, "auction_lot_id"));
        String channel = rs.getString("sale_channel");
        a.setSaleChannel(channel == null ? null : SaleChannel.valueOf(channel));
        a.setInspectionCertificateId(longOrNull(rs, "inspection_certificate_id"));
        a.setWarehouseCustodianCode(rs.getString("warehouse_custodian_code"));
        a.setBinLocation(rs.getString("bin_location"));
        a.setWmsConfirmedAt(toInstant(rs.getTimestamp("wms_confirmed_at")));
        a.setValuationId(longOrNull(rs, "valuation_id"));
        a.setPrudentValue(longOrNull(rs, "prudent_value"));
        a.setLtvRatioSnapshot(decimalOrNull(rs, "ltv_ratio_snapshot"));
        a.setLtvPolicyCode(rs.getString("ltv_policy_code"));
        a.setLtvPolicyVersion(intOrNull(rs, "ltv_policy_version"));
        a.setVersion(intOrNull(rs, "version"));
        return a;
    };

    @Override
    public Optional<UsedVehicleAttributes> findByVin(String vin) {
        if (vin == null || vin.isBlank()) {
            return Optional.empty();
        }
        return jdbc.query("SELECT " + SELECT_COLUMNS + " FROM vehicle_units WHERE vin = ?",
                MAPPER, vin.trim()).stream().findFirst();
    }

    @Override
    public boolean updateAttributes(UsedVehicleAttributes attributes, int expectedVersion) {
        if (attributes == null || attributes.getVin() == null || attributes.getVin().isBlank()) {
            throw new BusinessException(
                    ErrorCode.VALID_001,
                    "USED 属性更新缺少 vin");
        }
        // SET 子句与参数用同一个列表成对累积，最后 join：
        // 这条 UPDATE 没有条件列，"列顺序"和"参数顺序"错位只会静默写错值，不会报错。
        List<String> assignments = new ArrayList<>();
        List<Object> args = new ArrayList<>();
        addSet(assignments, args, "vehicle_type", name(attributes.getVehicleType()));
        addSet(assignments, args, "registration_plate", trim(attributes.getRegistrationPlate()));
        addSet(assignments, args, "first_registered_on", toSqlDate(attributes.getFirstRegisteredOn()));
        addSet(assignments, args, "inspection_expiry_date", toSqlDate(attributes.getInspectionExpiryDate()));
        addSet(assignments, args, "inspection_remaining_months", attributes.getInspectionRemainingMonths());
        // 里程三元组：数值、来源、采集时点同批写入，UNKNOWN 时数值必须为 NULL
        //（由 V50 ck_vu_mileage_unknown 兜底，应用层已在 R2.2 里做同口径校验）。
        addSet(assignments, args, "mileage", attributes.getMileage());
        addSet(assignments, args, "mileage_source", name(attributes.getMileageSource()));
        addSet(assignments, args, "mileage_unit", trim(attributes.getMileageUnit()));
        addSet(assignments, args, "mileage_captured_on", toSqlDate(attributes.getMileageCapturedOn()));
        addSet(assignments, args, "repair_history", name(attributes.getRepairHistory()));
        addSet(assignments, args, "repair_history_desc", trim(attributes.getRepairHistoryDesc()));
        addSet(assignments, args, "accident_history_flag", attributes.getAccidentHistoryFlag());
        addSet(assignments, args, "auction_grade", attributes.getAuctionGrade());
        addSet(assignments, args, "auction_lot_id", attributes.getAuctionLotId());
        addSet(assignments, args, "sale_channel", name(attributes.getSaleChannel()));
        addSet(assignments, args, "inspection_certificate_id", attributes.getInspectionCertificateId());
        addSet(assignments, args, "warehouse_custodian_code", trim(attributes.getWarehouseCustodianCode()));
        addSet(assignments, args, "bin_location", trim(attributes.getBinLocation()));
        // 乐观锁与时间戳：version 只在 WHERE 命中时递增，updated_at 也只在真的写成功时刷新。
        assignments.add("version = version + 1");
        assignments.add("updated_at = now()");
        args.add(attributes.getVin().trim());
        args.add(expectedVersion);
        String sql = "UPDATE vehicle_units SET " + String.join(", ", assignments)
                + " WHERE vin = ? AND version = ?";
        return jdbc.update(sql, args.toArray()) == 1;
    }

    /**
     * 追加一个 {@code col = ?} 赋值及其绑定参数。
     *
     * <p>用 {@code = ?} 直接绑定（值可为 NULL）：更新是"按传入快照整列覆盖"的语义，
     * 字段为 null 就是把该列写回 NULL——USED 属性更正本就要能撤销可选事实
     * （例：里程来源从 AUCTION_REPORT 改成 OWN_AUDIT 时，旧的采集日应一并清掉）。
     * 若改成"null 不更新"，R2.2 的更正就无法清空任何列；两种语义不能混用，
     * 这里选择与实体视图一致的整体覆盖。</p>
     */
    private static void addSet(List<String> assignments, List<Object> args,
                               String column, Object value) {
        assignments.add(column + " = ?");
        args.add(value);
    }

    private static String trim(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String name(Enum<?> value) {
        return value == null ? null : value.name();
    }

    private static Integer intOrNull(java.sql.ResultSet rs, String column) {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    private static Long longOrNull(java.sql.ResultSet rs, String column) {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private static BigDecimal decimalOrNull(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        // getBigDecimal 对 NULL 列本身就返回 null，这里只是收敛成显式的空值语义，
        // 免得调用点把"数值列为空"当成 0 处理（拍卖評価点空与 0.0 是两种事实）。
        return rs.getBigDecimal(column);
    }

    private static Boolean booleanOrNull(java.sql.ResultSet rs, String column) {
        boolean value = rs.getBoolean(column);
        return rs.wasNull() ? null : value;
    }

    private static LocalDate toLocalDate(Date value) {
        return value == null ? null : value.toLocalDate();
    }

    private static Instant toInstant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    private static Date toSqlDate(LocalDate value) {
        return value == null ? null : Date.valueOf(value);
    }
}

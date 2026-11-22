package com.company.floorplan.infrastructure.persistence;

import com.company.floorplan.domain.exception.BusinessException;
import com.company.floorplan.domain.exception.ErrorCode;
import com.company.floorplan.domain.model.CertificateCustodyEvent;
import com.company.floorplan.domain.model.CertificateStatus;
import com.company.floorplan.domain.model.CustodyActorType;
import com.company.floorplan.domain.model.CustodyEventType;
import com.company.floorplan.domain.model.EncumbranceType;
import com.company.floorplan.domain.model.InspectionCertificate;
import com.company.floorplan.domain.model.RegistryCheckSource;
import com.company.floorplan.domain.repository.InspectionCertificateRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 車検証与 custody 流水读写实现（表 {@code inspection_certificates} ＋
 * {@code certificate_custody_events}，V52／V53）。
 *
 * <p><b>列映射是 UC-R2-5 需要的视图，不是全列</b>：设质、保管、放证相关列
 * （{@code security_type} / {@code holder_*} / {@code custody_location} /
 * {@code release_order_no} / {@code *_at}）属 W-U4 的写入面，实体没映射，
 * 本类也不写——把它们放进 INSERT/UPDATE 的列清单，
 * 等于用本包的缺省值覆盖那条链路的权威值（详见实体注释的第二条理由）。</p>
 *
 * <p><b>状态迁移只写 status，不补时间戳</b>：V53 的
 * {@code ck_cert_status_timestamps} 要求 PLEDGED/RELEASED/LOST 必须带对应时间戳列，
 * 而那三态的写入方在设质/放证链路（W-U4），本仓储不映射那几列。
 * 因此 {@link #transitStatus} 只接受无配对要求的目标态
 * （本包换证链用的是 {@code REISSUED}；{@code RECEIVED}／{@code RELEASE_PENDING} 同族但不归本包写），
 * 传那三态会在方法入口被拒（{@code SYS_001}），而不是把一条注定违反 CHECK 的 UPDATE 送进库里。</p>
 *
 * <p><b>换证链的写入集在同一事务</b>：旧证 {@link #transitStatus} → 新证 {@link #insert}
 * （带 {@code predecessor_id}）→ 两条 {@link #appendCustodyEvent}，
 * 由应用层的一个 {@code @Transactional} 用例方法串起来；仓储本身不开事务，
 * 也不在这里补"少写一条事件"的洞——那属用例编排，不属数据访问。</p>
 */
@Repository
public class JdbcInspectionCertificateRepository implements InspectionCertificateRepository {

    private final JdbcTemplate jdbc;

    public JdbcInspectionCertificateRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 在场＝非 REISSUED／LOST，与 V53 的 {@code uk_cert_active_vin} 谓词逐字同构。 */
    private static final String ACTIVE_STATUS_PREDICATE = "status NOT IN ('REISSUED', 'LOST')";

    private static final RowMapper<InspectionCertificate> CERT_MAPPER = (rs, i) -> {
        InspectionCertificate c = new InspectionCertificate();
        c.setId(rs.getLong("id"));
        c.setCertKey(rs.getString("cert_key"));
        c.setCertificateNo(rs.getString("certificate_no"));
        c.setVin(rs.getString("vin"));
        c.setRegistrationPlate(rs.getString("registration_plate"));
        c.setOwnerName(rs.getString("owner_name"));
        c.setUserName(rs.getString("user_name"));
        c.setInspectionExpiryDate(toLocalDate(rs.getDate("inspection_expiry_date")));
        c.setIssueDate(toLocalDate(rs.getDate("issue_date")));
        c.setStatus(CertificateStatus.valueOf(rs.getString("status")));
        c.setPredecessorId(longOrNull(rs, "predecessor_id"));
        c.setEvidencePath(rs.getString("evidence_path"));
        c.setCreatedAt(toInstant(rs.getTimestamp("created_at")));
        c.setUpdatedAt(toInstant(rs.getTimestamp("updated_at")));
        c.setVersion(intOrNull(rs, "version"));
        return c;
    };

    private static final RowMapper<CertificateCustodyEvent> EVENT_MAPPER = (rs, i) -> {
        CertificateCustodyEvent e = new CertificateCustodyEvent();
        e.setId(rs.getLong("id"));
        e.setCertificateId(rs.getLong("certificate_id"));
        e.setEventType(CustodyEventType.valueOf(rs.getString("event_type")));
        e.setFromStatus(enumOrNull(rs.getString("from_status"), CertificateStatus.class));
        e.setToStatus(enumOrNull(rs.getString("to_status"), CertificateStatus.class));
        e.setEncumbranceType(enumOrNull(rs.getString("encumbrance_type"), EncumbranceType.class));
        e.setCheckSource(enumOrNull(rs.getString("check_source"), RegistryCheckSource.class));
        e.setActorType(CustodyActorType.valueOf(rs.getString("actor_type")));
        e.setActor(rs.getString("actor"));
        e.setOccurredAt(toInstant(rs.getTimestamp("occurred_at")));
        e.setEvidencePath(rs.getString("evidence_path"));
        e.setReason(rs.getString("reason"));
        e.setTraceId(rs.getString("trace_id"));
        return e;
    };

    @Override
    public Optional<InspectionCertificate> findActiveByVin(String vin) {
        if (vin == null || vin.isBlank()) {
            return Optional.empty();
        }
        // 谓词直接抄索引定义：拿 status <> 'REISSUED' AND <> 'LOST' 之类"等价写法"
        // 一旦索引改口径就会查出两条、然后 stream().findFirst() 静默挑一条。
        return jdbc.query("SELECT * FROM inspection_certificates WHERE vin = ? AND "
                        + ACTIVE_STATUS_PREDICATE,
                CERT_MAPPER, vin.trim()).stream().findFirst();
    }

    @Override
    public List<InspectionCertificate> findChainByVin(String vin) {
        if (vin == null || vin.isBlank()) {
            return List.of();
        }
        // issue_date 可空？列是 NOT NULL，所以不需要 NULLS LAST；补 id 做同日发布时的稳定次序。
        return jdbc.query("SELECT * FROM inspection_certificates WHERE vin = ? "
                + "ORDER BY issue_date, id", CERT_MAPPER, vin.trim());
    }

    @Override
    public Optional<InspectionCertificate> findById(Long id) {
        if (id == null) {
            return Optional.empty();
        }
        return jdbc.query("SELECT * FROM inspection_certificates WHERE id = ?", CERT_MAPPER, id)
                .stream().findFirst();
    }

    @Override
    public InspectionCertificate insert(InspectionCertificate certificate) {
        if (certificate == null || certificate.getVin() == null || certificate.getVin().isBlank()) {
            throw new BusinessException(ErrorCode.VALID_001, "車検証缺少 vin");
        }
        if (certificate.getCertificateNo() == null || certificate.getCertificateNo().isBlank()) {
            throw new BusinessException(ErrorCode.VALID_001, "車検証缺少証書番号");
        }
        if (certificate.getStatus() == null) {
            throw new BusinessException(ErrorCode.VALID_001, "車検証缺少状态");
        }
        // cert_key 由应用层按 D00 §13.1 的 CERT-{certificate_no} 拼好后传入。
        // 这里缺值直接拒绝，不在仓储补一份拼接逻辑：同一公式写两处，
        // 定源一改就会出现"新旧键并存"的幂等锚点，重放再也对不上。
        if (certificate.getCertKey() == null || certificate.getCertKey().isBlank()) {
            throw new BusinessException(ErrorCode.VALID_001,
                    "車検証缺少幂等锚点 cert_key（公式 CERT-{certificate_no} 属 D00 §13.1，由应用层拼装）");
        }
        try {
            Long id = jdbc.queryForObject(
                    "INSERT INTO inspection_certificates ("
                            + "cert_key, certificate_no, vin, registration_plate, owner_name, user_name, "
                            + "inspection_expiry_date, issue_date, status, predecessor_id, evidence_path, version) "
                            + "VALUES (?,?,?,?,?,?,?,?,?,?,?,0) RETURNING id",
                    Long.class,
                    certificate.getCertKey().trim(), certificate.getCertificateNo().trim(),
                    certificate.getVin().trim(), trim(certificate.getRegistrationPlate()),
                    trim(certificate.getOwnerName()), trim(certificate.getUserName()),
                    toSqlDate(certificate.getInspectionExpiryDate()), toSqlDate(certificate.getIssueDate()),
                    certificate.getStatus().name(), certificate.getPredecessorId(),
                    trim(certificate.getEvidencePath()));
            certificate.setId(id);
            return findById(id).orElse(certificate);
        } catch (DuplicateKeyException ex) {
            throw certificateConflict(certificate, ex);
        }
    }

    /**
     * 撞唯一约束时挑出正确的错误码。
     *
     * <p>两条约束的成因与对外语义不同，不能都报 CONFLICT_001：
     * {@code uk_cert_active_vin} 是业务不变量（该 VIN 已有在场的证）→ {@code CERT_002}（D07 §8 明确）；
     * {@code uk_cert_key} 是幂等（同证号重放）→ {@code CONFLICT_001}。</p>
     *
     * <p><b>识别方式是匹配 Spring 异常消息里的索引名</b>，不是拿 SQLState（两条都报 23505，分不开）。
     * 匹配不到时按 {@code CONFLICT_001} 兜底——两种成因都是 4xx 且整事务回滚，
     * 差别只在给客户的措辞。这里不承诺"一定能分对"，
     * 因为消息文本归驱动包与 Spring 版本管；真要稳定区分，需要 PG 侧把约束名放进错误详情字段，
     * 那属框架层改造，不在本包范围。</p>
     */
    private BusinessException certificateConflict(InspectionCertificate certificate, DuplicateKeyException ex) {
        String message = ex.getMessage() == null ? "" : ex.getMessage();
        if (message.contains("uk_cert_active_vin")) {
            return new BusinessException(ErrorCode.CERT_002, Map.of(
                    "vin", certificate.getVin(),
                    "certificateNo", certificate.getCertificateNo(),
                    "reason", "ACTIVE_CERT_ALREADY_EXISTS",
                    "detail", "该 VIN 已有未终结的有效車検証（uk_cert_active_vin）"));
        }
        return new BusinessException(ErrorCode.CONFLICT_001, Map.of(
                "idempotencyKey", certificate.getCertKey(),
                "reason", message.contains("uk_cert_key")
                        ? "DUPLICATE_CERT_KEY" : "UNIQUE_CONSTRAINT_VIOLATION",
                "detail", "車検証重放或唯一约束冲突"));
    }

    @Override
    public boolean transitStatus(Long id, String from, String to, int expectedVersion) {
        if (id == null || to == null || to.isBlank()) {
            throw new BusinessException(ErrorCode.VALID_001, "車検証状态迁移缺少目标状态");
        }
        // 三态需要配套时间戳（V53 ck_cert_status_timestamps），本仓储不映射那几列，
        // 让它们进来只会在库里炸 CHECK——先把话说明白，报错点提前一层，
        // 而不是留一条"迁移 PLEDGED 偶发 500"的现场事故给排查。
        if ("PLEDGED".equals(to) || "RELEASED".equals(to) || "LOST".equals(to)) {
            throw new BusinessException(ErrorCode.SYS_001, Map.of(
                    "reason", "STATUS_REQUIRES_TIMESTAMP",
                    "targetStatus", to,
                    "detail", "目标状态要求与 pledged_at/released_at/lost_at 同写，"
                            + "该链路属设质/放证侧（W-U4），本仓储不映射这些列"));
        }
        // 合法性已由领域层 CertificateStateMachine.requireTransition 判过，
        // 仓储不再重复白名单（两处各写一遍必然漂移）。这里的 WHERE 只保证两件事：
        //   status = from  → 防"读完到写之间被别人改过状态"（换证链最怕把新证置成 REISSUED）；
        //   version = 期望 → 乐观锁 CAS，未命中返回 0 行，由上层决定重试还是报冲突。
        int rows = jdbc.update("UPDATE inspection_certificates "
                        + "SET status = ?, updated_at = now(), version = version + 1 "
                        + "WHERE id = ? AND status = ? AND version = ?",
                to, id, from, expectedVersion);
        return rows == 1;
    }

    @Override
    public CertificateCustodyEvent appendCustodyEvent(CertificateCustodyEvent event) {
        if (event == null || event.getCertificateId() == null) {
            throw new BusinessException(ErrorCode.VALID_001, "custody 事件缺少 certificate_id");
        }
        if (event.getEventType() == null || event.getActorType() == null
                || event.getActor() == null || event.getActor().isBlank()) {
            throw new BusinessException(ErrorCode.VALID_001,
                    "custody 事件缺少 event_type / actor_type / actor");
        }
        // actor 非空是 V52 的 NOT NULL 列：R12.2 要求"人"与"合作方"两种主体显式区分，
        // 由应用层从 AuditActor（不接受请求体自报）或 partner_code 取得后传入。
        Long id = jdbc.queryForObject(
                "INSERT INTO certificate_custody_events ("
                        + "certificate_id, event_type, from_status, to_status, encumbrance_type, "
                        + "check_source, actor_type, actor, occurred_at, evidence_path, reason, trace_id) "
                        + "VALUES (?,?,?,?,?,?,?,?,COALESCE(?, now()),?,?,?) RETURNING id",
                Long.class,
                event.getCertificateId(), event.getEventType().name(), name(event.getFromStatus()),
                name(event.getToStatus()), name(event.getEncumbranceType()), name(event.getCheckSource()),
                event.getActorType().name(), event.getActor().trim(),
                event.getOccurredAt() == null ? null : Timestamp.from(event.getOccurredAt()),
                trim(event.getEvidencePath()), trim(event.getReason()), trim(event.getTraceId()));
        event.setId(id);
        return event;
    }

    @Override
    public List<CertificateCustodyEvent> findCustodyEvents(Long certificateId) {
        if (certificateId == null) {
            return List.of();
        }
        // 正序＝证据链的阅读方向（收证→设质→换证），与 idx_cce_cert_time 的 DESC 相反，
        // 这里显式 ASC：索引倒扫即可，不必让调用方再反转一次列表。
        return jdbc.query("SELECT * FROM certificate_custody_events WHERE certificate_id = ? "
                + "ORDER BY occurred_at, id", EVENT_MAPPER, certificateId);
    }

    private static String trim(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String name(Enum<?> value) {
        return value == null ? null : value.name();
    }

    /** 列里存的是枚举 name；为空返回空，不猜默认值（UNKNOWN 与"没填"是两件事）。 */
    private static <E extends Enum<E>> E enumOrNull(String raw, Class<E> type) {
        return raw == null || raw.isBlank() ? null : Enum.valueOf(type, raw);
    }

    private static Integer intOrNull(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    private static Long longOrNull(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
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

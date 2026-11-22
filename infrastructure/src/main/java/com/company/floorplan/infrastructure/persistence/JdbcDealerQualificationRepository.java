package com.company.floorplan.infrastructure.persistence;

import com.company.floorplan.domain.exception.BusinessException;
import com.company.floorplan.domain.exception.ErrorCode;
import com.company.floorplan.domain.model.AdmissionState;
import com.company.floorplan.domain.model.AuctionMembership;
import com.company.floorplan.domain.model.AuctionMembershipStatus;
import com.company.floorplan.domain.model.ComplianceCheck;
import com.company.floorplan.domain.model.ComplianceCheckSource;
import com.company.floorplan.domain.model.ComplianceCheckType;
import com.company.floorplan.domain.model.ComplianceResult;
import com.company.floorplan.domain.model.DealerLicense;
import com.company.floorplan.domain.model.DealerLicenseType;
import com.company.floorplan.domain.model.DealerQualification;
import com.company.floorplan.domain.model.LicenseStatus;
import com.company.floorplan.domain.repository.DealerQualificationRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 车商资质仓储实现（基础设施层；R1.1/R1.2/R1.5）。
 *
 * <p><b>三张表一个仓储</b>：{@code dealer_licenses}、{@code dealer_auction_memberships}、
 * {@code dealer_compliance_checks}（V45）共同构成领域侧的 {@link DealerQualification} 聚合，
 * 因此按聚合装载、按行写入。装载时一次把三段都取回来（车商维度的行数是个位数，
 * 不存在 {@code vehicle_units} 那种"一个车商几万台"的膨胀风险），
 * 这样 {@code DealerAdmissionPolicy} 拿到的永远是完整视图，不会因为漏 load 某一段而误判放行。</p>
 *
 * <p><b>枚举转换只在本层</b>：库里是 VARCHAR，领域侧是枚举。
 * 取值集与库侧 CHECK 必须一一对应（R13.4 双层约束），否则 {@code valueOf} 会抛
 * {@code IllegalArgumentException} 并让整条查询 500——所以这里统一走
 * {@link #enumOrNull} / {@link #enumOrThrow}，把"库里出现字典外的值"翻成
 * 可读的 SYS_001 而不是裸异常。</p>
 *
 * <p><b>写操作的并发边界</b>：V45 三张表都没有 {@code version} 列，
 * 所以本类<b>不做版本 CAS</b>；唯一约束（{@code uk_dlic_dealer_type_no}、
 * {@code uk_dealer_license_active}、{@code uk_dmem_dealer_house_no}、
 * {@code uk_dchk_request_no}）是唯一的并发兜底。撞约束时抛 {@code CONFLICT_001}
 * 让事务回滚，<b>不试图在同一事务里回查已存在行</b>（PG 撞约束后事务已中止，回查必然失败）。</p>
 */
@Repository
public class JdbcDealerQualificationRepository implements DealerQualificationRepository {

    private final JdbcTemplate jdbc;

    public JdbcDealerQualificationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 许可记录映射（列名与 V45 的 dealer_licenses 逐列对应）。 */
    private static final RowMapper<DealerLicense> LICENSE_MAPPER = (rs, i) -> {
        DealerLicense l = new DealerLicense();
        l.setId(rs.getLong("id"));
        l.setDealerId(rs.getLong("dealer_id"));
        l.setLicenseType(enumOrNull(DealerLicenseType.class, rs.getString("license_type")));
        l.setLicenseNo(rs.getString("license_no"));
        l.setCompetentAuthority(rs.getString("competent_authority"));
        l.setIssuedOn(toLocalDate(rs.getDate("issued_on")));
        l.setExpiresOn(toLocalDate(rs.getDate("expires_on")));
        l.setStatus(enumOrNull(LicenseStatus.class, rs.getString("status")));
        l.setEvidencePath(rs.getString("evidence_path"));
        l.setEvidenceDigest(rs.getString("evidence_digest"));
        l.setVerifiedBy(rs.getString("verified_by"));
        l.setVerifiedAt(toInstant(rs.getTimestamp("verified_at")));
        l.setCreatedAt(toInstant(rs.getTimestamp("created_at")));
        l.setUpdatedAt(toInstant(rs.getTimestamp("updated_at")));
        l.setCreatedBy(rs.getString("created_by"));
        l.setUpdatedBy(rs.getString("updated_by"));
        return l;
    };

    /** 会员行映射（V45 的 dealer_auction_memberships）。 */
    private static final RowMapper<AuctionMembership> MEMBERSHIP_MAPPER = (rs, i) -> {
        AuctionMembership m = new AuctionMembership();
        m.setId(rs.getLong("id"));
        m.setDealerId(rs.getLong("dealer_id"));
        m.setAuctionHouseCode(rs.getString("auction_house_code"));
        m.setMemberNo(rs.getString("member_no"));
        m.setMemberGrade(rs.getString("member_grade"));
        m.setJoinedOn(toLocalDate(rs.getDate("joined_on")));
        m.setExpiresOn(toLocalDate(rs.getDate("expires_on")));
        m.setStatus(enumOrNull(AuctionMembershipStatus.class, rs.getString("status")));
        m.setEvidencePath(rs.getString("evidence_path"));
        m.setCreatedAt(toInstant(rs.getTimestamp("created_at")));
        m.setUpdatedAt(toInstant(rs.getTimestamp("updated_at")));
        m.setCreatedBy(rs.getString("created_by"));
        m.setUpdatedBy(rs.getString("updated_by"));
        return m;
    };

    /** 合规核查行映射（V45 的 dealer_compliance_checks）。 */
    private static final RowMapper<ComplianceCheck> COMPLIANCE_MAPPER = (rs, i) -> {
        ComplianceCheck c = new ComplianceCheck();
        c.setId(rs.getLong("id"));
        c.setDealerId(rs.getLong("dealer_id"));
        c.setCheckType(enumOrNull(ComplianceCheckType.class, rs.getString("check_type")));
        c.setCheckedOn(toLocalDate(rs.getDate("checked_on")));
        c.setValidUntil(toLocalDate(rs.getDate("valid_until")));
        c.setResult(enumOrNull(ComplianceResult.class, rs.getString("result")));
        c.setSource(enumOrNull(ComplianceCheckSource.class, rs.getString("source")));
        c.setRequestNo(rs.getString("request_no"));
        c.setEvidencePath(rs.getString("evidence_path"));
        c.setNote(rs.getString("note"));
        c.setCheckedBy(rs.getString("checked_by"));
        c.setCreatedAt(toInstant(rs.getTimestamp("created_at")));
        c.setUpdatedAt(toInstant(rs.getTimestamp("updated_at")));
        c.setCreatedBy(rs.getString("created_by"));
        c.setUpdatedBy(rs.getString("updated_by"));
        return c;
    };

    @Override
    public Optional<DealerQualification> findByDealerId(Long dealerId) {
        if (dealerId == null) {
            return Optional.empty();
        }
        // 先确认主数据在不在：不存在时返回空聚合，由应用层翻成 NOT_FOUND_001，
        // 而不是返回一个"三段都空"的对象——那会被判定策略读成"资质全缺"，错误码含义就错了。
        // 用 query(...) 而不是 queryForObject(...)：后者零行时抛 EmptyResultDataAccessException，
        // 而"车商不存在"是正常业务分支，不该靠捕获异常来表达。
        List<Integer> hit = jdbc.query("SELECT 1 FROM dealers WHERE id = ?",
                (rs, i) -> rs.getInt(1), dealerId);
        if (hit.isEmpty()) {
            return Optional.empty();
        }
        DealerQualification q = new DealerQualification(dealerId);
        q.getLicenses().addAll(jdbc.query(
                "SELECT * FROM dealer_licenses WHERE dealer_id = ? ORDER BY issued_on DESC, id DESC",
                LICENSE_MAPPER, dealerId));
        q.getMemberships().addAll(jdbc.query(
                "SELECT * FROM dealer_auction_memberships WHERE dealer_id = ? ORDER BY auction_house_code, id",
                MEMBERSHIP_MAPPER, dealerId));
        q.getComplianceChecks().addAll(jdbc.query(
                "SELECT * FROM dealer_compliance_checks WHERE dealer_id = ? ORDER BY checked_on DESC, id DESC",
                COMPLIANCE_MAPPER, dealerId));
        return Optional.of(q);
    }

    @Override
    public Optional<DealerLicense> findLicense(Long dealerId, String licenseType, String licenseNo) {
        // 自然键三元组查询，与 uk_dlic_dealer_type_no 同构，因此最多一行。
        return jdbc.query("SELECT * FROM dealer_licenses WHERE dealer_id = ? AND license_type = ? AND license_no = ?",
                LICENSE_MAPPER, dealerId, licenseType, licenseNo).stream().findFirst();
    }

    @Override
    public DealerLicense insertLicense(DealerLicense license) {
        try {
            Long id = jdbc.queryForObject(
                    "INSERT INTO dealer_licenses (dealer_id, license_type, license_no, competent_authority, "
                            + "issued_on, expires_on, status, evidence_path, evidence_digest, verified_by, verified_at, "
                            + "created_by, updated_by) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?) RETURNING id",
                    Long.class,
                    license.getDealerId(), name(license.getLicenseType()), license.getLicenseNo(),
                    license.getCompetentAuthority(), toSqlDate(license.getIssuedOn()),
                    toSqlDate(license.getExpiresOn()),
                    license.getStatus() == null ? LicenseStatus.ACTIVE.name() : license.getStatus().name(),
                    license.getEvidencePath(), license.getEvidenceDigest(), license.getVerifiedBy(),
                    toSqlTimestamp(license.getVerifiedAt()), license.getCreatedBy(), license.getUpdatedBy());
            license.setId(id);
            // 摘要投影与明细同事务刷新：列表页读的是 dealers 三列投影，
            // 隔一屏再刷就会留下"明细已改、列表还旧"的窗口。
            refreshLicenseProjection(license.getDealerId());
            return license;
        } catch (DuplicateKeyException ex) {
            // 同番号重放（撞 uk_dlic_dealer_type_no）与"已有另一张有效证"（撞 uk_dealer_license_active）
            // 都落在这里；两种成因的文案分开，便于工单直接看出是哪种。
            throw conflict("许可记录已存在或该车商已有同类型有效许可：" + license.getLicenseType()
                    + "/" + license.getLicenseNo());
        }
    }

    @Override
    public boolean updateLicense(DealerLicense license) {
        // 只写会变的列，绝不整行覆盖：created_at/created_by/dealer_id 归插入路径所有，
        // 覆盖它们会让一次更正抹掉"这行最初是谁插的"这条审计事实。
        int rows = jdbc.update(
                "UPDATE dealer_licenses SET license_type=?, expires_on=?, status=?, evidence_path=?, "
                        + "evidence_digest=?, verified_by=?, verified_at=?, updated_by=?, updated_at=now() "
                        + "WHERE id=?",
                name(license.getLicenseType()), toSqlDate(license.getExpiresOn()),
                name(license.getStatus()), license.getEvidencePath(), license.getEvidenceDigest(),
                license.getVerifiedBy(), toSqlTimestamp(license.getVerifiedAt()),
                license.getUpdatedBy(), license.getId());
        if (rows > 0) {
            // 换证/取消都走这条路：旧证置 SUPERSEDED、新证置 ACTIVE 之后，
            // 投影必须按"当前代表行"重算，否则会一直显示已被换出的那张证。
            // 入参对象可能只带了 id（只改状态的最小更新），归属车商缺失时回查本行拿——
            // dealer_id 不可变，这次回查不存在"读到别人的版本"的问题。
            Long dealerId = license.getDealerId();
            if (dealerId == null) {
                dealerId = jdbc.queryForObject(
                        "SELECT dealer_id FROM dealer_licenses WHERE id = ?", Long.class, license.getId());
            }
            refreshLicenseProjection(dealerId);
        }
        return rows > 0;
    }

    @Override
    public List<AuctionMembership> findMemberships(Long dealerId, String status) {
        // status 是可选过滤（D07 §7.1 的 GET ?status=）；传了就按值过滤，没传就全量。
        if (status == null || status.isBlank()) {
            return jdbc.query("SELECT * FROM dealer_auction_memberships WHERE dealer_id = ? "
                    + "ORDER BY auction_house_code, id", MEMBERSHIP_MAPPER, dealerId);
        }
        return jdbc.query("SELECT * FROM dealer_auction_memberships WHERE dealer_id = ? AND status = ? "
                + "ORDER BY auction_house_code, id", MEMBERSHIP_MAPPER, dealerId, status.trim());
    }

    @Override
    public Optional<AuctionMembership> findMembership(Long dealerId, String auctionHouseCode, String memberNo) {
        return jdbc.query("SELECT * FROM dealer_auction_memberships "
                        + "WHERE dealer_id = ? AND auction_house_code = ? AND member_no = ?",
                MEMBERSHIP_MAPPER, dealerId, auctionHouseCode, memberNo).stream().findFirst();
    }

    @Override
    public AuctionMembership insertMembership(AuctionMembership membership) {
        try {
            Long id = jdbc.queryForObject(
                    "INSERT INTO dealer_auction_memberships (dealer_id, auction_house_code, member_no, "
                            + "member_grade, joined_on, expires_on, status, evidence_path, created_by, updated_by) "
                            + "VALUES (?,?,?,?,?,?,?,?,?,?) RETURNING id",
                    Long.class,
                    membership.getDealerId(), membership.getAuctionHouseCode(), membership.getMemberNo(),
                    membership.getMemberGrade(), toSqlDate(membership.getJoinedOn()),
                    toSqlDate(membership.getExpiresOn()),
                    membership.getStatus() == null
                            ? AuctionMembershipStatus.ACTIVE.name() : membership.getStatus().name(),
                    membership.getEvidencePath(), membership.getCreatedBy(), membership.getUpdatedBy());
            membership.setId(id);
            return membership;
        } catch (DuplicateKeyException ex) {
            // uk_dmem_dealer_house_no：同一车商在同一会场的同一会员号只能有一行。
            throw conflict("该会场的会员号已存在：" + membership.getAuctionHouseCode()
                    + "/" + membership.getMemberNo());
        }
    }

    @Override
    public boolean updateMembership(AuctionMembership membership) {
        // 三元组（dealer_id/auction_house_code/member_no）是幂等锚点，不允许被更新改写；
        // 要改号就是"新会员行 + 旧行置 TERMINATED"两条动作，不是一行 UPDATE。
        int rows = jdbc.update(
                "UPDATE dealer_auction_memberships SET member_grade=?, joined_on=?, expires_on=?, status=?, "
                        + "evidence_path=?, updated_by=?, updated_at=now() WHERE id=?",
                membership.getMemberGrade(), toSqlDate(membership.getJoinedOn()),
                toSqlDate(membership.getExpiresOn()), name(membership.getStatus()),
                membership.getEvidencePath(), membership.getUpdatedBy(), membership.getId());
        return rows > 0;
    }

    @Override
    public Optional<ComplianceCheck> findComplianceByRequestNo(String requestNo) {
        if (requestNo == null || requestNo.isBlank()) {
            // 人工录入没有报文号，幂等探测无从谈起（应用层改用"同 type + 同 checkedOn"判重）。
            return Optional.empty();
        }
        return jdbc.query("SELECT * FROM dealer_compliance_checks WHERE request_no = ?",
                COMPLIANCE_MAPPER, requestNo.trim()).stream().findFirst();
    }

    @Override
    public ComplianceCheck insertCompliance(ComplianceCheck check) {
        try {
            Long id = jdbc.queryForObject(
                    "INSERT INTO dealer_compliance_checks (dealer_id, check_type, checked_on, valid_until, "
                            + "result, source, request_no, evidence_path, note, checked_by, created_by, updated_by) "
                            + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?) RETURNING id",
                    Long.class,
                    check.getDealerId(), name(check.getCheckType()), toSqlDate(check.getCheckedOn()),
                    toSqlDate(check.getValidUntil()), name(check.getResult()),
                    check.getSource() == null ? ComplianceCheckSource.MANUAL.name() : check.getSource().name(),
                    check.getRequestNo(), check.getEvidencePath(), check.getNote(),
                    check.getCheckedBy(), check.getCreatedBy(), check.getUpdatedBy());
            check.setId(id);
            return check;
        } catch (DuplicateKeyException ex) {
            // uk_dchk_request_no（部分唯一，只作用于有报文号的行）。
            // 注意 partner_code 无处落库，所以这里的锚点比 CHK:{partner}:{requestNo} 更宽，
            // 差异与后果写在 DealerQualificationRepository#findComplianceByRequestNo 的注释里。
            throw conflict("该核查报文号已存在：" + check.getRequestNo());
        }
    }

    @Override
    public Optional<AdmissionState> findAdmissionState(Long dealerId) {
        // dealers.admission_state 允许 NULL（V46：存量行从未评估），NULL 用空 Optional 表达，
        // 不在这里造一个"默认态"——默认放行就是绕过闸门。
        List<String> values = jdbc.query(
                "SELECT admission_state FROM dealers WHERE id = ?",
                (rs, i) -> rs.getString("admission_state"), dealerId);
        if (values.isEmpty() || values.get(0) == null) {
            return Optional.empty();
        }
        return Optional.of(enumOrThrow(AdmissionState.class, values.get(0)));
    }

    @Override
    public boolean updateAdmissionState(Long dealerId, AdmissionState state) {
        if (state == null) {
            throw new BusinessException(ErrorCode.VALID_001, "准入态不能为空：清空准入态等于把闸门退回未评估");
        }
        // IS DISTINCT FROM：同值重算不写、不刷 updated_at，避免"每次重算都假装档案被改过"。
        // 没有 version 列可做 CAS（dealers 建表时未带，V46 也没补），取舍见接口注释。
        int rows = jdbc.update(
                "UPDATE dealers SET admission_state = ?, updated_at = now() "
                        + "WHERE id = ? AND admission_state IS DISTINCT FROM ?",
                state.name(), dealerId, state.name());
        return rows > 0;
    }

    @Override
    public List<DealerLicense> findActiveLicensesExpiringBy(LocalDate expiresBefore) {
        // 只扫 ACTIVE 且确有到期日的行：古物商許可本身不设有效期（expires_on IS NULL 是正常态），
        // 那些行不参与到期预警，否则预警列表会被"永不到期"的记录占满。
        return jdbc.query("SELECT * FROM dealer_licenses WHERE status = 'ACTIVE' "
                        + "AND expires_on IS NOT NULL AND expires_on <= ? ORDER BY expires_on, id",
                LICENSE_MAPPER, toSqlDate(expiresBefore));
    }

    /**
     * 刷新 {@code dealers} 的许可摘要投影
     * （{@code license_status} / {@code license_expiry_date} / {@code furigusho_no}，V46）。
     *
     * <p><b>为什么由本仓储写、且只有这里写</b>：V46 把这三列定义成"列表页免 join 的只读投影"，
     * 权威值在 {@code dealer_licenses}。投影必须有唯一写入方，否则就会出现
     * {@code updateAdmissionState} 注释里担心的"两个写入者改同一列必然漂移"——
     * 实测全仓没有任何代码写过这三列（只有本类），所以本方法就是那条"许可写入路径"。
     * 判定侧一律读明细表（V46 列注释原文），这三列只影响列表显示，不参与任何门禁。</p>
     *
     * <p><b>代表行的取法</b>：{@code ACTIVE} 优先，其次按 {@code issued_on}/{@code id} 最新。
     * 这样换证后（旧行 SUPERSEDED、新行 ACTIVE）投影跟着新行；
     * 只剩失效行时投影跟着那张最接近现在的证，而不是清空——
     * 列表页要能看出"这车商有过许可、现在是什么状态"，抹成 NULL 反而把事实说没了。</p>
     *
     * <p><b>同值不写</b>：谓词用 {@code IS DISTINCT FROM}，与 {@code updateAdmissionState}
     * 同一取舍；重复录入不该让"档案最后修改时间"跳一下。</p>
     *
     * @param dealerId 车商 ID；无许可行时本语句自然影响 0 行
     */
    private void refreshLicenseProjection(Long dealerId) {
        if (dealerId == null) {
            return;
        }
        jdbc.update(
                "UPDATE dealers d "
                        + "   SET license_status = s.status, license_expiry_date = s.expires_on, "
                        + "       furigusho_no = CASE WHEN s.license_type = 'FURIGUSHO' "
                        + "                            THEN s.license_no ELSE d.furigusho_no END, "
                        + "       updated_at = now() "
                        + "  FROM (SELECT license_type, license_no, status, expires_on "
                        + "           FROM dealer_licenses "
                        + "          WHERE dealer_id = ? "
                        + "          ORDER BY (status = 'ACTIVE') DESC, issued_on DESC, id DESC "
                        + "          LIMIT 1) s "
                        + " WHERE d.id = ? "
                        + "   AND (d.license_status IS DISTINCT FROM s.status "
                        + "        OR d.license_expiry_date IS DISTINCT FROM s.expires_on "
                        + "        OR (s.license_type = 'FURIGUSHO' AND d.furigusho_no IS DISTINCT FROM s.license_no))",
                dealerId, dealerId);
    }

    // ---- 本类内部的小工具 ----

    /** 枚举名转库内字符串：null 保持 null（列允许为空，不替业务造默认值）。 */
    private static String name(Enum<?> value) {
        return value == null ? null : value.name();
    }

    /** 读取枚举，空值返回 null；库里出现字典外的值时抛可读错误（不静默降级）。 */
    private static <E extends Enum<E>> E enumOrNull(Class<E> type, String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return enumOrThrow(type, raw);
    }

    /** 读取枚举且不允许为空：取值不在 Java 枚举里 ⟹ 双层约束漂移（R13.4），报 SYS_001。 */
    private static <E extends Enum<E>> E enumOrThrow(Class<E> type, String raw) {
        try {
            return Enum.valueOf(type, raw.trim());
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(ErrorCode.SYS_001,
                    "库里 " + type.getSimpleName() + " 出现字典外的取值：" + raw
                            + "（Java 枚举与 DB CHECK 漂移，需按 R13.4 同步两侧）");
        }
    }

    private static LocalDate toLocalDate(Date value) {
        return value == null ? null : value.toLocalDate();
    }

    private static Date toSqlDate(LocalDate value) {
        return value == null ? null : Date.valueOf(value);
    }

    private static Instant toInstant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    private static Timestamp toSqlTimestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    /** 唯一约束冲突统一翻成 CONFLICT_001（沿用既有幂等口径，不自造新错误码）。 */
    private static BusinessException conflict(String message) {
        return new BusinessException(ErrorCode.CONFLICT_001,
                message + "（撞唯一约束，请重读该车商现有档案后再提交）");
    }
}

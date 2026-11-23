package com.company.floorplan.infrastructure.persistence;

import com.company.floorplan.domain.model.AdmissionState;
import com.company.floorplan.domain.model.ComplianceCheckType;
import com.company.floorplan.domain.model.ComplianceResult;
import com.company.floorplan.domain.model.ComplianceSource;
import com.company.floorplan.domain.model.DealerAuctionMembership;
import com.company.floorplan.domain.model.DealerComplianceCheck;
import com.company.floorplan.domain.model.DealerLicense;
import com.company.floorplan.domain.model.DealerQualification;
import com.company.floorplan.domain.model.LicenseStatus;
import com.company.floorplan.domain.model.LicenseType;
import com.company.floorplan.domain.model.MembershipStatus;
import com.company.floorplan.domain.repository.DealerQualificationRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 车商资质仓储 JDBC 实现（USED R1）。
 *
 * <p>沿用本仓库既有做法：原生 {@code JdbcTemplate} + 手写 SQL（无 JPA/MyBatis），
 * 插入用 {@code INSERT ... RETURNING id} 取回主键，日期列显式走 {@link Date}/{@link Timestamp}
 * 而不是把 {@code LocalDate} 直接交给驱动——PG JDBC 对 java.time 的支持版本相关，
 * 依赖它会让同一份代码在不同驱动版本上表现不一致。</p>
 *
 * <p><b>聚合组装只有一条路</b>：{@link #findByDealerId} 一次把许可、会员、合规三张表读齐。
 * 之所以不给每张表单独暴露「组装聚合」的入口，是因为漏读任一张表，
 * {@code DealerAdmissionPolicy} 都会给出一个<b>看起来合法</b>的 ADMITTED——
 * 「合规没查」和「合规查了但没过」在结果上是同一个静默错误，这是最危险的一类。</p>
 */
@Repository
public class JdbcDealerQualificationRepository implements DealerQualificationRepository {

    private final JdbcTemplate jdbc;

    public JdbcDealerQualificationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** dealer_licenses 行映射。 */
    private static final RowMapper<DealerLicense> LICENSE_MAPPER = (rs, i) -> {
        DealerLicense l = new DealerLicense();
        l.setId(rs.getLong("id"));
        l.setDealerId(rs.getLong("dealer_id"));
        l.setLicenseType(LicenseType.from(rs.getString("license_type")));
        l.setLicenseNo(rs.getString("license_no"));
        l.setCompetentAuthority(rs.getString("competent_authority"));
        l.setIssuedOn(date(rs, "issued_on"));
        l.setExpiresOn(date(rs, "expires_on"));
        l.setStatus(LicenseStatus.from(rs.getString("status")));
        l.setEvidencePath(rs.getString("evidence_path"));
        l.setEvidenceDigest(rs.getString("evidence_digest"));
        l.setVerifiedBy(rs.getString("verified_by"));
        l.setVerifiedAt(offset(rs, "verified_at"));
        return l;
    };

    /** dealer_auction_memberships 行映射。 */
    private static final RowMapper<DealerAuctionMembership> MEMBERSHIP_MAPPER = (rs, i) -> {
        DealerAuctionMembership m = new DealerAuctionMembership();
        m.setId(rs.getLong("id"));
        m.setDealerId(rs.getLong("dealer_id"));
        m.setAuctionHouseCode(rs.getString("auction_house_code"));
        m.setMemberNo(rs.getString("member_no"));
        m.setMemberGrade(rs.getString("member_grade"));
        m.setJoinedOn(date(rs, "joined_on"));
        m.setExpiresOn(date(rs, "expires_on"));
        m.setStatus(MembershipStatus.from(rs.getString("status")));
        m.setEvidencePath(rs.getString("evidence_path"));
        return m;
    };

    /** dealer_compliance_checks 行映射（co_signed_by 由 V76 追加）。 */
    private static final RowMapper<DealerComplianceCheck> CHECK_MAPPER = (rs, i) -> {
        DealerComplianceCheck c = new DealerComplianceCheck();
        c.setId(rs.getLong("id"));
        c.setDealerId(rs.getLong("dealer_id"));
        c.setCheckType(ComplianceCheckType.from(rs.getString("check_type")));
        c.setCheckedOn(date(rs, "checked_on"));
        c.setValidUntil(date(rs, "valid_until"));
        c.setResult(ComplianceResult.from(rs.getString("result")));
        c.setSource(ComplianceSource.from(rs.getString("source")));
        c.setRequestNo(rs.getString("request_no"));
        c.setEvidencePath(rs.getString("evidence_path"));
        c.setNote(rs.getString("note"));
        c.setCheckedBy(rs.getString("checked_by"));
        c.setCoSignedBy(rs.getString("co_signed_by"));
        return c;
    };

    @Override
    public DealerQualification findByDealerId(Long dealerId) {
        // 准入态快照单独取：它落在 dealers 主数据上，不属于三张证据表
        AdmissionState stored = null;
        List<String> raw = jdbc.queryForList(
                "SELECT admission_state FROM dealers WHERE id = ?", String.class, dealerId);
        if (!raw.isEmpty()) {
            stored = AdmissionState.from(raw.get(0));
        }
        return new DealerQualification(dealerId, findLicenses(dealerId),
                findMemberships(dealerId), findComplianceChecks(dealerId), stored);
    }

    @Override
    public List<DealerLicense> findLicenses(Long dealerId) {
        // 排序： issued_on 倒序，最新登记的许可在前；同日期时 id 大者优先（后写入者为准）
        return jdbc.query("SELECT * FROM dealer_licenses WHERE dealer_id = ? "
                + "ORDER BY issued_on DESC NULLS LAST, id DESC", LICENSE_MAPPER, dealerId);
    }

    @Override
    public Optional<DealerLicense> findLicenseByKey(Long dealerId, LicenseType licenseType, String licenseNo) {
        // 命中 uk_dlic_dealer_type_no：这三列合起来就是幂等键 DLIC:{dealerId}:{type}:{no} 的库内对应
        List<DealerLicense> rows = jdbc.query("SELECT * FROM dealer_licenses "
                + "WHERE dealer_id = ? AND license_type = ? AND license_no = ?",
                LICENSE_MAPPER, dealerId, licenseType == null ? null : licenseType.name(), licenseNo);
        return rows.stream().findFirst();
    }

    @Override
    public DealerLicense insertLicense(DealerLicense license, String operator) {
        Long id = jdbc.queryForObject(
                "INSERT INTO dealer_licenses (dealer_id, license_type, license_no, competent_authority, "
                        + "issued_on, expires_on, status, evidence_path, evidence_digest, "
                        + "verified_by, verified_at, created_by, updated_by) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?) RETURNING id",
                Long.class,
                license.getDealerId(),
                license.getLicenseType() == null ? null : license.getLicenseType().name(),
                license.getLicenseNo(), license.getCompetentAuthority(),
                date(license.getIssuedOn()), date(license.getExpiresOn()),
                license.getStatus() == null ? LicenseStatus.ACTIVE.name() : license.getStatus().name(),
                license.getEvidencePath(), license.getEvidenceDigest(),
                license.getVerifiedBy(), timestamp(license.getVerifiedAt()),
                operator, operator);
        license.setId(id);
        return license;
    }

    @Override
    public int updateLicenseStatus(Long licenseId, LicenseStatus status, String operator) {
        return jdbc.update("UPDATE dealer_licenses SET status = ?, updated_by = ?, updated_at = now() "
                + "WHERE id = ?", status.name(), operator, licenseId);
    }

    @Override
    public List<DealerLicense> findActiveLicensesExpiringBetween(LocalDate fromInclusive, LocalDate toInclusive) {
        // 走 idx_license_expiry（部分索引：status='ACTIVE'），到期作业与预警列表共用
        return jdbc.query("SELECT * FROM dealer_licenses "
                + "WHERE status = 'ACTIVE' AND expires_on BETWEEN ? AND ? ORDER BY expires_on",
                LICENSE_MAPPER, date(fromInclusive), date(toInclusive));
    }

    @Override
    public List<DealerAuctionMembership> findMemberships(Long dealerId) {
        return jdbc.query("SELECT * FROM dealer_auction_memberships WHERE dealer_id = ? "
                + "ORDER BY auction_house_code, id", MEMBERSHIP_MAPPER, dealerId);
    }

    @Override
    public Optional<DealerAuctionMembership> findMembershipByKey(Long dealerId, String auctionHouseCode,
                                                                String memberNo) {
        // 命中 uk_dmem_dealer_house_no（幂等键 DMEM:{dealerId}:{house}:{memberNo} 的库内对应）
        List<DealerAuctionMembership> rows = jdbc.query("SELECT * FROM dealer_auction_memberships "
                + "WHERE dealer_id = ? AND auction_house_code = ? AND member_no = ?",
                MEMBERSHIP_MAPPER, dealerId, auctionHouseCode, memberNo);
        return rows.stream().findFirst();
    }

    @Override
    public DealerAuctionMembership insertMembership(DealerAuctionMembership membership, String operator) {
        Long id = jdbc.queryForObject(
                "INSERT INTO dealer_auction_memberships (dealer_id, auction_house_code, member_no, "
                        + "member_grade, joined_on, expires_on, status, evidence_path, created_by, updated_by) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?) RETURNING id",
                Long.class,
                membership.getDealerId(), membership.getAuctionHouseCode(), membership.getMemberNo(),
                membership.getMemberGrade(), date(membership.getJoinedOn()), date(membership.getExpiresOn()),
                membership.getStatus() == null ? MembershipStatus.ACTIVE.name() : membership.getStatus().name(),
                membership.getEvidencePath(), operator, operator);
        membership.setId(id);
        return membership;
    }

    @Override
    public int updateMembership(DealerAuctionMembership membership, String operator) {
        return jdbc.update("UPDATE dealer_auction_memberships SET member_grade = ?, joined_on = ?, "
                        + "expires_on = ?, status = ?, evidence_path = ?, updated_by = ?, updated_at = now() "
                        + "WHERE id = ?",
                membership.getMemberGrade(), date(membership.getJoinedOn()), date(membership.getExpiresOn()),
                membership.getStatus() == null ? null : membership.getStatus().name(),
                membership.getEvidencePath(), operator, membership.getId());
    }

    @Override
    public List<DealerComplianceCheck> findComplianceChecks(Long dealerId) {
        return jdbc.query("SELECT * FROM dealer_compliance_checks WHERE dealer_id = ? "
                + "ORDER BY check_type, checked_on DESC", CHECK_MAPPER, dealerId);
    }

    @Override
    public Optional<DealerComplianceCheck> findComplianceCheckByRequestNo(String requestNo) {
        // uk_dchk_request_no 是<b>部分</b>唯一索引（WHERE request_no IS NOT NULL）：
        // 人工核查没有对外请求号，可以无限多条；一旦有号，全库只能有一条。
        // 这条索引就是 I-11 入站重复推送的最后一道防线，所以查它必须用号而不是 (dealer,type,date)。
        if (requestNo == null || requestNo.isBlank()) {
            return Optional.empty();
        }
        List<DealerComplianceCheck> rows = jdbc.query(
                "SELECT * FROM dealer_compliance_checks WHERE request_no = ?", CHECK_MAPPER, requestNo);
        return rows.stream().findFirst();
    }

    @Override
    public DealerComplianceCheck insertComplianceCheck(DealerComplianceCheck check, String operator) {
        Long id = jdbc.queryForObject(
                "INSERT INTO dealer_compliance_checks (dealer_id, check_type, checked_on, valid_until, "
                        + "result, source, request_no, evidence_path, note, checked_by, co_signed_by, "
                        + "created_by, updated_by) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?) RETURNING id",
                Long.class,
                check.getDealerId(),
                check.getCheckType() == null ? null : check.getCheckType().name(),
                date(check.getCheckedOn()), date(check.getValidUntil()),
                check.getResult() == null ? null : check.getResult().name(),
                check.getSource() == null ? ComplianceSource.MANUAL.name() : check.getSource().name(),
                check.getRequestNo(), check.getEvidencePath(), check.getNote(),
                check.getCheckedBy(), check.getCoSignedBy(), operator, operator);
        check.setId(id);
        return check;
    }

    @Override
    public com.company.floorplan.domain.model.DealerScores findScores(Long dealerId) {
        // 四列一次取齐；行不存在时返回「未评分」视图，由调用方的 requireDealer 决定要不要报 404。
        List<com.company.floorplan.domain.model.DealerScores> rows = jdbc.query(
                "SELECT id, operating_score, stock_quality_score, score_set_by, score_co_signed_by, "
                        + "scores_confirmed_at, scores_run_key FROM dealers WHERE id = ?",
                (rs, i) -> new com.company.floorplan.domain.model.DealerScores(
                        rs.getLong("id"),
                        nullableInt(rs, "operating_score"),
                        nullableInt(rs, "stock_quality_score"),
                        rs.getString("score_set_by"), rs.getString("score_co_signed_by"),
                        rs.getObject("scores_confirmed_at", OffsetDateTime.class),
                        rs.getString("scores_run_key")),
                dealerId);
        return rows.stream().findFirst()
                .orElse(new com.company.floorplan.domain.model.DealerScores(dealerId, null, null, null, null, null, null));
    }

    @Override
    public int updateDualScores(Long dealerId, Integer operatingScore, Integer stockQualityScore,
                                String scoresRunKey, String coSignedBy, String operator) {
        // dealers 表没有 updated_by 列（V1 建的 LEGACY 主数据表，V77 只补了评分相关四列）：
        // 操作人记在 score_set_by / score_co_signed_by 上，审计另由 audit_events 承担。
        return jdbc.update("UPDATE dealers SET operating_score = ?, stock_quality_score = ?, "
                        + "score_set_by = ?, score_co_signed_by = ?, scores_confirmed_at = now(), "
                        + "scores_run_key = ?, updated_at = now() WHERE id = ?",
                operatingScore, stockQualityScore, operator, coSignedBy, scoresRunKey, dealerId);
    }

    /** INTEGER 列的可空读取：{@code rs.getInt} 对 NULL 返回 0，而 0 分是有效评分，两者不能混。 */
    private static Integer nullableInt(ResultSet rs, String column) throws SQLException {
        int v = rs.getInt(column);
        return rs.wasNull() ? null : Integer.valueOf(v);
    }

    @Override
    public int updateAdmissionState(Long dealerId, AdmissionState state) {
        return jdbc.update("UPDATE dealers SET admission_state = ?, updated_at = now() "
                + "WHERE id = ?", state == null ? null : state.name(), dealerId);
    }

    @Override
    public Optional<Long> findDealerId(Long dealerId) {
        List<Long> rows = jdbc.queryForList("SELECT id FROM dealers WHERE id = ?", Long.class, dealerId);
        return rows.stream().findFirst();
    }

    /** 读 DATE 列；NULL 返回 null（不要把「无期限」读成 1970-01-01）。 */
    private static LocalDate date(ResultSet rs, String column) throws SQLException {
        Date d = rs.getDate(column);
        return d == null ? null : d.toLocalDate();
    }

    /**
     * 读 TIMESTAMPTZ 列，保留偏移量。
     *
     * <p>不用 {@code getTimestamp()} 再补时区：那样得到的是 JVM 默认时区下的本地时间，
     * 一旦容器时区与库不一致（本仓库的库跑在 WSL/Docker 里，UTC；宿主是 JST+08:00 混用），
     * 「核验时间」就会带着一个没人知道的偏移。{@code getObject(OffsetDateTime.class)}
     * 由驱动按列的实际偏移直接给出。</p>
     */
    private static OffsetDateTime offset(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, OffsetDateTime.class);
    }

    /** 写 DATE 参数。 */
    private static Date date(LocalDate value) {
        return value == null ? null : Date.valueOf(value);
    }

    /** 写 TIMESTAMPTZ 参数。 */
    private static Timestamp timestamp(OffsetDateTime value) {
        return value == null ? null : Timestamp.from(value.toInstant());
    }
}

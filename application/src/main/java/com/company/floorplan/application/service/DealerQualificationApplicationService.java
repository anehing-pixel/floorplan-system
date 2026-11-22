package com.company.floorplan.application.service;

import com.company.floorplan.domain.exception.BusinessException;
import com.company.floorplan.domain.exception.ErrorCode;
import com.company.floorplan.domain.model.AdmissionDecision;
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
import com.company.floorplan.domain.repository.DealerRepository;
import com.company.floorplan.domain.repository.SupportRepository;
import com.company.floorplan.domain.repository.UsedConfigRepository;
import com.company.floorplan.domain.service.DealerAdmissionPolicy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 车商资质与准入用例（应用层；R1.1/R1.2/R1.5，UC-R1-1…UC-R1-4）。
 *
 * <p><b>本层只做三件事</b>：把请求参数装配成领域对象、划定事务边界、写审计与预警。
 * 判定规则全在 {@link DealerAdmissionPolicy}，数据库交互全在仓储——
 * 应用层不出现 SQL、不出现"今天算不算过期"这类业务口径。</p>
 *
 * <p><b>幂等的写法（与 {@code AdvanceApplicationService} 同族）</b>：先按自然键探测，
 * 命中就比指纹决定"回首次结果"还是抛 {@code CONFLICT_001}；
 * 探测与写入在同一事务里，撞唯一索引时由仓储翻成 {@code CONFLICT_001} 抛出。
 * 三个键的落点分别是 {@code uk_dlic_dealer_type_no}、{@code uk_dmem_dealer_house_no}、
 * {@code uk_dchk_request_no}（D06 §1.5 的 {@code DLIC:}/{@code DMEM:}/{@code CHK:}
 * 是<b>键语义</b>而非独立列：资质三张表没有 idempotency_key 列，
 * 键的段就是自然键本身，V45 把唯一索引建在那几列上，因此不需要额外一列）。</p>
 *
 * <p><b>不发布 outbox 事件</b>：准入态变化对应的事件 {@code DEALER_ADMISSION_STATE_CHANGED}
 * 带 † 号，不在 D00 冻结事件词表里（已在实现说明登记）。本类因此只写审计与告警，
 * 不调用 outbox 发布——词表未收口就发事件，下游订阅方会拿到一个没有契约的名字。</p>
 *
 * <p><b>未签字数值的处理</b>：合规核查有效期（D-02）与到期预警窗口（
 * {@code license-expiry-alert-days}）都从参数表读，读不到就不推导（{@code valid_until}
 * 落 NULL、预警窗口为空时不产出 EXPIRING）。本类没有任何"默认 180 天/30 天"的常量。</p>
 */
@Service
public class DealerQualificationApplicationService {

    private final DealerQualificationRepository qualificationRepository;
    private final DealerRepository dealerRepository;
    private final UsedConfigRepository usedConfigRepository;
    private final SupportRepository supportRepository;
    /** 准入判定策略（领域层，无状态，直接 new 不构成外部依赖）。 */
    private final DealerAdmissionPolicy admissionPolicy = new DealerAdmissionPolicy();

    public DealerQualificationApplicationService(DealerQualificationRepository qualificationRepository,
                                                 DealerRepository dealerRepository,
                                                 UsedConfigRepository usedConfigRepository,
                                                 SupportRepository supportRepository) {
        this.qualificationRepository = qualificationRepository;
        this.dealerRepository = dealerRepository;
        this.usedConfigRepository = usedConfigRepository;
        this.supportRepository = supportRepository;
    }

    /**
     * 新增或更正许可记录（UC-R1-1，D07 §7.1 {@code POST /api/dealers/{id}/licenses}）。
     *
     * <p><b>三种命中情形</b>：</p>
     * <ol>
     *   <li>同 (车商, 类型, 番号) 已存在且指纹一致 → 重放，返回首次结果，不写库不写审计；</li>
     *   <li>同键存在但指纹不同 → 视为订正（期限/管辖/证据录错了），只更新会变的列并留审计；</li>
     *   <li>键不存在但同类型已有另一张 ACTIVE 证 → 按 D03 §2.1 的换证链处理：
     *       旧证置 {@code SUPERSEDED}、新证以 {@code ACTIVE} 插入，两步同事务。
     *       换证/記載変更的申报时限属 [待定源]（D-02 系），本包<b>不做超时判定</b>，
     *       也不给旧证补任何"逾期换证"标记。</li>
     * </ol>
     *
     * @param dealerId            车商 ID
     * @param licenseType         许可类型（本期仅 FURIGUSHO）
     * @param licenseNo           许可番号
     * @param competentAuthority  管辖公安委员会
     * @param issuedOn            许可发日（issued_on）
     * @param expiresOn           到期日；可空＝业务上无有效期（古物商许可不设期限）
     * @param evidencePath        扫描件本地路径（U-R12：一期不接对象存储）
     * @param evidenceDigest      扫描件 SHA-256
     * @param verifiedBy          人工核查人（G-A1 复核主体）
     * @param verifiedAt          核查时点
     * @param actor               操作人（审计主体，来自 AuditActor，不由请求体自报）
     * @param traceId             链路号
     * @return 落库或首次已有的许可行
     */
    @Transactional
    public DealerLicense upsertLicense(Long dealerId, String licenseType, String licenseNo,
                                       String competentAuthority, LocalDate issuedOn, LocalDate expiresOn,
                                       String evidencePath, String evidenceDigest,
                                       String verifiedBy, Instant verifiedAt,
                                       String actor, String traceId) {
        requireDealer(dealerId);
        DealerLicenseType type = parseEnum(DealerLicenseType.class, licenseType, "许可类型");
        String no = requireText(licenseNo, "许可番号");
        String authority = requireText(competentAuthority, "管辖公安委员会");
        if (issuedOn == null) {
            throw new BusinessException(ErrorCode.VALID_001, "许可发日（issued_on）不能为空");
        }

        Optional<DealerLicense> existing = qualificationRepository.findLicense(dealerId, type.name(), no);
        if (existing.isPresent()) {
            DealerLicense current = existing.get();
            if (sameLicenseFingerprint(current, authority, issuedOn, expiresOn, evidenceDigest)) {
                // 情形 1：重放。返回首次结果，不产生第二次写与第二条审计——
                // 审计里出现两条同值记录会让"档案到底改没改过"这个问题没有答案。
                return current;
            }
            // 情形 2：订正。状态保持原值（不把 EXPIRED/REVOKED 悄悄刷回 ACTIVE），
            // 状态的变更必须走显式流转，不靠"顺手改个日期"完成。
            String before = licenseFingerprintOf(current);
            current.setCompetentAuthority(authority);
            current.setIssuedOn(issuedOn);
            current.setExpiresOn(expiresOn);
            current.setEvidencePath(blankToNull(evidencePath));
            current.setEvidenceDigest(blankToNull(evidenceDigest));
            current.setVerifiedBy(blankToNull(verifiedBy));
            current.setVerifiedAt(verifiedAt);
            current.setUpdatedBy(actor);
            qualificationRepository.updateLicense(current);
            supportRepository.writeAudit(actor, "DEALER_LICENSE_CORRECTED", "DEALER_LICENSE",
                    String.valueOf(current.getId()), before,
                    fingerprint(authority, issuedOn, expiresOn, evidenceDigest), traceId);
            return current;
        }

        // 情形 3：新番号。先看同类型是否已有 ACTIVE 证（uk_dealer_license_active 的冲突源头）。
        DealerLicense activeOther = qualificationRepository.findByDealerId(dealerId)
                .map(DealerQualification::activeLicense)
                .filter(lic -> lic.getLicenseType() == type)
                .orElse(null);
        if (activeOther != null) {
            activeOther.setStatus(LicenseStatus.SUPERSEDED);
            activeOther.setUpdatedBy(actor);
            qualificationRepository.updateLicense(activeOther);
        }

        DealerLicense license = new DealerLicense();
        license.setDealerId(dealerId);
        license.setLicenseType(type);
        license.setLicenseNo(no);
        license.setCompetentAuthority(authority);
        license.setIssuedOn(issuedOn);
        license.setExpiresOn(expiresOn);
        license.setStatus(LicenseStatus.ACTIVE);
        license.setEvidencePath(blankToNull(evidencePath));
        license.setEvidenceDigest(blankToNull(evidenceDigest));
        license.setVerifiedBy(blankToNull(verifiedBy));
        license.setVerifiedAt(verifiedAt);
        license.setCreatedBy(actor);
        license.setUpdatedBy(actor);
        DealerLicense saved = qualificationRepository.insertLicense(license);
        supportRepository.writeAudit(actor, "DEALER_LICENSE_UPSERTED", "DEALER_LICENSE",
                String.valueOf(saved.getId()),
                activeOther == null ? null : "supersededNo=" + activeOther.getLicenseNo(),
                fingerprint(authority, issuedOn, expiresOn, evidenceDigest), traceId);
        return saved;
    }

    /**
     * 新增或更正拍卖场会员资格（UC-R1-1 同族，D07 §7.1 {@code POST /api/dealers/{id}/auction-memberships}）。
     *
     * <p>键是 (车商, 会场, 会员号) 三元组，{@code uk_dmem_dealer_house_no} 全表唯一。
     * <b>会员状态不在这里改</b>：停会/退会（SUSPENDED/TERMINATED）改变的是 G-A2 门禁的结论，
     * 属资质失效动作，本方法只做"这条会员关系存在与否"的登记与资料更正。</p>
     *
     * @param dealerId         车商 ID
     * @param auctionHouseCode 拍卖场代码（UUSS/TAA/IAA…）
     * @param memberNo         会员号
     * @param memberGrade      会员分级（只存代码；分级带来的上浮幅度 [待定源]，本包不推导）
     * @param joinedOn         入会日
     * @param expiresOn        有效期止；可空
     * @param evidencePath     凭证路径
     * @param actor            操作人
     * @param traceId          链路号
     * @return 落库或首次已有的会员行
     */
    @Transactional
    public AuctionMembership upsertAuctionMembership(Long dealerId, String auctionHouseCode, String memberNo,
                                                     String memberGrade, LocalDate joinedOn, LocalDate expiresOn,
                                                     String evidencePath, String actor, String traceId) {
        requireDealer(dealerId);
        String house = requireText(auctionHouseCode, "拍卖场代码");
        String no = requireText(memberNo, "会员号");

        Optional<AuctionMembership> existing =
                qualificationRepository.findMembership(dealerId, house, no);
        if (existing.isPresent()) {
            AuctionMembership current = existing.get();
            String before = membershipSnapshot(current);
            boolean same = Objects.equals(current.getMemberGrade(), blankToNull(memberGrade))
                    && Objects.equals(current.getJoinedOn(), joinedOn)
                    && Objects.equals(current.getExpiresOn(), expiresOn)
                    && Objects.equals(current.getEvidencePath(), blankToNull(evidencePath));
            if (same) {
                return current;
            }
            current.setMemberGrade(blankToNull(memberGrade));
            current.setJoinedOn(joinedOn);
            current.setExpiresOn(expiresOn);
            current.setEvidencePath(blankToNull(evidencePath));
            current.setUpdatedBy(actor);
            qualificationRepository.updateMembership(current);
            supportRepository.writeAudit(actor, "DEALER_MEMBERSHIP_CORRECTED", "DEALER_MEMBERSHIP",
                    String.valueOf(current.getId()), before, membershipSnapshot(current), traceId);
            return current;
        }

        AuctionMembership membership = new AuctionMembership();
        membership.setDealerId(dealerId);
        membership.setAuctionHouseCode(house);
        membership.setMemberNo(no);
        membership.setMemberGrade(blankToNull(memberGrade));
        membership.setJoinedOn(joinedOn);
        membership.setExpiresOn(expiresOn);
        membership.setStatus(AuctionMembershipStatus.ACTIVE);
        membership.setEvidencePath(blankToNull(evidencePath));
        membership.setCreatedBy(actor);
        membership.setUpdatedBy(actor);
        AuctionMembership saved = qualificationRepository.insertMembership(membership);
        supportRepository.writeAudit(actor, "DEALER_MEMBERSHIP_UPSERTED", "DEALER_MEMBERSHIP",
                String.valueOf(saved.getId()), null, membershipSnapshot(saved), traceId);
        return saved;
    }

    /**
     * 记录一次合规核查结论（UC-R1-2，D07 §7.1 {@code POST /api/dealers/{id}/compliance-checks}）。
     *
     * <p><b>append-only</b>：结论更正＝插一条新行（{@code checked_on} 更新），不改旧行——
     * 「何时由谁得出什么结论」是审计证据，被覆盖掉就没了。</p>
     *
     * <p><b>{@code validUntil} 的两条来路</b>：调用方显式给了就用它；没给时读参数表
     * {@code floorplan.used.compliance-valid-days}（[待定源 D-02]）按 {@code checkedOn + n} 推导；
     * 参数表也没有 ACTIVE 行时<b>落 NULL</b>，不猜 180 天。NULL 在准入判定里
     * 与"已过期"同后果（G-A3 取严），所以这是一条偏保守、不会误放行的缺省。</p>
     *
     * <p><b>幂等锚点偏宽的后果如实保留</b>：D00 §13 的键是 {@code CHK:{partner}:{requestNo}}，
     * 但 D04 §3.3 没给这张表 {@code partner_code} 列，V45 的唯一索引只能按
     * {@code request_no} 单列建，于是两个合作方撞同一个报文号时后来者会收到
     * {@code CONFLICT_001}。收窄需要收口方补列，本包不自决（见仓储接口注释）。</p>
     *
     * @param dealerId    车商 ID
     * @param checkType   核查种类（ANTI_SOCIAL/AML/IDENTITY/CREDIT_BUREAU）
     * @param checkedOn   核查日
     * @param validUntil  有效期至；可空
     * @param result      结论（PASS/FAIL/UNKNOWN）
     * @param source      结论来源（MANUAL/PARTNER_API/CREDIT_BUREAU）
     * @param requestNo   外部报文号（人工录入为空）
     * @param evidencePath 证据路径
     * @param note        备注
     * @param checkedBy   核查执行人（同时是 COMPLIANCE_CHECK_DONE 的主体）
     * @param actor       操作人
     * @param traceId     链路号
     * @return 落库或首次已有的核查行
     */
    @Transactional
    public ComplianceCheck recordComplianceCheck(Long dealerId, String checkType, LocalDate checkedOn,
                                                 LocalDate validUntil, String result, String source,
                                                 String requestNo, String evidencePath, String note,
                                                 String checkedBy, String actor, String traceId) {
        requireDealer(dealerId);
        ComplianceCheckType type = parseEnum(ComplianceCheckType.class, checkType, "核查种类");
        ComplianceResult outcome = parseEnum(ComplianceResult.class, result, "核查结论");
        ComplianceCheckSource checkSource = source == null || source.isBlank()
                ? ComplianceCheckSource.MANUAL
                : parseEnum(ComplianceCheckSource.class, source, "结论来源");
        if (checkedOn == null) {
            throw new BusinessException(ErrorCode.VALID_001, "核查日（checked_on）不能为空");
        }
        String reqNo = blankToNull(requestNo);

        if (reqNo != null) {
            Optional<ComplianceCheck> replay = qualificationRepository.findComplianceByRequestNo(reqNo);
            if (replay.isPresent()) {
                ComplianceCheck first = replay.get();
                if (sameCheckFingerprint(first, dealerId, type, outcome, checkedOn)) {
                    return first;
                }
                // 同报文号却给出不同结论：不是重放，是两次不同的事实互相矛盾，
                // 必须让人来看，不能静默覆盖成后到者。
                throw new BusinessException(ErrorCode.CONFLICT_001, Map.of(
                        "idempotencyKey", reqNo,
                        "reason", "SAME_REQUEST_NO_DIFFERENT_RESULT",
                        "existing", first.getCheckType() + "/" + first.getResult(),
                        "incoming", type + "/" + outcome));
            }
        }

        LocalDate effectiveUntil = validUntil != null ? validUntil : deriveValidUntil(checkedOn);

        ComplianceCheck check = new ComplianceCheck();
        check.setDealerId(dealerId);
        check.setCheckType(type);
        check.setCheckedOn(checkedOn);
        check.setValidUntil(effectiveUntil);
        check.setResult(outcome);
        check.setSource(checkSource);
        check.setRequestNo(reqNo);
        check.setEvidencePath(blankToNull(evidencePath));
        check.setNote(blankToNull(note));
        check.setCheckedBy(checkedBy == null || checkedBy.isBlank() ? actor : checkedBy.trim());
        check.setCreatedBy(actor);
        check.setUpdatedBy(actor);
        ComplianceCheck saved = qualificationRepository.insertCompliance(check);
        // 审计动作名照抄 D04 §3.3 的 COMPLIANCE_CHECK_DONE（定源已有名字，不自造）。
        supportRepository.writeAudit(actor, "COMPLIANCE_CHECK_DONE", "DEALER_COMPLIANCE_CHECK",
                String.valueOf(saved.getId()), null,
                type.name() + "/" + outcome.name() + ",validUntil=" + effectiveUntil
                        + ",source=" + checkSource.name(), traceId);
        return saved;
    }

    /**
     * 重算准入态（UC-R1-4，D07 §7.1 {@code POST /api/dealers/{id}/admission/recalculate}）。
     *
     * <p><b>无副作用的重算</b>（D07 §7.1 原文）：只推导并回写 {@code admission_state}，
     * 不改许可/会员/合规任何事实。判定用聚合里<b>本次事务读到的</b>三段历史，
     * 因此同一次调用内"读事实"与"写结论"看到的是一致的世界。</p>
     *
     * <p><b>为什么规则 3 传 {@code null} 会场</b>：准入态是车商维度的总结论，
     * 不绑定某一具体拍卖场；支用申请时才按会场判 G-A2（D06 §1.3）。
     * 在这里传会场会把"该车商在 UUSS 没会员"误算成整体不予准入。</p>
     *
     * @param dealerId 车商 ID
     * @param reason   重算原因（作业触发／人工触发，写进审计便于事后回看）
     * @param actor    操作人
     * @param traceId  链路号
     * @return 判定结论（含状态、错误码与理由）
     */
    @Transactional
    public AdmissionDecision recalculateAdmission(Long dealerId, String reason, String actor, String traceId) {
        requireDealer(dealerId);
        LocalDate bizDate = LocalDate.now();
        DealerQualification qualification = qualificationRepository.findByDealerId(dealerId)
                .orElseGet(() -> new DealerQualification(dealerId));
        Integer expiringWindowDays = usedConfigRepository
                .findInt(UsedConfigRepository.LICENSE_EXPIRY_ALERT_DAYS, bizDate)
                .orElse(null);
        AdmissionDecision decision = admissionPolicy.evaluate(qualification, null, bizDate, expiringWindowDays);

        AdmissionState before = qualificationRepository.findAdmissionState(dealerId).orElse(null);
        boolean written = qualificationRepository.updateAdmissionState(dealerId, decision.getState());
        if (written) {
            supportRepository.writeAudit(actor, "DEALER_ADMISSION_RECALCULATED", "DEALER",
                    String.valueOf(dealerId),
                    before == null ? "NULL(未评估)" : before.name(),
                    decision.getState().name() + ",reason=" + reason
                            + ",judge=" + decision.getReason(), traceId);
        }
        // G-A1 的工单口径：许可缺失/过期与"临近到期"两类才发 DEALER_QUALIFICATION_LAPSED。
        // 其余 BLOCKED_COMPLIANCE / BLOCKED_MEMBERSHIP 的催办属各自门禁的下游，
        // 本包不为它们自造工单类型（D06 §1.3 只给了这一个名字）。
        if (decision.getState() == AdmissionState.BLOCKED_LICENSE || decision.getState() == AdmissionState.EXPIRING) {
            supportRepository.createAlert(dealerId, null, "DEALER_QUALIFICATION_LAPSED", "HIGH",
                    "准入态=" + decision.getState().name() + "：" + decision.getReason()
                            + "（重算触发原因=" + reason + "）");
        }
        // † DEALER_ADMISSION_STATE_CHANGED 不在 D00 冻结事件词表内：本轮只登记，不发 outbox。
        return decision;
    }

    /**
     * 列出将于窗口内到期或已过期的有效许可（UC-R1-5 的作业数据源）。
     *
     * <p><b>本包不建定时任务</b>：作业触发归 {@code JOB_TRIGGER} 侧与收口方排期，
     * 这里只给一个可被作业调用的只读查询。</p>
     *
     * <p><b>窗口天数没有默认值</b>：调用方没传就读参数表
     * {@code floorplan.used.license-expiry-alert-days}；两处都没有就报
     * {@code VALID_001}——"30 天"是 [待定源]，猜一个数会把预警名单的口径定死在代码里，
     * 比不返回更难纠正。</p>
     *
     * @param withinDays 预警窗口天数；可为 null（改读参数表）
     * @param bizDate    业务日；null 时取今天
     * @return 到期预警项（许可行原样返回，文案由上层拼）
     */
    @Transactional(readOnly = true)
    public List<DealerLicense> listExpiringLicenses(Integer withinDays, LocalDate bizDate) {
        LocalDate today = bizDate == null ? LocalDate.now() : bizDate;
        Integer days = withinDays != null ? withinDays
                : usedConfigRepository.findInt(UsedConfigRepository.LICENSE_EXPIRY_ALERT_DAYS, today)
                        .orElse(null);
        if (days == null || days <= 0) {
            throw new BusinessException(ErrorCode.VALID_001, Map.of(
                    "reason", "EXPIRY_WINDOW_NOT_SIGNED",
                    "detail", "预警窗口天数未签字：请在参数表登记 "
                            + UsedConfigRepository.LICENSE_EXPIRY_ALERT_DAYS + " 或显式传入 withinDays"));
        }
        return qualificationRepository.findActiveLicensesExpiringBy(today.plusDays(days));
    }

    /**
     * 取某车商的许可清单（D07 §7.1 {@code GET /api/dealers/{id}/licenses}）。
     *
     * <p>含 {@code SUPERSEDED}／{@code REVOKED} 历史行：GET 是"看这个主体的资质历史"，
     * 只回 ACTIVE 会让换证链在接口上断开，前端也就无法解释"为什么准入态是 BLOCKED_LICENSE"。</p>
     *
     * @param dealerId 车商 ID
     * @return 许可行列表；无记录返回空列表（不是 404——主体存在而没录资质是常态，
     *         这正是准入态要判成 {@code BLOCKED_LICENSE} 的情形）
     */
    @Transactional(readOnly = true)
    public List<DealerLicense> listLicenses(Long dealerId) {
        requireDealer(dealerId);
        return qualificationRepository.findByDealerId(dealerId)
                .map(DealerQualification::getLicenses)
                .orElse(List.of());
    }

    /**
     * 取某车商的拍卖场会员关系（D07 §7.1 {@code GET /api/dealers/{id}/auction-memberships?status=}）。
     *
     * @param dealerId 车商 ID
     * @param status   状态过滤（可空＝不过滤）
     * @return 会员行列表
     */
    @Transactional(readOnly = true)
    public List<AuctionMembership> listAuctionMemberships(Long dealerId, String status) {
        requireDealer(dealerId);
        String normalized = blankToNull(status);
        if (normalized != null) {
            // 过滤条件写错时宁可在入口给 400：把非法串当"查不到"返回空列表，
            // 调用方会以为该车商没有会员关系，而这是准入判定 G-A2 的输入。
            parseEnum(AuctionMembershipStatus.class, normalized, "会员状态");
        }
        return qualificationRepository.findMemberships(dealerId, normalized);
    }

    /**
     * 看当前准入判定（D07 §7.1 {@code GET /api/dealers/{id}/admission}）。
     *
     * <p><b>GET 不写库</b>：与 {@link #recalculateAdmission} 的分工是
     * "算给你看" vs "把结论落进 {@code admission_state}"。若 GET 也回写，
     * 一次列表页刷新就会改主数据并留下审计，重算的触发点也变得不可追溯。</p>
     *
     * <p><b>同时返回库里存的结论</b>（{@code storedState}）：两个值不同时说明
     * "事实变了但没人重算"，这正是 UC-R1-4 存在的理由，前端要能看见这层落差。</p>
     *
     * @param dealerId 车商 ID
     * @param bizDate  业务日；null 取今天（测试与补算历史某日用）
     * @return 判定视图（现算结论＋库中存量＋本次用的预警窗口）
     */
    @Transactional(readOnly = true)
    public AdmissionView currentAdmission(Long dealerId, LocalDate bizDate) {
        requireDealer(dealerId);
        LocalDate today = bizDate == null ? LocalDate.now() : bizDate;
        DealerQualification qualification = qualificationRepository.findByDealerId(dealerId)
                .orElseGet(() -> new DealerQualification(dealerId));
        Integer expiringWindowDays = usedConfigRepository
                .findInt(UsedConfigRepository.LICENSE_EXPIRY_ALERT_DAYS, today)
                .orElse(null);
        AdmissionDecision decision = admissionPolicy.evaluate(qualification, null, today, expiringWindowDays);
        AdmissionState storedState = qualificationRepository.findAdmissionState(dealerId).orElse(null);
        return new AdmissionView(dealerId, today, storedState, decision, expiringWindowDays);
    }

    /**
     * 准入判定视图（只读用例的返回结构）。
     *
     * @param dealerId            车商 ID
     * @param bizDate             本次判定用的业务日
     * @param storedState         库里已落的准入态（{@code null}＝从未评估）
     * @param decision            现算结论
     * @param expiringWindowDays  本次用的临近到期窗口；{@code null}＝参数未签字，EXPIRING 档不会产出
     */
    public record AdmissionView(Long dealerId, LocalDate bizDate, AdmissionState storedState,
                                AdmissionDecision decision, Integer expiringWindowDays) {
    }

    // =====================================================================
    // 内部小工具
    // =====================================================================

    /** 车商存在性：资质三张表都有 {@code REFERENCES dealers(id)}，主体不存在时提前给 404 而不是撞 FK。 */
    private void requireDealer(Long dealerId) {
        if (dealerId == null) {
            throw new BusinessException(ErrorCode.VALID_001, "车商 ID 不能为空");
        }
        dealerRepository.findById(dealerId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND_001, "车商不存在：" + dealerId));
    }

    /** 必填文本：空白归一为 null 后拒绝，避免把空串写进 NOT NULL 列（V45 的三列都是）。 */
    private static String requireText(String value, String label) {
        String v = blankToNull(value);
        if (v == null) {
            throw new BusinessException(ErrorCode.VALID_001, label + "不能为空");
        }
        return v;
    }

    /**
     * 枚举解析：非法取值回 {@code VALID_001} 而不是让 {@code valueOf} 抛
     * {@code IllegalArgumentException} 变成 500（与 {@code DealerApplicationService#parseStatus} 同款）。
     */
    private static <E extends Enum<E>> E parseEnum(Class<E> type, String raw, String label) {
        String v = blankToNull(raw);
        if (v == null) {
            throw new BusinessException(ErrorCode.VALID_001, label + "不能为空");
        }
        try {
            return Enum.valueOf(type, v.toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(ErrorCode.VALID_001,
                    label + "取值非法：" + raw + "（合法值见 D00 §8.1 字典）");
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /** 许可指纹：判定"这次请求是不是同一件事"只比这四列，不比审计主体（换人录同一事实仍是重放）。 */
    private static boolean sameLicenseFingerprint(DealerLicense current, String authority, LocalDate issuedOn,
                                                  LocalDate expiresOn, String evidenceDigest) {
        return Objects.equals(current.getCompetentAuthority(), authority)
                && Objects.equals(current.getIssuedOn(), issuedOn)
                && Objects.equals(current.getExpiresOn(), expiresOn)
                && Objects.equals(current.getEvidenceDigest(), blankToNull(evidenceDigest));
    }

    private static String licenseFingerprintOf(DealerLicense l) {
        return fingerprint(l.getCompetentAuthority(), l.getIssuedOn(), l.getExpiresOn(), l.getEvidenceDigest());
    }

    private static String fingerprint(String authority, LocalDate issuedOn, LocalDate expiresOn, String digest) {
        return "authority=" + authority + ",issuedOn=" + issuedOn + ",expiresOn=" + expiresOn
                + ",digest=" + blankToNull(digest);
    }

    private static String membershipSnapshot(AuctionMembership m) {
        return "house=" + m.getAuctionHouseCode() + ",memberNo=" + m.getMemberNo()
                + ",grade=" + m.getMemberGrade() + ",joinedOn=" + m.getJoinedOn()
                + ",expiresOn=" + m.getExpiresOn() + ",status=" + nameOf(m.getStatus());
    }

    /** 核查指纹：同报文号必须落在同一车商、同种类、同结论、同核查日上才算重放。 */
    private static boolean sameCheckFingerprint(ComplianceCheck first, Long dealerId, ComplianceCheckType type,
                                                ComplianceResult outcome, LocalDate checkedOn) {
        return Objects.equals(first.getDealerId(), dealerId)
                && first.getCheckType() == type
                && first.getResult() == outcome
                && Objects.equals(first.getCheckedOn(), checkedOn);
    }

    /**
     * 推导合规有效期。
     *
     * <p>读 {@code floorplan.used.compliance-valid-days}（D-02 未签字 ⟹ 参数表里没有 ACTIVE 行），
     * 读不到就返回 null：V41 的读侧口径是"库内值 → 显式入参 → fail-closed"，
     * 这里对应到判定侧就是"有效期为空 ⟹ G-A3 需要 valid_until 未过期时才判通过"，
     * 由领域层按取严处理，不在本层塞一个 180 天。</p>
     */
    private LocalDate deriveValidUntil(LocalDate checkedOn) {
        return usedConfigRepository.findInt(UsedConfigRepository.COMPLIANCE_VALID_DAYS, checkedOn)
                .map(checkedOn::plusDays)
                .orElse(null);
    }

    private static String nameOf(Enum<?> value) {
        return value == null ? null : value.name();
    }
}

package com.company.floorplan.application.service;

import com.company.floorplan.application.dto.LicenseDraft;
import com.company.floorplan.domain.exception.BusinessException;
import com.company.floorplan.domain.exception.ErrorCode;
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
import com.company.floorplan.domain.model.UsedConfigKeys;
import com.company.floorplan.domain.repository.DealerQualificationRepository;
import com.company.floorplan.domain.repository.SupportRepository;
import com.company.floorplan.domain.repository.UsedConfigRepository;
import com.company.floorplan.domain.service.DealerAdmissionPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 车商资质用例编排（USED R1，D06 §1.2 的 {@code DealerQualificationApplicationService}）。
 *
 * <p><b>三个写用例的共同形状</b>：参数校验 → 幂等判定 → 写证据表 → <b>重算准入态</b> → 审计。
 * 第四步骤不能省，也不能交给「明天再跑的到期作业」：</p>
 * <ul>
 *   <li>准入态是放款闸门的读入项。登记了一张已过期许可却把 {@code admission_state}
 *       留在 {@code ADMITTED}，闸门就会拿着旧快照放行；</li>
 *   <li>反过来（每次读都现算）会把闸门变成一次全表扫描，且无法回答
 *       「当时是以什么状态放行的」——审计要的是当时那个值，所以必须落快照。</li>
 * </ul>
 * <p>因此这里的顺序是「写完证据立刻在同事务内重算」，两者原子。</p>
 *
 * <p><b>事务边界只在本类</b>（AGENTS.md §10：{@code @Transactional} 只出现在 application 层）；
 * domain 的 {@link DealerAdmissionPolicy} 是纯函数，不碰库也不开事务。</p>
 */
@Service
public class DealerQualificationApplicationService {

    private static final Logger log = LoggerFactory.getLogger(DealerQualificationApplicationService.class);

    /** 审计动作名（与前端「操作日志」筛选项一致，改名要同步前端文案）。 */
    private static final String ACTION_LICENSE = "DEALER_LICENSE_UPSERT";
    private static final String ACTION_MEMBERSHIP = "DEALER_MEMBERSHIP_UPSERT";
    private static final String ACTION_COMPLIANCE = "DEALER_COMPLIANCE_CHECK";
    private static final String ACTION_ADMISSION = "DEALER_ADMISSION_RECALC";
    private static final String TARGET_TYPE = "DEALER_QUALIFICATION";

    /** 预警类型：资质到期预警（D03 §2.1 规则 4 要求"产出资质到期预警工单"）。 */
    private static final String ALERT_QUAL_EXPIRING = "DEALER_QUALIFICATION_EXPIRING";
    /** 预警类型：资质失效（D06 §1.1 验收点「无许可/许可过期 → 拒付 + 工单」）。 */
    private static final String ALERT_QUAL_LAPSED = "DEALER_QUALIFICATION_LAPSED";

    private final DealerQualificationRepository qualificationRepository;
    private final UsedConfigRepository configRepository;
    private final SupportRepository supportRepository;

    public DealerQualificationApplicationService(DealerQualificationRepository qualificationRepository,
                                                UsedConfigRepository configRepository,
                                                SupportRepository supportRepository) {
        this.qualificationRepository = qualificationRepository;
        this.configRepository = configRepository;
        this.supportRepository = supportRepository;
    }

    /**
     * 登记或换发古物商许可（UC-R1-1，幂等键 {@code DLIC:{dealerId}:{type}:{no}}）。
     *
     * <p><b>为什么要先降级再插入</b>：库内条件唯一索引
     * {@code uk_dealer_license_active ON (dealer_id, license_type) WHERE status='ACTIVE'}
     * 只允许一条 ACTIVE。顺序反了不是「逻辑不严谨」而是直接撞索引报错，
     * 且报错信息（duplicate key）与业务原因（换发）毫无关系，排查成本极高。</p>
     *
     * <p><b>同证号重复登记视为幂等</b>：命中 {@code uk_dlic_dealer_type_no} 时返回既有行，
     * 不报错也不写第二行。理由是车商换证时证号通常不变（改的是有效期），
     * 若把「同号再登记」当错误，前一次网络超时重试就会让内勤卡在这里；
     * 若要改有效期，走显式的 {@code expires_on} 更新而不是重新登记。</p>
     *
     * @param draft 许可草稿（证号、发证机关、交付日、到期日、证据）
     * @param operator 操作人（必须来自 {@code AuditActor}）
     * @return 登记结果视图（含是否新建、是否降级了旧证）
     */
    @Transactional
    public Map<String, Object> upsertLicense(LicenseDraft draft, String operator) {
        Long dealerId = requireDealer(draft.dealerId());
        LicenseType type = draft.licenseType() == null ? LicenseType.FURIGUSHO : draft.licenseType();
        // 要件校验只查「这条记录能不能被称为一张证」，不查业务口径：
        // 证号/发证机关/交付日缺一就无法事后核验，空白串也不能算有（"　" 不是号）。
        if (draft.licenseNo() == null || draft.licenseNo().isBlank()) {
            throw new BusinessException(ErrorCode.VALID_001, "许可番号不能为空");
        }
        if (draft.competentAuthority() == null || draft.competentAuthority().isBlank()) {
            throw new BusinessException(ErrorCode.VALID_001, "管辖公安委員会（发证机关）不能为空");
        }
        if (draft.issuedOn() == null) {
            throw new BusinessException(ErrorCode.VALID_001, "許可の交付日不能为空");
        }
        if (draft.expiresOn() != null && draft.expiresOn().isBefore(draft.issuedOn())) {
            throw new BusinessException(ErrorCode.VALID_001,
                    "有效期末日早于交付日: issued_on=" + draft.issuedOn() + " expires_on=" + draft.expiresOn());
        }

        Map<String, Object> data = new LinkedHashMap<>();
        Optional<DealerLicense> existing =
                qualificationRepository.findLicenseByKey(dealerId, type, draft.licenseNo().trim());
        if (existing.isPresent()) {
            // 幂等分支：同一证号已经登记过。回既有行，不动库、不重算、不写审计
            // （写审计会让一次重试产生两条"登记许可"，把操作流水搅浑）。
            DealerLicense hit = existing.get();
            data.put("idempotent", true);
            data.put("licenseId", hit.getId());
            data.put("status", hit.getStatus() == null ? null : hit.getStatus().name());
            data.put("supersededLicenseId", null);
            data.put("admissionState", currentAdmissionSnapshot(dealerId));
            return data;
        }

        // 换发：旧 ACTIVE 行降级 SUPERSEDED。当前实现按「同类型只可能有一条 ACTIVE」处理，
        // 但为防历史脏数据（索引建立前写入的多条），这里遍历降级而不是只降级一条。
        List<Long> superseded = new ArrayList<>();
        for (DealerLicense old : qualificationRepository.findLicenses(dealerId)) {
            if (old.getLicenseType() == type && old.getStatus() == LicenseStatus.ACTIVE) {
                int n = qualificationRepository.updateLicenseStatus(old.getId(), LicenseStatus.SUPERSEDED, operator);
                if (n > 0) {
                    superseded.add(old.getId());
                }
            }
        }

        DealerLicense license = new DealerLicense();
        license.setDealerId(dealerId);
        license.setLicenseType(type);
        license.setLicenseNo(draft.licenseNo().trim());
        license.setCompetentAuthority(draft.competentAuthority().trim());
        license.setIssuedOn(draft.issuedOn());
        license.setExpiresOn(draft.expiresOn());
        license.setStatus(LicenseStatus.ACTIVE);
        license.setEvidencePath(draft.evidencePath());
        license.setEvidenceDigest(draft.evidenceDigest());
        license.setVerifiedBy(operator);
        license.setVerifiedAt(OffsetDateTime.now());
        qualificationRepository.insertLicense(license, operator);

        supportRepository.writeAudit(operator, ACTION_LICENSE, TARGET_TYPE, String.valueOf(dealerId),
                superseded.isEmpty() ? null : "superseded=" + superseded,
                "licenseId=" + license.getId() + ",no=" + license.getLicenseNo()
                        + ",expires_on=" + license.getExpiresOn(), null);

        // 证据一变，快照立刻重算（见类注释）。reason 带进审计，便于回溯是谁的哪个动作触发的。
        Map<String, Object> recalc = recalculateAdmission(dealerId, ACTION_LICENSE, operator);
        data.put("idempotent", false);
        data.put("licenseId", license.getId());
        data.put("status", LicenseStatus.ACTIVE.name());
        data.put("supersededLicenseId", superseded.isEmpty() ? null : superseded.get(0));
        data.put("supersededLicenseIds", superseded);
        data.put("admissionState", recalc.get("admissionState"));
        return data;
    }

    /**
     * 维护拍卖场会员关系（UC-R1-1，幂等键 {@code DMEM:{dealerId}:{house}:{memberNo}}）。
     *
     * <p>与许可不同：会员的「同一自然键再登记」不是幂等返回，而是<b>就地更新</b>
     * （改状态、改到期日、改等级）。原因是会籍状态本身是会来回变的
     * （ACTIVE ↔ SUSPENDED 的续会/停摆），如果只允许追加就会要求每次都新增一行，
     * 而 {@code uk_dmem_dealer_house_no} 根本不允许同号第二行——
     * 追加语义在这张表上是跑不通的。</p>
     */
    @Transactional
    public Map<String, Object> upsertAuctionMembership(Long dealerId, String auctionHouseCode, String memberNo,
                                                       String memberGrade, LocalDate joinedOn, LocalDate expiresOn,
                                                       String status, String evidencePath, String operator) {
        Long id = requireDealer(dealerId);
        if (auctionHouseCode == null || auctionHouseCode.isBlank()) {
            throw new BusinessException(ErrorCode.VALID_001, "拍卖场代码不能为空");
        }
        if (memberNo == null || memberNo.isBlank()) {
            throw new BusinessException(ErrorCode.VALID_001, "会員番号不能为空");
        }
        MembershipStatus ms = MembershipStatus.from(status);
        if (status != null && !status.isBlank() && ms == null) {
            throw new BusinessException(ErrorCode.VALID_001, "未知的会员状态: " + status);
        }
        if (ms == null) {
            ms = MembershipStatus.ACTIVE;
        }

        DealerAuctionMembership membership = new DealerAuctionMembership();
        membership.setDealerId(id);
        membership.setAuctionHouseCode(auctionHouseCode.trim().toUpperCase(java.util.Locale.ROOT));
        membership.setMemberNo(memberNo.trim());
        membership.setMemberGrade(memberGrade);
        membership.setJoinedOn(joinedOn);
        membership.setExpiresOn(expiresOn);
        membership.setStatus(ms);
        membership.setEvidencePath(evidencePath);

        Optional<DealerAuctionMembership> existing = qualificationRepository
                .findMembershipByKey(id, membership.getAuctionHouseCode(), membership.getMemberNo());
        boolean created = existing.isEmpty();
        if (created) {
            qualificationRepository.insertMembership(membership, operator);
        } else {
            membership.setId(existing.get().getId());
            qualificationRepository.updateMembership(membership, operator);
        }

        supportRepository.writeAudit(operator, ACTION_MEMBERSHIP, TARGET_TYPE, String.valueOf(id),
                created ? null : "membershipId=" + existing.get().getId(),
                "house=" + membership.getAuctionHouseCode() + ",member_no=" + membership.getMemberNo()
                        + ",status=" + ms.name() + ",expires_on=" + membership.getExpiresOn(), null);

        Map<String, Object> recalc = recalculateAdmission(id, ACTION_MEMBERSHIP, operator);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("membershipId", membership.getId());
        data.put("created", created);
        data.put("admissionState", recalc.get("admissionState"));
        return data;
    }

    /**
     * 录入合规核查结论（UC-R1-2，幂等键 {@code CHK:{partner}:{requestNo}}）。
     *
     * <p><b>有效期缺失时不回落硬编码 180</b>（V41 的 fail-closed 分支）：
     * 参数 {@code compliance-valid-days} 读不到 ⟹ 拒绝「不给期限的人工推导」，
     * 要求调用方显式传 {@code valid_until}。这样库内出现的每一行都有明确的期限来源，
     * 要么是人给的、要么是签字过的参数给的。</p>
     *
     * <p><b>{@code source=MANUAL} 必须二人 + 证据</b>（D07 §3.8）：人工录入的
     * 「查过了、没问题」没有对外凭据，单人自证等于把 G-A3 这道闸门交给一个人。
     * 库内 V76 的 {@code ck_dchk_two_person} 是同一条规则的第二道防线。</p>
     */
    @Transactional
    public Map<String, Object> recordComplianceCheck(Long dealerId, String checkType, LocalDate checkedOn,
                                                     LocalDate validUntil, String result, String source,
                                                     String requestNo, String evidencePath, String note,
                                                     String coSignedBy, String operator) {
        Long id = requireDealer(dealerId);
        ComplianceCheckType type = ComplianceCheckType.from(checkType);
        if (type == null) {
            throw new BusinessException(ErrorCode.VALID_001,
                    "合规核查类型必填且必须是 " + ComplianceCheckType.required() + " 之一");
        }
        ComplianceResult res = ComplianceResult.from(result);
        if (res == null) {
            throw new BusinessException(ErrorCode.VALID_001, "核查结论必填（PASS/FAIL/UNKNOWN）");
        }
        ComplianceSource src = ComplianceSource.from(source);
        if (source != null && !source.isBlank() && src == null) {
            throw new BusinessException(ErrorCode.VALID_001, "未知的核查来源: " + source);
        }
        if (src == null) {
            // 缺省按最严的一侧处理：MANUAL 会触发二人 + 证据校验，
            // 让"没说要领来源"的记录必须补第二人，而不是默认落在权威源那一侧免检。
            src = ComplianceSource.MANUAL;
        }
        LocalDate on = checkedOn == null ? LocalDate.now() : checkedOn;

        if (ComplianceSource.requiresEvidenceAndCoSign(src)) {
            if (evidencePath == null || evidencePath.isBlank()) {
                throw new BusinessException(ErrorCode.VALID_001,
                        "source=" + src.name() + " 要求 evidence_path（D07 §3.8）");
            }
            if (coSignedBy == null || coSignedBy.isBlank()) {
                throw new BusinessException(ErrorCode.AUTH_003,
                        "人工录入的合规结论需第二人签认（违反发起与执行分离）");
            }
            if (coSignedBy.equals(operator)) {
                throw new BusinessException(ErrorCode.AUTH_003, "同人自证：签认人不能是录入人自己");
            }
        }

        LocalDate until = validUntil;
        if (until == null) {
            Integer days = configRepository.findInt(UsedConfigKeys.COMPLIANCE_VALID_DAYS).orElse(null);
            if (days == null || days <= 0) {
                // fail-closed：没有签字过的参数值，就不替业务发明一个有效期。
                throw new BusinessException(ErrorCode.VALID_001,
                        "未配置 " + UsedConfigKeys.COMPLIANCE_VALID_DAYS
                                + "，无法推导结论有效期；请显式传入 valid_until");
            }
            until = on.plusDays(days);
        }
        if (until.isBefore(on)) {
            throw new BusinessException(ErrorCode.VALID_001,
                    "有效期末日早于核查日: checked_on=" + on + " valid_until=" + until);
        }

        Map<String, Object> data = new LinkedHashMap<>();
        if (requestNo != null && !requestNo.isBlank()) {
            Optional<DealerComplianceCheck> dup =
                    qualificationRepository.findComplianceCheckByRequestNo(requestNo.trim());
            if (dup.isPresent()) {
                // I-11 重推：同一个对外请求号只可能有一个结论，重复推送返回首条。
                // 这里不比较内容是否一致——request_no 是合作方侧的号，内容不一致属对方故障，
                // 静默返回首条比"造出第二条结论"更安全（uk_dchk_request_no 也是这么定的）。
                data.put("idempotent", true);
                data.put("checkId", dup.get().getId());
                data.put("result", dup.get().getResult() == null ? null : dup.get().getResult().name());
                data.put("admissionState", currentAdmissionSnapshot(id));
                return data;
            }
        }

        DealerComplianceCheck check = new DealerComplianceCheck();
        check.setDealerId(id);
        check.setCheckType(type);
        check.setCheckedOn(on);
        check.setValidUntil(until);
        check.setResult(res);
        check.setSource(src);
        check.setRequestNo(requestNo == null || requestNo.isBlank() ? null : requestNo.trim());
        check.setEvidencePath(evidencePath);
        check.setNote(note);
        check.setCheckedBy(operator);
        check.setCoSignedBy(coSignedBy);
        qualificationRepository.insertComplianceCheck(check, operator);

        supportRepository.writeAudit(operator, ACTION_COMPLIANCE, TARGET_TYPE, String.valueOf(id),
                null, "type=" + type.name() + ",result=" + res.name() + ",source=" + src.name()
                        + ",checked_on=" + on + ",valid_until=" + until
                        + ",co_signed_by=" + coSignedBy, null);

        Map<String, Object> recalc = recalculateAdmission(id, ACTION_COMPLIANCE, operator);
        data.put("idempotent", false);
        data.put("checkId", check.getId());
        data.put("result", res.name());
        data.put("admissionState", recalc.get("admissionState"));
        return data;
    }

    /**
     * 重算并落准入态快照（UC-R1-4）。
     *
     * <p><b>预警走 {@code alerts} 而不是工单</b>：D03 §2.1 规则 4 与 D06 §1.1 验收点都要求
     * 「产出资质到期预警工单」，但本仓库的 {@code work_orders} 表<b>零 Java 实现</b>
     * （PROGRESS.md §9 第 4 条断裂）。在没有工单服务的前提下造一行 work_orders 记录，
     * 是一条没人消费的死数据；因此本轮用已有的 {@code alerts}（OPEN 态、有人看）承载，
     * 并把「工单化」列为待接线项。这是替代实现，不是忽略验收点。</p>
     *
     * <p><b>幂等</b>：本方法只写快照与预警，同一事实重复调用结果一致（预警按类型+OPEN 判存在，
     * 不重复堆同一条）。到期作业每天调它，不需要幂等键。</p>
     */
    @Transactional
    public Map<String, Object> recalculateAdmission(Long dealerId, String reason, String operator) {
        Long id = requireDealer(dealerId);
        DealerQualification qualification = qualificationRepository.findByDealerId(id);

        // 预警窗口取值：读不到 ⟹ 按 0 天（等于关闭预警）。
        // 这里可以fail-open 是有前提的：EXPIRING 本身不阻断放款（D03 §2.1 规则 4 是"允许但预警"），
        // 参数缺失只会少发预警，不会造成放行——与合规有效期那处的 fail-closed 方向不同，
        // 因为那一处的默认值会直接决定「一条没有期限的结论能不能算通过」，那是硬闸门。
        int alertDays = configRepository.findInt(UsedConfigKeys.LICENSE_EXPIRY_ALERT_DAYS).orElse(0);
        if (alertDays <= 0) {
            log.warn("准入预警窗口未配置或为 0，EXPIRING 预警将不产生 dealerId={} key={}",
                    id, UsedConfigKeys.LICENSE_EXPIRY_ALERT_DAYS);
        }

        DealerAdmissionPolicy.AdmissionDecision decision =
                DealerAdmissionPolicy.evaluate(qualification, LocalDate.now(), alertDays, alertDays);

        AdmissionState before = qualification.getStoredAdmissionState();
        qualificationRepository.updateAdmissionState(id, decision.state());

        supportRepository.writeAudit(operator, ACTION_ADMISSION, TARGET_TYPE, String.valueOf(id),
                before == null ? null : before.name(),
                decision.state().name() + ",reason=" + reason
                        + (decision.unmetReasons().isEmpty() ? "" : ",unmet=" + decision.unmetReasons())
                        + (decision.daysToExpiry() == null ? "" : ",days_to_expiry=" + decision.daysToExpiry()),
                null);

        // 预警只在「态变化时」落一次，避免到期作业每天刷一条同内容告警。
        boolean stateChanged = before != decision.state();
        String alertType = null;
        if (decision.state() == AdmissionState.EXPIRING && stateChanged) {
            alertType = ALERT_QUAL_EXPIRING;
        } else if (decision.state() != null && decision.state().blocksAdvance() && stateChanged) {
            // 从任何态跌进阻断态：这是 D06 §1.1 验收点要的「拒付 + 工单」信号
            alertType = ALERT_QUAL_LAPSED;
        }
        if (alertType != null && !hasOpenAlert(id, alertType)) {
            supportRepository.createAlert(id, null, alertType,
                    decision.state().blocksAdvance() ? "HIGH" : "WARNING",
                    "admission_state=" + decision.state().name()
                            + " reason=" + decision.reason()
                            + " days_to_expiry=" + decision.daysToExpiry());
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("dealerId", id);
        data.put("admissionState", decision.state().name());
        data.put("errorCode", decision.state().errorCode() == null ? null : decision.state().errorCode().name());
        data.put("blocked", decision.state().blocksAdvance());
        data.put("daysToExpiry", decision.daysToExpiry());
        data.put("missingComplianceTypes", decision.missingTypes());
        data.put("unmetReasons", decision.unmetReasons());
        data.put("alertRaised", alertType);
        return data;
    }

    /**
     * 读准入态与证据概览（GET /admission）；只读，不落库。
     *
     * <p><b>判的是实时值，不是快照</b>：{@code dealers.admission_state} 只在证据写入与到期作业
     * 重算时刷新，而许可过期、合规结论过期这两件事<b>不需要任何人写一行数据</b>就会发生。
     * 若把快照当判定返回，新建还没判过的车商读出来是 null（闸门要么炸要么放行），
     * 证过期的车商读出来还是 ADMITTED。所以这里现算，快照另出两个字段供对账：</p>
     * <ul>
     *   <li>{@code admissionState} ⟺ 按今天的日期现算的结论（放闸门该读这个）；</li>
     *   <li>{@code storedAdmissionState} ⟺ 库里那份快照（可能是昨天算的）；</li>
     *   <li>{@code snapshotStale} ⟺ 两者不一致，提示该跑重算作业了。</li>
     * </ul>
     */
    public Map<String, Object> getAdmission(Long dealerId) {
        Long id = requireDealer(dealerId);
        DealerQualification q = qualificationRepository.findByDealerId(id);
        int alertDays = configRepository.findInt(UsedConfigKeys.LICENSE_EXPIRY_ALERT_DAYS).orElse(0);
        DealerAdmissionPolicy.AdmissionDecision live =
                DealerAdmissionPolicy.evaluate(q, LocalDate.now(), alertDays, alertDays);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("dealerId", id);
        data.put("admissionState", live.state().name());
        data.put("errorCode", live.state().errorCode() == null ? null : live.state().errorCode().name());
        data.put("blocked", live.state().blocksAdvance());
        data.put("daysToExpiry", live.daysToExpiry());
        data.put("missingComplianceTypes", live.missingTypes());
        data.put("unmetReasons", live.unmetReasons());
        data.put("storedAdmissionState", q.getStoredAdmissionState() == null
                ? null : q.getStoredAdmissionState().name());
        data.put("snapshotStale", q.getStoredAdmissionState() != live.state());
        DealerLicense active = q.getActiveLicense();
        data.put("activeLicense", active == null ? null : licenseView(active));
        data.put("membershipCount", q.getMemberships().size());
        List<Map<String, Object>> memberships = new ArrayList<>();
        for (DealerAuctionMembership m : q.getMemberships()) {
            memberships.add(membershipView(m));
        }
        data.put("memberships", memberships);
        List<Map<String, Object>> checks = new ArrayList<>();
        for (DealerComplianceCheck c : q.getComplianceChecks()) {
            checks.add(complianceView(c));
        }
        data.put("complianceChecks", checks);
        return data;
    }

    /** 许可列表（GET /licenses）。 */
    public List<Map<String, Object>> listLicenses(Long dealerId) {
        Long id = requireDealer(dealerId);
        List<Map<String, Object>> out = new ArrayList<>();
        for (DealerLicense l : qualificationRepository.findLicenses(id)) {
            out.add(licenseView(l));
        }
        return out;
    }

    /** 会员列表，可按状态筛（GET /auction-memberships?status=）。 */
    public List<Map<String, Object>> listMemberships(Long dealerId, String status) {
        Long id = requireDealer(dealerId);
        MembershipStatus filter = MembershipStatus.from(status);
        List<Map<String, Object>> out = new ArrayList<>();
        for (DealerAuctionMembership m : qualificationRepository.findMemberships(id)) {
            if (filter == null || m.getStatus() == filter) {
                out.add(membershipView(m));
            }
        }
        return out;
    }

    /**
     * 到期预警列表（UC-R1-4 的 {@code listExpiringLicenses(withinDays)}）。
     *
     * <p>只返回 {@code status='ACTIVE'} 的证：已经是 SUPERSEDED/EXPIRED 的行不需要再预警，
     * 重复提醒只会淹掉真正要处理的那几条。</p>
     */
    public List<Map<String, Object>> listExpiringLicenses(int withinDays) {
        LocalDate today = LocalDate.now();
        List<Map<String, Object>> out = new ArrayList<>();
        for (DealerLicense l : qualificationRepository
                .findActiveLicensesExpiringBetween(today, today.plusDays(Math.max(withinDays, 0)))) {
            out.add(licenseView(l));
        }
        return out;
    }

    /** 车商存在性检查：不存在时统一回 NOT_FOUND_001，而不是让后面的 SQL 默默写进孤儿行。 */
    private Long requireDealer(Long dealerId) {
        if (dealerId == null) {
            throw new BusinessException(ErrorCode.VALID_001, "dealerId 不能为空");
        }
        return qualificationRepository.findDealerId(dealerId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND_001, "车商不存在: " + dealerId));
    }

    /** 当前库内快照（写用例返回体里带，省一次读接口往返）。 */
    private String currentAdmissionSnapshot(Long dealerId) {
        AdmissionState s = qualificationRepository.findByDealerId(dealerId).getStoredAdmissionState();
        return s == null ? null : s.name();
    }

    /** 同一车商同一类型是否已有 OPEN 预警（避免每天堆同一条）。 */
    private boolean hasOpenAlert(Long dealerId, String alertType) {
        // SupportRepository 没有「按 dealer 查 OPEN」的重载，findAlerts 是全类型+全状态带 limit。
        // 这里复用后过滤而不是新增接口：预警池的读法在 R9/R10 还会变，先不把查询形状固化进 domain。
        return supportRepository.findAlerts(alertType, "OPEN", 200).stream()
                .anyMatch(a -> dealerId.equals(a.getDealerId()));
    }

    private Map<String, Object> licenseView(DealerLicense l) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", l.getId());
        m.put("dealerId", l.getDealerId());
        m.put("licenseType", l.getLicenseType() == null ? null : l.getLicenseType().name());
        m.put("licenseNo", l.getLicenseNo());
        m.put("competentAuthority", l.getCompetentAuthority());
        m.put("issuedOn", l.getIssuedOn() == null ? null : l.getIssuedOn().toString());
        m.put("expiresOn", l.getExpiresOn() == null ? null : l.getExpiresOn().toString());
        m.put("status", l.getStatus() == null ? null : l.getStatus().name());
        m.put("evidencePath", l.getEvidencePath());
        m.put("verifiedBy", l.getVerifiedBy());
        return m;
    }

    private Map<String, Object> membershipView(DealerAuctionMembership m) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", m.getId());
        out.put("dealerId", m.getDealerId());
        out.put("auctionHouseCode", m.getAuctionHouseCode());
        out.put("memberNo", m.getMemberNo());
        out.put("memberGrade", m.getMemberGrade());
        out.put("joinedOn", m.getJoinedOn() == null ? null : m.getJoinedOn().toString());
        out.put("expiresOn", m.getExpiresOn() == null ? null : m.getExpiresOn().toString());
        out.put("status", m.getStatus() == null ? null : m.getStatus().name());
        out.put("evidencePath", m.getEvidencePath());
        return out;
    }

    private Map<String, Object> complianceView(DealerComplianceCheck c) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", c.getId());
        out.put("checkType", c.getCheckType() == null ? null : c.getCheckType().name());
        out.put("checkedOn", c.getCheckedOn() == null ? null : c.getCheckedOn().toString());
        out.put("validUntil", c.getValidUntil() == null ? null : c.getValidUntil().toString());
        out.put("result", c.getResult() == null ? null : c.getResult().name());
        out.put("source", c.getSource() == null ? null : c.getSource().name());
        out.put("requestNo", c.getRequestNo());
        out.put("evidencePath", c.getEvidencePath());
        out.put("checkedBy", c.getCheckedBy());
        out.put("coSignedBy", c.getCoSignedBy());
        return out;
    }
}

package com.company.floorplan.domain.service;

import com.company.floorplan.domain.exception.ErrorCode;
import com.company.floorplan.domain.model.AdmissionDecision;
import com.company.floorplan.domain.model.AdmissionState;
import com.company.floorplan.domain.model.AuctionMembership;
import com.company.floorplan.domain.model.ComplianceCheck;
import com.company.floorplan.domain.model.ComplianceCheckType;
import com.company.floorplan.domain.model.DealerLicense;
import com.company.floorplan.domain.model.DealerQualification;

import java.time.LocalDate;
import java.util.List;

/**
 * 车商准入判定策略（R1.5；D03 §2.1 的「领域策略 {@code DealerAdmissionPolicy}（纯函数）」）。
 *
 * <p><b>纯函数</b>：入参是聚合与业务日，出参是 {@link AdmissionDecision}，
 * 不读库、不读配置、不碰 Spring。需要外部输入的量（临近到期窗口天数）
 * 以参数形式由应用层传入，本类不保存默认值。</p>
 *
 * <p><b>规则顺序照抄 D03 §2.1，任一条命中即拒</b>；每条的<b>谓词精度</b>取自
 * D06 §1.3 的门禁表（G-A1/G-A2/G-A3 把 D03 的口语规则写成可判定条件）：
 * <pre>
 *   1 license 缺失 或 已过期        -> BLOCKED_LICENSE    QUAL_001  (G-A1)
 *   2 四类合规核查未全部有效通过     -> BLOCKED_COMPLIANCE QUAL_003  (G-A3)
 *   3 申请指向的拍卖场无有效会员     -> BLOCKED_MEMBERSHIP QUAL_002  (G-A2)
 *   4 临近到期（窗口内）             -> EXPIRING，允许但产出预警工单
 *   5 其余                          -> ADMITTED
 * </pre>
 * 两条容易读错的细节，都在 D06 §1.3 有明文：
 * <ul>
 *   <li><b>{@code expires_on IS NULL 不算过期}</b>——G-A1 原式是
 *       {@code status='ACTIVE' 且（expires_on IS NULL 或 >= 今天）}。
 *       这不是宽松，而是业务事实：古物商許可<b>本身不设有效期</b>（更新靠届出，不靠换证），
 *       把"没有到期日"判成过期会一次性阻断全部正常车商。
 *       代价是 V46 的 {@code expires_on} 单列分不出"无期限"与"未采集"两种事实
 *       （该列注释已登记这个缺口），区分只能靠 {@code evidence_path} 与审计，属收口方议题。</li>
 *   <li><b>规则 2 是"四类都要有"，不是"有一类就行"</b>——G-A3：四类 check_type
 *       均有未过期 {@code result='PASS'}。四类取值来自 D04 §3.3 的 CHECK 集
 *       （反社／AML／本人确认／征信照会），D03 §2.1 的聚合注释只列了三类的口语叫法，
 *       差异已登记为文档冲突，本类按 D04＋D06 的四类执行。</li>
 * </ul>
 * </p>
 *
 * <p><b>错误码的取舍（文档冲突，已裁决并上报）</b>：D03 §2.1 规则 3 写的是
 * {@code QUAL_004}，但 D07 §8 的 {@code QUAL_} 逐码表只有 001–003，
 * 且 D00 §9 明写「只新增前缀，不复用他人号段」「新增码必须同步前端提示文案」。
 * 本类按 <b>D07 §8 为准</b>：规则 3 回 {@code QUAL_002}（其文案正是「拍卖场会员关系无效或已过期」，
 * 语义完全对得上，只是号位不同），不新造 {@code QUAL_004}。
 * D07 §7.1 每行的「主要错误码」（licenses→{@code QUAL_001}、
 * auction-memberships→{@code QUAL_002}、compliance-checks→{@code QUAL_003}）
 * 与本映射逐一吻合，是该裁决的证据；{@code QUAL_004} 保留给收口方在 D07 §8 补码后使用。</p>
 *
 * <p><b>没有白名单入参</b>：不存在 {@code relaxedDealers} 之类的绕过开关（Q21 教训，
 * AGENTS.md §8）。任何"这个车商特殊放行"的诉求都必须以资质事实的形式进来
 * （补許可、补核查、补会员），而不是以开关的形式。</p>
 */
public class DealerAdmissionPolicy {

    /**
     * 判定车商准入态。
     *
     * @param qualification      资质聚合（許可／会员／合规三段历史）
     * @param auctionHouseCode   申请指向的拍卖场代码；为 {@code null} 时跳过规则 3
     *                           （准入重算本身不绑定某一会场，只有支用申请才绑）
     * @param bizDate            业务日
     * @param expiringWindowDays 临近到期窗口天数，来自参数表；
     *                           {@code null} 表示未签字 ⟹ <b>不产出 EXPIRING</b>，
     *                           只按规则 1/2/3 与 5 给结论（不替业务发明 30 天）
     * @return 判定结论
     */
    public AdmissionDecision evaluate(DealerQualification qualification,
                                      String auctionHouseCode,
                                      LocalDate bizDate,
                                      Integer expiringWindowDays) {
        if (qualification == null) {
            // 连聚合都取不到（车商不存在或无资质行）＝没有任何许可证据，fail-closed。
            return AdmissionDecision.blocked(AdmissionState.BLOCKED_LICENSE, ErrorCode.QUAL_001,
                    "无车商资质记录，按许可缺失拒绝准入");
        }

        // ---- 规则 1：license 缺失或已过期 ----
        DealerLicense license = qualification.activeLicense();
        if (license == null) {
            return AdmissionDecision.blocked(AdmissionState.BLOCKED_LICENSE, ErrorCode.QUAL_001,
                    "无有效古物商許可（缺失或状态非 ACTIVE）");
        }
        if (license.getExpiresOn() != null && bizDate != null && bizDate.isAfter(license.getExpiresOn())) {
            // 状态列可能还是 ACTIVE（写入时机在人工确认后），但日期已经过期：两条口径取更严的一侧。
            return AdmissionDecision.blocked(AdmissionState.BLOCKED_LICENSE, ErrorCode.QUAL_001,
                    "古物商許可已过期：" + license.getExpiresOn());
        }
        // expires_on IS NULL 不阻断（G-A1 原式），此时规则 4 也无临近到期可算，见下方空值判断。

        // ---- 规则 2：四类合规核查必须"各自都有一条未过期的 PASS"（D06 §1.3 G-A3）----
        ComplianceCheckType missingType = firstMissingPassType(qualification.getComplianceChecks(), bizDate);
        if (missingType != null) {
            return AdmissionDecision.blocked(AdmissionState.BLOCKED_COMPLIANCE, ErrorCode.QUAL_003,
                    "缺少有效的「" + missingType.getLabel() + "」核查结论（缺失/UNKNOWN/已过有效期均计不通过）");
        }

        // ---- 规则 3：申请指向的拍卖场无有效会员 ----
        if (auctionHouseCode != null) {
            AuctionMembership membership = qualification.activeMembership(auctionHouseCode, bizDate);
            if (membership == null) {
                return AdmissionDecision.blocked(AdmissionState.BLOCKED_MEMBERSHIP, ErrorCode.QUAL_002,
                        "拍卖场 " + auctionHouseCode + " 无有效会员资格");
            }
        }

        // ---- 规则 4：临近到期（允许，但需预警）----
        // 两个前置条件缺一不可：窗口天数已签字（参数表给了值）＋许可确实有到期日。
        // 古物商許可无到期日时不产出 EXPIRING——"永不到期"与"即将到期"是两个结论，
        // 把前者写成后者会让预警工单表堆满永远不会发生的提醒。
        if (expiringWindowDays != null && expiringWindowDays > 0
                && license.getExpiresOn() != null && bizDate != null) {
            LocalDate warningFrom = license.getExpiresOn().minusDays(expiringWindowDays);
            if (!bizDate.isBefore(warningFrom)) {
                return AdmissionDecision.expiring("古物商許可将于 " + license.getExpiresOn() + " 到期，需产出资质到期预警工单");
            }
        }

        // ---- 规则 5：其余 ----
        return AdmissionDecision.admitted("许可、四类合规核查、会员三项均有效");
    }

    /**
     * 找出第一个"没有有效通过记录"的核查种类（D06 §1.3 G-A3 的"四类均有"）。
     *
     * <p>按 {@link ComplianceCheckType} 的声明顺序返回，使拒绝原因在多次调用间稳定，
     * 便于工单文案与审计比对。全部满足时返回 {@code null}。</p>
     *
     * @param checks  该车商的核查历史（可为空列表／null，视为全缺）
     * @param bizDate 业务日
     * @return 缺失的种类；无缺失返回 null
     */
    private ComplianceCheckType firstMissingPassType(List<ComplianceCheck> checks, LocalDate bizDate) {
        for (ComplianceCheckType required : ComplianceCheckType.values()) {
            boolean passed = false;
            if (checks != null) {
                for (ComplianceCheck check : checks) {
                    // 种类必须对得上，且该行本身是"PASS 且未过期"（UNKNOWN/过期都不算，P-7）。
                    if (check.getCheckType() == required && check.isValidPass(bizDate)) {
                        passed = true;
                        break;
                    }
                }
            }
            if (!passed) {
                return required;
            }
        }
        return null;
    }
}

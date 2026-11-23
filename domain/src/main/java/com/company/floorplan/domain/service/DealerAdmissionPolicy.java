package com.company.floorplan.domain.service;

import com.company.floorplan.domain.model.AdmissionState;
import com.company.floorplan.domain.model.ComplianceCheckType;
import com.company.floorplan.domain.model.DealerAuctionMembership;
import com.company.floorplan.domain.model.DealerComplianceCheck;
import com.company.floorplan.domain.model.DealerLicense;
import com.company.floorplan.domain.model.DealerQualification;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * 车商准入判定（USED R1 的闸门，纯函数、无 Spring、无库访问）。
 *
 * <p><b>规则顺序照 D03 §2.1 逐条实现，任一命中即拒，不存在绕过开关</b>：
 * 许可 → 合规 → 会员 → 到期预警 → 准入。顺序是有意义的：
 * 许可是「有没有资格经营」，合规是「是不是反社」，会员是「能不能进场买」。
 * 先判许可，是因为没有许可时后面两项的判定对象本身就不该存在。</p>
 *
 * <p><b>没有 {@code relaxedDealers} 之类的白名单入参</b>（D03 §2.1 原文点名，
 * 也是 AGENTS.md §8 的 Q21 教训）：Q21 当年就是被一个可配置白名单从硬阻断
 * 软放行的。要在这个方法上增加任何「例外」，必须先是显式业务裁决。</p>
 *
 * <p><b>结论对象而不是裸异常</b>：本方法返回 {@link AdmissionDecision}，
 * 由调用方决定是抛 {@code QUAL_*}（放款闸门）还是落库 {@code admission_state}（重算作业）。
 * 如果在 domain 里直接抛异常，重算作业就没法把「为什么被拒」写进审计。</p>
 */
public final class DealerAdmissionPolicy {

    private DealerAdmissionPolicy() {
    }

    /**
     * 推导准入态。
     *
     * @param qualification  该商当前的资质事实（许可 + 会员 + 合规）
     * @param bizDate        业务日（判定基准日，不用 {@code now()} 是为了让单测可重放）
     * @param licenseAlertDays 许可到期预警窗口（D06 §1.5 的 30 天，{@code [待定源 D-02]}）
     * @param membershipAlertDays 会员到期预警窗口，与许可共用同一参数亦可，分开是为了能单独收紧
     * @return 准入结论（含未满足项清单，供审计与前端展示）
     */
    public static AdmissionDecision evaluate(DealerQualification qualification, LocalDate bizDate,
                                            int licenseAlertDays, int membershipAlertDays) {
        List<String> unmet = new ArrayList<>();

        // ── 规则 1（G-A1）：古物商许可必须存在、ACTIVE、未过期 ────────────────────
        DealerLicense license = qualification.getActiveLicense();
        if (license == null || !license.isValidOn(bizDate)) {
            unmet.add(license == null
                    ? "G-A1 无古物商许可记录"
                    : "G-A1 许可无效：status=" + license.getStatus() + " expires_on=" + license.getExpiresOn());
            return new AdmissionDecision(AdmissionState.BLOCKED_LICENSE, null, unmet, null);
        }

        // ── 规则 2（G-A3）：四类合规核查都要有未过期的 PASS，UNKNOWN 按不通过 ──────
        Set<ComplianceCheckType> passed = EnumSet.noneOf(ComplianceCheckType.class);
        for (DealerComplianceCheck check : qualification.getComplianceChecks()) {
            if (check.isPassingOn(bizDate) && check.getCheckType() != null) {
                passed.add(check.getCheckType());
            }
        }
        List<String> missingTypes = new ArrayList<>();
        for (ComplianceCheckType type : ComplianceCheckType.required()) {
            if (!passed.contains(type)) {
                // 逐项列出缺哪一类：只说「合规不通过」的提示无法指导内勤补什么
                missingTypes.add(type.name());
            }
        }
        if (!missingTypes.isEmpty()) {
            unmet.add("G-A3 合规未全部通过，缺: " + String.join(",", missingTypes));
            return new AdmissionDecision(AdmissionState.BLOCKED_COMPLIANCE, missingTypes, unmet, null);
        }

        // ── 规则 3（G-A2）：至少存在一条有效会员关系 ─────────────────────────────
        List<DealerAuctionMembership> validMemberships = new ArrayList<>();
        for (DealerAuctionMembership m : qualification.getMemberships()) {
            if (m.isValidOn(bizDate)) {
                validMemberships.add(m);
            }
        }
        if (validMemberships.isEmpty()) {
            unmet.add("G-A2 无有效拍卖场会员关系（status=ACTIVE 且未过期的记录为 0 条）");
            return new AdmissionDecision(AdmissionState.BLOCKED_MEMBERSHIP, null, unmet, null);
        }

        // ── 规则 4：允许，但在预警窗口内 → EXPIRING，并带出最近的一个到期天数 ──────
        Long nearest = license.daysToExpiry(bizDate);
        for (DealerAuctionMembership m : validMemberships) {
            Long d = m.daysToExpiry(bizDate);
            if (d != null && (nearest == null || d < nearest)) {
                nearest = d;
            }
        }
        boolean licenseExpiring = nearest != null && nearest >= 0 && nearest <= licenseAlertDays;
        boolean membershipExpiring = false;
        for (DealerAuctionMembership m : validMemberships) {
            Long d = m.daysToExpiry(bizDate);
            if (d != null && d >= 0 && d <= membershipAlertDays) {
                membershipExpiring = true;
                if (nearest == null || d < nearest) {
                    nearest = d;
                }
            }
        }
        if (licenseExpiring || membershipExpiring) {
            return new AdmissionDecision(AdmissionState.EXPIRING, null, List.of(), nearest);
        }

        // ── 规则 5：其余情形准入 ───────────────────────────────────────────────
        return new AdmissionDecision(AdmissionState.ADMITTED, null, List.of(), nearest);
    }

    /**
     * 放款闸门上的会员判定（G-A2 的「指定拍场」版本）。
     *
     * <p>准入态只回答「这个车商有没有任一有效会员」，放款还要回答
     * 「这笔落札所属的那个场，车商有没有会员资格」——钱是要打给那个场的，
     * 没有该场会员就无法解释付款的正当性。两者分开，才不会出现
     * 「在 TAA 有会员却在 USS 落札」被放行。</p>
     *
     * @param auctionHouseCode 申请/落札指向的拍场代码；{@code null} 或空白表示本笔不指向拍场
     *                         （例如经销商自有库存转融资），此时返回 {@code null} 表示「本判定不适用」
     * @return 命中的会员关系；{@code null} 表示「该场无有效会员」，调用方按 QUAL_002 拒
     */
    public static DealerAuctionMembership findMembershipFor(DealerQualification qualification,
                                                            String auctionHouseCode, LocalDate bizDate) {
        if (auctionHouseCode == null || auctionHouseCode.isBlank()) {
            return null;
        }
        for (DealerAuctionMembership m : qualification.getMemberships()) {
            if (m.isFor(auctionHouseCode) && m.isValidOn(bizDate)) {
                return m;
            }
        }
        return null;
    }

    /**
     * 准入结论。
     *
     * @param state         准入态
     * @param missingTypes  合规缺失的类型（仅 BLOCKED_COMPLIANCE 非空）
     * @param unmetReasons  人类可读的未满足项（写审计与前端提示）
     * @param daysToExpiry  最近到期天数（许可或有效会员取最小）；無期限时为 null
     */
    public record AdmissionDecision(AdmissionState state,
                                    List<String> missingTypes,
                                    List<String> unmetReasons,
                                    Long daysToExpiry) {

        public AdmissionDecision {
            // 清单不可变：结论一旦产出就要同时进审计与响应体，被下游改动会造成同一次判定两种说法
            missingTypes = missingTypes == null ? List.of() : List.copyOf(missingTypes);
            unmetReasons = unmetReasons == null ? List.of() : List.copyOf(unmetReasons);
        }

        /** 是否允许进入后续放款判定（EXPIRING 允许，但调用方要另发预警）。 */
        public boolean admitted() {
            return state != null && !state.blocksAdvance();
        }

        /** 一句话理由，供 message 与审计 detail。 */
        public String reason() {
            return unmetReasons.isEmpty() ? state.getLabel() : String.join("；", unmetReasons);
        }
    }
}

package com.company.floorplan.web.dto;

import java.time.LocalDate;

/**
 * USED 域（二手车库存垫资）接口请求体契约。
 *
 * <p><b>为什么不塞进 {@link ApiRequests}</b>：那个类是 LEGACY 单体的共用契约文件，
 * 多个业务域在同一段代码里追加，改一处要陪所有人重读。USED 域的入参形状
 * 由 D07 §7 独立定义（含 {@code [待定源]} 标记与二人签认字段），
 * 分文件能让「哪些字段是 USED 带来的」在 review 时一眼看出来。</p>
 *
 * <p><b>约定沿用 LEGACY</b>（AGENTS.md §10）：金额一律整数日元，日期 {@code yyyy-MM-dd}；
 * 操作人不从请求体读——审计操作人只认 {@code AuditActor}（令牌身份优先），
 * 请求体里出现 {@code operator} 一律忽略并记入响应的 {@code details.ignored_fields[]}。</p>
 */
public final class UsedRequests {

    private UsedRequests() {
    }

    /** R1 古物商许可登记（D07 §7.1 licenses 行）。 */
    public static class LicenseRequest {
        /** 许可类型；省略时按 FURIGUSHO 处理（当前取值域只有这一个）。 */
        public String licenseType;
        /** 許可番号（必填）。 */
        public String licenseNo;
        /** 管辖公安委員会（必填）。 */
        public String competentAuthority;
        /** 交付日（必填）。 */
        public LocalDate issuedOn;
        /** 有效期末日；{@code null} = 無期限。 */
        public LocalDate expiresOn;
        /** 証書扫描件路径。 */
        public String evidencePath;
        /** 扫描件 SHA-256。 */
        public String evidenceDigest;
    }

    /** R1 拍卖场会员关系维护（D07 §7.1 auction-memberships 行）。 */
    public static class MembershipRequest {
        /** 拍卖场代码（必填）。 */
        public String auctionHouseCode;
        /** 会員番号（必填）。 */
        public String memberNo;
        /** 会员等级。 */
        public String memberGrade;
        /** 入会日。 */
        public LocalDate joinedOn;
        /** 有效期末日；{@code null} = 無期限。 */
        public LocalDate expiresOn;
        /** 状态：ACTIVE/SUSPENDED/EXPIRED/TERMINATED；省略按 ACTIVE。 */
        public String status;
        /** 在籍証明/会員証路径。 */
        public String evidencePath;
    }

    /** R1 合规核查结论录入（D07 §7.1 compliance-checks 行 / §3.8 I-11 同字段）。 */
    public static class ComplianceCheckRequest {
        /** 核查类型：ANTI_SOCIAL/AML/IDENTITY/CREDIT_BUREAU（必填）。 */
        public String checkType;
        /** 核查日；省略取当天（按服务器业务日，不用 UTC 混算）。 */
        public LocalDate checkedOn;
        /** 结论有效期末日；省略时按参数 {@code compliance-valid-days} 推导，参数缺失则拒绝。 */
        public LocalDate validUntil;
        /** 结论：PASS/FAIL/UNKNOWN（必填）。 */
        public String result;
        /** 来源：MANUAL/PARTNER_API/CREDIT_BUREAU；省略按 MANUAL（最严的一侧）。 */
        public String source;
        /** 对外请求编号；有值时参与 I-11 幂等（库内 uk_dchk_request_no）。 */
        public String requestNo;
        /** 报告/回执路径；source=MANUAL 时必填。 */
        public String evidencePath;
        /** 备注。 */
        public String note;
        /** 第二签认人；source=MANUAL 时必填且必须不同于录入人。 */
        public String coSignedBy;
    }

    /** R1 双维评分录入（D07 §7.1 scores 行；二人签认）。 */
    public static class DealerScoreRequest {
        /** 经营资质分 0–100；与下一项至少给一个。 */
        public Integer operatingScore;
        /** 在库车辆质量分 0–100。 */
        public Integer stockQualityScore;
        /** 第二签认人（必填，且不能是自己）。 */
        public String coSignedBy;
        /** 评分批次键 {@code DSCR:{dealerId}:{ts}} 的 ts 部分；同键重推不重复写。 */
        public String runTs;
    }
}

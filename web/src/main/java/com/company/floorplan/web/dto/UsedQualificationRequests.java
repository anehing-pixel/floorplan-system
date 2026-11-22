package com.company.floorplan.web.dto;

/**
 * USED 域车商资质与准入接口的请求体契约（D07 §7.1 的 R1 那八行）。
 *
 * <p><b>为什么不塞进既有的 {@link ApiRequests}</b>：那份文件是全仓共用的请求体清单，
 * 本次并行开发里 W-U2／W-U3／W-U4 也要往里加内部类。同一个大文件被多个包同时插行，
 * 合并时只会得到一片冲突，而且谁都不敢删别人的行——按包拆一份请求体文件，
 * 边界与号段一致，代价只是多一个文件。既有 {@code ApiRequests} 一个字节没改。</p>
 *
 * <p><b>约定沿用 {@link ApiRequests} 的写法</b>：public 字段的静态内部类（简化 JSON 绑定）；
 * 日期一律 {@code yyyy-MM-dd} 字符串，由控制器显式解析成 {@code LocalDate}——
 * 这是仓内既有做法（{@code InsuranceController}／{@code JobController} 同此），
 * 好处是格式错误在入口就变成 {@code VALID_001}，不依赖 Jackson 的 JSR-310 绑定配置。</p>
 *
 * <p><b>请求体里没有操作人字段</b>：审计主体只能来自
 * {@link com.company.floorplan.web.config.AuditActor#resolve} 解析出的令牌身份，
 * 由客户端自报＝可伪造（{@code AuditActor} 类注释记的正是这个坑：
 * {@code audit_events.actor} 里存过冒烟脚本传的假值 {@code "1"}）。</p>
 *
 * <p><b>{@code confirmedBy}（双维评级的第二人）不在这里</b>：
 * D07 §7.1 只给了 {@code POST /api/dealers/{id}/scores}（录入单维分），
 * 没有"确认评级"这一行 ⟹ 确认动作本轮无 HTTP 入口，服务方法
 * {@code DealerRatingApplicationService.confirmScores} 已实现但待端点定源
 * （已登记为缺口，不自派路径）。</p>
 */
public final class UsedQualificationRequests {

    private UsedQualificationRequests() {
    }

    /**
     * 许可新增／订正／换证（POST /api/dealers/{id}/licenses）。
     *
     * <p>三种情形由服务端按 (车商, 类型, 番号) 与自然键指纹判别，请求体不需要声明是哪一种：
     * 同键同指纹＝重放，同键异指纹＝订正，同类型新番号＝换证（旧证自动置 SUPERSEDED）。</p>
     */
    public static class LicenseUpsertRequest {
        /** 许可类型（本期只有 {@code FURIGUSHO} 古物商许可）。 */
        public String licenseType;
        /** 许可番号。 */
        public String licenseNo;
        /** 管辖公安委员会。 */
        public String competentAuthority;
        /** 发日 {@code yyyy-MM-dd}。 */
        public String issuedOn;
        /** 到期日 {@code yyyy-MM-dd}；空＝该许可无有效期。 */
        public String expiresOn;
        /** 扫描件本地路径（U-R12：一期不接对象存储）。 */
        public String evidencePath;
        /** 扫描件 SHA-256 摘要。 */
        public String evidenceDigest;
        /** 人工核查人（G-A1 的复核主体，可空）。 */
        public String verifiedBy;
        /** 核查时点（ISO-8601，如 {@code 2026-11-22T09:30:00Z}）。 */
        public String verifiedAt;
    }

    /** 拍卖场会员关系新增／订正（POST /api/dealers/{id}/auction-memberships）。 */
    public static class AuctionMembershipUpsertRequest {
        /** 拍卖场代码（如 TAA／USS／JU）。 */
        public String auctionHouseCode;
        /** 会员号。 */
        public String memberNo;
        /** 会员等级（自由文本，取值集未定源，服务端不校验）。 */
        public String memberGrade;
        /** 入会日 {@code yyyy-MM-dd}。 */
        public String joinedOn;
        /** 到期日 {@code yyyy-MM-dd}；空＝未采集（不等于长期有效）。 */
        public String expiresOn;
        /** 凭证路径。 */
        public String evidencePath;
    }

    /** 合规核查结论录入（POST /api/dealers/{id}/compliance-checks）。 */
    public static class ComplianceCheckRequest {
        /** 核查种类。 */
        public String checkType;
        /** 核查日 {@code yyyy-MM-dd}。 */
        public String checkedOn;
        /** 结论有效期止 {@code yyyy-MM-dd}；空＝服务端按参数表推导，参数未签字时留空。 */
        public String validUntil;
        /** 结论（{@code PASS}／{@code FAIL}／{@code UNKNOWN}；后两者按 P-7 等同不通过）。 */
        public String result;
        /** 结论来源（人工／代办／系统）；空＝按 {@code MANUAL} 记（服务端默认，不是推导）。 */
        public String source;
        /** 外部请求号＝幂等键段（可空；给了就必须同结论，否则 409）。 */
        public String requestNo;
        /** 证据路径。 */
        public String evidencePath;
        /** 备注。 */
        public String note;
        /** 核查执行人（可空，默认取登录身份）。 */
        public String checkedBy;
    }

    /** 双维评级单维录入（POST /api/dealers/{id}/scores）。 */
    public static class ScoreDimensionRequest {
        /** 评分维度（{@code OPERATING} 经营资质分／{@code STOCK_QUALITY} 在库车辆质量分）。 */
        public String dimension;
        /** 分值。区间未签字（U-11），服务端不校验上下限，只要求非空。 */
        public Integer score;
        /**
         * 评审年度。<b>必填</b>——服务端不按今天反推年度：
         * 年审周期起止属 [待定源]，用 {@code LocalDate.now().getYear()} 兜一个年度
         * 等于替业务定了"评审年度＝自然年"（服务里 {@code REVIEW_YEAR_REQUIRED} 就是拦这个）。
         */
        public Integer reviewYear;
        /** 依据材料路径。 */
        public String evidencePath;
        /** 备注。 */
        public String note;
        /**
         * 幂等键（也可走 {@code Idempotency-Key} 请求头）。
         * 可空——空时服务端<b>不合成键</b>（{@code DSCR:{dealerId}:{ts}} 那种服务端自造的键
         * 每次都不同，起不到去重作用），只是这一次录入没有重放保护。
         */
        public String idempotencyKey;
    }

    /** 准入态重算（POST /api/dealers/{id}/admission/recalculate）。 */
    public static class AdmissionRecalculateRequest {
        /** 重算原因（作业触发／人工触发，写进审计便于事后回看；空串按未填处理）。 */
        public String reason;
    }
}

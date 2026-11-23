package com.company.floorplan.web.dto;

import java.math.BigDecimal;
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
 * 操作人不从请求体读——审计操作人只认 {@code AuditActor}（令牌身份优先）。
 * 本类的 DTO <b>一律不声明 {@code operator} 字段</b>，所以请求体里出现的 {@code operator}
 * 由 Jackson 直接丢弃（不是「收下后忽略」）；库里落的人永远是令牌那个人。</p>
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

    // ---- USED R2 车辆主数据 / 车况票 / 車検証（D06 §2.1、D07 §7.2）----

    /**
     * USED 建账（UC-R2-1）。
     *
     * <p>字段全部用包装类型／字符串：缺哪个字段要能区分「没传」和「传了 0/false」，
     * 基本类型会把前者静默变成后者，而 {@code vehicleType} 缺失正是 D06 §2 验收点要拦的那一类。
     * 枚举词（{@code mileageSource}、{@code repairHistory}、{@code saleChannel}）保留字符串形态，
     * 由控制器解析成 400，不让 Jackson 抛它自己的反序列化错。</p>
     */
    public static class UsedVehicleRegisterRequest {
        /** 車台番号（必填，17 位；幂等键 {@code VEH:{vin}}）。 */
        public String vin;
        /** 所属车商（必填）。 */
        public Long dealerId;
        /** 车辆类型分流键；本端点只接受 USED。 */
        public String vehicleType;
        /** 购入渠道（必填）：AUCTION / TRADE_IN / WHOLESALE。 */
        public String purchaseChannel;
        public String brand;
        public String model;
        /** 年式。 */
        public Integer modelYear;
        public String color;
        /** 发票/落札价（JPY 整数）；<b>不是</b>放款基准（D08 §5.4）。 */
        public Long invoiceAmount;
        public String supplierName;
        /** 登録番号。 */
        public String registrationPlate;
        /** 初度登録年月。 */
        public LocalDate firstRegisteredOn;
        /** 車検満了日。 */
        public LocalDate inspectionExpiryDate;
        /** 車検残存月数：录入值，服务端不推导（D06 §2.1 明写）。 */
        public Integer inspectionRemainingMonths;
        /** 里程读数。 */
        public Integer mileageKm;
        /** 里程来源：AUCTION_REPORT / WMS_INBOUND / OWN_AUDIT / DEALER_DECLARED / UNKNOWN。 */
        public String mileageSource;
        /** 里程采集时点。 */
        public LocalDate mileageCapturedOn;
        /** 修復歴：NONE / MINOR / MAJOR / UNKNOWN。 */
        public String repairHistory;
        public String repairHistoryDesc;
        public Boolean accidentHistoryFlag;
        /** 拍卖評価点（NUMERIC(3,1)）。 */
        public BigDecimal auctionGrade;
        /** 落札引用；R4 未建，可空。 */
        public Long auctionLotId;
        /** 销售渠道：RETAIL / WHOLESALE_RESELL / AUCTION_RELIST。 */
        public String saleChannel;
        /** 监管仓代码（账实比对的分片键）。 */
        public String warehouseCustodianCode;
        public String binLocation;
    }

    /** 车况票登记（UC-R2-2）；票可以先于建账到达，故本端点不要求 VIN 已在账。 */
    public static class ConditionReportRequest {
        /** 外部票号；非空即幂等键 {@code CRR:{reportNo}}。 */
        public String reportNo;
        /** 票型：AUCTION_INSPECTION / WMS_INBOUND_RECHECK / OWN_AUDIT（必填）。 */
        public String reportType;
        public LocalDate inspectedOn;
        public String inspector;
        /** フレーム状況原文。 */
        public String structureCondition;
        /** 票面修復歴（参与取严，不会把已知的 NONE 改写成 UNKNOWN）。 */
        public String repairHistory;
        public Boolean accidentHistoryFlag;
        public Boolean modificationFlag;
        public String interiorCondition;
        public String exteriorCondition;
        /** 票面/实车里程观测值：按来源可信序决定是否覆盖主数据。 */
        public Integer odometerKm;
        public LocalDate inspectionExpiryDate;
        public Boolean recycleTicketFlag;
        public LocalDate compulsoryInsuranceExpiry;
        public String automobileTaxStatus;
        /** 照片路径（JSON 数组文本）。 */
        public String photoPaths;
        public String attachmentPath;
        public String attachmentDigest;
    }

    /** 車検証收证登记（UC-R2-5 前半）。 */
    public static class CertificateRequest {
        /** 証書番号（必填；一车一证与跨车重号的核验对象）。 */
        public String certificateNo;
        /** 报文 VIN：给了就必须与路径一致（不一致 CERT_003）。 */
        public String vin;
        public String registrationPlate;
        public String ownerName;
        public String ownerAddress;
        public String userName;
        public String userAddress;
        public LocalDate firstRegisteredOn;
        /** 車検満了日（库内 NOT NULL）。 */
        public LocalDate inspectionExpiryDate;
        /** 発行年月日（库内 NOT NULL，且不晚于車検満了日）。 */
        public LocalDate issueDate;
        /** 担保形态（V52 八值）。 */
        public String securityType;
        public Boolean mortgageFlag;
        /** 瑕疵聚合结论；<b>省略＝未核查，落 UNKNOWN，不落 NONE</b>。 */
        public String encumbranceStatus;
        public String holderCustodianCode;
        public String holderType;
        public String custodyLocation;
        public String evidencePath;
        public String evidenceDigest;
        /** 核查来源：MANUAL / REGISTRY_AGENT / REGISTRY_API。 */
        public String checkSource;
        /** 事由。 */
        public String reason;
    }

    /**
     * 車検証换发（UC-R2-5 后半）。
     *
     * <p>比收证多一个 {@code coSignedBy}：换证会改掉权属检索键，D06 §2.3 要求二人，
     * 单人可改＝单人可把瑕疵车换到一张干净的证上。第二人不能是操作人自己（AUTH_003）。</p>
     */
    public static class CertificateRebindRequest extends CertificateRequest {
        /** 第二签认人（必填，且不同于当前操作人）。 */
        public String coSignedBy;
    }

    // ---- USED R3 多源审慎估值 / LTV 政策（D08 §3–§4、D07 §7.2）----

    /**
     * 估值录入（UC-R3-1，{@code POST /api/valuations}）。
     *
     * <p><b>没有 {@code prudentRule} 字段，这是有意的</b>：审慎算法读自配置键
     * {@code valuation.default-prudent-rule}（D08 §3.2）。让调用方挑算法等于
     * 让每一笔业务自己决定自己的放款基准有多高，而「WEIGHTED 还是 MIN」的差
     * 通常就是一两百万日元的可借额。</p>
     *
     * <p><b>金额用包装类型</b>：{@code value} 缺失（{@code null}）与显式 0 是两种事实
     * （V55 头注），基本类型会把前者静默变成后者，而 {@code MIN_OF_SOURCES} 拿到一个 0
     * 就把这台车的基准判成零。</p>
     */
    public static class ValuationRequest {
        /** 車台番号（必填）。 */
        public String vin;
        /** 用途（必填）：ADVANCE_BASE / PERIODIC_RECHECK / DISPOSAL_PRICING / MONITORING。 */
        public String purpose;
        /** 输入源清单（必填，至少一条）；禁止 {@code INTERNAL_DEGRADED}（D07 §7.2 / U-12）。 */
        public java.util.List<SourceLine> sources;
        /** 估值日；省略取当天。 */
        public LocalDate valuationDate;
        /** 有效截止日；省略时按参数 {@code valuation.valid-days} 推导，参数缺失则拒绝。 */
        public LocalDate validUntil;
        /** 第二签认人；{@code sources} 含 {@code MANUAL_OVERRIDE} 时必填且必须不同于操作人。 */
        public String coSignedBy;
        /** 溯源落札批次行（R4 未建，可空）。 */
        public Long auctionLotId;
        /** 溯源行情快照（V56）。 */
        public Long marketSnapshotId;
        public String appraisalProvider;
        public String appraisalNo;
        public String reportPath;
        public String reportDigest;
        /**
         * 业务键 {@code VAL-{vin}-{yyyyMMdd}-{seq}}；省略则由服务端推导。
         * <p>调用方自带这个键就得到幂等重放：同键重推返回已有结论、不写第二行。</p>
         */
        public String valuationKey;

        /** 一条输入源（＝库内 {@code valuation_inputs} 一行）。 */
        public static class SourceLine {
            /** 来源：AUCTION_WON_PRICE / THIRD_PARTY_APPRAISAL / MARKET_MATRIX / MANUAL_OVERRIDE。 */
            public String source;
            /** 该源报价（整数日元，必填；0 要显式写 0）。 */
            public Long value;
            /** 源侧引用号（落札票号 / 查定书号 / 行情批次号）。 */
            public String ref;
            /** 机构或供应商；除人工覆盖外必填（{@code uk_val_input} 的组成键）。 */
            public String provider;
            /** 报价口径日；省略取估值日。 */
            public LocalDate asOf;
            /** 权重，仅 WEIGHTED 用；MIN 规则留空。 */
            public BigDecimal weight;
            /** 同源多行的次序。 */
            public Integer rank;
        }
    }

    /**
     * 估值人工覆盖（UC-R3-3）。
     *
     * <p>{@code reason} 会同时进 {@code valuation_inputs.ref} 与 {@code audit_events}，
     * 所以长度受 ref 列宽约束（128）；下限 10 字符是 D08 §3.4 的要求——
     * 「重新看一下」这种理由等于没写。</p>
     */
    public static class ValuationOverrideRequest {
        /** 人工值（整数日元，必填；上限＝可用自动源最大值 × 参数倍率）。 */
        public Long manualValue;
        /** 覆盖理由（必填，10〜118 字符）。 */
        public String reason;
        /** 第二签认人（必填，且不同于操作人）。 */
        public String coSignedBy;
    }

    /** 估值人工置失效（UC-R3-4）。 */
    public static class ValuationExpireRequest {
        /** 失效理由（必填）：查定机构撤回报告、行情源被判定污染等。 */
        public String reason;
    }

    /**
     * LTV 政策起草（{@code POST /api/ltv-policies}）。
     *
     * <p><b>不含带集合</b>：D07 §7.2 把「建一版」与「配带」分成两个端点（{@code PUT /bands}），
     * 合在一起写会让审计看不出哪一次是真正的定案。版本同理不接受传入，
     * 服务端按 {@code max(version)+1} 推导。</p>
     */
    public static class LtvPolicyRequest {
        /** 政策码（必填，如 USED-MAIN；同码多版）。 */
        public String policyCode;
        /** 政策名（必填）。 */
        public String policyName;
        /** 生效起日（必填）。 */
        public LocalDate effectiveFrom;
        /** 生效止日；省略＝无限期。 */
        public LocalDate effectiveTo;
        /** 兜底成数（可空）；给了就必须严格小于所有带的 {@code max_ratio}（L3）。 */
        public BigDecimal defaultRatio;
    }

    /**
     * 一条分档带（{@code PUT /api/ltv-policies/{id}/bands} 的元素，全量替换）。
     *
     * <p><b>维度留空＝该维不设限</b>（V57 列注释），不是「该维为 0」；
     * 但 {@code maxRatio} 留空是错误而非不设限——没有上限的档就是无上限放款，
     * 所以它必填且 {@code 0 < r <= 1}（L4）。</p>
     */
    public static class LtvBandRequest {
        /** 命中顺序，小者优先（必填，&gt; 0，同政策内不得重复）。 */
        public Integer priority;
        public Integer ageBandMinMonths;
        /** 車龄带上限（不含）。 */
        public Integer ageBandMaxMonths;
        public Integer mileageBandMinKm;
        public Integer mileageBandMaxKm;
        /** 修復歴集合（CSV，如 {@code NONE,MINOR}）。 */
        public String repairHistoryIn;
        public BigDecimal gradeMin;
        public BigDecimal gradeMax;
        /** 車検残存下限（月）；V57 只有下界一列。 */
        public Integer inspectionRemainingMin;
        /** 车型热度单值（V57 是单值列，非集合）。 */
        public String modelHeat;
        /** 车商等级集合（CSV）。 */
        public String dealerGradeIn;
        /** 该档上限成数（必填）。 */
        public BigDecimal maxRatio;
        /** 利率加点（基点）。 */
        public Integer rateAddonBps;
        /** 该档突破预警线（0〜100 的百分比数值）。 */
        public BigDecimal breachThreshold;
        public String note;
    }
}

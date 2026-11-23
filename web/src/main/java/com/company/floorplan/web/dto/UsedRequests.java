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
}

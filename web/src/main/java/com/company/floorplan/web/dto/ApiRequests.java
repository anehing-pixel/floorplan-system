package com.company.floorplan.web.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * 接口请求体契约（对应《库存融资核心系统-接口文档.md》第 4 节）。
 *
 * <p><b>约定</b>：
 * <ul>
 *   <li>金额一律为<b>整数日元</b>（long），禁止小数与浮点；</li>
 *   <li>比率（advanceRatio）为浮点/定点，服务端按定点处理；</li>
 *   <li>日期格式为 {@code yyyy-MM-dd}（JST）；</li>
 *   <li>操作人通过请求头 {@code X-Operator-Id} 传递（审计留痕），
 *       幂等键通过 {@code Idempotency-Key} 或请求体中的 idempotencyKey 传递。</li>
 * </ul>
 *
 * <p>这里使用 public 字段的静态内部类以简化 JSON 绑定；
 * 生产环境如需更严格的校验可改为 private + getter 并配合 Bean Validation。</p>
 */
public final class ApiRequests {

    private ApiRequests() {
    }

    public static class CreateDealerRequest {
        public String dealerCode;
        public String name;
        public String riskGrade;
        public Integer businessYears;
        /** 联系人（授信准入登记的接口人）。 */
        public String contactPerson;
        /** 联系电话。 */
        public String contactPhone;
        /** 营业地址。 */
        public String address;
        /** 备注。 */
        public String remark;
    }

    /**
     * 编辑经销商档案（PUT /api/dealers/{id}）。
     *
     * <p><b>字段语义</b>：{@code null} = 该字段不修改（PATCH 语义）；
     * 空字符串 = 清空该字段。编号与名称为空字符串时按"不修改"处理。</p>
     */
    public static class UpdateDealerRequest {
        public String dealerCode;
        public String name;
        public String riskGrade;
        public Integer businessYears;
        /** ACTIVE / SUSPENDED / TERMINATED；为空表示不改状态。 */
        public String status;
        public String contactPerson;
        public String contactPhone;
        public String address;
        public String remark;
    }

    public static class CreateCreditLineRequest {
        public Long dealerId;
        public Long approvedLimit;
    }

    public static class FreezeRequest {
        public Long amount;
        public String reason;
    }

    public static class VehicleItem {
        public String vin;
        public String brand;
        public String model;
        public Integer modelYear;
        public Integer mileage;
        public String color;
        public String vehicleType;
        public Long invoiceAmount;
        public BigDecimal advanceRatio;
    }

    public static class BatchIngestRequest {
        public Long dealerId;
        public Long creditLineId;
        public String purchaseChannel;
        public String supplierName;
        public String idempotencyKey;
        public List<VehicleItem> vehicles;
    }

    public static class AdvanceRequest {
        public Long creditLineId;
        public String vin;
        public String idempotencyKey;
    }

    public static class SoldRequest {
        /** yyyy-MM-dd，为空则取当日 */
        public String soldAt;
    }

    public static class SecurityRequest {
        /** RETENTION / ASSIGNMENT / OTHER / NONE */
        public String securityType;
        public String regNo;
        public String ownerName;
    }

    public static class RepaymentRequest {
        public String vin;
        public Long settlementAmount;
        public String idempotencyKey;
    }

    public static class AuditRequest {
        public Long dealerId;
        /** yyyy-MM-dd */
        public String auditDate;
        public List<String> scannedVins;
    }

    /**
     * 告警处置请求。
     *
     * <p>{@code status} 只接受 HANDLED / CLOSED（仓储层已做白名单约束）。
     * {@code note} 是处置说明——事后复核时，"为什么这么处置"比"处置了没有"
     * 更重要，空说明等于没有结论。</p>
     */
    /* ---- 担保物台账（V10） ---- */

    /**
     * 担保登记批次请求。
     *
     * <p><b>对应业务流程</b>：审核人员与经销商签<b>一笔</b>让渡担保合同 →
     * 向东京法务局动产登记课做<b>一次</b>登记 → 取得<b>一个</b>登记编号 →
     * 把编号与合同文件管理到<b>这一批车</b>上。</p>
     *
     * <p>{@code vins} 是本批次覆盖的车架号清单；
     * {@code registrationNo} 是法务省登记编号（字符串，库内唯一）；
     * {@code registeredAt} 必填——失效日按登记日 + 5 年推算（法定 5 年、不可续展）；
     * {@code contractAttachmentId} 是担保合同附件（S3）——担保权成立的<b>实体依据</b>，
     * 与登记编号这个<b>公示依据</b>缺一不可。</p>
     */
    public static class CollateralBatchRegistrationRequest {
        /** 本批次覆盖的车架号清单 */
        public java.util.List<String> vins;
        /** 法务省登记编号（字符串） */
        public String registrationNo;
        /** 担保方式：ASSIGNMENT / RETENTION / MANUFACTURER_REPURCHASE / OTHER / NONE */
        public String registrationType;
        /** 对抗要件方式：REGISTRATION / POSSESSION_TRANSFER / CONSTRUCTIVE_TRANSFER / RETENTION / NONE */
        public String perfectionMethod;
        /** 登记机关（如"東京法務局 動産登記課"） */
        public String registryOffice;
        /** 登记日 ISO 格式（yyyy-MM-ddTHH:mm:ss） */
        public String registeredAt;
        /** 失效日（可空，默认登记日 + 5 年） */
        public String expiresAt;
        /** 担保合同附件 ID（S3 存证）——担保权成立的实体依据 */
        public Long contractAttachmentId;
        /** 合同编号 */
        public String contractNo;
        /** 合同签署日 ISO 格式 */
        public String contractSignedAt;
        /** 登记事项证明书附件 ID（謄本）——担保权成立的公示依据 */
        public Long certificateAttachmentId;
        /** 登记事项证明书编号 */
        public String certificateNo;
    }

    /** 抹消待办请求。 */
    public static class CancellationRequest {
        /** 申请说明 */
        public String note;
    }

    /**
     * 抹消完成请求。
     *
     * <p>{@code certificateAttachmentId} 是抹消凭证附件——抹消动作的凭证，
     * 缺失同样会被证据链检查报为缺口。</p>
     */
    public static class CancellationCompleteRequest {
        /** 抹消登记编号 */
        public String cancellationNo;
        /** 抹消凭证附件 ID */
        public Long certificateAttachmentId;
    }

    /** 指定回款账户请求（只传引用码，不传明文账号）。 */
    public static class RequiredAccountRequest {
        /** 经销商结算账户引用码 */
        public String accountRef;
    }

    public static class AlertHandleRequest {
        /** HANDLED（已处理）/ CLOSED（已关闭） */
        public String status;
        /** 处置说明 */
        public String note;
    }

    public static class HolidayRequest {
        /** yyyy-MM-dd */
        public String bizDate;
        public Boolean isBusinessDay;
        public String note;
    }

    // ===================== P0/P1 流程缺口对照 相关请求体 =====================

    /** 库龄递减还款（P0-①）。 */
    public static class CurtailmentRequest {
        public String vin;
        public String idempotencyKey;
    }

    /** 违约处置开单（P0-②）。 */
    public static class DisposalOpenRequest {
        public String vin;
        public String reason;
    }

    /** 违约处置结案（P0-②）：变现所得。 */
    public static class DisposalCloseRequest {
        /** 实际变现所得（整数日元）。 */
        public Long proceeds;
    }

    /** 车辆保险投保（P1）。 */
    public static class InsuranceBindRequest {
        public String vin;
        public String policyNo;
        public String insurer;
        public Long insuredAmount;
        /** yyyy-MM-dd */
        public String startDate;
        /** yyyy-MM-dd */
        public String endDate;
    }

    /** 车辆保险理赔登记（P1）。 */
    public static class InsuranceClaimRequest {
        public String policyNo;
        public Long claimedAmount;
    }

    /** 厂家回购申请（P1）。 */
    public static class RepurchaseRequestDto {
        public String vin;
        public String reason;
    }

    /** 厂家回购兑现（P1）：冲抵垫款本金。 */
    public static class RepurchaseFulfillRequest {
        public String requestNo;
        public Long amount;
    }

    /** 结清证明出具（P1）。 */
    public static class PayoffRequest {
        public String vin;
    }

    /** 展期续贷申请（P1）。 */
    public static class ExtensionApplyRequest {
        public String vin;
        public Integer extDays;
        public String reason;
    }

    /** 提前部分还款（P1）。 */
    public static class PartialRepaymentRequest {
        public String vin;
        public Long principalAmount;
        public String idempotencyKey;
    }

    /** 额度年审启动（P1）。 */
    public static class AnnualReviewInitiateRequest {
        public Long dealerId;
        public Long creditLineId;
        public String cycle;
    }

    /** 额度年审完成（P1）。 */
    public static class AnnualReviewCompleteRequest {
        public boolean passed;
        public String resultNote;
    }

    /** 权属核对录入（P0-③ 重复融资拦截的数据来源）。 */
    public static class LienCheckRequest {
        /** MANUAL / OEM_PORTAL / REGISTRY_API */
        public String checkSource;
        /** 是否在他处存在权属负担（true = 禁止重复融资） */
        public Boolean encumbered;
        public String note;
    }

    /**
     * 授信评级分级口径变更（后台动态配置）。
     * 等级代码走 URL 路径（{@code PUT /api/rating-configs/{grade}}），不放在请求体里。
     */
    public static class RatingConfigRequest {
        /** 等级名称（展示用），如"优质 / 标准 / 关注 / 观察"。 */
        public String gradeName;
        /** 年化利率加点（小数：0.005 = +0.5%），范围 0 ~ floorplan.rating.max-rate-surcharge。 */
        public BigDecimal rateSurcharge;
        /** 是否准入新业务（false = 停用该等级的新增垫款建账）。 */
        public boolean allowed;
        /** 备注（如"客户确认口径 2026-10 版"）。 */
        public String remark;
    }

    /** 经销商授信评级调整（PUT /api/dealers/{id}/risk-grade）。 */
    public static class DealerGradeRequest {
        /** 新评级代码（必须在评级口径表中存在且未停用）。 */
        public String riskGrade;
    }

    /* ---- 上游主数据与收款账户（ADR-007） ---- */

    /**
     * 新增上游（POST /api/suppliers）。
     *
     * <p>{@code supplierType} 决定打款模式，是核心字段——
     * 它不是可选的展示信息，而是资金去向的开关。</p>
     */
    public static class SupplierCreateRequest {
        /** 上游编号（唯一）。 */
        public String supplierCode;
        /** 上游名称。 */
        public String name;
        /**
         * 上游类型：MAKER_SALES_CO / REGIONAL_AGENCY / WHOLESALER /
         * AUCTION / USED_WHOLESALER / DEALER_SELF。
         */
        public String supplierType;
        /** 上游评级（可空）。 */
        public String riskGrade;
        /** 回购/担保能力：STRONG / LIMITED / NONE。 */
        public String guaranteeAbility;
        public String contactPerson;
        public String contactPhone;
        public String remark;
    }

    /** 更新上游（PUT /api/suppliers/{id}）。null 字段不修改。 */
    public static class SupplierUpdateRequest {
        public String name;
        public String supplierType;
        public String riskGrade;
        public String guaranteeAbility;
        public String contactPerson;
        public String contactPhone;
        public String remark;
    }

    /**
     * 新增收款账户（POST /api/suppliers/{id}/accounts 与 /api/dealers/{id}/bank-accounts）。
     *
     * <p><b>accountNo 是明文账号</b>，只在本次请求内存活，落库即 AES-GCM 加密，
     * 对外只返掩码。</p>
     */
    public static class BankAccountCreateRequest {
        /** 开户行。 */
        public String bankName;
        /** 户名。 */
        public String accountHolder;
        /** 明文账号（仅本次请求存在）。 */
        public String accountNo;
        /** 业务引用码（导入清单填这个；留空则自动生成）。 */
        public String accountRef;
        /** 生效日（yyyy-MM-dd）。 */
        public String effectiveFrom;
    }

    /** 状态变更（上游状态 / 账户状态）。 */
    public static class StatusChangeRequest {
        /** 目标状态。 */
        public String status;
    }

    /* ---- 附件（ADR-007 决策十） ---- */

    /**
     * 申请上传授权（POST /api/attachments/presign）。
     *
     * <p>授权会被绑定到 {@code objectKey + contentType + contentLength}——
     * 拿 A 的授权传 B 会被拒绝。</p>
     */
    public static class AttachmentPresignRequest {
        /** 业务类型：PURCHASE_ORDER。 */
        public String bizType;
        /** 业务对象 ID。 */
        public Long bizId;
        /** 原始文件名。 */
        public String filename;
        /** 内容类型。 */
        public String contentType;
        /** 文件大小（字节）。 */
        public long contentLength;
    }

    /**
     * 上传完成登记（POST /api/attachments/complete）。
     *
     * <p>服务端会回读对象元数据校验，防止"上传 A 登记 B"。</p>
     */
    public static class AttachmentCompleteRequest {
        /** 对象键。 */
        public String objectKey;
        /** 原始文件名。 */
        public String filename;
        /** 内容类型。 */
        public String contentType;
        /** 声明的文件大小。 */
        public long contentLength;
        /** 业务类型。 */
        public String bizType;
        /** 业务对象 ID。 */
        public Long bizId;
    }
}

package com.company.floorplan.domain.model;

/**
 * 担保物证据类型。
 *
 * <p><b>为什么单列这个枚举</b>：日本让渡担保的效力与顺位在 2025 年新法施行前
 * 长期依赖判例与实务，2025 年成立的新法
 * （《譲渡担保契約及び所有権留保契約に関する法律》，2025-05-30 成立、
 * 2025-06-06 公布，施行日最晚 2027-12-06）施行后清算与优先顺位更明确，
 * 但<b>内部证据留存仍然是核心风险控制手段</b>——
 * 担保权人的举证能力取决于日常留存是否完整。</p>
 *
 * <p>本枚举定义了"一台车的担保闭环需要哪些证据"，缺一项即构成证据链缺口。
 * 盘点作业据此扫描并生成缺口告警。</p>
 */
public enum EvidenceType {

    /** 合同：让渡担保契约 / 所有权保留契约 / 采购合同。 */
    CONTRACT("合同"),

    /** 登记事项证明书（謄本）：证明登记已设立。 */
    REGISTRATION_CERT("登记事项证明书"),

    /** 抹消凭证：证明登记已解除。 */
    CANCELLATION_CERT("抹消凭证"),

    /** 放款凭证：垫款支付记录。 */
    ADVANCE("放款凭证"),

    /** 盘点记录：实地盘点扫描清单与现场照片。 */
    INVENTORY_AUDIT("盘点记录"),

    /** 回款凭证：还款核销记录。 */
    REPAYMENT("回款凭证"),

    /** 处置凭证：收车 / 变现 / 损失认定材料。 */
    DISPOSAL("处置凭证"),

    /** 保险单：担保物保险。 */
    INSURANCE("保险单"),

    /** 厂家回购承诺 / 兑现凭证。 */
    REPURCHASE("厂家回购凭证");

    private final String label;

    EvidenceType(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}

package com.company.floorplan.domain.model;

/**
 * 合规核查的实施路径（文档原词「コンプライアンス確認の実施経路」；USED 域 R1.1）。
 *
 * <p><b>取值定源</b>：D04 §3.3 的 {@code dealer_compliance_checks.source}
 * —— {@code VARCHAR(24) CHECK ∈ {MANUAL, PARTNER_API, CREDIT_BUREAU}}；
 * DB 侧约束在 V45 的 {@code ck_dchk_source}。</p>
 *
 * <p><b>为什么判定不读本列</b>：本列回答「结论从哪来」，不回答「结论是什么」。
 * {@code DealerAdmissionPolicy} 规则 2 只看 {@link ComplianceResult}，
 * 来源只影响证据强度与补查动作（{@code MANUAL} 的 PASS 需要
 * {@code evidence_path} 支撑，{@code PARTNER_API} 的 PASS 还要能对上
 * {@code request_no} 的报文——V45 的 {@code uk_dchk_request_no} 就是为后者准备的）。
 * 「来源可信度加权」是 R1.2 聚合规则的待定源，本包不写入判定。</p>
 */
public enum ComplianceCheckSource {

    /** 人工录入（把外部查询结果抄进系统）。 */
    MANUAL("人工录入"),

    /** 合作方 API 回写（走集成侧验签，报文引用见 {@code request_no}）。 */
    PARTNER_API("合作方接口"),

    /** 信用情報機関（征信机构）回执。 */
    CREDIT_BUREAU("征信机构");

    /** 中文展示名（前端提示与审计说明用）。 */
    private final String label;

    ComplianceCheckSource(String label) {
        this.label = label;
    }

    /**
     * 展示名。
     *
     * @return 展示名
     */
    public String getLabel() {
        return label;
    }
}

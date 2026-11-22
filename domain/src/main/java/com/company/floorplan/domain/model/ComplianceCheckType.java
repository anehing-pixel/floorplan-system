package com.company.floorplan.domain.model;

/**
 * 合规核查的种类（文档原词「コンプライアンス確認の種類」；USED 域 R1.1）。
 *
 * <p><b>取值定源</b>：D04 §3.3 的 {@code dealer_compliance_checks.check_type}
 * —— {@code VARCHAR(24) CHECK ∈ {ANTI_SOCIAL, AML, IDENTITY, CREDIT_BUREAU}}；
 * DB 侧双层约束在 V45 的 {@code ck_dchk_type}。</p>
 *
 * <p><b>命名差异（登记为文档冲突）</b>：D03 §2.1 把聚合成员写成
 * 「complianceChecks（反社 / AML / 本人确认）」，用的是业务叫法「本人确认」；
 * D04 §3.3 落库写作 {@code IDENTITY}，并多出一个 {@code CREDIT_BUREAU}
 * （征信照会：D04 归入合规核查，D03 把它当 {@code riskGrade} 的输入）。
 * 本枚举以 <b>D04 §3.3 为准</b>——取值集的定义点在库侧，
 * 且 D04 §3.3 的 {@code source} 列同样有 {@code CREDIT_BUREAU}，两列成对出现，
 * 删掉本值会让 source 失去对应项。D03 的「本人确认」↔ {@code IDENTITY} 的对应关系写在下面，
 * 避免后来人以为是两件事。</p>
 *
 * <p><b>判定要求"四类全有"，定源是 D06 §1.3 G-A3</b>：四类 {@code check_type}
 * 各自都要有一条未过期的 {@code PASS} 才算合规通过，缺任一类即 {@code QUAL_003}。
 * 因此 {@code DealerAdmissionPolicy} 直接遍历本枚举的取值集，
 * <b>不需要"必需 type 清单"这个参数</b>——清单已由定源固定为全部四类，
 * 新增第五类取值时判定范围随之自动扩（这也是把清单绑在枚举上的意图）。
 * 仍未签字的是<b>各自的有效窗口</b>（{@code compliance-valid-days[D-02]}：核查日 + N 天 =
 * {@code validUntil} 的算法），那属录入侧 {@code recordComplianceCheck} 读参数表的部分，
 * 判定侧只看已落库的 {@code validUntil}，读不到参数就不写 {@code validUntil}
 * （空 {@code validUntil} 一律算无效，见 {@link ComplianceCheck#isValidPass}）。</p>
 */
public enum ComplianceCheckType {

    /** 反社会势力核查（业务原词「反社チェック」）。D03 §2.1 规则 2 与 P-7 的硬阻断项。 */
    ANTI_SOCIAL("反社会势力核查"),

    /** 反洗钱核查（AML／マネーロンダリング対策）。 */
    AML("反洗钱核查"),

    /** 本人确认（D03 §2.1 与库里都写作 {@code IDENTITY}，业务原词「本人确认」）。 */
    IDENTITY("本人确认"),

    /** 征信机构照会（信用情報機関）。与 D04 §3.3 的 {@code source = CREDIT_BUREAU} 配对。 */
    CREDIT_BUREAU("征信照会");

    /** 中文展示名（业务原词为日文时以原词为准的，如「古物商許可」，保留原词）。 */
    private final String label;

    ComplianceCheckType(String label) {
        this.label = label;
    }

    /**
     * 中文展示名。
     *
     * @return 展示名
     */
    public String getLabel() {
        return label;
    }
}

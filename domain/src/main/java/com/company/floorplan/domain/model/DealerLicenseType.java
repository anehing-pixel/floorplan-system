package com.company.floorplan.domain.model;

/**
 * 許可証類型（USED 域 R1.1）。
 *
 * <p><b>取值定源</b>：D04 §3.1 的 {@code dealer_licenses.license_type}
 * —— {@code VARCHAR(32) NOT NULL, CHECK ∈ {FURIGUSHO}}，并注明「古物商許可；未来可扩展」。
 * 注意 D00 §8.1（产品与车辆枚举字典）<b>不含</b>本枚举：车商资质侧的取值集是 D04 §3.1–3.3 定的，
 * 已作为「D00 §8 枚举字典未覆盖 R1 侧枚举」登记进实现说明的文档缺口清单。</p>
 *
 * <p><b>为什么现在只放一个值</b>：D03 §2.1 的审核规则 1 只问「有没有有效許可」，
 * 不区分类型。值域一旦先放开（例如先加 {@code MOTOR_VEHICLE_DEALER_LICENSE}），
 * 新类型就会在没有对应判定规则的情况下直接通过规则 1——这是 Q21 那类
 * 「配置面比判定面宽」的旁路形状。因此扩展的正确顺序是：
 * 先在 D04 §3.1 的 CHECK 加值 + 在 D03 §2.1 写清该类型的判定，再改本枚举，
 * 三处同一笔变更一起进（漏 Java 侧会出现「库存得进、读回 {@code valueOf} 抛
 * {@code IllegalArgumentException}」，漏 DB 侧则新值无处可存）。</p>
 *
 * <p><b>双层约束（R13.4）</b>：本枚举与 V45 的 {@code ck_dlic_license_type} 是同一集合的两个表达，
 * 谁都不比谁宽松。</p>
 */
public enum DealerLicenseType {

    /** 古物商许可（业务原词「古物商許可」，由管辖公安委员会发行）。USED 域"有效许可"目前只认这一个。 */
    FURIGUSHO("古物商许可");

    /** 中文展示名（列表页与审计说明用，不参与判定；库内值仍是 {@code FURIGUSHO}）。 */
    private final String label;

    DealerLicenseType(String label) {
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

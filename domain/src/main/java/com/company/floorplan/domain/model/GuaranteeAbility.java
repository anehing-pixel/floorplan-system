package com.company.floorplan.domain.model;

/**
 * 上游回购/担保能力。
 *
 * <p><b>为什么必须分三级</b>：日本新车流通里，经销商上游是多层渠道——
 * 制造商销售公司有厂商回购（STRONG），但区域总代理、批发公司、拍卖行
 * 普遍<b>没有</b>厂商回购（NONE）。若按"上游都是厂家"一刀切处理，
 * 就会在拍卖行采购的车辆上虚构出一份不存在的厂商回购担保。</p>
 */
public enum GuaranteeAbility {

    /** 强：厂商回购覆盖，可作为放款增信依据。 */
    STRONG("强（厂商回购）"),

    /** 有限：部分覆盖或仅部分车型覆盖，需逐单核实。 */
    LIMITED("有限"),

    /** 无：无回购覆盖，风险须由其他手段覆盖（抵质押/担保等）。 */
    NONE("无");

    private final String label;

    GuaranteeAbility(String label) {
        this.label = label;
    }

    /** 中文展示名。 */
    public String getLabel() {
        return label;
    }
}

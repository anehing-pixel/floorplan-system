package com.company.floorplan.recon.model;

/**
 * 一条"对拍事实"：账本两侧口径的取值结果，尚未判定是否构成差异。
 *
 * <p><b>为什么单独有这个类，而不是让规则直接查库</b>：
 * 规则必须是可以脱离数据库单测的纯函数。仓储负责把账本读成一组 Comparison
 * （subject + 期望侧取值 + 实际侧取值），规则只对这些值做判定。
 * 这样"SQL 取数是否正确"和"判定逻辑是否正确"可以分别验证——
 * 前者靠库，后者靠参数化单测，互不依赖。</p>
 *
 * <p>金额一律整数日元；状态类规则把布尔关系编码为 1/0（见 R2）。</p>
 *
 * @param subject  对拍对象标识（VIN / 额度 ID）
 * @param expected 期望侧取值（规则定义的"应有口径"）
 * @param actual   实际侧取值（被对拍账本的"实有口径"）
 * @param note     取数备注（可选，用于差异说明）
 */
public record Comparison(String subject, long expected, long actual, String note) {

    public Comparison(String subject, long expected, long actual) {
        this(subject, expected, actual, null);
    }
}

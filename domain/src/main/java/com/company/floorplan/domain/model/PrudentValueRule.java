package com.company.floorplan.domain.model;

import java.math.BigDecimal;
import java.util.Locale;

/**
 * 审慎值规则（USED R3.1，{@code vehicle_valuations.prudent_rule}，D08 §3.2）。
 *
 * <p>取值集 = V55 {@code ck_val_rule} = D00 §8.5 两值。</p>
 *
 * <p><b>规则由配置单点决定，请求体无权选择</b>（D08 §3.2「加权规则的使用限制」：
 * 不允许逐台临时选择，否则「哪台用哪种算法」变成可协商口径）。
 * 因此本枚举的 {@code #calculate} 只吃「已登记的输入 + 一个规则值」，
 * 规则值来自 {@code floorplan.used.valuation.default-prudent-rule}，
 * 用例层不接受客户端传 rule。</p>
 *
 * <p><b>金额全程 {@link BigDecimal} 或 long，绝不出现 double</b>（AGENTS §6.3）。
 * WEIGHTED 的除法必须先做「Σ(v·w) ÷ Σw」再一次向下取整：
 * 先把每台的四舍五入做完再求和，会得到一个偏高的审慎值——方向正好和「审慎」相反。</p>
 */
public enum PrudentValueRule {

    /** 取各可用源最小值（默认，也是唯一「争议时能自证」的口径）。 */
    MIN_OF_SOURCES,

    /** 加权平均：Σ(v_i × w_i) ÷ Σ w_i，向下取整。 */
    WEIGHTED;

    /** 解析入参词；非法词由用例层翻成 {@code VALID_001}。 */
    public static PrudentValueRule from(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("审慎值规则不能为空");
        }
        return PrudentValueRule.valueOf(raw.trim().toUpperCase(Locale.ROOT));
    }

    /** 宽松解析：读不到或脏值时返回 {@code null}，由调用方 fail-closed（配置缺值不许猜默认）。 */
    public static PrudentValueRule parseOrNull(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return from(raw);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}

package com.company.floorplan.bank.model;

/**
 * 回款匹配状态（取值与 V15 payment_transactions.match_status 及 V17 payment_matches 一致）。
 *
 * <p>UNMATCHED 不是"失败"，而是"挂起待人工"——设计稿 §3.6：低置信度不强行自动绑定，
 * 误匹配会同时造成漏管敞口与误催收，两种错误都不可接受。</p>
 */
public enum MatchStatus {

    /** 无法自动匹配，挂起待人工裁决。 */
    UNMATCHED,
    /** 高置信度自动匹配（VIN 命中，分数显著领先）。 */
    AUTO_MATCHED,
    /** 人工绑定到某借据；operator 必须是自然人。 */
    MANUAL_MATCHED,
    /** 人工判定为非本业务流水。 */
    IGNORED,
    /** 一笔流水拆分到多台车（口座振替/自动払出し 场景）。 */
    SPLIT;

    public String code() {
        return name();
    }
}

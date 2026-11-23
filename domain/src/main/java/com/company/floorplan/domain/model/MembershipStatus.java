package com.company.floorplan.domain.model;

import java.util.Locale;

/**
 * 拍卖场会员关系状态（USED R1，G-A2 判定输入）。
 *
 * <p>取值域 = {@code dealer_auction_memberships.ck_dmem_status}（V45）四值。
 * 与许可不同，会员是<b>按拍卖场分别</b>持有的一条关系（R1.1 验收：同一车商可在
 * USS 与 TAA 各有会员号），所以「有没有效」必须先回答「对哪个场」。</p>
 *
 * <p>{@code SUSPENDED} 单独存在是因为拍场的停摆（会费未纳、违规）可以随时发生且可恢复，
 * 与 {@code TERMINATED}（脱会，不可自行恢复）语义不同：前者可以出现在续会流程里，
 * 后者必须重新申请。闸门只认 ACTIVE，其余全算无效——不给「停摆但还能用」留口子。</p>
 */
public enum MembershipStatus {

    /** 正常会员。 */
    ACTIVE("有效会员"),

    /** 停摆（拍场单方面暂停，可恢复）。 */
    SUSPENDED("停摆"),

    /** 会籍到期。 */
    EXPIRED("已到期"),

    /** 脱会/除名。 */
    TERMINATED("已终止");

    private final String label;

    MembershipStatus(String label) {
        this.label = label;
    }

    /** 中文标签。 */
    public String getLabel() {
        return label;
    }

    /** 是否支撑准入（只认 ACTIVE）。 */
    public boolean isValid() {
        return this == ACTIVE;
    }

    /** 解析库内文本；空值返回 null。 */
    public static MembershipStatus from(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        for (MembershipStatus s : values()) {
            if (s.name().equals(normalized)) {
                return s;
            }
        }
        throw new IllegalArgumentException("未知的会员状态: " + value);
    }
}

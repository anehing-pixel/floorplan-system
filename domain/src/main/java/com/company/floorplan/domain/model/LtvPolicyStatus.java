package com.company.floorplan.domain.model;

import java.util.Locale;

/**
 * LTV 政策状态（USED R3.2/R3.4，{@code ltv_policies.status}，D04 §3.14）。
 *
 * <p>取值集 = V57 {@code ck_ltv_policy_status} 三值。<b>没有 PENDING</b>：
 * D04 §3.14 明确「政策生效是一步动作」，{@code approve} 端点内部校验二人分离后
 * 直接把 DRAFT 推到 ACTIVE，不留一个「谁都能推一把」的中间态。</p>
 *
 * <p><b>L5（D08 §4.2）版本不可变</b>：ACTIVE 版本的带集合不可就地改写，
 * 改带只能插新版本并把旧版本置 {@link #RETIRED}。所以 {@link #isMutable()}
 * 与 {@link #canEditBands()} 是两个问题：前者管这一行还能不能改，
 * 后者管带集合还能不能换——DRAFT 两者都是 true，ACTIVE 两者都是 false。</p>
 */
public enum LtvPolicyStatus {

    /** 草稿：可改可换带，未被任何放款引用。 */
    DRAFT,

    /** 生效中：判定读它；带集合冻结（L5），同 policy_code 同时最多一行（V57 {@code uk_policy_active}）。 */
    ACTIVE,

    /** 已退役：被新版本取代，或人工下线。历史笔靠 policy_code + version 仍能指回它。 */
    RETIRED;

    /** 本行是否还允许改写（ACTIVE 之后的行属审计证据）。 */
    public boolean isMutable() {
        return this == DRAFT;
    }

    /** 带集合是否允许整体替换（L5 的应用层表达）。 */
    public boolean canEditBands() {
        return this == DRAFT;
    }

    /** 迁移是否合法：只允许 DRAFT→ACTIVE、ACTIVE→RETIRED、DRAFT→RETIRED（废弃草稿）。 */
    public boolean canMoveTo(LtvPolicyStatus target) {
        if (target == null || target == this) {
            return false;
        }
        switch (this) {
            case DRAFT:
                return target == ACTIVE || target == RETIRED;
            case ACTIVE:
                return target == RETIRED;
            default:
                return false;
        }
    }

    /** 解析入参词；非法词由用例层翻成 {@code VALID_001}。 */
    public static LtvPolicyStatus from(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("政策状态不能为空");
        }
        return LtvPolicyStatus.valueOf(raw.trim().toUpperCase(Locale.ROOT));
    }
}

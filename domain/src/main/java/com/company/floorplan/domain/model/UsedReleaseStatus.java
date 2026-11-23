package com.company.floorplan.domain.model;

import java.util.EnumSet;
import java.util.Set;

/**
 * USED 域放车指令状态（十态）。
 *
 * <p><b>为什么不直接扩 {@code release-service} 里那枚 6 值 {@code ReleaseStatus}</b>：
 * 现役 {@code ReleaseStateMachine.ALLOWED} 是一张只有 6 个键的静态 {@code EnumMap}
 * （release-service/.../statemachine/ReleaseStateMachine.java:43-57），
 * 只加枚举值不加键会让 {@code ALLOWED.get(ACKNOWLEDGED)} 返回 {@code null}，
 * 「是否合法迁移」当场 NPE 或被误判非法（D13 §3 G11 点名的坑）。
 * D13 G11 给的落地形态是"扩那枚枚举 + 同包新增 {@code ReleaseStateMachineUsed}"，
 * 本轮改放在 {@code domain}，理由有两条，且都不违反 G11 要防的那件事：</p>
 * <ol>
 *   <li>LEGACY 那枚枚举与那张 6×6 白名单表按 §5 属禁区，一处不动，
 *       {@code ReleaseStateMachineTest} 的 36 组期望天然不受影响；
 *       USED 表另建（见 {@code UsedReleaseStateMachine}），LEGACY 的 EnumMap 不会被读到新值；</li>
 *   <li>解押编排（R8 + A9.1 的"同一事务里钱/额度/押印一起动"）按 D12 归 {@code web}(core)，
 *       而放车端点归 {@code release-service}——两边必须用<b>同一份</b>状态语义，
 *       否则会出现"core 认为 PARTIAL 合法、release 认为不合法"这种两套账。
 *       模块依赖实测：web/pom.xml 与 release-service/pom.xml 都只引
 *       application/infrastructure/common，两边共同可见的只有 domain 与 application，
 *       所以状态枚举措 domain、用例服务举 application、端点留在各自服务里做薄壳。</li>
 * </ol>
 *
 * <p><b>迁移白名单见 {@code UsedReleaseStateMachine}</b>；本枚只负责取值、
 * 集合语义与 RB-1 的「谁能被业务代码写入」判定。</p>
 *
 * @see com.company.floorplan.domain.service.UsedReleaseStateMachine
 */
public enum UsedReleaseStatus {

    /** 待释放：资金闸门通过后生成指令的初始态（D03 §3.3 的 [*] → PENDING_RELEASE）。 */
    PENDING_RELEASE("待释放"),

    /** 已下发：通道下发成功，等待对方受理或回执。 */
    SENT("已下发"),

    /**
     * 已受理：对方确认收到指令（USED 新增）。
     * <p>进入本态后撤销代价变高——D09 §5.4 要求 {@code force_reason} + 二人，
     * 且<b>不回补也不扣减额度</b>（额度占用从建单起算，撤销只是取消动作，不是退款）。</p>
     */
    ACKNOWLEDGED("对方已受理"),

    /**
     * 部分释放：回执项集与指令项集不一致（USED 新增，R7.4）。
     * <p>聚合规则「取最不利」：同一指令的多个项状态不一致时，主状态取最不利的项，
     * 所以 PARTIAL 不会被别的项的 RELEASED 洗成 CONFIRMED（A3.2）。</p>
     */
    PARTIAL("部分释放"),

    /** 已确认：收到有效回执（LEGACY 六态之一）。 */
    CONFIRMED("已确认"),

    /** 已释放：实车出库且車検証交还全部完成（LEGACY 六态之一）。 */
    RELEASED("已释放"),

    /**
     * 担保登记抹消完成（LEGACY 六态之一，但语义在本轮<b>收窄</b>）。
     * <p>原义"解押完成"含义模糊；收窄为「登记抹消完成回执到位」这一段
     * （D09 两段式：S4 实体权利消灭 {@code lien_extinguished_at} 在前，
     * S9③ 登记抹消 {@code lien_discharged_at} 在后）。{@code [待定源 D-07]}</p>
     */
    LIEN_DISCHARGED("登记抹消完成"),

    /**
     * 对方拒收（USED 新增）。
     * <p>RB-1：<b>只能由通道回执/驳回路径写入，不得作为调用方入参</b>，
     * 否则「调用方自报一个终态」就成了绕过证据链的捷径（A1.5）。</p>
     */
    REJECTED("对方拒收"),

    /**
     * 超期失效（USED 新增）。
     * <p>RB-1 同 REJECTED：只能由过期检查作业或 D07 §7.6 下发重校验的
     * 「valid_until 未到 → 拒发 + 置 EXPIRED」这条<b>系统内部</b>路径写入，
     * 请求体里带 {@code status=EXPIRED} 一律拒绝（A1.4/A1.5）。</p>
     */
    EXPIRED("超期失效"),

    /** 已取消：下发前撤销，或 REJECTED/EXPIRED 后人工作废（新指令另起新号）。 */
    CANCELLED("已取消");

    /**
     * LEGACY 六态：本轮之前 {@code release-service} 已有的取值集合。
     *
     * <p>用途只有一个——<b>可机检的兼容边界</b>。D03 §3.3 要求
     * 「旧六态之间的合法迁移集在 USED 枚举下保持完全一致」，
     * 这句话要能被断言，前提是先钉死"旧六态"是哪六个
     * （实测源：release-service/.../model/ReleaseStatus.java 的 6 个常量）。</p>
     */
    public static final Set<UsedReleaseStatus> LEGACY_STATES = Set.of(
            PENDING_RELEASE, SENT, CONFIRMED, RELEASED, LIEN_DISCHARGED, CANCELLED);

    /** USED 新增的四个态。 */
    public static final Set<UsedReleaseStatus> USED_ONLY_STATES = EnumSet.complementOf(
            EnumSet.copyOf(LEGACY_STATES));

    /** 终态：进入后不再有出边（D03 §3.3 的 --> [*] 只有这两个）。 */
    public static final Set<UsedReleaseStatus> TERMINAL_STATES = Set.of(LIEN_DISCHARGED, CANCELLED);

    private final String label;

    UsedReleaseStatus(String label) {
        this.label = label;
    }

    /** 中文标签，供前端与审计说明使用。 */
    public String getLabel() {
        return label;
    }

    /** 是否属于 LEGACY 六态（用于兼容断言与 product_line 选表）。 */
    public boolean isLegacyState() {
        return LEGACY_STATES.contains(this);
    }

    /** 是否终态。 */
    public boolean isTerminal() {
        return TERMINAL_STATES.contains(this);
    }

    /**
     * 该态是否<b>只允许系统侧写入</b>（D06 §16 RB-1）。
     *
     * <p>返回 true 的两个态（EXPIRED / REJECTED）都不表达"某人做了一个业务决定"，
     * 而表达"外部世界发生了事实"：期限自己到了、对方自己拒了。
     * 所以调用方在请求里指定它们必须被拒（A1.5），
     * 只有作业路径与回执/驳回入站路径能写（A1.4、D07 §7.6 下发重校验表第 2 行）。
     * 判定实现见 {@code UsedReleaseStateMachine#requireTransition}。</p>
     *
     * @return true 表示该态不可作为调用方入参
     */
    public boolean requiresSystemOrigin() {
        return this == EXPIRED || this == REJECTED;
    }

    /**
     * 是否处于"已出库但未抹消登记"的敞口段。
     *
     * <p>D07 §7.6 的重校验清单与 D09 S3 都要求：RELEASED 之后到 LIEN_DISCHARGED 之前，
     * 同一借据不能再开新指令（担保物已失控，只剩登记没完）。
     * 单独暴露这个区间，是为了让闸门条件写成业务语言而不是两个状态值的比较。</p>
     */
    public boolean isReleasedButNotDischarged() {
        return this == RELEASED;
    }

    /** 按数据库存量文本解析；未知取值直接失败，不静默兜底成 PENDING_RELEASE。 */
    public static UsedReleaseStatus from(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
    }
}

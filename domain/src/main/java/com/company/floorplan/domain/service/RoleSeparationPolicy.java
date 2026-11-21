package com.company.floorplan.domain.service;

import java.util.List;
import java.util.Map;
import java.util.Set;

import com.company.floorplan.domain.model.Permission;
import com.company.floorplan.domain.model.UserRole;

/**
 * 角色授权与职责分离策略（领域层）。
 *
 * <p><b>它管两件事</b>：</p>
 * <ol>
 *   <li><b>能不能做</b>：把"用户持有的角色集合"解析成"有效权限点集合"，
 *       供 web 层判断某接口是否放行；</li>
 *   <li><b>该不该由这个人做</b>：判断两个角色是否<b>不相容</b>（SOD），
 *       用于人员配置时的兼岗提醒，以及同一笔业务的经办人复核。</li>
 * </ol>
 *
 * <p><b>为什么放在领域层而不是 web 拦截器里</b>：不相容关系是业务规则，
 * 不是传输层细节。它同时被三个地方用到——登录时提示兼岗冲突、
 * 接口鉴权时判定权限、管理后台配置角色时提醒。
 * 放领域层才能被复用，且能脱离 HTTP 上下文做单元测试。</p>
 *
 * <p><b>权限点的并集语义</b>：一个用户可以有多个角色（一人多岗），
 * 其有效权限是各角色权限的<b>并集</b>。这样"经办岗 + 结算岗"的人
 * 既能发起垫款也能办结清，符合小团队现实。</p>
 *
 * <p><b>但并集不能突破不相容约束</b>：正因权限取并集，
 * 必须另设不相容矩阵来防止"一人身兼申请与支付"。
 * 权限管"能做什么"，不相容管"不能同时做什么"——两个问题，两套机制。</p>
 */
public final class RoleSeparationPolicy {

    /**
     * 不相容角色对（对称关系，只需声明一次）。
     *
     * <p><b>为什么是这些对</b>：每一条都对应一个真实可被利用的内控缺口：</p>
     * <ul>
     *   <li>{@code 采购录入 ↔ 采购审核}：录入者自审自签，采购清单失真无人把关；</li>
     *   <li>{@code 业务经办 ↔ 资金复核}：自己填的申请自己批；</li>
     *   <li>{@code 业务经办 ↔ 资金出纳}：自己申请自己放款（ADR-006 明确禁止）；</li>
     *   <li>{@code 资金复核 ↔ 资金出纳}：自己批完自己打，金额与收款方可被同时篡改；</li>
     *   <li>{@code 催收 ↔ 结算}：催收认定与收款核销同一人，可掩盖挪用；</li>
     *   <li>{@code 担保管理 ↔ 违约处置}：担保物处置由担保登记人自己定，处置估值易被操纵；</li>
     *   <li>{@code 系统管理员 ↔ 任何业务写岗}：管理员能改配置又能代行业务，双人复核失效。</li>
     * </ul>
     *
     * <p>注意：不相容是<b>岗位配置层面的规则</b>，
     * 不等于"同一个人绝不能持有两个角色"——现实中一人多岗很常见。
     * 因此系统管理员持有这些角色时应当<b>告警提示</b>而非硬阻断；
     * 真正必须阻断的是<b>同一笔业务</b>上的经办人复核（见
     * {@link #assertNoSelfApproval}），那是自然人级的硬约束。</p>
     */
    private static final Map<Set<UserRole>, String> INCOMPATIBLE = Map.of(
            Set.of(UserRole.PURCHASE_OPERATOR, UserRole.PURCHASE_REVIEWER),
            "录入岗与审核岗不相容：录入者不应审核自己的采购清单",
            Set.of(UserRole.BUSINESS_OPERATOR, UserRole.FUNDING_REVIEWER),
            "经办岗与复核岗不相容：不应自行审批自己发起的垫款申请",
            Set.of(UserRole.BUSINESS_OPERATOR, UserRole.FUNDING_CASHIER),
            "经办岗与出纳岗不相容：不应自行支付自己发起的垫款",
            Set.of(UserRole.FUNDING_REVIEWER, UserRole.FUNDING_CASHIER),
            "复核岗与出纳岗不相容：不应自行复核并支付同一笔放款",
            Set.of(UserRole.COLLECTION_OFFICER, UserRole.SETTLEMENT_OFFICER),
            "催收岗与结算岗不相容：催收认定与收款核销应由不同人负责",
            Set.of(UserRole.COLLATERAL_OFFICER, UserRole.DISPOSAL_OFFICER),
            "担保管理岗与处置岗不相容：担保物处置不宜由担保登记人自行认定");

    /** 系统管理员不得与任何持有写权限的业务岗同时配置。 */
    private static final List<UserRole> BUSINESS_WRITE_ROLES = List.of(
            UserRole.CREDIT_REVIEWER,
            UserRole.PURCHASE_OPERATOR,
            UserRole.PURCHASE_REVIEWER,
            UserRole.BUSINESS_OPERATOR,
            UserRole.FUNDING_REVIEWER,
            UserRole.FUNDING_CASHIER,
            UserRole.COLLATERAL_OFFICER,
            UserRole.POST_LENDING_MANAGER,
            UserRole.COLLECTION_OFFICER,
            UserRole.SETTLEMENT_OFFICER,
            UserRole.DISPOSAL_OFFICER,
            UserRole.RISK_ADVISORY_OFFICER);

    private RoleSeparationPolicy() {
    }

    /**
     * 解析有效权限点集合（所有角色权限的并集）。
     *
     * @param roles 用户持有的角色集合
     * @return 有效权限点集合；无角色时返回空集合
     */
    public static Set<Permission> effectivePermissions(Set<UserRole> roles) {
        if (roles == null || roles.isEmpty()) {
            return Set.of();
        }
        return roles.stream()
                .flatMap(r -> r.getPermissions().stream())
                .collect(java.util.stream.Collectors.toSet());
    }

    /**
     * 判断角色集合是否拥有指定权限点。
     *
     * @param roles      用户持有的角色集合
     * @param permission 待校验权限点
     * @return true 表示放行
     */
    public static boolean hasPermission(Set<UserRole> roles, Permission permission) {
        return permission != null && effectivePermissions(roles).contains(permission);
    }

    /**
     * 判断两个角色是否不相容。
     *
     * @param a 角色A
     * @param b 角色B
     * @return true 表示不相容
     */
    public static boolean incompatible(UserRole a, UserRole b) {
        if (a == null || b == null || a == b) {
            return false;
        }
        return INCOMPATIBLE.containsKey(Set.of(a, b));
    }

    /**
     * 列出角色集合内部所有不相容的组合及其说明。
     *
     * @param roles 用户持有的角色集合
     * @return 说明列表；无冲突时返回空列表
     */
    public static List<String> findConflicts(Set<UserRole> roles) {
        List<String> conflicts = new java.util.ArrayList<>();
        if (roles == null || roles.size() < 2) {
            return conflicts;
        }
        List<UserRole> list = new java.util.ArrayList<>(roles);
        for (int i = 0; i < list.size(); i++) {
            for (int j = i + 1; j < list.size(); j++) {
                UserRole a = list.get(i);
                UserRole b = list.get(j);
                if (a == UserRole.SYSTEM_ADMIN && BUSINESS_WRITE_ROLES.contains(b)) {
                    conflicts.add("系统管理员不应兼任业务写岗（" + b.getLabel() + "）："
                            + "管理员能改配置又能代行业务，双人复核失效");
                } else if (incompatible(a, b)) {
                    conflicts.add(INCOMPATIBLE.get(Set.of(a, b)));
                }
            }
        }
        return conflicts;
    }

    /**
     * 自然人级职责分离校验：同一笔业务的经办人与复核人不得为同一人。
     *
     * <p><b>这是硬约束，与角色不相容矩阵是两回事</b>：矩阵管"岗位怎么配"（可告警），
     * 本方法管"这一笔能不能办"（必须阻断）。哪怕某个操作员同时持有
     * 经办岗与出纳岗，他<b>自己发起的垫款也不能由自己打款</b>——
     * 因为校验对象是<b>自然人身份</b>而不是角色。</p>
     *
     * @param initiator 操作人（发起/申请环节）
     * @param approver  操作人（复核/支付环节）
     * @param action    动作描述，用于错误提示
     */
    public static void assertNoSelfApproval(String initiator, String approver, String action) {
        if (initiator != null && initiator.equals(approver)) {
            throw new com.company.floorplan.domain.exception.BusinessException(
                    com.company.floorplan.domain.exception.ErrorCode.AUTH_003,
                    java.util.Map.of(
                            "action", action,
                            "initiator", initiator,
                            "reason", "SELF_APPROVAL"));
        }
    }
}

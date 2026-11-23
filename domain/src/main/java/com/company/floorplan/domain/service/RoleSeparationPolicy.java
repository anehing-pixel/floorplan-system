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
     * 解析有效权限点集合（所有角色权限的并集 ＋ 隐式读层）。
     *
     * <p><b>为什么必须并上 {@link UserRole#implicitReadPermissions()}</b>：这一份集合就是登录时
     * 签进令牌 {@code perms} 的内容，也是 {@code JwtAuthInterceptor} 比对
     * {@code @RequirePermission} 时唯一读的清单。而 {@link UserRole#can(Permission)} 里那条
     * 「读点隐式放行」从未参与过鉴权链路，两边不同口径的后果是带读权限点注解的查询端点对
     * <b>所有</b>角色回 403（USED R3 冒烟实测：{@code GET /api/valuations/active?vin=} 三岗全 403，
     * 而写端点一路正常——最容易误判成「估值端点没实现」）。
     * 不改成「给每个角色手铺读点」：逐角色读矩阵属 D10 未签字部分（AGENTS §0 铁律 3），
     * 而且读点铺进 {@code permissions} 会让「该角色是否写岗」的 {@code isWriter()} 判定失真。
     * 读层里没有写点，因此并进令牌不会扩大任何写能力，也不与任何 SOD 对撞。</p>
     *
     * @param roles 用户持有的角色集合
     * @return 有效权限点集合；<b>无角色时返回空集合</b>（未获岗的账号连读层也不给）
     */
    public static Set<Permission> effectivePermissions(Set<UserRole> roles) {
        if (roles == null || roles.isEmpty()) {
            return Set.of();
        }
        Set<Permission> effective = roles.stream()
                .flatMap(r -> r.getPermissions().stream())
                .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
        effective.addAll(UserRole.implicitReadPermissions());
        return Set.copyOf(effective);
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

    // =====================================================================
    // T1-5（B1 担保门禁）：放款前的担保登记校验接缝
    // =====================================================================

    /**
     * 担保登记查询端口。
     *
     * <p>application 层只依赖本接口；真正的 Feign 调用在 web 层适配器中完成，
     * 因为 core 各模块的 pom 不依赖 common（common 反向依赖 application，直接引用会循环）。
     * 这样既不改契约签名，也能让门禁判定逻辑脱离 HTTP/Feign 做单测。</p>
     */
    public interface GuaranteeLookup {
        /**
         * @return 担保登记视图；服务不可达或无记录返回 null（判定策略按 fail-closed 处理）
         */
        GuaranteeView findByVin(String vin);

        /** 与 CollateralClient.RegistrationVO 字段对齐的只读视图。 */
        record GuaranteeView(String status, String regNo, String ownerName, String registeredAt) {
        }
    }

    /** 门禁判定结果。 */
    public record GuaranteeDecision(boolean blocked, String reason) {
        public static GuaranteeDecision allow(String reason) {
            return new GuaranteeDecision(false, reason);
        }

        public static GuaranteeDecision block(String reason) {
            return new GuaranteeDecision(true, reason);
        }
    }

    /**
     * T1-5（B1）放款前担保门禁判定。
     *
     * <p><b>Q21 已业务定源（2026-11-22）：采用 A 硬阻断</b>。查不到 REGISTERED 登记
     * （含查询无结果、服务不可达、未知状态）一律拒付，<b>不保留按经销商/产品线放宽的通道</b>——
     * 白名单绕过会让"硬阻断"退化成软放行，与定源结论冲突。拒付时上层仍生成 HIGH 级
     * 「担保待登记」工单催办，但那是追补动作，不是放行。</p>
     *
     * <p><b>ADR-Q27 已裁决</b>：判定源为 V10
     * {@code collateral_registration_batches + items}（唯一权威源），
     * 由 collateral-reg-service 直读后把视图传进来。
     * 本策略<b>只消费上层传入的视图，不自作主张选择判定源</b>；
     * 开关默认 false，本地原型/冒烟不参与放款判定。</p>
     *
     * @param view        权属查询结果（null 表示服务不可达/无记录）
     * @param gateEnabled 门禁开关（默认 false）
     */
    public static GuaranteeDecision evaluateGuarantee(GuaranteeLookup.GuaranteeView view,
                                                      boolean gateEnabled) {
        if (!gateEnabled) {
            return GuaranteeDecision.allow("担保门禁默认关闭，不参与放款判定（ADR-Q27 判定源已定为 V10）");
        }
        if (view == null) {
            return GuaranteeDecision.block("担保权属查询无结果或服务不可达，fail-closed 硬阻断放款");
        }
        if ("REGISTERED".equalsIgnoreCase(view.status())) {
            return GuaranteeDecision.allow("该 VIN 已有 REGISTERED 担保登记");
        }
        return GuaranteeDecision.block("该 VIN 担保状态为 " + view.status()
                + "（非 REGISTERED），按 Q21 硬阻断放款");
    }
}

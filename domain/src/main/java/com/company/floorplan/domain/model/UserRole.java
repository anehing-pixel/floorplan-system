package com.company.floorplan.domain.model;

import java.util.Set;

import com.company.floorplan.domain.model.Permission;

import static com.company.floorplan.domain.model.Permission.*;

/**
 * 业务角色枚举（岗位粒度）。
 *
 * <p><b>角色从哪来</b>：不是凭空设计的，而是沿《库存融资核心系统-业务流程文档》的
 * 十一环节逐段找出"谁在这个环节动手"，把动手的人聚成岗位。核心链路是：</p>
 * <pre>
 *   ①授信准入 → ②验车确权 → ③收车放款 → ④⑤担保监管 → ⑥库龄递减
 *   → ⑦零售赎车 → ⑧解押出库 → ⑨额度恢复结清 → ⑩违约处置 → ⑪额度年审
 * </pre>
 * <p>其中环节③（收车放款）链路最长、内部要拆成三段：</p>
 * <pre>
 *   采购数据录入（人工导入）
 *     → 资料与实地审核（验车确权岗）
 *       → 垫款申请（业务经办岗）
 *         → 资金复核（资金复核岗）
 *           → 打款支付（资金出纳岗）
 * </pre>
 *
 * <p><b>为什么"申请 / 复核 / 支付"要拆三个角色</b>：ADR-006 已经把垫款拆成
 * 三段式（申请落 PENDING、支付推 PROCESSING、银行回调确认终态），
 * 本枚举在此基础上把"复核"补进来，形成
 * <b>经办 → 复核 → 出纳</b> 的经典不相容职责分离（SOD）链条。
 * 合并前两项意味着"自己填的申请自己批"，合并后两项意味着"自己批的自己打"，
 * 两者在放贷与资金业务中都是监管红线。</p>
 *
 * <p><b>角色与权限的关系</b>：角色是权限点的打包（{@link #permissions()}），
 * 接口侧声明需要哪个权限点（见 web 层 {@code @RequirePermission}），
 * 判定逻辑是"当前用户的任一角色拥有该权限点即放行"。</p>
 *
 * <p><b>不相容角色（incompatibleWith）</b>：标出"绝不应由同一个人同时担任"的岗位对。
 * 它是<b>人员配置层面的提醒</b>，与运行期"同一笔业务的申请人≠支付人"的
 * 自然人级校验（{@code AUTH_003}）是两回事：前者防岗位兼岗，后者防一笔业务自办自批。</p>
 */
public enum UserRole {

    /**
     * 系统管理员。
     *
     * <p><b>刻意不授予业务单据的写权限</b>（垫款申请/复核/支付、还款核销、处置等）：
     * 管理员只管系统与主数据，<b>不碰业务单据</b>。这是金融系统的一条重要原则——
     * 管理员账号能改配置、能看全部数据，但不能替业务岗审批或放款，
     * 否则"双人复核"就形同虚设（一个人拿管理员账号就能把两道关都过了）。
     * 系统管理员的操作全部进审计表，事后可查。</p>
     *
     * <p>授信额度建账属于<b>主数据</b>（不是垫款单据），不涉及资金划转，
     * 且系统初始化与批量建账场景需要管理员参与，故保留该权限。</p>
     */
    SYSTEM_ADMIN("系统管理员", RoleDomain.PLATFORM,
<<<<<<< Updated upstream
            set(CONFIG_MAINTAIN, JOB_TRIGGER, DEALER_MAINTAIN, SUPPLIER_MAINTAIN, CREDIT_MANAGE)),
=======
            set(CONFIG_MAINTAIN, JOB_TRIGGER, DEALER_MAINTAIN, SUPPLIER_MAINTAIN, CREDIT_MANAGE,
                    // USED R1：許可／会员／合规三类档案都挂在经销商主数据下，
                    // 本仓现网只有本角色持 DEALER_MAINTAIN，故先落在此处（岗位缺口见下方注释）。
                    DEALER_QUALIFICATION_MAINTAIN, DEALER_COMPLIANCE_CHECK,
                    DEALER_SCORE_SET, DEALER_ADMISSION_MANAGE,
                    // USED 收尾轮：读权限点与 R2/R5 读写点。管理员仍是"唯一全读"的那个岗，
                    // 但 USED_VEHICLE_MAINTAIN 之外没有给它任何资金/放款单据写权（类注释的红线不变）。
                    DEALER_READ, USED_VEHICLE_MAINTAIN, USED_VEHICLE_READ,
                    CERTIFICATE_RECORD, CERT_READ, USED_COLLATERAL_READ,
                    // W-U2：政策与拍场"维护"给管理员，"批准"不给（D10 D-5 要求两岗换人）。
                    // 同一角色禁持 LTV_POLICY_APPROVE——批准权只在 RISK_ADVISORY_OFFICER。
                    LTV_POLICY_MANAGE, AUCTION_HOUSE_MANAGE,
                    // W-U3 集成运维（D10 §3 的 ADMIN 列 ●，`:126`）。
                    // 【D-12 的落法】PARTNER_KEY_ROTATE 刻意不给本岗：密钥轮换与 partner 停用
                    //（在 PARTNER_CONFIG_MANAGE 下）同人 = 一人可换钥可停人（D10 §4 `:155`）。
                    // 本仓对成对权限点分离的既有做法就是「同一角色不同时持有这两点」
                    //（D-4 录入↔覆盖、D-5 维护↔批准都是这么落的，见 W-U2 那段注释），
                    // RoleSeparationPolicy.INCOMPATIBLE 登记的是**角色对**而不是权限点对，
                    // 不为 D-12 另造一套框架；运行期换人校验仍缺，已记实现说明缺口。
                    PARTNER_CONFIG_MANAGE, INTEGRATION_MONITOR, INTEGRATION_RETRY_TRIGGER,
                    INTEGRATION_AUDIT_READ), CUSTODIAN_MAINTAIN, DEALER_MAINTAIN, SUPPLIER_MAINTAIN),
>>>>>>> Stashed changes

    /**
     * 授信审查岗：准入与评级。
     *
     * <p>对应环节①授信准入与环节⑪额度年审。新建授信额度、录入征信结论、
     * 给出评级建议、执行年审复核。
     * <b>不碰打款</b>——额度批了不等于钱可以出去。</p>
     */
    CREDIT_REVIEWER("授信审查岗", RoleDomain.CREDIT,
            set(CREDIT_MANAGE, ANNUAL_REVIEW, RISK_GRADE_ADJUST)),

    /**
     * 采购数据录入岗（人工导入）。
     *
     * <p>对应环节③的<b>第一段</b>：把经销商或渠道发来的采购清单（Excel/CSV）
     * 导入系统，登记上游主体与收款账户，生成"待审核"的采购订单。
     * 这是<b>纯录入岗</b>：只负责把数据搬进来并保证格式与主数据齐全，
     * <b>不做实质审核</b>（那是审核岗的事），更不能发起放款。</p>
     */
    PURCHASE_OPERATOR("采购数据录入岗", RoleDomain.PURCHASE,
            set(PURCHASE_IMPORT, ATTACHMENT_UPLOAD, BANK_ACCOUNT_MAINTAIN)),

    /**
     * 采购审核岗（资料审核 + 实地审核）。
     *
     * <p>对应环节③的<b>第二段</b>与环节②验车确权：核验采购资料与凭证、
     * 实地盘点核对车辆、确认权属无重复融资。
     * 用户口述的"审核人员的资料和实地审核"两个动作合并在本岗——
     * 两者都是"对录入内容做独立确认"，业务连续性强，且在中小团队里
     * 本就常由同一人承担；拆成两岗会让流程空转。</p>
     *
     * <p><b>与录入岗不相容</b>：录入的人不能审自己的单。</p>
     */
    PURCHASE_REVIEWER("采购审核岗", RoleDomain.PURCHASE,
            set(VEHICLE_VERIFY, INVENTORY_AUDIT, ATTACHMENT_UPLOAD, BANK_ACCOUNT_CONTROL)),

    /**
     * 业务经办岗（垫款申请人）。
     *
     * <p>对应环节③的<b>第三段</b>：核对采购订单无误后发起垫款申请，
     * 占用额度、落 PENDING 指令。也就是用户说的"相关负责人角色发起垫款申请"。
     * <b>不碰支付通道</b>——申请人只能发起，不能放款。</p>
     */
    BUSINESS_OPERATOR("业务经办岗", RoleDomain.FUNDING,
            set(ADVANCE_APPLY)),

    /**
     * 资金复核岗。
     *
     * <p>对应环节③的<b>第四段</b>：在打款前复核放款要素——
     * 收款方是谁（DIRECT_PAY 代付上游 / SELF_PAY 回款经销商）、收款账户是否有效、
     * 金额与垫款比例、委托付款协议是否已签（DIRECT_PAY 场景）。
     * 复核岗<b>只签字不划款</b>。</p>
     */
    FUNDING_REVIEWER("资金复核岗", RoleDomain.FUNDING,
            set(ADVANCE_REVIEW)),

    /**
     * 资金出纳岗（打款支付）。
     *
     * <p>对应环节③的<b>第五段</b>：向支付通道提交放款指令，把垫款推到 PROCESSING。
     * 支付结果由银行 webhook 回写确认，出纳岗<b>无法自行确认付款成功</b>
     * （/confirm、/fail 仅接受 HMAC 签名的通道回调）。</p>
     */
    FUNDING_CASHIER("资金出纳岗", RoleDomain.FUNDING,
            set(ADVANCE_DISBURSE)),

    /**
     * 担保管理岗。
     *
     * <p>对应环节④担保设定与解除：日本渠道不扣钥匙、不装 GPS，
     * 担保靠所有权保留/让与担保/厂家回购，登记与解除登记是本岗职责。</p>
     */
    COLLATERAL_OFFICER("担保管理岗", RoleDomain.POST_LENDING,
            set(SECURITY_MAINTAIN, COLLATERAL_MAINTAIN)),

    /**
     * 贷后管理岗。
     *
     * <p>对应环节⑤在库计息与动态贷后监管：盯库龄、盯残值、盯集中度，
     * 处理风险告警，做盘点差异升级。
     * 对应车辆状态 FINANCED/IN_STOCK 阶段的主要责任岗。</p>
     */
    POST_LENDING_MANAGER("贷后管理岗", RoleDomain.POST_LENDING,
            set(RISK_HANDLE, INVENTORY_AUDIT, COLLATERAL_RELEASE_RULE)),

    /**
     * 催收岗。
     *
     * <p>对应环节⑥库龄递减还款与逾期催收：跟进递减计划到期、核销部分本金、
     * 逾期上门催收。对应车辆状态 SOLD_PENDING/OVERDUE 阶段的主要责任岗。</p>
     */
    COLLECTION_OFFICER("催收岗", RoleDomain.POST_LENDING,
            set(CURTAILMENT_EXECUTE, REPAYMENT_EXECUTE)),

    /**
     * 结算岗。
     *
     * <p>对应环节⑦零售赎车、⑧解押出库、⑨额度恢复与结清：
     * 受理终端客户还款、核销、出具结清证明、触发担保解除与出库指令。
     * 对应车辆状态 SETTLED 阶段的责任岗。</p>
     */
    SETTLEMENT_OFFICER("结算岗", RoleDomain.POST_LENDING,
            set(VEHICLE_SOLD, REPAYMENT_EXECUTE, PAYOFF_ISSUE, SECURITY_MAINTAIN)),

    /**
     * 处置岗。
     *
     * <p>对应环节⑩违约处置与脱信销售：开单、收车、变现、损失认定、结案。
     * 对应车辆状态 DISPOSING/CLOSED 阶段的责任岗。</p>
     */
    DISPOSAL_OFFICER("违约处置岗", RoleDomain.POST_LENDING,
            set(DISPOSAL_EXECUTE)),

    /**
     * 增信业务岗。
     *
     * <p>对应 P1 系列：保险绑定与理赔、厂家回购兑现、展期续贷。
     * 这些动作跨状态发生（可能在库、也可能逾期），单设岗位便于专项管理。</p>
     */
    RISK_ADVISORY_OFFICER("增信业务岗", RoleDomain.POST_LENDING,
            set(INSURANCE_REPURCHASE)),

    /* ---- 以下为兼容性角色，保留以承接既有数据与旧令牌 ---- */

    /**
     * 通用只读岗（历史 {@code USER} 账号）。
     *
     * <p>存在原因：V5/V6 迁移里已种下 {@code operator}（role=USER），
     * 且早期令牌签发逻辑写的是单数 {@code role} 字段。
     * 该角色<b>不持有任何业务写权限</b>，仅保留登录与查询能力，
     * 供审计与回归测试使用。生产上不应新建此角色。</p>
     */
    READONLY_USER("通用只读岗", RoleDomain.PLATFORM, Set.of());

    /** 角色所属业务域。 */
    public enum RoleDomain {
        /** 平台与系统管理。 */
        PLATFORM("平台管理"),
        /** 授信与风险定价。 */
        CREDIT("授信风控"),
        /** 采购与上游准入。 */
        PURCHASE("采购准入"),
        /** 放款与资金。 */
        FUNDING("放款资金"),
        /** 贷后与处置。 */
        POST_LENDING("贷后处置");

        private final String label;

        RoleDomain(String label) {
            this.label = label;
        }

        public String getLabel() {
            return label;
        }
    }

    private final String label;
    private final RoleDomain domain;
    private final Set<Permission> permissions;

    UserRole(String label, RoleDomain domain, Set<Permission> permissions) {
        this.label = label;
        this.domain = domain;
        this.permissions = permissions;
    }

    /** 角色中文名。 */
    public String getLabel() {
        return label;
    }

    /** 所属业务域。 */
    public RoleDomain getDomain() {
        return domain;
    }

    /** 该角色拥有的权限点集合。 */
    public Set<Permission> getPermissions() {
        return permissions;
    }

    /**
     * 判断该角色是否拥有指定权限点。
     *
     * @param permission 权限点
     * @return true 表示拥有
     */
    public boolean can(Permission permission) {
        return permission != null && permissions.contains(permission);
    }

    /**
     * 该角色是否允许执行写操作（持有至少一个权限点）。
     *
     * <p>用于前端"是否显示操作按钮"的粗判，以及管理员账号的自我保护提示。</p>
     *
     * @return true 表示可写
     */
    public boolean isWriter() {
        return !permissions.isEmpty();
    }
}

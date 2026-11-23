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
                    INTEGRATION_AUDIT_READ,
                    // USED 监管方主数据：custodians 的通道能力维护是主数据动作，
                    // 本仓只有本岗持主数据写权（DEALER_MAINTAIN／SUPPLIER_MAINTAIN 同族），
                    // 故落此处。它不构成放车链闭环——下发与撤销都不在本岗。
                    CUSTODIAN_MAINTAIN)),

    /**
     * 授信审查岗：准入与评级。
     *
     * <p>对应环节①授信准入与环节⑪额度年审。新建授信额度、录入征信结论、
     * 给出评级建议、执行年审复核。
     * <b>不碰打款</b>——额度批了不等于钱可以出去。</p>
     *
     * <p><b>USED R1 的角色映射（D00 §11）</b>：该清单把资质维护授予「业务运营、风控」、
     * 双维评分授予风控侧。本仓 {@code UserRole} 里没有"业务运营"与"合规"两个岗位，
     * 风控侧能对上的是本岗（已持 {@code RISK_GRADE_ADJUST}／{@code ANNUAL_REVIEW}），
     * 因此资质维护、评分、准入重算三点授予本岗；
     * <b>{@code DEALER_COMPLIANCE_CHECK} 不授予本岗</b>——"合规"是独立岗位，
     * 把它并进授信审查岗会让核查录入与授信判定同源，违背 P-7 的分离意图。
     * 是否新增 {@code COMPLIANCE_OFFICER} 角色属权限矩阵（D10）的裁决，本包不擅自加角色。</p>
     */
    CREDIT_REVIEWER("授信审查岗", RoleDomain.CREDIT,
            set(CREDIT_MANAGE, ANNUAL_REVIEW, RISK_GRADE_ADJUST,
                    DEALER_QUALIFICATION_MAINTAIN, DEALER_SCORE_SET, DEALER_ADMISSION_MANAGE,
                    // USED 读侧：DEALER_READ 是 D07 §7.1 三行 GET 的定源点名；
                    // USED_VEHICLE_READ／USED_COLLATERAL_READ 给本岗是为了让授信判定看得到
                    // 车辆与担保的输入事实（只读，不构成写侧兼岗）。授予理由见
                    // Permission.DEALER_READ 注释。本岗仍不给 DEALER_COMPLIANCE_CHECK（P-7）。
                    DEALER_READ, USED_VEHICLE_READ, USED_COLLATERAL_READ)),

    /**
     * 采购数据录入岗（人工导入）。
     *
     * <p>对应环节③的<b>第一段</b>：把经销商或渠道发来的采购清单（Excel/CSV）
     * 导入系统，登记上游主体与收款账户，生成"待审核"的采购订单。
     * 这是<b>纯录入岗</b>：只负责把数据搬进来并保证格式与主数据齐全，
     * <b>不做实质审核</b>（那是审核岗的事），更不能发起放款。</p>
     */
    PURCHASE_OPERATOR("采购数据录入岗", RoleDomain.PURCHASE,
            set(PURCHASE_IMPORT, ATTACHMENT_UPLOAD, BANK_ACCOUNT_MAINTAIN,
                    // USED R2 建账与属性录入属"把已发生的事实搬进系统"，与本岗既有定位一致；
                    // 里程/修復歴的取严判定不在本岗手上（走车况票与观测接口，由服务层判来源优先级）。
                    USED_VEHICLE_MAINTAIN, USED_VEHICLE_READ,
                    // W-U2：估值录入 = 只录事实源（落札价/评估价/行情价），与"人工覆盖"分岗（D10 D-4）。
                    // 本岗刻意不持 VALUATION_OVERRIDE（覆盖在 RISK_ADVISORY_OFFICER），也不持
                    // LTV_POLICY_MANAGE/APPROVE——两个都不给录入侧，闭环在本岗上组不出来。
                    VALUATION_RECORD, AUCTION_LOT_IMPORT)),

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
            set(VEHICLE_VERIFY, INVENTORY_AUDIT, ATTACHMENT_UPLOAD, BANK_ACCOUNT_CONTROL,
                    // 只给读：审核岗要核車検証与车况票事实，但建账写入留给录入岗，
                    // 否则"自己建账自己审核"在本岗内部就成立了（同 P-7 的分离意图）。
                    USED_VEHICLE_READ, CERT_READ)),

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
            set(ADVANCE_DISBURSE,
                    // USED R7：下发是「把指令交给对方机构」的对外动作，与本岗既有的
                    // 「向支付通道提交放款」同构（都是只发不做业务判定），因此归出纳；
                    // 建单 RELEASE_ORDER_ISSUE 在结算岗，两岗分开 = 建单的人不能自己发。
                    // 下发时逐项重校验（D07 §7.6），"建单时过过闸门"不作数（红线 1）。
                    RELEASE_ORDER_DISPATCH)),

    /**
     * 担保管理岗。
     *
     * <p>对应环节④担保设定与解除：日本渠道不扣钥匙、不装 GPS，
     * 担保靠所有权保留/让与担保/厂家回购，登记与解除登记是本岗职责。</p>
     */
    COLLATERAL_OFFICER("担保管理岗", RoleDomain.POST_LENDING,
            set(SECURITY_MAINTAIN, COLLATERAL_MAINTAIN,
                    // R5 收证登记（只到"收讫"，不含设质/交还/灭失，那些归 W-U4）；
                    // USED_COLLATERAL_READ 是纯只读投影（ADR-Q27：权威源不可被本包改写）。
                    CERTIFICATE_RECORD, CERT_READ, USED_COLLATERAL_READ,
                    // W-U3 集成运维（D10 §3 `:125` 的 SECURITY_MAINTAIN 行 ●）。
                    // 【角色映射裁决】D10 §3 用的是矩阵自己的角色名，本仓没有同名 UserRole；
                    // 该行标注为「既有高危角色」，而本仓唯一持 SECURITY_MAINTAIN 权限点的岗
                    // 就是本岗（另一持有者 SETTLEMENT_OFFICER 在下文按 RELEASE_OFFICER ○ 处理），
                    // 故 ● 落在此处。
                    // 【D-12】PARTNER_CONFIG_MANAGE 不给本岗——能停 partner 的人不能再握轮换权，
                    // 与 SYSTEM_ADMIN 的分配恰好互补（那边有 MANAGE 无 ROTATE），
                    // 两组权限点在角色层面就不闭环。
                    // 【D10 §3 同行的另一条】"✘ 不得代填 partner 回执"本岗天然满足：
                    // RELEASE_RECEIPT_MANUAL 不在本岗权限集里。
                    PARTNER_KEY_ROTATE, INTEGRATION_MONITOR, INTEGRATION_RETRY_TRIGGER,
                    INTEGRATION_AUDIT_READ,
                    // ---- USED R5.6 担保与登记侧写权（本轮补授） ----
                    // 設質生效是放款前置闸门，与本岗既有的 CERTIFICATE_RECORD（收证）同属
                    // 担保管理动作；<b>交还</b> CERTIFICATE_RELEASE 刻意不给本岗（SOD 对 D-6：
                    // 押印未解先放证 = 担保物失控），落在 SETTLEMENT_OFFICER。
                    CERTIFICATE_PLEDGE, REGISTRATION_APPLY,
                    // 挂失申报要求「二人」，因此必须至少两岗持有，否则二人只能靠同岗换人，
                    // 而本仓的换人校验是跨角色断言（RoleSeparationPolicy）。
                    CERTIFICATE_LOST_DECLARE)),

    /**
     * 贷后管理岗。
     *
     * <p>对应环节⑤在库计息与动态贷后监管：盯库龄、盯残值、盯集中度，
     * 处理风险告警，做盘点差异升级。
     * 对应车辆状态 FINANCED/IN_STOCK 阶段的主要责任岗。</p>
     */
    POST_LENDING_MANAGER("贷后管理岗", RoleDomain.POST_LENDING,
            set(RISK_HANDLE, INVENTORY_AUDIT, COLLATERAL_RELEASE_RULE,
                    // 贷后监管要看车辆 USED 属性、証書状态与担保多源事实（全读，无写）。
                    USED_VEHICLE_READ, CERT_READ, USED_COLLATERAL_READ,
                    // W-U3：D10 §3 `:121` 的 WAREHOUSE_RECON 账实内勤在集成运维组是 ○（仅只读），
                    // 映射到本岗的理由是「仓与对账组 ● + 差异盯办」正对应本岗的 INVENTORY_AUDIT/RISK_HANDLE。
                    // ○ 的落法＝只给 INTEGRATION_MONITOR（看链路健康），
                    // 不给 INTEGRATION_RETRY_TRIGGER（重投会让对方二次收到指令）。
                    INTEGRATION_MONITOR,
                    // ---- USED R5.6/R6/R7 的「确认与盯办」侧（本轮补授） ----
                    // 三条都是对别人发起动作的独立确认位，与发起岗刻意分家：
                    //   REGISTRATION_CONFIRM ↔ REGISTRATION_APPLY（SOD 对 D-2）
                    //   RELEASE_ORDER_CANCEL ↔ RELEASE_ORDER_ISSUE（SOD 对 D-8）
                    //   CERTIFICATE_REISSUE_APPROVE 与收证/设质分离（审批位 ≠ 执行位）
                    REGISTRATION_CONFIRM, RELEASE_ORDER_CANCEL, CERTIFICATE_REISSUE_APPROVE,
                    CERTIFICATE_LOST_DECLARE,
                    // 快照受理与差异认定归账实内勤（D10 §3 的 WAREHOUSE_RECON 行映射到本岗，
                    // 理由同上面 INTEGRATION_MONITOR 那段）。RECON_IGNORE <b>不给本岗</b>
                    // （SOD 对 D-7：认定与忽略同人 = 账实断裂可被一人抹平）。
                    WMS_SNAPSHOT_IMPORT, RECON_ACK)),

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
            set(VEHICLE_SOLD, REPAYMENT_EXECUTE, PAYOFF_ISSUE, SECURITY_MAINTAIN,
                    // W-U2：结算岗对拍卖结算单做分摊守恒复核（AUC_005 的处置人）
                    SETTLEMENT_RECONCILE,
                    // W-U3：D10 §3 `:122` 的 RELEASE_OFFICER 放车放证内勤 → 本岗（唯一持
                    // VEHICLE_SOLD/PAYOFF_ISSUE 的放车放证侧岗位）。集成运维组是 ○（仅只读），
                    // 因此只给 INTEGRATION_MONITOR 看指令下发回执有没有回来，
                    // 不给 redeliver/replay——放车岗自己重投自己等的回执等于自证到账。
                    // 本岗虽也持 SECURITY_MAINTAIN 权限点，但<b>不</b>并入 D10 的 SECURITY_MAINTAIN
                    // 高危角色那一行（那一行只映射 COLLATERAL_OFFICER，理由见该处注释），
                    // 所以拿不到 PARTNER_KEY_ROTATE。
                    INTEGRATION_MONITOR,
                    // ---- USED R7 放车放证主链（本轮补授） ----
                    // 本岗 = D10 §3 的 RELEASE_OFFICER（放车放证内勤）：建单、放证、
                    // 降级代录三件都在它手上，但<b>结清推进 RELEASE_SETTLE 不授任何角色</b>
                    // （见 Permission 该点注释），所以下面这条链在本岗内部组不成闭环：
                    // 代录回执只能落 INTERNAL_WITNESS 事实，推不到 SETTLED、不回补额度。
                    RELEASE_ORDER_ISSUE, CERTIFICATE_RELEASE, RELEASE_RECEIPT_MANUAL,
                    // 忽略账实差异与「认定差异成立」换人（SOD 对 D-7，持有者在贷后管理岗）。
                    RECON_IGNORE)),

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
            set(INSURANCE_REPURCHASE,
                    // W-U2：人工覆盖与政策批准给增信岗——它不持有 VALUATION_RECORD / LTV_POLICY_MANAGE，
                    // 因此 D-4（录入↔覆盖）、D-5（维护↔批准）两组在本角色上都不构成同人闭环。
                    VALUATION_OVERRIDE, LTV_POLICY_APPROVE)),

    /* ---- 以下为兼容性角色，保留以承接既有数据与旧令牌 ---- */

    /**
     * 通用只读岗（历史 {@code USER} 账号）。
     *
     * <p>存在原因：V5/V6 迁移里已种下 {@code operator}（role=USER），
     * 且早期令牌签发逻辑写的是单数 {@code role} 字段。
     * 该角色<b>不持有任何业务写权限</b>，仅保留登录与查询能力，
     * 供审计与回归测试使用。生产上不应新建此角色。</p>
     *
     * <p><b>W-U3 集成运维（D10 §3 `:127` 的 AUDITOR 行）</b>：D10 的矩阵角色
     * {@code AUDITOR 审计（只读）} 在本仓没有同名 {@code UserRole}，本岗是唯一
     * 「只读 + 供审计使用」定位对得上的岗位，因此按 D10 该行只落
     * {@code INTEGRATION_AUDIT_READ} 一个点——留痕视图（{@code call_logs} 摘要）
     * 是 D10 §2 标的 L 级读点，不含密钥材料与报文全文（V74 列面里就没有）。
     * 类注释里"不持有任何业务写权限"的不变式<b>不被本行破坏</b>：本点是读点。
     * ⚠ 残留风险已登记实现说明：{@code JwtAuthInterceptor#parseRoles} 把旧令牌
     * {@code role=USER} 映射到本岗，所以<b>历史 USER 令牌</b>会一并拿到留痕读取权。</p>
     */
    READONLY_USER("通用只读岗", RoleDomain.PLATFORM, set(INTEGRATION_AUDIT_READ));

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
        // 读权限点走隐式层：D07 §7.2·§7.4 给读接口点了名，但本仓库口径一直是「查询不设权限点」
        // （见 Permission 类注释），逐角色铺读权限的矩阵属 D10 未签字部分，不猜。
        // 因此读点在此登记、在此统一放行；写点仍必须逐角色显式授予。
        return permission != null
                && (permissions.contains(permission) || READ_TIER.contains(permission));
    }

    /** 隐式读层：登录即可读，与既有查询接口行为一致。 */
    private static final Set<Permission> READ_TIER = set(
            VALUATION_READ, LTV_POLICY_READ, AUCTION_HOUSE_READ, AUCTION_LOT_READ,
            // USED R6/R7 的两个纯读点同层：放车放证查询（含证据链视图）与账实差异查询
            // 都不改任何状态，按本仓「查询不设权限点」的口径统一放行；
            // 差异的<b>认定</b>与<b>忽略</b>仍是显式写点（RECON_ACK／RECON_IGNORE，分属两岗）。
            RELEASE_READ, RECON_READ);

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

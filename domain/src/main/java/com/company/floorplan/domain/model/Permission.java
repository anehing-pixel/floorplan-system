package com.company.floorplan.domain.model;

import java.util.Set;

/**
 * 权限点枚举（原子操作粒度）。
 *
 * <p><b>为什么权限点与角色分开两层</b>：角色是"岗位"，会随组织调整而变；
 * 权限点是"动作"，由接口决定、相对稳定。若把动作直接挂在角色上，
 * 每新增一个接口就得改角色定义，权限矩阵会迅速腐化。
 * 现在是：<b>接口声明需要哪个权限点 → 角色声明自己拥有哪些权限点</b>，
 * 新增接口时只动接口一侧，角色侧只在新增岗位时才动。</p>
 *
 * <p><b>只覆盖写操作，查询不设权限点</b>：读接口对所有已登录用户开放
 * （风控岗要能看全量台账、催收岗要能看车辆档案）。这是本项目的一贯口径——
 * {@code RequireAdmin} 当初拦的就是"写"，不是"看"。</p>
 *
 * <p><b>命名约定</b>：{@code 域名_动作}，动作取自业务动词（导入/审核/发起/复核/支付/登记/盘点/催收/处置/回补）。
 * 同一动作在两个域名下含义不同就分开成两个权限点，避免"看起来一样其实不是"。</p>
 */
public enum Permission {

    /* ---- 系统与主数据域 ---- */

    /** 经销商主数据维护：新增/编辑/删除/调级。 */
    DEALER_MAINTAIN("经销商主数据维护"),

    /**
     * 上游主体准入：新增/编辑供应商、变更其状态与担保能力。
     * <p>这是<b>管理决策</b>而非导入动作——谁能成为上游、代付给谁、
     * 有无厂家回购能力，都属于准入判断，故归平台管理域，
     * 不与"录入岗"混同（录入岗只负责把已准入的上游数据搬进来）。</p>
     */
    SUPPLIER_MAINTAIN("上游主体准入管理"),

    /**
     * 收款账户录入：登记上游/经销商的收款账户（明文账号只在录入瞬间存在）。
     * <p>属导入流程的配套动作，归采购录入岗。</p>
     */
    BANK_ACCOUNT_MAINTAIN("收款账户录入"),

    /**
     * 收款账户管控：冻结/关闭/恢复账户。
     * <p>属风险动作——账户被冻结意味着钱打不出去，
     * 由审核岗（复核账户有效性）承担，与录入岗分离。</p>
     */
    BANK_ACCOUNT_CONTROL("收款账户冻结与恢复"),

    /** 系统配置维护：评级口径等动态配置项。 */
    CONFIG_MAINTAIN("系统配置维护"),

    /** 批量作业手工触发（批处理通常由调度驱动，此处留给运维手工重跑）。 */
    JOB_TRIGGER("批量作业手工触发"),

    /* ---- 授信与风控域 ---- */

    /**
     * 授信额度建账：新建循环授信额度、调整额度、冻结与解冻。
     * <p>属准入决策，归授信审查岗。</p>
     */
    CREDIT_MANAGE("授信额度建账与调整"),

    /** 授信年审执行：年审发起与复核、额度冻结恢复。 */
    ANNUAL_REVIEW("额度年审与冻结恢复"),

    /* ---- 采购导入域（ADR-007 决策四/八） ---- */

    /**
     * 采购清单导入：上传 Excel/CSV，预校验并落库。
     * <p>注意：导入只产生"待审核"的采购订单，<b>不等于放款授权</b>——
     * 放款另有独立权限点，二者不得由同一角色同时持有（见 UserRole 的不相容说明）。</p>
     */
    PURCHASE_IMPORT("采购清单导入"),

    /** 原始凭证上传（合同、发票、行驶单等），走对象存储直传。 */
    ATTACHMENT_UPLOAD("原始凭证上传"),

    /* ---- 验车确权与实地审核域（环节②） ---- */

    /**
     * 验车确权：到货核对车辆物理信息 + 权属校验（重复融资/查封拦截）。
     * <p>对应"资料审核 + 实地审核"合并后的执行角色。</p>
     */
    VEHICLE_VERIFY("验车确权与权属核验"),

    /** 实地盘点：到经销商场地逐台扫 VIN、拍照留证、账实比对。 */
    INVENTORY_AUDIT("实地盘点核查"),

    /* ---- 垫款与资金域（环节③，ADR-006 职责分离） ---- */

    /** 垫款申请：落 PENDING 指令、占用额度。申请人。 */
    ADVANCE_APPLY("垫款申请"),

    /** 垫款复核：审核放款要素（收款方、金额、账户有效性），不触达支付通道。 */
    ADVANCE_REVIEW("垫款复核"),

    /** 资金支付：向支付通道提交放款指令。支付人。 */
    ADVANCE_DISBURSE("资金支付执行"),

    /* ---- 担保与监管域（环节④⑤） ---- */

    /** 担保设定与解除登记。 */
    SECURITY_MAINTAIN("担保设定与解除"),

    /** 风险处置：告警降级、风险复核、冻结建议。 */
    RISK_HANDLE("风险告警处置"),

    /* ---- 贷后与还款域（环节⑥⑦⑨） ---- */

    /**
     * 售出标记：把车辆标记为已零售成交，进入结算窗口倒计时。
     * <p>这是"售出即还"机制的起点——标记后开始计算赎车窗口，
     * 超期未还即升级为 OOT（脱信销售）。属结算岗动作，
     * 与"还款核销"分开：前者是发起，后者是收到钱。</p>
     */
    VEHICLE_SOLD("车辆售出标记"),

    /** 库龄递减还款核销、催收跟进。 */
    CURTAILMENT_EXECUTE("库龄递减核销与催收"),

    /** 还款受理：整台/部分还款核销、额度回补。 */
    REPAYMENT_EXECUTE("还款核销与额度回补"),

    /** 结清证明出具。 */
    PAYOFF_ISSUE("结清证明出具"),

    /** 展期与保险、厂家回购等增信业务办理。 */
    INSURANCE_REPURCHASE("保险·回购·展期办理"),

    /* ---- 违约处置域（环节⑩） ---- */

    /** 违约处置：开单、收车、变现、损失认定、结案。 */
    DISPOSAL_EXECUTE("违约处置与脱信变现"),

    /* ---- 担保物台账域（V10） ---- */

    /**
     * 担保物台账查询与登记维护。
     * <p>含担保登记（法务省登记编号）、登记事项证明书归档、抹消登记推进。</p>
     */
    COLLATERAL_MAINTAIN("担保登记与抹消维护"),

    /**
     * 库存释放规则：指定回款账户 / 核验回款落点。
     * <p>与 {@link #COLLATERAL_MAINTAIN} 分开是因为这是<b>资金管控</b>动作
     * （决定回款必须进哪个账户），不是文书维护动作。</p>
     */
    COLLATERAL_RELEASE_RULE("回款账户指定与落点核验"),

    /* ---- 评级域 ---- */

    /** 经销商风险评级人工调整（评级唯一写入口）。 */
    RISK_GRADE_ADJUST("风险评级调整"),

    /* ---- USED 域 · 车商资质（R1；权限点定源 D00 §11，端点映射定源 D07 §7.1） ---- */

    /**
     * 车商资质／許可／会员档案维护（D00 §11 授予「业务运营、风控」）。
     * <p>覆盖 {@code POST /api/dealers/{id}/licenses} 与
     * {@code POST /api/dealers/{id}/auction-memberships}。</p>
     */
    DEALER_QUALIFICATION_MAINTAIN("车商资质与许可会员维护"),

    /**
     * 反社／AML 核查录入与结论（D00 §11 授予「合规」）。
     * <p>D07 §7.1 把这一行的权限点写成 {@code COMPLIANCE_CHECK_DONE}——那是
     * D00 §14 的<b>审计动作名</b>（D04 §3.1/§3.3 的 {@code verified_by}/{@code checked_by}
     * 注释都指向它），不是权限点。本包按定源清单 D00 §11 用本权限点，
     * 差异已登记为文档冲突。</p>
     */
    DEALER_COMPLIANCE_CHECK("合规核查录入与结论"),

    /**
     * 双维评分录入（D07 §7.1 的 {@code scores} 行，标「二人」）。
     * <p><b>D00 §11 未列本权限点</b>（该清单里没有任何 R1.3 双维评级的点），
     * 取自 D07 §7.1 的接口口径；需收口方在 D00 §11 补登记。
     * "二人"不由权限点表达，而由 {@code RoleSeparationPolicy.assertNoSelfApproval}
     * 在确认环节校验（同人回 {@code AUTH_003}）。</p>
     */
    DEALER_SCORE_SET("车商双维评分录入"),

    /**
     * 准入态重算与人工触发（D07 §7.1 的 {@code admission/recalculate} 行）。
     * <p><b>D00 §11 未列本权限点</b>，同 {@link #DEALER_SCORE_SET} 的处理。
     * 注意重算是无副作用的推导＋快照写入，<b>没有任何"手工设为 ADMITTED"的入口</b>，
     * 因此本权限点不构成旁路（Q21 红线）。</p>
     */
    DEALER_ADMISSION_MANAGE("车商准入态重算"),

    /* ---- USED 域 · 本轮（串行收尾轮）新增：读权限点 + R2/R5 读写点 ---- */

    /**
     * 车商资质档案读取（D07 §7.1 三行 GET 的权限点原文）。
     *
     * <p><b>为什么本轮开始给读接口标点</b>：本枚举类注释的仓内约定是「查询不设权限点」，
     * 上一轮因此没标（登记为实现说明 §4-④）。收尾轮的要求改成
     * 「每个端点都必须标 {@code @RequirePermission}」，而 D07 §7.1 已经给了这三行的
     * 权限点名字，所以照抄定源名，不自造。
     * 授予清单见 {@link UserRole}：只授 {@code SYSTEM_ADMIN} 与 {@code CREDIT_REVIEWER}
     * （后者是本仓里唯一对得上「风控侧」的岗，D00 §11 把资质与准入判定的读权给风控），
     * <b>没有</b>授给采购录入／经办岗：准入结论是授信侧的输出，
     * 让申请岗自查准入等于把 P-7 的分离再合并回去。</p>
     */
    DEALER_READ("车商资质与准入态读取"),

    /**
     * USED 车辆主数据维护（建账／属性更正／车况票录入／里程观测登记）。
     * <p><b>[待定源 U-13 权限点]</b>：D00 §11 的定源清单里没有任何 R2 车辆侧的点，
     * D07 §7 也没有 R2 端点小节（D13 §5.2 R8 把它列为开放项）。本点名按
     * 「域名_动作」约定取，端点映射由收尾轮指令指定。
     * 需收口方在 D00 §11 补登记后才能算定源；改名只需改本常量与两处注解，无逻辑耦合。</p>
     */
    USED_VEHICLE_MAINTAIN("USED 车辆主数据维护"),

    /**
     * USED 车辆主数据读取（属性视图／车况票列表）。
     * <p>同 {@link #USED_VEHICLE_MAINTAIN} 的 [待定源 U-13] 处置。</p>
     */
    USED_VEHICLE_READ("USED 车辆主数据读取"),

    /**
     * 車検証收证登记（R5 的「收证」一侧）。
     * <p>名字取自 D07 §7.3 第一行（{@code POST /api/certificates} 的权限点），
     * 那一份规格归 W-U4；本轮按收尾轮指令在 W-U1 的 V52 表上开<b>收证与查询</b>口，
     * <b>不做</b>质押设定／交还／灭失申告／补行认定（D12 W-U1「不做」行 + U-02 可延）。
     * 与 W-U4 的 {@code /api/certificates*} 存在能力重叠，已登记为跨包冲突项。</p>
     */
    CERTIFICATE_RECORD("車検証收证登记"),

    /** 車検証与保管事件读取（D07 §7.3 的 {@code CERT_READ} 行，同一处置）。 */
    CERT_READ("車検証与保管事件读取"),

    /**
     * 担保多源投影只读（V54 的权威源／顺位／瑕疵结论）。
     * <p>本点<b>只覆盖读</b>：担保权属的唯一权威源是
     * {@code collateral_registration_batches}（ADR-Q27），本包只做只读投影，
     * 不提供任何写入口。写作仍走 {@link #COLLATERAL_MAINTAIN} 的既有登记链路。</p>
     */
    USED_COLLATERAL_READ("担保多源投影只读"),

    /* ---- W-U2 多源审慎估值 / LTV 政策 / 拍卖接入（点名照 D07 §7.2·§7.4 与 D10 §2）---- */
    // 读接口在 D07 里也点了名（VALUATION_READ 等），但本仓库一贯口径是「查询不设权限点」，
    // 逐角色铺读权限属 D10 未签字部分。因此读点在此登记但由 UserRole#can 的隐式读层放行，
    // 写点（RECORD/OVERRIDE/MANAGE/APPROVE/IMPORT/RECONCILE）必须逐角色显式授予——
    // D-4（录入↔覆盖）与 D-5（维护↔批准）两组换人分离就靠"同一角色不同时持有这两点"落地。
    VALUATION_READ("估值查询"),
    VALUATION_RECORD("估值录入（仅事实源）"),
    VALUATION_OVERRIDE("估值人工覆盖（需第二人签认）"),
    LTV_POLICY_READ("LTV 政策查询与试算"),
    LTV_POLICY_MANAGE("LTV 政策与分档带维护"),
    LTV_POLICY_APPROVE("LTV 政策批准（与维护换人）"),
    AUCTION_HOUSE_READ("拍场档案查询"),
    AUCTION_HOUSE_MANAGE("拍场准入维护"),
    AUCTION_LOT_READ("落札明细查询"),
    AUCTION_LOT_IMPORT("落札数据接入"),
    SETTLEMENT_RECONCILE("拍卖结算对账与分摊确认"),

    /* ---- 外部合作方与集成运维域（R11，W-U3） ----
     *
     * <p><b>为什么单独成域</b>：partner 配置与密钥轮换是<b>接入面</b>的权限，
     * 与任何业务域的读写都不重合。尤其密钥轮换必须能从"改配置"里再分出来——
     * 能改 partner 档案的人不该自动拥有"换一把能验通对方报文的密钥"的能力，
     * 后者等价于给对方身份发证（D02 §5.6 / INV-4）。</p>
     *
     * <p><b>角色授予落在 {@link UserRole}</b>（串行第 3 棒补齐，D10 §3「集成运维组」列）。
     * 本域五点<b>全部不</b>进 {@code UserRole.READ_TIER} 隐式读层：
     * {@code INTEGRATION_MONITOR} / {@code INTEGRATION_AUDIT_READ} 虽然只是读，
     * 但读的是"谁在调我们、调了什么路径"，属接入面情报而不是业务台账，
     * 因此按 D10 §3 逐角色显式授予（●=全部、○=只给监控读点）。</p>
     */

    /**
     * partner 主数据维护：新增/改名/停用/恢复/改 scope 与 IP 白名单与限流阈值。
     * <p>不含改密钥的能力（见 {@link #PARTNER_KEY_ROTATE}）。</p>
     */
    PARTNER_CONFIG_MANAGE("合作方接入配置维护"),

    /**
     * 密钥轮换：写 {@code integration.partner_credentials} 的新版本行并推进缓存列。
     * <p>单独成点是因为它直接决定"谁能伪造通过验签"；每次调用都要落
     * {@code audit_events(PARTNER_SECRET_ROTATED)} 且操作人取 {@code AuditActor}（AGENTS §6.3）。</p>
     *
     * <p><b>D10 §4 D-12（密钥轮换 ↔ partner 停用）的落法</b>：本点<b>不</b>与
     * {@link #PARTNER_CONFIG_MANAGE} 授予同一角色，沿用本仓 D-4／D-5 两对既有做法
     * （「同一角色不同时持有这两点」，见 {@code UserRole} 里 W-U2 那段注释）。
     * 运行期"同一笔业务换人"的自然人校验仍归
     * {@code RoleSeparationPolicy.assertNoSelfApproval}。</p>
     */
    PARTNER_KEY_ROTATE("合作方密钥轮换"),

    /** 集成链路只读监控：收件箱、出站任务、调用留痕的列表与详情（只回摘要，不回全文）。 */
    INTEGRATION_MONITOR("集成链路监控查看"),

    /**
     * 人工重投/重放：收件箱 {@code redeliver}、出站 {@code DEAD -> PENDING}。
     * <p>与 {@link #INTEGRATION_MONITOR} 分开：看得到不等于能重发——
     * 重发会让对方收到第二次指令，是需要被单独审计的动作（D07 §7.8）。</p>
     */
    INTEGRATION_RETRY_TRIGGER("集成消息与任务人工重投"),

    /** 集成审计读取：按 traceId/partner 拉 {@code call_logs} 留痕（R12.2 的取数入口）。 */
    INTEGRATION_AUDIT_READ("集成调用留痕读取"),
    /**
     * 車検証质押生效——放款前置闸门（R5.1）。
     * <p>D10 §2 标 H 但<b>不要求二人</b>：其风险已被上游"实体交付证据"封住，
     * 再加二人只会诱发代签（D10 §2 表尾判断，评审可复核）。</p>
     */
    CERTIFICATE_PLEDGE("車検証质押生效"),
    /**
     * 車検証交还确认（放证）。
     * <p>与 {@link #CERTIFICATE_PLEDGE} 构成 SOD 对 D-6（押印未解先放证 = 担保物失控）；
     * 且只允许由权威回执链触发（D09 S9，红线 2）。</p>
     */
    CERTIFICATE_RELEASE("車検証交还确认"),
    /**
     * 車検証挂失申报：挂失后禁止一切放车放证（D09 C4）。
     * <p>要求二人（D10 §2）：单人可挂失 = 可用"挂失"掩盖証書已被放出的事实。</p>
     */
    CERTIFICATE_LOST_DECLARE("車検証挂失申报"),
    /** 换证 / 再発行审批：新証書成链（predecessor_id），旧証書置 REISSUED。 */
    CERTIFICATE_REISSUE_APPROVE("車検証换证与再发行"),
    /** 登记委托发起（移転登録 / 抵当権设定 / 抹消）。 */
    REGISTRATION_APPLY("登记委托发起"),
    /**
     * 登记结果确认。
     * <p>必须与 {@link #REGISTRATION_APPLY} 换人（SOD 对 D-2），且以入站证据为准：
     * 内部 confirm 只能推进到 {@code ACCEPTED_BY_AGENT}，不得伪造 {@code COMPLETED}
     * （D07 §7.3 + V69 的 chk_reg_completed_needs_inbox）。</p>
     */
    REGISTRATION_CONFIRM("登记结果确认"),
    /** 监管方（custodians）主数据与通道能力维护：USED 域首次有真实读写方。 */
    CUSTODIAN_MAINTAIN("监管方主数据与通道维护"),
    /** WMS 在库快照受理与差异重算（重算幂等：同快照同 biz_date 结果一致）。 */
    WMS_SNAPSHOT_IMPORT("WMS 快照受理与重算"),
    /** 账实差异查询（含双方快照摘要取证）。 */
    RECON_READ("账实差异查询"),
    /** 账实差异承认：认定差异成立并进入处置。 */
    RECON_ACK("账实差异承认"),
    /**
     * 账实差异忽略。
     * <p>与 {@link #RECON_ACK} 构成 SOD 对 D-7，且必须带理由（D09 §9、RB-3）：
     * 忽略无人复核 = 账实断裂被掩盖。</p>
     */
    RECON_IGNORE("账实差异忽略"),
    /**
     * 生成放车 / 放证指令（唯一有效创建入口，内含资金闸门）。
     * <p>风险 H 但单人：风险已被上游资金闸门 RELS_002 封住（D10 §2 表尾判断）。</p>
     */
    RELEASE_ORDER_ISSUE("放车放证指令生成"),
    /**
     * 指令下发：只能推进既有指令，不生成新指令（红线 1）。
     * <p>下发时逐项重校验（D07 §7.6 清单），"建单时查过闸门"不等于"下发时还能过"。</p>
     */
    RELEASE_ORDER_DISPATCH("放车放证指令下发"),
    /**
     * 指令撤销。
     * <p>与 {@link #RELEASE_ORDER_ISSUE} 构成 SOD 对 D-8；{@code ACKNOWLEDGED} 之后
     * 需 force_reason + 二人（D09 §5.4：撤销不回补也不扣减额度）。</p>
     */
    RELEASE_ORDER_CANCEL("放车放证指令撤销"),
    /**
     * 降级人工代录回执。
     * <p>authority 只能是 {@code INTERNAL_WITNESS}，永久区别于 partner 回执并打
     * {@code receipt_degraded}（D09 §7.1 / U-09）；与 {@link #RELEASE_SETTLE}
     * 构成 SOD 对 D-9——用代录把自己放的车洗成结清是红线 2 最直接的绕过形态。</p>
     */
    RELEASE_RECEIPT_MANUAL("回执人工代录"),
    /** 放车放证查询（含证据链视图）。 */
    RELEASE_READ("放车放证查询"),
    /**
     * 结清推进（台账结清 + 额度回补 + VEHICLE_SETTLED）。
     * <p>D07 §7.6 标「内部不开放，仅 dispatcher 触发」：本点是<b>存在性闸门</b>，
     * 刻意<b>不授予任何 UserRole</b>，因此任何令牌都拿不到它——只有回执链内部的
     * dispatcher 路径能推进。这是"内部不开放"的代码表达，不是漏配。</p>
     */
    RELEASE_SETTLE("结清推进（仅回执链内部触发）");

    private final String label;

    Permission(String label) {
        this.label = label;
    }

    /** 中文标签，用于前端按钮显隐提示与审计说明。 */
    public String getLabel() {
        return label;
    }

    /**
     * 权限点集合工具：便于枚举中以 {@code Set.of(...)} 声明角色权限。
     *
     * @param values 权限点
     * @return 不可变集合
     */
    public static Set<Permission> set(Permission... values) {
        return Set.of(values);
    }
}

# 二手车库存垫资改造 · D08 估值与 LTV 定价设计

> 上游：需求书 R3（估值/LTV/递减）、§3.4 链①（金额基准）、§3.4 链⑥（处置定价）；
> D00 §12（配置项）§8（枚举）、D03 §2.4/§3.5（不变式与聚合）、D04 §3.12–3.14（表）、D05 §3.3/§3.7（流程）。
> 本文是**全部金额口径的唯一出处**：D06 的用例、D09 的解押金额、D11 的断言均以本文为准，不各自定义公式。
> 开发暂缓；文中的公式、分档数值、单测用例是**设计规格 + 可评审样例**，不是已实现行为。

---

## 0. 文档控制

| 项 | 内容 |
|---|---|
| 编号 | DESIGN-USED-FLOORPLAN-D08 |
| 版本 | v0.1（草案） |
| 定源状态 | §4.3 分档示例、§7 递减参数、§8 处置折扣率**全部为建议默认值** `[待定源 D-05 / D-06 / U-07]`，签字前不得编码 |
| 单位约定 | 金额一律整数日元（`BIGINT`）；比率 `NUMERIC(5,4)`；比率中间计算用 `BigDecimal`，`RoundingMode.FLOOR`（可贷额）或 `CEILING`（应追额），逐处显式声明 |
| 取整通则 | **可贷上限向下取整、应追加/应还款额向上取整、处置底价向上取整到千円**。方向永远偏向保守侧，且必须写在方法名或注释里（评审可查） |

---

## 1. 本文要破除的现状（链①）

需求书 §3.4 链① 的证据与影响面：

| 落点 | 现状 | USED 域处置 |
|---|---|---|
| `V1:43 vehicle_units.invoice_amount` | 「厂家发票金额（必填）」，唯一金额入口 | 保留列与 LEGACY 行为；USED 域注释覆盖为「不得作为放款基准」（D04 §7） |
| `VehicleDraft.java:29-30` | 建账强校验 `invoice_amount > 0` | USED 域校验改为 `prudent_value > 0`；`invoice_amount` 在 USED 域允许为 0（拍卖场不出具厂家发票） |
| `AdvanceApplicationService.java:172-177` | `advanceAmount = invoiceAmount × 0.90` 硬编码 | **不修改该方法**；USED 域走新方法 `calcUsedAdvanceCeiling(...)`，由 `vehicle_type` 显式路由（域隔离见 D03 §1.2 聚合关系与 §6 包结构；原稿误引「D03 §1.3」，该节不存在，2026-11-22 D13 §4.4 WU2-05 修正） |
| `PurchaseOrderImportParser.java:52-60` | 导入模板固定列 `invoiceAmount` | 新增 USED 导入模板（落札明细驱动），旧模板保留给 LEGACY |
| `smoke-test.py:327` | 断言 `invoice×0.90` 公式 | **该断言不得改动**（LEGACY 回归基准）。USED 新增独立断言组，见 D11 §4 |
| 派生链 | `purchase_orders.total_amount` / `aging_plans.reduce_amount` / `loans.principal` / `credit_lines.used_amount` 全由 `invoice_amount` 派生 | USED 域全部改由 `prudent_value × LTV` 派生；派生源唯一化（§5） |

> **一处必须警惕的隐性污染**：`credit_lines.used_amount` 与 `vehicle_units.advance_amount` 的关系在 LEGACY 是
> 「放款额 = 发票×0.90」的单次写入。USED 域引入重估后，`advance_amount` 是**历史事实**（不随重估变化），
> 而 `prudent_value` 与敞口比率是**变化量**。两者混用会让额度回补算错。
> 因此 D04 §5.1 要求 `vehicle_units` 同时存 `prudent_value`（放款时点快照）与 `valuation_id`（当前有效估值指向），
> **禁止用 `valuation_id` 反查到的当前值去覆盖快照列**。

---

## 2. 定价基准四层

```
┌── 事实层（不可变）── won_price / auction grade / mileage / repair_history / inspection_expiry
│     来源 = I-01 落札推送或人工补录；唯一锚点 lot_key
├── 判断层（版本化）── prudent_value = PrudentValueCalculator(多源 → 审慎规则)
│     来源 = 落札价 / 第三方查定 / 相場快照 / 人工覆盖
├── 额度层（一次固化）── max_advance = FLOOR(prudent_value × LtvTierResolver.max_ratio)
│     来源 = ltv_policies + ltv_policy_bands（policy_code + version 快照）
└── 监控层（动态）  ── 实际 LTV = 剩余本金 / 最新 ACTIVE 估值 prudent_value
      突破 breach_threshold → 追加还款要求 topup_required_amount（不是重新放款依据）
```

| 层 | 可变性 | 谁写 | 谁读 | 审计要求 |
|---|---|---|---|---|
| 事实层 | **只增不改** | 集成消费 / 人工补录（须标 `source_message_id IS NULL`） | 判断层 | 补录须二人 |
| 判断层 | 每次重估新增一行，旧行 `EXPIRED` | `valuation` 上下文 | 额度层、监控层、处置 | `valuation_inputs` 保留全部输入 |
| 额度层 | 放款时一次固化，**永不重算** | 放款用例 | 台账、报表 | 快照 `ratio_snapshot` + `policy_version` |
| 监控层 | 每日/每次重估刷新 | 作业 | 告警、工单、处置 | 只写告警与 `topup_required_amount`，不改 `advance_amount` |

> 「额度层永不重算」是关键取舍：重估后敞口变大**不追回、不缩额**，只走监控层的追加还款机制。
> 理由：放款是已发生的资金事实，改历史放款额会让利息、对账、审计三处全部失去可复现性。

---

## 3. 估值（R3.1）

### 3.1 估值源与语义

| `ValuationSource` | 数据 | 权威度 | 可否作 `ADVANCE_BASE` | 缺失时的处理 |
|---|---|---|---|---|
| `AUCTION_WON_PRICE` | 场内真实成交价 | 最高（有支付事实） | ✔ | 非拍卖场来源（相谈/TRADE_IN）→ 该项不参与 MIN 集合 |
| `THIRD_PARTY_APPRAISAL` | 第三方查定书 | 高（独立） | ✔ | 要求提供但**允许宽限窗口** `[D-05]`：宽限期内可用「两源 MIN」，超期未补则估值降级为 `MANUAL_OVERRIDE` 并需二人 |
| `MARKET_MATRIX` | 行情快照 `valuation_market_snapshots` | 中（同规格批量） | ✔ | 用前一交易日快照；超过 `[D-05]` 天数则视为缺失 |
| `MANUAL_OVERRIDE` | 人工覆盖值 | 低（人） | ✔ 但**必须二人签认** | — |
| `INTERNAL_DEGRADED` | 系统内部推算兜底 | **不可用** | ✘（D03 I3） | 只产生监控层结论；申请一律 `VALU_003` 拒付 |

`ValuationPurpose` 三值的作用域：

| purpose | 用途 | 有效要求 | 失败后果 |
|---|---|---|---|
| `ADVANCE_BASE` | 放款基准 | 全部闸门 + 至少 2 个可用源（`[D-05]`） | 拒付，无绕过 |
| `REVALUATION` | 在库监控 | 允许单源；过期则触发重估作业 | 告警 + 追加还款判定用最新值 |
| `DISPOSAL_FLOOR` | 处置定价底线 | 允许单源，但必须**当日**估值 | 拒发再拍卖委托，先补估值 |

### 3.2 审慎值计算

```
prudent_value =
  MIN_OF_SOURCES:  min( { v ∈ 已登记且未过期的可用源 } )
  WEIGHTED:        floor( Σ ( v_i × w_i ) / Σ w_i )        -- 权重和归一化，向下取整
```

**缺源退化规则（必须显式，否则实现会各自发明）**：

| 可用源集合 | 结果 | `primary_source` | 是否允许 `ADVANCE_BASE` |
|---|---|---|---|
| 落札 + 查定 + 相場 | 三者取小 | 取胜者所属源 | ✔ |
| 落札 + 相場 | 二者取小 | 取胜者 | ✔（记 `inputs` 仅 2 项） |
| 仅落札 | = 落札价 | `AUCTION_WON_PRICE` | **✘**（与 §3.1「至少 2 源」取同一侧，见本节下方的裁决说明；仍写估值行并打 `single_source` 标记 + 告警，用途限 `PERIODIC_RECHECK`/`DISPOSAL_PRICING`） |
| 仅查定 | = 查定价 | `THIRD_PARTY_APPRAISAL` | **✘** 同上 |
| 仅相場 | = 相場 | `MARKET_MATRIX` | ✘ 相場是同类车批量价，不足以支撑单台敞口；**只能 `REVALUATION`** |
| 仅人工 | = 人工值 | `MANUAL_OVERRIDE` | ✔ 仅当二人签认 + 理由（`VALU_004` 若无签认）——**这是唯一的单源放款例外**，因为它本身就是一条被显式审批的覆盖动作，不是「凑巧只拿到一个源」 |
| 空集 | 无估值 | — | ✘ `VALU_001` |

**「单源能否作为放款基准」此前自相矛盾，现取保守侧**（2026-11-22 D13 §4.4 WU2-06）：
§3.1 `ADVANCE_BASE` 行写「至少 2 个可用源」，而 §3.2 退化表把「仅落札 / 仅查定」判成 ✔ 可放款，
两句话中间隔 15 行、都能被引用，实现者挑哪一句都自认有据——这正是「无绕过」这条红线最容易被凿开的形状。
现统一为：**除 `MANUAL_OVERRIDE`+二人签认之外，单源一律不可作 `ADVANCE_BASE`**（`VALU_001`，
按「无有效放款基准估值」处理并告警）。宽限窗口与「落札价单源是否最终放行」仍属 `[待定源 D-05]`，
签字前若业务要求放宽，**必须同时改掉 §3.1 那一行**，不许两处各留一套说法。

**加权规则的使用限制**：`WEIGHTED` 只能由 `LtvPolicy` 显式开启（`prudent_rule` 在政策层固定），
**不允许**逐台临时选择规则——否则「哪台用哪种算法」会变成可协商口径，事后无法审计。
默认全部 `MIN_OF_SOURCES`。

**示例（评审用，非承诺值）**

| 落札 | 查定 | 相場 | MIN 结果 | WEIGHTED(0.5/0.3/0.2) | 差异 |
|---|---|---|---|---|---|
| 2,050,000 | 1,980,000 | 2,120,000 | **1,980,000** | 2,043,000 | −63,000（−3.1%） |
| 1,450,000 | 缺失 | 1,380,000 | **1,380,000** | 1,425,000（权重重归一） | −45,000 |
| 3,900,000 | 3,600,000 | 3,700,000 | **3,600,000** | 3,765,000 | −165,000 |

> 表中差异即「审慎性来源」。`MIN` 默认的理由：二手车单台差异大、行情样本粗，
> 取小是唯一能在争议时自证的口径（「我们当时用了三个数里最小的」）。

### 3.3 有效期与刷新

- `valid_until = valuation_date + valuation.valid-days`（默认 45 日，`[D-05]`）。
- 有效期只约束**新放款与新指令**，不追溯已放款（§2「额度层永不重算」）。
- 过期作业：`ValuationExpiryJob` 每日扫描 `status='ACTIVE' AND valid_until < today` → 置 `EXPIRED` + 生成 `REVALUATION` 任务。
- 同一 VIN 同时只允许一行 `ACTIVE`（`uk_val_active` 部分唯一索引，D04 §3.12）：
  新行生效与旧行失效在**同一事务**内完成，避免两行同时 `ACTIVE` 导致读取不确定。

### 3.4 人工覆盖的硬约束（`[待定源 U-07]`）

| 约束 | 值 | 实现 |
|---|---|---|
| 上限 | 不得超过可用自动源最大值的 **115%** | `manual_value <= max(auto_sources) × 1.15` |
| 下限 | 无下限（可以更保守） | 保守方向不设限 |
| 二人 | `overridden_by ≠ co_signed_by`，且都需权限 | `AUTH_003` |
| 理由 | `reason` 非空且 ≥ 10 字符 | `VALID_001` |
| 留痕 | 写 `valuation_inputs(source=MANUAL_OVERRIDE, ref=理由编号)` + `audit_events VALUATION_OVERRIDE` | — |
| 禁止 | 人工值**不得**成为 `DISPOSAL_FLOOR` 的唯一源 | `DSPO_002`（处置必须面对市场） |

---

## 4. 分档 LTV（R3.2）

### 4.1 维度语义与取值来源

| 维度 | 计算 | 来源 | 备注 |
|---|---|---|---|
| `age_band`（月） | ` valuation_date − vehicle_units.first_registered_on ` 的自然月数（不足一月不计） | 車検証 / 登録 | 与 Q14 一致用日历口径，不用 30 日折算 |
| `mileage_band`（km） | `vehicle_units.mileage` 按 `mileage_source` 的最新可信值 | 落札票 / WMS 入库 | `mileage_source=UNKNOWN` 时**按上一档（更高里程）处理**，即保守归带 |
| `repair_history` | `RepairHistoryFlag` | 検査票 | `UNKNOWN` 与 `MAJOR` 同档处理（从严） |
| `grade`（評価点） | `auction_grade` | 落札票 | 无評価点（相谈车）→ 只允许命中 `grade_min IS NULL` 的带 |
| `inspection_remaining`（月） | `inspection_expiry_date − valuation_date` | 車検証 | < `inspection_remaining_min` → 拒或降档，见 §4.5 |
| `model_heat` | 由 `valuation_market_snapshots.trend_30d` + `sample_count` 推导 | 行情 | `UNKNOWN`（样本 < 阈值）按 `LOW` 处理（保守） |
| `dealer_grade` | 双维评分合成后的车商等级 `[待定源 U-11]`（原稿误标 `D-07`，见 D00 §16.1） | 评级引擎 | 评级是**第二道闸**，不与单台维度混合加权（否则不可解释） |

### 4.2 规则表结构与不变式

```
ltv_policies(policy_code, version, effective_from, effective_to, status, default_ratio, ...)
ltv_policy_bands(policy_id, priority, age_band_min/max, mileage_band_min/max,
                 repair_history_in, grade_min/max, inspection_remaining_min,
                 model_heat_in, dealer_grade_in, max_ratio, rate_addon_bps, breach_threshold)
```

| 不变式 | 表述 | 校验时点 | 违反后果 |
|---|---|---|---|
| **L1 唯一命中** | 任意合法输入组合，至多命中一条 band | 政策保存时全枚举抽样 + 运行时断言 | `LTV_002` 拒放款，工单政策缺陷 |
| **L2 全覆盖** | 任意合法输入组合，至少命中一条或落入兜底带 | 保存时校验「维度笛卡尔积的边界样本」全通过 | 兜底带生效，告警 `LTV_005`（码位定源见 D00 §9 下表 B；原稿写 `LTV_003` 与「突破需追加还款」撞码）并**计入缺口报表** |
| **L3 兜底保守** | `default_ratio` 必须 < 该政策所有 band 的 `min(max_ratio)` | 政策审批时 | 拒绝保存 |
| **L4 比率上界** | 所有 `max_ratio <= 1.0`（禁止融资本息之外的超额融资） | CHECK 约束 + 领域断言 | 拒绝保存 |
| **L5 版本不可变** | `status='ACTIVE'` 的政策不得改 band；只能新版本 + 旧版本 `RETIRED` | 应用层 + 无 UPDATE 权限 | `LTV_004` |
| **L6 生效不重叠** | 同一 `policy_code` 的多个 ACTIVE 版本生效期不得重叠 | 保存时 | 拒绝保存 |

> **L1 的运行时断言不是多余的**：政策表可以被人改坏，而放款只有几十毫秒的判断窗口。
> 命中多带时**必须拒绝并留痕**，绝不允许「取第一条命中」这种隐式默认（P-2）。

**兜底带**：`default_ratio` 不是 band，是政策行的独立列，只在**零命中**时使用；
命中多带时**不进兜底**（那是配置缺陷，应阻断而不是"降级放行"）。

### 4.3 示例分档（**建议默认值，全部 `[待定源 D-06]`，签字前不得编码**）

| `priority` | 車龄(月) | 里程(km) | 修復歴 | 評価点 | 車検残(月) | 结果 `max_ratio` | `rate_addon_bps` | `breach_threshold` |
|---|---|---|---|---|---|---|---|---|
| 10 | 0–36 | 0–30,000 | `NONE` | ≥4.5 | ≥12 | **0.80** | 0 | 0.90 |
| 20 | 0–36 | 0–50,000 | `NONE,MINOR` | ≥4.0 | ≥6 | **0.75** | 0 | 0.90 |
| 30 | 0–60 | 0–80,000 | `NONE,MINOR` | ≥3.5 | ≥6 | **0.70** | 25 | 0.90 |
| 40 | 0–60 | 0–80,000 | `NONE,MINOR` | 3.0–3.4 | ≥3 | **0.65** | 50 | 0.88 |
| 50 | 0–72 | 0–100,000 | 任意 | 任意 | ≥3 | **0.60** | 75 | 0.88 |
| 60 | 0–72 | 任意 | `MAJOR` | 任意 | 任意 | **0.50** | 150 | 0.85 |
| 70 | 0–72 | 任意 | `UNKNOWN` | 任意 | 任意 | **0.50** | 150 | 0.85 |
| 80 | >72 | 任意 | 任意 | 任意 | 任意 | **0.45** | 200 | 0.85 |
| 90 | 任意 | 任意 | 任意 | 任意 | <3（車検临催） | **0.40** | 250 | 0.80 |
| 兜底 | — | — | — | — | — | **0.40**（`default_ratio`） | 300 | 0.80 |

读法与自检要点（评审必查）：

1. `priority` 小者先命中，因此「車検临催」带（90）**必须排在正常带之后**、排在 `>72` 高龄带（80）**之后还是之前**
   是一个真实业务选择：本文取 **80 先、90 后**（高龄比臨催更决定残值），但两带比率相近（0.45 / 0.40），
   高龄且臨催的车最终落 0.45 —— 这与「双重从严」的直觉相反，**是本文主动暴露的一个待裁决点**。 `[待定源 D-06]`
2. `MAJOR` 与 `UNKNOWN` 同档同率（0.50/150bps），落实「UNKNOWN 不优于已知坏值」。
3. 兜底 0.40 严格小于所有带的最小值 0.40？——**不满足 L3（必须严格小于）**。
   因此示例中 90 带比率须调为 0.42 或兜底调为 0.35 才合法。本行保留为「反面示例」，
   提醒评审：L3 校验会在保存时就拒绝这张表，**必须先改再上线**。 `[待定源 D-06]`
4. 所有带 `max_ratio ≤ 0.80 < 1.0`，满足 L4；无「落札价全额融资」档位。
5. **⚠️ 本表与 §4.4 的「唯一命中否则报错」互斥（2026-11-22 D13 §3 G5 / §5.1-A4 登记）**。
   本表各带是**嵌套包含**关系（20 带 ⊂ 30 带 ⊂ 50 带，80/90 带又与各年龄带交叠），
   于是绝大多数真实车辆会同时命中多带。反例（可直接验）：车龄 24 月 / 45,000km / `NONE` / 評価点 4.2 / 車検残 12 月
   → 同时满足 20 带与 30 带 → 按 §4.4 现写法抛 `LTV_002`。
   这不是「表填错了」，是**两种语义混在一份文档里**：§4.3 读法 1 按「`priority` 小者先命中」（首命中制），
   §4.4 算法按「命中数必须为 1」（唯一命中制）。二者必须选一个，否则实现方各挑一边，
   放款比率就会出现「同一辆车两套结论」——这是资损向的口径分裂，不是代码风格问题。
   `[待定源 D-06]`：分档数值本就要业务签字，**命中语义一并签字**，不要只签数字。

### 4.4 命中判定算法（`LtvTierResolver`，纯函数）

**两条可选修法（都合法，必须择一，默认取 ② 并显式标记待签）**：

| 方案 | 算法改法 | 优点 | 代价 |
|---|---|---|---|
| ① **区间互斥切分** | §4.3 每带下界 = 上一带上界 + 1，各带两两不相交；§4.4 保持 `size > 1 → LTV_002` | 审计最强：一车一档唯一确定，`LTV_002` 是真实的数据异常告警 | 要重写整张表的 9 个带的五个维度，且高维组合下**很难写出既互斥又好读**的带（車検残/評価点交叉） |
| ② **`priority` 首命中制**（本文默认） | §4.4 改为取 `priority` 最小的命中带；`LTV_002` 只在**同 `priority` 且同时命中**时抛（那才是真歧义） | 现表示例值立刻合法，符合评分矩阵行业惯例，带可写宽 | 「靠顺序从严」的隐含知识必须写进表头，否则后人插带时顺序错一个就静默变率 |

```
resolve(input, policy):                       // 方案 ②（默认待签）
    matched = bands(policy).filter(b -> b.matches(input))
    if matched.isEmpty()      -> return policy.fallbackBand()          // 兜底 + 缺口计数
    top = matched.minBy(priority)
    if top.countSamePriority > 1 -> throw LTV_002(同序多带, 明细入 details)   // 只有这种才算歧义
    return top[0]                             // 不同序多带是设计内结果，不报错
```

`b.matches(input)` 的维度判定全部为「区间包含 / 集合包含」，**不含任何默认值推断**；
输入缺维度时该带不命中（不是「视为满足」）——这是 P-2「禁止隐式默认」的具体形态。

### 4.5 車検残存与放款期限的联动

| 条件 | 动作 | 依据 |
|---|---|---|
| `inspection_remaining < 3` 月且车辆将在車検期前售出 | 降档至 90 带 | 临催车行情折价 |
| `inspection_remaining < 1` 月（已满或当月满） | **拒付** `CERT_005` | 未受車検的车辆无法合法移动，实控价值近零 |
| 放款后車検到期且未续 | 告警 `CERTIFICATE_EXPIRING` + 工单；不自动降档已放款额 | §2 额度层不重算 |

---

## 5. 最大可贷额与放款联动

### 5.1 公式

```
max_advance = FLOOR( prudent_value × max_ratio )              -- 整数日元，向下取整
```

单台**不设**额外绝对上限；组合层通过 `ltv_limit_per_dealer` / `aging_exposure_limit_per_dealer`
（默认各 150,000,000，`[待定源 D-06]`）在放款闸门里拦，而不是改分档表。
**绝对上限与比率上限的分工要固定**：比率管单台风险，绝对额管集中度风险，混用会让「改一个数」同时影响两件事。

#### 5.1.1 现状链路与切换调用链（2026-11-22 D13 §3 G6 / §5.1-A5 具名，原稿只写公式不写落点）

**现状（实测代码，非推测）**：

| 坐标 | 现状行为 |
|---|---|
| `application/.../AdvanceApplicationService.java:115` `ingestVehicles(...)` | 建账时**就地算出放款额**并写进 `vehicle_units`，不是后续再算 |
| 同文件 `:172` | `ratio = draft.getAdvanceRatio() == null ? defaultAdvanceRatio : draft.getAdvanceRatio()` —— 比率**优先取请求体** |
| 同文件 `:176-177` | `advanceAmount = invoiceAmount × ratio`，`RoundingMode.HALF_UP`（**向上**，与本文 `FLOOR` 相反） |
| 同文件 `:97-102` | `floorplan.advance.max-ratio` 默认 **`1.0`**、`default-ratio` 默认 `0.90` |
| `web/.../VehicleController.java:87` | 请求未给比率时兜 `new BigDecimal("0.90")` |
| `web/.../dto/ApiRequests.java:79` | `advanceRatio` 是**对外请求字段**，任何人可传 |

> **由此得出一条必须先修的现状缺陷（与 USED 无关，主干就有）**：
> 现配置下调用方传 `advanceRatio = 1.0` 即可按**发票全额**放款，只被 `max-ratio:1.0` 这道形同虚设的闸拦住。
> 这与本文 §5.4「无落札价全额融资档位」、L4「`max_ratio < 1.0`」直接冲突，
> 也正是 AGENTS.md §12「用可配置参数放宽安全策略」的同一形状（Q21 的 `relaxedDealers` 是它的姊妹）。
> **建议处置（属技术修复，不需业务签字，但改动落在主干 `application`/`web`，需与在做的开发会话协调）**：
> USED 产品线的比率**一律不接受请求体传入**，由 `LtvTierResolver` 单点产出；
> 主干 LEGACY 线是否收紧 `max-ratio` 默认值 → `[待定源]`，**本文不擅自改主干行为**，只登记。

**USED 域必须打通的具名调用链（缺任一环即为「契约写了、没人调」的死代码，AGENTS §12 反模式第 2 条）**：

```
1  VehicleValuationQueryContract.getActiveByVin(vin)            ← 由建账用例调用，不是由报表调用
     └─ 无 ACTIVE 未过期 ADVANCE_BASE 估值 → G3 抛 VALU_001/VALU_002，不产生任何写入
2  LtvTierResolver.resolve(inputs, policy)                      ← 纯函数，输入取自估值快照的 inputs
     └─ 命中（含兜底）得到 max_ratio / policy_code / policy_version
3  UsedAdvanceCeilingCalculator.calcUsedAdvanceCeiling(prudent_value, max_ratio)
     └─ FLOOR 向下取整；本文原稿只写了这个公式，没写谁调它 → 现补明：**唯一调用方 = 第 4 步**
4  AdvanceApplicationService.ingestVehicles(...) 内 USED 分支
     └─ 用 3 的结果替代 :172-177 的 invoiceAmount × 请求体比率，并写
        prudent_value / ltv_ratio_snapshot / ltv_policy_code / ltv_policy_version / valuation_id
        （§5.2 五列同源一次写入，禁止两处各算）
5  申请额 ≤ max_advance 的校验放在 G5，与 3 同一次判定内完成，不在前端做
```

**验收口径**（D11 必须逐条有断言，否则本链路视为未交付）：
① 传 `advanceRatio=1.0` + USED 产品线 → 该字段被忽略且记入 `details.ignored_fields[]`，放款额仍 = `FLOOR(prudent_value×max_ratio)`；
② 无有效估值 → 建账失败且 `vehicle_units` 无新行（不是「建成 0 额」）；
③ `calcUsedAdvanceCeiling` 有 ≥1 个生产调用方（编译期可查引用数），单测绿不算交付。

### 5.2 落库与快照

| 字段 | 写入时机 | 是否可变 |
|---|---|---|
| `vehicle_units.prudent_value` | 放款时 | **否**（放款时点快照） |
| `vehicle_units.ltv_ratio_snapshot` | 放款时 | 否 |
| `vehicle_units.ltv_policy_code` / `ltv_policy_version` | 放款时 | 否 |
| `vehicle_units.valuation_id` | 每次新估值生效 | 是（指向当前 ACTIVE） |
| `vehicle_units.advance_amount` | 放款时 | 否 |
| `vehicle_units.topup_required_amount` | 每次突破判定 | 是（当前应追加额） |

> 有了这张表，「这单当时为什么放 148 万」在三年后仍可完整复现：`policy_version` + `valuation_id` +
> `inputs` + `prudent_value` + `ratio_snapshot`。这是本项目对「可追溯」的最低要求。

**⚠️ 快照的权威落点只有一个：`advances`（2026-11-22 由 D13 §4.4 WU2-15 定源）**

V58 在 `advances` 上建了同名的 `valuation_id` / `prudent_value` / `ltv_ratio_snapshot` / `policy_code`，
V50 又在 `vehicle_units` 上建了一份（D04 §5.1），本表原稿只写 `vehicle_units.*` ⟹ 同一事实两处可各存各的。
ADR-010 明确允许**同一 VIN 多笔垫款**，所以车辆侧那份在第二笔放款时必然被覆盖或写错行，
「当时用哪版政策」就退化成「最近一笔用哪版」。定源如下，别再各写各的：

| 落点 | 角色 | 可变性 | 允许用途 |
|---|---|---|---|
| `advances.ltv_ratio_snapshot` 等四列（V58） | **唯一权威** | 随借据一次性固化，之后不得改 | 判定、对账、审计复现、**D11 全部断言** |
| `vehicle_units.ltv_ratio_snapshot` 等列（V50） | 最近一笔的**只读展示投影** | 会被下一笔覆盖 | 列表页展示；**禁止进任何 WHERE / 门禁谓词 / 报表口径** |

> 需要同一条纪律的还有 `authoritative_source_type`（D04 §5.1 已按 ADR-Q27 撤销车辆侧列，见 D13 G13）。
> 两者的共同形状是：**「为了查得快」在别的聚合上留一份判定输入，早晚变成第二权威源。**

### 5.3 放款闸门顺序（与 D05 §4 一致）

```
G1 admission_state = ADMITTED                     → QUAL_001
G2 auction 场次在该车商 memberships 内且 ACTIVE      → QUAL_002
G3 存在 ACTIVE 且未过期的 ADVANCE_BASE 估值          → VALU_001 / VALU_002
G4 primary_source ≠ INTERNAL_DEGRADED              → VALU_003
G5 LTV 命中（分档语义按 §4.3-⑤/§4.4 择一方案，两者判定结果都必须唯一可复现）
   + 申请额 ≤ max_advance             → LTV_002 / LTV_001
G6 車検残存 ≥ 3 月                                  → CERT_005
G7 权属核查 conclusion ≠ CONFLICTED                 → GUARANTEE_001 / VEHICLE_005
G8 集中度与额度 (dealer 比率/绝对额/冻结/可用额)      → CREDIT_001 / CREDIT_002 / CREDIT_003
G9 落札明细核对 + 付款对象一致                        → PO_005 / ACCOUNT_002
```

任一门禁失败**不产生任何写入**（无半条借据、无孤儿额度占用）；失败本身写 `audit_events` + `risk_alerts`。
`GUARANTEE_001` 沿用既有枚举，不新造（避免同义码分裂）。

### 5.4 禁止事项

| 禁止 | 原因 |
|---|---|
| 用 `won_price` 直接当放款基准 | 落札价是「买入成本」不是「变现能力」，高竞价年份会系统性放大敞口 |
| 用 `invoice_amount` 当放款基准 | 链① 的旧口径，二手车无厂商发票；残留会静默走 0.90 老公式 |
| 用 `WEIGHTED` 临时逐台选择 | §3.2，隐式口径不可审计 |
| 重估后调整已放款额 | §2 额度层不重算 |
| 兜底带用于「正常业务」 | 兜底命中率必须进报表（L2），命中率高说明分档表有缺口，不是兜底好用 |

---

## 6. 估值重估引擎

### 6.1 触发条件

| 触发 | 条件 | 批量/单台 | 幂等 |
|---|---|---|---|
| 周期 | `reval-trigger-days`（默认 30）且无 ACTIVE 估值 | 批量 | `valuation_key` UNIQUE |
| 价格事件 | 同一 `model_key` 相場快照变动 ≥ 5% | 受影响 VIN 批量 | 去重键 `(vin, snapshot_id)` |
| 入库 | `WMS_INBOUND_CONFIRMED` 首次实控建立 | 单台 | `WMS:{wh}:{vin}:{inbound_no}` |
| 售出 | `sold_at` 登记 | 单台（为回款与敞口核对提供依据） | `VAL-{vin}-SOLD-{date}` |
| 处置前 | 发再拍卖委托前**必须**有当日估值 | 单台 | `DSPO:{case_no}:{round}` |
| 人工 | 运营点「立即重估」 | 单台 | 带操作人，重复点击不重复生效（同键同摘要） |

### 6.2 批量作业

| 参数 | 默认 | 说明 |
|---|---|---|
| 分片大小 | 500 台/批 | 按 VIN 哈希分片，与 `EventDispatcher` 同模式 |
| 并发 | 1 | **多实例不安全**：`consumer_offsets` 游标是服务级唯一（D02 风险 R-2），重估作业同理由单实例持有 |
| 单批超时 | 10 分钟 | 超时不重试整批，改为记录 `last_vin` 续跑 |
| 失败策略 | 单台失败不阻塞整批；失败台进 `valuation_reval_failures` 计数，连续 3 次进工单 | 避免一台脏数据卡死全量 |
| 执行窗口 | `02:00–05:00 JST` | 与快照对账错峰，两者都读大表 |
| 外部询价节流 | `reval-marketquery-throttle-per-min`（默认 60） | 行情方多有 QPS 限制，节流值进配置而非硬编码 |

> **主动声明**：重估引擎与既有 `ResidualValueEstimator` 是**替代关系还是并存关系**尚未定源（`[待定源 U-05]`，
> 已登记在 D01 §6）。本文按「并存 + `vehicle_type` 路由」写：`ResidualValueEstimator` 继续服务
> `NEW_STOCK` 的 ADR:138-139 一阶段口径（全局折旧参数），USED 走本文多源审慎值。
> 合并时**不得**把两者统一成一个函数——那会改动 LEGACY 的已裁决口径。

---

## 7. 库龄递减双因子（R3.4 / R9）

### 7.1 现状与问题

- 现状 `loan_term_reduction_plans` 与 `aging_plans.reduce_amount` 是**纯日历天数单因子**（`AgingDpdRecalcPolicy`）。
- 需求书 §3.4 链⑥ + R3.4：递减额应同时由**行情**与**車検期**驱动。
- ADR:151 全局参数口径与 `residual_value_estimated` 注释「仅用于预警」**均被 USED 域覆盖**（D01 §3.2）。

### 7.2 公式（建议）

```
day_driven       = FLOOR( advance_amount × aging_day_decay_rate_per_100d / 100 × aging_days )
                   上限截断：≤ advance_amount × aging_day_decay_cap（默认 0.35）

valuation_driven = CEILING( max(0, 敞口本金 - FLOOR(最新 ACTIVE 估值 prudent_value × 当前命中档 max_ratio)) )

certificate_driven = 車検残存 < certificate_decay_trigger_months（默认 2）
                     时按固定比例 0.35 强制递减（默认值，[待定源 D-05]）

required_reduction = MAX(day_driven, valuation_driven, certificate_driven)   -- 取大者，不是相加
target_reduction   = MAX(required_reduction, 既有 aging_plans.reduce_amount) -- 棘轮：不回退
top_up_amount      = target_reduction 中尚未由实际回款冲抵的部分
```

**为什么取大者而不是相加**：三个因子描述的是**同一件事**（敞口相对变现能力过大）的三个侧面。
相加会重复惩罚同一风险，并在高龄 + 跌价 + 臨催同时出现时给出超过本金的递减额。
需求书 §12.4 自评「R3.4 双因子算法不完整（两因子如何合成未定）」——**本文给出的裁决建议就是「取大者 + 棘轮不回退」**，
签字前仍属 `[待定源 D-05]`。

**棘轮（`target_reduction` 不回退）**：行情回升时不自动下调已下发的追加还款要求。
理由：追加还款通知已经送达车商，撤回会造成对账口径混乱；回升只影响**下一次**判定。

### 7.3 计算示例（示意参数，非承诺值）

| # | 本金 | 在库日 | 落点 | 最新审慎值 | max_ratio | `day_driven` | `valuation_driven` | `certificate_driven` | `required_reduction` |
|---|---|---|---|---|---|---|---|---|---|
| 1 | 1,485,000 | 90 | A | 1,980,000 | 0.75 | 33,000 | 0 | 0 | **33,000** |
| 2 | 1,485,000 | 90 | A | 1,600,000 | 0.75 | 33,000 | **185,000** | 0 | **185,000** |
| 3 | 1,485,000 | 250 | A | 1,980,000 | 0.75 | **92,000** | 0 | 0 | **92,000** |
| 4 | 1,485,000 | 600 | B | 1,700,000 | 0.70 | 103,000 | **295,000** | 0 | **295,000** |
| 5 | 1,485,000 | 600 | **兜底** | 1,700,000 | 0.35 | 44,000 | 1,485,000−595,000=**890,000** | 0 | **890,000** |
| 6 | 1,485,000 | 30 | 兜底带 | 1,000,000 | 0.35 | 11,000 | 1,135,000 | **519,750**（車検 1 月） | **1,135,000** |
| 7 | 1,485,000 | 700（>1000 日封顶前） | A | 1,980,000 | 0.75 | 封顶 7,000 | 0 | 0 | **7,000** |

> 示例 5/6 刻意展示兜底带的威力：比率 0.35 会把 `valuation_driven` 推到接近本金。
> 这是**设计意图**（兜底不该常落），也是 L2 缺口必须进报表的原因——
> 若某月兜底带递减额显著抬升，说明分档表或行情数据出了问题，而不是"风控变严了"。
>
> 示例 7 展示 `aging_day_decay_cap`（本金 35% 封顶）与按日线性递减速率之间的**不一致**：
> 若 100 天 0.25%，1400 天理论约 3.5%、10 年才 12.8%，远低于 35% 封顶，封顶形同虚设。
> 两者必须成对校准（要么封顶降到 ~5%，要么把速率改为分段加速）。本文按**速率分段、封顶 35%**
> 给建议：0–180 日 0.15%/100 日、181–365 日 0.30%/100 日、>365 日 0.60%/100 日。 `[待定源 D-05]`

### 7.4 参数落位

| 参数 | 载体 | 版本化 | 二人审批 |
|---|---|---|---|
| 递减速率表（分段） | `aging_plans` 规则行 + `aging_day_decay_rate_per_100d` | 是（`plan_code`+`version`） | 是 |
| 递减封顶 | `aging_day_decay_cap` | 是 | 是 |
| 車検触发阈值 | `certificate_decay_trigger_months` | 否 | 否（技术性阈值） |
| 突破告警线 `breach_threshold` | `ltv_policy_bands` 行内 | 是（随政策版本） | 是 |
| 豁免规则 | **`aging_exempt_rules`**（规则本体，D04 §3.14.1）+ `aging_plans.rule_code`（命中留痕）+ 二人 | 是 | 是（`AGING_RULE_EXEMPT`） |

> **`breach_threshold` 从独立参数表移进 band 行**：本文修订点。
> 理由是它与 `max_ratio` 描述同一档位的双重属性（比率上界 + 预警线上界），拆两张表会出现版本不同步，
> 导致「按 0.75 放款、按 0.85 预警」这种没人选过的组合。D04 §3.14 已按此写。

---

## 8. 处置变现定价（R3.5 / R10）

### 8.1 链条

```
当日 DISPOSAL_FLOOR 估值 → prudent_value
  → 起拍保留价 = FLOOR(prudent_value × disposal_reserve_ratio)（默认 0.85，[待定源 D-06]）
  → 委托拍卖场再出场（disposal_auctions，round）
  → 落槌价 hammer_price
  → 净回收 = hammer_price − 拍卖佣金 − 陆运费 − 场内手续费 − 登记抹消成本
  → 缺口 = 应清偿额 − 净回收 → 缺口进保证金 / 信保 / 追偿
```

### 8.2 应清偿额构成

`应清偿额 = advance_amount + accrued_interest + penalty + 垫付费用`（冲抵顺序：费用 → 利息 → 本金）。
`advance_amount` 是本金余额（不是初始放款额）——实现时必须取余额口径，取初始额会虚增敞口。

### 8.3 示例

| # | 审慎值 | 保留价 | 落槌价 | 成本合计 | 净回收 | 应清偿余额 | 缺口 | 结果 |
|---|---|---|---|---|---|---|---|---|
| 1 | 1,980,000 | 1,683,000 | 1,750,000 | 60,000 | 1,690,000 | 1,600,000 | −90,000（盈余） | 结清，盈余按裁决退车商 `[待定源 D-06]` |
| 2 | 1,980,000 | 1,683,000 | **流拍** | — | — | 1,600,000 | — | round+1，保留价 × 0.90，最多 2 轮 |
| 3 | 1,380,000 | 1,173,000 | 1,100,000 | 45,000 | 1,055,000 | 1,485,000 | **430,000** | 保证金扣抵 → 不足进信保 → 再不足进追偿工单 |
| 4 | 1,380,000（`INTERNAL_DEGRADED`） | — | — | — | — | — | — | **拒发委托** `VALU_003`，须先补当日有效源 |

> 示例 1 的「盈余归属」是真实业务问题（多数法域要求清算后余额返还债务人），
> 本文**不擅自规定**，按需求书 §12.3 D-06 一并列出签字项。

### 8.4 重拍轮次规则

| 轮 | 保留价 | 前置 |
|---|---|---|
| 1 | `prudent × disposal_reserve_ratio` | 当日估值 + 二人确认委托 |
| 2 | 第 1 轮保留价 × `relist_discount_ratio`（默认 0.90） | 必须**重新**取得估值；人工定价不得替代 |
| ≥3 | 不允许（`round-max` 默认 2） | 转报废/拆解通道或升级审批 |

---

## 9. `residual_value_estimated` 的处置（V11 三列）

| 列 | LEGACY 口径 | USED 口径 | 落点 |
|---|---|---|---|
| `residual_value_estimated` | 「仅用于预警，不作处置定价依据」 | **同时作为处置定价输入**（D03 I4） | `COMMENT ON` 覆盖（D04 §7） |
| `residual_value_source` | 未使用 | 写入 `ValuationSource` 值，**必须**可反查 `vehicle_valuations` 行 | 新增 CHECK 允许集 |
| `residual_value_at` | 未使用 | 写入 `valuation_date` | — |

**三列语义分工（本文裁决）**：

```
vehicle_units.prudent_value          ← 放款时点快照，不可变，用于复现「当时为什么放这么多」
vehicle_units.residual_value_estimated ← 最新监控值，随重估刷新，用于预警与递减判定
vehicle_valuations                   ← 全部历史与输入，权威账，任何结论都能反查到行
```

> **写入方必须出现**：`V11:287-294` 三列此前**全库无 `setResidualValue` 写入方**（字段在、逻辑无）。
> D06 的 `ValuationRefreshApplied` 处理器必须同时写这三列，否则 V11 继续是死列。
> 需求书 §12.4 自评的「R3 未说明 V11 三列如何复用」即本节，已闭合。

**一致性约束**（D03 §2.4）：`I1` 同 VIN 同时只一行 ACTIVE；`I2` `ADVANCE_BASE` 的 `prudent_value` 必须等于其输入最小值（或 `MANUAL_OVERRIDE` 例外）；`I3` `INTERNAL_DEGRADED` 不得作 `ADVANCE_BASE`/`DISPOSAL_FLOOR`；`I4` `residual_value_*` 三列必须与最新 ACTIVE 行一致，且每次重估留旧值快照进 `valuation_market_snapshots` 或历史表。

---

## 10. domain 层单测用例表（纯函数，`mvn -B -pl domain test` 可跑）

| 类 | 用例 | 断言要点 |
|---|---|---|
| `PrudentValueCalculator` | `minOfThreeSources` | 取三者最小 |
| | `minIgnoresMissingSources` | 单源缺失不影响结论 |
| | `noSourcesThrowsVALU001` | 空集合抛 `VALU_001` |
| | `degradedSourceNeverAdvanceBase` | `INTERNAL_DEGRADED` 单独存在 → `VALU_003` |
| | `weightedUsesNormalizedWeightsAndFloor` | 权重和≠1 时归一化 + 向下取整 |
| | `weightedOnlyWhenPolicyEnabled` | 政策未开加权时忽略 `w_i` |
| | `marketMatrixOnlyCannotBeAdvanceBase` | 仅相場 → `VALU_002` |
| | `manualOverrideRequiresTwoSigners` | 缺第二签认 → `VALU_004` |
| | `manualOverrideCappedAt115Percent` | 超上限 → `VALU_005` |
| | `tieBetweenTwoDecimalRatiosIsExact` | 断言用 `BigDecimal.compareTo`，不用 `assertEquals`（AGENTS.md：中间量走 `BigDecimal`） |
| `LtvTierResolver` | `singleHitByPriority` / `multiHitThrowsLTV002` / `noHitFallsBack` | 不取第一条 |
| | `unknownRepairHistoryTreatedAsMajor` | UNKNOWN 不优于已知坏值 |
| | `mileageUnknownEscalatesOneBand` | 里程来源不明时保守升档 |
| | `missingDimensionDoesNotMatch` | 缺维度不是「视为满足」 |
| | `fallbackRatioStrictlyBelowMinBand` | L3 |
| | `bandRatioNeverExceedsOne` | L4 |
| | `certificateUnderOneMonthRejects` | `CERT_005` |
| | `crossProductBoundarySampleAllCovered` | 参数化测试，边界值全扫 |
| | `policyVersionImmutableWhenActive` | L5 |
| `AgingDpdRecalcPolicy`（既有） | `legacySingleFactorUnchanged` | **回归守卫：禁止断言 USED 行为** |
| `AgingDualFactorCalculator` | `takesMaxNotSum` | 三因子取大者 |
| | `ratchetNeverDecreases` | target 不回退 |
| | `dayDecayCappedAt35Percent` | 封顶生效 |
| | `segmentedRateAppliesAcross365Boundary` | 分段边界 181/365 各一条 |
| | `valuationDrivenUsesBalanceNotAdvanceAmount` | 余额口径 |
| | `certificateTriggerAtTwoMonthsRemaining` | 車検驱动 |
| | `exemptRuleStillRecordsTargetReduction` | 豁免只是不追缴 |
| `DisposalPricingPolicy` | `reserveUsesFloorAndRatio` | 保留价取整方向 |
| | `netProceedsSubtractsAllFourCosts` | 四项成本齐备 |
| | `shortfallOrderGuaranteeThenCreditThenRecovery` | 缺口吸收顺序 |
| | `surplusHandlingUnimplementedPendingD06` | 盈余归属未签字时抛异常而非默认 |
| | `relistRequiresFreshValuation` | 二次委托必须新估值 |
| | `roundMaxTwo` | 第 3 轮拒绝 |
| | `degradedValuationBlocksConsign` | 示例 4 |

---

## 11. 自检

- 需求书 R3 的五个子项（双源审慎值 / 分档 LTV / 有效期与触发 / 递减双因子 / 处置定价）逐条有公式、参数、示例、单测。
- R3.3 硬校验落地：`max_advance < won_price`（即 LTV < 1）+ `repair_history ∈ {MAJOR, UNKNOWN}` 落低档——两处都不靠「提示」，是闸门 5/6。
- R3.4 指出的「两因子如何合成未定」已给出裁决建议（取大者 + 棘轮），并保留 `[待定源 D-05]` 标记。
- R3.5 指出的「与 `residual_value_estimated` 作预警不作定价的 ADR:151 口径冲突」已覆盖（§9），并在 D01 §3.2 效力对照表里登记。
- §1 列出的 LEGACY 污染点（`invoice_amount`、`smoke-test.py:327`、`PurchaseOrderImportParser`）全部只读保留，未提议改动既有公式。
- §4.3 主动暴露两个不自洽（兜底带违反 L3、高龄与臨催的优先级），不掩盖。
- 数值示例经复核，其中 2 处刻意保留为「反面示例」（§4.3 第 3 点、§7.3 表注）用于说明参数必须成对校准，不是计算疏漏。
- 未签字项：`D-05`（估值周期与源要求、递减参数与豁免）、`D-06`（分档表数值、绝对上限、盈余归属）、`U-11`（双维评级合成，D03 §3.1；**原稿误标 D-07**，D-07 实为解押顺序）、`U-05`（与 `ResidualValueEstimator` 并存/替代）。

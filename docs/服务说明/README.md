# 服务说明（按业务语言）

> 这份目录用业务语言说明每个服务**在什么场景下、替谁做什么事、不做什么**。
> 一个服务一份文件；数字与状态以 2026-11-24 的代码实查为准，最新断点仍看 `PROGRESS.md`。
>
> 不按技术分层写，按“业务上谁需要它”写。遇到“正式记录 / 查询副本 / 事件”这些词，下面有解释。

---

## 先统一三个说法

**1. 正式记录 vs 查询副本**

同一件事可能有两份数据：

- **正式记录**：真正用来做业务判断的那份，比如“这台车到底有没有办担保登记”；
- **查询副本**：给运营、审计查询方便而保存的一份拷贝，不参与放款判断。

本项目里最典型的是担保登记：正式记录在 V10 迁移建立的**担保登记批次表**（`V10__collateral_ledger.sql`），查询副本在 V18 迁移建立的**权属查询表**（`V18__collateral_registration.sql`）。V25 迁移（`V25__adr_q27_authoritative_source.sql`）明确了这个关系。

**2. “事件”不是实时调用**

一件事办完后，系统先把业务数据记下来，再写一条“事情发生了”的通知；另一个服务稍后读到这条通知，再做自己的事。这样两个服务不用同时成功，但要求：
- 通知不能重复发、不能乱序丢；
- 收到通知的服务要能承受“同一条通知再来一次”。

**3. 查不到就拒绝**

涉及付款、放车的检查，查不到或服务不可用时，一律按“不满足”处理，不先放行后补。业务上就是：**宁可这笔先不放，也不在没担保的情况下把钱付出去。**

---

## 11 个可独立启动的服务

| 服务（端口） | 用业务话讲，它管什么 |
|---|---|
| `floorplan-core`（8080） | **主账本**：收车建账、垫款申请/复核/付款、还款结清、按日计息、车辆和采购单主数据、附件，以及二手车资质/车辆/估值三条业务链 |
| `auth-service`（8081） | **登录与退出**：核对账号密码、发令牌；退出时让旧令牌立即失效 |
| `bank-service`（8082） | **银行流水与回款匹配**：拉流水，把一笔回款对到某台车/某笔借据；对不准就挂人工 |
| `credit-service`（8083） | **经销商与授信额度**：建经销商档案、核额度、冻结/解冻、评级、年审；回款后把可用额度加回来 |
| `risk-service`（8084） | **贷后风控**：实地盘点、风险预警、权属核对、担保台账与登记批次管理、违约处置 |
| `loan-service`（8085） | **借据与逾期天数**：放款后建借据，算每笔借据逾期了几天，维护经销商风险画像 |
| `collateral-reg-service`（8086） | **担保登记查询与留痕**：付款前回答“这台车有没有有效担保登记”；记录人工补录和外部快照 |
| `release-service`（8087） | **放车/放证指令**：车卖了、钱到了，给保管方下指令放车或放证，并跟踪回执 |
| `recon-service`（8088） | **对账**：拿两套账互相核对，列出差异，不自动改数 |
| `api-gateway`（18080） | **统一大门**：所有请求从这一个地址进来，按路径分给对应服务 |
| `external-api`（18090） | **外部合作方专用入口**：拍卖行、WMS、监管仓等外部系统的隔离接入面；目前只有骨架 |

> 另有 4 个共享代码模块 `domain` / `application` / `infrastructure` / `common`，它们是编译进各服务的代码库，不是独立进程，不在本目录单独成文。

---

## 阅读顺序建议

1. 先看 `floorplan-core.md`：理解钱和车的主流程；
2. 再看 `bank-service.md`、`credit-service.md`、`loan-service.md`、`collateral-reg-service.md`：理解一笔回款之后，额度、借据、担保分别怎么变；
3. 然后看 `risk-service.md`、`release-service.md`、`recon-service.md`：理解贷后、放车和对账；
4. 最后看 `auth-service.md`、`api-gateway.md`、`external-api.md`：理解入口与边界。

---

## 文档清单

| 文件 | 服务 |
|---|---|
| [`floorplan-core.md`](floorplan-core.md) | 核心账务（web） |
| [`auth-service.md`](auth-service.md) | 登录认证 |
| [`bank-service.md`](bank-service.md) | 银行流水与回款匹配 |
| [`credit-service.md`](credit-service.md) | 经销商与授信额度 |
| [`risk-service.md`](risk-service.md) | 贷后风控与违约处置 |
| [`loan-service.md`](loan-service.md) | 借据与逾期天数 |
| [`collateral-reg-service.md`](collateral-reg-service.md) | 担保登记查询与留痕 |
| [`release-service.md`](release-service.md) | 放车/放证指令 |
| [`recon-service.md`](recon-service.md) | 对账 |
| [`api-gateway.md`](api-gateway.md) | 统一入口 |
| [`external-api.md`](external-api.md) | 外部合作方接入面 |

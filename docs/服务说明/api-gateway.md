# api-gateway（统一入口，端口 18080）

> 一句话：**浏览器、前端、外部调用方只记一个地址：18080。网关按请求路径，把请求转给真正负责的服务。**

---

## 它在业务里管什么

系统里有 9 个业务服务、各自不同端口。如果让前端记住“登录找 8081、额度找 8083、回款找 8082……”，既难维护，也容易把请求打到错误的服务。

所以设一个统一入口：

```text
浏览器 / 调用方
     │
     ▼
api-gateway :18080
     │
     ├─ /api/auth/**           → auth-service
     ├─ /api/dealers/**        → credit-service
     ├─ /api/payments/**       → bank-service
     ├─ /api/loans/**          → loan-service
     ├─ /api/collateral-reg/** → collateral-reg-service
     ├─ /api/releases/**       → release-service
     ├─ /api/recon/**          → recon-service
     ├─ /api/inventory/** 等   → risk-service
     └─ 其余 /api/**           → floorplan-core
```

---

## 它具体做什么

### 1. 按路径转发

- 路径以 `/api/auth/` 开头，转到登录服务；
- 路径以 `/api/dealers/`、`/api/credit-lines/` 开头，转到授信服务；
- 路径以 `/api/payments/` 开头，转到银行回款匹配服务；
- 路径以 `/api/loans/` 开头，转到借据服务；
- 路径以 `/api/collateral-reg/` 开头，转到担保登记查询服务；
- 路径以 `/api/releases/` 开头，转到放车指令服务；
- 路径以 `/api/recon/` 开头，转到对账服务；
- 其余 `/api/**` 默认转到核心账务服务。

### 2. 托管管理控制台页面

- `index.html`、`login.html`、CSS/JS 也走网关；
- 页面和接口同源，浏览器不用处理跨域。

### 3. 给每次请求打一个追踪号

- 生成 `X-Request-Id`，一路传到下游服务；
- 出问题时可以用这个号把一次请求经过的多个服务日志串起来。

---

## 它不做什么

- **不验令牌**：令牌真假、权限够不够，由各个业务服务自己检查；网关只负责转发；
- 不做限流、熔断、降级；
- 不做业务判断，不碰数据库。

---

## 为什么“路由顺序”很重要

网关是“第一条匹配的规则就生效”。如果宽泛规则排在前面，精确规则就永远轮不到。

真实踩过的坑：

- 经销商银行账户接口 `/api/dealers/{id}/bank-accounts` 本来在核心账务服务；
- 但 `/api/dealers/**` 整段先被授信服务的宽规则接走了；
- 授信服务没有这个接口，于是请求经网关变成 404；
- 修法是加一条更精确的规则，排在宽规则前面。

所以现在的顺序是：**精确路径在前，宽路径在后，最后才是 `/api/**` 兜底。**

---

## 对外暴露的主要路径

| 路径 | 转到哪个服务 | 业务含义 |
|---|---|---|
| `/api/auth/**` | auth-service | 登录、退出 |
| `/api/dealers/**`、`/api/credit-lines/**`、`/api/rating-configs/**`、`/api/annual-reviews/**` | credit-service | 经销商、额度、评级、年审 |
| `/api/inventory/**`、`/api/alerts/**`、`/api/disposals/**`、`/api/collateral/**` | risk-service | 盘点、预警、处置、担保台账 |
| `/api/vehicles/*/lien-check` | risk-service | 车辆权属核对（必须排在核心车辆接口前） |
| `/api/jobs/bank-fetch` | bank-service | 银行流水采集（必须排在核心作业接口前） |
| `/api/payments/**`、`/api/bank/**` | bank-service | 回款匹配 |
| `/api/loans/**`、`/api/dealer-profile/risk/**` | loan-service | 借据、逾期、画像 |
| `/api/collateral-reg/**` | collateral-reg-service | 担保登记查询与留痕 |
| `/api/releases/**` | release-service | 放车/放证指令 |
| `/api/recon/**` | recon-service | 对账 |
| `/api/dealers/*/bank-accounts` | floorplan-core | 经销商结算账户（精确规则，防被授信宽规则吞掉） |
| 其余 `/api/**` | floorplan-core | 垫款、还款、计息、车辆、采购单、附件等 |

---

## 现在真实状态（2026-11-24）

- 13 条显式路由（1 条静态页 + 12 条 API 路由，含核心兜底），本地用环境变量把服务发现地址覆盖成直连；生产接 Nacos 后走服务发现；
- 启动时不会自动按服务名暴露路由，避免没声明的服务被意外访问；
- 路由顺序有构建期测试（模拟“第一条匹配”），关键路径还配有经网关的冒烟断言；
- 网关目前不验令牌，鉴权在各业务服务内部完成。

---

## 用一句话总结

> `api-gateway` 是统一大门：前端只记 18080，它按路径把请求分给对应服务，并给每次请求一个追踪号；它不判断业务、不验权限，但路由顺序错了会让请求进错门。

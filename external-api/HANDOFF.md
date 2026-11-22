# HANDOFF · external-api（DMZ 外部接口层）

| 项 | 内容 |
|---|---|
| 归属 | W-U0 交付 ②（骨架）→ **W-U3 交付能力**（D12 §3 W-U3 行、D02 §3.3 五件套） |
| 端口 | **18090**（DMZ，**不经 gateway**，不在 `gateway/application.yml` 里加路由） |
| 命名 | Maven `artifactId` = `external-api`；`spring.application.name` = `external-api-service`（D12 W-U0 警示 ③ 裁决，两套名各有所指，勿混用） |
| 基线 | 本目录建于 USED 开发轮基线；引用标签一律取最新 `base-*`，本文不写死标签名 |

## 本轮实际落地了什么（只这些）

- `pom.xml`：依赖集 = `common` + `starter-web` + `starter-jdbc` + nacos + test。
  **未声明** `domain` / `application` / `infrastructure`。
- `ExternalApiServiceApplication`：`@SpringBootApplication(scanBasePackages = …external)`
  + `@EnableScheduling` + `@EnableDiscoveryClient`。
  **没有** `@Import(JdbcOutboxPublisher / EventDispatcher)`——这是 2026-11-22 D13 G9 / A8 反转后的定案：
  给 DMZ 装配发布端 = 给它业务写权限，破 INV-2。
- `application.yml`：`flyway.enabled=false`、datasource 带 `currentSchema=integration`、
  DB 角色 `floorplan_dmz`（env 可覆盖）、**无任何 partner 密钥**。

以上都不构成「外部接口可用」。D02 §3.3 的另外四件——拆能力（验签/签名/nonce/限流/轮换）、
迁数据（`integration` 六表 V70–V74 + 角色授权）、通链路（WMS 入库确认入站 → core 消费 → 车辆 `IN_STOCK`）、
有断言（`scripts/smoke_used_integration.py` 覆盖正/反/重放/超频）——**全部 0**。

## 三条开工前要知道的硬事实

1. **`floorplan_dmz` 角色在仓库里没有任何创建点**（`grep -rn floorplan_dmz infrastructure/.../db/migration/`
   与 `scripts/` 双侧零命中；V70 也还没写），W-U0 交付 ⑤ 未做。所以本进程现在起不来是**预期状态**，
   不要为了让它起来而把 username 换成超管 `floorplan`——那等于取消 INV-1 的实证机会。
2. **INV-2 的「依赖树检查」这一半在当前模块图下不可满足**：`floorplan-common` 自身以 compile
   作用域依赖 `floorplan-domain` 与 `floorplan-application`（见 `common/pom.xml`），
   凡依赖 common 者 `mvn dependency:tree` 必含这两项。已登记为 **D13 §5.2 R11 待用户裁决**。
   裁决前的执行口径 = **源码级红线**：DMZ 代码里出现 `*ApplicationService` 引用即不合格。
   不许用 `<exclusions>` 绕——`GlobalExceptionHandler` 与 `JwtAuthInterceptor` 运行期真需要
   `ErrorCode` / `AuthApplicationService`，排掉就是 `NoClassDefFoundError`。
3. **`integration` 六表与 `floorplan_dmz` 是库级对象**，不随分支 schema 复制，所以 **W-U3 单人独占**
   （派发记录 §0 裁决 2）；并行期不要在本包之外的分支里建 `integration.*`。

## 本地跑法（W-U3 用，非本骨架验收）

启动必须 `env -u SERVER__PORT SERVER_PORT=18090`（AGENTS 坑 1），且「启动 → 冒烟 → 清理」写进同一条命令（坑 2）。
迁移由 `web` 执行，本进程 `flyway.enabled=false`（坑 3）。

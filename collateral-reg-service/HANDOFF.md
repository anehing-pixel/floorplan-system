# COLLATERAL HANDOFF · 担保权属权威源服务

## 1. 改了什么

### 提交记录
| commit | 内容 |
| --- | --- |
| `09d593f` | V18 迁移 + 担保权属领域模型（6 个类） |
| `70241ac` | 权威源裁决策略 + 参数化单测（10 用例） |
| `e84d7a8` | Repository / Service / Controller / 启动类装配 |

### 文件清单
**迁移（仅 V18 号段）**
- `infrastructure/src/main/resources/db/migration/V18__collateral_registration.sql`（新增）

**collateral-reg-service**
- `pom.xml`：新增 `spring-boot-starter-test`（test scope），供 JUnit 5 参数化测试使用。
- `CollateralRegServiceApplication.java`：`@Import` 增加 4 个新 bean；未改动端口/扫描路径/其它配置。
- `model/`：`Registration`、`RegistrationItem`、`SourceSnapshot` + `RegistrationStatus`、`SourceSystem`、`RegistrationEventType`。
- `strategy/`：`AuthoritativeSourceStrategy`（接口 + `Decision`）、`DefaultAuthoritativeSourceStrategy`。
- `repository/`：`JdbcCollateralRegistrationRepository`（`NamedParameterJdbcTemplate`）。
- `service/`：`CollateralRegistrationService`。
- `controller/`：`CollateralRegController`。
- `src/test/java/.../DefaultAuthoritativeSourceStrategyTest.java`。
- 本文件 `HANDOFF.md`。

未修改父 pom、任何 `application.yml`、`common/`、`web/`、`domain/`、`application/`、`infrastructure/` 下的既有 java、V16/V17/V19/V20/V21 迁移、其它服务目录。

---

## 2. 权威表结构（V18）

| 表 | 用途 | 关键列 |
| --- | --- | --- |
| `collateral_registration` | 一 VIN 一条**当前权属**（upsert 覆盖） | `vin`(PK)、`owner_name`、`reg_no`、`status`、`amount BIGINT`、`registered_at`、`discharged_at`、`source`、`updated_at` |
| `collateral_registration_history` | **权属流水/历史**（登记/变更/抹消/快照裁决，只追加） | `id`、`vin`、`event_type`、`reg_no`、`owner_name`、`status`、`amount BIGINT`、`source`、`note`、`occurred_at` |
| `collateral_source_snapshot` | **三源快照**（每次上报留痕，供冲突裁决回溯） | `id`、`vin`、`source`、`status`、`reg_no`、`owner_name`、`amount BIGINT`、`raw_payload JSONB`、`captured_at` |

时间列统一 `TIMESTAMPTZ`，金额统一 `BIGINT`（整数日元），索引见迁移文件。

**表名偏离说明（需 PM 确认）**：任务卡原拟把流水表命名为 `collateral_registration_items`，但该名已被 V10 第 185 行占用（登记批次↔车辆明细，含 `batch_id`/`ledger_id` 非空 FK）。若 V18 以同名建表，`CREATE TABLE IF NOT EXISTS` 在已应用 V10 的库上会**静默跳过**，仓储按新列读写将直接运行时报错。故流水表改名为 `collateral_registration_history`，语义不变。已在迁移头部注释标注。

---

## 3. Q27 阻塞标注在哪

Q27「担保以哪张表为准 / 三源冲突如何裁决 / 什么算 REGISTERED」业务口径尚未书面确认。本实现**不把未确认规则伪装成既定事实**，标注位置：

1. **V18 迁移头部注释**：明确"只提供结构与留痕，不写死判定规则"，开关打开前查询 fail-closed 返回 NONE。
2. **`DefaultAuthoritativeSourceStrategy` 类 javadoc + `decide()` 中的 `TODO(Q27)`**：说明优先级只是占位默认值，不是业务结论。
3. **`@Value` 开关**：`floorplan.collateral.authoritative-source.enabled`，**默认 `false`（关闭）**。关闭时 `decide()` 返回 `Decision.skipped(...)`，调用方保持当前权属不变，不据此放行门禁。
4. **`CollateralRegistrationService.importSnapshot()`**：`skipped` 分支只留痕、不改当前权属。
5. **本文件本节**。

**启用方式**（待 Q27 下发后，由部署侧配置，无需改代码）：
- 启动参数：`--floorplan.collateral.authoritative-source.enabled=true`
- 或环境变量：`FLOORPLAN_COLLATERAL_AUTHORITATIVE_SOURCE_ENABLED=true`

若届时业务口径与占位优先级不同，替换 `AuthoritativeSourceStrategy` 实现即可，不动仓储/服务/Controller。

---

## 4. Controller 与 CollateralClient 契约如何对齐

只读契约 `common/contract/CollateralClient.java` 未做任何修改。对齐点：

- Feign：`@FeignClient(name = "collateral-reg-service", path = "/api/collateral-reg")`
- 命中方法：`GET /api/collateral-reg/registration?vin=xxx`，`@RequestParam("vin")`
- **返回类型直接是 `CollateralClient.RegistrationVO`，不包 `ApiResponse`**——否则 core 侧 Feign 解码失败，直接走 `Fallback` 返回 NONE 拒付。
- 字段映射（`Registration` → `RegistrationVO`）：
  - `status` ← `RegistrationStatus.name()`（REGISTERED / PENDING / CANCELLED / NONE）
  - `regNo` ← `reg_no`
  - `ownerName` ← `owner_name`
  - `registeredAt` ← `registered_at.toString()`（ISO）
- 查无记录返回 `Registration.none(vin)` → `status=NONE`，fail-closed。

**其它端点**（非契约，供运营/导入）：
- `GET /api/collateral-reg/registration/history?vin=` 权属流水
- `POST /api/collateral-reg/registration` 登记
- `POST /api/collateral-reg/discharge` 抹消
- `POST /api/collateral-reg/snapshot/import` 三源快照导入

**装配方式**：新类均不加 `@Component/@Service/@Repository/@RestController`，由启动类 `@Import` 显式注册，避免与 `scanBasePackages` 组件扫描重复注册。`CollateralRegController` 使用 `@RequestMapping` + `@ResponseBody`（`@RequestMapping` 足以让 `RequestMappingHandlerMapping` 识别为 handler；`@ResponseBody` 保证返回值直接序列化）。

---

## 5. 单测结果

`DefaultAuthoritativeSourceStrategyTest` 共 **10 个参数化用例，全部通过**：
- 策略关闭：即使三源均 REGISTERED 也不采纳（fail-closed）
- 开启 + 空快照 / 未知来源：不采信、保持原状
- 开启 + 三源一致 REGISTERED / NONE：直接采纳
- 开启 + 冲突：批次表优先、批次表缺失时台账优先、仅车辆源可用
- 同源多份快照取 `capturedAt` 最新一份

过程中曾发现"未知来源被当成状态一致而误采纳"的漏洞，已修复为未知源不参与裁决，并补了回归用例。

命令：`mvn -q -pl collateral-reg-service -am test` → `TEST_EXIT=0`。

---

## 6. 验证结果

- `mvn -q -pl collateral-reg-service -am test`：通过（见上）。
- `mvn -q -pl collateral-reg-service -am package`：通过（`PACKAGE_EXIT=0`）。
- **无数据库环境下的限制**：未做真实 PostgreSQL 的集成/端到端测试（环境无 `localhost:5434` 实例）。编译、单测、Spring 装配均不依赖数据库即可通过；仓储 SQL 的正确性需在联调环境用真实库复核。

---

## 7. TODO / 交接注意

1. **Q27 定源**：业务书面确认后，决定是打开开关沿用占位优先级，还是替换策略实现。
2. **core 侧 Feign JWT 传播（协作点）**：`WebMvcConfig` 拦截全部 `/api/**` 且无放行路径。core 通过 Feign 调 `GET /registration` 时若不传播 `Authorization`，会被 401 挡下、直接走 `Fallback` 返回 NONE 拒付。需 core 侧确认 token 传播或在网关侧放行该内部端点——本服务不跨目录修改。
3. **V10 表名冲突**：流水表改名 `collateral_registration_history`，需 PM 确认是否接受；若必须叫原名，需另行处理 V10 既有表的兼容（不能靠 `IF NOT EXISTS`）。
4. **三源快照的实际导入作业未实现**：需要 vehicle / collateral-ledger / 批次表各自推送或定时拉取后调用 `POST /snapshot/import`，属跨服务依赖，此处留 TODO，未跨目录改代码。
5. **真实 DB 复核**：V18 迁移在 PostgreSQL 上的执行、JSONB CAST、`TIMESTAMPTZ` 时区转换需联调环境验证。

# PROGRESS · 单一事实来源（断点续跑）

> **任何模型/任何人接手，第一件事：读本文件。** 它比任何聊天记录、路线图都新。
> 更新规则：**每完成一个原子步骤，立刻改本文件对应行 + commit**，不要攒着批量更新。
> 本文件记录"代码实查状态"，不记录"计划做什么"。计划见路线图，状态见本文件。
>
> **协作模式（2026-11-23 起）**：单 agent 串行开发。此前多 agent 并行波次（Wave 0–2、USED W-U1…W-U5）
> 留下的 worktree 表、任务卡、派发/验收/合并规则、schema 隔离脚本**已废弃并删除**，
> 那段过程记录要查就查 git 历史（`git log --oneline -- PROGRESS.md docs/`，删除前的最后一份是 `d3ec429`）。
> 本文件只保留仍然成立的事实与待办。

最后更新：2026-11-24（GMT+8） · **读接口授权矩阵已签字并实施（B 功能矩阵：13 个读点 + 14 角色 READ_MATRIX + 21 个 Controller GET 注解），主干/事件 290、USED 209、浏览器 37 全绿**；
此前：生产就绪：弱密钥/多实例配置启动期 fail-fast（prod profile）+ EventDispatcher 消费者游标租约（V83）；
此前：安全基线落地：JWT 可撤销（V82 token_version + 退出登录）、每次请求权限回查、未知异常不再回传内部信息；单测 + 冒烟 ㉖ 覆盖；
此前：网关路由吞噬修复：`/api/dealers/*/bank-accounts` 精确路由 + `GatewayRouteOrderTest` + 经网关冒烟断言；
此前：解押跨域释放落地：core（floorplan-core）消费 PAYMENT_MATCHED，settled=true 时真正解除车辆担保与担保物台账、生成抹消待办（V81）；
此前：PAYMENT_MATCHED 生产/消费链路落地（真实借据 Feign + JDBC 匹配存储 + credit-service 回补 + 四条丢失断言重建）；
此前：生产级差距清单落档（全局 + 13 模块代码实查，仅分析、未改代码）；
此前：浏览器端到端测试首次落地（37/37）+ 修复参数绑定异常误报 500（见 §2）；
此前：USED R2 车辆主数据／車検証切片贯通（见 §2），R1 之后第二个贯通的业务切片；
此前提交进仓库的 domain 三件套里那份 stash 冲突标记已解，
迁移集补齐到 V1–V74，`floorplan` 库改成从迁移链干净重建（56 版、现位于 v74），
旧库保留为 `floorplan_legacy_20261123` + 整库备份。开发分支 `used-floorplan-dev`。

---

## 0. 现状三句话

> **服务拓扑与「目标态 vs 现状」的差**见《库存融资核心系统-目标微服务架构设计.md》**§0 当前实况**（2026-11-23 实查）：
> 11 个可部署进程 = 1 网关 + 9 业务服务 + 1 DMZ 接入面，另有 4 个共享库模块（domain/application/infrastructure/common）；
> 物理拆库未开始（仍共用 `public` schema）；Feign 与 outbox 机制已落地但只通一条异步链路。

1. **核心（新车）链路可运行**：16 个构建单元编译全绿、主干冒烟 211 条 `[PASS]` / 0 FAIL、
   10 服务启停正常（`run-microservices.sh` 一键起的 10 个进程，不含 DMZ 的 `external-api`）、
   `work_orders` 之外的业务表齐全。历史技术债（B-2 处置解冻、B-3 处置期不回补、
   collateral-reg 全端点 500、`/api/payments/**` 被 core 兜底 404、四个新服务漏装 `AuthApplicationService`、
   `V19` 部分唯一索引非法语法）**均已闭环**，细节在 git 历史里。
2. **事件链路已通两根线，回款链的回补与解押都已接上**：`LOAN_CREATED`（放款确认 → 建借据）；
   `PAYMENT_MATCHED`（回款匹配 → credit-service 额度回补 + core 解押，2026-11-24）。
   core 侧 `PaymentMatchedSecurityConsumer` 在 `settled=true` 时置
   `vehicle_units.security_status=RELEASED`、`collateral_ledger.released=true`、登记批次抹消 `REQUESTED`。
   `COLLATERAL_CHANGED` 仍零调用方。**仍未接**：release-service 的指令域消费；
   与直接还款路径共用 `settle_seq` 的双重释放防线（USED R8 §8.1）。见 §3 的开放项。
3. **USED（二手车库存垫资）已从「只有库结构与字典层」推进到「R1 + R2 贯通」**：V40–V74 已落库（资质/车辆/車検証/担保多源/估值/LTV/
   拍卖/库龄/监管仓/放车放证/登记委托/集成五段）、`Permission` 69 点与 `UserRole` 14 岗已含 USED 权限点、
   错误码段（QUAL_/CERT_/VALU_/LTV_/AUC_/REGI_/WMS_/RELS_/PTNR_）已定源。
   **R1 车商资质（准入闸门 G-A1〜G-A4）与 R2 车辆主数据／车况票／里程／車検証收证换发**均已有业务层与端点并过网关冒烟；
   余下估值分档、落札、
   放款闸门、WMS 账实、放车放证、回款回补与外部接入层仍在重写中，规格源 = `docs/二手车库存垫资改造-06/-07/-08/-09`。

---

## 1. 全局环境事实（别再踩坑）

| 项 | 事实 |
|---|---|
| 仓库根 | `C:/Users/Administrator/Documents/GitHub/floorplan-system` |
| 分支 | `master`（主干）· `feat/payment-matched-chain`（PAYMENT_MATCHED 链路，2026-11-24 从 `681ed7a` 切出；`used-floorplan-dev` 已不存在，此前 USED R1–R3 实际落在 `master`） |
| 数据库 | Docker 容器 `floorplan-db2`，端口 **5434**（5432 被占用），库/用户/密码均 `floorplan`；宿主无 `psql`，用 `docker exec floorplan-db2 psql -h 127.0.0.1 -p 5434 -U floorplan -d floorplan` |
| 库的来源 | **2026-11-23 从仓库迁移链干净重建**：`public` 重建那一刻 = 56 版 Flyway、位于 v74，90 张表（此后 R1/R2 又落了 V75–V78，现位于 v78，见下一行）。重建前的库改名 `floorplan_legacy_20261123`（内含并行期 agent 直打 public 留下的漂移：history 记到 V27 而 `V27__release_payment_ref.sql` 只存在于未合并的 `agent/w1-release`；另有六个 agent 隔离 schema）。整库备份 `backups/pre-used-rebuild-20261123-102807.sql.gz` |
| 迁移号段 | `V1`–`V74` 已落库**禁改**（checksum）。USED 占 `V40`–`V79`：**V75–V77 已被 R1 资质切片占用**（V75 种 3 个配置键、V76 合规二人签认列+CHECK、V77 dealers 双维评分四列+CHECK），**V78 已被 R2 占用**（种里程差异阈值 `floorplan.used.mileage-diff-threshold-km`），**V79 已被 R3 占用**（种估值三键 + 回正 V78 source_ref）。**V80 已被 PAYMENT_MATCHED 回补占用**（`credit_line_restorations`，credit-service 消费侧 L2 幂等表），**V81 已被 core 解押释放占用**（`payment_security_releases`），**V82 已被安全基线占用**（`users.token_version`），**V83 已被多实例游标租约占用**（`consumer_offsets.lease_owner/lease_until`；2026-11-24 已落库，下一可用号 **V84**，取号前先 `order by installed_rank desc` 复核——`max(version)` 是字符串排序，会把 '9' 排到 '79' 前面）。库里现位于 **v83 / 65 版**。⚠️ 号段只能往 head 之上取：库里 head=V74 且 Flyway 未开 `out-of-order`，低号（含 D04 §2 预留的 V42–V44）会让启动校验抛 `Detected resolved migration not applied to database` 整栈起不来，那几个号从此是永久空号。核心侧下一可用号 **V27**；`V32`–`V39` 是有意留的空隙，别占。逐号台账以本节为准，改号段先在这里登记 |
| 编译 | Windows JDK 17 + Maven 3.9.16 |
| 启动坑 | 启动一律 `env -u SERVER__PORT`（否则覆盖各服务端口）；`run-microservices.sh` 已处理 |
| 进程坑 | 后台 JVM 随 shell 回收 → 启动 + 测试 + 清理必须在**同一条命令**内 |
| 注册中心 | 本地不启 Nacos，`fail-fast=false`，注册失败仅告警 |
| 验证金标准 | `PYTHON=python PYTHONIOENCODING=utf-8 bash run-microservices.sh smoke` → EXIT 0 且 FAIL=0（断言数随开发增长，**不写死数字**）。两条实测陷阱：① 本机 Git Bash 没有 `python3`，不带 `PYTHON=python` 会 EXIT 127、服务起了但冒烟一行没跑；② stdout 一重定向/接管道，Windows 上 python 回落 cp936，`smoke-test.py` 里 53 行含 GBK 编不出的带圈数字 ⟹ 只打 51 条 PASS、后 160 条没跑、EXIT 1 而屏幕半屏绿。`run-microservices.sh:40/:50` 已注入两个默认值兜底，**不经本脚本直接跑 `python smoke-test.py > x.txt` 仍要自己带 `PYTHONIOENCODING`** |
| 取证陷阱 | 不要把 mvn 输出接管道给 `tail`：管道退出码是 `tail` 的，实测 BUILD FAILURE 而 `$?` = 0。要么 `> file 2>&1` 后 `grep -E "BUILD\|ERROR" file`，要么自己 `$?` |

---

## 2. USED 改造：本轮已落地的基线

| 项 | 内容 | 证据 |
|---|---|---|
| 冲突标记清理 | `domain` 的 `Permission.java` / `UserRole.java` 里有提交进 master 的 `<<<<<<< Updated upstream` 三处，整个 reactor 编不过 | `20fafa6`；`grep -cE "^(<<<<<<<\|=======\|>>>>>>>)"` = 0 |
| 权限点并集 | 69 个权限点（含 19 个 USED R5.6/R6/R7 写点）；`READ_TIER` 隐式读层照 serial 版 | `Permission.java` / `UserRole.java` |
| 角色授予 | USED 写点已入岗：設質/登记发起 → `COLLATERAL_OFFICER`，确认/撤销/快照认定 → `POST_LENDING_MANAGER`，建单/放证/代录 → `SETTLEMENT_OFFICER`，下发 → `FUNDING_CASHIER`，主数据 → `SYSTEM_ADMIN`。D-2/D-6/D-7/D-8 四对 SOD 在角色层不闭环；`RELEASE_SETTLE` 不授任何角色 | 同上；14 个角色 `set()` 内无重复元素（重名会让 `Set.of` 在类初始化就抛） |
| 错误码并集 | 补入 `REGI_001/002`、`WMS_001/002/003`、`RELS_001/002/003/004/006` | `ErrorCode.java` |
| 迁移集 | V40–V64 + V70–V74 从 `used/serial-20261123` 取入，与 master 原有 V65–V69 合成 USED 全量结构。**V65–V69 此前从未在任何库上执行过** | 空库启动 → `Successfully applied 56 migrations ... now at version v74`；`SqlStructureSelfCheck` 13 张表列名一致 |
| **USED R1 业务切片（2026-11-23 12:20 落地）** | 车商资质准入打通到端：`DealerAdmissionPolicy`（D03 §2.1 五规则按序判定，无白名单入参）＋ `DealerQualificationRepository`/`UsedConfigRepository` 两套接口与 JDBC 实现 ＋ `DealerQualificationApplicationService`/`DealerRatingApplicationService` ＋ 8 个端点 `/api/dealer-qualifications/**`（路径裁决见控制器类注释：`/api/dealers/**` 已被网关让给 credit-service，故另起前缀由 core 兜底）＋ V75–V77 三版迁移。二人红线（合规签认、双维评分）同时落在服务判定与库内 CHECK 两处 | `mvn -B package` 全绿；**经网关** `SMOKE_BASE=http://127.0.0.1:18080 python smoke_used.py` → 43 条 `[PASS]` / 0 FAIL / EXIT 0（含 401/403 分档、四类合规 UNKNOWN 不折叠、同人自证 403、换发降级、配置窗口改值即变、直写库被 CHECK 拒） |
| **USED R2 业务切片（2026-11-23 13:20 落地）** | 车辆主数据／車検証打通到端：`MileageSource`/`RepairHistory`/`EncumbranceType`/`CertificateStatus` 等 13 个 domain 模型＋`MileageResolutionPolicy`（来源可信序＋采集时点＋取严，纯函数）＋`CertificateStateMachine`＋`EncumbrancePolicy`＋`UsedVehicleMasterRepository`/`JdbcUsedVehicleMasterRepository`（V50/V51/V52/V53/V66 五张表，只写 USED 列，不动 LEGACY 业务列）＋`UsedVehicleMasterApplicationService` 6 个用例＋`/api/used-vehicles/**` 6 个端点＋V78 阈值种子。四个坑本轮各自修掉：① 非法枚举词由用例翻成 400（原先 IllegalArgumentException 冒成 500 SYS_001）；② 收证先判「同証書番号」再判「在场证」，顺序反了幂等重放永远回 409；③ 换发必须先让旧证 `REISSUED` 再插新证，否则撞 `uk_cert_active_vin`；④ 控制器 `toDraft` 必须传**报文** vin，传路径 vin 等于把 G-D1 那条不一致校验永久短路 | `mvn -B package` 全绿、`mvn -B -pl domain,application -am test` 49/49；**经网关** `SMOKE_BASE=http://127.0.0.1:18080 python smoke_used.py` → 101 条 `[PASS]` / 0 FAIL / EXIT 0（含 UNKNOWN 里程「建账放行、放款阻断」、阈值从库里现读（改成 0 立刻开单）、票先于建账入账、二人换发留下 AMENDED/REISSUED/RECEIVED 流水、ADMIN 无换发审批位） |
| **USED R3 业务切片（2026-11-23 15:20 落地）** | 估值与 LTV 定价打通到端：`VehicleValuation`/`ValuationInput`/`LtvPolicy`/`LtvBand` 等 12 个 domain 模型＋`PrudentValueCalculator`（多源审慎值算法，MIN_OF_SOURCES/WEIGHTED 两档）＋`LtvPolicyValidator`（L1〜L4 不变式校验，纯函数）＋`LtvTierResolver`（分档定档，边界样本导出）＋`UsedAdvanceCeilingCalculator`（覆盖上限 115%）＋`UsedValuationApplicationService`/`LtvPolicyApplicationService` 11 个用例＋`/api/valuations/**` 4 个端点＋`/api/ltv-policies/**` 5 个端点＋V79 配置种子（三键：valid-days/uplift/rule）。鉴权链路修复：`UserRole.implicitReadPermissions()` 新增，`RoleSeparationPolicy.effectivePermissions()` 并上隐式读层（根因：`can()` 里的读层放行从未参与令牌签发，带 `@RequirePermission` 的查询端点对所有角色回 403） | `mvn -B clean package` 全绿；**经网关** `SMOKE_BASE=http://127.0.0.1:18080 python smoke_used.py` → 209 条 `[PASS]` / 0 FAIL / EXIT 0（含单源/降级源/缺署名一律拒在写之前、同键重放幂等、二人覆盖 +115% 上限 + 同日防线、L1〜L4 校验未通过整体不写、换人批准生效、试算只算不落、配置键三键读库含脏值 fail-closed） |
| 自检口径修正 | `SqlStructureSelfCheck` 原来只查 INSERT 列、且不带 schema 过滤。R1 首跑时 `UPDATE dealers ... SET updated_by=?` 撞 500（该表从 V1 起就没这列）暴露两个洞：① UPDATE 引用的列不在任何 INSERT 清单里 ⟹ 查不到；② 同库有 `floorplan_legacy_20261123` 同名表，只按 `table_name` 过滤会把两套列并起来 ⟹ 假通过 | 已加 `table_schema = current_schema()` 与 `dealers` 条目；启动期 **22 张表**列名一致（R2 再补 4 张 USED 表条目，并把 `vehicle_units` 清单加进 `updated_at`） |
| **浏览器端到端测试（2026-11-23 17:40 落地）** | 新增 `browser-test.py`（Playwright + 本机 Chrome，经网关 18080）：登录页错误密码/正确登录/令牌写入、11 个视图逐个真实渲染、新增/编辑经销商弹窗、名下车辆/车辆详情抽屉、全局刷新、admin vs operator 写按钮权限差、退出登录、浏览器内 fetch 冒烟；全程收集 console.error/pageerror/失败请求并落截图到 `browser-test-results/`（已 gitignore） | `PYTHONIOENCODING=utf-8 python browser-test.py` → **37 PASS / 0 FAIL / EXIT 0**，截图 18 张；主干 `smoke-test.py` 211 PASS / 0 FAIL、USED `smoke_used.py` 209 PASS / 0 FAIL |
| **修复：参数绑定异常误报 500（浏览器测试揪出）** | `GET /api/vehicles`、`/api/credit-lines` 缺必填 `dealerId` 时抛 `MissingServletRequestParameterException`，web 与 common 的 `GlobalExceptionHandler` 都只兜底到 `handleUnknown` ⟹ 500 SYS_001。这属客户端传参错误，应是 400 | web/common 两处 `GlobalExceptionHandler` 新增 `handleBadRequest`，接 `MissingServletRequestParameterException`/`MethodArgumentTypeMismatchException`/`HttpMessageNotReadableException` → 400 VALID_001（`ErrorCode.VALID_001` 既有，400）。浏览器测试加两条断言钉住：缺参 `/api/vehicles` 与 `/api/credit-lines` 均返回 400 VALID_001（不再 500）；`mvn -B -DskipTests package` 全绿 |
| **读接口授权矩阵（2026-11-24 签字并实施）** | Q-R1~R8 签字：B 功能矩阵、13 个细粒度读点、PII 不授、ADMIN/READONLY 全业务读点、○ 按 —、数据范围 C 下期、读点仅作令牌展示且鉴权每次回查。代码：`Permission` 新增 13 读点；`UserRole.READ_MATRIX`（写点/读点物理分离，`READ_TIER` 删除，`isWriter()` 只认写点）；`RoleSeparationPolicy.effectivePermissions` 并集写+读；21 个 Controller 的 GET 加 `@RequirePermission`（唯一豁免 `/api/attachments/local-download` 预签名 URL）。实施修订 3 条（冒烟/浏览器实测）：`BUSINESS_OPERATOR` +`CREDIT_READ`/`RISK_READ`，`PURCHASE_OPERATOR`/`PURCHASE_REVIEWER` +`LTV_POLICY_READ`。文档 `docs/读接口授权矩阵.md`（原草案已升格）。**未做**：数据范围 C（OWN_DEALER/OWN_PARTNER）、PII 二人授权流程 | `mvn -B package` 全绿；`run-microservices.sh smoke` **290 PASS / 0 FAIL**；USED 冒烟 **209/209**；浏览器 **37/37**；GET 审计仅 1 个豁免 |
| **生产就绪：密钥 fail-fast + 多实例游标租约（2026-11-24 落地）** | **启动期检查**：common 新增 `ProductionReadinessCheck`（Spring Boot 自动配置导入，十服务 + web 一次覆盖）。激活 `prod`/`production` profile 或 `floorplan.security.strict-secrets=true` 时，以下任一命中即**拒绝启动**：JWT 密钥为开发默认/不足 32 字节、webhook 密钥为开发默认、DB 密码为 `floorplan`/`floorplan_dmz`、`lock.provider` 非 redis、`bank-fetch.source=demo`；本地无 profile 只 WARN。**多实例游标**：V83 给 `consumer_offsets` 加 `lease_owner/lease_until`；`EventDispatcher` 每轮原子认领/续租（2 分钟），未抢到直接跳过；`@PreDestroy` 优雅停机立即释放租约，重启无需等待；接管语义 at-least-once，由 `event_consumptions` + 业务唯一键兜底。**未做**：作业定时调度（仍手动 `POST /api/jobs/*`）、读接口授权矩阵（与现有「读接口不设权限点」定源冲突，待裁决）、Redis 锁实例落地、密钥轮换 | `mvn -B package` 全绿（`ProductionReadinessCheckTest` 8 条）；`run-microservices.sh smoke` EXIT 0：主干 219 + 事件 **71** = **290 PASS / 0 FAIL**（含 3 条 `lease_owner` 生效断言） |
| **安全基线（2026-11-24 落地）** | **JWT 可撤销**：V82 `users.token_version`；登录写入 `ver` 声明，`AuthApplicationService.verify()` 每次请求回查（签名/过期 → 用户启用 → 版本 → 角色）；auth-service 新增 `POST /api/auth/logout` 把版本 +1，旧令牌立即 401。**权限回查**：每次请求从 `user_roles` 现算角色与有效权限，拦截器不再信任令牌 `perms/roles` 快照；删角色后旧令牌立即 403；禁用账号旧令牌立即失效。**异常脱敏**：common/web 两处 `handleUnknown` 只回 `SYS_001` 固定文案 + traceId，完整栈进服务端日志。单测 6 条（application 4 + common/web 各 1），冒烟 ㉖ 7 条。**未做**：读接口租户级授权矩阵（需业务裁决）、refresh/jti/kid/aud、JWT/DB/webhook 密钥强校验与轮换、MFA/失败锁定/登录审计 | `mvn -B package` 全绿；`run-microservices.sh smoke` EXIT 0：主干 **219** + 事件 68 = **287 PASS / 0 FAIL**（㉖：删除 user_roles 后同令牌 403、logout 后旧令牌 401、重新登录成功） |
| **网关路由吞噬修复（2026-11-24 落地）** | `gateway/application.yml` 新增精确路由 `core-dealer-bank-accounts`（`Path=/api/dealers/*/bank-accounts` → core），排在 `credit` 宽路由（`/api/dealers/**`）之前；新增 `GatewayRouteOrderTest`（不启动网关，按 YAML 顺序 + AntPath 首个命中断言 15 条路径 + 1 条顺序）；`smoke-test.py` ㉑ 前新增经网关的 `POST /api/dealers/{id}/bank-accounts` 断言 + `dealer_bank_accounts` 清理 | `mvn -B package` 全绿（gateway 1 test）；`run-microservices.sh smoke` EXIT 0：主干 **212** + 事件 68 = **280 PASS / 0 FAIL** |
| **解押跨域释放（2026-11-24 落地）** | core（floorplan-core）新增 `PaymentMatchedSecurityConsumer`：消费同一 `PAYMENT_MATCHED`，`settled=true` 时行锁释放 `vehicle_units.security_status`、`markReleased` 担保物台账、登记批次抹消 REQUESTED；V81 `payment_security_releases(payment_ref)` 做 L2 幂等；`settled=false`/缺失一律跳过（部分回款不解押）；台账缺失写 HIGH 告警但车辆侧仍释放；**不碰额度**（credit-service 负责），避免跨域直写与双重回补 | `mvn -B package` 全绿；`run-microservices.sh smoke` EXIT 0：主干 211 + 事件 **68** PASS / 0 FAIL（C1b 真实解押、C2 重放不重复、C3 部分回款不释放、C4 人工绑定释放） |
| **PAYMENT_MATCHED 生产/消费链路（2026-11-24 落地）** | bank-service：`FeignLoanLookupClient` 替换日志存根（真实契约 `LoanQueryClient`；404=无在贷借据，5xx 抛异常）、`JdbcPaymentStore` 替换内存存储（V17 三表，匹配结论重启不丢）、`PaymentMatchedPublisher` 在匹配事务内发 `PAYMENT_MATCHED`（键 `paymentRef`；`settled`/`restoreAmount`/`creditLineId` 口径见契约 §2.2）、`ReleaseOrchestrator` 不再写 `availableAfter=before` 冒充回补（V17 只登记 PENDING 请求）；credit-service：`PaymentMatchedConsumer` 行锁真实回补 + V80 `credit_line_restorations` L2 幂等 + 实现 `GET /api/credit-lines/{id}/available`；删除 BigDecimal 死契约 `CreditLineClient`。本行落地时 core 解押尚未接（随后由上一行补齐）；仍缺 release-service 消费（USED R7） | `mvn -B package` 全绿；`PYTHON=python PYTHONIOENCODING=utf-8 bash run-microservices.sh smoke` EXIT 0：主干 211 PASS + `smoke_payment_matched.py` **50 PASS / 0 FAIL**（随后扩到 68，见上一行） |
| **生产级差距清单（2026-11-23 22:04，仅分析零代码改动）** | 全局 + 按模块两份视角归集成 `docs/生产级差距清单.md`。**全局 P0 六项**：密钥无强校验（web 硬编码 `floorplan/floorplan`、JWT/webhook/账户 key 全有 dev 兜底）、核心闭环未完成、生产开关默认关、JWT 不可撤销且权限不回查、无健康检查/容器化/保活、无备份恢复。**模块视角 13 块**：gateway 路由吞噬 `/api/dealers/{id}/bank-accounts`（经网关必 404）＋治理六项全无；web 出款是内存 Mock＋银行流水 demo＋作业全手动点；auth 无 MFA/锁定/审计/租户；external-api 纯骨架零 Controller；common 幂等键只是回显＋Feign 无认证头致担保门禁形同虚设；bank 匹配只两级＋内存存储重启即丢；loan 借据 7 态只写 3 态、还款计划利息恒 0；release 解押只打日志、回补 `availableAfter=before`；recon 只比内部表、actor 不写审计；credit 可用额度三套口径、乐观锁无 `AND version=?`；risk 告警只落库、`risk_rules` 零读写；collateral-reg 零事务/零锁、门禁默认关 | 全部条目带文件路径+行号，可复现。动手顺序见该文档 §3。**注意：本文是分析快照，唯一事实来源仍是本 PROGRESS.md；业务口径以 ADR 总定源为准** |

**架构裁决（本轮定，写进代码注释）**：不逐字搬任一并行分支的业务层，结构取 serial 版、
application/web/integration 层重写。理由：`used/wu1`…`used/wu5` 五路都没按 D07 契约写完且 master 上
混着半截；serial 分支虽绿但本身是各分支的拼装，未经验证。

**实现与设计稿的偏差登记（R2 轮，逐条都是实查后的事实，不是「以后再补」的托词）**：

- 里程来源字典按**库内 CHECK** 落，不照抄 D06 §2.4 的名字：D06 那份清单里的 `REGISTRY`（陸運局滞后登记）
  在 V50 取值域里没有对应值，实现归入 `DEALER_DECLARED`；`WMS_SNAPSHOT` 与 `MANUAL` 同为「自己数出来的」，
  归入 `OWN_AUDIT`。对应关系写在 `MileageSource` 类注释里，优先级序（实物 &gt; 自盘 &gt; 票面 &gt; 申报 &gt; 未知）
  与 §2.4 一致。
- **修復歴取严不建差异行**：`wms_reconcile_diffs.diff_type` 的 CHECK 里没有 `MASTER_DATA_DIFF` /
  `REPAIR_HISTORY_DIFF` 槽位（V66 只给了 `ODOMETER_DIFF` 等实物差异），所以票面取严的结果只进 `audit_events`。
  要开单就得新增一版放宽 CHECK——不在 P0 主链，登记为缺口而不是偷偷写进别的 diff_type。
- `vehicle_units` 没有 `idempotency_key` 列 ⟹ 重复建账不做「静默返回既有行」，回 409 `VEHICLE_003`
  并在 `details` 里带 D06 §2.5 那个键名 `VEH:{vin}`。补列的必要性（第二次请求是不是同一份数据无法判断）
  属待裁决项，随 R4 一起提。
- `AUC_006`（VIN 不在落札集，D06 §2.5 点名）在 `ErrorCode` 里**还不存在**，随 R4 落札切片补，
  本轮不预先造一个没有抛点的错误码。
- UC-R2-4 导入模板（`IMP:{batch_id}:{row_no}`）与 `syncFromAuctionLot` / `syncFromWmsInbound` 未实现，归 R4/R6。
  因此 R2 现在的观测入口只有两条：HTTP 推票与手动重算——**别把「策略支持五源」读成「五源都会来」**。
- `floorplan.used.enabled`（D00 P-3 的配置闸门）全仓零引用，USED 端点当前无条件可达；
  实际生效的只有 `vehicle_type='USED'` 那道数据闸门。灰度回退属 R14，本次范围外（§5.3 那行口径已按实修正）。
- `floorplan.used.master-data-diff-alert-enabled`（D06 §2.5 第二项）**没种**：代码里没有读方
  （主数据差异告警未接线），按 `UsedConfigKeys` 自己的原则「只登记真的有读方的键」留空，
  接上告警那条线时再随迁移种值。

**实现与设计稿的偏差登记（R3 轮，逐条都是实查后的事实，不是「以后再补」的托词）**：

- **鉴权链路修复（READ_TIER 不在令牌签发路径）**：`UserRole.can()` 里的读层放行从未参与
  `RoleSeparationPolicy.effectivePermissions()`，导致令牌 `perms` 缺读点、带 `@RequirePermission` 
  的查询端点对所有角色回 403。修复法：新增 `UserRole.implicitReadPermissions()` 返回 `READ_TIER`，
  `effectivePermissions()` 并上隐式读层。不改成「给每个角色手铺读点」：逐角色读矩阵属 D10 未签字部分
  （AGENTS §0 铁律 3），而且读点铺进 `permissions` 会让「该角色是否写岗」的 `isWriter()` 判定失真。
- **SOD 同人分支不可达**：当前角色矩阵下 `MANAGE` 与 `APPROVE`、`RECORD` 与 `OVERRIDE` 都不并列，
  所以换人校验只可能以 403 `AUTH_002` 出现（角色层就拦了），同人分支（`AUTH_003`）在冒烟里触不到。
  冒烟断言 `SOD_VIOLATION_BLOCKED == 0` 并如实登记缺口，同人分支由单测覆盖。
- **政策列表不新建 GET /{id}**：D07 §7.2 的契约里没点这个端点，`GET /api/ltv-policies?status=ACTIVE`
  的响应已经内联带集合与校验结论（PUT 是全量替换，客户端必须能读到当前带集当底本）。新建 GET /{id}
  会让「读草稿」与「读生效版」的语义混在一起，不如让客户端用 list 筛 status。
- **同日重复覆盖回 409 CONFLICT_001**：D08 §3.4 的「同日防线」在代码里落成了「同一估值行当天
  第二次覆盖回 409」，而不是「回 200 但留审计痕迹」。理由：覆盖是高敏操作（改放款基准），静默成功
  会让调用方以为「改完了」而不去翻审计表；显式 409 强制调用方知道「今天已经有人动过这台车的估值」。
- **两条刻意不种的绕过键**：① `valuation.require-second-source-for-advance=false`（D08 §3.2 的
  「除人工覆盖外，单源不得作放款基准」是与 Q21 同族的硬策略，给它一个能置为 false 的键 = 留一条
  把硬策略软放行的可配置旁路）；② `valuation.default-max-ratio-fallback=0.40`（兜底成数已经有
  权威源 = `ltv_policies.default_ratio`，再种一枚全局默认值会把 L2 阻断改成放行）。两枚都登记在
  §4.1 待签字清单的「刻意不种」行。

---

## 3. 集成 TODO（代码里留了接缝，仍开放）

> 前一轮（Wave 1/2 + 收口期）的 14 项**全部 ✅ 已闭环**，此处不再罗列，要查历史看 git。
> 下面只列**仍然开放**的项——它们多数正是本次 USED 改造要顺手接上的线。

| 项 | 问题 | 状态 |
|---|---|---|
| **事件生产者已接两个，`COLLATERAL_CHANGED` 仍零调用方** | 真实调用方：`AdvanceApplicationService.confirmAdvance`（`LOAN_CREATED`，键 `advanceId`）与 bank `PaymentMatchApplicationService`（`PAYMENT_MATCHED`，键 `paymentRef`，2026-11-24）。`COLLATERAL_CHANGED` 仍零调用方，聚合键与 `status` 取值域待 Q-EVT1 | ✅ `PAYMENT_MATCHED` 已接；`COLLATERAL_CHANGED` ⬜ 等业务 |
| **发布端 `NOT EXISTS` 去重（两半都已验）** | `LOAN_CREATED` 那半已有端到端断言；`PAYMENT_MATCHED` 那半 2026-11-24 补上：`smoke_payment_matched.py` B5 先让事件投递成 `SENT`，再**删 `payment_matches` 行、保留 outbox 的 SENT 行**从 HTTP 入口重放匹配 ⟹ 仍只 1 条事件。这条取法避开了 `uk_payment_match_txn` 的归因干扰。抑制条件仍是 `status IN ('PENDING','SENT')` | ✅ 已闭环 |
| **回款匹配后的回补与解押均已真实落地** | credit-service `PaymentMatchedConsumer` 行锁回补 + V80 `credit_line_restorations(payment_ref)` L2；core `PaymentMatchedSecurityConsumer` 在同一事件上释放车辆担保、担保物台账与抹消待办 + V81 `payment_security_releases(payment_ref)` L2。`ReleaseOrchestrator` 不再写 `availableAfter = before` 冒充已回补。**仍未接**：release-service 的指令域消费；「银行匹配」与「直接还款」尚未共用 `settle_seq` 幂等键（USED R8 §8.1），两条路径同时走时额度可能被释放两次；⚠️ 两个消费方是同一事件上的**两个独立事务**，core DEAD 而 credit 成功会出现「额度已回补、担保未解除」的部分状态，需 `LEDGER_GAP` 巡检兜底（未实现） | ✅ 回补+解押闭环；🟡 双路径幂等/部分状态巡检待定源 |
| **`COLLATERAL_CHANGED` 聚合键与 `status` 取值域未定源** | 属业务口径（Q-EVT1），契约文档里留白并标 owner，没按猜测填 | ⬜ 等业务 |
| **`DEAD` 事件无人工重放接口** | 死信只能查 `event_consumptions.status='DEAD'`；游标已越过该 id，回退会连带重放历史事件 | ⬜ 开放（接缝保留）|
| **多实例共用一条游标** | `consumer_offsets` 按 `consumer` 一行，两进程同名会互覆盖游标 → 漏读/重复读。单实例部署未暴露；**多实例前必须**改 `FOR UPDATE SKIP LOCKED` 或按实例名分片 | ⬜ 开放（部署前置条件）|
| **credit 两条死契约（Q-EVT2）** | 已二选一：实现 `CreditLineQueryClient`（`GET /api/credit-lines/{id}/available`，`ApiResponse<Long>`，整数日元，负数截 0），删除 `CreditLineClient`（`GET /available?dealerId=`，`BigDecimal`）及其 bank 存根 `LoggingCreditLineClient` | ✅ 已闭环 |
| **旧措辞仍留在共享代码里** | `V21` 迁移注释写「aggregate_key 通常是 vin 或借据号」，与已裁决口径**相反**。`V21` 已落库不能改 ⟹ 只能改 javadoc；`OutboxPublisher` / `DomainEvent` 的 javadoc 已于 2026-11-24 改为「这一次发生的主键，禁止用 vin」 | 🟡 javadoc 已改；`V21` 注释冻结留痕 |

---

## 4. 已知阻塞项（非技术，等外部确认）

| 编号 | 问题 | 卡住谁 |
|---|---|---|
| ~~Q27~~ | ✅ 已裁决落地：V10 `collateral_registration_batches + items` 为唯一权威源，V18 三表只读投影 | 已解除 |
| ~~Q21~~ | ✅ 已定源：硬阻断，不保留按经销商放宽；`relaxedDealers` 白名单已删 | 已解除 |
| ~~Q9/Q10~~ | ✅ 已由《业务口径总定源》ADR §2 定稿（放车 `DEALER_WAREHOUSE` / 放证 `CERTIFICATE_DEPOSITORY`）| 已解除 |
| ~~Q14~~ | ✅ DPD 按自然日 `CALENDAR`，不切换营业日 | 已解除 |
| ~~R5–R8~~ | ✅ 4 条真实取数 SQL + `ExactValueReconRule` 精确相等判定，8 条规则全 `implemented=true` | 已解除 |
| **Q-EVT1** | `COLLATERAL_CHANGED` 的 `aggregate_key` 取值、「权属一次变更」边界、`status` 取值域 | `COLLATERAL_CHANGED` 接线 |
| ~~Q-EVT2~~ | ✅ 2026-11-24 已裁决落地：实现 `CreditLineQueryClient`（`GET /api/credit-lines/{id}/available`，Long 整数日元），删除 `CreditLineClient`（BigDecimal 死契约）及 bank 存根 | 已解除 |
| **Q-EVT3** | `LOAN_CREATED` 载荷的 `dueDate` / `gracePeriodDays` 权威源是谁？唯一像样出处 `financing_terms`（V11）**全仓零写入方**（唯一 Java 引用是 `SqlStructureSelfCheck`）。不裁决就只能不发，消费侧 `generateRepaymentPlan` 见 null 只 WARN 跳过 ⟹ 新建借据无还款计划，逾期/对账转不起来 | 业务；当前实现按「不发」，**不许自造默认值** |

> 上三行的口径与选项已写进 `docs/接口契约-事件与Feign.md`，签字后替换文档 + 接线，框架不用改。

### 4.1 USED 域待签字清单（D-01…D-09 / U-01…U-13）

按用户 2026-11-23 裁决：**未签字的口径按 `docs/二手车库存垫资改造-01-业务定源ADR草案.md` 的「采纳默认值」实现**，
数值外置成配置键、代码落点标 `[待定源 D-xx]`，签字后只改配置不改代码。
四条红线（无资金不放车 / 无 partner 回执不结清不回补 / 无質押不放款 / 账实不符不出库）**不适用本条**，不写开关。

清单在实现过程中逐条登记（每项：编号 / 主题 / 采纳值 / 配置键 / 落点 / 影响面），
完成 P0 主链后在本节汇总，作为交付物之一。

| 编号 | 主题 | 采纳值 | 配置键（种在哪一版） | 代码落点 | 签字后要改什么 |
|---|---|---|---|---|---|
| D-02 | 反社／合规结论有效期 | 180 天 | `floorplan.used.compliance-valid-days`（V75） | `DealerAdmissionPolicy` 规则 3 | 只改 `used_config_params.param_value`，代码不动 |
| — | 許可／会員到期预警窗口 | 30 天（D06 §16 已定值，不属待签字） | `floorplan.used.license-expiry-alert-days`（V75） | `DealerAdmissionPolicy` 规则 4 | 同上；列在这里是为了和「种了没人读」的键区分开 |
| 红线 | 双维评分二人签认 | `true`，且读到 `false` 仍按 `true` 执行并打 ERROR | `floorplan.used.reporting.dealer-rating-two-person-required`（V75） | `DealerRatingApplicationService` | **不改**：这条登记是为了「有人试图关闭红线」在库里留下可读痕迹，不是提供关闭能力 |
| 无编号（D06 §2.5 直接给值，未标待定源） | 里程勾稽阈值 | 1000 km（日本二手车市把 1,000 km 以内当作「运输／整备里程」的常规容差） | `floorplan.used.mileage-diff-threshold-km`（V78） | `MileageResolutionPolicy` ＋ `UsedVehicleMasterApplicationService.applyMileage` | 改值只改「超没超阈值 ⟹ 开不开 `ODOMETER_DIFF`」，不改采信哪个来源；冒烟已实测改成 0 立刻开单，证明这个数运行时从库里读 |
| D-11 | 里程差异是否阻断放款 | 只在「无任何可用观测」（`UNDETERMINED`）时阻断；超阈值只开差异、不阻断 | 无键（属策略，不属参数） | `MileageResolutionPolicy.Decision.blocksAdvance` | 若签字要求「超阈值也压放款」，改 `Decision` 的判定而不是加开关 |
| D-05 | 估值有效天数 | 45 天 | `floorplan.used.valuation.valid-days`（V79） | `UsedValuationApplicationService` 闸门判定 | 改值只改「哪天失去放款资格」，不回算存量行 |
| — | 人工覆盖上限倍率 | 1.15（115%） | `floorplan.used.valuation.manual-override-max-uplift`（V79） | `PrudentValueCalculator.overrideCeiling` | 改值只改「人工能抬高到自动源的多少倍」，下限不设限 |
| — | 审慎值默认算法 | `MIN_OF_SOURCES`（取小） | `floorplan.used.valuation.default-prudent-rule`（V79） | `PrudentValueCalculator.calculate` | 改成 `WEIGHTED` 前必须确认存量政策的权重数据齐备 |
| 刻意不种 | 单源是否可作放款基准 | `true`（但 D08 §3.2 的「除人工覆盖外，单源不得作放款基准」是硬策略） | 无键（属硬策略，不属参数） | `PrudentValueCalculator` 拒因 `SINGLE_SOURCE_NOT_MANUAL` | 要改这条口径应当改 D08 §3.2 并签字，不是在库里翻开关 |
| 刻意不种 | 兜底成数全局默认 | 无（走 `ltv_policies.default_ratio`） | 无键（政策已带版本、带二人批准、带 L3 校验） | `LtvTierResolver` 未给兜底则零命中判 `LTV_002` | 再种一枚全局默认值会把阻断改成放行 |

> 只登记**代码里真的有读方**的键（`UsedConfigKeys.wired()` 与这张表逐条对得上）。
> D06 §16 全表 28 个键里其余的（估值容差、LTV 档位、账龄阶梯…）随 R3 起的各自切片进这张表，
> 现在写成常量只会让人以为那些口径已经落地。
>
> **两处编号要更正，别照着抄**：① 上一行 D-11 不是 D01 §5 清单里的编号（那份到 D-09 为止，
> 且 D10 §5 已把「D-10」用作「计算书编制↔签认」那对 SOD），这里只是本地占位，签字时按实际编号走；
> ② 里程阈值那行 V78 的 `source_ref` 里写着 `[待定源 D-10]`，是本轮采纳默认值时误加的标号——
> D06 §2.5 给的是定值、没标待定源。**已落库的迁移不可改**（checksum），
> 这条文案留到下一次 USED 迁移里一句 `UPDATE used_config_params SET source_ref=… WHERE param_key='floorplan.used.mileage-diff-threshold-km'` 回正。
> 代码侧（`UsedConfigKeys` / `MileageResolutionPolicy`）的注释本轮已同步改成「D06 §2.5 定值」。

---

## 5. 未开发需求全量清单（2026-11-22 全仓实查，USED 部分 2026-11-23 补）

> 口径：只列「代码里确实没有 / 只写了骨架 / 默认关闭 / 零测试覆盖 / 只有设计稿」的项，
> 并单独列出「文档状态滞后造成的假未完成」，避免把过期 `[ ]` 当真实缺口。

### 5.1 真·未开发（核心链路）

| 编号 | 需求 | 实证 | 依赖 |
|---|---|---|---|
| D1 | 回款自动匹配到 VIN + 自动解押/额度释放 | 2026-11-24 已落：`FeignLoanLookupClient` 替换日志存根、`JdbcPaymentStore` 替换内存存储、`PAYMENT_MATCHED` 生产 + credit-service 真实回补 + core 真实解押（`PaymentMatchedSecurityConsumer`）。**仍缺**：五级匹配只实现 VIN + 金额（订单号/时间窗/付款人未做）；release-service 消费未做；与直接还款路径共用 `settle_seq` 的双重释放防线；真实出款通道仍是 Mock | 结算归属口径（USED R8）+ 外部通道 |
| D3 | recon 差异处置闭环 | `recon_diffs.status` 的 CONFIRMED/IGNORED/RESOLVED 无接口；`actor` 未写 `audit_events`；无分布式锁；未发 outbox 事件 | 后续增强 |
| D4 | release 撤销端点 + 账实缺口巡检 | `SENT 超期未回执` / `RELEASED 但台账未更新` 巡检作业未实现 | 后续增强（USED R6/R7 会重做这块）|
| D5 | 三源快照实际导入作业 | 只有 `POST /snapshot/import` 接口，没有推送方/定时拉取方 | 跨服务依赖 |
| D6 | M11 客户支付确认凭证 | `customer_payment_confirmations` 只出现在 `SalesApplicationService:101` 的 TODO 注释，无表无持久化 | 需新迁移（V28 已预留）+ 出库门禁联动 |
| D7 | 违约通知 / 法律清算 HTTP 端点 | `DisposalStatus` 有 NOTIFIED/LEGAL_ACTION/CANCELLED 三态，`DisposalController` 无对应端点 | 后续增强 |
| D8 | 风险扫描等作业 + 调度器 | `RISK_SCAN`/`TERM_CHECK`/`DEALER_METRIC_SCAN`/`RECON_FILE_GEN`/`SUBSIDY_SETTLE` 均不存在，全靠 `POST /api/jobs/*` 手动触发，未接 Quartz | 生产化 |
| D9 | 放款前置（垫付合同/法务局登记门槛） | `docs/放款前置…设计.md` 的 6 个端点全仓 Java 0 处；前端担保页不存在 | 仅设计稿 |
| D10 | 贷后风险分层 P2–P7 | `dpd_stage_rules`/`default_events`/`deduction_failures` 表未建（V31 预留），分档固化在 `DpdCalculator` 常量，画像 `deduction_fail_rate_30d` 置 NULL | 规则中心/后续 |
| D11 | 外部系统对接 | 真实征信、银企直连/MT940 + webhook、法务局登记、external-api 独立入口、外部 WMS 全缺 | **本次用 Node 挡板替代，真实第三方排期开发侧解不了** |
| D12 | 生产化工程能力 | 全仓 pom 无 `actuator`/`micrometer`；CI 静态检查、JVM 调优、压测、灰度回滚、灾备演练未做 | 生产化 |
| D13 | **工单体系 0 Java** | `work_orders` / `work_order_actions` 表齐全，零实体零 Service；门禁拒付说「生成 HIGH 工单」，实际只写 `risk_alerts` | V29 预留；本次 P0 主链**不做**（不在范围） |
| D14 | **前端 8 个模块 0 页面** | 垫款申请/复核、还款核销、出库指令、对账、借据贷后、违约处置、担保登记批次、回款匹配全缺；后端 110+ 端点，前端实际调用约 1/4 | 本次 P0 主链**不做** |

### 5.2 USED 域（本次改造的范围，逐需求实查在实现中回填）

| 编号 | 需求（D06 的 R 系列） | 当前实查状态 |
|---|---|---|
| R1 | 车商资质／許可／反社核查／双维评分／准入闸门 | **已贯通**（V75–V77 + 8 端点 + `DealerAdmissionPolicy`，网关冒烟覆盖） |
| R2 | USED 车辆主数据 + 车况票 + 里程观测 | **已贯通**（2026-11-23 13:20：`UsedVehicleMasterApplicationService` 6 用例 + `/api/used-vehicles/**` 6 端点 + V78 阈值种子 + 里程取严策略；車検証收证/换发链在本切片） |
| R5 | 車検証与担保多源投影、设质/交还/灭失/补发、登记委托 | 表已落（V52/V53/V54/V69），枚举与状态机部分存在，**业务层待写** |
| R3/R4 | 多源审慎估值 + LTV 分档定价 | 表已落（V55–V59），**业务层待写** |
| R8 | 拍卖落札接入与结算分摊 | 表已落（V60–V63），**业务层待写** |
| R6 | WMS 在库快照与账实比对 | 表已落（V65/V66），**业务层待写** |
| R7 | 放车放证闭环（指令/下发/回执/结清/额度回补）| 表已落（V67/V68），**业务层待写**；红线 1/2 的落点 |
| R9–R10 | 违约处置变现、报表 | 号段 **V79** 预留（V75–V77 已被 R1 占用、V78 已被 R2 阈值种子占用），**本次不做**（P0 主链外）|
| R11 | 外部合作方接入与集成层（五件套）| 表已落（V70–V74），`external-api` **只有骨架** |
| R12–R14 | 留痕审计、告警工单、灰度上线 | 部分表在 V74；**本次不做工单/灰度** |

### 5.3 已实现但默认关闭（配置，不是代码缺口）

| 开关 | 出处 | 关闭时跳过什么 |
|---|---|---|
| `floorplan.core.guarantee-gate.enabled` | `AdvanceApplicationService` `@Value(...:false)` | `enforceGuaranteeGate` 直接 return，连权属服务都不查；**Q27 判定路径改造未完全落地，开启即全量拒付，不擅自打开** |
| `floorplan.credit.strict-sod` | `CreditApplicationService` `:false` | 单人自动审批入口放行、不校验复核人≠发起人。**生产必须 true** |
| `floorplan.collateral.authoritative-source.enabled` | `DefaultAuthoritativeSourceStrategy` `:false` | 三源对账诊断返回 skipped；不影响门禁（门禁直读 V10）|
| `floorplan.used.enabled` | USED 总开关（D00 P-3）| **不是「默认关闭」而是根本没接线**：全仓 Java 零引用，USED 端点无条件可达。灰度回退属 R14，本次范围外；口径见 §2 偏差登记 |
| `floorplan.bank-fetch.source` / `lock.provider` / `s3.bucket` | `web/application.yml` | demo 流水源 / 内存锁 / 空桶名 → 走本地实现。**文件存储按用户要求继续用本地**，不引入 S3/MinIO |

### 5.4 已实现但零/低端到端覆盖

- 主干 `smoke-test.py` 覆盖 219 条断言（含 ㉔ 五个外围服务的「从网关可达」9 项、㉑ 前 bank-accounts 网关切流 1 项、㉖ 安全基线 7 项）；
  事件链路 `smoke_payment_matched.py` 覆盖 71 条（A1–A4 丢失断言 + B1–B5 生产者/发布去重 +
  C1–C4 真实回补 + C1b/C2/C4 core 解押与 L2 + 3 条多实例租约）。`run-microservices.sh smoke` 两个脚本串跑，任一失败即总退出码非 0。
- ✅ **并行套件删除带来的覆盖回退已补**（2026-11-24）：原 `smoke_w0/w2_core/w1_loan/smoke_used_wu4` 删除后，
  四条行为曾零自动化覆盖。新 `smoke_payment_matched.py` A1–A4 已重建：
  `aggregate_key`=`advanceId` 而非 VIN、重复回调不重复发事件、缺 `dueDate` ⇒ 还款计划为空、
  坏载荷 `DEAD` 不留半条借据。该脚本已收进 `run-microservices.sh smoke` 的调用链（主干之后、同一命令生命周期）。
- **仍未被冒烟覆盖的端点**：loan `GET /loans/{id}`、`/loans/{id}/dpd`、画像 recompute；
  release confirm/release/discharge-lien/建单；bank 批量 `POST /payments/match`、`ignore`、
  `match-by-transaction`、`GET /matches/{id}/release`（单笔 match / manual-match 已被
  `smoke_payment_matched.py` B/C 覆盖）；collateral-reg `discharge`、`snapshot/import`（单条）；
  risk `CollateralController` 12 个端点；web 的供应商/采购单/附件/aging/holidays 等 30+ 端点
  （经销商结算账户 `POST /api/dealers/{id}/bank-accounts` 已于 2026-11-24 补上经网关断言）。
- **教训（两次实证）**：零覆盖 = 假绿。补断言曾揪出 collateral-reg 全端点 500 + V10 漏挂车、
  `/api/payments/**` 被 core 兜底 404。**新增端点必须同步补冒烟。**
- USED 端到端断言待写：`scripts/smoke_used*.py`，须覆盖四条红线（D11 的 A1–A10 断言组）。

### 5.5 ⚠️ 文档状态滞后造成的「假未完成」

| 文档 | 过期表述 | 实查事实 |
|---|---|---|
| `docs/库存融资核心系统-任务进度与演进路线图.md` | 47 项全 `[ ]`，称「B1–B4、M1–M12 全部仍未修复」 | 文件头自带状态盲区提示；实查 B1–B4/M9/M12 已落地。**只作审计快照** |
| `docs/库存融资核心系统-十一环节闭环核查报告.md` | 称「4 严重断点 + 12 中等缺口 + 全库零实现模块」 | 2026-10-26 时点快照；`DisposalStatus` 三态已实现 |
| USED 设计集 D04/D06/D07/D11 的「分包」列与「挡板」表 | 按 W-U1…W-U5 五个包描述交付边界 | 分包已废弃：**同一批人（我一个人）按 P0 主链顺序做**，表结构归属仍照 D04 |
| 原 `docs/并行开发-*.md`、`docs/AGENT_TASK-*.md`、`scripts/agent_env.sh`、`scripts/smoke_w*.py` | — | **已删除**（用户指令：不再采用多 agent 模式）。引用到它们的地方按不存在处理 |

> **状态准绳**：业务口径以 `docs/ADR-业务口径总定源.md` 为唯一事实来源，担保权属以
> `docs/ADR-Q27-担保权属权威源裁决.md` 为准，USED 域命名/号段以
> `docs/二手车库存垫资改造-00-设计基线与命名定源.md` 为准。
> 历史报告中任何「待业务确认 / TODO(Q27) / 建议口径」表述均只作审计快照。

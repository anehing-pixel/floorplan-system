# ENGINEERING HANDOFF（ENG agent 交付说明）

> 分支：`agent/eng`　基线：`e441da1`　交付 tag：`agent/eng-done`　日期：2026-11-22

## 1. 交付概览

| 项目 | 结果 |
|---|---|
| 提交数 | 7 个（每逻辑块一个 commit） |
| 新增文件 | 6 个（含本文件） |
| 修改现有文件 | **0 个** |
| 越界检查 | 未改任何 `pom.xml` / `application.yml` / 服务 `src/test/` / 迁移文件 / `PROGRESS.md` / `AGENT_TASK.md`；未执行 worktree/branch/merge/reset/amend 等仓库结构命令 |

**提交清单**：

| commit | 说明 |
|---|---|
| `54e2979` | `[ENG] T0-7` 新增 CI 流水线：JDK17 全模块构建含单测门禁 |
| `f5dab24` | `[ENG] T4-3` 编写 JVM 调优与静态检查接入模板 |
| `4438d25` | `[ENG] T4-4` 编写慢 SQL 治理与执行计划归档模板 |
| `3e7f884` | `[ENG] T4-5` 新增零依赖压测脚本与 TPS/P99 报告模板 |
| `4001736` | `[ENG] T4-6` 编写灰度发布与一键回滚 Runbook |
| `e81c845` | `[ENG] T4-7` 编写灾备与备份恢复 Runbook |
| `b4a309b` | `[ENG]` 编写五类常见故障排查路径主体 |

**新增文件**：

- `.github/workflows/ci.yml`
- `docs/工程化配置模板.md`
- `docs/性能与排障指南.md`
- `scripts/loadtest/loadtest.py`
- `scripts/loadtest/README.md`
- `docs/ENGINEERING_HANDOFF.md`（本文件）

## 2. CI 门禁是否生效

**已由 CI 文件保证的部分**：

- 触发：push 到 `master`/`main`，以及针对这两个分支的 PR；同一 ref 重复推送取消旧运行。
- 流程：`actions/checkout@v4` → `actions/setup-java@v4`（Temurin JDK 17 + Maven 缓存）→ `mvn -q -B package`。
- 门禁有效性：父 pom 聚合 14 个模块，根目录 `mvn package` 覆盖全模块；`package` 生命周期包含 `test`，单测失败即 Maven 非 0 退出、job 失败；同时归档 `**/target/surefire-reports/**` 便于定位。
- YAML 语法/结构已用 Ruby `psych` 解析校验通过（顶层键、触发器、`jobs.build.steps`、门禁命令均存在）。

**CI 文件无法保证、需外部配置的部分（上报 PM）**：

1. **分支保护**：要让“测试不过不能合并”真正生效，需在 GitHub 仓库 Settings → Branches 对 `master` 开启 Branch protection，并 Require status checks to pass，勾选本工作流的 `构建与单元测试门禁 (build)`。此项只能在仓库设置完成，CI 文件做不到。
2. **健康检查端点**：Runbook 多处使用 `actuator/health` 作为健康检查约定，但全仓库 **未发现 `spring-boot-starter-actuator` 依赖**。需 Wave2 决定是否引入（改 pom，ENG 禁区）或替换为项目现有的健康检查端点。当前 Runbook 中该路径应视为**占位约定**。
3. **静态检查**：Checkstyle / SpotBugs / JaCoCo 均未接入（需改 pom）。方案已写入 `docs/工程化配置模板.md` 第 3 节，本次未改 pom。

## 3. 压测工具选型与验证

**环境探测（2026-11-22，本机）**：

| 工具 | 状态 | 处理 |
|---|---|---|
| actionlint | 缺失 | 改用 Ruby 自带 `psych` 解析 YAML |
| locust / k6 / JMeter | 均缺失 | 按任务卡“都装不了就写脚本 + README”，采用纯 Python 标准库脚本 |
| Java 17 / Maven 3.9.16 / Python 3.13.14 / Ruby 4.0.9 | 可用 | 直接用于校验与验证 |
| PyYAML | 缺失 | 未安装，用 Ruby psych 替代 |

**选型**：`scripts/loadtest/loadtest.py`——零第三方依赖，每线程复用 HTTP/1.1 连接，closed-loop 加压，产出含 **TPS / 平均 RT / P50~P99 / 错误率 / 状态码分布 / 容量结论** 的 Markdown 报告；`--sla-p99`、`--sla-error-rate` 可作为门禁，退出码 `0`=达标、`2`=不达标。定位为“随时可跑”的摸底工具，正式全链路压测仍建议 k6/JMeter/locust（报告模板兼容）。

**验证记录**：

- `python3 -m py_compile scripts/loadtest/loadtest.py`：通过。
- `python3 scripts/loadtest/loadtest.py --help`：正常输出全部参数。
- 进程内临时 HTTP 服务真跑自测（5 并发 × 1 秒）：1381 请求全部成功，错误率 0.00%，TPS 1377.2，P99 27.8ms，退出码 0，报告文件生成且含 TPS/P99/容量结论等全部栏目。
- 未在真实部署环境施压：本机没有运行中的被测服务，属预期限制，不影响交付（脚本已具备实测能力）。

## 4. 仅为模板、需 Wave2 集成的内容

| 模板项 | 位置 | 需 Wave2 完成的动作 |
|---|---|---|
| JVM G1 参数 | `docs/工程化配置模板.md` 第 1 节 | 按 T4-5 压测结果替换 `{{HEAP_SIZE}}` 等占位符，套用到各服务部署配置；同步核对容器内存限额 |
| 慢 SQL 清单与归档规范 | 同文档第 2 节 | 建立 `docs/performance/explain/`、`docs/performance/reports/` 目录；开启慢查询日志（改配置）；DBA 按清单加索引并归档 before/after |
| 静态检查插件 | 同文档第 3 节 | 改父 pom 引入 Checkstyle/SpotBugs/JaCoCo；建议单模块试点后再全仓库启用 |
| 灰度/回滚命令占位符 | `docs/性能与排障指南.md` 第 1 节 | 按环境填 `{{NS}}`、`{{REGISTRY}}`、`{{OLD_TAG}}`、`{{CONTAINER}}` 等；确认 K8s 或 Compose 路线；预发演练回滚脚本 |
| 灾备备份命令 | 同文档第 2 节 | 运维把全量备份 + binlog 归档配置为定时任务/CronJob，接入完整性校验与失败告警；确认异地存储 |
| 健康检查端点 | 同文档各节 | 引入 actuator 或替换端点（见第 2 节第 2 点，需 PM 决策） |
| 各服务单测基类 | 见下方模板 | PM 裁决 ENG 不碰任何服务 `src/test/`，由 Wave2 按模板落地 |

**各服务单测基类模板（交 Wave2 落地，ENG 未写入任何 `src/test/`）**：

```java
// 建议路径：<service>/src/test/java/com/company/<service>/BaseServiceTest.java
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@AutoConfigureMockMvc
public abstract class BaseServiceTest {
    @Autowired protected MockMvc mockMvc;
    @Autowired protected ObjectMapper objectMapper;

    // 各服务在此基类上扩展：固定测试时间、共享测试数据准备与清理、
    // 用 @MockBean 隔离 Feign/DB 外部依赖等。
    // 注意：不要在此基类里直连生产数据库；test profile 必须指向测试库。
}
```

> 引入该基类需在对应服务补 `spring-boot-starter-test` 测试依赖（若尚未引入），属 Wave2 的 pom 改动范围。

## 5. TODO（移交项）

1. **PM/仓库管理员**：开启 master 分支保护并勾选 CI required check（否则“测试不过不能合并”不成立）。
2. **PM 决策**：是否引入 `spring-boot-starter-actuator` 提供 `/actuator/health`；若否，统一替换 Runbook 中的健康检查路径。
3. **Wave2**：按压测结果定容 JVM 堆参数并套用；建立性能报告与执行计划归档目录；开启慢查询日志。
4. **Wave2**：评估并接入静态检查插件，先单模块试点。
5. **Wave2**：按模板落地各服务单测基类，补齐测试依赖；CI 的 `mvn package` 会自动纳入这些单测。
6. **运维**：落地备份定时任务、完整性校验与异地副本；每季度执行恢复演练并测 RTO/RPO。
7. **各服务负责人**：按环境填灰度/回滚命令占位符，预发演练一键回滚；每季度演练一次。
8. **后续**：如引入 k6/JMeter/locust，用本报告模板跑正式全链路压测，替换当前摸底数据。

## 6. 验证汇总

| 验证项 | 命令/方式 | 结果 |
|---|---|---|
| CI YAML 语法与结构 | Ruby `psych` 解析 + 关键结构断言 | 通过 |
| 压测脚本语法 | `python3 -m py_compile` | 通过 |
| 压测脚本功能 | 进程内 HTTP server 实跑 1 秒 | 通过（1381 请求，TPS 1377，P99 27.8ms，退出码 0） |
| 全仓库编译 | `mvn -q -B -DskipTests package` | 通过（退出码 0） |
| 边界检查 | `git diff --name-only e441da1 HEAD` | 仅 6 个新增文件（含本 HANDOFF），无越界改动 |

## 7. 环境限制记录（未硬装工具）

| 缺失项 | 影响 | 替代方案 |
|---|---|---|
| actionlint | 无法用专用 linter 校验 workflow | Ruby psych 解析 + 结构断言 |
| locust / k6 / JMeter | 无法用现成压测工具 | 零依赖 Python 标准库脚本，已语法检查 + 真跑自测 |
| PyYAML | 无法用 Python 解析 YAML | Ruby psych |
| 运行中的被测服务 | 无法做真实业务接口压测 | 临时 HTTP server 验证脚本链路；真实压测列为 Wave2 TODO |

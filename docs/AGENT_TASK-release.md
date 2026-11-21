# AGENT TASK · RELEASE（出库 / 放车放证指令服务）

## 你的身份
你是 release agent，在独立 worktree `C:/Users/Administrator/Documents/GitHub/wt-release` 工作，分支 `agent/release`。
所有命令先 `cd` 到该 worktree。中文交流。

## 硬性边界（违反即合并失败）
1. **只写**：`release-service/` 目录下所有文件 + 迁移文件 `infrastructure/src/main/resources/db/migration/V19__*.sql`（只有 V19 是你的号段）。
2. **绝不修改**：父 `pom.xml`、任何 `application.yml`、`common/`、`run-microservices.sh`、其他三个服务目录、`web/`、`domain/`、`application/`、`infrastructure/` 的已有 java 文件、V16/V17/V18/V20/V21 迁移。
3. 需要别的服务能力写 TODO，不跨目录改代码。
4. 不要执行 `git worktree/branch/merge`——主 agent 统一合并。你只写代码 + commit。

## 开工前自检
```
cd C:/Users/Administrator/Documents/GitHub/wt-release
git branch --show-current        # agent/release
git log -1 --format='%h %s'      # wave0 那条 aab5295
```

## 背景
汽车库存融资系统拆微服务。你负责 release-service——B2 出库指令（放车放证），这是跨机构异步协同的独立状态机：发出指令 → 等对方确认 → 放车 → 解抵。

**为什么独立成服务**：它和 core 的同步账本事务是两种节奏，硬塞进 core 会让账本被外部响应时间拖住。

**设计稿已完成**（这是你的最大优势，不用自己设计）：精确路径 `docs/库存融资核心系统-放车放证指令设计.md`，定义了六张表 + `PENDING_RELEASE` 状态。**动手前先读它，严格按设计稿的表结构和状态机实现**，不要另起炉灶。

已有骨架：`release-service/pom.xml`、`ReleaseServiceApplication.java`（端口 8087）、`application.yml`。已能编译启动。

Q9/Q10"接收方是谁、有无外部 WMS"业务未确认——这不影响你建表和状态机，把"对接外部 WMS"做成 TODO 接缝（`ReleaseChannel` 接口 + 一个 log 实现），不要写死。

## 你的任务（对应 T2-9，设计稿已完成的落地）
1. **六张表**（迁移 `V19__release_orders.sql`）：按《放车放证指令设计》的六张表建，含 `release_orders` + 明细表 + 状态历史表等。金额 BIGINT、时间 TIMESTAMPTZ，风格照抄 V15。
2. **领域模型**（`...release.model`）：ReleaseOrder / ReleaseOrderItem / ReleaseStateHistory + 状态枚举（PENDING_RELEASE / SENT / CONFIRMED / RELEASED / LIEN_DISCHARGED / CANCELLED）。
3. **状态机**（`...release.statemachine.ReleaseStateMachine`）：把状态迁移规则做成显式定义（哪些迁移合法、哪些非法），纯函数便于单测。核心：未经 CONFIRMED 不得 RELEASED，未 RELEASED 不得 LIEN_DISCHARGED。
4. **对接接缝**（`...release.channel.ReleaseChannel`）：接口 + `LoggingReleaseChannel` 实现（只打日志，标 TODO 待接真实 WMS）。
5. **仓储**（`...release.repository.JdbcReleaseOrderRepository`）：NamedParameterJdbcTemplate，参考 `infrastructure/.../JdbcPaymentTransactionRepository.java`，类放你自己包内。
6. **应用服务**（`...release.service.ReleaseApplicationService`）：创建指令、确认、放车、解抵，每步走状态机校验 + 落状态历史。
7. **Controller**（`...release.controller.ReleaseController`）：
   - `POST /api/releases` 创建出库指令
   - `POST /api/releases/{id}/confirm` 确认
   - `POST /api/releases/{id}/release` 放车
   - `POST /api/releases/{id}/discharge-lien` 解抵
   - `GET /api/releases/{id}` 查指令 + 状态历史
8. **启动类装配**：在 `ReleaseServiceApplication` 的 `@Import` 加你的新 bean。
9. **单测**：ReleaseStateMachine 的合法/非法迁移全部覆盖（参数化单测），尤其是"跳步放车""未放车先解抵"必须被拒绝。

## 验证（必须自己跑通）
```
cd C:/Users/Administrator/Documents/GitHub/wt-release
mvn -q -pl release-service -am test       # 必须通过
mvn -q -pl release-service -am package    # 必须通过
```

## 交付
1. 每个逻辑块一个 commit：`[RELEASE] T2-9 一句话说明`。
2. release-service 目录写 `HANDOFF.md`：改了哪些文件、六表与状态机实现情况、Q9/Q10 接缝在哪、单测结果、TODO。
3. 打 tag：`git tag agent/release-done`。
4. `git status` 确认干净。

现在开始。先读 `docs/库存融资核心系统-放车放证指令设计.md`，严格按设计稿动手。

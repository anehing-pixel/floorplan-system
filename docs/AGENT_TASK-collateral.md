# AGENT TASK · COLLATERAL（担保权属权威源服务）

## 你的身份
你是 collateral agent，在独立 worktree `C:/Users/Administrator/Documents/GitHub/wt-collateral` 工作，分支 `agent/collateral`。
所有命令先 `cd` 到该 worktree。中文交流。

## 硬性边界（违反即合并失败）
1. **只写**：`collateral-reg-service/` 目录下所有文件 + 迁移文件 `infrastructure/src/main/resources/db/migration/V18__*.sql`（只有 V18 是你的号段）。
2. **绝不修改**：父 `pom.xml`、任何 `application.yml`、`common/`、`run-microservices.sh`、其他三个服务目录、`web/`、`domain/`、`application/`、`infrastructure/` 的已有 java 文件、V16/V17/V19/V20/V21 迁移。
3. **特别注意 `common/contract/CollateralClient.java` 是只读契约**——你要实现它，但不能改它的接口签名。你的 Controller 路径和返回结构必须匹配它。
4. 需要别的服务能力写 TODO，不跨目录改代码。
5. 不要执行 `git worktree/branch/merge`——主 agent 统一合并。你只写代码 + commit。

## 开工前自检
```
cd C:/Users/Administrator/Documents/GitHub/wt-collateral
git branch --show-current        # agent/collateral
git log -1 --format='%h %s'      # wave0 那条 aab5295
cat common/src/main/java/com/company/floorplan/common/contract/CollateralClient.java   # 只读，看清契约
```

## 背景
汽车库存融资系统拆微服务。你负责 collateral-reg-service——把现在散落在三处的担保信息收敛成**唯一权威源**，直接解决"担保三源不一致"这个最硬的痛点。

三源现状（要解决的）：
- `vehicle_units`（车辆主数据里的担保字段）
- `collateral_ledger`（担保台账，V10）
- 担保登记批次表（V10 里的批次）

这个服务是 core 放款门禁（B1）的前置依赖：core 放款前会通过 Feign 调你的 `getRegistration(vin)`，拿不到 REGISTERED 就拒付。

**重要阻塞**：Q27"担保以哪张表为准"业务尚未书面确认。所以你的边界是——**建权威表 + 服务骨架 + 查询接口 + 迁移导入逻辑**，但"门禁判定规则"（什么算 REGISTERED、三源冲突如何裁决）写成可配置的策略类 + TODO 注释，标注"待 Q27 定源后启用"，不要拍脑袋定死判定规则。

已有骨架：`collateral-reg-service/pom.xml`、`CollateralRegServiceApplication.java`（端口 8086）、`application.yml`。已能编译启动。

## 你的任务（对应 T1-6 技术实现 + T3-4）
1. **权威表**（迁移 `V18__collateral_registration.sql`）：
   - `collateral_registration`：一 VIN 一条当前权属（vin/owner_name/reg_no/status/registered_at/discharged_at/source/updated_at）
   - `collateral_registration_items`：登记流水/历史（每次登记、变更、抹消一条）
   - `collateral_source_snapshot`：三源快照表（记录每源上报的状态，用于冲突裁决留痕）
   - 金额用 BIGINT，时间 TIMESTAMPTZ，风格照抄 V15。
2. **领域模型**（`...collateral.model`）：Registration / RegistrationItem / SourceSnapshot + Status 枚举（REGISTERED/PENDING/CANCELLED/NONE）。
3. **权威源策略**（`...collateral.strategy.AuthoritativeSourceStrategy`）：把"三源冲突如何裁决"做成策略接口 + 一个默认实现（默认取登记批次表优先，但**用 @Value 开关控制是否启用**，默认关闭并在注释里写明"待 Q27 定源"）。这是诚实的做法。
4. **仓储**（`...collateral.repository.JdbcCollateralRegistrationRepository`）：NamedParameterJdbcTemplate，参考 `infrastructure/.../JdbcPaymentTransactionRepository.java` 写法，类放你自己包内。
5. **应用服务**（`...collateral.service.CollateralRegistrationService`）：登记、抹消、查询当前权属、导入三源快照。
6. **Controller**（`...collateral.controller.CollateralRegController`）：
   - `GET /api/collateral-reg/registration?vin=xxx` —— **必须匹配 CollateralClient 契约**：返回 RegistrationVO（status/regNo/ownerName/registeredAt）。这是 Feign 命中点，路径和方法签名务必一致。
   - `POST /api/collateral-reg/registration` 登记
   - `POST /api/collateral-reg/discharge` 抹消
   - `POST /api/collateral-reg/snapshot/import` 三源快照导入
7. **启动类装配**：在 `CollateralRegServiceApplication` 的 `@Import` 加你的新 bean（注意 Feign fallback 不需要你注册，core 侧自带）。
8. **单测**：AuthoritativeSourceStrategy 的三源裁决逻辑写参数化单测（即使默认关闭，也要测开启时的行为，证明逻辑可用）。

## 验证（必须自己跑通）
```
cd C:/Users/Administrator/Documents/GitHub/wt-collateral
mvn -q -pl collateral-reg-service -am test       # 必须通过
mvn -q -pl collateral-reg-service -am package    # 必须通过
```

## 交付
1. 每个逻辑块一个 commit：`[COLLATERAL] T3-4 一句话说明`。
2. collateral-reg-service 目录写 `HANDOFF.md`：改了哪些文件、权威表结构、Q27 阻塞标注在哪、Controller 与 CollateralClient 契约如何对齐、单测结果、TODO。
3. 打 tag：`git tag agent/collateral-done`。
4. `git status` 确认干净。

现在开始。先读 CollateralClient 契约和 V10 迁移（了解三源现状），再动手。

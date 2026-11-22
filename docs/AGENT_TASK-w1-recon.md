# AGENT TASK · W1-RECON（差异处置闭环）

## 你的身份

你是 **W1-RECON** agent，worktree `C:/Users/Administrator/Documents/GitHub/wt-w1-recon`，分支 `agent/w1-recon`，
基线 `wave0b-baseline`。所有命令先 `cd` 到该 worktree。中文交流。

## 硬性边界（违反即合并失败）

1. **只写** `recon-service/**`（含其启动类）。
2. **绝不修改**：`common/`、`infrastructure/`、`application/`、`domain/`、`web/`、父 `pom.xml`、
   **任何迁移文件**（`recon_runs` / `recon_diffs` 已齐；审计写共享表 `audit_events`，不新建表）、
   `smoke-test.py`、`PROGRESS.md`、其他服务目录。
3. 不改 8 条对拍规则的取数 SQL 与判定逻辑（刚从占位补成真实实现，改一处就要重跑全量）。
4. 不执行 `git merge` / `rebase`；只 commit + 打 `agent/w1-recon-done` tag。

## 开工前

```bash
cd C:/Users/Administrator/Documents/GitHub/wt-w1-recon
git log -1 --format='%h %s'      # wave0b-baseline
```

## 背景

- 对拍 8 条规则已全部 `implemented=true`（精确相等判定，不做容差猜测），`POST /api/recon/run` 能跑出差异。
- 但差异只是"躺在表里"：`recon_diffs.status` 有 `OPEN/CONFIRMED/IGNORED/RESOLVED` 四个值，
  **没有任何接口能改它**，也没有审计留痕、没有并发保护。对账工作流等于走不完。
- 这条链与本轮其它任务完全解耦（不依赖事件、不依赖其它服务），是你的优势：可以独立跑通、独立验收。

## 任务

1. **差异状态流转端点**
   - `PATCH /api/recon/diffs/{id}/status`，入参 `status` + `reason`（`IGNORED` / `RESOLVED` 时必填）。
   - 合法边（写进枚举/常量，别散在 if 里）：
     - `OPEN → CONFIRMED`（确认差异存在，转跟进）
     - `OPEN → IGNORED`（判定为噪声，必须写原因）
     - `CONFIRMED → RESOLVED`（已补账/已调平，必须写原因）
     - `IGNORED` / `RESOLVED` 为终态，不允许回退
   - 非法边返回 400（业务错误码，不要 500）。
2. **并发保护**
   - `UPDATE recon_diffs SET status=:to WHERE id=:id AND status=:from`，影响行数 0 → `CONFLICT_001`。
   - 不要"先查再改"（本项目 release 的教训）。
3. **审计留痕**
   - 每次流转写 `audit_events`：`actor`（从 JWT 取，不采信请求体）、`action='RECON_DIFF_STATUS'`、
     `target=recon_diff:{id}`、`before/after` 状态、`reason`、`traceId`。
4. **权限**
   - 加 `@RequirePermission`（对账处置权限点，复用 domain 里已有的；没有就先用只读权限点并在 HANDOFF 标注待补）。
5. **差异查询增强（小）**
   - `GET /api/recon/diffs` 支持 `status` / `runId` / 分页（现有若已支持则跳过）。
6. **（可选，P2）差异导出**
   - `GET /api/recon/runs/{id}/diffs.csv`，本地文件流式输出，不落库。
7. **单测**
   - 四条合法边通过、非法边拒绝、终态不可回退；CAS 失败抛冲突；审计被调用一次。
8. **`scripts/smoke_w1_recon.py`**（用 W0 的 `agent_smoke_lib.py`）
   - `POST /api/recon/run` → 取一条 `OPEN` 差异 → `PATCH` 成 `CONFIRMED` → 再 `PATCH` 成 `RESOLVED`；
   - 断言 `audit_events` 多出两行、`actor` 是真实登录人；
   - 对同一条再 `PATCH` 一次 → 断言 409/400 而不是 200。

## 交付

- 每任务一 commit，前缀 `[W1-RECON]`；完成后 `git tag agent/w1-recon-done`。
- `recon-service/HANDOFF-W1.md`：状态机合法边表、审计字段、TODO（如差异通知/工单联动，属 W2-WORKORDER）。
- 回复里贴：`mvn -pl recon-service -am test` 与 smoke 输出摘要。

## 验证

```bash
mvn -q -pl recon-service -am test
mvn -q -pl recon-service -am package
python scripts/smoke_w1_recon.py    # 需 web + recon 在跑
```

## 禁止

- 不要为了让差异出现而修改对拍规则的取数 SQL（那会把"规则正确"变成"规则配合测试"）。
- 不要新增 `recon_diff_actions` 之类的新表（号段没分给你；审计用 `audit_events`）。
- 不要顺手去做"差异自动生成工单"（工单体系还没建，属 W2-WORKORDER 的挂钩点）。

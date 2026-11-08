# 29 · goal 收尾指令 + 工具权限（M12）

> 2026-11-07 03:1x｜基线 `0d70075`（M11 goal-round-driver 之后）。
> 目标：补掉 docs/28 §六.1/2 的两个缺口——`<goal_complete>/<goal_blocked>` 收尾指令、
> goal 工具的 direct-human / goal-round 权限。
> 上游依据：`dsh-tool-goal/lib/types/wrapup.js`、`.../authority.js`、`dsh-llm/lib/types/message.js`
> （`boundContextSummary` + `deferContext`）。

---

## 一、一句话结果

真机跑通两条完整闭环（都是**自动续轮里的模型自报状态**，不是面板操作）：

```
目标：写 done.txt 然后标 complete（max=3）
  turn 1  人类消息「先只回我一句 ok」→ 助手 ok
  turn 2  <goal_round> Round 1/3
          get_goal / working_directory / write / read / update_goal(complete)
          seq 31 goal/change complete revision 1→2
          seq 33 user/message source={kind:"tool-goal",form:"notice",summary:"complete: …"}
                 正文 <goal_complete>…（逐字节上游）
          seq 35 新的 step：模型在同一 turn 里写收尾话（不再调工具）
  目标条「已完成 · 第 1/3 轮」+ 聊天里「目标收尾 · 完成」分隔条

目标：每一轮都试 update_goal(blocked)（max=4，阈值 3）
  Round 1  blocked → 拒绝：blocked requires at least 3 consecutive goal rounds; current round is 1
  Round 2  blocked → 拒绝：… current round is 2
  Round 3  blocked → 成功：goal/change block revision 2、reason=model-reported/缺少 API Key
           seq 55 user/message source={kind:"tool-goal",form:"notice",summary:"blocked: …"}
                  正文 <goal_blocked>…Blocked: "缺少 API Key"…
  「受阻 · 第 3/4 轮 · 缺少 API Key」+「目标收尾 · 受阻」+ 收尾话
```

证据截图：

| 截图 | 内容 |
|---|---|
| `docs/shots/goal-wrapup-complete.png` | update_goal 卡 →「目标收尾 · 完成」→ 收尾消息（做了什么/怎么验证/说明） |
| `docs/shots/goal-wrapup-strip.png` | 目标条「已完成 · 第 1/3 轮」+ 收尾消息 |
| `docs/shots/goal-wrapup-blocked.png` | 第 3 轮 get_goal/update_goal →「目标收尾 · 受阻」→ 收尾消息 |
| `docs/shots/goal-wrapup-threshold.png` | 第 1 轮被 3 轮阈值拒绝 + 目标条已显示「受阻 · 第 3/4 轮」 |

会话事件证据：`parity/runs/goal-wrapup-session.jsonl`（complete）、
`parity/runs/goal-blocked-session.jsonl`（blocked + 阈值），用 `tools/session-dump.ps1` 解包。

---

## 二、上游语义（读源码确认）

### 2.1 收尾指令是「注入一条 user 通知」，不是硬停本轮

`dsh-tool-goal` 里 complete/blocked 成功后：

```js
if (authority.kind === "goal-round") exec.deferContext(createUserMessage({
  content: renderWrapupContext(goal.objective[, blocked_reason]),
  source: { kind: "tool-goal", form: "notice", summary: boundContextSummary(`${action}: ${objective}`) },
}));
```

- `deferContext` 的上下文由工具注册表收集，**接在这一步全部 tool_result 之后**（不插进 tool_result 中间）；
- 于是模型在同一 turn 里再被请求一次，带「写收尾话、不要再调工具」的指令；
- `boundContextSummary` 上限 120 个 UTF-16 单元，超长取前 119 + `…`；
- 只有 `authority.kind === "goal-round"` 才注入：人类直连的 complete/blocked 不需要收尾指令。

### 2.2 权限只看当前 open turn 的事件

`authority.js`：

- `hasDirectHumanInput`：当前 turn 里存在 `user/message` 且 `source.kind === "user"`（且 agent 是根）；
- `isMatchingGoalRound`：存在 `source.kind === "goal"` 且 goalId/revision 与当前目标一致、
  `round === goal.roundsStarted`（即**正好是当前这一轮**，不是旧轮次重放）；
- `complete/blocked` 两者之一即可；`create/edit/pause/resume` 只要 direct human；
- `blocked` 的 3 轮阈值**只**在 goal-round 授权下判。

---

## 三、代码改动

### 3.1 `core:model`

- `Goal.kt`：新增 `GoalAuthority`（`None` / `DirectHuman` / `GoalRound(goalId,revision,round)`）；
- `Session.kt`：`ChatMessage.sourceSummary`（`tool-goal` 通知的单行摘要，UI/重放用）。

### 3.2 `core:session`

- **`GoalWrapup.kt`（新）**：`complete(objective)` / `blocked(objective, reason)` 逐字节对齐
  `renderWrapupContext`（objective/reason 用 `GoalRoundPrompt.quote`，即 JS `JSON.stringify(string)`），
  外加 `summary(action, objective)` / `bound()`（120 字截断）。
  文案不是手抄：`tools/wrapup-oracle.mjs` 从上游源码抽出函数现算，写进
  `core/session/src/test/resources/goal-wrapup/*.txt`，单测逐字节比对。
- **`SessionSession.openTurnUserSources()`**：最后一个 `turn/start` 之后、且 turn 尚未 `turn/end`
  的 `user/message.source` 列表（上游 `openTurnStartSeq` 的手机端等价物）。
- **`GoalStore.authority()`**：按上面的证据规则返回 `GoalAuthority`。

### 3.3 `core:tools`

- `Tool.kt`：`GoalHost` 新增 `authority()`；`update(...)` 增加 `authority` / `minBlockedRounds`
  参数并返回 `GoalUpdateResult(view, notice)`；`ToolResult.notice`（宿主据此 deferContext）。
- `GoalTools.kt`：
  - `create_goal` / `edit` / `pause` / `resume`：非 DirectHuman → 上游原文
    `this goal operation requires a direct human turn on a top-level agent`（码 `GOAL_TOOL_AUTHORITY_REQUIRED`）；
  - `complete` / `blocked`：`None` → `complete and blocked require a direct human turn or the current goal round`；
  - `blocked` 空原因在进状态机前挡掉（上游码 `GOAL_TOOL_INVALID_UPDATE`）；
  - 阈值：goal 轮传 3，人类直连传 0。
- 三轮阈值文案仍由 `GoalStore` 生成，与上游逐字一致：
  `blocked requires at least 3 consecutive goal rounds; current round is N`。

### 3.4 `app`

- `GoalHost` 适配器：`update` 在 goal 轮 complete/blocked 时用 `GoalWrapup` 生成通知
  （正文 + `summary`），返回 `GoalUpdateResult`。
- `runTurn`：工具循环里先收集 `result.notice`，**全部 tool_result 落库之后**再逐条追加
  `user/message`（`source={kind:"tool-goal",form:"notice",summary}`）和 UI 消息；
  随后同一 turn 继续请求，模型写收尾话。
- 重开会话时从 `source.summary` 还原 `sourceSummary`。

### 3.5 UI（`ChatScreen`）

- `sourceKind=="tool-goal"` 的消息不显示正文，渲染「目标收尾 · 完成 / 受阻」分隔条
  （绿色 CheckCircle / 橙色 ErrorOutline，与「自动续轮 · 第 N 轮」同一套语汇）；
- 会话内搜索跳过这类内部提示词。

---

## 四、测试

- `:core:session:test` **36 个**全绿：
  - `GoalWrapupTest` 4（complete/blocked 逐字节、JSON 转义、120 字截断）；
  - `GoalAuthorityTest` 5（无 open turn、人类直连、turn 已收口、当前轮、旧轮次/旧 revision）；
  - 原有 `GoalStoreTest` 13 / `GoalRoundDriverTest` 7 / `ScheduleStoreTest` 7。
- `:core:tools:test` **53 个**全绿：`GoalToolsTest` 13（新增 6：create/edit/pause/resume 权限、
  goal 轮 complete 带通知、无授权拒绝、空 blocked_reason、人类直连阈值 0）。
- 其余 core 模块全绿（构建脚本同 docs/28）。
- 门 2：`.\tools\parity.ps1 verify` = **114 vs 114 PASS**（工具数/提示词未变，夹具无需重录）。
- 真机：两条闭环见 §一；验收后把 `done.txt` 移出 workspace（`files/goal-wrapup-done-evidence.txt`），
  approval 已还原为 ask。

---

## 五、交叉场景：goal 续轮 + 到期提醒插队（docs/28 §六.5 收口）

`startTurn` 的循环顺序是「先结清 goal，再让到期提醒插队」。真机用「每轮 `bash sleep 8` +
15 秒后的一次性提醒」把提醒卡在两轮之间，事件序列证明插队生效：

```text
seq 25  user/message kind=goal round=2
seq 31  assistant「第 2 轮…」
seq 36  user/message kind=user  「提醒插队测试：回我一句提醒到了」   ← 提醒轮插进来
seq 37  assistant「提醒到了」
seq 43  user/message kind=goal round=3                              ← 目标继续
…
seq 88  goal/change block（round-limit 6/6）
```

截图 `docs/shots/goal-schedule-interleave.png`；事件 `parity/runs/goal-schedule-interleave2.jsonl`。
另有一次反例（`goal-schedule-session.jsonl`）：目标轮跑得比提醒快，8 轮在 20 秒内跑完，
提醒在目标**收尾之后**才投递——这也符合「goal 优先、提醒随后」的既定顺序。

验收后清理了 workspace 里被 bash 写出来的 `files/` 子目录，门 2 复跑仍是 114 vs 114 PASS
（`parity.ps1 verify` 同时把 approval 还原成 ask、base_url 还原成官方）。

---

## 六、没做 / 已知限制

1. **`blocked` 阈值可配置**仍是常量 3（上游 `blockedAfterConsecutiveRounds` 配置项）；手机端没暴露设置。
2. **`get_goal` 的 open-turn 要求**没做：上游在无 open turn 时连 get_goal 都拒绝
   （`GOAL_TOOL_DRIVER_REQUIRED`），手机端只在 host 缺失时拒绝；模型不会在 turn 外调工具，观察不到差异。
3. 收尾指令只覆盖**模型自报** complete/blocked；驱动的 `round-limit` 收尾（docs/28）仍直接结束，
   不注入 `<goal_blocked>`（上游 driver 也不注入）。
4. 同一 assistant 消息里多条工具调用时，通知统一排在全部 tool_result 之后（上游同序），
   没有 per-call 交错。

---

## 七、下一步

1. `core:engine` 抽取：`startTurn` / goal 续轮循环从 AppViewModel 挪走，补 P0 场景到 6 个。
2. 聊天：M9 搜索跳转/长按复制逐项点验；消息引用/重发、时间分隔、导出会话。
3. 工具：`screenshot`（MediaProjection）→ `skill` → `job_*`。
4. goal 轮里 `ask_user_question` 的交叉场景（工具会挂起等用户，自动续轮要不要让路，需真机确认）。

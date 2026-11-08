# 28 · goal-round-driver：自动续轮 + `/goal` 面板

> 2026-11-07 03:0x｜基线 `2fe09d6`（M10 schedule 之后）。
> 目标：补掉 docs/24 §三.1/2 的两个缺口——回合结束后按 activation 自动续轮、用户侧 `/goal` 控制。
> 上游依据：`@deepseek-ai/dsh-goal-round-driver`（README + lib/index.js）、`dsh-goal`（activation 语义）、
> `dsh-tool-goal`（`round-limit` / 三轮 blocked 阈值）。

---

## 一、一句话结果

真机（会话 `98415170-c940-48ca-b933-91fab4ee7f82`）跑通两种闭环：

```
goal(max=2) + 人类起始消息
  turn 1  人类消息
  turn 2  <goal_round> Round: 1/2   → bash 追加 round-1
  turn 3  <goal_round> Round: 2/2   → bash 追加 round-2
  seq 96  goal/change operation=block revision=2
          blockedReason={code:"round-limit", message:"Goal reached its configured limit of 2 rounds."}
          页面：目标条「受阻 · 第 2/2 轮」

/goal 面板：编辑上限 2→4 → 继续
  turn 4/5  Round 3/4、4/4（自动续轮）
  再次 block（message 换成 limit of 4）

用户停止正在跑的 Round 9 → turn 10 aborted + goal/change operation=pause
面板暂停正在跑的 Round 10 → turn 11 aborted + pause
```

证据截图：

| 截图 | 内容 |
|---|---|
| `docs/shots/goal-round-mid.png` | 第 2 轮「自动续轮 · 第 2 轮」分隔条 + 受阻目标条 + 本轮 bash 卡 |
| `docs/shots/goal-panel.png` | `/goal` 面板：受阻、轮次用尽、编辑/清空/关闭 |
| `docs/shots/goal-panel-saved.png` | 把上限改成 4 后出现「继续」 |
| `docs/shots/goal-resume-round3b.png` | 继续后跑到 4/4 再次 round-limit |
| `docs/shots/goal-round-34b.png` | 第 3/4 轮文字里的自动续轮分隔条 |
| `docs/shots/goal-stopped-round.png` | 用户停止 → 目标条「已暂停 · 第 9/20 轮」 |
| `docs/shots/goal-host-pause.png` | 面板暂停 → 「已暂停 · 第 10/20 轮」 |

---

## 二、上游语义（读源码后确认，不是自创）

### 2.1 activation 是进程内状态

`dsh-goal` README §「What survives and what does not」+ `lib/index.js`：

- `armed/disarmed` **不落盘**，只存在 per-session 内存；
- 每次 `agent/created`（打开/新建会话）一律 `disarmed`；
- `create` → armed；`resume` → armed；`pause/complete/block/clear` → disarmed；`edit` 保持；
- 因此**会话恢复后 active 目标不会自己跑**，必须用户（或人授权的）`resume`。

手机端对应：`GoalActivationState` 由 `AppViewModel` 持有，`openSessionById` / `newChat` 换新实例。

### 2.2 driver 的 idle 判决

`dsh-goal-round-driver/lib/index.js` 的 `drive()`：

- 只在 whole-agent idle；`goal.phase === "active" && goal.activation === "armed"`；
- `roundsStarted >= maxGoalRounds` → `ctx.goals.block({code:"round-limit", message:"Goal reached its configured limit of N rounds."})`；
- 否则 `round = roundsStarted + 1`，排一条 goal 来源 user 消息；
- 提示词逐字节固定（见 §三.2）；
- 用户 interrupt 正在跑的 goal 轮 → 下一 idle 点 `pause`；无关取消只 `disarm`；
- max-tokens / 异常 → `disarm`，不自动重试（等用户 resume）。

### 2.3 工具阈值 vs 驱动收尾

`dsh-tool-goal` 的 3 轮阈值只约束**模型调用 `update_goal action=blocked`**；
driver 的 `round-limit` 直接走 goal service 的 `block`，不受阈值限制。原生两处分开实现：
`GoalStore.update` 保留 `GOAL_TOOL_BLOCK_THRESHOLD`，`GoalStore.blockByDriver` 不做阈值。

---

## 三、代码改动

### 3.1 `core:session`

**`GoalActivationState` + `GoalStore`**

- 构造参数新增 `activation: GoalActivationState = GoalActivationState()`（默认 disarmed）；
- `create` → arm；`update` 按 action arm/disarm；`resume` 允许 active+disarmed（会话恢复后的恢复通道），
  仍拒绝 model 对 paused 的 resume（`GOAL_TOOL_RESUME_PAUSED`）；
- 新增用户/宿主 API：`pauseAsUser` / `resumeAsUser` / `editAsUser` / `clearAsUser` / `blockByDriver` / `disarm`；
- 修复 `goal/change` 的 `operation` 原来恒写 `"block"` 的 bug（现在写真实动作，`blocked` → `block`）；
- 修复轮次重复计数：`roundsStarted` = 最新 `goal/change` 快照里的基数 + **该事件之后**的 goal 消息
  （原来把快照之前的消息又数了一遍）；
- 上游语义补齐：已完成的目标可以被新 `create` 直接替换；未完成的仍 `GOAL_ALREADY_EXISTS`。

**`GoalRoundDriver.kt`（新）**

- `decideAtIdle()` → `Continue(view, round)` / `Blocked(view)` / `Stop`；
- `onInterrupted(round)`：匹配当前 goal id+revision 的 goal 轮 → `pauseAsUser`，否则只 `disarm`；
- `GoalRoundPrompt.render()` 逐字节对齐上游：

```text
<goal_round>
Objective: "…JSON.stringify(objective)…"
Round: N/max

Continue working toward the objective in this same session. Treat the current workspace, tool results, and durable session state as authoritative; inspect them instead of assuming earlier narration is still current. Make concrete progress and verify the result. Before claiming completion, gather evidence that the whole objective is achieved, read the current goal, and mark it complete. If work remains, leave the goal active for the next round. Follow the configured goal-tool policy before reporting a blocker.
</goal_round>
```

`quote()` 实现 JS `JSON.stringify(string)` 的转义（`"`、`\`、U+0000–U+001F；中文原样），单测锁定。

**`SessionSession`**

- `appendUserMessage(text, source)`：goal 轮写 `source={kind:"goal", goalId, revision, round}`；
- 修复**重开会话后 turn 从 1 重新编号**（docs/五.6 的旧账）：从已落盘 `turn/start` 的最大 turn 续号；
- `appendRaw` 加 `@Synchronized`、`events` 加 `@Volatile`：面板的 pause/resume 与 turn 的 IO 协程
  可能同时写同一会话，必须串行化 seq/帧。

### 3.2 `app`

- `runTurn` 拆成 `startTurn`（循环）+ `runTurn`（单轮）：
  - `TurnInput(text, goalRound?)`；goal 轮在 user 消息前额外写 `request/header`（独立 request series）；
  - 每轮结束后 `nextGoalTurn()` 按 activation 判决；`outcome = MAX_TOKENS/ERROR` 时只 disarm；
  - 续轮之间检查到期提醒并让路（提醒是用户输入，投完再继续 goal）；
- `stop()` 不再同步把 `streaming=false`：取消收尾要先把 `turn/end` 写完，避免用户立刻再发造成并发写；
- 取消收尾：`stepOpen/assistantPersisted` 保证事件平衡、不重复写 assistant；用户 stop 且当前是 goal 轮 → pause；
- 工具执行后 goal 三件套立刻刷新目标条（不等本轮结束）；
- 用户面板操作：`pauseGoal`（运行中会 abort turn）、`resumeGoal`（idle 时立即 kick 一轮）、`editGoal`、`clearGoal`；
- 调试通道新增 `--ei dsh_goal_max_rounds N`（配合 `--es dsh_goal` 验 round-limit）。

### 3.3 UI（`ChatScreen`）

- goal 轮消息不再显示内部提示词，改成分隔条「自动续轮 · 第 N 轮」；搜索也跳过其正文；
- 目标条可点，右侧箭头；文本增加 activation：active+disarmed 显示「已停续轮」；
- `/goal` 面板（AlertDialog）：objective / 阶段 / 轮次 / 受阻原因；按钮按状态出现
  **暂停**（active+armed）、**继续**（paused / blocked / active+disarmed，轮次用尽时提示先改上限）、
  **编辑**（objective + 上限）、**清空**（二次确认）、关闭；操作失败在面板内显示文案；
- 「更多 → 目标」入口（无目标时置灰）。

---

## 四、v4 事件证据

`parity/runs/goal-session*.jsonl`（`tools/session-dump.ps1` 解包）：

```text
seq 0    goal/change operation=create revision=1 maxGoalRounds=2 roundsStarted=0
seq 1    turn/start turn=1
seq 3    user/message source={kind:"user"}
seq 65   turn/end turn=1 completed
seq 67   request/header（goal 轮独立 request series）
seq 68   turn/start turn=2
seq 70   user/message source={kind:"goal",goalId,revision:1,round:1}
seq 83   turn/end turn=2 completed
seq 84   request/header
seq 85   turn/start turn=3
seq 87   user/message Round: 2/2
seq 95   turn/end turn=3 completed
seq 96   goal/change operation=block revision=2 roundsStarted=2
         blockedReason={code:"round-limit",message:"Goal reached its configured limit of 2 rounds."}
```

用户停止/面板暂停：

```text
seq 189  turn/end turn=10 aborted
seq 190  goal/change operation=pause revision=14
...
seq 196  goal/change operation=pause revision=16
seq 199  turn/end turn=11 aborted
```

（pause 事件先于 aborted 的 turn/end 是正常的：宿主先改状态再取消 turn。）

---

## 五、测试

- `:core:session:test` **27 个**全绿：`GoalStoreTest` 13（含 activation/用户恢复/清空/operation）、
  `GoalRoundDriverTest` 7（提示词逐字节、JSON 转义、Continue、round-limit、paused/disarmed、
  interrupt pause、无关取消只 disarm）、`ScheduleStoreTest` 7。
- `:core:prompt:test :core:protocol:test :core:parity:test :core:legacy:test :core:tools:test
  :core:llm:test :core:attachment:test` 全绿。
- 门 2：`.\tools\parity.ps1 verify` = **114 vs 114 PASS**（02:42 夹具）。
  注意：夹具的 bash 场景会真的 `ls -1` 工作目录，验收前要把新增测试文件移出 workspace，
  否则第二个请求体（tool_result）对不上夹具。本次把 `goal-rounds.txt` 移到 `files/goal-rounds-evidence.txt`。

---

## 六、没做 / 已知限制

1. **收尾指令没做**：上游 goal 轮里 `complete`/`blocked` 后会 `deferContext(renderWrapupContext(...))`，
   注入 `<goal_complete>` / `<goal_blocked>` 让模型写收尾消息。原生目前在 goal 轮里调这两个 action
   会正常结束本轮，但没有这条额外 user 通知。
2. **工具权限校验没做**：上游 `create/edit/pause/resume` 要求 direct human，`complete/blocked` 要求
   人类或当前 goal 轮。原生工具目前只看状态机，不看消息来源。
3. **多 agent/队列竞争没做**：手机端单 agent，上游的 inbox/attempt/pre-step fence、durability flush
   不适用；但同一进程里“用户消息 vs 自动轮”的竞争只靠 `streaming` 串行化。
4. **blocked 后编辑上限**：面板改上限只改 durable 字段，`blockedReason.message` 保留旧文案，
   要等用户「继续」才清掉（与上游 edit 不改 phase/reason 一致）。
5. **提醒让路只验证了代码路径**，还没真机跑「长 goal + 到期提醒插队」场景。

---

## 七、下一步

1. 收尾指令（`<goal_complete>/<goal_blocked>`）+ 工具权限（direct human / goal round）。
2. `core:engine` 抽取：把 `startTurn` 循环从 AppViewModel 挪走，顺便让对拍台能跑 goal 场景。
3. 真机补「长 goal + 提醒插队」和「goal 轮中 `ask_user_question`」两个交叉场景。
4. 聊天：消息引用/重发、按时间分隔、导出会话；M9 搜索跳转/长按复制逐项点验。

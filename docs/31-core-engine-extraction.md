# 31 · core:engine：把一轮对话从 AppViewModel 里抽出来（M14）

> 2026-11-07 03:5x｜基线 `80437b4`（M13 聊天跨天/长按之后）。
> 目标：还掉 docs/28 §七.2、docs/29 §七.1、docs/30 §五.3 连续欠了四轮的账——
> `startTurn` / `runTurn` / goal 续轮循环从 `AppViewModel`（1293 行）挪进 `core:engine`，
> 让「一轮对话怎么跑」不依赖 Compose，可被 JVM 单测和后续的 subagent/jobs 复用。

---

## 一、一句话结果

| 项 | 之前 | 现在 |
|---|---|---|
| 轮次循环位置 | `AppViewModel`（1293 行，直接读写 Compose 状态） | `core:engine/TurnEngine.kt`（563 行，纯 JVM + 接口注入） |
| 请求体组装 | `AppViewModel.buildRequest`（约 85 行） | `TurnEngine.buildRequest`（同逻辑，逐字节不变） |
| 单测覆盖 | 无（轮次循环只能真机验） | `core:engine` **7 个** JVM 单测：事件平衡、工具循环、审批拒绝、goal 续轮 + round-limit、收尾通知顺序、取消落库、max_tokens 只 disarm |
| 门 2 | 114 vs 114 PASS（1 个场景） | **5 个场景全 PASS**（shell/read/write/edit/tool-error，请求 sha 与夹具逐个对齐） |
| 真机 | — | 普通对话 + goal 1/1 收尾两条闭环（见 §四） |

`AppViewModel` 现在只负责：设置/会话仓库/审批与提问的 UI 弹窗、`ChatSessionState` 适配（`ComposeTurnUi`）、
`stop()` 取消、以及调度入口（`send` / `kickGoalIfIdle` / 提醒轮询）。

---

## 二、为什么要抽（不是洁癖）

1. **下一步的 `subagent` / `subagent_fork` / `job_*` 需要「进程内再跑一个 agent」**：
   只能复用一个不绑 UI 的引擎，否则第二个 agent 要复制一遍 1300 行 ViewModel。
2. **轮次循环是事故高发区**（半截回复丢失、step/turn 不平衡、收尾通知顺序、取消丢事件），
   但以前**没有一行单测**——只能靠真机 + 日志。抽出来后才可能用假 LLM 重放这些边界。
3. **契约 #4/#5（工具行为、流式与中断）需要可复现测试**：现在取消、max_tokens、审批拒绝都是 JVM 用例。

---

## 三、设计

### 3.1 契约（`EngineContracts.kt`）

```kotlin
data class TurnInput(val text: String, val goalRound: GoalRoundRef? = null)
enum class TurnOutcome { COMPLETED, MAX_TOKENS, ERROR }

interface TurnUi {                    // Compose 状态容器要实现的 11 个方法
    fun messages(): List<ChatMessage> // 消息列表仍归 UI（流式逐字替换是快照状态的强项）
    fun title(): String; fun setTitle(...); fun setSessionId(...)
    fun setStreaming(...); fun setStatus(...); fun setError(...); fun setUsage(...); fun setGoal(...)
    fun appendMessage(message: ChatMessage): Int
    fun updateMessage(index: Int, message: ChatMessage)
}

interface SessionGateway { fun current(): SessionSession?; suspend fun create(): SessionSession }
class TurnStopState { @Volatile var requested = false }   // 宿主与引擎共享的「用户按了停止」

class EngineDeps(
    workspacePath, config: () -> LlmConfig, providerLabel: () -> String, reasoningEffort: () -> String,
    registry, toolContext, stop, apiTools, systemPrompt: () -> String, runtimeContext: () -> String,
    stream, sessions, goals, imageBytes, approval,
    nextSchedulePrompt, tickSchedules,
    onRequest: (Int, ApiRequest) -> Unit, onLlmEvent, onToolResult, onTurnFinished, newId,
)
```

设计取舍（写死理由）：

- **消息列表仍由 UI 持有**：`ChatSessionState.messages` 是 `mutableStateListOf`，
  流式 token 每 33ms 就地替换一条消息；让引擎再持一份会变成两份真相。
  引擎通过 `appendMessage` 拿到下标、通过 `updateMessage(index, …)` 改写。
- **设置/配置全部用 lambda**：`config = { llmConfig() }`，用户中途改模型/baseURL 下一轮立即生效；
  抽出来时如果拍成 `val` 就会吃到引擎构造瞬间的旧值。
- **停止开关独立成对象**：`stop()`（用户）与 `cancelStream()`（切会话）都要写它，
  放在 `EngineDeps` 里避免 App 侧再碰引擎实例（也顺带解决了「init 里调试发送时 lazy 还没初始化」的顺序坑）。
- **`requestNo` 归引擎**：门 2 `run.jsonl` 里 `llm.request.n` 与 `llm.event.n` 必须同号，以前靠两个字段凑，
  现在引擎自增、通过 `onRequest(n, request)` 交给宿主落盘。

### 3.2 搬走的逻辑

`TurnEngine` 现在是这两段的等价搬家（逐行对照，无行为改动）：

- `startTurn`：`runTurn → nextGoalTurn → 到期提醒插队 → 继续/退出 → tickSchedules()`；
- `runTurn`：`request/header? → turn/start → step/start → user/message → 流式 → assistant/message →
  tool/call → tool/result →（收尾通知）→ … → step/end → turn/end`；
- 取消路径：没落过库的半截 assistant 先补写 → 补 `step/end` → `turn/end aborted` →
  用户 stop 的 goal 轮 `pause`、无关取消只 `disarm`；
- `buildRequest`：运行时上下文 + 同角色消息合并 + reasoning 原样回传 + image 句柄/base64 + 工具清单过滤。

### 3.3 App 侧只剩适配

```kotlin
private inner class ComposeTurnUi(private val state: ChatSessionState) : TurnUi { … }

private fun startTurn(state: ChatSessionState, input: TurnInput) {
    streamJob = viewModelScope.launch {
        try { engine.startTurn(ComposeTurnUi(state), input) }
        finally { state.streaming = false; state.statusText = null; refreshSessions() }
    }
}
```

---

## 四、验证

### 4.1 JVM 单测（7）

`core/engine/src/test/.../TurnEngineTest.kt`，用假 LLM（脚本化 `LlmEvent`）+ 假工具 + 临时目录真 v4 会话：

| 用例 | 锁定的契约 |
|---|---|
| `plainTurnWritesBalancedEvents` | 事件顺序固定；运行时上下文与用户输入是**同一条** user 消息的两个 text 块（与夹具一致） |
| `toolLoopContinuesInSameTurn` | 工具循环在同一 turn 内两 step；`tool/result` 早于第二次 assistant；schema 原样进请求 |
| `deniedToolBecomesErrorResult` | 用户拒绝 → `User denied this tool call.` + `isError` |
| `goalRoundContinuesThenBlocksOnRoundLimit` | idle 点自动续轮（`source.kind=goal`、round 1/1、提示词逐字节）；轮次用尽由 driver 写 `round-limit` |
| `wrapupNoticeAppendedAfterAllToolResults` | 收尾通知排在**全部** tool_result 之后（上游 deferContext 顺序） |
| `cancellationPersistsPartialAssistantAndClosesTurn` | 取消时半截回复落库 + `turn/end aborted` + step 平衡 |
| `maxTokensStopsContinuationAndDisarms` | 超 token 只 disarm，不续轮、不改 phase |

全仓 core 模块（prompt/protocol/parity/legacy/session/tools/llm/attachment/engine）全绿。

### 4.2 门 2（设备回放）

```text
.\tools\parity.ps1 verify -Captures parity\fixtures\device-smoke
→ 回放器 2 个夹具；run-1 / run-2 各 58014 bytes run.jsonl
→ status PASS，114 vs 114
```

两次 run 的 `llm.request` sha 与夹具逐个相等（脚本里 `Test-RunMatchesFixtures` 校验），
说明抽出后的请求体**逐字节没变**。

#### P0 场景从 1 个扩到 5 个

抽引擎的同时补了四个真机录制场景（每个都是"真实端点录一次 SSE → 回放两次比对"）：

| 夹具 | 场景 | 覆盖 | 结果 |
|---|---|---|---|
| `device-smoke` | bash `ls -1` 列目录 | shell 执行 + 工作区读取 | 114 vs 114 PASS |
| `device-read` | `read hello.md` 取第一行 | fs 只读工具、UTF-8 内容 | 80 vs 80 PASS |
| `device-write` | `write parity-write.txt` 三行 | 文件创建、内容落盘 | 158 vs 158 PASS |
| `device-edit` | `write` 后再 `edit` foo→baz | 写 + 改的组合、edit 结果文案 | 80 vs 80 PASS |
| `device-read-error` | `read not-exist.txt` | 工具错误路径（`isError` 结果回灌） | 228 vs 228 PASS |

**新参数 `-PreRunClean`**（`tools/parity.ps1`）：写/改类场景不幂等——`write` 第一次返回
`Created file`、第二次就是 `Updated file`，夹具必然对不上。所以每次 run 前按相对路径删工作区产物：

```powershell
.\tools\parity.ps1 record -Captures parity\fixtures\device-write -PreRunClean parity-write.txt -Prompt "…"
.\tools\parity.ps1 verify -Captures parity\fixtures\device-write -PreRunClean parity-write.txt -Prompt "…"
```

（record 里也调了同一段清理，保证录制起点和回放起点一致。场景跑完记得把产物从设备工作区删掉，
否则 `device-smoke` 的 `ls -1` 夹具会对不上。）

剩下 1 个 P0 场景（失败重试 / 取消中断）不是"补一条 prompt"能解决的：当前 record 只录成功 SSE，
取消中断还需要在流中途注入 `am` 停止信号，留到门 2 下一轮。

### 4.3 真机

| 截图 | 内容 |
|---|---|
| `docs/shots/engine-smoke.png` | 普通对话：用户气泡 → 「思考过程 253 字」→「引擎抽取完成。」+ 今天分隔 |
| `docs/shots/engine-goal.png` | goal（max=1）：目标条「已完成 · 第 1/1 轮」+ 收尾消息（做了什么/如何验证/需要你留意） |

goal 会话 `a868bb25-…` 的事件序列（`parity/runs/goal-engine-session.jsonl`）：

```text
seq 0   goal/change create（active）
seq 1   turn/start 1
seq 3   user/message kind=user
seq 6   turn/end completed
seq 9   turn/start 2                       ← 自动续轮
seq 11  user/message kind=goal round=1
seq 13  tool/call get_goal
seq 14  tool/call bash
seq 20  tool/call bash
seq 25  tool/call read
seq 30  tool/call update_goal
seq 31  goal/change complete
seq 33  user/message kind=tool-goal        ← 收尾通知在 tool_result 之后
seq 38  turn/end completed
```

验收后删掉 bash 写出的 `engine-goal-check.txt`，workspace 恢复成 `agent-shot.png` + `hello.md`。

---

## 五、没做 / 已知限制

1. **P0 场景 5/6**：bash-ls / read / write / edit / tool-error 已录（见 §四.2）；
   「失败重试」「取消中断」需要夹具/时序支持（当前 record 只录成功 SSE、没有流中注入停止的通道），
   留到门 2 下一轮。给 `parity.ps1` 再加「场景名 → prompt + PreRunClean」的套件表之后，
   一条命令就能跑完 5 个场景。
2. **`onTurnFinished` 用的是「当前 chat」**：如果 turn 跑到一半用户切走会话（会被取消），
   `run.jsonl` 的 `turn.end` 可能记错状态容器。只影响调试录制，不影响会话落库。
3. **引擎实例是 ViewModel 的单例**；`requestNo` 因此全 App 连续编号（与抽出前一致）。
4. **单测没有覆盖 image 块 / 提醒插队 / ask_user_question 挂起**：前两个靠既有真机证据，
   第三个是 docs/29 §六.4 的旧账（goal 轮里提问的交叉场景）。
5. `AppViewModel` 仍有 943 行：会话加载/列表、审批/提问、schedule 适配、goal 面板、调试通道还在里面。
   下一步可把「会话投影」和「调试通道」再拆出去。

---

## 六、下一步

1. 用新引擎做 `subagent` / `subagent_fork`（同一 `TurnEngine` + 独立 `ChatSessionState`）。
2. `job_list` / `job_output` / `job_kill`：后台 bash 任务表 + 输出环形缓冲。
3. 门 2 补场景：read / write / edit / tool-error；给 `parity.ps1` 加「场景名 → prompt + workspace 前置」表。
4. 聊天：消息级「...」入口、导出 Markdown。

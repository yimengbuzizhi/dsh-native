# 33 · 子代理：`subagent` / `subagent_fork` / `list_agents` / `send_message` / `interrupt_agent`（M16）

> 2026-11-08 04:2x｜基线 `c7e6c66`（M15 后台任务）之后。
> 目标：工具 23 → **28 / 32**，把上游的进程内委派（`dsh-subagent` + `-fork-in-process` +
> `dsh-tool-subagent` + `dsh-tool-subagent-control`）移植成原生：子代理是**独立会话 + 独立
> TurnEngine**，父代理只拿最终文本/通知，不共享上下文（fork 除外）。
> 上游依据：`dsh-tool-subagent/lib/index.js`（工具文案/返回 `<id>`）、
> `dsh-subagent/lib/types/continuation-messages.js`（结算通知逐字）、
> `dsh-subagent/lib/types/manager.js`（listing/权限/omitClosing）、
> `dsh-tool-subagent-control/lib/index.js`（三个控制工具）。

---

## 一、一句话结果

| 项 | 之前 | 现在 |
|---|---|---|
| 工具数 | 23 / 32 | **28 / 32**（新增 subagent / subagent_fork / list_agents / send_message / interrupt_agent） |
| 子代理运行时 | 无 | 新模块 `core:agents`：代理表 + 深度上限 + 收件队列 + 结算通知 + 父子收发授权 |
| 子代理执行 | 无 | App 级独立 `TurnEngine` + **独立 v4 会话文件**（`parentSession` / `origin=subagent` / `delegationDepth`） |
| 通知 | job 完成通知 | 子代理结算 `source.kind:"subagent-settled"` + 子代理消息 `"agent-message"`（relay），忙时 step 起点注入 / idle 唤醒一轮 |
| 单测 | — | `core:agents` **12** + `core:tools` +10（总 **70**）+ `core:engine` +2（总 **11**）+ `core:prompt` +1 |
| 门 2 | 5 个夹具 | 工具表 + 系统提示词（`tool:subagent` 段）变化 → **5 个夹具全部重录**，verify 全 PASS（§四.3） |
| 真机 | — | 三条闭环：委派→子代理写文件→结算→父代理收尾；委派→`list_agents(running)`→`interrupt_agent`→aborted 通知；**父代理 idle 时被结算通知唤醒一轮**（截图 `subagent-tools.png` / `subagent-interrupt.png` / `subagent-idle-wake.png`） |

---

## 二、上游语义（读源码确认）

### 2.1 `subagent` 是异步 activation

`dsh-tool-subagent` 不等待子代理跑完：`startActivation()` 返回 `{kind:"activation", subagentId}`，
模型侧渲染 **`started subagent <id>`**；`subagentId` 就是**子会话 id**。子代理完成后由运行时
把结果作为一条 user 通知投给父代理（`notifySettlement`），父代理 idle 时被唤醒一轮。

`subagent_fork` 是同一个工具换一套 description/prompt 文案（`providerWording(true)`）：
子代理继承父会话**已完成的轮次**（不含正在跑的当前轮）。

### 2.2 子代理的初始任务与返回指导

可续（continuable）的子会话在任务末尾追加一段指导（`withContinuableReturnGuidance`）：

```text
Your parent agent id is "<parentId>". Before you finish, send your result to that agent with
send_message({ agent_id: "<parentId>", message: "<self-contained result>" }). The parent shares
your workspace but does not automatically receive your transcript, tool output, or reasoning. Send
earlier messages as well when a finding changes what the parent should do next; sending a message
does not end your turn.
```

它是**第二个 text 块**，不是拼进任务正文——所以 v4 `user/message.content` 与请求体都必须保留两个块。

### 2.3 结算通知（逐字）

`createSettlementMessage`：第一个块是摘要（`boundContextSummary` 上限 120），随后：

- 子代理给父代理发过消息 → `It sent you a message just before finishing, so its closing message is omitted.`
- 没有收尾话 → `It left no closing message.`
- 否则 → `Its closing message:` + 最后一个 assistant 的 text 块。

摘要按终态分：

| stopReason | 摘要 |
|---|---|
| completed（可续） | `Background subagent <id> finished and will do no further work unless you send it more.` |
| completed（不可续） | `Background subagent <id> finished. It cannot receive follow-up messages.` |
| aborted | `Background subagent <id> was stopped before it finished.` |
| max-tokens | `Background subagent <id> ran out of room before it finished.` |
| refusal | `Background subagent <id> declined the task.` |
| error | `Background subagent <id> failed before it finished.` |
| 其他 | `Background subagent <id> ended abnormally (<reason>) before it finished.` |

`source = {kind:"subagent-settled", form:"notice", summary, senderSessionId}`。

### 2.4 `send_message` 是 relay，不是问答

- 正文块：`Agent <senderId> sent a message: ` + 消息块；
- `source = {kind:"agent-message", form:"relay", senderSessionId}`；
- 工具回执 **`message delivered to agent <id>`**（不返回对方答案）；
- 只允许**直接父子**：父→子、子→父；兄弟/无关代理拒绝；
- 子在收尾前给父发过消息 → 结算通知省略 closing text（`omitClosingText`）。

### 2.5 深度与权限

- `maxDepth` 默认 **1**：根能委派一层，子代理再调 `subagent` 报
  `subagent depth 2 exceeds maxDepth 1`；
- 子代理的权限档位在启动时固定：需要审批的操作**自动拒绝**（运行时上下文自述
  `SUBAGENT_DELEGATION_CONTEXT`），不能在子会话里提权；
- 深度用尽时父运行时会附一条上下文：
  `Delegation depth for \`subagent\`: current N, maximum M, remaining levels 0. Do not call ...`。

---

## 三、实现

### 3.1 `core:agents`（新模块，纯 JVM）

| 文件 | 内容 |
|---|---|
| `AgentRegistry.kt` | 代理表、`start/interrupt/listChildren/listDescendants/send/takeNotices/shutdown`、结算通知构造、深度检查、父子授权 |

关键设计（写死理由）：

- **代理 id 由 App 传入 = 子会话 id**（上游 `subagentId == childId`）：`start(..., id = session.id)`，
  这样 `list_agents` 的 id、`send_message` 的 agent_id、结算通知里的 id 都能直接拿去开子会话。
- **收件队列按收件人分组**：结算→父、`send_message`→目标。收件人忙时引擎在 step 边界取；
  idle 时宿主用 `onNotice` 唤醒一轮；`hasNotices` 给「取空→放标记」的竞争兜底。
- **父子授权**：根会话不是 `AgentRecord`，所以 `send()` 允许「未知 sender 恰为目标记录的 parentId」；
  子→父另记 `reportedToParent`，结算时省略 closing text。
- **interrupt 的两段取消**：注册表取消启动协程（还没开始跑就只置 `stopRequested`，由启动体自己
  按 aborted 结算，避免 cancel 掉连结算都不执行）；App 另取消该子代理的续轮协程。
- **结算只做一次**：`settle` 先查 `status`；teardown（`shutdown`）后不再投递通知。

### 3.2 `core:tools`

- `ToolContext` 新增 `agents: AgentHost?`；`AgentHost` 只暴露工具需要的
  `currentAgentId/currentDepth/maxDepth/start/list/send/interrupt`（core:tools 不依赖 core:agents）。
- 新 `agents/AgentTools.kt`：五个工具与上游逐字文案（`started subagent` / `(no subagents)` /
  `<id> [running] — <label>` / ` parent= depth=` / `message delivered…` / `interrupt requested…`）。
- `AgentHost.start` 是 `suspend`：App 要先建子会话（id 要回填给注册表）。

### 3.3 `core:session` / `core:engine`

- `SessionSession.appendUserMessageTexts()`：一个 user/message 写多个 text 块（子代理任务、
  结算通知）；`appendUserMessage` 保留为单块快捷方式。
- `SessionRepository.create` 支持 `parentSession / isSeeded / origin / delegationDepth`；
  `SessionSummary.parentSession` 用于把子会话挡在主列表外。
- `TurnInput.textBlocks`：普通轮单块、子代理初始任务两块；`buildRequest` 对 USER 消息**逐块**发，
  不再 join（现有单块场景逐字节不变）。
- `UserNotice.texts / source`：通知可以多块、可覆盖完整 source（`subagent-settled` 与
  `tool-jobs` 的 source 形状不同）；wake 轮与 step 边界注入共用同一套落库逻辑。
- `TurnEngine.startTurn` 返回最后一次 `TurnOutcome`（子代理结算需要 completed / max-tokens / error）。

### 3.4 App / UI

- `AppViewModel`：App 级 `AgentRegistry` + `agentScope`（子代理不因切会话/切页面中断）；
  - `RootAgentHost`：根会话 depth 0，fork seed = `state.messages.take(turnStartIndex)`；
  - `ChildAgentHost`：子代理 depth N，再委派超限直接报深度错误；
  - `runChildAgent`：建子会话（header 带 parent/origin/depth）→ 独立 `ToolContext`（workspace/cwd、
    jobOwner=子 id、goal/schedule 绑子会话、`askUser` 全部跳过、审批固定）→ 独立 `TurnEngine`；
  - 通知统一走 `takeNoticesFor(owner)`（job + agent 合并）；`JobSettlement` 新增 `owner`，
    子代理自己起的后台任务结算时也能唤醒**子代理**（而不是根会话）；子代理 idle 由
    `wakeChildAgent` 唤醒；
  - `childApproval`：只读工具放行，其余仅 `approvalMode=auto` 放行（与子代理权限固定一致）。
- 聊天页：`subagent-settled` 渲染「子代理完成/已停止/失败」分隔条；`agent-message` 渲染轻卡片
  （显示子代理报告正文，不伪装成用户气泡）；工具卡 `compactArgs` 认 `description/agent_id`。
- 子代理会话不进主会话列表（`parentSession != null` 过滤），留给后续「子代理」面板。

---

## 四、验证

### 4.1 单测

| 模块 | 数量 | 覆盖 |
|---|---|---|
| `core:agents` | 12 | 结算文案、无收尾话、omitClosing、aborted、error+diagnostic、深度上限、父子授权、children/descendants、通知只投一次、启动前 interrupt、summary 截断 |
| `core:tools` | 70（+10） | 五个工具的渲染/必填/不可用/深度错误透传/registry 28 个工具 |
| `core:engine` | 11（+2） | 多 text 块任务在会话与请求体里都保持分块；子代理结算通知的 source/3 块正文 |
| `core:prompt` | 6（+1） | 注册委派工具后 `tool:subagent` 段自动拼进系统提示词 |

全仓 core 测试绿。

### 4.2 真机（PLT120 / Android 15，账号通道 deepseek-flash）

| 截图 | 内容 |
|---|---|
| `docs/shots/subagent-tools.png` | `subagent`（写 `agent-child.txt`）→ `list_agents` → 子代理 `send_message` 报告 → 「子代理完成」分隔条 → 唤醒轮里父代理 `bash` 校验并收尾，回复里带子代理 id 与文件路径 |
| `docs/shots/subagent-interrupt.png` | `subagent`（子代理 sleep 60）→ `list_agents` 显示 `[running]` → `interrupt_agent` → 结算通知「was stopped before it finished / It left no closing message.」 |
| `docs/shots/subagent-idle-wake.png` | 父代理启动子代理后**立刻结束本轮**；父 idle 时先收到 `agent-message` 唤醒（新 turn），再在下一个 step 收到 `subagent-settled`，父代理读文件后回复结果 |

事件证据（`tools/session-dump.ps1` 解包）：

```text
# 根会话 76eb1f0d（正常闭环）
turn/start 1 → step 1 subagent → step 2 list_agents → step 3 bash sleep 20
step 4 start → user/message source={kind:"agent-message", form:"relay", senderSessionId:8a0af59b…}
             → user/message source={kind:"subagent-settled", form:"notice", summary:"Background subagent …"}
             → bash 校验 → step 5 → turn/end completed

# 子会话 ad7f5d2b（header.parentSession=76eb1f0d, origin=subagent, delegationDepth=1）
user/message content=[任务, 返回指导] → bash → write → bash → send_message → "done" → turn/end completed
# 子会话 19154422（被 interrupt）：turn/end reason.kind = "aborted"

# 根会话 c0b96d95（idle 唤醒：通知在**新 turn** 的 step/start 之后落库）
seq 13 turn/start
seq 14 step/start
seq 15 user/message source={kind:"agent-message", form:"relay", senderSessionId:a3db78fe…} blocks=2
seq 20 step/start
seq 21 user/message source={kind:"subagent-settled", form:"notice", summary:"Background subagent …",
                          senderSessionId:a3db78fe…} blocks=2
```

### 4.3 门 2（工具表变化 → 5 个夹具全部重录）

新增 5 个工具后 `tools` 数组（28 个）和系统提示词（`tool:subagent` 段）都变了，5 个夹具整套重录：

| 夹具 | 场景 | 结果（run-1 vs run-2） |
|---|---|---|
| `device-smoke` | bash `ls -1` | **71 vs 71 PASS** |
| `device-read` | `read hello.md` | **126 vs 126 PASS** |
| `device-write` | `write` 三行（`-PreRunClean parity-write.txt`） | **75 vs 75 PASS** |
| `device-edit` | `write` + `edit`（`-PreRunClean parity-edit.txt`，本轮模型多做了两次 bash 校验 → 5 个请求） | **245 vs 245 PASS** |
| `device-read-error` | `read not-exist.txt` | **193 vs 193 PASS** |

回放请求体确认：`tools.length = 28`、系统提示词含 `Start independent delegations`。
跑完清理工作区，恢复成 `agent-shot.png` + `hello.md`；App 设置还原
（`approval=ask`、`base_url` 官方、SSE/run 录制关闭）。

---

## 五、没做 / 已知限制

1. **fork 只 seed 内存上下文，不复制父会话前缀到子会话文件**：子会话 header 有
   `isSeeded=true`，但文件从空开始；请求体里能看到父会话已完成的消息。进程内可续，重启后
   不能从文件恢复 fork 的前缀（上游会把父日志前缀写进子会话）。
2. ~~**没有子代理面板**~~ → **已在 M17 补齐**（docs/34）：一级页「子代理」+ 工作台摘要卡，
   running/inactive 列表、只读回放、停止、看结果；重启后靠子会话文件 + `lastStopReason` 显示历史。
3. **子代理 `ask_user_question` 一律按跳过处理**：没有把子代理的问题路由到用户 UI。
4. **子代理权限只有两档**：只读工具放行；其余仅全局 `approvalMode=auto` 放行，
   没有「每次询问时把请求转给父会话的用户」。
5. **续轮中断不产生新的结算通知**：`send_message` 续轮被 `interrupt_agent` 停掉时，
   子会话落 `turn/end aborted`，但父代理不会收到第二次 settlement（上游按 activation 语义
   也只结算一次；这里按「调用方自己 interrupt，自己知道」处理）。
6. **子代理运行在进程内**：App 被杀后子会话文件还在，但运行中的代理与队列消失（与 jobs 一致）。
7. **子会话 title 是引擎 fallback**（任务前 18 字）：`list_agents` 用 label，UI 面板做出来时要改用 label。
8. **`subagent` 工具本身在 ask 档位需要审批**（根会话弹窗）；子代理里再调会被深度/审批拦下。

---

## 六、下一步

1. 子代理面板（工作台/任务页）：running/inactive 列表、打开子会话、kill、结果抽屉。
   → **M17 已完成，见 docs/34。**
2. 工具批次 C：`exit_plan_mode` / `load_workspace_dependencies` / `workflow`（workflow 可先用
   QuickJS 或 Kotlin 脚本子集）+ `list_subagent_models`（夹具里没有，按上游挂）。
3. 门 2 补「失败重试」「取消中断」两个场景（需要假 LLM / 流中注入停止）。
4. 聊天尾巴：消息级「...」入口、导出 Markdown、搜索逐项点验。

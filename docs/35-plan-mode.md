# 35 · 计划模式 + `exit_plan_mode` + `load_workspace_dependencies`（M18）

> 2026-11-08 04:5x｜基线 `8e9a6f8`（M17 子代理面板）之后。
> 目标：工具 28 → **30 / 32**，把上游 `dsh-plan-mode` 的「计划 → 审批 → 执行」流程移植成原生：
> 会话级 `plan/mode` 状态、动态 `plan:policy` 系统提示词段、`exit_plan_mode` 审批工具、聊天页计划条；
> 同时挂上 `load_workspace_dependencies`（Android 没有随包 payload，如实报不可用）。
> 上游依据：`dsh-plan-mode/lib/index.js`（投影/预步骤/工具/命令）、
> `dsh-web-app/presets/standard.patch.yml`（`plan-mode.config.section` 逐字文案，从 `app.asar` 提取）。

---

## 一、一句话结果

| 项 | 之前 | 现在 |
|---|---|---|
| 工具数 | 28 / 32 | **30 / 32**（新增 `exit_plan_mode` / `load_workspace_dependencies`） |
| 计划模式 | 无 | 会话级 `plan/mode` 事件 + 投影 + 动态提示词段 + 聊天页状态条 + 菜单开关 |
| 计划审批 | 无 | `exit_plan_mode`：检查计划格式 → 弹「Plan review」审核卡（完整 markdown + Approve / Keep planning）→ 批准后**下一个 step 起点**退出 |
| 切换 narration | 无 | 用户在轮次内/空闲切换时按上游语义补 `source.kind:"plan-mode"` 的 user 通知（只在「上一次请求描述的是另一态」时） |
| `load_workspace_dependencies` | 无 | 工具已挂（schema/description 与上游一致）；Android 无 Python/Node payload → 如实报不可用 |
| 单测 | engine 11 / tools 73 / prompt 6 / session 36 | **engine 14 / tools 81 / prompt 7 / session 40** |
| 门 2 | 5 个夹具（28 工具） | 工具表 30 个 → **5 个夹具全部重录**（§五） |
| 真机 | — | 计划模式进入/审核/批准/退出 + 空闲切换 + narration 全部跑通（截图 4 张 + 事件证据） |

---

## 二、上游语义（读源码确认）

### 2.1 状态：`plan/mode` 是唯一真相

`dsh-plan-mode` 注册一个 `plan` projection（stateVersion 4），折叠规则：

- 最后一条 `plan/mode {active}` 决定 `active`；
- `entered` = 曾经 `active=true`（`exit_plan_mode` 的准入沿用到会话结束，退出计划模式**不**把工具从目录里拿掉）；
- `request/header` 时记 `activeAtLastHeader`（上一份请求告诉模型的是哪一态）。

切换的两种时机：

| 时机 | 行为 |
|---|---|
| 轮次外（idle） | 立即 append `plan/mode`；若 `activeAtLastHeader` 与目标不同，`agent.inject(narration)` |
| 轮次内（open turn） | 记成 `pendingIntents`，在**下一个被接受的 step** 的 pre-step 落库（`onBoundary`） |

narration 逐字：

```text
The user switched this session to plan mode.
The user switched this session back to the default mode.
```

`source = {kind:"plan-mode", form:"notice", summary:<同上>}`；`exit_plan_mode` 自己的切换 narrate=false
（工具结果已经叙述了这次切换）。

### 2.2 系统提示词段：`plan:policy`（order 500）

上游把文案放在**部署配置**里（`standard.patch.yml` 的 `plan-mode.config.section`），
不是 `system.txt` 快照的一部分，所以之前对拍看不到。M18 把它逐字抓出来存成
`core/prompt/src/main/resources/prompt/0.2.2-rc.1/plan-policy.txt`，只在计划模式激活时渲染，
位置按上游 `SECTION_ORDERS.PLAN_POLICY = 500`（persona 之后、file-reference 之前）。

内容要点（逐字，见资源文件）：只探索不改文件；工具目录跨模式不变；不要用 `todo_write` 追踪计划；
计划必须决策完备；准备好后 `exit_plan_mode` 必须是该条 assistant 消息里**唯一且最后**的工具调用。

### 2.3 `exit_plan_mode`（工具 29）

| 项 | 上游 | 原生 |
|---|---|---|
| 可用性 | 只在 `loggedActive` 时 | `PlanModeHost.active()`；否则 `exit_plan_mode is only available in plan mode` |
| 入参 | `plan`：以 `#` 标题开头的完整 markdown | 同（正则 `^#\s+\S`） |
| 审核问题 | id `plan-review`，header `Plan review`，`Approve this plan and leave plan mode?`，detail=plan | 同（`AskQuestion.detail`，聊天页用 Markdown 渲染） |
| 选项 | `Approve` / `Keep planning` | 同 |
| 批准 | 返回 `{approved:true}`，渲染 `Plan approved — plan mode exited; carry out the plan starting with your next step.`；pending `{active:false, narrate:false}` | 同 |
| 拒绝 | 抛错 `The user chose to keep planning; revise the plan and present it again.` | 同（ToolResult.err） |
| 自定义反馈 | `… their feedback: <text>` | 同 |
| 取消/跳过 | ASK_CANCELLED → `{approved:false}`（turn 结束） | 跳过/空答案按 keep planning 处理（我们的提问通道没有取消语义） |

实现里 `exit_plan_mode` 加进 App 的只读工具集：它本身就是一次用户审批（审核卡），
不再叠一层工具审批弹窗。

### 2.4 `load_workspace_dependencies`（工具 30）

上游返回随包 Python / Node / pnpm 的绝对路径与发行版版本（`runtime.json`）。
Android 包**不携带**这份 payload（桌面版装在 `dsh-runtimes/<runtime>/dependencies/`），
所以原生实现如实返回错误：

```text
workspace dependencies are not bundled in this Android build: no Python/Node.js payload is installed. …
```

schema/description 与 `tools.json` 一致（契约 #2），模型据此改用系统工具。
以后接 Operit 的 Python 运行时，在 `LoadWorkspaceDependenciesTool` 里返回真实路径即可。

---

## 三、实现

### 3.1 `core:session` / `core:model`

- `SessionSession.appendPlanMode(active)`：append `plan/mode {active}`；
- `SessionCodec.planModeOf(events)` → `PlanModeView(active, entered)`（最后一条生效 + entered 粘性）；
- `SessionSummary` 不新增字段（计划模式不是列表信息）。

### 3.2 `core:engine`：`PlanModeState`

```kotlin
class PlanModeState {
    @Volatile var active = false          // 已落库
    @Volatile var pending: Boolean? = null // 轮次内排队
    @Volatile var narrate = true          // 用户切换 = true；exit_plan_mode = false
    @Volatile var activeAtLastHeader: Boolean? = null
    @Volatile var entered = false
    fun selected(): Boolean = pending ?: active
    fun reset(active: Boolean, entered: Boolean)
}
```

- `runTurn` 在**每个 step 的 `session.startStep()` 之前**调用 `applyPendingPlan()`：
  落 `plan/mode`，按需生成 narration；narration 在 `step/start` 之后落成 `user/message`
  （与 inbox 通知同序，`source.kind:"plan-mode"`）。
- `buildRequest` 发请求前写 `activeAtLastHeader = selected()`（对应上游 request/header 的时点）。
- `EngineDeps.plan` / `onPlanChanged`：宿主共享状态、pending 落库后刷新 UI。

事件顺序（真机验证，§五.3）：

```text
… step/end 29 → plan/mode 30 → step/start 31 → assistant/message 32 …
```

### 3.3 `core:prompt`

`PromptPack.systemFor(..., planMode: Boolean = false)` 在激活时追加合成段
`PlanSection(name="plan:policy", order=500, text=plan-policy.txt)`，其余排序/过滤/join 不变。
非计划模式逐字节不变（`desktopSnapshotRoundTrips` 仍 PASS）。

### 3.4 `core:tools`

- `AskQuestion.detail`（长正文，审核卡用；`ask_user_question` 不填）；
- `ToolContext.planMode: PlanModeHost?`（`active()` / `requestExit()`），core:tools 不依赖 core:engine；
- `ExitPlanModeTool`、`LoadWorkspaceDependenciesTool`；registry 28 → 30。

### 3.5 App / UI

- `AppViewModel.planState`（App 级、随会话 `reset`）：引擎与 UI 共用一个对象；
- 根 `ToolContext.planMode` 绑定 `planState`；子代理 `planMode = null`（没有审批通道）；
- `setPlanMode(active)`：轮次内 → pending（`下一步生效`）；idle → 立即落库 + 按需 narration；
  新会话第一条消息之前切换 → 内存标记，`SessionGateway.create` 建文件时补写 `plan/mode`；
- 聊天页：`⋮ → 计划模式`（进入/退出），输入框上方计划条（`只做方案，不改文件；用 exit_plan_mode 提交审批`），
  轮次内切换显示「下一步生效 · …」；`source.kind:"plan-mode"` 的消息渲染成「已进入/已退出计划模式」分隔条；
- 审核卡：`Plan review` + 完整 plan markdown（可滚动）+ `Approve` / `Keep planning` + 自定义反馈；
- 调试通道：`--ez dsh_plan_mode true`（配合 `dsh_send` 新建会话即计划模式，真机脚本验收用）。

**拍板理由**：上游的入口是 `/plan` 斜杠命令。原生输入框没有斜杠命令系统，先用「菜单开关 + 状态条 + 审核卡」
把**行为链**做对（会话事件/提示词/审批/退出时机），斜杠命令等输入触发系统一起做（docs/19 §命令面板）。

---

## 四、验证

### 4.1 单测

| 模块 | 数量 | 新增覆盖 |
|---|---|---|
| `core:engine` | **14**（+3） | pending 在 step/start 前落库 + narration 顺序 + system=计划版；header 已同态不 narrate；pending==active 丢弃 |
| `core:tools` | **81**（+8） | 只在计划模式/计划格式/批准文案/拒绝/反馈/跳过/无提问通道；workspace deps 报不可用；registry 30 |
| `core:prompt` | **7**（+1） | plan:policy 只在 planMode 渲染、位置在 persona(0) 与 file-reference(900) 之间、逐字取自 preset |
| `core:session` | **40**（+4） | plan/mode 折叠（最后生效、entered 粘性、坏数据忽略）、append 后可重放 |
| 其余 core | 不变 | 全绿（protocol / parity / legacy / llm / attachment / jobs / agents） |

### 4.2 真机（PLT120 / Android 15，账号通道 deepseek-flash）

| 截图 | 内容 |
|---|---|
| `docs/shots/plan-review.png` | 计划模式提问 → 模型提交 `exit_plan_mode` → 「Plan review」审核卡（完整 markdown + Approve / Keep planning） |
| `docs/shots/plan-approved.png` | 批准后继续执行：计划条消失，模型按计划跑 bash（turn 内后续 step） |
| `docs/shots/plan-mode-strip.png` | 空闲点切换：输入框上方计划条 + 「已进入计划模式」分隔条 |
| `docs/shots/plan-mode-exit.png` | 状态条「退出」→ 条消失（同态上一次请求已告知 → 不重复 narration） |

事件证据（`tools/session-dump.ps1`，会话 `90c1b040…`）：

```text
seq 1  plan/mode {"active":true}          # 新会话创建即计划模式（--ez dsh_plan_mode true）
seq 2  turn/start
seq 27 tool/call   exit_plan_mode
seq 28 tool/result Plan approved — plan mode exited; carry out the plan starting with your next step.
seq 29 step/end
seq 30 plan/mode {"active":false}          # 下一个 step 的 pre-step
seq 31 step/start
seq 53 turn/end completed

# 空闲切换（同一会话）
seq 55 plan/mode {"active":true}
seq 56 user/message source={kind:"plan-mode", form:"notice",
                            summary:"The user switched this session to plan mode."}
seq 57 plan/mode {"active":false}          # 上一次请求已告知非计划模式 → 不再 narration
```

### 4.3 门 2（工具表 28 → 30，5 个夹具重录）

| 夹具 | 行数（run-1 vs run-2） | 结果 |
|---|---|---|
| device-smoke | 205 vs 205 | PASS |
| device-read | 45 vs 45 | PASS |
| device-write | 83 vs 83 | PASS |
| device-edit | 310 vs 310 | PASS |
| device-read-error | 201 vs 201 | PASS |

回放请求体确认：`tools.length = 30`，含 `exit_plan_mode` / `load_workspace_dependencies`；
非计划模式请求的 `system` 与 M17 逐字节一致（无 `plan:policy`，`system` 3950 字符）。

**两个重录坑（写进流程）**：

1. `parity.ps1 record` **不清理夹具目录**：新一轮请求数变少时（本次 device-edit 5→4、
   device-read-error 3→2），旧的 `llm-0005.jsonl` / `llm-0003.jsonl` 会残留，verify 直接
   「请求数 ≠ 夹具数」。重录后必须 `ls` 一遍夹具目录、删掉超出本轮请求数的文件。
2. `ls -la` 类输出带文件 mtime：device-read-error 上一版录制时模型顺手跑了 `ls -la`，
   record 与 verify 之间工作区文件被别的场景重建，mtime 变了 → 第 3 个请求 sha 必不一致。
   这次重录模型只用了 read（2 个请求），且 **record → verify 背靠背跑**，之后才清工作区。

跑完清理工作区（只剩 `agent-shot.png` + `hello.md`）、还原设置：`approval=ask`、`base_url` 官方、
`debug_dump_sse=off`、`debug_parity_run=off`。

---

## 五、没做 / 下一步

1. **`/plan` 斜杠命令**：M21 已做（`/plan`、`/plan off`、`/plan <message>` + command/run/done 日志，见 docs/38）。
2. **子代理会话的计划模式**：子代理没有审核通道，`planMode = null`（`exit_plan_mode` 报不可用）；
   上游子代理会话可以有自己的 plan mode，但没有 UI 审批。
3. **`load_workspace_dependencies` 只是占位**：Android 没有 Python/Node payload；接 Operit 运行时后再返回真路径。
4. **计划审核只有「Approve/Keep planning」两态**：上游还有 ASK_CANCELLED（turn 结束）语义，
   我们的提问通道把跳过当 keep planning。
5. **`exit_plan_mode` 与审批策略**：固定为免工具审批（审核卡即审批）；上游的沙箱/审批策略是否也跳过未验证。
6. **工具 30/32**：还缺 `skill`（插件/技能系统）与 `workflow`（JS 编排，等 QuickJS）。

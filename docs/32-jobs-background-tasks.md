# 32 · 后台任务：`job_*` 三件套 + `bash run_in_background`（M15）

> 2026-11-08 04:0x｜基线 `ef03581`（M14 core:engine + 门 2 五场景）之后。
> 目标：把工具数从 20 推到 23——`job_output` / `job_list` / `job_kill`，
> 以及 `bash` 的后台执行（`run_in_background: true`）。
> 上游依据：`dsh-jobs`（类型/状态）、`dsh-jobs-local`（进程内注册表/环形缓冲/id 生成）、
> `dsh-tool-jobs`（三个工具与完成通知的逐字文案）、`dsh-tool-bash/lib/types/background.d.ts`
> （进程 → job outcome 的映射）。

---

## 一、一句话结果

| 项 | 之前 | 现在 |
|---|---|---|
| 工具数 | 20 / 32 | **23 / 32**（新增 job_output / job_list / job_kill） |
| `bash` | 只有前台（超时杀进程） | `run_in_background: true` → 立刻返回 `started background job <id>`，输出进环形缓冲 |
| 后台任务运行时 | 无 | 新模块 `core:jobs`：任务表 + 消费式读取 + 环形保留 + wait/kill + 完成通知队列 |
| 完成通知 | 无 | `source.kind:"tool-jobs"` 的 user 消息：忙时在 step 起点注入，idle 时唤醒一轮 |
| 单测 | — | `core:jobs` **12** 个 + `core:tools` 新增 7 个 + `core:engine` 新增 2 个 + `core:prompt` 1 个 |
| 门 2 | 5 个夹具（114 / 80 / 158 / 80 / 228 行） | 工具表变化 → **5 个夹具全部重录**，verify 全 PASS（见 §四.3） |
| 真机 | — | 两条闭环：kill 三步链 + 完成后唤醒（截图 `jobs-tools.png` / `jobs-notice.png`） |

---

## 二、上游语义（读源码确认）

### 2.1 任务与状态

`dsh-jobs-local` 的 job id 是 `<kind>-<16 位 uuid hex>`（`randomUUID().replaceAll("-","").slice(0,16)`）；
状态机 `running → stopping → completed / killed / failed`。`bash` 进程的结算映射
（`dsh-tool-bash/background.js`）：

- 正常退出：`completed`，detail = `exit code: N`（**非零也是 completed**，不是 failed）；
- 被取消：`killed`，detail = `signal: …`，拿不到信号时 `killed before exit`；
- 启动失败/异常：`failed`，detail = 错误文案。

模型侧永远看到 `[status: <status>[, <detail>]]`（`render.statusLine`）。

### 2.2 读取是消费式的

`job_output` 取「上次读取之后」的输出：stdout 按序拼，stderr 统一收尾在**一个** `[stderr]` 段
（`renderModelDelta`）。环形缓冲超限丢头，读取时附
`[some output was dropped from memory; full output: …]`；没有 spill 文件时是 `(unavailable)`。

`wait: true` 默认等 30 秒、上限 600 秒；超时**不杀任务**，返回当前 `[status: running]`。

### 2.3 完成通知（`dsh-tool-jobs`）

- 结算时若没有「活等待」收走（awaited）、不是模型自己 `job_kill` 的、不是 teardown，
  就给 owner 发一条 user 通知；
- 输出总字节 ≤ `inlineOutputBytes`（默认 2048）时内联：
  `background job <id> (kind: <label 首行>) finished [status: …]. Output:\n<输出>`
  空输出是 `. No new output.`；否则 `. Read its output with job_output.`；
- `source = {kind:"tool-jobs", form:"notice", summary}`，summary 上限 120 个 UTF-16 单元（超长前 119 + `…`）；
- 忙的 agent 在**下一个 step 起点**注入（turn 不能带着未投递的通知收口）；idle 的 agent 被
  **唤醒一轮**；
- 模型改用 `job_output` 收任务时，撤回 pending 通知，并把通知内联时消费掉的输出（`held`）补回去。

### 2.4 bash 后台

`run_in_background: true` 时 `bash` 只返回 `started background job <jobId>`；没有超时，
停止只能 `job_kill`。前台命令上游也会登记成 job 再 remove（模型看不到 id），
原生仍走原前台路径——请求体/文案不变，避免动已对拍的 5 个夹具行为。

---

## 三、实现

### 3.1 `core:jobs`（新模块，纯 JVM）

| 文件 | 内容 |
|---|---|
| `JobRegistry.kt` | `JobRegistry`（start/read/list/get/kill/wait/takeNotices/shutdown）、`ManagedJob`（环形输出 + 游标 + 等待者）、`JobRender`（上游同款文案） |

关键设计（写死理由）：

- **消费游标在 job 里**：模型只有一个读者，`read()` 返回后游标推进到 `total`；下一次读只拿增量。
  多读者（UI 跟随）走非消费快照，不在本里程碑。
- **环形缓冲按 UTF-8 字节**：上限 1 MiB，只丢整块；单块超限时保留 UTF-8 安全的尾部并标 `gapBefore`
  （不切多字节字符）。
- **`wait` 与通知的竞争用锁解决**：`beginWait()` 在 job 锁里「查终态 + 登记等待者」一步完成；
  结算时 `waiters > 0` 才记 `awaited`，awaited 的结算不发通知。
- **取消钩子在锁外调用**：`kill` 先把状态改 `stopping`、拿钩子，再调 `destroyForcibly`，
  避免生产者回调重入死锁。
- **通知由注册表构建**：结算时如果可内联，就先做一次消费读取并把输出存进 `JobNotice.heldText`；
  `job_output` 读到时把 held 补回正文（上游 `notice.held` 语义）。

### 3.2 `core:tools`

- `ToolContext` 新增 `jobs: JobRegistry?` 与 `jobOwner: () -> String?`（owner = 当前会话 id）；
- `BashTool`：`run_in_background` 分支把进程登记成 job，stdout/stderr 分两路泵进环形缓冲，
  `onCancel` 里 `destroyForcibly`；没挂 job 运行时则返回上游同款
  `background jobs unavailable: …`；
- 新 `jobs/JobTools.kt`：三个工具的模型侧渲染，逐字对齐上游（见 §二）。

### 3.3 `core:engine`

- `TurnInput` 新增 `notices: List<UserNotice>`（`wake = notices.isNotEmpty()`）：
  通知唤醒轮在 `turn/start` → `step/start` 之后把通知落成 user 消息，不再追加文本用户消息、不起标题；
- 每个 step 起点 `drainJobNotices()` 并落库（上游 inbox 在 step 起点 claim 的顺序）；
- 模型最后一条没有工具调用时：先看 inbox，有通知就把本 step 收口、开下一 step（同一 turn），
  没有才 `turn/end`；
- `EngineDeps.jobNotices: () -> List<UserNotice>`（默认空，单测可注入）。

### 3.4 App / UI

- `AppViewModel`：App 级 `JobRegistry`（owner=当前会话 id）；结算监听在 idle 时带通知唤醒一轮，
  `startTurn` 收尾再补一次（覆盖「turn/end 瞬间才结算」的竞争）；
- 聊天页：`sourceKind == "tool-jobs"` 渲染成「后台任务完成 / 已终止 / 失败 · kind」分隔条，
  正文不参与搜索（与 goal 轮/收尾同一套语汇）。

---

## 四、验证

### 4.1 单测

| 模块 | 数量 | 覆盖 |
|---|---|---|
| `core:jobs` | 12 | 消费式读取、stdout/stderr 顺序、环形丢头 lossy、kill→stopping→killed、超时等待不杀任务、awaited 抑制通知、内联/短通知文案、held 补回、owner 隔离、失败结算、teardown、result 只读一次 |
| `core:tools` | 60（+7） | 三工具渲染、wait 收终态、bash 真后台进程、无运行时错误、空 job_id/unknown job |
| `core:engine` | 9（+2） | 通知在 step 起点注入且 turn 继续、wake 轮不追加用户消息/不起标题、通知在 turn/start 之后 |
| `core:prompt` | —（+1） | 注册 job_* 后 `tool:jobs` 段自动拼进系统提示词 |

全仓 core 模块测试全绿（命令同 docs/28 §四）。

### 4.2 真机（PLT120 / Android 15）

| 截图 | 内容 |
|---|---|
| `docs/shots/jobs-tools.png` | bash 后台 `sleep 25; echo bg-done` → `job_list`（`running — …`）→ `job_output`（`(no new output)` / running）→ `job_kill`（已请求取消） |
| `docs/shots/jobs-notice.png` | 后台 `sleep 4; echo notice-ok` → 只回 `started`；4 秒后「后台任务完成 · bash」分隔条 + 唤醒一轮的回复「Job finished: notice-ok (exit code 0).」 |

事件证据（`tools/session-dump.ps1` 解包）：

```text
# 第二会话（wake 轮的结构）
seq 12  session/title
seq 13  turn/start
seq 14  step/start
seq 15  user/message source={kind:"tool-jobs",form:"notice",
                          summary:"bash sleep 4 && echo wake-ok [status: completed, exit code: 0]"}
seq 16  assistant/message     ← 模型被通知唤醒后回复
seq 17  step/end
seq 18  turn/end completed
```

第一版通知是「宿主先写 user/message、再 turn/start」，与上游「turn/start → step/start →
user/message」不一致；改成 `TurnInput.notices` 后事件顺序对齐（§三.3）。

### 4.3 门 2（工具表变化 → 5 个夹具全部重录）

新增 3 个工具后 `tools` 数组和系统提示词（`tool:jobs` 段）都变了，**5 个夹具的请求 sha 全部失效**，
按 docs/31 §四.2 的约定整套重录：

| 夹具 | 场景 | 覆盖工具 | 结果（run-1 vs run-2） |
|---|---|---|---|
| `device-smoke` | bash `ls -1` | bash | **139 vs 139 PASS** |
| `device-read` | `read hello.md` | read | **45 vs 45 PASS** |
| `device-write` | `write` 三行（`-PreRunClean parity-write.txt`） | write | **88 vs 88 PASS** |
| `device-edit` | `write` + `edit`（`-PreRunClean parity-edit.txt`） | write + edit | **116 vs 116 PASS** |
| `device-read-error` | `read not-exist.txt` | read（错误路径） | **223 vs 223 PASS** |

重录后逐个检查了夹具里的 tool_use：smoke=bash、read=read、write=write、edit=write+edit、
read-error=read，场景覆盖没有因为重录漂移。跑完清理设备工作区，恢复成
`agent-shot.png` + `hello.md` 两个文件（否则 smoke 的 `ls -1` 结果对不上）。

---

## 五、没做 / 已知限制

1. **kill 的 detail 没有信号名**：Java `Process` 拿不到 `SIGKILL` 字符串，统一写
   `killed before exit`（上游有信号时写 `signal: …`）。
2. **没有 spill 文件**：环形丢头时提示里的完整输出路径是 `(unavailable)`；
   上游会把完整流写进 spill 文件。
3. **`job_output wait` 的 user steer 中断没接**：原生没有「用户中途插话」通道，
   等待只会被结算/超时/停止打断，不会出现 `[status: running, user steered]`。
4. **任务表只在进程内**：App 被杀后后台任务和未投递通知一起消失（上游 `jobs-local` 也是进程内，
   但桌面端会话常驻，体感不同）。
5. **前台 bash 不登记 job**：模型看不到前台命令的 id，也就没有「超时后转后台（promote）」；
   上游 `promoteOnTimeout` 默认开，原生仍是超时杀进程（与既有夹具行为一致）。
6. **subagent 类 final-output 已具备能力但暂无生产者**：`JobControl.result()` /
   `job_output` 的 result 分支已实现并单测，等 `subagent` 工具接入。
7. ~~**没有任务面板**~~ → **已在 M20 补齐**（docs/37）：一级页「任务」+ 工作台摘要卡，
   全表（跨 owner）/ 只读输出预览 / 停止；`JobRegistry.listAll()` + `peek()` + `onChange`。

---

## 六、下一步

1. 工具批次 B：`subagent` / `subagent_fork` / `list_agents` / `send_message` / `interrupt_agent`
   （复用 `TurnEngine` + `core:jobs` 的 final-output/通知通道）。
2. ~~后台任务面板（任务页）：running 列表 + kill + 输出抽屉。~~ → **M20 已完成，见 docs/37。**
3. 门 2 补「失败重试」「取消中断」两个场景（需要假 LLM / 流中注入停止）。
4. 聊天尾巴：消息级「...」入口、导出 Markdown、搜索逐项点验。

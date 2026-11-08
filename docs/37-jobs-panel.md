# 37 · 后台任务面板（M20）

> 2026-11-08 05:2x｜基线 `23008bd`（M19 技能系统）之后。
> 目标：把 `bash run_in_background` + `job_*` 的后台任务从「只有模型能操作」补成用户可见的一级页：
> 全表列表（跨 owner）、只读输出预览、停止、工作台摘要卡；顺手修掉「用户停止后台 bash 会结算成 failed」。
> 本轮**不动工具表**（仍 31 个）、不动系统提示词与请求序列化 → 5 个门 2 夹具无需重录。

---

## 一、一句话结果

| 项 | 之前 | 现在 |
|---|---|---|
| 后台任务可见性 | 只有模型能用 `job_list` / `job_output`；用户只能在聊天工具卡看到 id | **一级页「任务」**（抽屉第 4 项）+ 工作台摘要卡 + 快速开始第 3 格 |
| 列表数据 | `list(owner)` 按会话过滤，面板拿不到全表 | `JobRegistry.listAll()`（跨 owner，带 owner/kind/status/耗时/输出量） |
| 输出预览 | 无；`read()` 会推进模型游标、撤回 held 通知 | `JobEntry.preview` **只读 peek**：不推进游标、不撤回通知；卡片 600 字符、详情 20K 字符 |
| 停止 | 只有模型能 `job_kill` | 面板「停止」→ 二次确认 → `kill(id, owner, …)`；2 秒内翻「已停止」 |
| 状态翻转 | 无 UI | `JobRegistry.onChange`（start / kill / settle）推 UI；运行中每秒轮询输出与耗时 |
| 取消语义 | 停止后台 bash 可能因 `destroyForcibly` 后管道读抛 `IOException` 结算成 `failed` | 取消态下把该异常按 EOF 处理 → `killed`；回归单测锁住 |
| 单测 | jobs 12 / tools 87 | **jobs 15（+3）/ tools 88（+1）**；全仓 core **223** 全绿 |
| 门 2 | 5 个夹具（31 工具） | 未重录；device-smoke **246 vs 246 PASS** |
| 真机 | — | 两个真实后台任务（运行中/已完成）→ 面板 → 停止 → 已停止 → 输出弹层（6 张截图） |

---

## 二、为什么任务表是「进程内、不造历史」

上游 `dsh-jobs-local` 的任务表是进程内注册表：`job_*` 的 owner 是 agent 会话，App 进程退出后任务与
未投递通知一起消失。原生沿用这个语义，面板**不伪造历史**：

- 空态直接写「任务表只在当前进程内 · 重启后清空」；
- 不把任务写进 v4 会话（会话事件只保留模型的 `tool/call` / `tool/result`，工具卡仍可回看 id）；
- 想补历史只能从会话 `tool/result` 反解 job id，但拿不到实时状态/输出，做了也是假数据——不做。

### 2.1 `core:jobs` 的三个新读接口

```kotlin
data class JobEntry(
    val job: JobSnapshot,
    val owner: String?,
    val preview: String,          // 可见输出末尾（不含 log 通道）
    val previewTruncated: Boolean,
    val unreadBytes: Long,        // total - 模型已消费游标
)

fun listAll(previewChars: Int = 600): List<JobEntry>          // 全表，按登记顺序
fun peek(jobId: String, owner: String?, previewChars: Int = 20_000): JobEntry
@Volatile var onChange: (() -> Unit)?                          // start / kill / settle，锁外回调
```

关键约束（写进 KDoc 与单测）：

1. **peek 不推进 `cursor`**：`job_output` 之后仍读到全量；内联完成通知的 `heldText` 也不会被面板吞掉；
2. 预览从环形缓冲**末尾**回取，最多复制 `previewChars` 个字符（默认 600，避免 1 MiB 任务每秒轮询反复搬运）；
3. `log` 通道（只给观察者的过程日志）不进预览，`stdout` / `stderr` 按原顺序拼接；
4. `onChange` 在锁外调用，且 **settle 时先 `settlementListener` 再 `onChange`**——与 `AgentRegistry.settle`
   同序：面板先看到终态，父代理再被完成通知唤醒。

### 2.2 停止语义（与模型侧一致）

`kill` 把任务置 `STOPPING` 并触发取消钩子；生产者以 `killed` 结算。用户从面板停止 = 用户已经知道结果，
`JobRegistry.settle` 里 `killRequested` 为真时**不再投递完成通知**——所以确认弹窗写的是
「不会再把完成通知投给模型；模型下次 `job_list` 仍能看到终态」。

---

## 三、界面

### 3.1 信息架构

抽屉顺序变成：**工作台 / 会话 / 子代理 / 任务 / 插件 / 设置**。工作台：

```
┌ 后台任务            全部 › ┐
│ [终端] 1 个任务运行中      │
│        共 2 个后台任务 · 进程内
│        ● ping -c 300 …  17 秒
└────────────────────────────┘
```

- 快速开始第 3 格从「终端」（死入口，点了回工作台）换成「任务」；
- 摘要卡运行中优先展示最多 2 条（label + 耗时），没有运行中时显示「没有运行中的任务」；
- 工作台滚动后状态栏区域盖一条与页面同色的 scrim（edge-to-edge 下内容不再压住时间/电量）。

### 3.2 任务面板

```
顶栏：☰  任务                          ⟳
      2 个任务 · 运行中 1
状态卡：1 运行中   2 任务总数   2.6 KB 累计输出
       任务在 App 进程内执行；预览只读，不会吃掉模型未读的输出。
● 运行中
  [终端] ping -c 300 127.0.0.1            ● 运行中
         bash · bash-adfaa34 · 会话 · 依次做两…
         ┌ mini 终端（末尾 4 行）──────────┐
         │ ttl=64 time=0.461 ms            │
         └─────────────────────────────────┘
         05:18:21 开始  35 秒      [停止] [查看输出]
● 已结束
  [终端] echo M20-ALPHA; …                ● 已完成
         bash · bash-5125bf7 · 会话 · 依次做两…
         M20-ALPHA / M20-BETA / M20-GAMMA
         05:18:21 开始  2 秒              [查看输出]
```

- 段顺序：运行中（含停止中）→ 已结束；排序：RUNNING → STOPPING → 最近开始；
- 徽标：运行中（蓝，脉冲）/ 停止中（琥珀）/ 已完成（绿）/ 已停止（琥珀）/ 失败（红）；
- 卡片正文优先 mini 终端预览（深色块、等宽 11sp、最多 4 行），没有输出时显示「等待输出…」；
- 详情弹层：20K 字符、可滚动、可选中复制；显示耗时/输出量/模型未读量，并明确「只读预览」；
- 运行中每秒轮询 `refreshJobs()`（输出/耗时）；状态翻转本身由 `onChange` 立即推，不靠轮询。

**拍板理由**：用户要「任务/工具/控制台=重（Operit）」。Operit 的任务管理是一级列表页 + 状态摘要卡，
不是塞进聊天；M15 的 `job_*` 已经能把任务跑起来，缺的是用户的观察/停止面。本轮只补读接口与 UI，
不改工具 schema，因此是最划算的一刀（也不用重录夹具）。

---

## 四、顺带修复：后台 bash 取消结算成 failed（契约 5）

真机停止 `ping -c 300` 时抓到：任务卡片显示「失败」，而不是「已停止」。原因在 `BashTool` 的后台生产者：

```text
kill → onCancel → process.destroyForcibly()
     → 读 stdout/stderr 的 pump 协程抛 IOException("Stream closed")
     → coroutineScope 抛出 → job 以 FAILED 结算（detail=Stream closed）
```

修复：`pump` 捕获 `IOException`，**若 `control.isCancelled()` 已为真就按 EOF 处理**，让
`process.waitFor()` 返回后走 `JobOutcome(KILLED, "killed before exit")`。新增回归单测
`bashRunInBackgroundKillSettlesKilledNotFailed`（Windows 用 cmd 内建循环、Unix 用 shell `while`，
避免子进程持有管道导致 pump 等不到 EOF）。

---

## 五、验证

### 5.1 单测（全仓 core 223 全绿）

| 模块 | 数量 | 新增覆盖 |
|---|---|---|
| `core:jobs` | **15**（+3） | `listAll` 跨 owner + 预览不消费游标；`peek` 截断标记 + `unreadBytes` + 读后仍全量；`onChange` 在 start/kill/settle 各触发 |
| `core:tools` | **88**（+1） | 后台 bash 取消必须 `killed`，不能因管道 IOException 变 `failed` |
| 其余 core | 不变 | agents 15 / engine 16 / prompt 7 / session 40 / skill 21 / protocol 3 / parity 8 / legacy 6 / llm 4 |

### 5.2 门 2（无重录）

工具表仍 31、系统提示词与请求序列化未动；device-smoke verify：

```text
run-1 vs run-2: 246 vs 246  PASS
```

跑完还原 `approval=ask`、`base_url=官方`、`debug_dump_sse=off`、`debug_parity_run=off`；
workspace 只剩 `agent-shot.png` + `hello.md`，`files/skills` 为空（已核对 shared_prefs 与目录）。

### 5.3 真机（PLT120 / Android 15，账号通道 deepseek-flash）

证据会话 `3665663f-5944-44ba-b5d6-337b5a644c3d`（两个 job 的请求/工具结果）：

```text
seq 3  user/message  “依次做两件事…”（run_in_background 两次）
seq 5  tool/call     bash {"command":"ping -c 300 127.0.0.1","run_in_background":true}
seq 6  tool/call     bash {"command":"echo M20-ALPHA; …; sleep 2","run_in_background":true}
seq 7  tool/result   started background job bash-adfaa34f368e4e66
seq 8  tool/result   started background job bash-5125bf7e66014836
```

| 截图 | 内容 |
|---|---|
| `docs/shots/jobs-workbench.png` | 工作台「后台任务」摘要卡：1 个运行中、共 2 个、`ping` 17 秒；状态栏 scrim 生效 |
| `docs/shots/jobs-panel.png` | 任务面板：1 运行中 + 1 已完成；mini 终端预览（ping 实时行 / 三行标记）、耗时、查看输出 |
| `docs/shots/jobs-stop-confirm.png` | 「停止任务？」确认：任务名 + 取消语义说明 + 取消/停止 |
| `docs/shots/jobs-stopped.png` | 停止后：运行中 0，`ping` 卡翻「已停止」，已完成卡仍在 |
| `docs/shots/jobs-output.png` | 已完成任务的输出弹层：完整命令、三行输出、2 秒 / 29 B、只读说明 |
| `docs/shots/jobs-empty.png` | 空态：0 任务 + `--es dsh_open_screen jobs` 直达；明确「任务表只在当前进程内 · 重启后清空」 |

停止后 `JobRegistry` 结算为 `KILLED`（detail `killed before exit`），没有向模型投递完成通知；
`job_list` 仍能看到终态（与 M15 的 `job_kill` 语义一致）。

---

## 六、没做 / 下一步

1. **任务历史不落盘**：进程退出即清空；要历史只能从会话 `tool/result` 反解（无实时状态/输出）。
2. **不能从面板创建任务**：`bash` 仍由模型发起；面板只观察/停止（避免用户绕过会话 owner）。
3. **不做 owner/kind 过滤与搜索**：当前任务量级小，运行中优先 + 已结束按时间已够；任务多了再加。
4. **工具卡不能跳任务页**：聊天里的 `bash run_in_background` 工具卡只显示 id；后续可点 id 打开面板并高亮。
5. **停止只杀当前进程**：`destroyForcibly` 不递归杀子进程树；Android 的 `sh -c` 通常会 exec 目标，
   但显式 `cmd &` 的孙进程仍可能存活（记在 docs/32 §五）。
6. **工具 31/32**：仍缺 `workflow`（QuickJS）。

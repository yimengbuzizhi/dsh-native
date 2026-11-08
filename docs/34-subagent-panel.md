# 34 · 子代理面板 + 中断语义修复（M17）

> 2026-11-08 04:4x｜基线 `816dc96`（M16 子代理五件套）之后。
> 目标：补上 docs/33 §五.2 的「没有子代理面板」——running/inactive 列表、打开子会话（只读回放）、
> 停止、看结果；顺手修掉两个中断语义缺口（契约 5）：「系统返回键退出 App 会连带杀死正在跑的子代理」
> 和「前台 `bash` 长命令不响应取消（stop / interrupt 要等命令自己跑完）」。

---

## 一、一句话结果

| 项 | 之前 | 现在 |
|---|---|---|
| 子代理可见性 | 只有模型能用 `list_agents`；子会话被主列表过滤，用户看不到 | **一级页面「子代理」**（抽屉第 3 项）+ 工作台摘要卡 |
| 面板数据 | 无 | `AgentRegistry.all()`（运行态/终态）+ 会话仓库里 `parentSession != null` 的子会话**合并**；重启后历史也在 |
| 打开子会话 | 无 | 只读回放（`readOnlyReason="subagent"`，底部说明与旧版导入副本区分） |
| 停止 | 只有模型能 `interrupt_agent` | 面板「停止」→ 确认弹窗 → 注册表 cancel + 续轮 cancel，**2 秒内**翻「已停止」 |
| 系统返回键 | 在聊天页按返回直接退出 Activity（ViewModel 清理 → 正在跑的子代理/任务被杀） | 聊天 → 关聊天；二级页 → 回设置；抽屉 → 关抽屉；非工作台 → 回工作台 |
| 取消语义 | `ToolRegistry` 把 `CancellationException` 当工具错误吞掉；前台 bash 阻塞读不响应取消 | 取消一路透传到 `TurnEngine` 的 aborted 收口；bash 取消时 `destroyForcibly` |
| 单测 | `core:agents` 12 / `core:tools` 70 | **`core:agents` 15（+3）/ `core:tools` 73（+3）** |
| 门 2 | 5 个夹具 | **5 个夹具全部 verify PASS**（工具表/提示词未变，无需重录） |
| 真机 | — | 工作台摘要、面板 running/inactive、停止→已停止→父代理唤醒收尾、子会话只读回放（5 张截图，§五） |

---

## 二、数据模型：为什么是「内存 + 磁盘」合并

子代理运行在进程内（`core:agents` 的 `AgentRegistry`），App 被杀后运行态消失，但**子会话文件还在**。
只读内存 → 重启后面板空白，用户永远看不到历史子代理；只读磁盘 → 运行中的状态、终态（aborted/error）、
用户可读的 label（`description`）都没有。所以两边各取一半：

| 字段 | 来源 | 说明 |
|---|---|---|
| id / parentId / label / depth / cwd | `AgentRecord`（有则覆盖） | 运行中能拿到模型写的 description |
| running / 终态 | `AgentRecord.status` / `terminal` | 只有进程内有记录时才知道 aborted / error |
| 标题 / 预览 / 消息数 / 更新时间 | `SessionSummary` | 重启后靠它显示历史 |
| 终态（重启后） | `SessionSummary.lastStopReason` | 新字段：解出最后一条 `turn/end` 的 `reason.kind` |

`mergeSubagents()`（app 模块，`chat/SubagentUi.kt`）负责合并：以子会话为骨架、进程内记录覆盖运行态字段；
进程内还没有落盘文件的记录也显示（启动瞬间）。排序 = **运行中优先，其次最近活动倒序**。

### 2.1 core 侧的三个新读接口（`core/agents`）

```kotlin
fun all(): List<AgentRecord>          // 本次进程内启动过的全部代理（含已结算，创建顺序）
var onChange: (() -> Unit)?           // 表变了（start / settle）：宿主刷面板，锁外调用
@Volatile var terminal: AgentTerminal? // 结算后写入（completed/aborted/error + 收尾话）
```

`settle()` 改成先 `onChange` 再 `onNotice`：面板先看到终态，再让父代理被结算通知唤醒——
否则父代理可能立刻开下一轮，面板数据反而落后一拍。结算仍然只做一次（`status` 已是 INACTIVE 直接返回）。

### 2.2 `core:session` / `core:model`

- `SessionSummary` 新增 `delegationDepth` / `origin` / `isSeeded` / `lastStopReason`，`summarize()` 从 header
  与最后一条 `turn/end` 填充（`SessionCodec.stopReasonOf`）。
- 面板不用为了「深度 1」「fork」这些标签再单独解一遍 header。

---

## 三、界面

### 3.1 工作台（入口）

- 快速开始第 2 格换成「子代理」（原来是无实际动作的「工作区」，顺手修掉死入口）。
- 「子代理」分区：白卡显示 `N 个运行中` + `共 M 个子会话 · 深度上限 1 层`；运行中最多列 2 条（id + label），
  「全部 ›」进面板。空态显示「没有运行中的子代理」。

### 3.2 子代理面板（一级页面）

```
顶栏：☰   子代理                       ⟳
          5 个子会话 · 运行中 1
状态卡：  1 运行中   4 已结束   1 层 深度上限
          子代理在独立 v4 会话里执行，结果以通知回到主代理。
● 运行中
  [人像] Run sleep 240                 ● 运行中
         5f95575e · 深度 1 · 2 条消息
         使用 bash 工具执行命令 sleep 180…
         刚刚                    [停止] [查看会话]
● 已结束
  [人像] 任务：在你的工作目录（…       ● 已完成
         a3db78fe · 深度 1 · 6 条消息
         done
         10 分钟前                       [查看会话]
```

- 终态徽标：运行中（蓝，脉冲）/ 已完成（绿）/ 已停止（琥珀）/ 失败·已拒绝（红）/ 超出长度（琥珀）/
  已结束（灰，只有文件、没有 turn/end）。
- 卡片正文优先显示**收尾话**（`terminal.closingTexts`），否则会话最后一条正文（`preview`）。
- 「停止」只在 `running && live` 时出现，二次确认。
- 有运行中代理时面板每 3 秒重合并一次（消息数/预览会变）；状态翻转本身走 `onChange`，不靠轮询。
- 冷启动首次要解压 100+ 会话（约 5 秒）：`subagentsLoading` 期间显示「正在读取子会话…」，
  不再先闪一下「还没有子代理」。

**拍板理由**：用户要「复制 Operit」+「任务/工具/控制台=重（Operit）」。Operit 的任务管理是一级列表页，
不是塞进会话列表；子会话混进主会话会让人误以为是自己开的对话（docs/33 §五.2）。所以做成独立一级页 +
工作台摘要入口，抽屉顺序：工作台 / 会话 / 子代理 / 插件 / 设置。

### 3.3 子会话只读回放

`openSubagentSession(id)`：读文件 → 投影成 `ChatSessionState`，`readOnly=true`、`readOnlyReason="subagent"`。
**不接管成当前会话**（不写 `handle`、不投提醒、不唤醒通知）：子会话的续轮只能由主代理 `send_message` 触发，
用户直接在里面发消息会绕开父子授权（契约 3/5）。聊天页底部说明：

> 🔒 子代理会话 · 只读回放
> 主代理委派的子会话，结果已通过通知交回；这里只回看执行过程。

顶栏副标题也从 `<助手> · <模型>` 换成 `子代理会话 · <模型>`。

---

## 四、中断语义修复（契约 5）

### 4.1 系统返回键（`BackHandler`）

之前聊天页是「全屏页 + 早退 return」，没有 `BackHandler`：按系统返回直接 finish Activity，
`AndroidViewModel.onCleared()` 会取消 `agentScope` / `jobScope` ——**正在跑的子代理和后台任务被连坐**。
M17 在 `DshAppContent` 统一接管：

```kotlin
BackHandler(enabled = chat != null || subScreen != null || drawerState.isOpen || screen != Workbench) {
    when { chat != null -> vm.closeChat(); subScreen != null -> ...; drawer open -> close; else -> screenName = Workbench }
}
```

### 4.2 `ToolRegistry` 不能吞掉取消

`CancellationException` 是 `IllegalStateException` 的子类，原来 `catch (e: Exception)` 会把它变成
`ToolResult.err("CancellationException: …")`——stop/interrupt 只写一条工具错误，然后**继续跑下一步**，
要到下一次挂起（下一个 LLM 请求）才真正停下。现在 `execute()` 显式先 `catch (CancellationException) { throw e }`。

### 4.3 前台 `bash` 取消时杀进程

原实现 `withContext(Dispatchers.IO) { process.inputStream.readBytes() }`：阻塞读不响应协程取消，
`bash sleep 240` 会拖到 240 秒后才取消（子代理面板上一直显示「运行中」）。
现在用 `suspendCancellableCoroutine` + 读线程，`invokeOnCancellation` 里 `destroyForcibly()`：
读端立刻 EOF → 抛 `CancellationException` → 引擎写 `step/end` + `turn/end aborted`。

真机证据（子代理 34282106 执行 `sleep 240`，面板点「停止」）：

```text
# 子会话 34282106（--data-data-… slug；header.parentSession=f522f194, origin=subagent, delegationDepth=1）
turn/start 1 → step/start 2 → user/message 3（任务 + 返回指导两块）
→ tool/call 5（bash sleep 240）→ step/end 6 → turn/end 7 {"kind":"aborted"}
（没有 tool/result：取消发生在工具执行中，与上游中止语义一致）

# 父会话 f522f194（打开会话后补投通知 → 新 turn）
seq 13 turn/start → 14 step/start → 15 user/message
  source={kind:"subagent-settled", summary:"Background subagent 34282106-… was stopped before it finished."}
→ 18 turn/end
```

---

## 五、验证

### 5.1 单测

| 模块 | 数量 | 新增覆盖 |
|---|---|---|
| `core:agents` | **15**（+3） | `all()` 含已结算记录且保持创建顺序；`onChange` 在 start 触发一次、settle 只触发一次；`terminal` 结算后可见 |
| `core:tools` | **73**（+3） | 取消从 `ToolRegistry` 透传（不被当工具错误）；普通异常仍是工具错误；未知工具 |
| 其余 core | 不变 | engine 11 / prompt 6 / session / protocol / parity / legacy / llm / attachment / jobs 全绿 |

### 5.2 门 2（无重录）

工具表 28 个、系统提示词、请求序列化都没动 → 5 个夹具直接 verify：

| 夹具 | 结果 |
|---|---|
| device-smoke | **71 vs 71 PASS** |
| device-read | **126 vs 126 PASS** |
| device-write | **75 vs 75 PASS** |
| device-edit | **245 vs 245 PASS** |
| device-read-error | **193 vs 193 PASS** |

跑完清理工作区（只剩 `agent-shot.png` + `hello.md`）、设置还原：`approval=ask`、`base_url` 官方、
`debug_dump_sse=off`、`debug_parity_run=off`。

### 5.3 真机（PLT120 / Android 15，账号通道 deepseek-flash）

| 截图 | 内容 |
|---|---|
| `docs/shots/subagent-workbench.png` | 工作台「子代理」摘要卡：1 个运行中（Run sleep 180）+ id；快速开始第 2 格是子代理 |
| `docs/shots/subagent-panel.png` | 面板：状态摘要 1/4/1 层；运行中卡（停止 + 查看会话）；已结束卡带已完成/已停止徽标与结果预览 |
| `docs/shots/subagent-panel-stopped.png` | 点「停止」→ 确认 → 2 秒内翻 `已停止`，从运行中段移到已结束段 |
| `docs/shots/subagent-session.png` | 打开已完成的子会话：只读回放（任务 + 返回指导两块、bash/send_message 工具卡、done）、底部只读说明 |
| `docs/shots/subagent-settled-wake.png` | 父会话里「子代理已停止」分隔条 + 模型收尾（「在完成前被中止了，没有留下结束消息」） |

---

## 六、没做 / 下一步

1. **只读是快照**：运行中的子会话点进「查看会话」是打开瞬间的画面，不实时跟着流；
   需要实时的话得把 `ChildRuntime.state` 直接挂到 UI（要处理「用户发消息」路径，暂不做）。
2. **不能从面板给子代理发消息**：续轮仍只能主代理 `send_message`（父子授权语义），面板只观察/停止。
3. ~~**后台任务面板仍缺**~~ → **已在 M20 补齐**（docs/37）：一级页「任务」+ 工作台摘要卡，
   全表（跨 owner）/ 只读预览 / 停止；`job_*` 的 owner 直接显示为会话或子代理标题。
4. **子代理消息卡不能点开子会话**：聊天里的 `subagent` 工具卡与 `agent-message` 卡还没接面板跳转。
5. **冷启动首次列表慢**（解压 100+ 会话约 5 秒）：`SessionRepository` 的「只读首帧」优化还没做（docs/17 §五）。
6. 子代理会话的标题是引擎 fallback（任务前 18 字）；面板优先用 `AgentRecord.label`，但重启后只剩标题。
7. `bash` 之外的阻塞工具（`web_fetch` 等）取消仍要等超时；有 60 秒上限，暂未逐个加取消钩子。

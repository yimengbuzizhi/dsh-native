# 39 · 聊天尾巴：助手消息操作 + 会话导出 + 工具卡跳转（M22）

> 2026-11-07 05:5x｜基线 `a3bed45`（M21 输入触发）之后。
> 目标：把 docs/30 §四 留下的三件用户可见的尾巴补上——**助手消息也能复制/引用**、
> **会话导出 Markdown**、**工具卡跳转（子代理回放 / 任务面板）**；顺手做搜索跳转的逐项点验。
> 本轮**不动工具表（仍 31 个）**、不动系统提示词与请求序列化 → 5 个门 2 夹具无需重录。

---

## 一、一句话结果

| 项 | 之前 | 现在 |
|---|---|---|
| 助手消息操作 | 只有系统文本选择，整条复制/引用要手动选全 | 每条**有正文**的助手消息尾部有「⋯」：复制整条 / 引用（不抢长按手势；工具调用-only 的消息不显示，没内容可操作） |
| 会话导出 | 无 | 顶栏「⋯ → 导出 Markdown」：写入**下载/DSH/DSH-<标题>-<时间>.md**，弹窗显示真实路径 + 「打开」 |
| 导出内容 | — | 只含聊天里看得见的内容；思考/工具结果折叠在 `<details>` 里；goal/任务/技能等内部提示词只留一行来源注记 |
| 工具卡 → 任务 | 只能看到 job id | `bash run_in_background` 卡片下出现「查看后台任务 ›」→ 一级页「任务」 |
| 工具卡 → 子代理 | 只能看到子会话 id | `subagent` / `subagent_fork` 卡片下出现「回放子代理会话 ›」→ 只读子会话 |
| 子会话返回 | 返回键直接回工作台 | 从工具卡进的子会话**压栈**：返回键回父会话（列表/面板进的子会话仍直接回工作台） |
| 子会话已被清理 | 会跳进一个空「新会话」 | 留在当前页 + 瞬态提示「子会话不存在：xxxxxxxx」 |
| 搜索 | M9 已做，未逐项点验 | 本机 `sleep` 3 处命中，1/3 → 3/3 逐次跳转、命中气泡与关键词高亮（截图） |
| 单测 | core 247（不含 attachment） | **core 255**：session 42 → **50**（+8，`SessionMarkdownTest`），其余不变 |
| 门 2 | device-smoke 246 | **246 vs 246 PASS**（UI-only，未重录） |
| 真机 | — | 助手菜单/引用、导出弹窗、导出文件、任务跳转、子代理回放 + 返回、搜索 3 处（10 张截图） |

---

## 二、上游对照与拍板

上游聊天页的消息操作在 `dsh-client-ui-chat` 的 `MessageIconActions` 里：**复制 + branch（在该消息处分叉会话）**，
没有「重新生成」；桌面端也没有「导出 Markdown」（只有 `dsh-session-log-export` 的日志导出）。

- **助手「⋯」= 复制 / 引用**：docs/30 已经确认助手正文长按会与系统选择工具条叠加，所以入口改成消息尾部的
  小图标按钮，正文继续保留系统选择（选片段复制）。
- **不做「重新生成」**：v4 是 append-only（`docs/port/01`），没有删除/截断事件；上游的对应能力是 branch
  （在消息处分叉新会话），不是原地重生成。硬做只能「再发一遍」，会把同一问题写进历史两次——
  留到会话层的 branch/fork 一起做。
- **导出格式自定**：给人看的 Markdown，不冒充会话归档；内部上下文（goal 轮、任务通知、技能目录/指令）
  只写一行注记，不把提示词倒进文件。
- **工具卡跳转只在非流式时出现**：切页/开子会话会 `cancelStream()`（M17 语义：一次只跑一个聊天页 turn），
  轮次中给入口等于让用户误触中断。

---

## 三、实现

### 3.1 `core:session`：`SessionMarkdown`（纯 JVM）

| 函数 | 说明 |
|---|---|
| `render(title, model, sessionId, exportedAt, messages)` | UI 消息序列 → Markdown；连续 assistant 消息合成一个「助手」段（与界面一致） |
| `fileName(title, at)` | `DSH-<标题>-<yyyyMMdd-HHmmss>.md`；路径分隔符/控制字符换 `-`、空白压 `-`、最长 40 字、空标题退化为「会话」 |

渲染规则：

- 用户消息（`source.kind == null/user`）→ `## 用户 · 时间` + 正文；
- 内部来源（goal / tool-goal / tool-jobs / subagent-settled / agent-message / plan-mode /
  skill-catalog / skill-invocation）→ 只有一行 `> 〔来源注记〕`，**不导出正文**；
- assistant：思考 → `<details><summary>思考过程 · N 字</summary>`；工具调用 → `**工具 · name**` +
  `json` 参数 + `<details>工具结果</details>`（按 `toolCallId` 配对）；正文按原样；
- 单个工具结果最多 4000 字符，超出追加「已截断，原长 N 字符」。

### 3.2 `app`：`SessionExport`（Android 落盘）

- Android 10+：`MediaStore.Downloads` + `RELATIVE_PATH=Download/DSH` + `IS_PENDING`，**不申请存储权限**；
- Android 9-：退化成 App 专属外部目录（`Android/data/<pkg>/files/Download/DSH`），同样不申请权限；
- 返回 `ExportedSession(uri, displayPath, error)`；UI 只用 `displayPath` 展示、用 `uri` 走
  `ACTION_VIEW`（`FLAG_GRANT_READ_URI_PERMISSION`），没有 Markdown 阅读器时 Toast。

### 3.3 聊天页（`ChatScreen.kt`）

- `AssistantTurn` 新增 `linksEnabled / onAction / onOpenSubagent / onOpenJobs`：
  - 正文非空时在时间戳后渲染 24dp 的「⋯」，点开复用 `MessageActionDialog`（复制 / 引用；用户消息仍有长按菜单）；
  - 工具卡下解析结果首行：`started subagent <id>` → 「回放子代理会话」；
    `started background job <id>` → 「查看后台任务」；
- 顶栏「⋯」菜单新增「导出 Markdown」（无消息时禁用）：`Dispatchers.Default` 渲染、`Dispatchers.IO` 写盘；
- 导出对话框：路径 + 「打开 / 好」，并注明「只含聊天里看得见的内容」。

### 3.4 返回栈（`AppViewModel.kt`）

```text
openSubagentSession(id)
  cancelStream()
  previous = chat
  load(id)
    失败：previous 非空 → 留在当前页 + commandNotice「子会话不存在：xxxxxxxx」
          否则 → 原来的失败页（返回键回工作台）
    成功：projectSession(readOnly=subagent) → previous 压栈 → chat = 子会话
closeChat()
  chat = chatBackStack.removeLastOrNull()   // 有父会话回父会话，栈空才真的关聊天
newChat() / deleteSession(当前会话) → 清栈
```

**拍板理由**：子代理回放是「从工具卡钻进去看过程」，返回应回父会话；而分享/复制链接式的入口
（子代理面板）本来就没有父聊天页，保持直接回工作台。

### 3.5 调试通道

本轮没有新增调试参数；沿用 `--es dsh_open_session <id>` 打开证据会话。

---

## 四、验证

### 4.1 单测 / 构建

| 模块 | 数量 | 新增覆盖 |
|---|---|---|
| `core:session` | **50**（42+8） | 页眉/角色段、连续 assistant 合并、思考折叠、工具调用/结果配对、内部来源不导出正文、4000 字截断、文件名清洗/回退、空会话 |
| 其余 core | 不变 | input 15 / skill 25 / tools 88 / engine 19 / session 50 / jobs 15 / agents 15 / prompt 7 / parity 8 / legacy 6 / protocol 3 / llm 4 = **255** |
| `:app:assembleDebug` | — | 通过（Gradle 8.9 / JDK 21） |

### 4.2 门 2（未重录）

UI-only 改动，工具表 31、system 3950 字、请求序列化都没动。device-smoke verify：

```text
run-1 vs run-2: 246 vs 246  PASS
```

### 4.3 真机（PLT120 / Android 15，账号通道 deepseek-flash）

证据会话（都是已有真机会话，没有新造）：

| 会话 | 用途 |
|---|---|
| `3665663f-5944-44ba-b5d6-337b5a644c3d` | M20 两个后台 bash → 工具卡「查看后台任务」、导出、助手「⋯」/引用 |
| `f522f194-f073-4fa9-96ec-48ee3cd18558` | M17 子代理 → 工具卡「回放子代理会话」→ 只读子会话 → 返回父会话 |
| 同上 | 会话内搜索 `sleep`（3 处）逐项跳转 |

| 截图 | 内容 |
|---|---|
| `docs/shots/m22-more-menu.png` | 顶栏「⋯」：目标 / 提醒 / **导出 Markdown** / 计划模式 |
| `docs/shots/m22-export.png` | 导出成功弹窗：`下载/DSH/DSH-依次做两件事…-20261107-055208.md` + 打开/好 |
| `docs/shots/m22-assistant-menu.png` | 助手正文尾部「⋯」→ 复制 / 引用（工具调用-only 的消息没有入口） |
| `docs/shots/m22-assistant-quote.png` | 引用后输入框出现 `> 已完成，退出码为 0: …`，键盘自动弹起 |
| `docs/shots/m22-jobs-jump.png` | 点工具卡「查看后台任务」→ 一级页「任务」 |
| `docs/shots/m22-subagent-link.png` | M17 父会话：`subagent` 卡片 + 「回放子代理会话」 |
| `docs/shots/m22-subagent-replay.png` | 子会话只读回放（标题「会话」、副标题「子代理会话」、底部锁形只读条） |
| `docs/shots/m22-subagent-back.png` | 返回键回到父会话（滚动位置保留，子代理卡仍在） |
| `docs/shots/m22-search.png` | 搜索 `sleep`：1/3，命中气泡高亮 |
| `docs/shots/m22-search-jump.png` | 2 次「下一处」→ 3/3，命中消息滚到可视区、关键词高亮 |

导出文件核对（拉回本地 `parity/runs/m22-export.md`，共 1680 字节，节选）：

~~~~text
# 依次做两件事，不要做别的：1) 用

> 模型 deepseek-flash · 会话 3665663f · 导出 2026-11-07 05:52 · 5 条消息

## 用户 · 2026-11-07 05:18
…
## 助手 · 2026-11-07 05:18

<details><summary>思考过程 · 134 字</summary>
…
</details>

**工具 · bash**

```json
{"description": "Ping localhost 300 times in background", "command": "ping -c 300 127.0.0.1", "run_in_background": true}
```

<details><summary>工具结果</summary>

```text
started background job bash-adfaa34f368e4e66
```
~~~~

收尾已还原：`approval=ask`、`base_url=官方`、`debug_dump_sse=off`、`debug_parity_run=off`；
workspace 只剩 `agent-shot.png` + `hello.md`，`files/skills` 为空；导出的测试 md 与设备截图已删。

---

## 五、没做 / 下一步

1. **branch（在消息处分叉会话）**：上游 `MessageIconActions.onBranch`；需要会话层「从 seq 复制前缀建新会话」，
   与「重新生成」一起做才有意义。
2. **结构化引用**：现在引用仍是 `>` 文本前缀，不记录引用的是哪条消息；上游也没有结构化 quote，暂不做。
3. **导出附件/图片**：只导出文字与工具摘要；附件（图片）不内嵌、不复制。
4. **工具卡跳转的深链**：任务页只打开列表，不定位到具体 job；子代理只读回放不实时刷新（沿用 M17 语义）。
5. **工具 31/32**：仍缺 `workflow`（QuickJS）。

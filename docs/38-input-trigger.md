# 38 · 输入触发 / 斜杠命令（M21）

> 2026-11-07 05:4x｜基线 `19da9e4`（M20 任务面板）之后。
> 目标：把「输入框打字触发」补齐——`/` 命令菜单、`/名字` 用户直接调用技能、`@文件` 引用，
> 并把命令生命周期（`command/run` / `command/done`）与技能注入（`source.kind:"skill-invocation"`）
> 对齐上游；**不动工具表（仍 31 个）**、不动系统提示词 → 5 个门 2 夹具无需重录。
> 上游依据：`dsh-commands`（命令语法/生命周期）、`dsh-plan-mode`（`/plan` 四态文案）、
> `dsh-tool-skill`（`SKILL_GESTURE` + pre-step 注入）、`dsh-file-reference`（`@path` / `@"…"`）、
> `dsh-client-ui-input-trigger` / `ui-commands` / `ui-skill` / `ui-reference`（菜单与裁决）。

---

## 一、一句话结果

| 项 | 之前 | 现在 |
|---|---|---|
| 输入框 `/` | 只是普通字符 | 触发面板：**命令**（`/plan`）+ **技能**（目录里 `userInvocable` 的条目）；前缀优先、子序列过滤 |
| 输入框 `@` | 只是普通字符 | 触发面板：工作区文件/目录（有界索引、模糊排序、目录优先、最多 20 条）；选中插入 `@path` / `@dir/` / `@"path with spaces"` |
| `/plan` | 只有「⋮ → 计划模式」菜单开关 | 斜杠命令四态：`committed / queued / cancelled / noop`，文案逐字取自上游；`/plan <message>` 选模式后把消息作为普通用户消息提交 |
| `/plan off` | 只能通过计划条「退出」 | 命令与计划条两条路径都可用；轮次内 = 下一步生效 |
| `/名字` 技能 | 只能由模型调 `skill` 工具 | 用户直接输入 `/name`：消息原文进模型，pre-step 注入 `<skill_content>`（`source.kind:"skill-invocation"`），UI 显示可展开「技能指令 · 名字」卡 |
| 未知 `/xxx` | 会被当成普通提示词发出去 | 拒绝（与上游适配器一致），输入框保留草稿 + 结果条提示 |
| 命令日志 | 无 | `command/run` → 命令副作用 → `command/done`（v4 事件；不进模型历史、不进消息投影） |
| 单测 | core 223（不含 attachment） | **core 247**：input 15（新）/ skill 25（+4）/ engine 19（+3）/ session 42（+2），其余不变 |
| 门 2 | device-smoke 246 | **246 vs 246 PASS**（未重录；工具表 31、system 3950 字不变） |
| 真机 | — | `/plan`、`/plan off`、`/plan <msg>`、`/名字`、`/` 菜单、`@` 菜单、未知命令拒绝（10 张截图） |

---

## 二、上游语义（读源码确认）

### 2.1 命令行语法（`dsh-commands.parseCommand`）

```text
^/([a-z][a-z0-9_-]*)(?=$|[\t\n\r ])
```

- 第 0 字节必须是 `/`；名称 `a-z` 开头，可含 `a-z0-9_-`；
- 名称之后必须是**行尾或一个空白**；之后的每个字节（**包括分隔空白**）都是 `rawInput`；
- 解析不出命令的行不会被当作命令；UI 适配器会拒绝未知 `/xxx`，不会降级成提示词。

### 2.2 命令生命周期（`CommandRuntime.execute`）

```text
command/run   { commandId, name, args?, source:{kind:"user"} }
   … handler 的副作用（plan/mode、steer 的 user 消息等）…
command/done  { commandId, kind, text?, sourceEventSeq? }
```

- 命令不包 turn，直接作为仅日志事件追加；命令与结果**永不进模型历史**；
- 准入失败（语法/未知名称）**什么都不记**（没进 handler）；
- `recordInput:false` 的命令不写 `args`；`/plan` 默认写（`/plan` → `args:""`，`/plan off` → `args:" off"`）。

### 2.3 `/plan`（`dsh-plan-mode` 命令 handler，逐字）

| 输入 | 行为 | `command/done.text` |
|---|---|---|
| `/plan` | `set(true)` | `Plan mode on. Use /plan off to leave.`（queued/cancelled 也走「Entering…」分支） |
| `/plan <message>` | `set(true)` + `agent.steer(用户消息)` | 同上 |
| `/plan off` · committed | `set(false)` | `Plan mode off.` |
| `/plan off` · queued | 轮次内排队 | `Leaving plan mode (applies from the next step).` |
| `/plan off` · cancelled | 取消尚未生效的进入 | `Plan mode entry cancelled.` |
| `/plan off` · noop | 已经是关闭 | `Plan mode is already inactive.`；若已记录 active 则 `Leaving plan mode (applies from the next step).` |

`set()` 四态与 `plan/mode` 落库时机沿用 M18：轮次外立即落库；轮次内 pending 到下一个 step 起点；
narration 只在「上一次请求告诉模型的是另一态」时补（`/plan` 命令也走同一逻辑）。

### 2.4 用户直接调用技能（`dsh-tool-skill` pre-step）

```text
SKILL_GESTURE = (^|\s)\/([a-z0-9]+(?:-[a-z0-9]+)*)(?=\s|$)
```

- 只扫本 step **直接用户消息**（`source.kind:"user"`）的每个 text 块；goal/通知/工具结果不能伪造手势；
- 名字按首次出现去重；目录里不存在 → 静默跳过（`/usr/bin` 这类路径不会误伤）；
- `userInvocable:false` → 跳过；`disable-model-invocation:true` 的技能**可以**从这条路径加载；
- 注入一条 `user/message`：正文 = `renderSkillContent()`，`source = {kind:"skill-invocation", name, form:"instructions"}`；
- 注入点在**技能目录消息之后、本 step 请求之前**（pre-step 链的最末尾，最贴近模型回答）。

### 2.5 `@file` 引用（`dsh-file-reference`）

- `@path` 是**普通提示词文本**，不读文件内容；模型要自己调 `read`；
- 含空白的路径用 `@"path with spaces"`；目录带尾斜杠 `@dir/`；目录的斜杠在闭合引号之前；
- 只有 token 开头（文本开头或空白后）的 `@` 触发；`email@host` 不触发。

---

## 三、实现

### 3.1 新模块 `core:input`（纯 JVM）

| 文件 | 内容 |
|---|---|
| `CommandLine.kt` | `parseCommandLine()` 手写扫描（避开 Java `$` 与 JS `$` 的行尾差异） |
| `InputTrigger.kt` | `inputTrigger(text, cursor)` 检测 `/`、`@`、`@"…"`；`formatFileMention()` |
| `FileReferenceSearch.kt` | 有界递归索引（50000 项）、默认排除构建目录、不跟符号链接、模糊排序（精确→前缀→子串→子序列，目录加成）、最多 20 条 |
| `Rank.kt` | `/` 菜单排序：前缀优先、再子序列（`rankByName`） |

### 3.2 `core:skill`

- `SkillGestures.kt`：`invokedSkillNames(texts)`（上游 `SKILL_GESTURE` 逐字）；
- `SkillHost.loadForUser(name)`：跳过 modelInvocable、仍校验 userInvocable；
  `FileSystemSkills` 实现（`disable-model-invocation` 技能的唯一入口）。

### 3.3 `core:session`

- `appendCommandRun(commandId, name, rawInput, recordInput)`；
- `appendCommandDone(commandId, kind, text, sourceEventSeq)`；
- 字段顺序与上游一致；命令事件不参与 `messagesOf` 投影。

### 3.4 `core:engine`

- `EngineDeps.skillInvocations: (SessionSession, List<String>) -> List<UserNotice>`；
- 只在**本轮第一步**、且不是 wake / goal 轮时，拿 `input.userTexts` 去扫手势；
- 注入顺序：本 step 的通知 → 技能目录 → 技能指令 → 请求；
- 目录消息不变（空目录仍零注入）。

### 3.5 App / UI

- `AppViewModel.submit(text)`：整行先解析命令 → `/plan` 执行；名称是用户可调用技能 → 普通发送；
  其余 `/` 行拒绝（保留草稿）；普通文本走 `send`；
- `executePlanCommand()`：为了命令日志完整，**新会话会先建 v4 文件**（request/header），
  然后 `command/run` → `plan/mode` → `command/done` →（可选）`turn/start`；
- `setPlanMode` 重构为 `applyPlanSelection`，返回四态供文案使用；计划条「退出」行为不变；
- 命令结果条 `ChatSessionState.commandNotice`：5 秒自动消失，不写聊天正文；
- 聊天输入框改用 `TextFieldValue`（要光标位置）、IME 动作 Send；
- `/` 面板：命令 + 技能（打开时 `refreshSkills()`）；`@` 面板：工作区候选（IO）；
  选中命令/技能插入 `/name `；选中文件插入 mention（目录保持开放以便继续下钻）；
- 技能指令卡：折叠「技能指令 · 名字」，点开渲染 `<skill_instructions>` 的 markdown；
- 回放兼容：`openSessionById` 从 `source.name` 补技能名（source 没有 summary）。
- 调试通道 `--es dsh_send` 改走 `submit()`，因此可以直接用 adb 验 `/plan`、`/名字`。

**拍板理由**：`/plan` 与技能都是「输入触发」的一部分，分开做会重造一遍菜单/裁决；
`@file` 只是纯文本（上游也不读内容），所以不做结构化引用载荷，先把最常用的路径补全做对。
命令入口建会话是为了事件顺序与上游一致（有 agent 才有 command 日志），代价是新会话列表里会先出一条空标题会话。

---

## 四、验证

### 4.1 单测（core 247，不含 attachment 16）

| 模块 | 数量 | 新增覆盖 |
|---|---|---|
| `core:input` | **15**（新） | 命令语法/边界/拒绝；`/`、`@`、`@"…"` 触发与光标；mention 格式；文件索引（排除、目录查询、失效重建、空格路径、20 条上限）；菜单排序 |
| `core:skill` | **25**（+4） | 手势边界/去重/多 text 块；`disable-model-invocation` 用户可加载；`user-invocable:false` 拒绝 |
| `core:engine` | **19**（+3） | 技能注入在目录之后、请求 4 个 text 块、UI 卡片；后续 step 不重复注入；wake 通知不扫手势 |
| `core:session` | **42**（+2） | 命令生命周期事件字段/顺序、`recordInput:false`、不进消息投影 |

### 4.2 真机（PLT120 / Android 15，账号通道 deepseek-flash）

临时技能 `files/skills/m21-demo/SKILL.md`（正文要求回 `SKILL-M21-MARKER`）：

| 截图 | 内容 |
|---|---|
| `m21-skill-invocation.png` | `/m21-demo` 用户气泡 → 技能目录分隔条 → 「技能指令 · m21-demo」→ 模型回 `SKILL-M21-MARKER` |
| `m21-skill-expanded.png` | 重开会话后点开技能指令卡：markdown 正文 + 名字回放正确 |
| `m21-plan-command.png` | 新会话 `/plan`：计划条 + 结果条 `Plan mode on. Use /plan off to leave.`（没有模型消息） |
| `m21-plan-off.png` | 同一会话 `/plan off`：计划条消失 + 结果条 `Plan mode off.` |
| `m21-plan-message.png` | `/plan 先读 hello.md…`：消息成为普通用户泡泡（不带 `/plan`），模型在计划模式下 read + READY |
| `m21-slash-menu.png` | 输入 `/`：命令 `/plan` + 技能 `/m21-demo` 分组菜单 |
| `m21-slash-picked.png` | 点选命令后输入框变成 `/plan `（不立即执行） |
| `m21-slash-executed.png` | 发送后：`已进入计划模式` 分隔条 + 计划条 + 结果条 |
| `m21-at-menu.png` | 输入 `@`：工作区文件候选（agent-shot.png / hello.md） |
| `m21-at-picked.png` | 点选文件后输入框插入 `@hello.md `（菜单关闭） |
| `m21-unknown-command.png` | `/nope` 发送被拒绝：结果条「未知命令 /nope」，草稿保留 |

事件证据（`tools/session-dump.ps1`）：

```text
# 技能调用 06ce7f97-701a-47ea-aeac-ab1b1f405ede
seq 3  user/message  "/m21-demo"（source.kind=user）
seq 4  user/message  source={kind:"skill-catalog", entries:[m21-demo]}
seq 5  user/message  source={kind:"skill-invocation", name:"m21-demo", form:"instructions"}
seq 6  assistant/message  SKILL-M21-MARKER

# /plan 711609a3-cf5c-48d7-87d2-2558c78d894c
seq 0  request/header
seq 1  command/run  {name:"plan", args:"", source:{kind:"user"}}
seq 2  plan/mode    {active:true}
seq 3  command/done {kind:"success", text:"Plan mode on. Use /plan off to leave."}

# /plan <message> a31830b1-9579-40ee-a45b-7180a9962ca9
seq 1  command/run  {args:" 先读 hello.md，然后只回 READY，不要改文件"}
seq 2  plan/mode    {active:true}
seq 3  command/done
seq 4  turn/start → seq 6 user/message（只有消息正文，不含 /plan）
```

请求体（`--es dsh_dump_request`）：

```text
/m21-demo      tools=31  system=3950（无技能段）  首条 user = 4 个 text 块：
               运行时上下文 → /m21-demo → 技能目录 → <skill_content>
/plan <msg>    tools=31  system=6309（含 plan:policy）  首条 user = 3 个 text 块
```

### 4.3 门 2（无重录）

工具表仍 31、系统提示词与请求序列化未动；device-smoke verify：

```text
run-1 vs run-2: 246 vs 246  PASS
```

收尾已还原：`approval=ask`、`base_url=官方`、`debug_dump_sse=off`、`debug_parity_run=off`；
workspace 只剩 `agent-shot.png` + `hello.md`，`files/skills` 为空。

---

## 五、没做 / 下一步

1. **其余命令**：`/model`、`/permission`（popupSelect）、`/compact`（压缩）、`/goal`、`/feedback`、`/download-log` 尚未注册；
   现有命令目录只有 `plan`（`DSH_COMMANDS`）。
2. **`@session` 引用**：上游 `@[label](dsh-session:…)` + pre-step 快照；原生只做文件。
3. **附件命令**：上游 `/plan` 可带图片/文件（`input.attachments`）；原生还没有用户发图/附件提交。
4. **流中命令**：原生输入条在轮次中变成「停止」，`/plan off` 只能用计划条「退出」；
   `/plan <message>` 在流中不会 steer（没有输入队列）。
5. **键盘导航**：菜单支持点击/IME Send，没有 ↑/↓/Tab 高亮与 Escape 关闭。
6. **引用 UI**：`@path` 是纯文本，没有行内 chip、点击预览、目录下钻的面包屑；
   文件索引在工具结果/轮次结束失效重建，不做后台增量。
7. **技能菜单刷新时机**：只在 `/` 打开时刷新一次；手动往目录丢文件后需要重新开菜单。
8. **工具 31/32**：仍缺 `workflow`（QuickJS）。

# 36 · 技能（skill）+ 会话内技能目录（M19）

> 2026-11-08 05:1x｜基线 `019f121`（M18 计划模式）之后。
> 目标：工具 30 → **31 / 32**，把上游 `dsh-skill` / `dsh-skill-filesystem` / `dsh-tool-skill`
> 三层移植成原生：SKILL.md 发现、优先级合并、会话内技能目录消息、`skill` 工具加载 `<skill_content>`；
> 同时把「空目录不占上下文」的字节等价性保住（5 个夹具全部重录后仍 PASS）。
> 上游依据：`dsh-skill/lib/index.js`（名字语法/调用策略/渲染/摘要）、
> `dsh-skill-filesystem/lib/index.js`（四个根、rank、frontmatter、`resourceBase`）、
> `dsh-tool-skill/lib/index.js`（catalog 注入语义 + 工具四条错误分支）、
> `dsh-web-app/presets/standard.patch.yml`（standard preset 里 `skill-filesystem` + `tool-skill` 的挂法）。

---

## 一、一句话结果

| 项 | 之前 | 现在 |
|---|---|---|
| 工具数 | 30 / 32 | **31 / 32**（新增 `skill`） |
| 技能发现 | 无 | 四个根：`workspace/.dsh/skills`(100) → `workspace/.agents/skills`(200) → `files/skills`(400, skipSystem) → `files/agents/skills`(500)；目录型 `SKILL.md` + 平铺 `*.md`；同名先到先得；`.system` 跳过 |
| 会话内目录 | 无 | 每个 step 起点比较 sha256 摘要；变了才落一条 `user/message`（`source.kind:"skill-catalog"`，首次 = 目录、之后 = 「目录变了」替换消息）；空目录且从没发布过 → **什么都不加**（请求体零变化） |
| `skill` 工具 | 无 | 四条分支逐字对齐上游（invalid name / unknown or no longer available / not available for model invocation / `<skill_content>`） |
| 设置页 | 无 | 设置 → 技能：已发现技能（名字/描述/来源/路径）、四个扫描根（rank + 缩短路径）、被忽略原因、重新扫描 |
| 聊天页 | 无 | `skill-catalog` 消息渲染成「技能目录 · N 个技能」分隔条（不参与搜索、不进正文） |
| 单测 | tools 81 / engine 14 | **skill 21（新模块）/ tools 87 / engine 16** |
| 门 2 | 5 个夹具（30 工具） | 工具表 31 → **5 个夹具全部重录并 PASS**（§五） |
| 真机 | — | 目录消息 → 模型调 `skill` → 按 SKILL.md 指令回显标记行，全链路跑通（截图 2 张 + 事件证据） |

---

## 二、上游语义（读源码确认）

### 2.1 三层分工

| 层 | 上游包 | 原生 | 负责 |
|---|---|---|---|
| 能力缝 | `dsh-skill` | `core:skill` 的 `SkillModel.kt` | 名字正则 `^[a-z0-9]+(?:-[a-z0-9]+)*$`、`SkillInvocation`、`<skill_content>` 渲染、转义 |
| provider | `dsh-skill-filesystem` | `SkillFileSystem.kt` + `SkillFrontmatter.kt` | 四个根、rank、目录/平铺、YAML frontmatter、`resourceBase = 目录` |
| 消费端 | `dsh-tool-skill` | `SkillCatalog.kt` + `core:tools` 的 `SkillTool` | 目录消息（首次/变更/不完整）、sha256 摘要、工具四条分支 |

### 2.2 发现规则（逐条照抄）

- 根目录顺序 = rank 升序；**同名先到先得**（低优先级条目只记一条 warning）；
- 一个根下：子目录 → 读 `<dir>/SKILL.md`（目录型，`resourceBase` = 该目录）；`*.md` 平铺文件 → 单文件型（`resourceBase` = 根目录）；其它忽略；
- 条目按名字码元序；`user-dsh` 根跳过 `.system`（上游 `skipSystem: true`）；
- frontmatter 首行必须是 `---`、必须有闭合 `---`、必须是映射、`name`/`description` 非空、`name` 过 kebab-case；
- 旧字段 `disableModelInvocation` / `modelInvocable` / `userInvocable` → **整文件拒收**（上游抛错后忽略）；
- `disable-model-invocation: true` → 目录与工具都不给模型（`SkillLoad.NotModelInvocable`），但用户仍可调用；
- 读失败/解析失败只忽略那一条；**根目录本身打不开**才把快照标成 incomplete（目录消息带「发现不完整」提示）。

原生只实现这四个根：Android 没有桌面 `$DSH_HOME`/`$AGENTS_HOME`/bundled dir 的分离，
`files/` 就是 App 的 DSH home，`files/skills` 与 `files/agents/skills` 对应 user 两级。

### 2.3 目录消息（`agent/pre-step`）

每个 step 起点把「当前目录」的 sha256 摘要与会话里**最后一条可见** `skill-catalog` 消息比较：

| 情况 | 行为 |
|---|---|
| 摘要相同 | 什么都不加（请求体零变化） |
| 从没发布过 + 目录为空 + 发现完整 | 什么都不加（空目录不占上下文） |
| 其余 | 追加 `user/message`：`source = {kind:"skill-catalog", form:"catalog", entries:[{name,description}], [update], [incomplete]}`，正文是 `<system-reminder>`（首次 = 目录；之后 = 「这份目录取代之前所有列表」） |

摘要 = sha256(`"incomplete\n"`?) + 每行 `["name","description"]`（行间 `\n`），与上游 `digestCatalogEntries` 同形；
description 先做空白折叠 + trim，超过 500 字截断加 `...`（`DEFAULT_CATALOG_DESCRIPTION_MAX_LENGTH`）。

注入点在 **inbox 通知之后、本 step 请求之前**（与上游「claimed messages → 注入」的顺序一致）。

### 2.4 `skill` 工具（第 31 个）

| 项 | 上游 | 原生 |
|---|---|---|
| schema | `{name: string}`，`required:["name"]` | 直接用 `tools.json`（契约 #2） |
| 名字非法 | `invalid skill name "..."` | 同 |
| 不存在/刚被删/解析失败 | `skill "..." is unknown or no longer available` | 同 |
| `disable-model-invocation` | `skill "..." is not available for model invocation` | 同 |
| 成功 | `<skill_content name>` + `<skill_resources>`（目录基址 + 相对路径提示）+ `<skill_instructions>` + 正文 | 同（逐字，见 `renderSkillContent`） |
| 审批 | 只读（`presentCall.kind = "read"`） | 加进 `READ_ONLY_TOOLS`（「每次询问」也不弹窗；子代理可调） |

---

## 三、实现

### 3.1 新模块 `core:skill`（纯 JVM，可单测）

| 文件 | 内容 |
|---|---|
| `SkillModel.kt` | `isSkillName`、`SkillInvocation`、`SkillResourceBase`、`SkillSummary`/`SkillContent`、`SkillLoad`、`SkillHost`、`escapeText`、`renderSkillContent` |
| `SkillFrontmatter.kt` | YAML 子集（顶层标量/引号/行内注释/`|`/`>` 块标量/嵌套跳过）+ 旧字段拒收 + 布尔解析 |
| `SkillFileSystem.kt` | `SkillRoot`、`FileSystemSkills`（`snapshot()` / `load()` / 忽略原因）、`androidRoots()`、`findProjectRoot()` |
| `SkillCatalog.kt` | 条目归一化、sha256 摘要、首次/变更/不完整文案、`decide()`、`source()`/`readEntries()` |

### 3.2 `core:engine`

- `EngineDeps.skillCatalog: (SessionSession) -> UserNotice?`：每个 step 起点调用一次，非 null 落库 + 刷新 UI；
- 工具审批/通知/计划模式的顺序不变；catalog 只多一条 `user/message`（不另开 step/turn）。

### 3.3 `core:tools`

- `ToolContext.skills: SkillHost?`；`SkillTool`（registry 30 → 31，`tools.json` 的 `skill` schema 自动进请求）。

### 3.4 App / UI

- `AppViewModel`：`FileSystemSkills(androidRoots(workspace, filesDir))`；根/子代理引擎都挂 `skillCatalog`；
  设置页数据（`skillsUi` / `skillsComplete` / `skillsWarnings` / `skillRoots` / 路径缩短）；
- 聊天页：`skill-catalog` 分隔条（「技能目录 · N 个技能」），搜索排除；
- 设置 → 技能页（`SubScreen.Skills` + `SkillsScreen.kt`）：已发现技能、扫描根、被忽略原因、刷新；
- 调试通道 `--es dsh_open_screen skills` 直接落到该二级页（`dsh_open_screen` 现在同时认一级页与二级页）。

**拍板理由**：上游把「用户直接 `/名字` 调用」也归在同一个渲染上；原生输入框还没有斜杠命令系统（与 `/plan` 同一笔账），
所以 M19 只做**模型侧**（目录 + loader），用户 invocation 等输入触发系统一起做。
`bundled`/Office 技能属于插件/provider 层，等 cordis 插件内核（docs/04）。

---

## 四、验证

### 4.1 单测

| 模块 | 数量 | 覆盖 |
|---|---|---|
| `core:skill` | **21**（新） | frontmatter（引号/注释/块标量/旧字段/缺闭合）、发现（目录/平铺/同名优先级/`.system`/坏文件 warning）、`disable-model-invocation`、load 三分支、首次/变更/不完整目录文案逐字、摘要稳定性、`<skill_content>` 逐字、转义、source 往返 |
| `core:tools` | **87**（+6） | `skill` 工具四条分支 + 无服务 + 空名字；registry 31 |
| `core:engine` | **16**（+2） | catalog 消息在用户消息之后、step/start 之后落库；请求体 3 个 text 块；null 时请求体 2 块不变 |

### 4.2 真机（PLT120 / Android 15，账号通道 deepseek-flash）

临时技能 `files/skills/parity-skill/SKILL.md`（正文含 `SKILL-MARKER-42`）：

| 截图 | 内容 |
|---|---|
| `docs/shots/skill-loaded.png` | 用户消息 → 「技能目录 · 1 个技能」分隔条 → `skill {"name":"parity-skill"}` 工具卡（成功）→ 模型按技能说明回显 `SKILL-MARKER-42` |
| `docs/shots/skills-page.png` | 设置 → 技能：已发现技能（描述 + `files/skills/...` 路径）、四个扫描根（rank 与缩短路径）、优先级说明 |

事件证据（`tools/session-dump.ps1`，会话 `9855352f-85fa-4151-bd95-aa1dd5f3a2f9`）：

```text
seq 3  user/message  source.kind=user
seq 4  user/message  source.kind=skill-catalog form=catalog   # 目录消息（entries 里只有 parity-skill）
seq 5  assistant/message
seq 6  tool/call     skill {"name": "parity-skill"}
seq 7  tool/result   <skill_content name="parity-skill">…SKILL-MARKER-42…</skill_content>
seq 9  step/start … seq 12 turn/end
```

请求体（`--es dsh_dump_request`）：`tools = 31`（含 `skill`）、`system` 3950 字（**没有**技能段，与桌面一致）、
第一个 user 消息 3 个 text 块 = 运行时上下文 + 用户输入 + 技能目录。

### 4.3 门 2（工具表 30 → 31，5 个夹具重录）

| 夹具 | 请求数 | run-1 vs run-2 | 结果 |
|---|---|---|---|
| device-smoke | 2 | 246 vs 246 | PASS |
| device-read | 2 | 45 vs 45 | PASS |
| device-write | 2 | 88 vs 88 | PASS |
| device-edit | 3 | 80 vs 80 | PASS |
| device-read-error | 2 | 150 vs 150 | PASS |

重录细节：
- `record` 仍不清理夹具目录：device-edit 本轮只有 3 个请求（M18 是 4），旧的 `llm-0004.jsonl` 必须手删；
- 录制时 `--ez dsh_auto_approve true` 会把 approval 持久化成 auto，收尾必须 `--ez dsh_ask_approval true`；
- **临时技能必须在录制前删掉**：否则每个请求会多一条 `skill-catalog` 的 user 消息（夹具会绑死在本机技能上）。

收尾：workspace 只剩 `agent-shot.png` + `hello.md`；`approval=ask`、`base_url=官方`、`debug_dump_sse=off`、
`debug_parity_run=off`（已核对 `shared_prefs/dsh.xml`）。

---

## 五、没做 / 下一步

1. **用户 invocation（`/名字`）**：M21 已做（手势扫描 + pre-step 注入 `<skill_content>` + `source.kind:"skill-invocation"`，见 docs/38）；输入触发面板同时列出可调用技能。
2. **bundled / Office / 徽章 provider**：桌面有 `DSH_BUNDLED_SKILL_DIR` 与 `dsh-skill-office`；原生等插件内核（docs/04）。
3. **`whenToUse` 不进目录**：上游目录行只有 name + description；`whenToUse` 已解析保存，留给以后 UI/排序。
4. **文件监听**：上游有 chokidar，原生每个 step 现扫（5 个目录，可忽略）；设置页手动刷新。
5. **工具 31/32**：只剩 `workflow`（JS 编排，需要 QuickJS）。
6. **技能的写入口**：现在只能用文件管理器/`run-as` 放文件；以后可加「新建技能」向导（编辑 SKILL.md + frontmatter 校验）。

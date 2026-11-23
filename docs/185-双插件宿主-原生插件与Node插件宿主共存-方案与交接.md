# 185 · 鲸歌底层重构：原生原语层 + 双插件宿主（实施版 v2）

> 2026-11-19 23:0x–23:5x 讨论定稿。**本轮未改源码、未构建、未装机、未动 tag/Release/发布树。**
> 本文取代同日早先的讨论稿：早期逐条讨论记录收进附录 A。
> 现行发布版 **0.3.5 / versionCode 8**（`docs/184`）；本方案不涉及升版。

---

## 〇、一句话

**鲸歌 = 一层剁到足够碎的原生原语（方块）+ 一个运行时组合层（橡皮泥）+ 两个插件宿主（原生轻宿主 / Node 重宿主）。
冷启动只跑第一层和轻宿主，Node 在页面可用之后「继续」启动。**

判断这件事成不成，只有一个判据：**能不能在不改 APK 的前提下，用原语拼出新能力**（第一个样本：handoff）。

---

## 一、已定决策（用户拍板，不再讨论）

| # | 决策 | 来源 |
|---|---|---|
| D1 | **原生内核不重写**：UI、`TurnEngine`、会话格式 `session.v4`、提示词、内置工具全部保留 | 「把 node 塞进去，而不是重构」 |
| D2 | **不把 dsh 当底座**；原生始终是主体，关掉宿主 App 仍完整 | 「如果核心仍是 dsh 就不算原生移植」 |
| D3 | **社区 DSH 插件要能原样跑** → 必须真 Node | 「插件基本都是 dsh web 界面插件」 |
| D4 | **node 是启动流程的后半段**：进页面、能聊天之后「继续」启动，不是用户触发的新流程 | 「注意是继续，不是开始」 |
| D5 | **两套插件体系共存**：原生插件系统保留，且随冷启动启动 | 「原生的插件系统还是要留着的，共存吧」 |
| D6 | **装真 dsh 包，不写桩**：只有"跨进程共享状态/原生能力"才走桥 | 「把 dsh 关于插件依赖的部分全部弄进来」 |
| D7 | **零补丁跟上游最新版**（当前 `@deepseek-ai/dsh@0.2.2-rc.5`，Node ≥24.9） | 「他的内核是改的，现在内核要认准上游最新版」 |
| D8 | **把边界剁碎**，而不是把功能剁碎：定义有界原语集合，让组合发生在运行时 | 「剁得足够碎也算是泥」 |
| D9 | **界面只给 3 个容器**（全屏/悬浮/面板），不逐类镜像 dsh 的界面贡献；**永久放弃"插件嵌进原生聊天流"** | 「还是无底洞啊」 |
| D10 | **降级链**：真跑 → **搬 dsh 真环境（不自己写环境）** → AI 调插件自身的 node 侧适配 → 不支持。**不把 dsh 插件改写成原生插件** | 「不是改造成原生版，还是 node，只是鲸歌适配的 node；node 的一些环境不要自己做，搬 dsh」 |

---

## 二、为什么是这条路（三行，不再展开）

- **现状**（Kotlin 重写 + QuickJS 模拟 `@deepseek-ai/*`）：功能是死的、插件只能跑子集，追不上。
- **甲**（整包 dsh + web UI）：自由度满格，但冷启 7.4s、原生身份没了。
- **本方案**：原生身份保留，把"自由度"从功能搬到**原语 + 运行时组合**上；自由度上界 = 原语集合，
  结构上界靠 node 宿主打开。**代价诚实写在 §十一。**

---

## 三、架构（五层）

```
L5 组合层（运行时）          AI 用工具拼 / 插件用桥拼 / 用户用界面拼
                            handoff、多步研究、自动化流程……都长在这一层
─────────────────────────────────────────────────────────────
L4 插件宿主                  原生宿主（QuickJS·冷启动内）      Node 宿主（真包·后台续启）
─────────────────────────────────────────────────────────────
L3 原语层（ABI，本方案核心）   ~20 个可组合原语：会话/话题/模型/回合/界面/数据/工具
                            三方同一份定义：AI 工具面、插件桥面、原生调用面
─────────────────────────────────────────────────────────────
L2 原生服务                   SessionStore · TurnEngine · PromptPack · LlmProviders · ToolRegistry
                            PluginStore · 附件 · 终端 · 设备能力 · 提醒/任务
─────────────────────────────────────────────────────────────
L1 原生 UI（Compose）         冷启动路径只到这里 + L2 + L3 的只读部分
```

**关键点**：L3 是新的。现在 L2 里的能力全都能用，但只对 L1 开放；L3 把它们变成"任何一方都能调"的东西。

---

## 四、原语层（ABI）——「剁碎」的落点

### 4.1 原则

1. **一个动作只暴露一次**：同一个能力不许"UI 一份实现、工具再包一层、插件再走一条路"。
2. **原语必须可组合**：原语的返回值要能直接喂给下一个原语（会话 id、事件 id、brief 文本、surface key）。
3. **原语不带界面假设**：`ui.navigate` 只表达"去哪"，不表达"怎么画"。
4. **不改 APK 也要能拼**：任何"必须发版才能做"的复合能力，都是原语不够碎的信号 → 记一条，别去改功能。

### 4.2 首批清单（草案，P1 定稿到 15–25 个）

| 组 | 原语 | 现状 |
|---|---|---|
| 会话/话题 | `session.list` `session.open` `session.fork(atEvent)` `session.read(range)` `session.summarize(range)→brief` `topic.end` `topic.switch` | 实现都有，**未暴露** |
| 模型 | `model.list` `model.set` `effort.set` `permission.set` | prefs 里有，**未暴露** |
| 回合 | `turn.send` `turn.stop` `turn.state` | 有工具/内部实现，**未统一** |
| 界面 | `ui.openContainer(kind)`（fullscreen/floating/sheet）`ui.ask_user(q, options)` `ui.notify` | 现有悬浮窗/导航有实现，**未统一** |
| 数据 | `store.get/set(ns)` `fs.read/write` | 插件 config 有，**未统一** |
| 事件 | `event.emit` `event.subscribe(pattern)` | `SessionSession.addEventListener` 有，**未暴露** |
| 工具 | `tool.list` `tool.call(name, args)` `tool.register` | 有，**未统一** |
| 已有 | `schedule.*` `job.*` `goal.*` | 已经是工具，补齐描述即可 |

> 注：`session.summarize` / `topic.switch` / `topic.end` 是 handoff 的必要条件；`session.fork`
> 是分支原语（P-1 已暴露并用单测 + 真机验证，但 handoff 实测不需要它——见 §九 P-1 完成记录）。

### 4.3 「够碎了吗」的判据（写进 CODEOWNERS 级别的纪律）

> 任何新需求，先问：**用现有原语组合能不能做到？**
> - 能 → 走组合层（工具/插件/提示词），不改内核。
> - 不能，且缺的是**原语** → 加原语（有界），走 P1 的流程。
> - 不能，且缺的是"某个业务流程的特定写法" → 说明在用错的层解决问题，退回组合层。

---

## 五、启动时序与状态机（D4/D5 的落地）

```
T+0       进程启动
T+668ms   原生首帧（加载页）
T+~1.3s   核心就绪
T+~1.9s   原生壳首帧 → 进页面 → 可聊天（内置工具齐全）
          ★ 原生插件系统在这一段内启动完毕（与 0.3.5 一致）
T+1.9s起  启动流程「继续」：后台 spawn nodehost（低优先级）
            spawn →（仅首次）解压运行时 → 起真 cordis + 真 dsh 包
            → 装载已启用社区插件 → 上报能力清单 → bridge ready
T+~4–6s   插件能力点亮
```

`PluginHostState`：`OFF / IDLE / EXTRACTING / STARTING / READY / FAILED / STOPPED`。
硬规则：**聊天路径永不被宿主状态阻塞**；插件区必须显示"启动中/失败/缺什么"，不许假装没有。

---

## 六、双宿主分工

| | 原生宿主（保留） | Node 宿主（新增） |
|---|---|---|
| 形态 | 鲸歌包 IR / 我们的契约 | 社区 npm 包（DSH 生态原样） |
| 启动 | 冷启动内 | 冷启动后后台续启 |
| 命名空间 | `native:<pluginId>` | `node:<pkgName>` |
| 能力上界 | 我们实现的契约 | 真包覆盖到的 dsh API |
| 导入分流 | 鲸歌格式包 | `dependencies` 有 `@deepseek-ai/*` 或 `exports["./client"]` |

- 同一个包**不许**同时装两个宿主；迁移 = 卸载重装（UI 要写清会重置配置）。
- 能力合并：内置工具不可被覆盖；插件撞名自动加 `<host>_` 前缀；提示词三段拼接；供应商同名时 内置>native>node，被压制项**可见**。
- 插件页面：node 插件只出 WebView 面（§八），不进原生 Compose 树；native 插件可进原生树。

---

## 七、Node 宿主：真包、零补丁、影子机制

### 7.0 node 环境 = 搬 dsh 的，不是我们造的（D6/D10 的核心，先回答这个问题）

**是。方案里明确写：node 侧的环境由 dsh 的真包构成，鲸歌不自己实现一套 node 环境。**
但不是"整个 dsh 全搬"，按三类处理：

| dsh 的东西 | 处理 | 理由 |
|---|---|---|
| 插件直接 `import` 的包：`cordis`、`@deepseek-ai/dsh-tools`、`schemastery`、`dsh-llm`/`dsh-prompt` 的注册接口、`dsh-cordis-client-runner` 等 | **搬**（npm 原样、零补丁） | 这就是"鲸歌适配的 node"：环境是 dsh 的，插件不用改 |
| 需要 Android 适配才能跑的 4 个包：`dsh-subprocess-local`、`dsh-attachment-local`、`dsh-bash-local`、`dsh-session-persistence-jsonl` | **不搬**，走桥接原生（原生终端/附件/bash/`session.v4` 已有等价能力） | 搬它们就要打那 4 个补丁（§7.1），零补丁纪律就破了；而原生侧本来就有更好的实现 |
| dsh 的 turn engine / agent loop / `dsh web` 服务器 | **禁搬** | 越界线：搬了鲸歌就退化成甲（§7.1） |

一句话：**插件看得见的 dsh API 全搬真的；插件看不见但鲸歌已有的运行能力走桥；
dsh 的主体（回合/前端服务）不进来。**

### 7.1 零补丁（D7）

- dsh 包只读；**不改源码、不改 dist、不改它的 package.json**。
- 定制只允许在：① 我们的桥与桩；② cordis 配置；③ WebView 运行时注入（不写进 dist）。
- 插件安装不许抄旧项目（塞 node_modules + 改 deps + 改 `cordis.patch.yml`）；优先用 `@deepseek-ai/dsh-plugin-manager`。
- **旧项目那 4 个源码补丁我们不需要**：subprocess→原生终端、attachment→原生附件、bash→原生工具、session 持久化→原生 `session.v4`。桥接架构天然绕开。
- **反过来的风险**：若某类插件必须加载 dsh 自己的 session/agent/subprocess 才跑，等于要打补丁 → **停下来找用户**，不许默默打。

### 7.2 影子机制（插件怎么"出现在原生里"）

> 元数据就绪时「搬」过来，执行时「借」过去；插件本体永远在 node 进程。

| 插件动作 | 原生落点 | 机制 |
|---|---|---|
| `ctx.tools.register` | `ToolRegistry` 多一个 `node_<name>` | 搬（name/description/schema） |
| 模型调用该工具 | `TurnEngine` → 审批 → 审计 → 桥 `tool.invoke` → node 真执行 → 结果写回 `session.v4` | 借 |
| `ctx.promptSections.register` | `PromptPack` 拼装时插入 | 搬（文本/顺序/作用域） |
| `ctx.llm.register` | `LlmProviders` 多一项（复用 `ConfiguredLlmProvider`） | 搬 |
| `dsh.client` UI | 插件页「打开界面」→ WebView 面 | 嵌 |
| 插件 config | `PluginRecord.config` | 原生是唯一真相 |

### 7.3 跨进程做不到的三件事

| 写法 | 对策 |
|---|---|
| 提示词段的 `when: () =>` 函数 | 只支持声明式条件；函数条件标"不支持" |
| 供应商 `stream()` 回调 | 先只支持配置式；后续可"原生发请求 → node 调 stream → chunk 流回" |
| 工具中间件改写 | 双向 `host.event` 打补丁，最晚做（P4） |

这些是"部分不可用"的主要来源，插件页逐条显示。

### 7.4 反例：为什么「逐类镜像」是无底洞（2026-11-19 23:5x 用户否决）

用户追问「预设/悬浮窗/单独页面怎么接到原生」，我给了下面这张 10 类落点表；用户当场否决：
**「还是无底洞啊」——判断正确。** 保留此表作为反例，后来者不要再走：

> 增长源在上游：dsh 每加一类界面贡献，我们就得多一个落点。**原生镜像的复杂度 ∝ 上游的界面表面。**
> 只要「逐类翻译」，洞就不会见底。**结论：不翻译，只给容器（§7.5）。**

| # | 插件贡献（dsh 侧） | 原语 | 原生落点／显示形态 | 生效机制 | 难度 |
|---|---|---|---|---|---|
| 1 | `tools.register` | `tool.register` / `tool.call` | `ToolRegistry` 里的 `node_*` 工具；插件页列出 | 模型直接可调；审批/审计走原生 | 低 |
| 2 | `promptSections` | `prompt.section` | 无 UI；`PromptPack` 拼装时插入 | 每次拼请求生效 | 低 |
| 3 | `llm.register` | `model.register` | 模型列表多一项 | 复用 `ConfiguredLlmProvider` | 低 |
| 4 | `commands` | `command.register` | 原生 slash / 命令面板多一项 | 输入时命中 → 桥转发执行 | 低 |
| 5 | 预设（模型+提示词+工具+权限组合） | `preset.list` / `preset.apply` | 「设置 → 助手/角色」列表项 | 选中即应用到当前会话 | 低（纯数据） |
| 6 | 单独页面（`ui.registerRoute`） | `ui.navigate` + `surface` | 原生导航入口 → 全屏 WebView | 真 runner 加载插件前端 | 中 |
| 7 | 悬浮窗（`shell.overlay`） | `ui.openSurface(floating)` | 原生透明悬浮窗 | 插件 web 画在窗口里 | 中 |
| 8 | 插槽卡片（消息流内、设置分区、工具栏） | `surface.mount(slot)` | 原生排版里的小 WebView | 测量尺寸 + 填充 | **中高 → 无底洞源头** |
| 9 | `sessionProjections` | `event.subscribe` | 有 view 做成面 | 原生事件流喂插件 | 低–中 |
| 10 | 存储/定时/后台 | `store.*` / `schedule.*` | 无 UI | 原生是唯一真相 | 低 |

**为什么危险**：第 1–5、9–10 类是"数据"，加一个是一个，线性、便宜；第 6–8 类是"界面位置"，
每类都要原生排版配合，且上游随时可能加新位置——**第 8 类尤其是深渊**。所以只保留数据类，界面类改容器。

### 7.5 封底方案：容器化 + 三级降级链

**容器只有一个原语**：`ui.openContainer(kind, pluginId, payload)`，`kind ∈ {fullscreen, floating, sheet}`。

| 容器 | 对应 dsh 插件的东西 | 原生负责 | 插件负责 |
|---|---|---|---|
| `fullscreen` | 单独页面 / route | 打开、返回、生命周期 | 容器内 100% 是它的 web 世界 |
| `floating` | `shell.overlay` / 悬浮窗 | 位置、层级、手势、点击穿透 | 同上 |
| `sheet` | 面板 / 抽屉 | 高度、拖拽、键盘 | 同上 |

- **不实现 dsh 的 slot 协议**：插件 UI 进容器自己长，原生不认识它是什么 → **新插件零适配**，
  上游加界面贡献 → 仍然进容器 → **落点数固定 3 个，不随上游增长**。
- 附一组容器能力：关闭自己、设标题、发一条消息、读主题、调设备能力（走桥）。
- §四 的原语清单据此瘦身：**砍掉 `ui.navigate` / `surface.mount(slot)`，只留 `ui.openContainer`。**

**永久放弃（写进产品定位）**：插件嵌进原生聊天流——会话流内卡片、消息级 custom render、
侧栏插块，一律不做；插件要界面就开容器。

**降级链（2026-11-19 23:5x 用户两次修正后的定稿；早先"改写成原生插件"的写法是错的，已作废）**：

> 用户原话：「不是改造成原生版，还是 node，只是是鲸歌适配的 node；node 的一些环境不要自己做，搬 dsh。」

```
① 真跑     鲸歌的 Node 宿主（真 node + 真 cordis + 插件依赖的真 dsh 包）+ 桥 + 3 容器
   ↓ 缺某个 dsh 服务/包，或插件 import 的东西不存在
② 搬环境   **不自己写环境、不写桩**：把 dsh 的对应真实现整包搬进来（真 dsh 服务包），
           经桥暴露给原生。这才叫"鲸歌适配的 node"
   ↓ 搬了环境还是不行（依赖桌面 app / 原生 helper / 完整 web 前端 / 会话流内嵌）
③ 调插件   用鲸歌的 skill 让 AI 改**插件自身**的 node 侧适配：替换 import、加降级分支、
           把依赖的 dsh API 用法改到鲸歌宿主提供的范围内。**产物仍是一个 node 插件**，
           不改写成 Kotlin 原生插件
   ↓ 还是不行
④ 不支持   明确标「无法运行（原因）」，**不做原生改写**
```

**②「搬环境」的纪律（关键，防止滑坡）**：

- 只搬**插件运行所依赖**的包，按 `import` 闭包算；搬进来的是 npm 原样包，零补丁（D7）。
- **禁止**因此启动 dsh 的 turn engine / agent loop / `dsh web` 服务器——一旦某插件"必须起这些"
  才能跑，就越过 §7.1 的越界线：**停下来找用户**。
- 每搬一批要记账：体积、内存、启动增量（进 `docs/185-evidence/` 的账本），否则会悄悄胀成甲。

**③「调插件」的做法**：

- 输入：原插件包 + 显式失败清单（缺哪个 API / 哪个 import 解析不到）。
- 过程：AI 读源码 → 改依赖与调用方式 → 在宿主里试运行 → 修到能跑。
- 产物：`node:` 命名空间下的一个**适配版插件包**，原包保留可回退，报告写清"改了什么、哪些行为不等价"。
- 触发：**手动流程，不做 UI 按钮**（用户 2026-11-19 23:5x 定）。用户对 AI 说「把这个插件适配到鲸歌」，
  AI 按 skill 执行；插件页只提示「该插件可让 AI 手动适配」，不提供一键按钮。
- 风险：上游更新不自动跟进（产物是分叉）；分发时注意原插件许可证。

**注意**：早先版本这里写的是"改写成原生插件（native 命名空间）"——**与 D6（装真包不写桩）方向相反，
用户已否决，作废。** 鲸歌的原生插件体系留给"本来就是鲸歌格式的插件"，不是 dsh 插件的翻译目标。

**这条链的意义**：洞不封的地方不靠我们手工适配——**先搬 dsh 的环境，再让 AI 调插件，最后才说不支持**。
我们的工程量因此有界（数据类线性 + 3 容器 + 按需搬包），长尾交给 ②③。

### 7.6 三级链的口径（回答「node 改变原生」到底定了没）

- **能改原生的**：工具、提示词段、模型供应商、命令、预设（数据类，桥接即生效）+ 3 种容器（界面类）。
- **不能改原生的**：任何表外的界面位置（会话流卡片、侧栏插块、主题级定制、依赖 dsh 前端内部状态）。
  → 插件页显示「界面扩展点不支持」；走降级链 ②（搬环境）或 ③（调插件），**不改写成原生插件**。
- **不许**：为单个插件在原生里加一个专属落点。**一次也不许**——那正是无底洞的起点。

---

## 八、界面：原生 + 三种容器（D9）

- **原生**：聊天、设置、侧栏、悬浮球、通知、终端——全部原生，不进 web。
- **容器**：插件界面只在这三种容器里活（§7.5）——`fullscreen`（单独页面）、`floating`（悬浮窗）、
  `sheet`（底部面板）。容器内 100% 是插件的 web 世界；原生只管位置/层级/生命周期/能力调用。
- **不做**：会话流内插卡片、消息级 custom render、侧栏插块、主题级定制。
  → 插件页显示「界面扩展点不支持」，能力缺口走 D10 的 ②（搬环境）/ ③（AI 调插件）。
- 现有的 `SurfaceSpec.kt` / `SlotSpec.kt` / `SurfaceRenderer.kt` / `PluginShellScreen.kt` 保留给
  **原生插件**（native 命名空间）用，**不再用于镜像 dsh 插件的界面**。
- **B（dsh 界面模式）**：留作逃生舱，**不在本项目范围**；若启用，前提是先给 dsh 写"走桥的
  session persistence provider"，否则会出现两套会话存储。

---

## 九、阶段计划（每阶段独立验收）

### P-1 · ABI 实验（1 周，最先做，产数据）

目标：用鲸歌自己的原语把 **handoff** 端到端做出来一次。
- 暴露 5 个原语：`session.fork` / `session.summarize` / `model.set` / `effort.set` / `ui.navigate`(+`topic.end`)。
- 让 AI 通过工具调它们，完成：旧话题出 brief → 新话题带上下文起来 → 旧话题归档。
- **验收**：连续两次交接成功；新话题能引用旧话题的具体决定；原有回合/会话格式未变；JVM 单测覆盖原语。
- **判据**：做成 ⇒ 按本方案继续；做不成 ⇒ 停下来找用户重谈方向。
- **纪律**：不动发布版、不升版、不改会话格式；实验代码可回滚。

#### P-1 完成记录（2026-11-20 凌晨，已完成）

- 实际暴露 **6 个**原语：`session.fork` / `session.summarize` / `model.set` / `effort.set` /
  `topic.switch` / `topic.end`（P-1 用 `topic.switch` 取代了草案里的 `ui.navigate`）。
- 实现位置：`core:tools` 的 `primitives/PrimitiveTools.kt`（契约 + 工具面）、
  `core:prompt/.../tools-primitives.json`（独立 schema，不动上游 32 工具快照）、
  `AppViewModel.AppPrimitiveHost`（fork / compaction 摘要 / prefs / 导航 / 回收站）。
- **handoff 实际组合**：`session_summarize` → `topic_switch`（无 id，brief 作新话题第一条
  `user/message`，`source.kind="handoff"`）→ `topic_end`（无 id；当前轮在跑时延迟到轮次收尾）。
  `session.fork` 是独立分支原语，handoff 不需要它。
- 真机连续 3 次 handoff 成功（A→B→C），新话题都能只靠 brief 复述旧话题决定；旧话题进回收站；
  会话仍是 `session.v4`，**没有新增事件类型**。JVM 764 / 0。
- 证据：`docs/185-evidence/README.md`（未跟踪，不入库）。
- 两个 P-1 口径（后续沿用）：
  1. 6 个原语加入 `READ_ONLY_TOOLS`（只改会话/设置，不写工作区，避免 ask 模式每次交接弹 3 次审批）；
  2. `topic_end` 只进回收站、不真删；对当前话题延迟归档。

### P0 · Node 探针（1–2 天）

- `jniLibs/arm64-v8a/libnode.so`（bionic Node ≥24.9）+ `@deepseek-ai/dsh@0.2.2-rc.5`（npm 原样、零补丁）
  + 最小 JS（起 cordis、装 1 个真工具包）+ spawn + 回环 WS + `tool.invoke` 一次。
- 数字（真机 vivo PEMM00 / Android 13）：spawn→`host.hello` ≤1.5s（含真包 ≤2.5s）；RSS ≤180MB；
  主进程冷启三项（668/1297/1930）变化 ≤±5%；宿主关闭时内存与 0.3.5 一致（±3%）；
  **依赖树剪枝后体积逐项记账**。
- 不达标 → 把数字给用户，停下来重谈。

#### P0 完成记录（2026-11-20 凌晨，已完成）

- 真机达标：spawn→`host.hello` 冷启中位 **1.384s**（5 次 1.338–1.414s；热进程 1.146s），
  Node 宿主 RSS 中位 **73.3MB**；主进程冷启三项同机 A/B **−1.4% / −1.8% / −1.3%**；
  宿主关闭后 App RSS +0.41%（与 0.3.5 同机中位 −0.8%）。
- 跑的是 bionic **Node v26.4.0**（sha256 `A0D21A…D1E6`）+ **真 `@deepseek-ai/dsh@0.2.2-rc.5`**
  拆包（cordis / dsh-tools / session-projection / system-prompt / dsh-tool-todo，npm 原样零补丁）+
  回环 WS + 一次真 `todo_write` 的 `tool.invoke`。
- 体积：libnode 49.7MB + 依赖库 47.6MB + 剪枝 dsh 树 17.3MB（全量 764MB/42,653 文件 →
  2.27% 字节）→ APK +17.85MB（+15.0%）。
- P0 的 tool 执行上下文是最小 façade（没有 turn engine/agent loop），宿主也还没接正常启动路径；
  这两件留给 P2。证据：`docs/185-evidence/P0-node-probe.md`。

### P1 · 原语层正式化（1–2 周）

- 把 P-1 的 5 个扩到 15–25 个；**同一份定义**生成三方接口（AI 工具面 / 插件桥面 / 原生调用面）。
- 文档 + 单测 + 一张「能力→原语」映射表；明确"哪些能力暂时不能由原语组合"。
- 验收：用原语组合出 3 个复合能力（handoff + 一个自定义流程 + 一个插件），不改内核。

#### P1 完成记录（2026-11-20 凌晨，已完成）

- **20 个原语**（19 个 READY + `ui.openContainer` 如实标 P3），定义只在
  `core/primitives/.../PrimitiveCatalog.kt` 一处；工具面（`PrimitiveTool` + `PromptPack`）、
  插件面（`ctx.primitive.invoke/list`）、原生面（`PrimitiveHost.invoke`）都由它生成，
  不再有手写的 `tools-primitives.json` 与 6 个工具类。
- **扩展**：`session.list/open/read`、`model.list`、`permission.set`、`turn.send/stop/state`、
  `tool.list/call`、`store.get/set/list`；`session.fork/summarize`、`model.set`、`effort.set`、
  `topic.switch/end` 保留。插件桥 P1 只开 8 个只读原语（写原语等 P4 权限模型）。
- **真机**：一轮内严格按序 7 步（list → state → read → effort_set → summarize → switch → state）
  全部成功，`topic_switch` 新建话题并交接 brief，新话题只凭 brief 复述三条决定；
  一个 QuickJS 原生插件通过 `ctx.primitive` 调 `session.list`/`turn.state` 成功、
  `topic.switch` 被 `PERMISSION_DENIED` 拒绝。JVM **779 / 0**。
- **两个口径修正**（影响后续）：
  1. `session.summarize` 只总结**已完成轮次**，调用它的这一轮被排除（否则 brief 自指；
     P-1 的“复用 compaction 摘要”不变）；
  2. “当前话题” = **调用所属话题**（执行中轮次的话题）；模型在一轮里 `topic.switch` 后
     `turn.state` 仍返回旧话题，插件（无轮次）看到的是前台话题。这是有意的，已写进
     `docs/186` §五。
- **边界**：插件桥只验常驻 QuickJS（工具路径未挂桥，P2）；`ui.openContainer` 只是 P3 登记；
  `session.open/turn.send/stop/tool.call/store.*/permission.set` 只有 JVM 用例，未真机。
- 文档：`docs/186-原语层-单一定义与三面接口-能力映射.md`；证据：
  `docs/185-evidence/P1-primitives.md`。未升版、未打 tag、未动发布树、会话仍 `session.v4`。

### P2 · Node 宿主全链路

- 生命周期（spawn/超时/崩溃 ≤3 次重启/stop/进程组清理）、`EXTRACTING` 进度、首次解压。
- 桥四种 op 全链路；审批、审计（`host:"node"`）；工具表合并/冲突/超时/取消。
- 验收：一个真实社区工具插件跑通；kill 宿主后自动恢复；JVM 覆盖四种异常。

### P3 · 宿主能力扩展

- 提示词段 → 模型供应商 → 命令 → **预设**（`preset.apply`）→ **3 种容器**（`ui.openContainer`）。
- **不做**：会话流插槽、逐类界面贡献镜像（D9）。
- 验收：`docs/97` 矩阵里能跑的 3 个 web 插件，至少 2 个能在容器里正常显示并交互。

### P4 · 导入与 UI

- 安装页识别归属 + 可改；插件页两分区；`IDLE/STARTING` 文案；错误与日志入口。
- 验收：真机截图；关宿主原生插件仍在。

### P5 · 收口

- 内存/耗电（前后台两组）、ABI 分包（arm64 必带，x86_64 可选）、发布前按 `docs/184` 流程重跑。
- **升版/tag/Release 仍需用户单独确认。**

---

## 十、测试与证据

- JVM：原语契约、组合、桥编解码、合并/冲突、超时/崩溃降级、分流规则。
- 真机：P-1 交接两次、P0 数字、P2 全链路、P3 两个 web 面、P5 内存耗电。
- 证据目录 `docs/185-evidence/`（未跟踪，不入库）：logcat、`dsh_dump_*`、截图、RSS 采样。
- 禁止：无真机数据写"真机通过"；用 sleep 轮询等状态。

---

## 十一、代价与风险（诚实版）

| 项 | 量级 | 说明 |
|---|---|---|
| APK 体积 | 剪枝后待实测（全量依赖树 463MB / 304 包未压缩） | 不实测不许对外说数字 |
| 首次解压 | 1–2s（一次性） | 放在 T+1.9s 之后，显示进度 |
| 宿主常驻内存 | +50~120MB（装真包 ≤180MB） | 低端机紧张；可自动降级 |
| 首次启用插件等待 | 0.5~1.5s（可后台预热） | 用户可感知一次 |
| 自由度上界 | 原语集合 + 真包覆盖 | **永远不等于 dsh**：界面型插件缺扩展点、桌面/原生 helper 类插件跑不了 |
| 长期维护 | 上游升级要重跑插件矩阵 | 零补丁降低了代价，但没有归零 |

**回滚**：`node_plugin_host_enabled` 开关（首版发布建议默认关、标"实验"），关掉即回 0.3.5，无数据迁移。

---

## 十二、开放问题（2026-11-19 23:5x 已收敛大半）

1. ~~P-1 现在做还是搁置~~ → **用户「都可以」**。建议 P-1 优先（能证伪方案），交接里写明"开工前问一句"。
2. Node 宿主首版发布默认开还是关 → 暂定**默认关 + 标实验**，P5 前再确认。
3. 路径 B（dsh 界面模式）是否永远不做 → 暂**不做**，留文档不实现。
4. ~~③「让 AI 适配插件」的入口~~ → **手动流程，不做 UI 按钮**（已写进 §7.5 ③）。

---

## 十三、给下一个 AI 的交接

**读的顺序**：本文 → `docs/184`（现状）→ `docs/05` §二（被推翻的前提）→ `docs/128`（旧兼容层上限）
→ `docs/97`（社区插件矩阵，验收样本）→ `D:\dsh\github-official\src\deepseek-harness-android-app-main\docs\开发指南.md`
（node-on-Android 踩坑）→ 同目录 `dsh-patches\README.md`（**旧项目 4 处源码补丁，我们要绕开**）。
**基线**：`@deepseek-ai/dsh@0.2.2-rc.5` + bionic Node ≥24.9，零补丁。

**第一步**：等用户对 §十二.1 拍板。若同意 → 做 P-1（ABI 实验），从 `session.fork` 开始。
**不要**先动 `PluginModule.kt`，不要先改 UI，不要先起 node。

**红线**：
- 只在用户明确要求时改代码；每阶段验收数字先给用户看再进下一阶段。
- 不构建发布包、不升版、不打 tag、不动 `D:\dsh\native-publish`。
- 不卸 `com.deepseek.harness`；不改系统设置；不清理 `docs/181/182/183/184-evidence`。
- 不改会话格式；测试禁 sleep 轮询；中文字符串只用 read/edit/write 改。
- 未测项不写"真机通过"；设备 prefs 含真实 key，不读不输出不提交。
- **删除原生插件体系是禁止项**（D5）；**默默给 dsh 打补丁是禁止项**（D7）。

**固定验收命令**（本轮未运行）：
```
.\.tools\gradle-8.9\bin\gradle.bat --console=plain :app:assembleDebug :app:testDebugUnitTest \
  :core:engine:test :core:plugins:test :core:session:test :core:attachment:testDebugUnitTest :support:testDebugUnitTest
```
（`core:tools`、`core:prompt` 另跑；JAVA_HOME=`C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot`。）

---

## 附录 A · 讨论过程要点（2026-11-19 22:5x–23:5x）

1. 起点：鲸歌是 DSH 原生移植版，但社区插件导不进来，因为"太原生、没有 Node"。
2. 否决甲：核心仍是 dsh 就不算原生移植，和社区项目没区别。
3. 否决纯兼容层加强：追不完上游。
4. 敲定：node 塞进去、不重构内核、页面可用后「继续」启动、两套体系共存、装真包、零补丁跟上游。
5. 用户追问「这样不是真 dsh 样子吧」→ 承认：功能真、环境半真、界面不真。
6. 用户追问「没有两全吗」→ 给出 A/B/C 三条界面路径。
7. 用户提出「方块 vs 橡皮泥」：方块再细也改不了，剁碎不收敛。
8. 用户否决「方块 + 泥接口」：handoff 这类组合能力在鲸歌里做不出来，因为东西是死的。
9. 收敛：问题不在"方块"，在**没有 ABI**；Android 也是方块但 App 是泥，因为方块有 API。
10. 用户接受「剁得足够碎也算泥」→ 定调：把边界剁成有界原语（§四），第一个样本是 handoff（P-1）。
11. 用户追问「预设/悬浮窗/单独页面怎么接原生」→ 我给 10 类落点表 → 用户当场否决「还是无底洞啊」。
12. 收敛为 D9/D10：**界面只给 3 个容器**（不镜像贡献类型），长尾按降级链处理。
    洞的封法不是"剁更细"，是"不翻译，只给容器"。
13. 用户再次修正 D10：「不是改造成原生版，还是 node，只是鲸歌适配的 node；node 的一些环境不要自己做，搬 dsh。」
    → 降级链改为「真跑 → 搬 dsh 真环境 → AI 调插件自身 → 不支持」，**禁止**把 dsh 插件翻译成 Kotlin 插件。

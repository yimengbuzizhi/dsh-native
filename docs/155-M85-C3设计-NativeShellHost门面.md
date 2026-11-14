# 155 · M85-C3 设计：`NativeShellHost` 门面（拆 `ui:native-shell` 模块前必须的一步）

> 2026-11-14 00:3x。承接 `docs/153`（C2：页面所有权归壳）。本文件是 `docs/152` §3 第 2 条
> 「物理模块化准备」的**设计**，写完这一份再动代码。不改行为、不动 UI 实现。

---

## 1. 为什么需要门面（现状测量）

C2 之后，22 个页面组件已经**只接数据和回调 lambda**（`ChatScreen(state=..., onSubmit=...)`），
不直接依赖 `AppViewModel`。真正还直接拿 `AppViewModel` 的是壳自己：

| 文件 | 直接用的 `vm.*` 成员数 |
|---|---|
| `ui/shell/native/NativeShellRoot.kt` + `native/pages/NativePageDispatch.kt` + `shell/DshDrawer.kt` | **109** |
| `ui/App.kt`（选壳/系统栏）+ `ui/surface/PluginShellScreen.kt`（可选壳宿主） | 19 |
| `ui/chat`、`ui/settings`、`ui/plugins`… 22 页组件 | **0**（只接数据 + 回调 lambda） |

也就是说：**页面边界已经干净了，壳和宿主的状态/动作边界还没切**。
把 UI 搬进独立 Gradle 模块（目标 `ui:native-shell`，宿主只依赖 `shell/v1`）之前，必须把
「壳拿到的那个大对象」换成一个**接口**，否则模块依赖会反向指回 `app`：

```text
现在：ui/shell/** ──直接依赖──> chat.AppViewModel ──依赖──> data/core/app 全部实现
目标：ui:native-shell ──依赖──> ui:shell-api(NativeShellHost) <──实现── AppViewModel
```

---

## 2. 门面形状：v1 先做一个**扁平接口**，分组留到搬模块时

> 实现前复核（2026-11-14 00:5x）：109 个成员里绝大多数是「只读状态 + 动作」，
> 分组会逼着 `NativeShellRoot`/`NativePageDispatch`/`DshDrawer` 里 **109 处调用点**
> 从 `vm.submit(...)` 改成 `host.chat.submit(...)`，diff 巨大且纯机械。
> 所以 C3-1 先做扁平 `NativeShellHost`（调用点只换类型、不换名字），分组推迟到 C3-2/C3-3
> 真正搬模块、需要给第三方 native 壳收窄面的时候再做；届时用 `typealias`/子接口渐进迁移。

```kotlin
/** M85-C3：壳与宿主之间唯一的界面服务面（`ui/shell/**` 只依赖它，不依赖 AppViewModel）。 */
interface NativeShellHost {
    // ── 聊天 ─────────────────────────────────────────────
    val chat: ChatSessionState?
    fun submit(text: String): Boolean
    fun stop()
    ...
    // ── 会话 ─────────────────────────────────────────────
    val sessions: List<SessionSummary>
    fun openSession(summary: SessionSummary)
    fun newChat()
    ...
    // ── 工作区 / 设置 / 插件 / 设备 / 壳 / 调试 ─────────────
}
```

规则：

1. **只读 + 动作，不暴露实现**：接口里不出现 `AppViewModel`、`Application`；
   `File`/`String` 路径可以（页面已经这么用）。
2. **Compose 状态语义保留**：`var chat by mutableStateOf(...) private set` 在接口里声明成
   `val chat: ChatSessionState?`（实现类 `var` 可直接满足 `val`），读它仍触发重组；
   不复制快照、不每帧拷贝大列表。
3. **不解释业务**：门面只转发，不做规则判断；`builtinScreenEnabled` 这类宿主已有判断原样转发。
4. **page 注册表单独**：`ShellPage`/`ShellPageRegistry`（C2 已有）**不进** `NativeShellHost`；
   它属于 `shell/v1` + `page/v1` 契约，模块里和 `NativeShell` 同包。
5. **实现代价**：`AppViewModel` 实现接口时，109 个成员都要加 `override`（机械改动，编译期可验）；
   `createWorkspace`/`resolveApproval` 的默认参数要从实现类搬到接口（Kotlin 不允许 override 带默认值）。

---

## 3. 迁移顺序（每步都可真机回退，每步一个 commit）

| 步骤 | 内容 | 真机验收 |
|---|---|---|
| **C3-1** | 新建 `ui/shell/NativeShellHost.kt`（扁平接口，109 个成员 + 注释分区），`AppViewModel` 实现它（成员加 `override`；`createWorkspace`/`resolveApproval` 默认参数搬到接口），`ui/shell/**` 三处签名 `vm: AppViewModel` → `vm: NativeShellHost`（参数名保留 `vm`，调用点不变）。**行为零改动** | 默认 UI/发消息/抽屉/22 页/返回/停用壳→安全模式；无 FATAL |
| **C3-2** | 把 `NativeShellHost` + 子接口 + 它们用到的状态类型搬进新 Android 库模块 **`ui:shell-api`**（`ShellChatHost` 等）。要搬的类型：`ChatSessionState`（纯 Compose + core 模型）、`WorkspaceUi`、`BuiltinPluginUi`、`JobUi`/`SubagentUi`/`WaitingSession`、`PluginPageEntry`/`PluginPageTarget`、`ShellPickRequest`、`RemoteDshController` 的**只读接口**；`SettingsStore` 依赖 `SharedPreferences`，v1 允许 `ui:shell-api` 是 Android 库直接包含它（以后再按需抽接口）。`app` 依赖 `ui:shell-api`，`AppViewModel` 实现不动的成员 | 同 C3-1 + 142/142（`tools/m84b-page-check.py`） |
| **C3-3** | 建 **`ui:native-shell`** 模块，按组搬源码：① `ui/chat` → ② `ui/shell`（抽屉/壳/页面清单+dispatch）→ ③ `ui/settings`/`ui/plugins`/`ui/sessions`/… 各一级页 → ④ `ui/onboarding`。每搬一组：`app` 的 `DshApp` 只 import `ui:shell-api` 的 `NativeShells`；真机冒烟 + 页面路由日志 | 每组一次默认 UI + 该组页面截图；模块边界扫描：`app` 不再 import `xin.dshaa.nativeapp.ui.*` |
| **C3-4** | `page/v1` 完整版：M84-G 插件整页（`pluginPageFor`/`pluginPageEntries`）与壳声明页面并进 `ShellPageRegistry`（带渲染 lambda，插件页渲染仍由宿主 `pluginui` 提供），抽屉/调试通道只读注册表 | 插件整页进抽屉/同 id 替换内置页/停用恢复 |
| **C3-5** | C 收口：M84-B **142/142** 等价回归 + 源码扫描（宿主无具体页面 import）+ 停用内置 UI 插件安全模式 + 同 id 接管演示 | `docs/m84b-check-*.json` |

依赖关系：C3-1 不做完不能拆模块；C3-2 可以先把类型搬走再搬源码（避免一次动两个变量）；
C3-4 可以在 C3-3 之前或之后，互不阻塞（但都在 142/142 之前）。

### 3.1 C3-2 类型搬迁清单（C3-1 落地后复核，2026-11-14 00:5x）

`NativeShellHost` 现在引用这些**宿主状态类型**，它们要么搬进 `ui:shell-api`，要么在
`ui:shell-api` 里加只读接口让实现类实现：

| 类型（现状位置） | 被引用文件数 | 搬迁要点 |
|---|---|---|
| `chat.ChatSessionState` | 8 | 依赖 Compose + `core.model` + `TextFieldValue`，纯状态类，直接搬 |
| `chat.JobUi` / `chat.SubagentUi` / `chat.WaitingSession` / `chat.SessionBadge` | 4/4/4/6 | 纯数据类，随 `ChatSessionState` 一起搬（同一 `chat` 包内互相引用） |
| `data.SettingsStore` | 10 | 31KB，只依赖 `SharedPreferences` + Compose；搬进 Android 库模块可行（v1 不抽接口） |
| `data.WorkspaceUi` | 5 | 纯数据类，直接搬 |
| `data.BuiltinPluginUi` | 3 | 持有 `BuiltinPlugin` → 把 `BuiltinPlugin`/`BuiltinPluginKind` 数据类拆到 shell-api，**`BuiltinPlugins` 目录 + `BuiltinPluginStore` 留在 app**（依赖 `BuiltinPageIds`/工具清单） |
| `data.OperitBundledPackage` | 4 | 纯数据类，直接搬 |
| `remote.RemoteDshController` | 4 | 24KB，依赖 WebSocket/Compose/通知；C3-2 先给 `ui:shell-api` 加**只读接口 `ShellRemote`**（`RemoteDshScreen` 用到的成员），让 `RemoteDshController : ShellRemote`，避免搬整类 |
| `ui.shell.PluginPages`（`PluginPageEntry`/`PluginPageTarget`） | 2 | 只依赖 `core.plugins`，**直接搬**（C3-1 已从 VM 搬出） |
| `ui.shell.ShellPage`/`PageLevel`/`NativeShell` | — | 直接搬；`ShellPageRegistry` 依赖 app 的 `BuiltinPlugins`/`BuiltinPageIds`，**留在 app** 或把 `BuiltinPageIds`（纯字符串）一起搬 |

**做法**：新建 `:ui:shell-api`（Android library + compose 插件，照 `ui/design/build.gradle.kts`），
搬文件 → 改包名 → 全仓改 import（用脚本 + 编译期查漏）→ `app` 加 `implementation(project(":ui:shell-api"))`；
先只搬契约 + 状态类型（`ui/shell/**` 仍留在 app），编译过 + 真机冒烟 + commit，
再进入 C3-3 搬 UI 源码。**注意**：同名包内跨包引用不会自动加 import（`chat/*.kt` 引用
`ChatSessionState` 原本同包），搬走后要逐个补 import。

---

## 4. 风险与对策

| 风险 | 对策 |
|---|---|
| 109 个成员的接口手写出错 | 用真机/编译双保险：接口声明按现有签名逐条抄（`val`/`var`/`fun` 原样），编译不过立刻暴露；行为零改动，冒烟即可 |
| Compose 状态被接口「快照化」导致不重组 | 接口只暴露状态对象本身（`ChatSessionState`/`SettingsStore`/`List` 保持 `mutableStateOf` 实例），不 expose 拷贝 |
| 类型搬模块时循环依赖（app ↔ core ↔ ui） | 顺序：`core`（纯 Kotlin）→ `ui:shell-api`（Android+Compose，仅依赖 core）→ `ui:native-shell`（依赖 shell-api + design）→ `app`（依赖三个） |
| 资源/`R` 引用 | 现有 `ui:design` 模式；壳模块自身资源用自己的 `R`，宿主资源通过参数传（已有 `assistantAvatar` 等先例） |
| 门面变成「第二个 ViewModel」 | 纪律：接口里只转发，不加业务逻辑；分组接口总数控制在 8 个以内，成员多了先拆页面不拆门面 |
| 拆模块导致 142/142 回归成本 | 每组一次冒烟 + C3-5 跑一次全量；脚本 `tools/m84b-page-check.py` 已验证可跑（`docs/153` §2 同类路径） |

---

## 5. 明确不做

- 不重写 UI、不改布局/交互；不把 `AppViewModel` 的业务逻辑搬进门面；
- 不动 Surface 壳（`PluginShellScreen` 继续由宿主渲染，`pluginui` 保持宿主侧）；
- 不做 DEX 动态加载（`docs/144` §10）；
- 不在本设计里决定多模块发布（`ui:shell-api` 先做本地模块，随 app 一起打包）。

## 6. 给下一棒的最短路径

1. 读 `docs/153` §2/§3 + 本文件 §2/§3；
2. 做 **C3-1**：`ui/shell/NativeShellHost.kt` + `AppViewModel` 实现 + 三处签名替换；
3. 构建 → 装机 → 冒烟（默认 UI/发一条消息/抽屉/设置/插件页/停用壳→安全模式）→ commit；
4. 再按 §3 表继续 C3-2。

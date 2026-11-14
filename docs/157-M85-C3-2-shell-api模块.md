# 157 · M85-C3-2：契约与状态类型搬进 `ui:shell-api`

> 2026-11-14 01:0x。按 `docs/155` §3/§3.1 做 C3-2：新建 `:ui:shell-api` Android 库模块，把
> `NativeShellHost` 门面和它引用的状态类型**物理搬出 app**，为 C3-3 搬 `ui:native-shell` 清掉反向依赖。
> 行为零改动：UI 一行没动，`AppViewModel` 的 109 个门面成员签名不变。
> 真机：OPPO A55 5G（PEMM00 / 720×1600 / Android 13），无线调试 `192.0.2.10:45111`。

---

## 1. 这一片做了什么

### 1.1 新模块 `:ui:shell-api`

- 目录 `ui/shell-api/`，Gradle 路径 `:ui:shell-api`，照 `ui/design`：`com.android.library` +
  `kotlin.android` + `kotlin.plugin.compose`，`namespace = "xin.dshaa.ui.shellapi"`，
  compileSdk 34 / minSdk 24 / JVM 17，`buildFeatures.compose = true`；
- 依赖只有 **core + Compose**：`api` -> `core:model`/`session`/`plugins`/`skill`/`tools`/`input`/`jobs`/`agents`；
  `core:session` 显式 `exclude(group = "com.github.luben")`，zstd 仍由 app 用带 `.so` 的 AAR 统一提供；
- `app` 加 `implementation(project(":ui:shell-api"))`；`settings.gradle.kts` 加 include。

### 1.2 搬走的文件/类型（包名统一到 `xin.dshaa.ui.shellapi.*`）

| 新位置 | 类型 | 说明 |
|---|---|---|
| `shellapi/` | `NativeShellHost` | C3-1 的 109 成员扁平门面，原样搬 |
| `shellapi/` | `NativeShell`、`ShellPage`、`PageLevel` | `shell/v1` 契约；`NativeShells` 注册表留 app |
| `shellapi/` | `PluginPageEntry`、`PluginPageTarget` | 页面插件类型，原样搬 |
| `shellapi/chat/` | `ChatSessionState`（含 `PendingImage`/`PendingFile`/`CommandSheet`/`SessionBadge`/`PendingApproval`/`WaitingSession`/`ATTACHMENT_REFERENCE_HINT`） | 整个文件搬 |
| `shellapi/chat/` | `JobUi`/`buildJobRows`/`formatJobDuration`/`formatJobBytes` | 整个文件搬 |
| `shellapi/chat/` | `SubagentUi`/`mergeSubagents` | 整个文件搬 |
| `shellapi/data/` | `SettingsStore`、`AssistantProfile` | 整个文件搬（v1 不抽接口；`AssistantProfile` 是 `SettingsStore` 的公开类型） |
| `shellapi/data/` | `WorkspaceUi` | 从 `WorkspaceStore.kt` 抽出；`WorkspaceEntry`/`WorkspaceStore` 留 app |
| `shellapi/data/` | `BuiltinPlugin`/`BuiltinPluginKind`/`BuiltinPluginUi` | 从 `BuiltinPlugins.kt` 抽出；`BuiltinPlugins` 目录与 `BuiltinPluginStore` 留 app |
| `shellapi/data/` | `OperitBundledPackage`/`OperitStatus` | 从 `OperitBundledPackages.kt` 抽出；目录类留 app |
| `shellapi/remote/` | `ShellRemote`（新只读接口） | `RemoteDshScreen` 用到的 22 个状态/动作 |
| `shellapi/remote/` | `RemotePhase`/`RemoteSession`/`RemoteAskOption`/`RemoteAskQuestion`/`RemoteToolCard`/`RemoteRole`/`RemoteChatMessage`/`RemoteChatState` | 远程纯数据类，从 `RemoteDsh.kt` 抽出 |

**仍留在 app 的**：`NativeShells`（注册 `BuiltinNativeShell`，依赖 `BuiltinPlugins`/`BuiltinPageIds`）、
`ShellPageRegistry`（汇总/校验）、`BuiltinPlugins` 目录、`BuiltinPluginStore`、`WorkspaceStore`、
`OperitBundledPackages` 目录、`RemoteDshClient`/`RemoteDshController` 实现。
`SafeModeScreen` 继续拿 `AppViewModel`（宿主兜底，按设计保留）。

### 1.3 `ShellRemote`：不搬 24KB 的远程控制器

`RemoteDshController : ShellRemote`，给 22 个成员加 `override`（9 个 `var ... private set` 状态 +
13 个动作）；`NativeShellHost.remote` 类型从 `RemoteDshController` 改为 `ShellRemote`，
`RemoteDshScreen`/`RemoteConnectPane`/`RemoteChatPane` 也改收 `ShellRemote`——**编译期证明接口够用**。
`RemoteDshController.relativeTime`（companion 里的会话相对时间）随之挪到 `RemoteDshScreen` 的私有
top-level 函数，行为/文案不变（避免 `java.time` 在 shell-api 里触发 desugaring 配置问题）。

### 1.4 搬迁做法与坑

1. **批量搬文件 + 改包名**用 Python（显式 UTF-8、保留 LF）：`app/chat|data|remote|ui/shell` → `ui/shell-api`；
   抽类型时按声明边界切片，原文件其余部分不动；
2. **全仓 import/FQN 修正**：脚本把旧全限定名按类型名右边界替换（避免 `BuiltinPlugin` 污染
   `BuiltinPlugins`/`BuiltinPluginStore`、`ShellPage` 污染 `ShellPageRegistry`），再给同包引用补 import。
   **坑**：搬走后同包引用不会自动加 import（`chat/AppViewModel.kt` 一次补了 12 个）；
3. **`ShellRemote` 的 override** 与 C3-1 的 `NativeShellHost` 一样用脚本插 `override`，编译期验证 22/22；
4. 构建一次过（除 override）——`:ui:shell-api:compileDebugKotlin` 与 `:app:compileDebugKotlin` 0 error。

---

## 2. 真机验收

| # | 场景 | 结果 | 证据 |
|---|---|---|---|
| 1 | 默认聊天页（冷启） | 原界面不变：标题/抽屉/输入条/模型 pill；`route page=Chat sub=-` | `m85c3-2-10-shellapi-default.png` |
| 2 | 抽屉状态（跨模块读 Compose 状态仍重组） | 117 个会话、130 工具、默认工作区/助手列表正常 | `m85c3-2-11-shellapi-drawer.png` |
| 3 | 二级页 | `dsh_open_screen modelconfig` -> 模型与参数配置（`page=Settings sub=ModelConfig`） | `m85c3-2-12-shellapi-modelconfig.png` |
| 4 | 一级页 | `dsh_open_screen plugins` -> 插件页（`page=Plugins`，内置 24） | `m85c3-2-13-shellapi-plugins.png` |
| 5 | 页面注册表 | `dsh_dump_pages`：22 页、`catalogMatch=true`、`unowned=[Plugins,Settings]`、unknown/duplicates/disabled 空 | `docs/m85c3-2-pages.json` |
| 6 | 全量等价回归 | **M84-B 142/142 PASS**（142 条全 PASS，0 FAIL） | `docs/m84b-check-m85c3.json`、`docs/m85c3-2-check.txt` |
| 7 | 无崩溃 | 以上流程 + 回归脚本全程无 `FATAL` | logcat |
| 8 | 单元测试 | `:core:plugins:test` 强制重跑 **121/121**（0 fail / 0 error） | `core/plugins/build/test-results/test` |

构建：`:app:assembleDebug` **BUILD SUCCESSFUL**（debug APK 118,815,733 B 装机）。
设备恢复：`dsh_shell=native`、`onboarding_done=true`、页面插件全开、无障碍 **Bound**。

---

## 3. 没做 / 下一片

1. **C3-3**：建 `ui:native-shell` 模块，按组搬 UI 源码（`ui/chat` → `ui/shell`（抽屉/壳/页面清单+dispatch）
   → 各一级页 → `ui/onboarding`）；每组真机验收 + 源码扫描（`app` 不再 import `ui.*` 页面）。
   到那一步再把 `NativeShells` 注册表/`BuiltinNativeShell` 的装配点从 app 迁走。
2. **C3-4**：`page/v1` 完整版（M84-G 插件整页并进 `ShellPageRegistry`）。
3. **C3-5**：C 收口（142/142 + 三终点回归 + 停用内置壳安全模式 / 同 id 接管演示）。
4. 本片 `ui/shell/**` 源码仍在 app（按计划只搬契约+状态类型）；`SafeModeScreen` 仍依赖 `AppViewModel`。

## 4. 文件

- 新增模块：`ui/shell-api/build.gradle.kts` + `ui/shell-api/src/main/java/xin/dshaa/ui/shellapi/**`（14 个 .kt）
- 修改：`settings.gradle.kts`、`app/build.gradle.kts`（依赖 + include）
- 修改：`chat/AppViewModel.kt`、`data/BuiltinPlugins.kt`、`data/OperitBundledPackages.kt`、
  `data/WorkspaceStore.kt`、`remote/RemoteDsh.kt`、`ui/remote/RemoteDshScreen.kt`、
  `ui/shell/{NativeShell,ShellPages}.kt` 及全仓 import 更新
- 删除：`chat/{ChatSessionState,JobUi,SubagentUi}.kt`、`data/{SettingsStore,AssistantProfile}.kt`、
  `ui/shell/{NativeShellHost,PluginPages}.kt`（内容搬进模块）
- 证据：`docs/m84b-check-m85c3.json`、`docs/m85c3-2-pages.json`、`docs/shots/m85c3-2-{10..14}-shellapi-*.png`
- 设计：`docs/155`；上一片：`docs/156`

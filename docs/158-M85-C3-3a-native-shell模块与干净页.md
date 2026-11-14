# 158 · M85-C3-3a：`ui:native-shell` 模块落地（第一批干净页）

> 2026-11-14 07:2x。按 `docs/155` §3 C3-3 开始把 UI 源码搬出 app。本片先建模块、搬**不依赖 app 私有类**的
> 7 个页面目录；壳（抽屉/路由/页面清单）与聊天页仍留 app，等下一片处理依赖后再搬。
> 真机：OPPO A55 5G（PEMM00 / 720×1600 / Android 13），无线调试 `192.0.2.10:45111`。

---

## 1. 这一片做了什么

### 1.1 新模块 `:ui:native-shell`

- `ui/native-shell/`，Gradle 路径 `:ui:native-shell`，`com.android.library` + kotlin + compose，
  `namespace = "xin.dshaa.ui.nativeshell"`，compileSdk 34 / **minSdk 26**（terminal PTY 要求）/ JVM 17；
- 依赖：Compose BOM、`ui:design`、`ui:shell-api`、`core:model/plugins/jobs/session`、`thirdparty:terminal`、
  activity-compose / core-ktx / kotlinx-serialization；`core:session` 同样 `exclude(group = "com.github.luben")`
  （否则 zstd jar 与 app 的 AAR 重复，`checkDebugDuplicateClasses` 会挂）；
- `app` 加 `implementation(project(":ui:native-shell"))`；`settings.gradle.kts` include。

### 1.2 搬走的第一批（7 个目录 + 1 个小组件）

| 目录 | 页面 | 说明 |
|---|---|---|
| `ui/plugins` | Plugins | 插件列表/详情/配置/能力清单 |
| `ui/sessions` | Sessions | 会话列表/回收站 |
| `ui/agents` | Agents | 子代理面板 |
| `ui/jobs` | Jobs | 后台任务面板 |
| `ui/remote` | RemoteDsh | 远程 DSH 页（只依赖 `ShellRemote` + shellapi 远程类型） |
| `ui/workspace` | Workspace | 工作区文件浏览 |
| `ui/terminal` | Terminal | 终端/环境配置（`thirdparty:terminal`） |
| `ui/shell/SessionBadgeMark.kt` | — | 会话状态点，sessions 与抽屉共用 |

**做法**：文件原样移动，**包名暂不改**（仍是 `xin.dshaa.nativeapp.ui.*`），这样 app 里的
`NativePageDispatch` 的 import 一行都不用动；等 C3-3 全部搬完、`NativePageDispatch` 进模块后，
app 里自然不再有 `xin.dshaa.nativeapp.ui.*` 的 import。

### 1.3 本片遇到的可见性/依赖问题

1. `ui/packages/PackagesScreen.kt`（还未搬）用 `ui/plugins/PluginsScreen.kt` 的
   `internal fun PluginDetailDialog`；跨模块后 `internal` 不可见 → 改成 `fun`（同模块内调用点不变）；
2. `ui/sessions/SessionsScreen.kt` 引用 `ui/shell/SessionBadgeMark`（壳目录还在 app）→ 把
   `SessionBadgeMark.kt` 一起搬进 native-shell（同包 `ui.shell`，app 的 DshDrawer 仍能解析）；
3. 模块的 `core:session` 必须排除 zstd jar（同 shell-api），否则打包重复类。

---

## 2. 真机验收

| # | 场景 | 结果 | 证据 |
|---|---|---|---|
| 1 | 冷启默认聊天页 | `route page=Chat sub=-`，原界面 | logcat |
| 2 | Plugins | 插件页正常（内置 24 / 本地 37 / 已接入 72） | `m85c3-3a-Plugins.png` |
| 3 | Sessions | 会话列表正常 | `m85c3-3a-Sessions.png` |
| 4 | Jobs | 任务面板正常 | `m85c3-3a-Jobs.png` |
| 5 | Agents | 子代理面板正常 | `m85c3-3a-Agents.png` |
| 6 | RemoteDsh | `page=Settings sub=RemoteDsh`，连接页正常 | `m85c3-3a-RemoteDsh.png` |
| 7 | Workspace | 工作区文件页正常 | `m85c3-3a-Workspace.png` |
| 8 | Terminal | `page=Terminal`，环境配置页正常 | `m85c3-3a-Terminal.png` |
| 9 | 页面注册表 | `dsh_dump_pages` 22 页、`catalogMatch=true`、unowned/unknown/duplicates/disabled 同基线 | `docs/m85c3-3a-pages.json` |
| 10 | 无崩溃 | 以上流程无 `FATAL` | logcat |

构建：`:app:assembleDebug` **BUILD SUCCESSFUL**；设备恢复 `dsh_shell=native`、无障碍 Bound。

---

## 3. 没做 / 下一片

C3-3 剩下的 UI 都有 app 私有依赖，不能纯移动，需要先处理依赖：

1. **C3-3b 聊天页**（`ui/chat` 7 文件 268KB）：依赖 `ChatMirror`、`BuiltinPageIds`（纯常量）、
   `McpConfigStore`/`McpServer`、`AttachmentCapture`/`LocationCapture`。计划：
   `BuiltinPageIds`/`McpServer` 进 shell-api；`McpConfigStore`、`AttachmentCapture`/`LocationCapture`、
   `ChatMirror` 移到 native-shell（或抽 host 回调），再搬 `ui/chat`。
2. **C3-3c 壳**（`ui/shell` 抽屉/`NativeShellRoot`/`NativePages`/`NativePageDispatch` 等）：
   需要 ① 插件整页渲染改成 host 传入的 `@Composable` 回调（`PluginPageScreen` 留 app）；
   ② `NativeShells` 注册表搬 shell-api + native-shell 通过 ServiceLoader 注册 `BuiltinNativeShell`，
   否则 app↔native-shell 循环。`SafeModeScreen` 留 app。
3. **C3-3d 设置/包管理/设备页/onboarding**：`BuildConfig`（版本号改参数）、`BackupManager`/`GitHubAuth`、
   `BundledSkills`/`Mcp*`、`device.*`（DeviceShell/Shower 虚拟屏/AttachmentCapture/A11y/DevicePermissions）
   需要下沉到共享模块或抽接口。
4. **C3-4**：`page/v1` 完整版；**C3-5**：C 收口（142/142 + 三终点 + 停用壳/同 id 接管）。

## 4. 文件

- 新增模块：`ui/native-shell/build.gradle.kts` + `ui/native-shell/src/main/java/xin/dshaa/nativeapp/ui/**`
- 修改：`settings.gradle.kts`、`app/build.gradle.kts`
- 移动：`ui/{plugins,sessions,agents,jobs,remote,workspace,terminal}/**`、
  `ui/shell/SessionBadgeMark.kt`
- 可见性：`ui/plugins/PluginsScreen.kt` 的 `PluginDetailDialog` 改 public
- 证据：`docs/m85c3-3a-pages.json`、`docs/shots/m85c3-3a-*.png`

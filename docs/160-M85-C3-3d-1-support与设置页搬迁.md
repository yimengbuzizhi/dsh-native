# 160 · M85-C3-3d-1：`:support` 共享模块 + 设置/包管理/工作区页搬入 native-shell

> 2026-11-14 07:2x。C3-3 第三批：先把 UI 依赖的宿主服务抽到共享模块 `:support`，再搬
> `ui/settings`（11 文件）、`ui/packages`、`ui/workspaces`。壳/抽屉/页面 dispatch 仍留 app（下一批）。
> 真机：OPPO A55 5G（PEMM00 / 720×1600 / Android 13）。

---

## 1. 为什么先建 `:support`

设置页不是「纯 UI」：`BackupScreen` 用 `BackupManager`、`SettingsScreen`/`GitHubLoginDialog` 用
`GitHubAuth`、`PackagesScreen` 用 `BundledSkills`、`WorkspacesScreen`/`WorkspacePicker` 用
`DevicePermissions`。这些类 app 与 UI 都要用，放在 app 会让 native-shell 反向依赖 app。
所以新建 Android 库模块 **`:support`**（namespace `xin.dshaa.support`，minSdk 26），只放
「宿主与 UI 共用的 Android 服务」，两边都依赖它；包名保持 `xin.dshaa.nativeapp.*` 不变，
app 侧 import 一行都不用改。

搬进 `:support`：
- `data/BackupManager.kt`（导出/恢复清单；新增构造参数 `appVersion`，不再读 app BuildConfig）
- `data/GitHubAuth.kt`
- `data/BundledSkills.kt`
- `device/DevicePermissions.kt`
- `device/a11y/DshAccessibilityService.kt`（服务类随模块走，manifest 里的
  `xin.dshaa.nativeapp/.device.a11y.DshAccessibilityService` 仍解析到同一个 FQN；装后 Bound=1 已验）

模块依赖：`core:model/session/plugins/backup`、Compose runtime、core-ktx、serialization、Shizuku api。

## 2. BuildConfig 依赖切断

`AboutScreen`/`SettingsScreen`/`BackupScreen` 原来直接读 app 的 `BuildConfig`。改成参数：
- `AboutScreen(onBack, versionName = "", versionCode = 0)`
- `SettingsScreen(..., versionName = "")`
- `BackupScreen(..., appVersion = "")`

`NativePageDispatch`（仍在 app）调用时传 `BuildConfig.VERSION_NAME` / `VERSION_CODE`。
真机「关于」页显示 **0.3.2（versionCode 5）**，与 app 一致。

## 3. 搬走的文件

- `ui/settings/` 11 文件：Settings / ModelConfig / Assistant / Behavior / Privacy / Backup /
  DataMigration / Skills / Workspaces / About / GitHubLoginDialog
- `ui/packages/PackagesScreen.kt`
- `ui/workspaces/WorkspacePicker.kt`

native-shell 新增依赖：`core:legacy`（DataMigration）、`core:llm`（ModelConfig 的 `LlmProviders`），
`core:session`/`core:legacy` 均排除 zstd jar。

## 4. 真机验收

| # | 场景 | 结果 | 证据 |
|---|---|---|---|
| 1 | 设置一级页 | 账号/个性化/工作区/功能与工具段正常 | `m85c3-3d-Settings.png` |
| 2 | 模型与参数配置 | `page=Settings sub=ModelConfig`，模型通道/API 设置正常 | `m85c3-3d-ModelConfig.png` |
| 3 | 备份与恢复 | `sub=Backup` 正常 | `m85c3-3d-Backup.png` |
| 4 | 关于 | `sub=About`，版本 **0.3.2（versionCode 5）** | `m85c3-3d-About.png` |
| 5 | 包管理 | `page=Packages`，插件 37/包 0/技能 2/MCP 2 正常 | `m85c3-3d-Packages.png` |
| 6 | 工作区管理 | `sub=Workspaces` 正常 | `m85c3-3d-Workspaces.png` |
| 7 | 无障碍服务 | 服务类随 `:support` 后仍 **Bound=1** | `dumpsys accessibility` |
| 8 | 页面注册表 | 22 页、`catalogMatch=true`、unowned/unknown/duplicates/disabled 同基线 | `docs/m85c3-3d-pages.json` |
| 9 | 无崩溃 | 以上流程无 `FATAL` | logcat |

构建：`:app:assembleDebug` **BUILD SUCCESSFUL**。

## 5. 没做 / 下一片

1. **C3-3d-2**：`ui/device`（A11y）、`ui/screen`（DeviceShell + shower 虚拟屏）、`ui/permissions`、
   `ui/onboarding`、`memory/MemoryScreen`（记忆库页）——依赖 `A11y`/`DeviceShell`/`VirtualScreenManager`/
   `MemoryViewModel` 等宿主能力，需要继续下沉到 `:support` 或抽 host 回调。
2. **C3-3c 壳**：`ui/shell`（抽屉/root/页面清单+dispatch）仍留 app；依赖 `PluginPageScreen`、
   `NativeShells`、`ShellPageRegistry`。计划：插件整页渲染改 host `@Composable` 回调；`NativeShells`
   搬 shell-api + native-shell 用 ServiceLoader 注册 `BuiltinNativeShell`；`SafeModeScreen` 留 app。
3. **C3-4/C3-5** 不变。

## 6. 文件

- 新增模块：`support/build.gradle.kts` + `support/src/main/java/...`（5 个类）
- 移动：`data/{BackupManager,GitHubAuth,BundledSkills}.kt`、`device/DevicePermissions.kt`、
  `device/a11y/DshAccessibilityService.kt` → `support/`
- 移动：`ui/settings/**`、`ui/packages/**`、`ui/workspaces/**` → native-shell
- 修改：`app/build.gradle.kts`、`ui/native-shell/build.gradle.kts`、`settings.gradle.kts`、
  `AboutScreen`/`SettingsScreen`/`BackupScreen`/`NativePageDispatch`（版本参数）
- 证据：`docs/m85c3-3d-pages.json`、`docs/shots/m85c3-3d-*.png`

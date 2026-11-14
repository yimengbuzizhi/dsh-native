# 162 · M85-C3-3c：壳/抽屉/页面 dispatch 搬进 native-shell（ServiceLoader 装配）

> 2026-11-14 07:3x。C3-3 最后一大片：把 `ui/shell/native`（路由/抽屉/22 页 dispatch/页面清单）
> 与虚拟屏页搬进 `:ui:native-shell`；`NativeShells` 注册表搬 `ui:shell-api`，由 native-shell 通过
> ServiceLoader 注册（app 不再 import 任何具体壳/页面）。`SafeModeScreen` 与 `ShellPageRegistry` 留 app。
> 真机：OPPO A55 5G（PEMM00 / 720×1600 / Android 13）。

---

## 1. 装配方式（打断 app↔native-shell 循环）

- `ui:shell-api` 新增 `NativeShellProvider` + `NativeShells` 注册表（`BUILTIN_ID` 常量也在这里）：
  - 优先 `ServiceLoader` 读 `META-INF/services/xin.dshaa.ui.shellapi.NativeShellProvider`；
  - 资源被裁掉时回落到按类名反射 `BuiltinNativeShell.INSTANCE`（无编译期依赖）。
- native-shell 新增 `NativeShellService : NativeShellProvider` + `src/main/resources/META-INF/services/...`。
- `NativeShell.Root(host, ui)` 多一个 `NativeShellHostUi` 页面槽：插件整页 WebView 仍由宿主
  `pluginui` 渲染，壳通过 `ui.PluginPage(target,onBack)` / `ui.openPluginPage(context,record,routeId)`
  把活交回宿主——壳不 import WebView/pluginui。
- 壳版本号改走 `NativeShellHost.appVersionName/appVersionCode`（dispatch 不再读 app BuildConfig）；
  路由日志去掉 `BuildConfig.DEBUG` 判断（142 脚本依赖 `DshPage` 行）。
- `ChatMirror` 改依赖 `NativeShellHost` 并搬 `:support`（host 类型从 AppViewModel 收窄），
  `AttachmentCapture`/`LocationCapture`/`LocationAttachActivity`/通知监听/`NotificationFeed` 一并进
  `:support`；`VirtualScreenOverlay` 的 `ChatMirror.stop()` 改 `VirtualScreenRuntime.onStopChat`
  钩子（app 在 `AppViewModel.init` 注入）。

## 2. 搬走的文件

- app `ui/shell/` → native-shell：`DshDrawer.kt`、`native/NativeShellRoot.kt`（含 `BuiltinNativeShell`）、
  `native/pages/NativePageDispatch.kt`、`native/pages/NativePages.kt`
- app `ui/screen/VirtualScreenPage.kt`、`device/shower/{VirtualScreenManager,VirtualScreenOverlay}.kt`、
  `VirtualHomeActivity.kt` → native-shell
- app 留：`ui/App.kt`（入口）、`ui/shell/SafeModeScreen.kt`、`ui/shell/ShellPages.kt`、
  `ui/surface/**`、`pluginui/**`

## 3. 真机验收

| # | 场景 | 结果 | 证据 |
|---|---|---|---|
| 1 | 冷启默认界面 | `route page=Chat`，说明 ServiceLoader 装配成功（不是安全模式） | `m85c3-3c-default.png` |
| 2 | 抽屉 | 118 会话/130 工具/工作区/助手正常（DshDrawer 在 native-shell） | `m85c3-3c-drawer.png` |
| 3 | 设置页 | `page=Settings` 正常（设置页已在 native-shell） | `m85c3-3c-Settings.png` |
| 4 | 虚拟屏页 | `page=VirtualScreen` 正常（页面/管理器/Overlay/HomeActivity 全在 native-shell） | `m85c3-3c-VirtualScreen.png` |
| 5 | 记忆库页 | `page=Memory` 正常 | `m85c3-3c-Memory.png` |
| 6 | 停用内置壳 → 安全模式 | 关 `builtin.shell.native` 显示「界面插件已停用」+ 一键重开 | `m85c3-3c-safemode.png` |
| 7 | 重新启用 | 点按钮回到原聊天页，无数据变化 | `m85c3-3c-restored.png` |
| 8 | 页面注册表 | 22 页、`catalogMatch=true`、unowned/unknown/duplicates/disabled 同基线 | `docs/m85c3-3c-pages.json` |
| 9 | 无崩溃 | 以上流程无 `FATAL`；无障碍服务重新 Bound | logcat / dumpsys |

**未跑**：本片未重跑 M84-B 142/142（计划 C3-5 收口统一跑）；插件整页槽只做了编译+接线，
没有再用 M84-G 插件页做一次真机替换演示（142 里不含 plugin 整页）。

## 4. 没做 / 下一片

1. **C3-3 收尾**：app 仍 import `ui.chat` 的公共组件（悬浮窗全屏用 `QuotedAttachment`/
   `consumeInnerScroll`/`QuotedAttachmentChip`）+ `SafeModeScreen`/`Surface`（宿主兜底，按设计）。
   悬浮窗 UI 是否也搬 native-shell 可在 C3-5 前决定。
2. **C3-4**：`page/v1` 完整版（M84-G 插件整页并进 `ShellPageRegistry`）。
3. **C3-5**：C 收口——**142/142**、三终点回归、停用壳安全模式 + 同 id 接管演示。
4. 无障碍坑不变：`am force-stop` 后要重发并看 `Bound services:{Service[label=DSH 无障碍…]}`。

## 5. 文件

- shell-api：新增 `NativeShells.kt`（Provider+注册表）、`NativeShellHostUi.kt`；
  `NativeShell.Root(host,ui)`；`NativeShellHost` +版本字段
- native-shell：新增 `NativeShellService.kt`、`META-INF/services/...NativeShellProvider`、
  `device/shower/VirtualScreenRuntime.kt`；搬 `DshDrawer`/`NativeShellRoot`/`NativePageDispatch`/
  `NativePages`/`VirtualScreenPage`/`VirtualScreenManager`/`VirtualScreenOverlay`/`VirtualHomeActivity`
- support：`ChatMirror`/`AttachmentCapture`/`LocationCapture`/`LocationAttachActivity`/
  `NotificationFeed`/`DshNotificationListenerService`（ChatMirror 收窄到 NativeShellHost）
- app：`App.kt`（hostUi 槽）、`AppViewModel`（版本字段/虚拟屏钩子/`attachTextFile`）、
  `ui/shell/ShellPages.kt`、`SafeModeScreen`
- 证据：`docs/m85c3-3c-pages.json`、`docs/shots/m85c3-3c-*.png`

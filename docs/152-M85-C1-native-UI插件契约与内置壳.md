# 152 · M85-C1：native UI 插件契约 + `builtin.shell.native` 落地（原生 UI 原样搬进壳）

> 2026-11-14 00:1x。设计基线 `docs/144` §5/§8 + **2026-11-13 23:4x 用户澄清**
> （原生 UI 不删、原样插件化；Surface 壳可选）。上一片 `docs/151`（可选壳补齐）。
> 真机：OPPO A55 5G（PEMM00 / 720×1600 / Android 13），无线调试 `192.0.2.10:45111`。
> 这一片是 M85-C 的第一片：**先立契约、把整屏 UI 原样搬进内置界面插件**，不动 UI 本身。

---

## 1. 这一片做了什么

### 1.1 `shell/v1` native 后端契约（宿主只认它）

新文件 `app/ui/shell/NativeShell.kt`：

```kotlin
interface NativeShell {
    val id: String
    val label: String
    @Composable fun Root(vm: AppViewModel)   // 整屏：引导 + 抽屉 + 页面 + 聊天 + 设置
}

object NativeShells {
    const val BUILTIN_ID = BuiltinPlugins.NATIVE_UI_SHELL  // builtin.shell.native
    fun builtin(): NativeShell?   // 内置实现
    fun byId(id: String?): NativeShell?
    fun all(): List<NativeShell>
}
```

- 宿主入口 `ui/App.kt` 只 import `NativeShells`/`SafeModeScreen`/`PluginShellScreen`，**不再 import 任何页面**；
- 壳选择：Surface 壳（`vm.shellActive`，可选第二套）> native 壳（默认）> 安全模式兜底；
- `ui/App.kt` 只剩主题/系统栏 + 选壳，约 90 行（原来 691 行全是 UI）。

### 1.2 整屏 UI 原样搬进 `builtin.shell.native`

- 原 `ui/App.kt` 的 `DshAppContent`（路由 `NavRoute`、抽屉、22 页 `when`、聊天页参数、
  settings 二级页、宽屏常驻抽屉、返回栈、调试路由）**逐行搬**到
  `ui/shell/native/NativeShellRoot.kt`，只改名 `NativeShellContent`；
- `BuiltinNativeShell : NativeShell` 包住它：`Root(vm)` = onboarding 未完成 → 原引导页，
  否则 `NativeShellContent(vm)`；
- 因此用户看到的还是**原来的界面**（同一份 Compose 代码、同一套 ViewModel 回调），
  只是「由谁提供」变成内置界面插件。

### 1.3 内置界面插件：可停用、可重新打开

- `BuiltinPlugins` 新增常量 `NATIVE_UI_SHELL = "builtin.shell.native"` 和一条
  `kind = BuiltinPluginKind.Shell` 的记录（默认启用；与工具/页面插件同一个
  `builtin_plugins_disabled` 持久化、同一个插件页开关）；
- `AppViewModel.nativeUiEnabled()`：读 `builtinPluginsUi` 后查 store，**Compose 可观察**；
- 插件页新增「界面插件（1）」分区（工具/页面/悬浮窗分区之前），说明关掉 = 安全模式；
- 停用后没有可选 Surface 壳 → 宿主画 `SafeModeScreen`（仅此一个兜底界面，不是产品 UI）：
  解释原因 + 「重新启用原生界面」；
- 重新启用走**干净冷任务**（`Intent` + `CLEAR_TASK`）：真机复现过就地重组后抽屉停在
  打开态、返回键直接退出的残留布局；安全模式是低频自救路径，重建任务可接受
  （验证见 §2 #4）。

### 1.4 没动的部分

- 22 页/设置/抽屉/onboarding 的 Compose 实现一行没改；`BuiltinPages`/`BuiltinPageIds`
  仍在 `ui/pages`、`data`（页面清单的「所有权」迁移是 C2+ 的事）；
- Surface 可选壳 `builtin.shell.whale`（1.4.1）选择链路不变；
- 存储/回合/提示词/模型契约（M85-D/E）未动。

---

## 2. 真机验收

| # | 场景 | 结果 | 证据 |
|---|---|---|---|
| 1 | 默认界面不变 | 冷启动（无 `dsh_shell`）= 原聊天落地页；标题/抽屉按钮/输入条/模型 pill 与改动前一致 | `m85c1-01-default-ui.png`、`m85c1-06-fresh-start.png` |
| 2 | 插件页看到界面插件 | 标题「内置插件 24 · 本地 37 · 已接入 72」（24 = 原 23 + 界面插件）；「界面插件（1）」分区代码已加（滚动截图没截到该分区，开关链路由 #3/#4 验证） | `m85c1-02-plugins-page.png` |
| 3 | 停用 → 安全模式 | `am start --es dsh_builtin_off builtin.shell.native` 冷启 → 「界面插件已停用」+ 解释 + 「重新启用原生界面」；logcat 无 FATAL | `m85c1-04-safe-mode.png` |
| 4 | 重新启用 → 干净原界面 | 安全模式点按钮 → 写回启用（`DshPlugin: builtin plugin builtin.shell.native enabled=true tools=130`）→ 冷任务重启 → 原聊天页/抽屉/输入条正常，抽屉默认关闭、返回键正常 | `m85c1-05-reenabled.png` |
| 5 | 无崩溃 | 上述流程 dispatch/组合日志正常，无 `FATAL`/ANR | logcat |

已知（本片不修）：

- 重新启用用冷任务重启，**会丢当前输入框草稿**（会话/消息/设置都在磁盘/VM 之外，不受影响）；
  不做就地重组是因为上面那个残留布局。
- `dsh_shell` pref 目前仍用 `native` 表示原生后端；`builtin.shell.native` 作为 pref 值
  还没有被 `resolveShellTarget` 接受（C2 会统一成「壳 id」语义）。

单测：`:core:plugins:test` 121/121 不涉及本片；`:app:assembleDebug` 通过。

---

## 3. 明确没做 / 下一片（M85-C2+）

1. **页面所有权迁移（C2）**：把 `BuiltinPages`/`BuiltinPageIds` 的「路由清单 + 页面渲染
   dispatch」从宿主 `ui/pages` 移进 native 壳（`ui/shell/native/pages/`），
   `DshAppContent`/调试通道改为向壳要清单；宿主不再 import `BuiltinPages`。
2. **物理模块化（C3+）**：目标是把 UI/页面搬进独立 Gradle 模块（`ui:native-shell`），
   宿主只依赖 `shell/v1` 契约。前提是先做 **`NativeShellHost` 门面**（设置/会话/聊天动作/
   工作区/插件页等宿主服务接口），否则 UI 直接依赖 `AppViewModel` 无法拆模块。
   拆模块按页面组小步走：聊天页 → 设置/抽屉 → 各一级页 → onboarding。
3. **页面级可换（page/v1）**：页面 id/标题/层级 + 渲染归壳；插件注册整页（现有 M84-G 的
   `pluginPageFor`）与内置页面走同一张注册表。
4. **M84-B 142/142 等价回归**：C 收口前必须跑，证明体验与 0.3.2 对齐。
5. **同 id 接管**：native 后端随 APK 编译（不做 DEX 动态加载，`docs/144` §10）；
   「同一槽位可换」目前 = 选 Surface 壳（`dsh_shell`），C2 把 `dsh_shell=builtin.shell.native`
   也变成合法壳 id，并允许未来 page/v1 页面插件覆盖同名页面。

---

## 4. 文件

- 契约/注册表：`app/ui/shell/NativeShell.kt`（新）
- 内置 native 壳：`app/ui/shell/native/NativeShellRoot.kt`（新；原 `ui/App.kt` 的整屏 UI 原样搬入）
- 宿主入口：`app/ui/App.kt`（现在只剩主题/选壳/安全模式）
- 安全模式：`app/ui/shell/SafeModeScreen.kt`（新）
- 内置插件登记：`app/data/BuiltinPlugins.kt`（`NATIVE_UI_SHELL`、`kind = Shell`）
- ViewModel：`app/chat/AppViewModel.kt`（`nativeUiEnabled()`）
- 插件页：`app/ui/plugins/PluginsScreen.kt`（界面插件分区）
- 截图：`docs/shots/m85c1-*.png`

# 156 · M85-C3-1：`NativeShellHost` 门面（壳不再依赖 `AppViewModel`）

> 2026-11-14 00:5x。按 `docs/155` §2/§3 做 C3-1：**先把壳和宿主之间的界面服务面切成接口**，
> 行为零改动、UI 一行没动。这是把 `ui/shell` 搬进独立 Gradle 模块（`ui:native-shell`）的前置条件。
> 真机：OPPO A55 5G（PEMM00 / 720×1600 / Android 13），无线调试 `192.0.2.10:45111`。

---

## 1. 这一片做了什么

### 1.1 新契约：`ui/shell/NativeShellHost.kt`

`ui/shell` 目录下（`NativeShellContent` / `NativePageDispatch` / `DshDrawer`）直接用到的
**109 个 `AppViewModel` 成员**原样抽成接口，按功能分区注释：

- 聊天（状态/回合/审批/答题/plan/goal/日程/附件/模型/子代理回放/消息分支/等待横幅）
- 会话列表与回收站、工作区、设置、用户插件/内置插件/插件整页、Operit 包、技能、
  后台任务/子代理、设备/远程/路径、引导/调试通道/模型探测；
- 所有状态属性在接口里是 **`val`**（实现类 `var ... private set` 满足 `val`，读它仍触发重组）；
  动作方法只转发，不解释业务；`suspend fun` 原样保留（`pauseGoal`/`resumeGoal`/`editGoal`/
  `clearGoal`/`fileCandidates`/`fetchModels`/`testConnection`）。

**扁平 v1**（`docs/155` §2）：分组子接口会把 109 处调用点改成 `host.chat.x()`，纯机械大 diff；
分组推迟到 C3-2/C3-3 需要给第三方 native 壳收窄面时再做。

### 1.2 宿主实现 + 壳签名

- `class AppViewModel(...) : AndroidViewModel(app), NativeShellHost`；109 个成员加 `override`
  （脚本插入，编译期验证：`override` 数量 +109，0 error）；
- `createWorkspace`/`resolveApproval` 的默认参数从实现类搬到接口（Kotlin 不允许 override 带默认值）；
- `NativeShell.Root(vm: AppViewModel)` → `Root(host: NativeShellHost)`；
  `BuiltinNativeShell.Root`、`NativeShellContent`、`NativePageDispatch` 同步换类型
  （参数名仍叫 `vm`，方法体 0 改动）；
- `PluginPageEntry`/`PluginPageTarget` 从 `AppViewModel` 内部搬到 `ui/shell/PluginPages.kt`
  （否则门面引用它们 = 壳仍依赖 VM）。

### 1.3 边界现状（源码扫描）

| 位置 | 依赖 |
|---|---|
| `ui/shell/NativeShellHost.kt` | 只依赖 core 模型 + `chat`/`data`/`remote` 的状态类型（下一步搬 `ui:shell-api`） |
| `ui/shell/native/**` | **只依赖 `NativeShellHost`**（无 `AppViewModel` import） |
| `ui/shell/SafeModeScreen.kt` | 仍拿 `AppViewModel`——**宿主兜底界面**（不是壳），C3 之内不动 |
| `ui/App.kt` / `MainActivity` | 入口 + 选壳，按 `shell/v1` |

---

## 2. 真机验收（行为零改动冒烟）

| # | 场景 | 结果 | 证据 |
|---|---|---|---|
| 1 | 默认聊天页 | 冷启动 = 原聊天落地页（标题/抽屉/输入条/模型 pill），`DshPage route page=Chat` | `m85c3-06-facade-default.png` |
| 2 | 抽屉状态（接口读状态仍可重组） | 点抽屉 = 117 个会话/工作区/助手列表正常（`dsh`/`kdoc` 状态经接口读到） | `m85c3-07-facade-drawer.png` |
| 3 | 二级页渲染 | `--es dsh_open_screen modelconfig` → 模型与参数配置（`route page=Settings sub=ModelConfig`） | `m85c3-08-facade-modelconfig.png` |
| 4 | 一级页渲染 | `--es dsh_open_screen plugins` → 插件页（`route page=Plugins`） | `m85c3-09-facade-plugins.png` |
| 5 | 页面注册表不受影响 | `dsh_dump_pages`：22 页、`catalogMatch=true`、`unowned=[Plugins,Settings]`、`disabled=[]` | `docs/m85c3a-pages.json` |
| 6 | 无崩溃 | 以上流程 + 冷启恢复默认界面，无 `FATAL`/`ANR` | logcat |

构建：`:app:assembleDebug` 通过（`:app:compileDebugKotlin` 0 error）。
设备恢复：`dsh_shell=native`、`approval_mode=auto`、`onboarding_done=true`、页面插件全开、无障碍 Bound。

**验证过程中的坑（下一棒注意）**：KDoc 里写 `` `ui/shell/**` `` 会让 Kotlin 认为开了嵌套块注释
（`Unclosed comment`）——Kotlin 块注释里不要出现 `/*`（HANDOFF 红线里也有这条）。

---

## 3. 没做 / 下一片

1. **C3-2**：把 `NativeShellHost` + 它引用的状态类型（`ChatSessionState`/`WorkspaceUi`/`JobUi`/
   `SubagentUi`/`WaitingSession`/`SettingsStore`/`RemoteDshController` 只读接口…）搬进新模块
   `ui:shell-api`，`app` 依赖该模块；然后重跑 142/142（`tools/m84b-page-check.py`）。
2. **C3-3**：建 `ui:native-shell` 模块，按组搬 UI 源码（聊天页 → 抽屉/设置 → 各一级页 → onboarding）。
3. **C3-4**：`page/v1` 完整版（M84-G 插件整页并进 `ShellPageRegistry`）。
4. `SafeModeScreen` 仍依赖 `AppViewModel`（宿主兜底，按设计保留）。
5. M84-B 142/142：本片只做冒烟（行为零改动），C3-2 搬模块后再跑全量。

## 4. 文件

- 新增：`app/ui/shell/NativeShellHost.kt`（109 成员门面）
- 新增：`app/ui/shell/PluginPages.kt`（`PluginPageEntry`/`PluginPageTarget` 从 VM 搬出）
- 修改：`app/chat/AppViewModel.kt`（实现接口 + 109 个 `override` + 默认参数搬家）
- 修改：`app/ui/shell/NativeShell.kt`（`Root(host: NativeShellHost)`）
- 修改：`app/ui/shell/native/NativeShellRoot.kt`、`native/pages/NativePageDispatch.kt`（只换类型）
- 设计：`docs/155`（§2 改为扁平 v1 的理由）
- 证据：`docs/shots/m85c3-0{6,7,8,9}-facade-*.png`、`docs/m85c3a-pages.json`

# 40 · Operit 功能批量移植（第一批：工作流 / 终端 / 虚拟屏 / 无障碍 / 悬浮球 / 工具箱 / 聊天页）

> 2026-11-07 晚｜基线 `7df2b5a`（M22）。用户新指令：
> **「把自己已经自研的内容改成 Operit 的；聊天界面 UI 照着 Operit；功能和聊天页面先做；记得看源码。」
> 本轮没有连接真机（用户明确「先不测试」），所以只做「源码 / 构建 / 单测」级验收，真机留到设备回来。**

---

## 一、一句话结果

| 项 | 之前 | 现在 |
|---|---|---|
| 工具 | 31/32（缺 workflow） | **32/32**：`workflow` 工具 + QuickJS 引擎 + 子代理 fan-out + 后台 job |
| 聊天页 | Rikka 风（头像 + 气泡 + 圆角输入托） | **Operit Cursor 风**：`Response` 标题行、全宽正文、用户右对齐卡片；长按菜单（复制/朗读/引用/重发/重新生成/创建分支/信息）+ Operit Classic 输入条（模型/角色芯片、＋菜单、麦克风/发送） |
| 长按 AI 消息 | 只有「复制 / 引用 / 重发」对话框 | Operit 同款：复制（含格式）、朗读（TTS）、引用、重新生成、创建分支、信息；v4 append-only 做不到的（删除/回滚/变体/多选）暂不做并写明 |
| 创建分支 / 重新生成 | 无 | 会话前缀 fork（`SessionRepository.fork`，干净 turn 边界）+ 新会话打开；重新生成 = fork 到上一轮用户消息之前 + 重发 |
| 终端 | 无 | `:thirdparty:terminal`（OperitTerminalCore 源码复制）+ 抽屉「终端」一级页 |
| 虚拟屏 | 无 | `:thirdparty:shower`（showerclient 源码复制）+ Shizuku/root ShellRunner + 虚拟屏页（启动/触摸/按键/截图） |
| 无障碍 | 无 | 内嵌 `AccessibilityService`（读屏/点击/输入/手势/截图）+ 无障碍页（状态/节点树） |
| 悬浮窗 | 无 | 悬浮球 Service（可拖动、快捷菜单：对话/终端/虚拟屏/无障碍/工具箱） |
| 工具箱 | 无 | 一级页「工具箱」：设备能力状态 + 功能入口 + 工具清单 |
| minSdk | 24 | **26**（showerclient / OperitTerminalCore 的下限） |

---

## 二、按源码照搬的部分（用户要求「看源码」）

### 2.1 聊天页（Operit `ui/features/chat`）

读了这些文件并按其结构实现（不是凭截图猜）：

| Operit 文件 | 照搬的内容 | 本项目落点 |
|---|---|---|
| `components/ChatArea.kt` L613-1263 | 消息长按 `DropdownMenu`：180dp 宽、36dp 行高、13sp、16dp 前置图标；菜单项顺序与含义（复制 / 朗读 / 插件项 / 编辑重发 / 回滚 / 重新生成 / 修改记忆 / 变体删除 / 删除 / 回复 / 插入总结 / 创建分支 / 信息 / 多选） | `ChatScreen.kt` 的 `MessageMenuDialog` / `MenuRow`；按 v4 能力裁剪 |
| `components/style/cursor/AiMessageComposable.kt` | AI 消息头：左 `Response`（labelSmall、文字 70% 透明）、右角色/模型（50% 透明）；正文全宽、左右 16dp | `AssistantTurn` 头部 |
| `components/style/cursor/UserMessageComposable.kt` | 用户消息右对齐卡片、圆角、整体可长按 | `UserMessage` |
| `components/style/input/classic/ClassicChatInputSection.kt` | 输入托：回复预览条、附件面板、输入行、发送/麦克风切换、模型/角色设置条 | 输入条 `SettingsChip` + 回复预览 + ＋菜单 + 麦克风/发送 |
| `components/style/input/classic/ClassicChatSettingsBar.kt` | 输入框上方设置条（模型/角色/计划芯片） | `SettingsChip` 行 |
| `ui/features/toolbox/` | 工具箱信息架构 | `ui/toolbox/ToolboxScreen.kt` |

**拍板**：Operit 的消息操作里，「删除 / 回滚 / 变体 / 多选」依赖可截断的会话；我们的 v4 是 append-only，
硬做只能伪造历史。因此这一轮只做**能由 fork/追加表达**的项；删除/回滚等留到「会话截断」方案拍板后再说。

### 2.2 终端（OperitTerminalCore，LGPL 源码复制）

- 整模块复制到 `thirdparty/terminal/`（67 个文件；PTY JNI、proot rootfs、Compose 终端、AIDL、设置页）。
- 仅三处改动：manifest 删 `package`（AGP 8 不接受）；`CommandExecutionEvent.kt` 去 `@Parcelize` 改手写 Parcelable（根工程没有 parcelize 插件；AIDL 契约不变）；`TerminalManager.kt` 加 `sendInputToSession` / `sendInterruptSignalToSession`（2026-11-09，给 AI 终端工具按 sessionId 写输入/发 Ctrl+C，不切用户正在看的会话；见 docs/77）。
- app：`implementation(project(":thirdparty:terminal"))`；`packaging.jniLibs.useLegacyPackaging = true`（PTY 要从 APK 解压出来执行）；删掉上游 2 字节的坏 `libsudo.so`。
- 页面：`ui/terminal/TerminalPage.kt` 直接渲染它的 `TerminalScreen`（首启走 SetupScreen）。

### 2.3 虚拟屏（showerclient，LGPL 源码复制）

- 整模块复制到 `thirdparty/shower/`（AIDL + `assets/shower-server.jar` 1.13MB 逐字节保留）。
- 宿主注入：`DeviceShell`（`:app/device/DeviceShell.kt`）实现 `ShellRunner`：
  - `SHELL` → Shizuku（反射 `Shizuku.newProcess`）→ root → App shell；
  - `ROOT` → `su -c`；
  - `DEFAULT` → root → Shizuku → App shell。
- Shower 服务端用广播交回 Binder：`ShowerBinderReceiver`（action 是 jar 里写死的 `com.ai.assistance.operit.action.SHOWER_BINDER_READY`）。
- 页面：`ui/screen/VirtualScreenPage.kt`：启动/停止服务、创建 1080×1920 虚拟显示、`ShowerSurfaceView` 渲染、触摸映射、HOME/BACK/RECENTS、截图。
- 已知：Shower 客户端硬编码 `/sdcard/Download/Operit` 暂存 jar（上游行为），因此需要 MANAGE_EXTERNAL_STORAGE 或旧存储权限。
- 第三方改动（2026-11-09）：`ShowerController` 缓存最近 SPS/PPS，`setBinaryHandler` 先补发参数集再重放缓冲帧——预览窗 surface 重建（贴边恢复/关掉再打开）后解码器仍能初始化（docs/78）。

### 2.4 无障碍 / 悬浮球 / 工具箱

- 无障碍：Operit 的无障碍是**独立 provider APK**（`com.ai.assistance.operit.provider`）——我们没有照搬「装一个 provider」，而是在应用内实现
  `DshAccessibilityService`（等价能力：节点树 dump、按文本/ID 查找、点击/长按、ACTION_SET_TEXT、全局动作、手势、`takeScreenshot`）。
  理由：内嵌服务不需要第二次安装，权限引导也更短；能力面一致。无障碍页给出「去系统设置」入口与节点树预览。
- 悬浮球：Operit `FloatingChatService` 是完整聊天浮层；本轮先做**悬浮球 + 快捷菜单**（对话/终端/虚拟屏/无障碍/工具箱），
  避免复制第二套聊天引擎；完整浮层聊天留到 UI 重做之后。
- 工具箱：设备能力状态卡（Shizuku/root/无障碍/悬浮窗）+ 七个入口 + 当前工具名清单。

---

## 三、workflow（第 32 个工具）

### 3.1 模块结构

```text
core/js/            QuickJS 2026-06-04（源码 vendored）+ 自写 JNI 桥
core/workflow/      纯 JVM 引擎：meta 校验 / JSON Schema 子集 / JS 外壳 / 运行与并发 / 文案
core/tools/.../workflow/WorkflowTool.kt   工具层（schema 来自上游 tools.json）
app/chat/QuickJsWorkflowRuntime.kt        core:js ←→ core:workflow 适配
app/chat/AppViewModel.kt                  workflowHost / WorkflowChildRunner / 后台 job
```

### 3.2 语义（对齐上游 `dsh-workflow-ptc`）

- 脚本体放进 `(async () => { ... })()`：顶层 `await`、`return` 都支持；
- 全局：`agent(prompt, opts)` / `parallel(thunks)` / `pipeline(items, ...stages)` / `phase(title)` / `log(message)` / `args`；
- `agent()` 立即返回 Promise → 多个调用真并发（`parallel`/`pipeline` 不需要 barrier）；
- 子代理失败只让该 `agent()` 兑现 `null`；选项/schema/限额错误是 fatal（combinator 会 rethrow）；
- 限额默认：maxConcurrentAgents=4、maxTotalAgents=1000、maxItemsPerCall=4096（上游默认值）；
- 前台结果文案：`workflow "<name>" completed (N agents).\nReturn value:\n<pretty JSON>`（5 万字符截断）；
- 后台：登记 `kind="workflow"` 的 job，进度（phase/log/agent-start/end）写 `log` 通道；结算时把返回值写进 job 的 `result`，
  **完成通知内联结果文本**（`JobRegistry.buildNotice` 已改：final-output 型 job 的 result 随通知走，job_output 不重复消费）。

### 3.3 QuickJS 桥的两个关键决定（真机踩坑后改的）

1. **不读 `JS_PromiseState/JS_PromiseResult`**：真机上同一段脚本偶发「空错误栈 / 非 Promise 值被当 pending」。
   改成统一包装：脚本值或 Promise 都汇到 `__dshDone(value)` / `__dshFail(error)`，C 侧只认这两个回调；
2. **字符串跨 JNI 一律 `byte[]`（UTF-8）**，不用 `NewStringUTF/GetStringUTFChars`（modified UTF-8 对中文/emoji 不可靠）。
3. JS 线程等待 completion 用条件变量（20ms 超时轮询 + 唤醒），不忙转；`interrupt()` 走 QuickJS 中断回调，
   等待循环也看取消标记 → 取消能立刻结束。

### 3.4 与上游的已知差距

- `agent()` 的 `provider`/`model` 选项目前只透传、不生效（原生只有一个 provider 路由）；
- 结构化输出用「提示词要求返回 JSON + Kotlin 侧解析校验」实现（上游是 Activation 的 output contract）；
- workflow 子代理不登记到子代理面板（与上游一致：不进父代理 catalog、不发结算通知）；
- 没有 Node 的 `vm` 隔离/超时切片（QuickJS 是独立 runtime，脚本拿不到文件/网络，安全性反而更好）。

---

## 四、验证（无真机）

| 项 | 结果 |
|---|---|
| `:app:assembleDebug` | BUILD SUCCESSFUL（Gradle 8.9 / JDK 21） |
| core 单测 | 全绿；`:core:tools` 工具数断言 31 → **32**；新增 `:core:workflow` 14 个测试（meta/schema/render/engine）；新增 `:core:session` 4 个 fork 测试 |
| `:thirdparty:terminal:assembleDebug` | BUILD SUCCESSFUL（AAR 68.8MB，含 64MB rootfs） |
| `:thirdparty:shower:assembleDebug` | BUILD SUCCESSFUL（shower-server.jar 逐字节一致） |
| 真机 | **未测**（用户明确本轮先不测试；设备无线调试未连接） |

### 尚未验证的风险（设备回来第一件事）

1. QuickJS `.so` 在 arm64 真机加载；`parallel` 两路 complel 都到宿主（已写 instrumented test 5 条，等设备）；
2. 终端 rootfs 首启展开（64MB asset）与 PTY；
3. Shower 服务拉起（需要 Shizuku/root）、虚拟显示画面与触摸映射；
4. 无障碍服务启用后 `A11y.dump()` 的节点权限（`flagRetrieveInteractiveWindows` 需要系统允许）；
5. 悬浮球 FGS 在 Android 15 的前台服务类型（specialUse）与通知权限；
6. 门 2 夹具：工具表 31→32，5 个夹具的请求 sha 全变，设备回来必须全部重录。

---

## 五、下一步

1. **设备 tools**：把无障碍/截图/虚拟屏包装成模型可调用工具（`device_info` / `tap` / `swipe` / `press_key` / `input_text` /
   `capture_screenshot` / `launch_app` / `dump_ui`），schema 放 `tools-android.json` 追加到请求工具表；
2. **助手配置**：按 Operit 的角色卡/人设/工具开关/模型绑定重做 AssistantScreen；
3. **聊天页收尾**：Operit 的复制预览 BottomSheet（纯文本/Markdown/XML 三种格式）、多选模式、消息信息里的 token/耗时；
4. **插件**：QuickJS 已经跑通，下一步抽 `cordis` 风格内核 + ToolPkg 加载；
5. 设备回来后：重录 5 个夹具、跑 QuickJS instrumented、真机验收上面 6 条风险项。

---

## 六、本轮提交

- `feat(workflow)`：core:js + core:workflow + workflow 工具（32/32）
- `feat(device)`：shower/terminal 模块 + ShellRunner + Shizuku/root + 悬浮球 + 无障碍 + 工具箱 + 虚拟屏页
- `feat(chat)`：Operit Cursor 风聊天页 + 消息长按菜单 + Classic 输入条 + 分支/重新生成

---

## 七、真机回归：冷启动闪退（2026-11-07 22:10 修复）

**症状**：装机后每次点开必闪退；`logcat -b crash` 是
`FATAL EXCEPTION: Unable to get provider rikka.shizuku.ShizukuProvider: ClassNotFoundException`。

**根因**：清单里为了 Shizuku 的 binder 入口手写了 `<provider android:name="rikka.shizuku.ShizukuProvider">`，
但 `DeviceShell` 全走反射（`Class.forName("rikka.shizuku.Shizuku")`），Gradle 里**没引 Shizuku 依赖** ——
编译/构建都能过（清单合并不校验类是否存在），装机后 AMS 实例化 provider 就崩。
自动化单测和 `assembleDebug` 都发现不了这类"清单引用了不存在的类"。

**修法**：`:app` 加 `dev.rikka.shizuku:api:13.1.5` + `dev.rikka.shizuku:provider:13.1.5`（Maven Central，
provider 里只有 `ShizukuProvider` + `BinderContainer` 两个类），清单保持手写（这两个 AAR 的清单**不**自带 provider 节点，
只是带 `API_V23` 权限与 `V3_SUPPORT` meta-data）。反射代码不动。

**真机验证**：装机冷启不再崩，落地聊天页；`--es dsh_send "真机冒烟 只回OK"` 一轮走完：
模型 `deepseek-flash` 调 `device_info`（返回 `shizuku: connected`）→ 最终回 `OK`，会话落 `session.v4.jsonl.zstd`。

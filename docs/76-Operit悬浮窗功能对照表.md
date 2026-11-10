# 76 · Operit 悬浮窗 / 虚拟屏浮层 功能对照表

> 2026-11-09 00:10｜基线：Operit 源码 `D:\dsh\native\.thirdparty-src\Operit`（只读），
> 原生仓库 `D:\dsh\native` HEAD `f06f395` + 第四十六棒改动（§十八）。
> **状态以 §十七（第四十五棒）+ §十八（第四十六棒，逐 ID 回填）为准**；
> §五-§十二 状态列是 2026-11-08 22:43 审计基线，只在 §18.4 有明确覆盖时才算数。
>
> 这份表是**先读源码、后写代码**的验收底稿。每条都指回实际读过的文件与行号；
> 只 grep 过签名的内容不写进「实现方式」，也不把「自己另画了一个」写成「已搬」。
> 用户 2026-11-08 的要求：**功能表一定要正确、详细，不要再出现看少了的情况。**

## 〇、状态图例与证据口径

| 标记 | 含义 | 证据要求 |
|---|---|---|
| ✅ | 真机验证 | `docs/shots/` 截图 + 操作命令/日志 |
| 🟡 | 代码完成，未装机验证 | 源码 + 编译通过 |
| 🟠 | 有实现，但明确是简化版/只覆盖一部分 | 源码 + 差异列写清缺什么 |
| ❌ | 未做 | 原生无对应实现 |
| ➖ | 平台不适用/有意不做 | 必须写理由 |

**证据口径**：
- 「代码完成」只说明写完了，不等于「照 Operit 搬完」。
- 本表里的「原生对应」写的是**这一轮准备落到的文件**；如果该文件当前还是简化版，状态列会写 🟠，不会写 ✅。
- 上一棒交接里对 Operit 的几处描述与源码不一致，本表以源码为准，见 §二。

---

## 一、结论先行（必须先看）

1. **Operit 的悬浮窗不是「一个球 + 一个面板」，而是「一个 Service + 一个 WindowManager + 一个 ComposeView + 6 个模式状态机」。**
   `FloatingChatService` 只负责生命周期/聊天核心/通知；`FloatingWindowManager` 用**同一个 ComposeView** 承载
   `FloatingChatWindow`，再由 `AnimatedContent` 按 `currentMode` 切换 6 个模式。窗口的宽高、flags、位置、
   焦点、模糊、转场全部由 manager 改 `WindowManager.LayoutParams` 完成。
   原生现在（`20fbb89`）是 **球 ComposeView + 面板 ComposeView 两个独立窗口**，面板还是自己设计的
   `FloatingChatPanel`。所以不能对外说「悬浮窗照 Operit 搬了」，只能算「球的外形搬了一部分」。

2. **Operit 的 6 个模式是并列的，不是「球/窗」两级。**
   `WINDOW`、`BALL`、`VOICE_BALL`、`FULLSCREEN`、`RESULT_DISPLAY`、`SCREEN_OCR` 每个都有自己的窗口参数、
   进入/退出路径和返回模式（`FloatingMode.kt:3-10`、`FloatingWindowManager.kt:490-996`）。

3. **全屏模式不是「语音模式的壳」。** 它有独立 ViewModel：流式 TTS、连续语音识别、静默发送、
   自动恢复录音、空闲超时、唤醒问候、自动附件、头像表情、编辑面板、底部胶囊输入、语音波形。
   见 `FloatingFullscreenModeViewModel.kt:39-744` 与 `BottomControlBar.kt:113-1003`。

4. **SCREEN_OCR 不是「截个图」。** 它是全屏截图 + 手绘轨迹 + 矩形选区 + 8 个拖拽手柄 + 裁剪预览 +
   ML Kit 中英文 OCR + 生成带坐标的文本附件 + 回到上一个模式 + 自动勾选「圈选识别」。
   见 `FloatingScreenOcrScreen.kt:277-883`。

5. **虚拟屏浮层不是「小窗 + ×」。** 它是按 agentId 多实例的 Compose 浮层：40% 屏宽视频 + 56dp 左侧
   自动化控制栏、贴边收起（36dp + 12dp 越界）、全屏、App 图标把手、触摸转发（含历史点）、
   自动化步骤/暂停/退出、彩虹边框、截图当前帧、Shower 断流取消任务。见
   `VirtualDisplayOverlay.kt:83-1296`。

6. **原生当前只有「球的外形 + 简化聊天面板 + 简化虚拟屏小窗」。** 本表 §五 起是逐条差距；
   在表做完之前不动悬浮窗/虚拟屏代码（按交接要求）。

---

## 二、先纠正上一棒交接里的 5 处不准确

| # | 上一棒说法 | 源码实际 | 出处 |
|---|---|---|---|
| 1 | 呼吸 `0.93→1.0` | **`0.95→1.0`**，3000ms，`FastOutSlowInEasing` | `SiriBall.kt:170-179` |
| 2 | 球的长按语音「整段被注释掉」 | 长按手势代码确实注释了（`SiriBall.kt:274-298`），**但 `SpeechInteractionManager` 仍然被创建、`initialize()`、申请焦点，并收集识别结果**（`:66-101`）。只是当前没有入口触发 `startListening()` | `SiriBall.kt:66-101, 269-299` |
| 3 | 粒子拖尾只在拖动时显示 | 源码 `particleSystem.UpdateEffect(isPressed)`，`isPressed` 只由拖动设置（`:113-137`）；**没有**「生成中也显示」 | `SiriBall.kt:113-137, 248-268` |
| 4 | 虚拟屏浮层「只 grep 过签名」 | 实际 1296 行，包含 §七 全部能力；`show(displayId)` 只是入口 | `VirtualDisplayOverlay.kt` 全文 |
| 5 | docs/75 的文件行数（如 SiriBall 522、FloatingChatWindowScreen 1329） | 按 `read` 实际行号：SiriBall **529**、WindowScreen **1402**、BottomControlBar **1003**、VirtualDisplayOverlay **1296**、FloatingChatService **850**。表内一律用实际行号 | 本表 §十一 阅读记录 |

另外：`FloatingMode.kt` 确实是 6 个枚举、没有别的逻辑；`FloatingWindowManager` 的 1101 行是 `wc` 口径，
`read` 实际 1197 行。

---

## 三、Operit 分层与原生对应（架构级）

| 层 | Operit（文件:行） | 做什么 | 原生现状 | 原生目标 |
|---|---|---|---|---|
| 前台 Service | `FloatingChatService.kt:60-850` | dataSync 前台服务、WakeLock、崩溃计数、通知、`ChatServiceCore(FLOATING)`、主题、Intent extras、生命周期广播 | `FloatingBallService.kt:58-382` 只有通知/两个窗口/快捷菜单 | 重写为 `FloatingChatService.kt`（保留 Service 类名，manifest 少改） |
| 窗口状态 | `FloatingWindowState.kt:10-124` | Compose state：x/y、300×400dp、scale 0.8、模式、上次位置、贴边、转场、模糊 | 只有球 x/y 存 SharedPreferences | 新建 `FloatingWindowState.kt`，字段逐项对齐 |
| 窗口管理器 | `FloatingWindowManager.kt:97-1197` | 单 ComposeView、每模式 LayoutParams、转场、焦点/IME、状态指示器、显隐、模糊 | 无；`FloatingBallService` 内各自 add/remove | 新建 `FloatingWindowManager.kt` |
| 根 UI / 模式切换 | `FloatingChatWindow.kt:70-233` | `FloatContext` + `AnimatedContent(currentMode)` + Toast host | `FloatingChatPanel.kt` 固定一种布局 | 新建 `FloatingRoot.kt`（或复用 `FloatingChatWindow` 名） |
| 数据/聊天核心 | `ChatServiceCore` + `ChatRuntimeHolder.getCore(FLOATING)`（`FloatingChatService.kt:239`） | 悬浮窗自己持有一个可独立运行的 chat core | `ChatMirror` 借主界面 `AppViewModel`（`ChatMirror.kt:20-42`） | 保留 `ChatMirror`（单引擎，不双开）；差异见 §十.1 |
| 模式枚举 | `FloatingMode.kt:3-10` | 6 模式 | 无 | 新建同名枚举 |
| 虚拟屏浮层 | `VirtualDisplayOverlay.kt:83-1296` | 每 agent 一个 Compose overlay + ShowerSurfaceView | `VirtualScreenOverlay.kt:31-222` 单例 + 自绘 LinearLayout | 重写为 `VirtualDisplayOverlay.kt`（object 改成 per-agent 类） |

---

## 四、M51 这一轮的原生落点（文件级）

> 这些是**计划落点**，状态在 §五 之后逐条标。已存在的文件不重复造。

| 新/改 | 文件 | 职责 |
|---|---|---|
| 新建 | `device/floating/FloatingMode.kt` | 6 模式枚举 |
| 新建 | `device/floating/FloatingWindowState.kt` | 位置/尺寸/scale/模式/转场/贴边/模糊状态 + SharedPreferences |
| 新建 | `device/floating/FloatingWindowManager.kt` | 单 ComposeView、LayoutParams、模式转场、焦点/IME、状态条 |
| 重写 | `device/floating/FloatingBallService.kt` | 前台服务、通知、WakeLock、崩溃保护、Intent extras、ChatMirror 回调 |
| 新建 | `device/floating/FloatingChatWindow.kt` | 根 UI + `AnimatedContent` 6 模式 |
| 重写 | `device/floating/FloatingOrb.kt` | 逐参数对齐 `SiriBall` + `BallParticles` |
| 新建 | `device/floating/ui/window/*` | WINDOW 标题栏、消息、输入、附件、缩放、历史会话、处理状态 |
| 新建 | `device/floating/ui/fullscreen/*` | 全屏、语音态、TTS、编辑、底部控制栏、头像表情 |
| 新建 | `device/floating/ui/screenocr/*` | 圈选 OCR |
| 新建 | `device/floating/ui/ball/*` | BALL/VOICE_BALL/RESULT_DISPLAY |
| 重写 | `device/shower/VirtualScreenOverlay.kt` | per-agent 浮层、贴边/全屏/自动化/截图/触摸 |
| 改 | `device/shower/VirtualScreenManager.kt` | show/hide/update 自动化、当前 App 包名、截图 |
| 改 | `chat/ChatMirror.kt` | 暴露消息/附件/审批/提问/停止/发送、PromptFunctionType、会话切换 |
| 新增 | `device/floating/FloatingSpeechManager.kt` | 系统 SpeechRecognizer/TTS 封装（后端差异见 §十.2） |
| 改 | `app/build.gradle.kts` | 加 ML Kit 中文/拉丁 OCR 依赖 |

---

## 五、Service 与窗口生命周期

| ID | Operit 文件:行 | 它怎么做 | 原生对应 | 状态 | 与 Operit 的有意差异 |
|---|---|---|---|---|---|
| S1 | `FloatingChatService.kt:213-313` | `onCreate` 初始化 Application、单例、SharedPreferences、崩溃保护、WakeLock、`ChatServiceCore(FLOATING)`、`FloatingWindowState`、`FloatingWindowManager`、前台通知；失败 `stopSelf` | `FloatingBallService.kt:73-82` | 🟠 | 原生没有 WakeLock、崩溃计数、chat core、主题、状态机；计划本轮补齐 |
| S2 | `FloatingChatService.kt:346-381` | 通知渠道 `IMPORTANCE_LOW`、`setShowBadge(false)`、ongoing、category service、点通知回主界面 | `FloatingBallService.kt:309-335` | 🟠 | 原生有渠道/ongoing/点击回主界面；缺 `dataSync` 前台类型、`setShowBadge(false)`、category |
| S3 | `FloatingChatService.kt:301-306` | `ForegroundServiceCompat.startForeground(dataSync=true)` | `FloatingBallService.kt:75` | 🟠 | 原生 manifest 是 `specialUse`；是否改 dataSync 要按 Android 15 前台类型校验后再定 |
| S4 | `FloatingChatService.kt:315-344` | 10 分钟 `PARTIAL_WAKE_LOCK`，onStart/onDestroy 获取/释放 | 无 | ❌ | 计划补；注意不要常驻不释放 |
| S5 | `FloatingChatService.kt:84-211` | 崩溃计数持久化，60s 窗口内 >3 次停用服务；保存状态并重启 | 无 | ❌ | 计划补同样阈值，不自己发明 |
| S6 | `FloatingChatService.kt:383-547` | `START_STICKY`；`INITIAL_MODE`、`KEEP_IF_EXISTS`、`AUTO_ENTER_VOICE_CHAT`、`WAKE_LAUNCHED`、`AUTO_EXIT_AFTER_MS`、`COLOR_SCHEME`、`TYPOGRAPHY` | `FloatingBallService.kt:71-82` 无 intent 处理 | ❌ | 计划补 `INITIAL_MODE`/`AUTO_ENTER_VOICE_CHAT`/`AUTO_EXIT_AFTER_MS`/`KEEP_IF_EXISTS`；主题 extras 用原生 `DshTheme` 不需要 |
| S7 | `FloatingChatService.kt:132-148` | `AUTO_EXIT_AFTER_MS` 到时 `onClose()` | 无 | ❌ | 计划补 |
| S8 | `FloatingChatService.kt:556-562` | `onTaskRemoved` 重启服务 | 无 | ❌ | 计划补；后台启动限制要 try/catch |
| S9 | `FloatingChatService.kt:564-580` | `onLowMemory`/`onTrimMemory` 保存窗口状态 | 无 | ❌ | 计划补 |
| S10 | `FloatingChatService.kt:601-677` | `onDestroy` 释放 WakeLock、取消 chat/TTS/ASR、保存状态、destroy manager、恢复异常处理器、发停止广播 | `FloatingBallService.kt:84-89` | 🟠 | 原生只 remove views；缺取消语音/TTS、保存状态、广播 |
| S11 | `FloatingChatService.kt:679-717` | `onClose`：取消当前消息、取消 ASR/TTS、`prepareForExit`、通知 binder、`stopSelf` | 无统一 `onClose` | ❌ | 计划补 |
| S12 | `FloatingChatService.kt:719-757` | 回调 `onSendMessage/onCancelMessage/onAttachmentRequest/onRemoveAttachment/getMessages/getAttachments/getInputProcessingState` | `ChatMirror.send/stop` 只有两个 | 🟠 | 计划扩成 `FloatingWindowCallback` 适配层 |
| S13 | `FloatingChatService.kt:787-848` | `getComposeView`、`switchToMode`、`setFloatingWindowVisible`、`setFloatingWindowPersistentHidden`、`setStatusIndicatorVisible/Persistent` | 无 | ❌ | 计划补；持久隐藏的语义是「AI 可继续跑，窗口不显示」 |
| S14 | `FloatingWindowManager.kt:162-195` | `show()` 建 `ComposeView`，设置 Lifecycle/ViewModelStore/SavedState 三个 owner，`FloatingWindowTheme`，addView | `FloatingBallService.kt:110-138`（球）/`:237-287`（面板） | 🟠 | 原生球有 Lifecycle+SavedState，但缺 ViewModelStoreOwner；面板同；计划单 view 三 owner 全给 |
| S15 | `FloatingWindowManager.kt:197-220` | `destroy()` 先隐藏状态条、`cancelFocusBeforeExit`、removeView、清引用 | `FloatingBallService.kt:84-89` | 🟠 | 原生 closePanel/removeBall 分开；计划统一 |
| S16 | `FloatingWindowManager.kt:222-267` | 全屏透明「点外部收焦点」View（`TYPE_APPLICATION_OVERLAY` + NOT_FOCUSABLE + MATCH_PARENT，默认 GONE） | 无 | ❌ | 计划补；这是 WINDOW 输入法能收键盘的关键 |
| S17 | `FloatingWindowManager.kt:311-364` | 窗口/状态条的显示、持久隐藏、持久显示状态机 | 无 | ❌ | 计划补 |
| S18 | `FloatingWindowManager.kt:366-488` | 状态指示器两种：全屏彩虹边框 / 顶部「自动化进行中」卡片；可设 alpha | 无 | ❌ | 计划补；本轮至少 TOP_BAR，彩虹边框可复用现有 `RainbowBorder`（若有） |
| S19 | `FloatingWindowManager.kt:490-588` | 按模式算 LayoutParams（见 §六） | 球/面板各自硬编码 | 🟠 | 计划重写 |
| S20 | `FloatingWindowManager.kt:590-635` | 全屏 cutout shortEdges；Android 12+ `FLAG_BLUR_BEHIND` + 48dp 模糊；记录 `fullscreenSystemBlurActive` | 无 | ❌ | 计划补；API<31 时 Compose 回退，和 Operit 一样 |
| S21 | `FloatingWindowManager.kt:637-642` | `isAtEdge(x,width)`，5px 容差 | 无 | ❌ | 计划补 |
| S22 | `FloatingWindowManager.kt:644-661` | `updateWindowSizeInLayoutParams` / `updateViewLayout` | 无 | ❌ | 计划补 |
| S23 | `FloatingWindowManager.kt:663-676` | 模式切换保持中心点的位置计算 | 无 | ❌ | 计划补 |
| S24 | `FloatingWindowManager.kt:678-996` | 模式切换状态机：保存离开位置、`previousMode`、`isTransitioning`、目标宽高/xy/flags/gravity/blur；球↔其他 150/100/500ms 同步 | 无 | ❌ | 本轮核心；按源码逐个分支抄 |
| S25 | `FloatingWindowManager.kt:998-1040` | 拖动：全屏禁止；球 1.0 灵敏度；窗口按 scale；边界限制并写回 state | `FloatingBallService.kt:122-130`（球） | 🟠 | 面板没有拖动边界写回；计划统一 |
| S26 | `FloatingWindowManager.kt:1042-1188` | 焦点/IME：去 NOT_FOCUSABLE、WINDOW 加 NOT_TOUCH_MODAL/WATCH_OUTSIDE_TOUCH、200ms 后重试最多 4 次、找可文本编辑焦点再 showSoftInput | 面板 `SOFT_INPUT_ADJUST_RESIZE`（`:279-280`） | 🟠 | 原生没有焦点重试/点外部收起；计划逐行抄 |
| S27 | `FloatingWindowManager.kt:1190-1196` | `getComposeView()` 给语音申请焦点 | 无 | ❌ | 计划补 |

---

## 六、六种模式的窗口参数与转场（源码级）

> 这一节是 `FloatingWindowManager.kt:490-996` 的拆解，表里每个模式都按这里的参数实现，
> 不允许「大概写一个 WindowManager.LayoutParams」。

| 模式 | 窗口类型/宽高 | flags / softInput | 位置与边界 | 进入/退出 | 原生状态 |
|---|---|---|---|---|---|
| `BALL` / `VOICE_BALL` | `TYPE_APPLICATION_OVERLAY`；`ballSize` dp（默认 60） | `NOT_FOCUSABLE \| LAYOUT_NO_LIMITS`；`SOFT_INPUT_STATE_UNSPECIFIED` | 至少留球半径可见；x 可越界半个球，y 在 `safeMargin..screenH-minVisible-safeMargin`；从全屏进入时放右侧垂直居中 | 进入先 `cancelFocusBeforeExit`；离开保存 `lastBallPositionX/Y`；转场延迟/淡出 | 🟠 只有球窗口，无模式状态 |
| `WINDOW` | 同样 overlay；`windowWidth/Height` dp × density × scale | `NOT_FOCUSABLE \| LAYOUT_NO_LIMITS`；默认不可获焦，输入时动态去 flag | 至少 2/3 宽可见，y 至少半高可见；从球进入按中心点展开并严格夹在屏内 | 离开保存 `lastWindowPositionX/Y`、`lastWindowScale`，回球时恢复 | 🟠 固定 330dp 面板，无尺寸/缩放/位置持久化 |
| `FULLSCREEN` | `MATCH_PARENT × MATCH_PARENT` | `LAYOUT_IN_SCREEN \| LAYOUT_NO_LIMITS`（无 NOT_FOCUSABLE，因此可获焦）；`SOFT_INPUT_ADJUST_NOTHING` | 0,0 | 进入时暂停唤醒词监听；开 48dp 系统模糊；cutout shortEdges | ❌ |
| `SCREEN_OCR` | 同全屏 | 同全屏但不加模糊 | 0,0 | 进入时暂停唤醒词监听 | ❌ |
| `RESULT_DISPLAY` | `WRAP_CONTENT × WRAP_CONTENT` | `NOT_FOCUSABLE \| LAYOUT_NO_LIMITS` | 根据球中心在屏幕左/右决定 `Gravity.TOP\|START` 或 `TOP\|END`，贴在球旁边 | 只从球 `onTriggerResult` 进入；3 秒后自动回 `BALL`（若仍是 RESULT_DISPLAY） | ❌ |
| 转场 | —— | —— | 非球→球延迟 150ms 改物理尺寸；球→非球先 `ballExploding=true` 100ms 淡出再改；球↔球立即；`isTransitioning` 500ms 防抖 | `FloatingChatWindow.kt:154-218` 的 AnimatedContent 同步 | ❌ |

**焦点细节（必须照抄）**：`setFocusable(true)` 去掉 `FLAG_NOT_FOCUSABLE`；WINDOW 额外加
`FLAG_NOT_TOUCH_MODAL | FLAG_WATCH_OUTSIDE_TOUCH`，这样点外部仍能收焦点；`scheduleImeShow`
先 `requestFocus()`，再找 `onCheckIsTextEditor()` 的焦点，找不到每 50ms 重试，最多 4 次。
`setFocusable(false)` 清焦点、隐藏键盘、恢复 NOT_FOCUSABLE（全屏/OCR 除外）。

---

## 七、WINDOW 模式（Operit `FloatingChatWindowScreen.kt` 等）

| ID | Operit 文件:行 | 它怎么做 | 原生对应 | 状态 | 与 Operit 的有意差异 |
|---|---|---|---|---|---|
| W1 | `FloatingChatWindowScreen.kt:171-211` | 外层 `Layout` 固定测量 `windowWidth×Height`，再按 scale 用 graphicsLayer 缩放；透明 alpha 由 `FloatContext.animatedAlpha` 控制 | 无 | ❌ | 计划照抄；不用 `Modifier.scale`，否则窗口物理尺寸和点击区域会错 |
| W2 | `FloatingChatWindowScreen.kt:362-414` | 12dp 圆角、3dp 边框（拖动/缩放时高亮 primary）、阴影 8dp、裁剪 | `FloatingChatPanel.kt:70-76` 20dp 圆角、无边框 | 🟠 | 计划改 12dp/3dp/8dp |
| W3 | `FloatingChatWindowScreen.kt:431-554` | 48dp 标题栏：历史、全屏、最小化球；中间拖动；右侧回主界面、关闭；按下 hover 背景 | `FloatingChatPanel.kt:79-122` 只有打开 App/最小化/关闭 | 🟠 | 计划补历史/全屏，保留「打开 App」 |
| W4 | `FloatingChatWindowScreen.kt:213-345` | 最近会话浮层：按 `updatedAt` 倒序取 20，显示头像/标题/分组，点 `switchChatLocal`，点外部关闭 | 无 | ❌ | 计划补；头像加载复用主界面现有能力 |
| W5 | `FloatingChatWindowScreen.kt:417-429` | 关闭按钮先 200ms alpha→0 再 `onClose` | 无 | ❌ | 计划补 |
| W6 | `FloatingChatWindowScreen.kt:644-671` | 消息区/输入弹层二选一；点空白 `clearFocus + hide keyboard + onInputFocusRequest(false)` | 无 | ❌ | 计划补 |
| W7 | `FloatingChatWindowScreen.kt:673-885` | 消息列表：过滤 sender=="think"、倒序、`CursorStyleChatMessage`、加载更早/更新、LoadingDots、滚动到底、自动跟随 | `FloatingChatPanel.kt:126-160` 只显示最后 80 条纯文本 | 🟠 | 计划补分页/Loading/滚动；Cursor 样式可用原生消息组件替换，但要保留 tool/think 过滤语义 |
| W8 | `FloatingChatWindowScreen.kt:721-735` | 自动滚到底：有新历史先 `showLatestMessagesForCurrentChat`，否则 `animateScrollToItem(0)` | 无 | ❌ | 计划补 |
| W9 | `FloatingChatWindowScreen.kt:887-925` | `FloatingMessageItem` 把 index/heightMemory 传给 CursorStyleChatMessage；悬浮窗禁用对话框防闪退 | 无 | ❌ | 计划补 |
| W10 | `FloatingChatWindowScreen.kt:927-1099` | 「输入对话框」全屏编辑：标题、附件列表、`OutlinedTextField` 填满、FAB 发送/取消；300ms 后请求焦点 | 无 | ❌ | 计划补；这是 WINDOW 里的展开输入，不是另一个窗口 |
| W11 | `FloatingChatWindowScreen.kt:1101-1181` | 右边缘缩放：16dp 热区、三竖线、拖宽 | 无 | ❌ | 计划补 |
| W12 | `FloatingChatWindowScreen.kt:1183-1262` | 右下角缩放：24dp 热区、按拖动主轴改 scale（0.3-1.0） | 无 | ❌ | 计划补 |
| W13 | `FloatingChatWindowScreen.kt:1264-1345` | 底部高度手柄：12dp 热区、三横线、拖高 | 无 | ❌ | 计划补 |
| W14 | `FloatingChatWindowScreen.kt:1347-1400` | 处理状态条：按 InputProcessingState 映射文案/工具名/错误色，12dp 转圈 | `FloatingChatPanel.kt:108-117` 只有标题下一行 | 🟠 | 计划补完整映射 |
| W15 | `FloatingChatWindowViewModel.kt:16-129` | 窗口 state、拖动、resize（150dp/200dp 下限，屏 80% 上限）、scale 0.3-1.0、`toggleScale` 0.3/0.5/0.7/1.0 | 无 | ❌ | 计划补 |
| W16 | `FloatingChatWindowViewModel.kt:131-158` | 拖动用 `windowState.scale` 修正；附件面板/输入弹层开关 | 无 | ❌ | 计划补 |
| W17 | `FloatingChatWindowScreen.kt:109-155` | `ChatViewHookPluginRegistry` 发 VIEW_OPENED/VIEW_UPDATED/VIEW_CLOSED（含 chatId/workspace/title） | 无 | ❌ | 原生暂无 chatview 插件钩子；先留 TODO，等插件体系对齐再补 |
| W18 | `FloatingChatWindowScreen.kt:220-231` | `ChatToastHost` 显示 chatCore 的 toast 事件 | 无 | ❌ | 计划补；原生先显示错误/状态条即可，不伪造插件 toast |
| W19 | `FloatingChatWindowScreen.kt:157-163` | 根 `FloatContext` 用 `remember(chatService, windowState, density, scope)` 稳定实例，回调用 `rememberUpdatedState` + SideEffect | 无 | ❌ | 计划照抄，避免每次重组重建状态 |
| W20 | `FloatContext.kt:132-197` | 所有 UI 状态/回调的容器：messages、宽高、scale、mode、位置、附件、输入弹层、`pendingScreenSelection` | `FloatingChatPanel.kt` 局部 `input` | 🟠 | 计划新建 `FloatContext`（原生版可精简掉路由字段，但状态语义保留） |

### WINDOW 输入条与附件（`FloatingChatWindowInputControls.kt` / `AttachmentPanel.kt`）

| ID | Operit 文件:行 | 它怎么做 | 原生对应 | 状态 | 差异 |
|---|---|---|---|---|---|
| W21 | `FloatingChatWindowInputControls.kt:73-84` | 附件面板（输入弹层关闭时）与底部输入条二选一 | 无 | ❌ | 计划补 |
| W22 | `FloatingChatWindowInputControls.kt:86-262` | 底部输入条：附件缩略图行、`OutlinedTextField` 最多 2 行、焦点变化通知服务、+ 按钮、发送/停止、回车发送 | `FloatingChatPanel.kt:162-213` 只有单行输入 + 箭头/停止 | 🟠 | 计划补附件/多行/焦点/停止语义 |
| W23 | `FloatingChatWindowInputControls.kt:99-106` | `isProcessing` 来自 `chatCore.isLoading`；焦点变化同时 `setWakeListeningSuspendedForIme` | 无 | ❌ | 计划用 `ChatMirror.isLoading` 替代 |
| W24 | `FloatingChatWindowInputControls.kt:264-303` | 附件面板 5 项：屏幕内容、通知、位置、圈选识别、插件包；前三个走 `onAttachmentRequest("screen_capture"/...)`，圈选切 SCREEN_OCR，插件包弹 `PackageSelectorDialog` | 无 | ❌ | 计划补；原生附件管线已有截屏/通知/位置工具能力可接 |
| W25 | `AttachmentPanel.kt:56-177` | 水平 LazyRow、5 个 72dp 选项、50dp 图标块、展开/收起动画、底部说明 | 无 | ❌ | 计划照抄布局 |
| W26 | `AttachmentPanel.kt:168-176` | 包选择对话框，选中后 `package_attach:<pkg>` | 无 | ❌ | 计划补 |
| W27 | `FloatingChatWindowInputControls.kt:225-290` | 全屏模式的 chip：TTS 静音、屏幕内容、通知、位置、圈选识别 | 无 | ❌ | 见 §八 |

### 消息/指示器组件

| ID | Operit 文件:行 | 它怎么做 | 原生对应 | 状态 | 差异 |
|---|---|---|---|---|---|
| W28 | `ChatIndicators.kt:14-62` | 3 个 6dp 圆点，600ms 跳动，错 160ms | 无 | ❌ | 计划补 |
| W29 | `ChatMessages.kt:11-48` | 单条消息 `MessageItem`（悬浮窗专用，禁用弹窗） | 无 | ❌ | 可复用原生消息渲染，但要保持「工具/think 不炸」 |
| W30 | `ChatModels.kt:3-19` | `ResizeEdge` 8 方向 + `lerp` | 无 | ❌ | 计划补；原生 resize 只做右边/底部/右下，后续补 8 向 |

---

## 八、BALL / VOICE_BALL / RESULT_DISPLAY

| ID | Operit 文件:行 | 它怎么做 | 原生对应 | 状态 | 差异 |
|---|---|---|---|---|---|
| B1 | `SiriBall.kt:47-55` | 4 主色 `#0A84FF/#BF5AF2/#FF375F/#00D4FF` | `FloatingOrb.kt:61-64` 同色 | 🟡 | 无差异（颜色） |
| B2 | `SiriBall.kt:248-305` | 外层 `Box` 尺寸 `ballSize*1.6`；Canvas `ballSize*3.0`；圆半径 `min/2 * pressScale` | `FloatingOrb.kt:142-167` 外层和 Canvas 都是 `size`（112dp），半径 `0.62*size` | 🟠 | 原生把 Canvas 缩小了；必须改回 1.6/3.0 的比例，否则光团边缘/波形裁切不同 |
| B3 | `SiriBall.kt:156-179` | 旋转 15s；呼吸 `0.95→1.0`，3s Reverse | `FloatingOrb.kt:70-88` 旋转 15s；呼吸 `0.93→1.0` | 🟠 | 改 0.95；另有 1.2s activeRotation 是原生自加，用于 Loading 弧，不能替代 1s 的 loadingRotation |
| B4 | `SiriBall.kt:181-246` | 三层音波 1.0→1.2、2.5s、offset 0/833/1666、alpha .5→0，乘 .25/.2/.15 | `FloatingOrb.kt:89-140` 1.0→1.25、alpha .55→0，颜色层顺序不同 | 🟠 | 逐参数改回 1.2/.5 和源码的颜色顺序 |
| B5 | `SiriBall.kt:366-380` | 底部光晕 0.3/0.2、半径 `0.7*breathe`、Screen | `FloatingOrb.kt:201-215` 0.32/0.22、0.75 | 🟠 | 改回 0.3/0.2/0.7 |
| B6 | `SiriBall.kt:382-421` | 4 光斑距离 .2/.25/.22/.23、大小 .7/.65/.6/.68、alpha .8/.5/.2/0 | `FloatingOrb.kt:217-221` 距离同、大小 .72/.66/.6/.68；`drawColorBlob` alpha .85/.5/.18 | 🟠 | 改回 .7/.65/.6/.68、.8/.5/.2/0 |
| B7 | `SiriBall.kt:423-438` | 中心核 0.7/0.6/0.5、半径 .65、Screen | `FloatingOrb.kt:223-238` 0.75/0.62/0.45、.68 | 🟠 | 改回 |
| B8 | `SiriBall.kt:440-458` | 高光偏移 -.25、半径 .35、alpha .5/.25 | `FloatingOrb.kt:240-251` -.26/.38、.55/.18 | 🟠 | 改回 |
| B9 | `SiriBall.kt:460-474` | 外边界 0/.05/.15/0，半径 breathe | `FloatingOrb.kt` 无 | ❌ | 计划补 |
| B10 | `SiriBall.kt:113-134` | 按下缩放 0.85，200ms FastOutSlowIn | 无 | ❌ | 计划补；当前只改粒子没有缩放 |
| B11 | `SiriBall.kt:136-137, 318-321, 477-479` | 粒子只在 `isPressed` 时更新/绘制，前后两层；`BallParticles` 只画 z<=0 | `FloatingOrb.kt:66-68` 用 `pressing || active` | 🟠 | 改成只在 pressing；生成中不要显示粒子（与 Operit 一致） |
| B12 | `BallParticles.kt:46-92` | 2 粒子；调色板随机；固定倾角 60/150、30/120；轨道 1.4-1.5；速度 1.2-2.4 正负；baseSize 20 | `FloatingOrb.kt:323-355` 同结构；baseSize 12 | 🟠 | 改 baseSize 20、`orbitRadius` 和速度公式对齐 |
| B13 | `BallParticles.kt:160-232` | 100 点拖尾、`visibility` 400ms 进/300ms 出、只画 z<=0、边缘 -0.3 淡出、颜色 85% 白 + 15% 原色 | `FloatingOrb.kt:394-434` 同结构；alpha 公式 `(1-progress*0.9)*vis*edge*0.12` 与源码 `1-progress^0.9` 不同 | 🟠 | 逐行改回 `alphaFade = 1 - progress^0.9`、`tailAlpha = alphaBase*0.08` |
| B14 | `SiriBall.kt:481-492` | Loading 弧 270°、1s 旋转、4dp、Round | `FloatingOrb.kt:260-269` 260°、3.5px | 🟠 | 改 270°/4dp；旋转用 1s 的 `loadingRotation`，不要复用 activeRotation 1.2s |
| B15 | `SiriBall.kt:248-302` | Box 的 pointerInput 拖动 → `onMove`，拖动开始 `StatePressing`，结束保存；tap → `onClick` | `FloatingOrb.kt:142-164` 拖动+长按；服务里拖动 | 🟠 | 原生多了长按菜单；Operit 长按代码已注释，这是有意差异（用户要快捷入口） |
| B16 | `SiriBall.kt:140-154` | `ballExploding` 时 100ms 线性 alpha→0，不缩放 | 无 | ❌ | 计划补；模式转场需要 |
| B17 | `FloatingChatBallMode.kt:14-34` | BALL 点击 → WINDOW；`onTriggerResult` → RESULT_DISPLAY，3s 后仍为 RESULT 则回 BALL | 无（点击开面板） | ❌ | 计划补模式语义；现有点击行为只是开面板，不是 `onModeChange(WINDOW)` |
| B18 | `FloatingVoiceBallMode.kt:14-35` | VOICE_BALL 点击 → FULLSCREEN；结果同样 3s 回 BALL | 无 | ❌ | 计划补；注意回的是 `BALL` 不是 VOICE_BALL（源码如此） |
| B19 | `SiriBall.kt:63-101` | 创建并初始化 `SpeechInteractionManager`，申请 ComposeView 焦点，收集识别结果；长按入口注释但服务仍活着 | 无 | ❌ | 计划在 `FloatingSpeechManager` 中实现可用的按住说话入口；不是把整段删掉 |
| B20 | `FloatingResultDisplay.kt:24-56` | 取最后一条 `sender=="ai"` 内容，白底 12dp 圆角气泡；没有则「你好」；点击回 BALL | 无 | ❌ | 计划补；文本要过滤/截断，避免超长气泡 |
| B21 | `FloatingWindowManager.kt:559-571, 862-891` | RESULT_DISPLAY 的窗口参数与球左右侧 gravity 计算 | 无 | ❌ | 计划补 |

---

## 九、FULLSCREEN 模式

### 9.1 窗口与背景

| ID | Operit 文件:行 | 它怎么做 | 原生对应 | 状态 | 差异 |
|---|---|---|---|---|---|
| F1 | `FloatingFullscreenScreen.kt:334-365` | 全屏 `Box`，背景 scrim alpha 0.22；`autoEnteringVoice` 时 0；260ms 动画 | 无 | ❌ | 计划补 |
| F2 | `FloatingFullscreenScreen.kt:341-406` | 系统模糊不可用时：垂直渐变 alpha .30、22dp Compose blur、白色噪点 0.06；260ms | 无 | ❌ | 计划补；系统模糊可用时不要重复画 fallback |
| F3 | `FloatingFullscreenScreen.kt:407-480` | 左上「新对话」；右上「回窗口」「缩成语音球」「关闭」 | 无 | ❌ | 计划补；新对话要读 `autoNewChatGroup` |
| F4 | `FloatingFullscreenScreen.kt:482-587` | 主内容：语音态显示 WaveVisualizer+头像；消息区在语音态底部 52%，否则居中 offset 72dp | 无 | ❌ | 计划补 |
| F5 | `FloatingFullscreenScreen.kt:489-546` | 语音头像 420/320dp（有 Avatar 时），中心点击热区 220dp；无 Avatar 时 300/120/140dp | 无 | ❌ | 原生暂无 Avatar 运行时；先做波形+静态头像，状态机保留，差异见 §10.3 |
| F6 | `FloatingFullscreenScreen.kt:548-586` | 消息显示 `AnimatedContent(effectiveWaveActive)`，语音态 300/150ms 淡入淡出 | 无 | ❌ | 计划补 |
| F7 | `FloatingFullscreenScreen.kt:589-597` | 编辑面板固定在底部，IME 偏移 | 无 | ❌ | 计划补 |
| F8 | `FloatingFullscreenScreen.kt:599-633` | 底部控制栏：可见性 = showBottomControls && !edit && !wave；高度预留 120dp | 无 | ❌ | 计划补 |

### 9.2 底部控制栏与输入

| ID | Operit 文件:行 | 它怎么做 | 原生对应 | 状态 | 差异 |
|---|---|---|---|---|---|
| F9 | `BottomControlBar.kt:113-186` | 整条 `AnimatedVisibility` 淡入+上滑；`translationY=-imeOffset` 跟键盘 | 无 | ❌ | 计划补 |
| F10 | `BottomControlBar.kt:212-291` | 横向 chip 行：TTS 静音、屏幕内容、通知、位置、圈选识别；OCR 选中可清除/进入/退出 | 无 | ❌ | 计划补；SCREEN_OCR 的返回逻辑要看的 `previousMode` |
| F11 | `BottomControlBar.kt:293-377` | 48dp 白色胶囊 `OutlinedTextField`；左侧电话图标进语音态；右侧发送；focus 只有输入非空或 `allowBlankFocus` 才能获焦 | 无 | ❌ | 计划补 |
| F12 | `BottomControlBar.kt:379-473` | 空白时盖一层手势层：按下 450ms 内松手=聚焦键盘；超过=按住说话；上下拖超过 48dp=取消 | 无 | ❌ | 计划补；这是全屏语音的核心交互 |
| F13 | `BottomControlBar.kt:475-517` | 按住时 24 格音量历史从右往左画黑条；取消时白字「松开取消」，否则「松开结束」 | 无 | ❌ | 计划补 |
| F14 | `BottomControlBar.kt:524-678` | 胶囊外发光：8 层圆角渐变、9s 流动、1.8s 鼓包、7 个锚点、HSV 提饱和/亮度 | 无 | ❌ | 计划补；性能敏感，按源码 drawWithCache |
| F15 | `BottomControlBar.kt:683-722` | 私有 BackButton/MinimizeToVoiceBallButton（当前未被主布局引用，保留） | 无 | ❌ | 不单独做；顶部已有 |
| F16 | `BottomControlBar.kt:727-1001` | 麦克风按钮：点击进语音态；长按录音；左右拖 60px 切「编辑/取消」提示；松手按当前区域停止/编辑/取消 | 无 | ❌ | 计划补；与 F12 的按住说话是两套入口，都要保留 |
| F17 | `EditPanel.kt:51-165` | 黑色 80% 圆角面板、120dp 输入框、取消/发送、IME 偏移、300ms 滑入 | 无 | ❌ | 计划补 |

### 9.3 全屏 ViewModel / 语音 / TTS

| ID | Operit 文件:行 | 它怎么做 | 原生对应 | 状态 | 差异 |
|---|---|---|---|---|---|
| F18 | `FloatingFullscreenModeViewModel.kt:39-119` | 状态：wave/edit/input/attachments/OCR/TTS mute/avatar motion；代理 SpeechInteractionManager 的 recording/volume/recognition | 无 | ❌ | 计划新建 `FloatingFullscreenViewModel` |
| F19 | `FloatingFullscreenModeViewModel.kt:187-266` | 最新消息分流：think→「思考中」；ai 有 stream→逐字收集并 TTS；无 stream→静态；首条 AI 消息不朗读 | 无 | ❌ | 计划补 |
| F20 | `XmlTextProcessor.kt:16-63` | 流式剥 XML 标签和标签内容，只留纯文本；连续换行折叠 | 无 | ❌ | 计划补；否则 TTS 会读 `<mood>` |
| F21 | `FloatingFullscreenModeViewModel.kt:268-307` | TTS 按 `TtsSegmenter.nextSegmentEnd` 切句，首句打断；`cleanTextForTts` 清洗；静音只在非语音态生效；首句后抑制识别 1200ms | 无 | ❌ | 计划补；原生只有主聊天「朗读」按钮，不是流式 |
| F22 | `FloatingFullscreenModeViewModel.kt:309-370` | 开始/停止录音；进入语音态先切 UI、可选唤醒问候、再 `startVoiceCapture`；失败则退回非语音态 | 无 | ❌ | 计划补；系统 SpeechRecognizer 的可用性要检测 |
| F23 | `FloatingFullscreenModeViewModel.kt:391-423` | 中心头像点击：AI 忙/说话时先取消并重启录音；否则进/退语音态 | 无 | ❌ | 计划补 |
| F24 | `FloatingFullscreenModeViewModel.kt:414-423` | 识别结果：抑制窗口内丢弃；非空更新活动时间；`autoSendSilence=isWaveActive` | 无 | ❌ | 计划补 |
| F25 | `SpeechInteractionManager.kt:121-190` | 开始录音：检查焦点、清空累计、取唤醒词快照、`ensureMicrophoneForeground`、发 `ACTION_PREPARE_WAKE_HANDOFF`、等 180ms、最多 12 次重试 `startRecognition(zh-CN, continuous, partial)` | 无 | ❌ | 系统 SpeechRecognizer 没有 `ensureMicrophoneForeground`；用前台服务+`RECORD_AUDIO` 权限替代，差异见 §10.2 |
| F26 | `SpeechInteractionManager.kt:192-211` | 停止：取消=立刻 reset；非取消=`isProcessingSpeech`、停止识别、3s fallback 超时 | 无 | ❌ | 计划补 |
| F27 | `SpeechInteractionManager.kt:213-241` | 结果累计：如果新结果不以旧 partial 开头，就把旧 partial 加句号并入 accumulated；静默 2s 自动 finalize | 无 | ❌ | 计划补 |
| F28 | `SpeechInteractionManager.kt:243-276` | 唤醒词前缀剥离（支持正则、标点清洗） | 无 | ❌ | 原生无唤醒词服务；函数保留但默认不启用 |
| F29 | `SpeechInteractionManager.kt:278-304` | 3s fallback、`finalizeSpeechInput` 发文本/提示没听清 | 无 | ❌ | 计划补 |
| F30 | `FloatingFullscreenModeViewModel.kt:144-185` | AI 回合前停录音；`awaitAiTurnAndResumeVoiceCapture` 轮询 120ms，AI 忙过再空闲后自动重启录音；重置空闲计时 | 无 | ❌ | 计划补；用 `ChatMirror.state.streaming` 替代 `isAiBusy` |
| F31 | `FloatingFullscreenModeViewModel.kt:481-521` | 空闲超时：默认读 prefs 1-600s；TTS 朗读中/工具忙碌时不退出；到点 `exitWaveMode`，若是唤醒启动则 `onClose` | 无 | ❌ | 计划补 |
| F32 | `FloatingFullscreenModeViewModel.kt:372-389` | 唤醒问候：开关/文案 prefs，和录音并行（全双工），抑制识别 1200ms | 无 | ❌ | 无唤醒词，暂不触发；保留接口 |
| F33 | `FloatingFullscreenModeViewModel.kt:540-563` | 编辑模式：停止录音、填文本、标题「编辑你的消息」；发送用 VOICE 类型 | 无 | ❌ | 计划补 |
| F34 | `FloatingFullscreenModeViewModel.kt:565-607` | 文本发送：先清 UI，再按 chip 捕获屏幕/通知/位置，OCR 附件已提前加；`onSendMessage(VOICE)`，等 AI 回合恢复录音 | 无 | ❌ | 计划补；原生 `ChatMirror.submit` 无 PromptFunctionType，见 §10.1 |
| F35 | `FloatingFullscreenModeViewModel.kt:609-651` | 语音自动附件：关键词匹配 屏幕/通知/位置/时间，读 prefs 开关与条目 | 无 | ❌ | 计划补；依赖原生设置页新增配置，或先读固定开关 |
| F36 | `FloatingFullscreenModeViewModel.kt:653-743` | 头像表情：`<mood>` 优先、关键词兜底；thinking/playOnce/IDLE 重置；消息 key 去重 | 无 | ❌ | 原生暂无 Avatar 引擎；先保留 emotion 状态与静态头像映射，差异见 §10.3 |

---

## 十、SCREEN_OCR 模式

| ID | Operit 文件:行 | 它怎么做 | 原生对应 | 状态 | 差异 |
|---|---|---|---|---|---|
| O1 | `FloatingScreenOcrScreen.kt:330-360` | 进入即 `AIToolHandler.executeTool("capture_screenshot")`，读返回路径 `BitmapFactory.decodeFile` | 无 | ❌ | 原生用 `DshAccessibilityService.screenshot()` 或现有截图工具；不用 Operit 的 AIToolHandler |
| O2 | `FloatingScreenOcrScreen.kt:400-415` | 全屏 `Image(contentScale=Fit)` 画截图 | 无 | ❌ | 计划补 |
| O3 | `FloatingScreenOcrScreen.kt:417-474` | 遮罩层手绘：`detectDragGestures` 收集轨迹点（间距 ≥2.5px），松手取包围盒；<3 点或宽高 <10px 提示太小 | 无 | ❌ | 计划补；这是「手绘圈选」不是矩形拖选 |
| O4 | `FloatingScreenOcrScreen.kt:150-202` | `clampSelectionRect`：交换反向边、最小 32dp、夹在 overlay 内 | 无 | ❌ | 计划补 |
| O5 | `FloatingScreenOcrScreen.kt:477-505` | 确认后 8 个手柄命中测试（4 角+4 边+移动），拖动更新矩形 | 无 | ❌ | 计划补 |
| O6 | `FloatingScreenOcrScreen.kt:88-122` | 裁剪映射：截图 `Fit` 后的 letterbox 偏移/缩放，换算回原图 px，夹 1px | 无 | ❌ | 计划补；不处理 letterbox 会裁错 |
| O7 | `FloatingScreenOcrScreen.kt:376-398` | 确认时生成预览：裁剪 + 最长边 280px 缩放 | 无 | ❌ | 计划补 |
| O8 | `FloatingScreenOcrScreen.kt:506-687` | 遮罩/挖洞/白框/四角/8 个白点/轨迹三层发光/网格涟漪（HSV 渐变、径向位移） | 无 | ❌ | 计划补；视觉是 Operit 的一部分，不能省 |
| O9 | `FloatingScreenOcrScreen.kt:689-833` | 选区下方/内部放取消+确认 FAB；确认后裁剪、`OCRUtils.recognizeText(HIGH)`（中英双语）、空文本提示 | 无 | ❌ | 需加 ML Kit 中文/拉丁依赖；OCR 质量 HIGH 会 2x 放大 |
| O10 | `FloatingScreenOcrScreen.kt:783-814` | 生成 `AttachmentInfo(fileName=screen_ocr.txt, mime=text/plain)`，内容含 `screen_selection` 坐标、`image_px`、归一化 rect、OCR 正文、内联指令；加进 ChatCore 附件；`pendingScreenSelection=true`；回 `previousMode` | 无 | ❌ | 原生附件模型是 `PendingFile`/`PendingImage`；需要适配成文本附件，坐标格式照抄 |
| O11 | `FloatingScreenOcrScreen.kt:835-881` | 左上 32dp 玻璃关闭按钮，点回 previousMode | 无 | ❌ | 计划补 |

---

## 十一、虚拟屏浮层（`VirtualDisplayOverlay.kt` 全文）

> 原生现有的 `VirtualScreenOverlay.kt` 是 222 行自绘 `LinearLayout`，只在 M50 验过「拖动+×+触摸」。
> 下面每一条都是 Operit 的真实能力；不能拿现有小窗冒充。

| ID | Operit 文件:行 | 它怎么做 | 原生对应 | 状态 | 差异 |
|---|---|---|---|---|---|
| V1 | `VirtualDisplayOverlay.kt:83-110` | `ConcurrentHashMap<String, VirtualDisplayOverlay>` 按 agentId 多实例；`hide(agentId)`/`hideAll()` | `VirtualScreenOverlay.kt:31-47` 单例 object | 🟠 | 计划改成 per-agent 类 + 静态 map |
| V2 | `VirtualDisplayOverlay.kt:171-218` | `TYPE_APPLICATION_OVERLAY` + `WRAP_CONTENT` + `NOT_FOCUSABLE\|LAYOUT_NO_LIMITS` + TRANSLUCENT；ComposeView + ServiceLifecycleOwner 三 owner；`DisposeOnDetachedFromWindow` | `VirtualScreenOverlay.kt:131-146` 同类窗口，但 View 是 LinearLayout | 🟠 | 计划换 ComposeView；生命周期完整 |
| V3 | `VirtualDisplayOverlay.kt:220-247` | `show(displayId)`：已有 view 只显示；首次按 UI 无障碍模式决定是否贴边；`updateLayoutParams` | `VirtualScreenOverlay.kt:56-192` 每次 show 新建；没有 firstShow 状态 | 🟠 | 计划补 firstShow/isSnapped/无障碍模式分支 |
| V4 | `VirtualDisplayOverlay.kt:429-484` | 默认小窗：视频区 0.4×屏宽、0.4×(屏高-状态栏)，左侧再加 56dp 控制栏；居中；全屏/贴边三态切换 | `VirtualScreenOverlay.kt:67-72` 固定 170dp 宽，按视频比例算高 | 🟠 | 计划改 40%+56dp；现宽度/比例不对 |
| V5 | `VirtualDisplayOverlay.kt:486-532` | 拖动：全屏禁止；贴边时只上下；普通窗 x/y 夹在屏内；写 `lastWindowX/Y` | `VirtualScreenOverlay.kt:148-174` 顶栏拖动，无边界/贴边 | 🟠 | 计划补全窗口拖动和边界 |
| V6 | `VirtualDisplayOverlay.kt:342-370` | 贴边：可见 36dp + 越界 12dp，高 48dp；左右按中心判断；y 夹状态栏到屏底；300ms 动画 | 无 | ❌ | 计划补 |
| V7 | `VirtualDisplayOverlay.kt:372-427` | 恢复默认位置：按 lastWindow 0.4 尺寸 + 控制栏；300ms ValueAnimator 同步 x/y/w/h | 无 | ❌ | 计划补 |
| V8 | `VirtualDisplayOverlay.kt:331-340` | 全屏切换：`isFullscreen`、`controlsVisible=false`、`isSnapped=false`、更新参数 | 无 | ❌ | 计划补 |
| V9 | `VirtualDisplayOverlay.kt:762-876` | 非贴边才显示控制：全屏右上「最小化(贴边)/退出全屏/关闭」；小窗点一下显示中央「贴边/全屏/关闭」，3s 自动隐藏 | `VirtualScreenOverlay.kt:175-177` 只有 × | 🟠 | 计划补全套控件/自动隐藏 |
| V10 | `VirtualDisplayOverlay.kt:879-955` | 贴边把手：灰色 12dp 圆角、30dp App 图标（`currentAppPackageName`）或左右箭头；点恢复 | 无 | ❌ | 计划补；需要 `updateCurrentAppPackageName(pkg)` |
| V11 | `VirtualDisplayOverlay.kt:958-982` | 小窗左侧 56dp 自动化控制栏（仅 automationVisible） | 无 | ❌ | 计划补 |
| V12 | `VirtualDisplayOverlay.kt:1227-1285` | `AutomationControlBar`：`current/total`、暂停/继续、退出；Card 半透明 | 无 | ❌ | 计划补 |
| V13 | `VirtualDisplayOverlay.kt:257-292` | `showAutomationControls(total, initialStatus, onToggle, onExit)`、`updateAutomationProgress`、`hideAutomationControls` | 无 | ❌ | 计划补；状态字段在 overlay 内 |
| V14 | `VirtualDisplayOverlay.kt:255` | `captureCurrentFramePng()` 直接取 `ShowerSurfaceView` 当前帧 | 无 | ❌ | 计划补；虚拟屏截图当前只用 `controller.requestScreenshot()`（页面），浮层没有 |
| V15 | `VirtualDisplayOverlay.kt:325-329` | `setShowerBorderVisible(visible)` 彩虹边框 | 无 | ❌ | 计划补；可复用 Compose 画边框 |
| V16 | `VirtualDisplayOverlay.kt:294-323` | `hide(cancelAutomation, reason)`：移除 map、可选取消 agent、`ShowerController.shutdown(agentId)`、生命周期 destroy、removeView、清所有状态 | `VirtualScreenOverlay.kt:195-207` 只 removeView，不 shutdown 虚拟屏 | 🟠 | 计划补；注意页面/浮层共用一个 Shower 流，hide 不能误杀页面 |
| V17 | `VirtualDisplayOverlay.kt:596-618` | 500ms 轮询 `ShowerController.getVideoSize(agentId)`；从有到无时 `PhoneAgentJobRegistry.cancelAgent("Virtual screen closed")` | 无 | ❌ | 计划补；原生没有 PhoneAgentJobRegistry，用 `ChatMirror.stop`/任务注册表替代 |
| V18 | `VirtualDisplayOverlay.kt:619-760` | `ShowerSurfaceView` 始终只有一个；小窗/全屏改 layout，贴边缩到 1dp 但保持附着；`bindController` 每次 update | `VirtualScreenOverlay.kt:114-123` 一个 Surface，但 hide 时直接移除 | 🟠 | 计划和页面互斥：页面打开时 hide 浮层（现有 M50 逻辑保留） |
| V19 | `VirtualDisplayOverlay.kt:658-737` | 全屏触摸转发：单指、只处理 DOWN/MOVE/UP/CANCEL、映射到视频坐标、MOVE 逐历史点注入、`Mutex` 串行、`MotionEvent.obtain` 后 recycle | `VirtualScreenOverlay.kt:209-221` 只 DOWN/MOVE/UP，无历史点/无 Mutex，且小窗也转发 | 🟠 | 计划照抄；小窗是否转发要与 Operit 对齐（只有全屏转发） |
| V20 | `VirtualDisplayOverlay.kt:112-120` | `mapOffsetToRemote` 归一化到 `(vw-1, vh-1)` | 无 | ❌ | 计划补 |
| V21 | `VirtualDisplayOverlay.kt:249-253` | `updateCurrentAppPackageName` 给贴边把手取 App 图标 | 无 | ❌ | 计划补；`VirtualScreenSession.appLabel` 只有应用名，没有包名 |
| V22 | `VirtualDisplayOverlay.kt:984-1220` | 视频未就绪：显示「virtual_display_not_ready」、贴边把手、全屏/小窗控制、自动化栏 | 无 | ❌ | 计划补；原生现在启动即当成功，黑屏没有提示 |
| V23 | `VirtualDisplayOverlay.kt:157-169` | 所有窗口操作 `runOnMainThread`，失败只 log 不崩 | 无 | ❌ | 计划补 |
| V24 | `ScreenCaptureService.kt:20-121` | Android 14+ MediaProjection 前台服务：通知渠道、`FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION`、`isMediaProjectionForegroundReady` 静态标志 | 无；原生虚拟屏走 Shower shell，不申请 MediaProjection | ➖ | 原生截图走无障碍 `takeScreenshot`，不需要 MediaProjection 前台服务；这是平台路线差异，不是漏搬 |

---

## 十二、插件悬浮窗 / UI Debugger（Operit 的「多窗口」延伸）

| ID | Operit 文件:行 | 它怎么做 | 原生对应 | 状态 | 差异 |
|---|---|---|---|---|---|
| P1 | `ToolPkgFloatingWindowRuntime.kt:26-160` | 单例 controller；JSON `command(owner, operation, args)`；capabilities（overlay 权限、positioning、alwaysOnTop、clickThrough、focusable）；5s audit | 无 | ❌ | 原生插件体系已有 JS/Compose DSL 基础，但未接悬浮窗；本轮不做，列后续 |
| P2 | `ToolPkgFloatingWindowHost.kt:42-263` | overlay + ComposeView + ServiceLifecycleOwner；options 宽高/xy/clickThrough/focusable；拖动回报几何；hide 用 INVISIBLE 保留会话；close 销毁 | 无 | ❌ | 同上；这是插件窗口，不是聊天悬浮窗 |
| P3 | `ToolPkgFloatingWindowSession.kt:34-296` | 解析 UiRoute、租 JS 引擎、首渲染 compose_dsl、state rerender、action dispatch、onLoad | 无 | ❌ | 同上 |
| P4 | `UIDebuggerWindowManager.kt:38-196` | 56dp 蓝球 → 全屏 UIDebugger，拖动/展开/关闭 | 无 | ➖ | 开发调试工具，不属于用户功能；不搬 |

---

## 十三、原生与 Operit 的有意差异（必须写理由）

### 13.1 聊天核心：共享 `AppViewModel` 而不是 Service 自建 `ChatServiceCore`
- Operit：`FloatingChatService` 自己 `getCore(FLOATING)`，悬浮窗和主界面可以是两个 core。
- 原生：`ChatMirror` 借主界面 core（`ChatMirror.kt:20-42`）。
- 理由：原生没有 Operit 的 `ChatRuntimeHolder` 多槽体系；共享一个 ViewModel 可以保证**同一条会话、同一个引擎、不会双开**，也避免把会话存储再复制一份。用户要求「切会话不中断」已经在 M49 用 `LiveChat` 做过，共享 core 更稳。
- 代价：悬浮窗不能脱离主界面进程独立跑；Service 被杀后需要主界面重建 `ChatMirror`。这一点要在实现时用 `ChatMirror.host` 空值兜底 UI（显示「打开鲸歌」），不是假装能发。

### 13.2 语音识别后端：系统 `SpeechRecognizer`，没有 Operit 的唤醒词/全双工服务
- Operit：`SpeechServiceFactory` + `VoiceServiceFactory` + `AIForegroundService` + `SpeechPrerollStore`，支持唤醒词、连续识别、TTS、音频焦点、麦克风前台服务。
- 原生：目前只有主聊天的一个「朗读」按钮用 `android.speech.tts.TextToSpeech`（`ChatScreen.kt:1941-1948`），没有语音识别服务。
- 计划：新增 `FloatingSpeechManager`，用 `android.speech.SpeechRecognizer` 做按住说话/连续识别，用 `TextToSpeech` 做流式朗读；UI/状态机照 Operit。
- **明确差异**：不做「唤醒词常驻监听」和「全双工 TTS 边播边听」。原因：需要额外的离线唤醒模型、常驻音频前台服务与音频路由策略，原生没有这套依赖；硬做会变成另一个语音 App，不是悬浮窗移植。这个差异要在设置页写明，不能悄悄省。
- 若用户要求唤醒词也必须一致，需要单独立项（模型/权限/功耗/回声消除），不能塞进 M51。

### 13.3 头像：保留 emotion 状态，但不接 DragonBones/Live2D 运行时
- Operit：`AvatarRepository`、`AvatarControllerFactoryImpl`、`AvatarView`、`AvatarEmotionManager`，支持模型/表情/动作。
- 原生：没有 Avatar 运行时。计划在 FULLSCREEN 语音态显示静态助手头像 + 呼吸/波形；`<mood>` 只驱动表情标签/光效，不驱动骨骼动画。
- 理由：Avatar 运行时是独立大模块（模型格式、渲染器、资源包），不属于悬浮窗；先保证语音态布局/状态机/按钮位置一致，头像位留可插拔接口。

### 13.4 主题：原生用 `DshTheme/DshTheme2`，不搬 Operit 的 `SerializableColorScheme`
- Operit：`COLOR_SCHEME`/`TYPOGRAPHY` Parcelable extra + SharedPreferences JSON，`FloatingWindowTheme` 独立 MaterialTheme。
- 原生：已有自己的设计系统；悬浮窗直接用 `DshTheme2`，不引入 Operit 主题序列化。
- 理由：避免两套主题系统打架；视觉以原生「鲸歌」为准（用户要求 Operit 主体 + Rikka 排版）。

### 13.5 状态指示器：先做 TOP_BAR，彩虹边框后补
- Operit：`FULLSCREEN_RAINBOW` 默认，全屏彩虹边框 + 细线流动；`TOP_BAR` 是顶部卡片。
- 原生：先做顶部「自动化进行中」卡片，满足可见性；彩虹边框需要单独绘制，放到后续。
- 理由：状态指示器不是主交互；先保证用户知道 AI 在跑，避免本轮范围爆炸。表里状态会如实写 ❌/🟠，不写「已搬」。

### 13.6 虚拟屏截图：用 Shower `requestScreenshot`/`captureCurrentFramePng`，不引入 MediaProjection 服务
- Operit：`ScreenCaptureService` 是 MediaProjection 前台服务（Android 14 要求），另有 `VirtualDisplayOverlay.captureCurrentFramePng()` 取 Shower 帧。
- 原生：虚拟屏本身走 Shower（shell），页面截图用 `controller.requestScreenshot()`；普通屏截图用无障碍 `takeScreenshot`。
- 理由：原生没有用 MediaProjection 录屏，硬加一个前台服务会多一个常驻通知和权限流程，且与 Shower 流冲突。悬浮窗「截当前帧」用 Shower 的 `captureCurrentFramePng` 对齐 Operit 的浮层路径。

---

## 十四、实现顺序（按依赖，不按好看）

1. **地基**：`FloatingMode` / `FloatingWindowState` / `FloatingWindowManager` / `FloatingChatService` 重写；
   球和面板先接到同一个 manager，保证现有功能不回退。
2. **WINDOW**：标题栏、消息列表、输入条、附件、缩放、历史会话、处理状态、IME 焦点。
3. **BALL 逐参数**：SiriBall/BallParticles 全参数对齐；长按菜单保留为原生差异；补 RESULT_DISPLAY。
4. **FULLSCREEN**：窗口/背景/顶部按钮/消息/底部控制栏/编辑面板；语音用系统 `SpeechRecognizer`，
   TTS 用 `TextToSpeech`，状态机照 Operit；Avatar 留接口。
5. **SCREEN_OCR**：截图/圈选/8 手柄/裁剪/ML Kit/附件/返回；先做真机截图验收。
6. **虚拟屏浮层**：per-agent、40%+56dp、贴边/全屏/控制/自动化/截图/触摸历史；保留 M50 的
   「页面打开时 hide 浮层」的单 sink 约束。
7. **插件悬浮窗**：本轮不做，docs/76 保留为后续项（用户 11 项清单第 10 项另立）。

---

## 十五、验收清单（真机必须逐项截图）

| 编号 | 操作 | 预期 | 截图名（计划） |
|---|---|---|---|
| A1 | `am start ... --ez dsh_floating true` | 球出现，不挡状态栏，拖动/贴边/重启位置保留 | `76-ball.png` |
| A2 | 点球 | 变 WINDOW，标题栏/消息/输入完整 | `76-window.png` |
| A3 | 输入文字发送 | 流式逐字，状态条随 InputProcessingState 变 | `76-window-stream.png` |
| A4 | 点全屏按钮 | 全屏，背景模糊/回退，顶部 4 按钮 | `76-fullscreen.png` |
| A5 | 全屏底部电话/麦克风 | 语音态波形+头像+底部消息；按住说话有音量条 | `76-voice.png` |
| A6 | 全屏圈选识别 | 截图/手绘/8 手柄/OCR 结果附件 | `76-ocr.png` |
| A7 | 圈选确认 | 回全屏，OCR chip 勾选，附件在输入条 | `76-ocr-attached.png` |
| A8 | 球长按菜单 | 菜单项完整，能开主界面/新对话/停止/终端/虚拟屏/设置/关闭 | `76-ball-menu.png` |
| A9 | 球生成中 | 白色 Loading 弧 + 状态；完成后 RESULT_DISPLAY 3s | `76-ball-loading.png` |
| A10 | 虚拟屏小窗 | 40% 视频 + 56dp 控制栏，拖动/点显示控件/3s 隐藏 | `76-vd-small.png` |
| A11 | 虚拟屏贴边 | 36dp 把手/App 图标，点恢复 | `76-vd-snap.png` |
| A12 | 虚拟屏全屏 | 全屏视频，右上最小化/退出/关闭，触摸能操作虚拟屏 | `76-vd-full.png` |
| A13 | 自动化控制 | 步骤 `n/N`、暂停/继续、退出 | `76-vd-auto.png` |
| A14 | 虚拟屏截图 | 浮层 `captureCurrentFramePng` 得到当前帧（日志/文件） | `76-vd-capture.png` |
| A15 | 页面/浮层互斥 | 打开虚拟屏页面时浮层收起，页面流正常 | `76-vd-page.png` |

---

## 十六、阅读记录（逐份，实际 `read` 行号）

> 这一节就是 docs/75 §三 的「读透?」勾选依据。行号是本轮 `read` 的实际总行数；
> `wc` 口径差异已忽略。全部文件都读到了实现，不是只 grep 签名。

### ui/floating（29 份）

| 文件 | 实际行数 | 读到 | 关键内容 |
|---|---:|---|---|
| `FloatContext.kt` | 197 | 1-197 | Composable 上下文、回调、窗口状态、输入弹层 |
| `FloatingChatWindow.kt` | 233 | 1-233 | AnimatedContent 6 模式、转场时长、Toast |
| `FloatingMode.kt` | 10 | 1-10 | 6 枚举 |
| `FloatingWindowTheme.kt` | 130 | 1-130 | MaterialTheme/字号 |
| `ui/ball/SiriBall.kt` | 529 | 1-529 | 光团、音波、粒子、Loading、手势、语音初始化 |
| `ui/ball/BallParticles.kt` | 233 | 1-233 | 2 粒子、3D 轨道、100 点拖尾、只画 z<=0 |
| `ui/ball/FloatingChatBallMode.kt` | 35 | 1-35 | 点击→WINDOW；结果 3s→BALL |
| `ui/ball/FloatingVoiceBallMode.kt` | 35 | 1-35 | 点击→FULLSCREEN；结果 3s→BALL |
| `ui/ball/FloatingResultDisplay.kt` | 56 | 1-56 | 最后 AI 文本白气泡；点回 BALL |
| `ui/fullscreen/FloatingFullscreenMode.kt` | 23 | 1-23 | 入口委托 |
| `ui/fullscreen/screen/FloatingFullscreenScreen.kt` | 650 | 1-650 | 全屏布局/背景/顶部/语音态/消息/编辑/控制栏 |
| `ui/fullscreen/viewmodel/FloatingFullscreenModeViewModel.kt` | 754 | 1-754 | 语音/TTS/编辑/附件/空闲/表情全部状态机 |
| `ui/fullscreen/components/BottomControlBar.kt` | 1003 | 1-1003 | chip/胶囊/按住说话/音量条/发光/麦克风 |
| `ui/fullscreen/components/EditPanel.kt` | 167 | 1-167 | 编辑面板 |
| `ui/fullscreen/components/GlassyChip.kt` | 88 | 1-88 | 玻璃 chip |
| `ui/fullscreen/components/MessageDisplay.kt` | 167 | 1-167 | 倒序消息、渐变遮罩、语音预览 |
| `ui/fullscreen/components/WaveVisualizerSection.kt` | 269 | 1-269 | 波形/头像/AI Loading 环 |
| `ui/fullscreen/XmlTextProcessor.kt` | 64 | 1-64 | 流式剥 XML |
| `ui/pet/AvatarEmotionManager.kt` | 108 | 1-108 | mood/关键词表情、剥标签 |
| `ui/screenocr/FloatingScreenOcrMode.kt` | 10 | 1-10 | 入口 |
| `ui/screenocr/screen/FloatingScreenOcrScreen.kt` | 883 | 1-883 | 截图/手绘/8 手柄/裁剪/OCR/附件 |
| `ui/window/screen/FloatingChatWindowScreen.kt` | 1402 | 1-1402 | 标题/历史/消息/输入/缩放/状态 |
| `ui/window/viewmodel/FloatingChatWindowViewModel.kt` | 172 | 1-172 | 窗口 state/resize/scale/drag |
| `ui/window/components/AttachmentPanel.kt` | 229 | 1-229 | 5 项附件面板 |
| `ui/window/components/ChatIndicators.kt` | 62 | 1-62 | 三点 Loading |
| `ui/window/components/ChatMessages.kt` | 48 | 1-48 | MessageItem |
| `ui/window/components/FloatingChatWindowInputControls.kt` | 304 | 1-304 | 输入条/附件/焦点 |
| `ui/window/models/ChatModels.kt` | 19 | 1-19 | ResizeEdge/lerp |
| `voice/SpeechInteractionManager.kt` | 322 | 1-322 | 识别/累计/静默/唤醒词/超时 |

### services/floating（6 份）+ service（1 份）

| 文件 | 实际行数 | 读到 | 关键内容 |
|---|---:|---|---|
| `services/floating/FloatingWindowManager.kt` | 1197 | 1-1197 | 单 view、窗口参数、转场、焦点/IME、状态条 |
| `services/floating/FloatingWindowState.kt` | 124 | 1-124 | 窗口/模式/位置持久化 |
| `services/floating/ToolPkgFloatingWindowHost.kt` | 263 | 1-263 | 插件 overlay host |
| `services/floating/ToolPkgFloatingWindowRuntime.kt` | 161 | 1-161 | 插件 controller/capabilities/audit |
| `services/floating/ToolPkgFloatingWindowSession.kt` | 296 | 1-296 | compose DSL 渲染/action |
| `services/floating/UIDebuggerWindowManager.kt` | 196 | 1-196 | UI debugger 球/全屏 |
| `services/FloatingChatService.kt` | 850 | 1-850 | 前台/chat core/通知/intent/生命周期 |

### 虚拟屏（2 份）

| 文件 | 实际行数 | 读到 | 关键内容 |
|---|---:|---|---|
| `ui/common/displays/VirtualDisplayOverlay.kt` | 1296 | 1-1296 | per-agent/贴边/全屏/自动化/触摸/截图 |
| `core/tools/system/ScreenCaptureService.kt` | 121 | 1-121 | Android 14 MediaProjection 前台服务 |

**结论：38/38 份都读到了实现；docs/75 的清单可以逐份打勾。**

---

## 十七、M51 实现进度（2026-11-08 23:40，最新；覆盖前面表里的旧状态）

> 前面 §五-§十二 的「状态」列是 22:43 的**审计基线**，不是现在的代码状态。这一节是实际进度，
> 下一棒以本节为准。本轮（第四十五棒）已把虚拟屏浮层、OCR、全屏 scrim、WINDOW 附件面板、
> 按住说话真机验完；主表未逐格回填，不拿它当验收结论。

### 17.1 已写进代码（编译通过）

| 模块 | 已实现 | 关键文件 |
|---|---|---|
| 地基 | 6 模式枚举、窗口状态持久化、单 ComposeView manager、模式转场、焦点/IME 重试、状态条（TOP_BAR）、Service 通知/WakeLock/自动退出/keep-if-exists | `FloatingMode.kt`、`FloatingWindowState.kt`、`FloatingWindowManager.kt`、`FloatingBallService.kt` |
| WINDOW | 标题栏（历史/全屏/圈选/收起/主界面/关闭）、最近会话、消息列表（Markdown/工具卡/流式）、处理状态、审批/提问卡、停止本轮、截图附件、缩放/高度手柄、IME | `ui/window/FloatingWindowScreen.kt` |
| WINDOW 附件面板 | 5 项（屏幕内容/通知/位置/圈选识别/插件包）、横向 LazyRow、50dp 图标块、展开收起、插件选择对话框 | `ui/window/FloatingWindowScreen.kt` |
| BALL | SiriBall/BallParticles 全参数对齐（0.95 呼吸、1.2 音波、270° 弧、100 点拖尾、只画 z<=0）、长按菜单、结果气泡 | `FloatingOrb.kt`、`ui/ball/FloatingResultDisplay.kt` |
| FULLSCREEN | 半透明 scrim、顶部新对话/回窗口/缩语音球/关闭、消息列表、chip 行、胶囊输入、系统 SpeechRecognizer/TTS、按住说话/上滑取消、TTS 静音、回合结束自动再听 | `ui/fullscreen/FloatingFullscreenScreen.kt`、`FloatingSpeechManager.kt` |
| SCREEN_OCR | 整屏截图（先隐藏 overlay）、手绘轨迹、包围盒、8 手柄、裁剪、ML Kit 中英 OCR、坐标文本附件、返回 | `ui/screenocr/FloatingScreenOcrScreen.kt`、`OcrEngine.kt` |
| 虚拟屏浮层 | per-agent 实例、视频按真实宽高比（1088×1920 → 341×602）、56dp 自动化栏仅在自动化时占位、贴边/全屏、控制层 3s 隐藏、App 图标把手、触摸转发（全屏）、当前帧截图、自动化步骤/暂停/退出、断流提示 | `VirtualScreenOverlay.kt`、`VirtualScreenSession.kt`、`VirtualScreenManager.kt` |
| 截图通道 | 无障碍优先，失败时 Shizuku `screencap -p` 走 stdout（不落文件，绕开 Android/data 写权限） | `DeviceShell.runBytes`、`FloatingWindowManager.capturePhysicalScreen`、`AppViewModel.attachScreenshot` |

### 17.2 真机已验证（截图）

| 项 | 截图 | 结论 |
|---|---|---|
| 球（60dp、贴边位置持久化） | `docs/shots/76-state-2316.png`、`76-ball-3.png`、`76-ball-4.png` | 显示/位置正常；光团比 Operit 原图偏小是窗口 60dp 裁切所致（与 Operit 同结构） |
| WINDOW 单窗口 + 尺寸 | `docs/shots/76-window-8.png`、`76-window-9.png`、`76-window-11.png` | 460×640px = 288×400dp × 0.8；标题/输入/历史/缩放都在；位置按保存值/球中心打开 |
| 标题栏圈选识别 | `docs/shots/76-ocr-2.png`、`76-ocr-3.png`、`76-ocr-4.png` | 隐藏 overlay 截图 → 手绘 → 8 手柄 → 确认 → `screen_ocr.txt` 附件 → 回 WINDOW |
| OCR 文字识别 | 日志 `FloatingOcr: crop=368x64 chars=27` | ML Kit 中英模型识别出中文（「需求，或者让它直接动手。」）；截图 65KB/116KB 均能取到 |
| WINDOW 历史浮层 | `docs/shots/76-history-1.png` | 会话历史（标题/条数/时间/主界面/关闭），点条目切会话 |
| OCR 关闭键 | `docs/shots/76-ocr-9.png` | `statusBarsPadding` 后 X 在状态栏下方（center y≈128）可点，点完回 WINDOW |
| FULLSCREEN scrim | `docs/shots/76-fullscreen-3.png` | `0x380B0F17` + 系统 48dp blur，能透出后面 App；chip/输入/语音都显示 |
| 虚拟屏浮层小窗 | `docs/shots/78-after-ai-preview.png` | 341×602 @ [363,920]：高度取可用屏高 40%、宽度按视频比例；无黑边、画面不压扁（2026-11-09 修复，见 docs/78） |
| 虚拟屏浮层全屏 | `docs/shots/76-vd-full-3.png`、`78-after-fullscreen-touch.png` | frame [0,0][720,1570]（此前 +64 偏移已修）；QQ 铺满，点群能开会话 |
| 虚拟屏贴边/恢复 | `docs/shots/78-after-snap.png`、`78-after-restore.png` | 把手 96×96 @ [648,920]；点把手回 [341×602]@[363,920]，**画面仍在**（2026-11-09 修掉贴边黑屏） |
| 虚拟屏控制层 | `docs/shots/76-vd-controls2.png` | 点视频出现 贴边/全屏/关闭 三键，3s 自动隐藏 |
| WINDOW 附件面板 | `docs/shots/76-attach-1.png`、`76-attach-3.png` | 5 项横排；点「屏幕内容」→ `screenshot-*.png` 附件芯片，无红色报错 |
| FULLSCREEN 按住说话 | `docs/shots/76-voice-1.png`、`76-voice-4.png` | 按住/松开手势已触发识别；本机无 RecognitionService，提示「系统没有可用的语音识别服务」 |

### 17.3 本轮已修（含根因）

1. **OCR 截图失败**（`76-ocr-1.png` → 已修）：根因不是无障碍没开，而是旧实现把 `screencap -p`
   写到 `/sdcard/Android/data/<pkg>/files/ocr-*.png`，Shizuku shell 在这台 ROM 上对该路径的写权限
   不稳定，`ShellCommandResult.success` 没到成功分支，最后只剩 `A11y.screenshot()`；而设备无障碍
   实际是关的（`enabled_accessibility_services=null`）。
   现在改走 **stdout**：`DeviceShell.runBytes("screencap -p", ShellIdentity.SHELL)` 直接读
   `IRemoteProcess` 的字节流再 `BitmapFactory.decodeByteArray`，不落文件、不依赖无障碍。
   日志证据：`FloatingWindowManager: shell screencap bytes=65081 / decode=720x1570`。
2. **虚拟屏浮层整体下移 64px**：`TYPE_APPLICATION_OVERLAY + TOP|START` 的坐标原点默认在状态栏下方，
   小窗 y 从 516 变 580、全屏从 [0,0][720,1570] 变 [0,64][720,1634]（底部被裁）。
   已加 `FLAG_LAYOUT_IN_SCREEN`；现在小窗 [160,516]、全屏 [0,0][720,1570] 都对齐 `getRealMetrics`。
3. **贴边后恢复位置丢失**：`applyParams()` 动画分支把 `lastX/lastY` 改成了把手坐标，恢复时跳到屏幕边角。
   已拆出 `snapRestoreX/Y`，贴边前记位置、点把手恢复；全屏也不再覆盖 last 位置。
4. **全屏控制键点不动**：全屏窗口铺到 y=0 后，控制条落在状态栏（0..64px）的触摸区里，点击被系统拦走。
   已把控制条下移到 `status_bar_height + 8dp`；贴边/退出全屏/关闭三键真机可点。
5. **WINDOW 旧 prefs 把窗口钉在左上角**：v1 自绘面板留下 `floating_height=250`、`floating_x/y=0`。
   已加 `floating_prefs_version=2` 一次性迁移（只重置 `floating_*`，不碰会话/主题/模型），默认
   Operit `200,200 / 300×400dp / 0.8`（宽被 `0.8×屏宽` 压到 288dp，与 Operit 夹取一致）。
6. **应用进程 displayMetrics 被虚拟屏污染**：`FloatingWindowState`、`FloatingWindowManager`、
   `VirtualScreenOverlay` 全部改 `DisplayManager.getDisplay(DEFAULT_DISPLAY).getRealMetrics()`；
   悬浮窗/虚拟屏在跑过 VirtualHomeActivity 后仍按 720×1570 算。
7. **截图附件只依赖无障碍**：`AppViewModel.attachScreenshot()` 现在同样先 a11y、失败走 Shizuku
   `screencap`；WINDOW 的「屏幕内容」在无障碍关闭时也能出 `screenshot-*.png` 附件。
8. **WINDOW 附件面板只有截图**：补齐 Operit `AttachmentPanel` 的 5 项（屏幕内容/通知/位置/圈选识别/
   插件包），插件包给 `plugin-<id>.txt`（id/name/version/enabled/format/path/tools）。
9. **FULLSCREEN 只有点按**：改为按住说话 + 上滑 60dp 取消（`awaitEachGesture`），TTS 静音沿用；
   AI 回合结束后等 TTS 读完再自动恢复录音（不再发送后 400ms 就抢麦）。
10. **死代码**：`device/floating/FloatingChatPanel.kt` 已删（全仓库无引用）。
11. **OCR 关闭键点不到**：全屏 overlay 从 y=0 铺起，32dp 的 X 正好落在状态栏触摸区（0..64px），
    点击被系统拦走（实测点 (57,113)/(64,96) 都没反应）。已加 `statusBarsPadding()`，
    X 移到状态栏下方（center y≈128），点完正常回 WINDOW。
12. **重启恢复到瞬态模式**：`floating_mode=SCREEN_OCR` 会被持久化，重启后进入空截图页（只有黑底+关闭）。
    `FloatingWindowState.restoreMode()` 现在把 SCREEN_OCR / RESULT_DISPLAY 退回 WINDOW；
    BALL / WINDOW / FULLSCREEN 仍按上次恢复（Operit 也不持久化 OCR 模式）。

### 17.3.1 2026-11-09 补修（m53，详见 docs/78）

用户真机反馈「缩小再点回来屏幕没了」「大黑边」「点全屏整个手机用不了」。根因与修复：

1. **贴边把 SurfaceView 从组合里移除** → `surfaceDestroyed`，恢复时新解码器只拿到最近 ≤120 帧、
   没有 SPS/PPS，`MediaCodec.configure` 起不来 → 永久黑屏。现在贴边保持 1dp 附着（对齐
   Operit V18），`ShowerController` 另外缓存 SPS/PPS 在重绑时补发。
2. **黑屏 + 全屏 = 整屏触摸被浮层吃掉**：修掉黑屏后全屏正常；`hasDisplay=false` 页补了
   「关闭预览」文字按钮，真断流也有出口。
3. **大黑边**：自动化面板改为仅自动化时占 56dp；预览尺寸按视频真实宽高比算（341×602），
   默认右下角，避开浮窗默认位置。
4. **悬浮窗内部消息误显示**：`time-context` / `goal` / `tool-jobs` 等 `source.kind` 注入消息
   在 WINDOW/FULLSCREEN 不再按 user 气泡显示（`FloatingMessageFilter.kt`）。

### 17.4 仍未完成 / 已知差异（下一棒优先）

1. **通知附件未接**：~~需要 `NotificationListenerService` + 「通知使用权」授权流程；现在点「通知」
   会 toast「需要通知使用权，这一版未接入」。位置附件同理（未申请 `ACCESS_COARSE_LOCATION`，
   也缺运行时授权 UI）。~~ **第四十六棒已完成**（`DshNotificationListenerService` +
   `AttachmentCapture` + `LocationAttachActivity`），真机验收见 §十八。
2. **语音识别是设备限制**：`cmd package query-services -a android.speech.RecognitionService`
   = `No services found`（本机没有 RecognitionService，装了 Google/讯飞语音才行）。
   手势、错误提示、TTS 静音、自动再听逻辑都已就位；TTS 引擎存在
   （`com.yuemeng.speechsuite`，`tts_default_synth` 已设）。要在本机跑通识别得内置离线 ASR，
   属新工程，不在 M51 范围。
3. **虚拟屏自动化栏**（步骤 n/N、暂停、退出）只有 AI 自动化流程会调
   `showAutomationControls`，本轮没触发真机验证；代码在 `OverlayInstance.AutomationBar`。
   **第四十六棒已补调试入口并真机验证**（`--ez dsh_virtual_automation true`，见 §十八）。
4. **插件包选择对话框**：本轮是列出 native 插件清单并附 `plugin-<id>.txt`；Operit 是选 zip 包。
   原生插件不是 zip，语义已按 docs/76 §七 W26 记为**有意差异**。
5. **WINDOW 其他细节**（历史浮层、审批/提问卡、长文滚动、双指缩放、TOP_BAR 状态条）上一轮实现，
   本轮没有逐项重验；`76-window-*` 只覆盖标题/输入/附件/尺寸。
6. **主表状态未逐格回填**：§五-§十二 还是 22:43 审计基线；完成项以本节 17.1-17.3 为准。

### 17.5 下一棒直接可执行

```powershell
cd D:\dsh\native
cmd /c "set JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot&& .tools\gradle-8.9\bin\gradle.bat :app:assembleDebug --console=plain > build-debug.log 2>&1"
C:\Android\platform-tools\adb.exe install --no-streaming -r -t app\build\outputs\apk\debug\app-debug.apk
C:\Android\platform-tools\adb.exe shell am force-stop xin.dshaa.nativeapp
C:\Android\platform-tools\adb.exe shell am start -n xin.dshaa.nativeapp/.MainActivity --ez dsh_floating true
# 虚拟屏浮层（不经过模型工具调用，直接起 1080×1920 虚拟屏 + QQ）：
C:\Android\platform-tools\adb.exe shell am start -n xin.dshaa.nativeapp/.MainActivity --ez dsh_virtual_overlay true
```

球窗口中心 = `dumpsys input` 里 `name=<hash> xin.dshaa.nativeapp` 的 `frame` 中心（注意状态栏 64px：
小窗/球的 `mAttrs y` 要 +64 才是 input frame y；虚拟屏浮层加了 `LAYOUT_IN_SCREEN`，frame 就是屏幕坐标）。
不要再用截图目测坐标点球。

---

## 十八、第四十六棒：通知/位置附件 + WINDOW IME + 底部夹取 + 自动化栏（2026-11-09 00:10 最新）

> 本节覆盖 §十七：§17.4 的 1（通知/位置附件）、3（虚拟屏自动化栏）已完成；
> 另外修掉两个真机问题（WINDOW 输入法弹不出来、窗口贴底被裁 64px）。
> **状态以 §十七 + §十八 为准**；§五-§十二 的状态列仍是 22:43 审计基线，§18.4 逐 ID 回填。

### 18.1 本轮改动（文件级）

| 文件 | 改动 |
|---|---|
| `device/notify/NotificationFeed.kt`（新） | 最近通知环形缓冲（30 条、key 去重、`activeNotifications` 播种），只留 appLabel/title/text/time/ongoing |
| `device/notify/DshNotificationListenerService.kt`（新） | `NotificationListenerService`，连接/断开/新通知/移除都进 feed，日志 tag `DshNotify` |
| `device/attach/AttachmentCapture.kt`（新） | 通知拼 `notifications-*.txt`、位置拼 `location-*.txt`，统一 `AttachOutcome(ok/message/needsGrant)` |
| `device/attach/LocationCapture.kt`（新） | 权限判断、`getCurrentLocation`（8s 超时）→ `getLastKnownLocation` 兜底、provider 选择 |
| `device/attach/LocationAttachActivity.kt`（新） | 透明 Activity：运行时定位授权 → 取点 → Geocoder 取地址（2.5s best-effort）→ 进待发附件 |
| `device/DevicePermissions.kt` | 新增 `notificationAccessGranted` / `openNotificationAccessSettings` / `locationGranted` |
| `chat/AppViewModel.kt` + `chat/ChatMirror.kt` | 新增 `attachTextFile(name, text)` / `attachText(name, text)`：文本附件进 `pendingFiles`，只读/生成中拒绝 |
| `device/floating/ui/window/FloatingWindowScreen.kt` | 附件面板「通知/位置」接真实现；底部说明改文案；输入框加 interactionSource + 按下兜底 |
| `device/floating/ui/fullscreen/FloatingFullscreenScreen.kt` | 「通知」chip 接 `AttachmentCapture`；新增「位置」chip；chip 行改为横向滚动 |
| `device/floating/FloatingWindowManager.kt` | WINDOW 改常驻可获焦（去掉 `NOT_FOCUSABLE`，加 `NOT_TOUCH_MODAL|WATCH_OUTSIDE_TOUCH`）；`contentH = screenH - statusBarHeight` 修正底部夹取；`setFocusable` 在 WINDOW 不再把 flag 加回；补 focus/updateViewLayout 失败日志 |
| `MainActivity.kt` | 调试入口 `--ez dsh_virtual_automation true`（浮层+自动化栏+2s/4s 进度）、`--ez dsh_attach_location true`；虚拟屏启动抽成 `startDebugVirtualOverlay()`，`onNewIntent` 也生效 |
| `AndroidManifest.xml` + `strings.xml` | 定位两权限；`DshNotificationListenerService`（BIND_NOTIFICATION_LISTENER_SERVICE）；`LocationAttachActivity`（透明、不导出） |

### 18.2 真机验收（截图）

| 项 | 截图 | 结论 |
|---|---|---|
| 通知未授权 → 跳授权页 | `docs/shots/76b-3-notify-toast.png`、`76b-4-notify-grant.png` | toast「还没有通知使用权…」+ 打开「设备和应用通知」，鲸歌在列表里（不允许） |
| 通知使用权授予 | `settings get secure enabled_notification_listeners` | 含 `xin.dshaa.nativeapp/.device.notify.DshNotificationListenerService`；日志 `DshNotify: listener connected active=11 feed=7` |
| 通知附件 | `docs/shots/76b-6-notify-attached.png` | 7 条 → `notifications-20261108-234425.txt` 芯片 + toast「已添加通知附件（7 条）」；日志 `notifications attached n=7 chars=672` |
| 通知附件端到端（真正发给模型） | `docs/shots/76b-54-typed.png`、`76b-55-sent.png` | 附件芯片 + 文本 `test` 发送；`--es dsh_dump_request` 落盘的请求第 3 个 text 块 = `[附件: notifications-*.txt]` + 8 条通知全文（appLabel/包名/时间/常驻/正文）；模型回复按「系统/连接、消息、推广」分类，并指出 shell 包名的异常 |
| 位置运行时授权 | `docs/shots/76b-7-loc-perm.png` | 系统框「要允许“鲸歌”获取位置信息吗」+ 确切/大致位置；点「使用时允许」 |
| 位置附件 | `docs/shots/76b-8-loc-granted.png`、`76b-9-loc-attached.png` | `location-20261108-234443.txt`（139 字符）进待发附件；日志 `lat=37.32315 lon=122.13038 provider=network acc=30.0 address=true` |
| WINDOW 输入法 | `docs/shots/76b-36-ime-good.png`、`76b-37-typed2.png`、`76b-38-typed-hidden.png` | 点输入框键盘弹出（ImeTracker `onShown`）；`input text hello` 进输入框；收起键盘后输入框显示 `hello` |
| WINDOW 贴底夹取 | `docs/shots/76b-33-window-clamped.png` | 从屏底球打开 → frame `[260,930][720,1570]`，不再出现 `[260,994][720,1634]` 的 +64 越界 |
| 虚拟屏自动化栏 | `docs/shots/76b-40-automation.png`、`76b-41-auto-now.png`、`76b-43-auto-paused.png`、`76b-44-auto-exit.png` | 1/5→3/5→5/5 会刷新；点暂停变「继续」（日志 `automation toggle paused=true`）；点 × 栏消失（日志 `automation exit`） |
| 虚拟屏浮层基础 | 日志 `apply animate=false full=false snap=false target=160,516 400x602 screen=720x1570` | 40%+56dp、屏幕坐标（`LAYOUT_IN_SCREEN`）保持上一轮结论 |

### 18.3 本轮根因与修复

1. **通知附件从 0 到 1**：原实现只有一句 toast。新增 `NotificationListenerService`
   （清单里 `BIND_NOTIFICATION_LISTENER_SERVICE`，系统「通知使用权」页授权后才绑定）+
   `NotificationFeed`（内存 30 条，按 key 去重；listener 重连时用 `activeNotifications` 播种）。
   附件入口先查 `NotificationManagerCompat.getEnabledListenerPackages`，没授权就跳
   `Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS`，**不会假装有数据**。
2. **位置附件必须走 Activity**：`ACCESS_*_LOCATION` 是运行时权限，Service/悬浮窗不能自己弹框。
   新增透明 `LocationAttachActivity`（`Theme.Translucent.NoTitleBar`、不导出）承接授权，
   授权后 `getCurrentLocation`（8s 超时）取一次；失败退各 provider 的 `lastKnown`；
   地址用 `Geocoder` best-effort（2.5s），拿不到只给坐标。产物是 `location-*.txt`。
3. **WINDOW 键盘弹不出来（本轮最重要修复）**：窗口默认 `FLAG_NOT_FOCUSABLE`；点输入框后
   Compose 焦点会变（紫色边框），但窗口不可获焦，系统在 `ImeTracker: onFailed at
   PHASE_CLIENT_REQUEST_IME_SHOW` 直接拒绝。实测 `Modifier.onFocusChanged` 和
   TextField `interactionSource` 在本机都没能把回调送到 Service（`setFocusable` 日志不出现）。
   修复：**WINDOW 窗口常驻可获焦**（`NOT_TOUCH_MODAL | WATCH_OUTSIDE_TOUCH`），
   `setFocusable(false)` 在 WINDOW 不再把 `NOT_FOCUSABLE` 加回去；键盘弹出、键入、收起都正常。
   代价：WINDOW 会持有输入焦点（返回键先给窗口），点外部收焦点（S16）仍未做。
4. **窗口贴底越界 64px**：`TYPE_APPLICATION_OVERLAY + Gravity.TOP` 的 `params.y` 原点在状态栏下方，
   原 clamp 用 `screenH - height`，实际 frame 会到 `1570+64`。新增
   `contentH = screenH - statusBarHeight`，所有窗口/球 y 夹取改用它；实测贴底窗口
   `[260,930][720,1570]` 正好到屏底。
5. **全屏 chip 的「通知」是假的**：原来只把 status 文案改成「暂未接入」。现在和 WINDOW
   共用 `AttachmentCapture`；并补上 Operit 的「位置」chip（ChipsRow 6 个 chip 改为横向滚动）。
6. **自动化栏只有 AI 能触发**：新增 `--ez dsh_virtual_automation true` 调试入口，
   起 1080×1920 虚拟屏 + 浮层后直接 `showAutomationControls(5, …)`，2s/4s 推进进度，
   暂停/退出回调打 `DshDebug` 日志。三个交互本轮全部真机点到。

### 18.4 状态回填（逐 ID；覆盖 §五-§十二 对应行的「状态」列）

> 图例同 §〇：✅ 真机验证 / 🟡 代码完成未单独验 / 🟠 部分实现 / ❌ 未做 / ➖ 有意不做。
> 「本轮」= 第四十六棒；「上轮」= 第四十五棒（§十七）。

**§五 Service 与生命周期**

| ID | 状态 | 依据 / 备注 |
|---|---|---|
| S1 | 🟠 | Service/state/manager/前台通知就位；无崩溃计数、无 Service 自建 chat core |
| S2 | 🟠 | 渠道 LOW、ongoing、点通知回主界面；缺 `setShowBadge(false)`/category |
| S3 | 🟠 | manifest 是 `specialUse`，不是 `dataSync`；Android 15 前台类型校验通过 |
| S4 | 🟡 | `acquireWakeLock`/`releaseWakeLock` 已接（超时 10 分钟，onDestroy 释放） |
| S5 | ❌ | 崩溃计数/60s 内 >3 次停用未做 |
| S6 | 🟠 | `INITIAL_MODE`/`KEEP_IF_EXISTS`/`AUTO_EXIT_AFTER_MS` 已接；`AUTO_ENTER_VOICE_CHAT`/主题 extras 无 |
| S7 | 🟡 | `AUTO_EXIT_AFTER_MS` → `closeSelf()` 已接，未单独截图 |
| S8 | 🟡 | `onTaskRemoved` 重启服务已接 |
| S9 | 🟡 | `onLowMemory`/`onTrimMemory` 保存窗口状态已接 |
| S10 | 🟠 | onDestroy 释放 WakeLock/保存状态/destroy manager；无 ASR/TTS 取消、无停止广播 |
| S11 | 🟠 | `closeSelf` = prepareForExit + `ChatMirror.stop` + stopSelf；无 ASR/TTS 取消 |
| S12 | 🟠 | ChatMirror 暴露发送/停止/截图/文本附件/审批/提问/切会话；无附件移除回调 |
| S13 | 🟠 | manager 有 `setFloatingWindowVisible`/持久隐藏字段；Service 未对外暴露 API |
| S14 | ✅ | 单 ComposeView + Lifecycle/ViewModelStore/SavedState 三 owner（窗口能起即证明） |
| S15 | 🟠 | destroy 先隐藏状态条/cancelFocus/removeView；无统一 `cancelFocusBeforeExit` |
| S16 | ❌ | 全屏透明「点外部收焦点」View 未做（WINDOW 已改常驻可获焦，暂不需要） |
| S17 | 🟠 | 窗口显隐状态字段在；无对外持久隐藏 API |
| S18 | 🟠 | TOP_BAR 状态指示器上轮已做；彩虹边框未做 |
| S19 | ✅ | 六模式 LayoutParams 已实现（本轮实测 BALL/WINDOW/FULLSCREEN/OCR 的 frame） |
| S20 | ✅ | 全屏 cutout + 系统 48dp blur（`76-fullscreen-3.png`） |
| S21 | 🟡 | `isAtEdge` 5px 容差已实现 |
| S22 | ✅ | `updateWindowSize`/`updateViewLayout` 已接（缩放、手柄） |
| S23 | ✅ | 模式切换保中心（球→窗本轮实测 `[544,1470]` → `[260,930]`） |
| S24 | 🟠 | 六模式切换已实现；RESULT_DISPLAY 本轮未重验 |
| S25 | ✅ | 拖动/边界写回 state（本轮多次移动窗口、贴底夹取生效） |
| S26 | 🟠 | IME 本轮修好（常驻可获焦）；点外部收焦点未做 |
| S27 | 🟡 | `getComposeView()` 已有 |

**§六 六种模式窗口参数**

| 模式 | 状态 | 依据 / 备注 |
|---|---|---|
| BALL / VOICE_BALL | 🟡 | 60dp、非聚焦、`LAYOUT_NO_LIMITS`、位置持久化；贴边/转场细节见 §六 |
| WINDOW | ✅ | 尺寸/scale/位置持久化 + 本轮 IME/贴底修复；frame 实测 `[260,930][720,1570]` |
| FULLSCREEN | ✅ | scrim/blur/chips/按住说话（`76-fullscreen-3`、`76-voice-1`） |
| SCREEN_OCR | ✅ | 截图→手绘→8 手柄→OCR→附件（`76-ocr-2/3/4/9`） |
| RESULT_DISPLAY | 🟡 | 3s 回 BALL 已实现，本轮未重验 |
| 转场 | 🟠 | AnimatedContent 时长照抄；`isTransitioning` 防抖有，未逐毫秒对比 |

**§七 WINDOW（W1-W30）**

| ID | 状态 | 依据 / 备注 |
|---|---|---|
| W1 | ✅ | 固定测量 + `placeRelativeWithLayer(scale)`，不是 `Modifier.scale`（窗口 460px 实测） |
| W2 | 🟠 | 12dp 圆角 + 3dp 边框（拖动/缩放高亮）；阴影只有 elevation 近似 |
| W3 | ✅ | 标题栏：历史/全屏/圈选/最小化/主界面/关闭 + 拖动（`76b-11/27`） |
| W4 | ✅ | 最近会话浮层（`76-history-1.png`） |
| W5 | ❌ | 关闭前 200ms alpha→0 未做 |
| W6 | 🟠 | 点空白 `clearFocus` 未接（靠返回键/IME 收起） |
| W7 | ✅ | 消息列表 Markdown/工具卡/流式/倒序（复用聊天核心） |
| W8 | ✅ | 有新消息 `animateScrollToItem(0)` |
| W9 | 🟠 | 消息项自定义渲染；无 heightMemory 复用 |
| W10 | ❌ | 输入对话框（展开编辑）未做 |
| W11 | ✅ | 右边缘拖宽手柄（截图里紫色竖条） |
| W12 | 🟠 | 右下角 scale 拖拽未做；只有右宽 + 底高 |
| W13 | ✅ | 底部高度手柄（截图里紫色横条） |
| W14 | 🟠 | `ProcessingStatus` 显示 streaming 文案/错误；无工具名/12dp 转圈映射 |
| W15 | 🟡 | 窗口 state/尺寸/scale/位置持久化（`FloatingWindowState`） |
| W16 | 🟡 | 拖动按 scale 修正；附件面板开关 |
| W17 | ❌ | chatview 插件钩子未做 |
| W18 | 🟠 | 错误/状态条有；未接 ChatToastHost |
| W19 | 🟠 | 无 FloatContext；参数逐个传 |
| W20 | 🟠 | 无 FloatContext；状态拆在 state/ChatMirror |
| W21 | ✅ | 附件面板与输入条二选一（`AnimatedVisibility`） |
| W22 | ✅ | 输入条：附件芯片行、3 行、+、发送/停止、IME 发送 |
| W23 | 🟠 | `isProcessing=chat.streaming`；无唤醒监听挂起（无唤醒词） |
| W24 | ✅ | 5 项；屏幕/通知/位置本轮全部真机可用（`76b-6`、`76b-8`） |
| W25 | ✅ | LazyRow、72dp 选项、50dp 图标块、展开动画、底部说明（`76b-2/12`） |
| W26 | 🟠 | 插件包对话框列 native 插件，附 `plugin-<id>.txt`（有意差异） |
| W27 | ✅ | 全屏 chip：语音/静音/屏幕/通知/位置/圈选（位置本轮补，行可横滚） |
| W28 | 🟠 | 只有 `CircularProgressIndicator`；无 3 点跳动 |
| W29 | 🟠 | 自定义 MessageItem（Markdown），工具/think 不炸 |
| W30 | 🟠 | 只有右/底/右下，无 8 方向 |

**§八 BALL / VOICE_BALL / RESULT_DISPLAY（B1-B21）**

| ID | 状态 | 依据 / 备注 |
|---|---|---|
| B1 | ✅ | 四主色与 Operit 一致 |
| B2 | ✅ | 外层 1.6 / Canvas 3.0 比例已改 |
| B3 | ✅ | 呼吸 0.95/3s、旋转 15s |
| B4 | ✅ | 三层音波 1.2/2.5s/alpha .5 |
| B5 | ✅ | 底部光晕 .3/.2、半径 .7 |
| B6 | ✅ | 四光斑大小/alpha .8/.5/.2/0 |
| B7 | ✅ | 中心核半径 .65 |
| B8 | ✅ | 高光 -.25/.35 |
| B9 | 🟠 | 最外边界未逐像素对比 |
| B10 | 🟡 | 按下缩放 0.85（200ms） |
| B11 | ✅ | 粒子只在 pressing 显示 |
| B12 | ✅ | baseSize 20、倾角/轨道/速度对齐 |
| B13 | ✅ | 100 点拖尾、`1 - progress^0.9` alpha |
| B14 | ✅ | Loading 弧 270°/4dp/1s |
| B15 | ✅ | 拖动 + 长按菜单（原生有意差异） |
| B16 | ✅ | `ballExploding` 100ms 淡出 |
| B17 | ✅ | BALL 点击→WINDOW；回合结束→RESULT_DISPLAY→3s→BALL |
| B18 | ✅ | VOICE_BALL 点击→FULLSCREEN |
| B19 | 🟠 | `FloatingSpeechManager` 可用（全屏按住说话）；无唤醒词 |
| B20 | ✅ | 结果气泡白底 12dp，取最后一条 AI 文本 |
| B21 | ✅ | RESULT_DISPLAY 窗口参数/左右侧 gravity |

**§九 FULLSCREEN（F1-F36）**

| ID | 状态 | 依据 / 备注 |
|---|---|---|
| F1 | ✅ | scrim `0x380B0F17` + 260ms（`76-fullscreen-3`） |
| F2 | 🟠 | 系统 blur 48dp 已验证（`76-fullscreen-3`）；Compose fallback 未验 |
| F3 | ✅ | 顶部新对话/回窗口/缩语音球/关闭 |
| F4 | 🟠 | 语音态波形+静态头像/消息区布局 |
| F5 | 🟠 | 静态头像；无 Avatar 运行时（§13.3） |
| F6 | 🟠 | AnimatedContent 简化 |
| F7 | ❌ | 编辑面板（EditPanel）未做 |
| F8 | 🟠 | 底部控制栏显隐简化 |
| F9 | 🟠 | 无上滑淡入动画 |
| F10 | ✅ | chip 行（TTS 静音/屏幕/通知/位置/圈选） |
| F11 | ✅ | 胶囊输入 + 发送 |
| F12 | ✅ | 按住说话 450ms / 上滑 60dp 取消（`76-voice-1/4`） |
| F13 | 🟠 | 有「松开结束/上滑取消」文案；无 24 格音量历史 |
| F14 | ❌ | 胶囊 8 层外发光未做 |
| F15 | ➖ | 未引用，不单独做 |
| F16 | 🟠 | 麦克风按住说话；无左右拖切编辑/取消 |
| F17 | ❌ | 编辑面板未做 |
| F18 | ✅ | 状态在本屏 `remember` |
| F19 | 🟠 | 新消息逐条朗读；流式分词简化 |
| F20 | ❌ | XML 标签剥离未做 |
| F21 | 🟠 | TTS 静音 + 首句打断；无 1200ms 抑制窗 |
| F22 | ✅ | SpeechRecognizer 可用性检测/失败回退 |
| F23 | 🟠 | 声波中心点击重启未做 |
| F24 | 🟠 | 识别结果直接进输入 |
| F25 | ✅ | 系统识别 start；本机无 RecognitionService 时提示明确 |
| F26 | ✅ | cancel/stop 分开 |
| F27 | 🟠 | partial 累加简化 |
| F28 | ➖ | 无唤醒词（有意差异 §13.2） |
| F29 | 🟠 | 3s 超时提示 |
| F30 | ✅ | 回合结束等 TTS 读完自动再听 |
| F31 | ❌ | 空闲超时退出未做 |
| F32 | ➖ | 无唤醒词，问候不触发 |
| F33 | ❌ | 编辑模式未做 |
| F34 | 🟠 | 文本发送 + 附件；无 VOICE 类型 |
| F35 | ❌ | 语音关键词自动附件未做 |
| F36 | ❌ | Avatar 表情未做 |

**§十 SCREEN_OCR（O1-O11）**

| ID | 状态 | 依据 / 备注 |
|---|---|---|
| O1 | ✅ | 隐藏 overlay → Shizuku `screencap -p` stdout → Bitmap（不落文件） |
| O2 | ✅ | Fit 全屏显示截图 |
| O3 | ✅ | 手绘轨迹（≥2.5px）→ 包围盒 |
| O4 | ✅ | clamp：反向边交换、最小 32dp、夹在屏内 |
| O5 | ✅ | 8 手柄拖动 |
| O6 | ✅ | letterbox 映射回原图 px |
| O7 | ✅ | 280px 预览 |
| O8 | 🟠 | 遮罩/挖洞/白框/四角/8 白点已画；网格涟漪/三层发光简化 |
| O9 | ✅ | ML Kit 中英 OCR（日志 `crop=368x64 chars=27`） |
| O10 | ✅ | `screen_ocr.txt`（坐标+正文）进待发附件 |
| O11 | ✅ | 关闭键加 `statusBarsPadding` 后可点（`76-ocr-9`） |

**§十一 虚拟屏浮层（V1-V24）**

| ID | 状态 | 依据 / 备注 |
|---|---|---|
| V1 | ✅ | per-agent `ConcurrentHashMap` |
| V2 | ✅ | ComposeView + Lifecycle/ViewModelStore/SavedState + `DisposeOnDetachedFromWindow` |
| V3 | 🟠 | `show(displayId)` 复用；首次贴边分支简化 |
| V4 | ✅ | 40% 屏宽 + 56dp 栏（`76-vd-4`，日志 400x602） |
| V5 | ✅ | 拖动边界/贴边只上下 |
| V6 | ✅ | 贴边 36+12dp、300ms（`76-vd-snap-3`） |
| V7 | ✅ | 恢复贴边前位置（`76-vd-restore`） |
| V8 | ✅ | 全屏 `[0,0][720,1570]`（`76-vd-full-3`） |
| V9 | ✅ | 控制层 3s 自动隐藏（`76-vd-controls2`） |
| V10 | ✅ | App 图标把手/左右箭头 |
| V11 | ✅ | 左侧 56dp 自动化栏 |
| V12 | ✅ | n/N + 暂停/继续 + 退出（本轮 `76b-40/41/43/44`） |
| V13 | ✅ | `show/update/hideAutomationControls` 本轮真机 |
| V14 | 🟡 | `captureCurrentFramePng` 已实现，未截图 |
| V15 | 🟡 | 彩虹边框已实现，未验 |
| V16 | 🟠 | `hide()` 只 removeView，不 shutdown 虚拟屏 |
| V17 | ❌ | 断流 500ms 轮询取消 agent 未做 |
| V18 | ✅ | 单 SurfaceView；页面打开时 hide 浮层（M50 保留） |
| V19 | ✅ | 全屏触摸转发 DOWN/MOVE/UP；历史点/Mutex 未做 |
| V20 | ✅ | 映射到 `(vw-1, vh-1)` |
| V21 | 🟠 | `updateCurrentAppPackageName` 已实现，调用方少 |
| V22 | ✅ | 未就绪黑底提示 |
| V23 | ✅ | `runOnMainThread` + try/catch |
| V24 | ➖ | MediaProjection 前台服务不需要（Shower 路线，§13.6） |

**§十二 插件悬浮窗（P1-P4）**

| ID | 状态 | 依据 / 备注 |
|---|---|---|
| P1 | ❌ | 插件悬浮窗 controller 未做（用户 11 项清单第 10 项另立） |
| P2 | ❌ | 未做 |
| P3 | ❌ | 未做 |
| P4 | ➖ | UI Debugger 属开发工具，不搬 |

### 18.5 仍未完成 / 已知问题（按优先级）

1. **WINDOW 贴底时键盘会盖住窗口**：overlay 不会被 IME resize，`imePadding` 只作用于窗口内部；
   窗口在屏幕下半部时输入框会被键盘挡住（本轮截图 `76b-36/37`）。下一步应监听 IME inset，
   把窗口整体上移（或在键盘弹出时自动贴到上半屏）。Operit 是动态改 flag + insets，我们还没搬。
2. **通知缓冲不持久化**：进程被杀后历史丢失，重连时只播种当前通知栏（有意取舍：通知正文敏感）。
3. **权限中心没有「通知使用权 / 定位」两行**：入口只在悬浮窗附件面板；要加到
   `PermissionCenter` 需同时处理「可选权限不算进 allReady」，别把引导页卡住。
4. ~~**附件内容未做「发给模型」的端到端验证**~~：已验（见 18.2「通知附件端到端」）：
   `--es dsh_dump_request <路径>` 落盘的请求体里能看到附件 text 块，模型按通知内容做了分类回复。
5. 其余同 §17.4：语音识别是设备限制（无 RecognitionService）、插件包是有意差异、
   WINDOW 审批/提问卡/长文滚动/TOP_BAR 本轮未重验。

### 18.6 下一棒直接可执行

```powershell
cd D:\dsh\native
cmd /c "set JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot&& .tools\gradle-8.9\bin\gradle.bat :app:assembleDebug --console=plain > build-debug.log 2>&1"
C:\Android\platform-tools\adb.exe install --no-streaming -r -t app\build\outputs\apk\debug\app-debug.apk
C:\Android\platform-tools\adb.exe shell am force-stop xin.dshaa.nativeapp
# 悬浮窗
C:\Android\platform-tools\adb.exe shell am start -n xin.dshaa.nativeapp/.MainActivity --ez dsh_floating true
# 虚拟屏浮层 / 自动化栏
C:\Android\platform-tools\adb.exe shell am start -n xin.dshaa.nativeapp/.MainActivity --ez dsh_virtual_overlay true
C:\Android\platform-tools\adb.exe shell am start -n xin.dshaa.nativeapp/.MainActivity --ez dsh_virtual_automation true
# 位置附件（要有已打开会话）
C:\Android\platform-tools\adb.exe shell am start -n xin.dshaa.nativeapp/.MainActivity --ez dsh_attach_location true
# 通知使用权（shell 代授权，验收用）
C:\Android\platform-tools\adb.exe shell cmd notification allow_listener xin.dshaa.nativeapp/.device.notify.DshNotificationListenerService
C:\Android\platform-tools\adb.exe shell cmd notification post -t "张三" dsh_test_1 "晚上一起吃饭？"
```

WINDOW 附件面板的图标中心（720×1570、窗口 `[260,930][720,1570]` 时）：屏幕内容 ≈(379,1011)、
通知 ≈(461,1011)、位置 ≈(572,1011)、圈选 ≈(683,1011)；先点 `+` 展开面板。
输入框在窗口底部一行；WINDOW 现在可获焦，点输入框键盘会直接弹出。


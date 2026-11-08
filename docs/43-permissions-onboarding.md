# 43 · 权限与能力页 + 首启引导（2026-11-07）

## 为什么

真机回归（`docs/40 §七` 修完闪退）后暴露三个问题：

1. **权限授予没有页面**：`DeviceShell.requestShizukuPermission()` 写了但没有任何入口调用，
   无障碍/悬浮窗/通知/存储也没地方看状态。用户只能猜「为什么虚拟屏用不了」。
2. **没有使用指导**：新装/清数据后直接掉进聊天页，不知道 Shizuku 是什么、去哪儿开。
3. **输入条太简陋**：一条细输入框 + 一排小字芯片，用户明确要求「像 Operit 一样」。

## 一、输入条（`ui/chat/ChatComposer.kt`）

> **2026-11-07 23:2x 已被 v2 取代**：按 Operit `AgentChatInputSection` 重排（输入在上、模型 pill + 齿轮/＋/发送在下、进度环、全屏编辑），
> 详见 `docs/45-composer-and-fixes.md` §一。下面保留 v1 记录，仅作历史对照。

照 Operit `AgentChatInputSection` 的分层，裁到手机可用的部分：

| 层 | 内容 |
|---|---|
| 卡片上方 | 引用条（长按消息→引用）、待发图片缩略图 + 「＋」追加块 |
| 卡片本体 | 圆角 26dp、1dp 描边、3dp 阴影；左「＋」(38dp)、中间多行输入（最多 8 行）、右侧 42dp 渐变发送键 |
| 卡片底部 | 模型芯片、计划模式芯片（显示「计划：开启/退出/普通」）、右侧「Enter 发送 / 发送 / 生成中…」 |
| 附件面板 | `ModalBottomSheet` 3 列图标网格：相册/拍照/截图/计划模式/目标/提醒（有 goal 才可点） |

- 发送键：空输入且无图时是灰色（不可点），有字/有图时品牌渐变，流式中变成「停止」。
- 输入为空时点发送键不再弹「语音输入待接入」Toast（旧版行为），直接无效。
- 删除旧实现里 `SettingsChip` 那一排小字（信息挪进卡片底部行）。
- **踩坑**：`Modifier.navigationBarsPadding()` 必须加在输入区根节点上，否则卡片会压进手势条。

## 二、权限与能力（`ui/permissions/PermissionCenter.kt`）

一个独立页（设置 → 功能与工具 → 权限与能力；2026-11-09 起入口从工具箱移到功能与工具，见 docs/79），六项能力：

| 能力 | 判定 | 动作 |
|---|---|---|
| Shizuku | `Shizuku.pingBinder()` + `checkSelfPermission()` 四态（未装/未运行/未授权/已授权） | 安装→商店；未运行→打开 Shizuku；未授权→`requestPermission` |
| 无障碍 | `AccessibilityManager.getEnabledAccessibilityServiceList` 匹配本 App 组件 | `android.settings.ACCESSIBILITY_DETAILS_SETTINGS`（带回退到总列表） |
| 悬浮窗 | `Settings.canDrawOverlays` | `ACTION_MANAGE_OVERLAY_PERMISSION` |
| 通知 | `NotificationManagerCompat.areNotificationsEnabled` | 应用通知设置页 |
| 所有文件访问 | `Environment.isExternalStorageManager()` | `ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION` |
| 电池优化 | `PowerManager.isIgnoringBatteryOptimizations` | `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`（manifest 加 `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`） |

- 顶部一张进度卡：「已就绪 n/6」+ 进度条 + 刷新按钮。
- **状态刷新**：`ON_RESUME` 事件 + 未全就绪时 900ms 轮询（Shizuku binder 是异步到的，
  只靠 Activity 的 resume 回调在「从 Shizuku 授权框回来」时会读到旧状态）。
- Shizuku 回调在 binder 线程 → 用 `rememberCoroutineScope().launch` 切回主线程再改状态。

## 三、首启引导（`ui/onboarding/OnboardingScreen.kt`）

3 页 `HorizontalPager`：① 欢迎（是什么/能干什么）② 权限（直接复用 `PermissionChecklist`，与设置页同一份实现）
③ 完成（按实际就绪数给文案 + 三条上手提示）。

- 落地判定：`dsh` prefs 的 `onboarding_done`；`AppViewModel.onboardingDone` 是 Compose 状态，
  点「开始使用/跳过」置 true，所以不用重启。
- 调试通道：`--ez dsh_reset_onboarding true` 清标记（冷启重看一遍）。
- 返回键：引导内先回上一页（`BackHandler`），第一页才交回系统。

## 四、验证（真机 PLT120 / Android 15）

| 项 | 结果 |
|---|---|
| `:app:assembleDebug` | BUILD SUCCESSFUL |
| 冷启（引导未完成） | 直接进引导第 1 页；点「跳过/开始使用」后落聊天 |
| 引导第 2 页 | 六项状态与系统一致（首次显示 1/6；所有文件访问是在系统里已授予的，显示 2/6） |
| 设置 → 权限与能力 | 与引导同款数据；无障碍「去开启」跳到系统无障碍设置页（验证 topResumedActivity） |
| 输入条 | 卡片/渐变发送键/芯片行/虚线占位渲染正常；键盘弹起时卡片上浮不被遮 |

## 五、后续

1. 工作区改造：新建可选任意目录（应用目录外）、目录选择器、切换面板仿 Operit。
2. 虚拟屏：Shizuku 已授权仍启动失败，下一轮查 `ShowerScreen`/`shower-server.jar` 拉起链路。
3. 无障碍开启后回来，虚拟屏「截图」与设备工具体验需要一起回归。

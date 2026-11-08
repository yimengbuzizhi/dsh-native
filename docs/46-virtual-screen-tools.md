# 46 · 虚拟屏给 AI 用（Operit 同款 display 参数）+ Home 语义 + 输入框 clamp 修复

日期：2026-11-07 23:23–23:57

## 一、问题：虚拟屏只给人看，AI 没有工具

上一轮的虚拟屏页面只能手动点。用户问「虚拟屏是给 AI 用的，为什么没给 AI 做用虚拟屏的工具」，
并强调「看 Operit 咋做的你就咋做」。

结论：Operit 没有另起一套 `virtual_*` 工具。它在现有设备工具上加可选 `display`：
传了 display 就 `input -d <id> ...` 走 shell，不传就走无障碍/主屏。本轮照这个形状接。

## 二、工具形状（Operit DebuggerUITools 同款）

`AndroidDeviceTools` 变成 12 个工具（总工具数 44）：

| 工具 | 虚拟屏行为 |
|---|---|
| `start_virtual_display` | 新增。确保 Shower server + display；带 `app` 就启动，不带就开虚拟桌面 |
| `close_all_virtual_displays` | 新增。`shutdown()` + `ShowerServerManager.stopServer()` |
| `tap` | 有 `display`：0..999 按 `videoSize` 换算成像素，`input -d <id> tap x y` |
| `long_press` | `input -d <id> swipe x y x y 800`（Operit 同款） |
| `swipe` | `input -d <id> swipe x1 y1 x2 y2 duration` |
| `press_key` | `input -d <id> keyevent <key>`；`home` 特判成虚拟桌面；`recents` 明确报错（系统级，不能按 display 隔离） |
| `input_text` | `KEYCODE_CLEAR` → App 写剪贴板 → `KEYCODE_PASTE`，中文也能进（`input text` 只支持 ASCII） |
| `launch_app` | `ShowerController.launchApp(pkg)`，只落在虚拟屏 |
| `capture_screenshot` | `ShowerController.requestScreenshot()`，存 `workspace/screenshots/virtual-<id>-<stamp>.png` |
| `device_info` | 多一行 `virtual display: id=… size=… app=…` |

`start_virtual_display` 返回 display id 和尺寸，模型把 id 传给后续工具即可。

代码：
- `device/shower/VirtualScreenManager.kt`：生命周期、app 名/包名解析、home、status。
- `device/tools/AndroidDeviceTools.kt`：`displayId()` 解析与各工具分支。
- `core/prompt/.../tools-android.json`：给 7 个已有工具加 `display`，新增 2 个生命周期工具。

## 三、Home 不再把主屏切桌面

实测（Android 15 / PLT120）：`KeyEvent.setDisplayId(13)` + `InputManager.injectInputEvent` 的
`KEYCODE_HOME` 仍然只切**主屏**；`adb shell input -d 13 keyevent HOME` 也一样。原因在
PhoneWindowManager 把 HOME 当全局键处理，不是 Shower 的 displayId 没设。

所以 Home 不走 HOME 键，改成在虚拟 display 上启动自己的桌面 Activity：

- 新增 `VirtualHomeActivity`：4 列应用网格，点应用直接 `ShowerController.launchApp` 到虚拟屏；
  返回键被吃掉（避免虚拟屏变黑）。
- `VirtualScreenManager.openHome()` 用 `am start --display <id> -f 0x10000000 -n <pkg>/.VirtualHomeActivity`。
- `VirtualScreenPage` 的 🏠 按钮和 `press_key(display, home)` 都走这里。
- 返回键仍用 `controller.key(4)`（实测按 display 注入有效）；最近键从工具栏删掉，
  改为「尺寸」菜单，避免误触主屏最近任务。

真机确认：`Display #18` 的 `topResumedActivity=VirtualHomeActivity`，主屏 `MainActivity` 不受影响。

## 四、尺寸可选 + 回前台黑屏

- 尺寸预设（`VirtualDisplaySpec.presets`）：1080×1920/320、1080×2400/420、720×1280/320。
  存在 `vs_width/vs_height/vs_dpi`，页面「☰」打开；运行中会 stop→start 重建。
- 真实宽度仍按编码器 16 对齐（1080→1088）。
- 回前台黑屏：`ShowerSurfaceView` 的 Surface 换了，但 Compose 不重建。
  `VirtualScreenPage` 现在加 `LifecycleEventObserver`，`ON_RESUME` 再 `ensureDisplay` 一次接 sink。
- `AndroidManifest` 加 `QUERY_ALL_PACKAGES`，虚拟桌面/应用选择器能枚举全部已安装应用。

## 五、用户反馈修复：模型配置输入框逐字 clamp

QQ 群反馈（详见 §八）：

- 最大输出：想输入多位数字，第一位就被 `setMaxTokens` 夹到 1024，字段立刻跳值。
- 模型名称：删空会立刻 `ifBlank { deepseek-flash }`，没法先清空再改名。

根因：`value = settings.maxTokens.toString()` + 每次 `onValueChange` 直接写 SettingsStore 并 clamp。
修法（`ModelConfigScreen` + `SettingsTextField.onFocusChanged`）：

- 编辑期只改本地 `maxTokensText/temperatureText/modelText`，不校验、不落盘；
- 失焦 / 键盘「完成」时才 `setMaxTokens` / `setTemperature` / `setModel`，然后把规范化后的值同步回输入框；
- 模型名允许编辑中为空；最大输出允许中间态 `2`、`25`、`256`；
- `SettingsTextField` 新增可选 `onFocusChanged`，其他页面不传就保持原行为。

## 六、调试通道：App 已在前台时 `am start --es dsh_send` 被吞

以前 MainActivity 只在 `onCreate` 读 debug extras；App 已运行时 `am start` 走 `onNewIntent`，
消息静默丢弃。现在 `onNewIntent` 对已知 debug extras 执行 `recreate()`，重新走 onCreate/AppViewModel。

## 七、真机验证

- 模型严格按提示调了三个工具：
  `start_virtual_display` → `press_key {display:18,key:home}` → `capture_screenshot {display:18}`，
  返回路径 `/storage/emulated/0/screenshots/virtual-18-20261107-234505.png`。
- 该图即虚拟桌面：`docs/shots/46-virtual-home.png`（1088×1920，4 列应用网格）。
- 工具调用卡片：`docs/shots/46-tools-e2e.png`。
- 模型配置页：`docs/shots/46-modelconfig.png`。
- `:core:engine:test :core:tools:test :core:prompt:test :core:parity:test` 通过。
- 构建：`:app:assembleDebug` BUILD SUCCESSFUL。

## 八、QQ 用户反馈记录（2026-11-07 23:5x）

群号：11316535（群名「神秘群」）；反馈用户 QQ：361465745（群昵称「无忆报」，管理员）。

本轮看到的反馈：

1. 本地部署模型（qwen3.x）时联网搜索/识图开关不可用，和本地部署无关，是开关本身没生效。
   —— 上一轮已做成真开关（`vision_<model>` / `web_search_<model>`），本轮未再改。
2. 点选部分项目反馈弱、缺少高亮。
3. 图标重复，建议收进任务菜单。
4. 无法添加插件，建议自带代理。
5. 项目页面参考 Operit 2。
6. 最大输出输入框逐字 clamp（见 §五）。
7. 模型名称删空自动回填（见 §五）。
8. 首次进入时加权限引导。
9. 推理强度不支持彻底关闭。

已在群里回复（23:55）：输入框 clamp / 模型名回填优先修；图标/右上角菜单重复已处理；
首次权限引导与 Operit 2 对齐在做；插件本地导入还在排期；修好群里同步。
也给「无忆报」私聊发了一份同样的说明。

说明：QQ 的 .hdoc 笔记和 .txt 附件没下载成功，反馈是看聊天记录整理的；下轮可继续核对原文件。

## 九、下一轮

1. 模型配置页真机回归 §五：清空模型名、输 6 位 max_tokens、失焦确认落盘（本轮被 QQ 通知打断）。
2. 虚拟屏 AI 工具补 `set_input_text` 真机用例（中文粘贴）；考虑 `dump_ui` 在虚拟屏不可用，工具描述写清用截图。
3. 首次启动权限引导再核对（反馈 8）；推理强度「彻底关闭」（反馈 9）需要模型参数层支持。
4. 图标/菜单位置（反馈 3）继续统一；插件页本地导入（反馈 4）。
5. 输入条：长按发送菜单、token 用量条、模型下拉直接切模型。
6. 工作区面板仿 Operit（路径缩写/打开目录/导入扫会话）。

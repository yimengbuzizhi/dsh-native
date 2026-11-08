# NOTICE · 第三方来源与许可

本项目以 LGPL-3.0 发布。下列外部代码/设计被参考或复制，按 LGPL-3.0 要求在此声明来源。

## Operit（com.ai.assistance.operit）

- 仓库：https://github.com/AAswordman/Operit
- 许可：LGPL-3.0
- 本地只读副本（开发时使用，不随本仓库分发）
- 复制/改写清单（2026-11-07，照 `docs/16-Operit源码精读-UI.md` 的精读结论）：

| 本项目文件 | Operit 来源 | 性质 |
|---|---|---|
| `ui/design/.../component/Settings.kt` | `ui/features/settings/sections/ModelApiSettingsSection.kt`（L1519-1788）的 `SettingsSectionHeader` / `SettingsTextField` / `SettingsSelectorRow` / `SettingsSwitchRow` / `SettingsInfoBanner`；`ui/features/settings/screens/SettingsScreen.kt`（L361-452）的 `SettingsSection` / `CompactSettingsItem` | **按源码几何/字号/结构改写为 Compose（复制式）** |
| `app/.../ui/settings/ModelConfigScreen.kt` | `ui/features/settings/screens/ModelConfigScreen.kt`（L385-845）的页面结构：选择模型配置 → API设置 → 思考/上下文 → 模型参数 → 高级设置；字段与中文文案取自 `values/strings.xml` | 结构/文案照搬 |
| `app/.../ui/settings/SettingsScreen.kt` | 同上 `SettingsScreen.kt` 的分组（账号/个性化/AI模型配置/数据/关于） | 结构/文案照搬 |
| `app/.../ui/settings/AssistantScreen.kt` | `ui/features/settings/components/AvatarPicker.kt`（圆形 70dp、2dp 主色描边、换图/重置）与 `ui/features/chat/components/CharacterSelectorPanel.kt`（角色行 12dp 圆角、36dp 头像、选中描边） | 结构照搬 |
| `ui/design/.../component/Avatar.kt` | 同上 AvatarPicker 的渲染约定（图片 URI + Crop）；图片加载换成自写 Bitmap 缓存（不引入 Coil） | 设计照搬，实现自写 |
| `app/.../ui/chat/ChatScreen.kt` 的 `ThinkingBlock` | `ui/features/chat/components/part/CustomXmlRenderer.kt`（L725-1000）+ `XmlCanvasBlockComponents.kt`（L51-206） | 交互照搬（箭头旋转/缩进竖线/限高 300dp/偏好键 `expand_thinking_process_default`），实现自写 |

### 2026-11-07 第二轮复制（M23 起）

| 本项目 | Operit 来源 | 性质 |
|---|---|---|
| `thirdparty/shower/`（模块 `:thirdparty:shower`） | `showerclient/` 整模块（含 `assets/shower-server.jar`、AIDL、Controller、SurfaceView） | **源码复制**（仅删 manifest 的 `package` 属性、重写 build.gradle） |
| `thirdparty/terminal/`（模块 `:thirdparty:terminal`） | `OperitTerminalCore`（https://github.com/AAswordman/OperitTerminalCore，LGPL-3.0）整模块（PTY JNI、proot rootfs、终端 UI、AIDL） | **源码复制**（删 manifest package；`CommandExecutionEvent` 去掉 kotlinx.parcelize 改手写 Parcelable） |
| `ui/chat/ChatScreen.kt` 的消息菜单/输入条 | `ui/features/chat/components/ChatArea.kt`（MessageItem 的 DropdownMenu：复制/朗读/引用/重新生成/创建分支/信息）与 `components/style/input/classic/ClassicChatInputSection.kt` | 交互/视觉照搬，实现按本工程状态模型重写 |
| `ui/toolbox/ToolboxScreen.kt` | `ui/features/toolbox/` | 信息架构照搬（能力入口 + 状态卡），实现自写 |
| `ui/device/AccessibilityScreen.kt`、`device/a11y/` | Operit 的无障碍 provider（`com.ai.assistance.operit.provider`）与 `AccessibilityUITools` | 能力对照，实现自写（本项目内嵌 AccessibilityService） |
| `device/floating/FloatingBallService.kt` | `services/floating/FloatingWindowManager.kt` / `FloatingChatService.kt` | 形态参考（悬浮球 + 快捷菜单），实现自写 |
| `ui/screen/VirtualScreenPage.kt` | `core/tools/agent/ShowerController` 使用侧 + showerclient README | 接入层自写 |
| `ui/chat/ChatScreen.kt` 的输入条附件（docs/42） | `ui/features/chat/components/AttachmentChip.kt`（52dp 缩略图 + 右上角 ✕）与 `AttachmentSelector.kt`（附件面板：相册/拍照/截图） | 交互照搬，实现自写（Photo Picker + FileProvider + `AttachmentStore`，未复制代码） |

## QuickJS（quickjs-2026-06-04）

- 仓库：https://github.com/bellard/quickjs （MIT）
- 本地位置：`core/js/src/main/cpp/quickjs/`（源码 vendored，含 LICENSE）
- 用途：workflow 的 JS 引擎（`core:js` + `core:workflow`）；JNI 桥 `core/js/src/main/cpp/js_bridge.cpp` 为自写。
- 参考了 Operit `quickjs/quickjs_jni.cpp` 的 JNI 形态（LGPL-3.0，未逐行复制）。


## RikkaHub（me.rerere.rikkahub）

- 仓库：https://github.com/rikkahub/rikkahub
- 许可：**AGPL-3.0，只参考设计，不复制任何代码**。
- 参考点：留白、字号层级、少颜色、单一强调色、聊天页无气泡排版。
- 2026-11-07 外壳参考（docs/41）：聊天落地、☰ 抽屉、抽屉里的会话列表/日期分组/长按菜单、底部圆形动作条、
  顶栏「标题+副标题」；只读 `ChatPage.kt` / `ChatDrawer.kt` / `ConversationList.kt` 的信息架构，**代码自写，未复制**。

## 上游 DSH（@deepseek-ai/dsh 0.2.2-rc.1）

- 提示词、工具 schema、会话 v4 格式、协议字段均来自 npm 包 `@deepseek-ai/dsh@0.2.2-rc.1` 及其依赖（`docs/port/` 有逐字记录）。
- 只使用其规范与资源（`core/prompt/src/main/resources/prompt/0.2.2-rc.1/`），不包含其源代码。

## 其它

- zstd-jni 1.5.6-8（Apache-2.0）
- OkHttp（Apache-2.0）、kotlinx.serialization（Apache-2.0）、Jetpack Compose（Apache-2.0）

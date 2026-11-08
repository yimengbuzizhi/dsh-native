# 15 · Operit UI 借鉴清单（用户要求：全面照搬，不自研）

> 2026-11-07｜用户明确：**我们是在做一个新软件，别人已经成熟，UI 能学就学、能做成别人的样子就做，千万不要自己发明界面。**
> Operit（LGPL）源码已 clone：`<workspace>\native\.thirdparty-src\Operit`（gitignored，只读参考，可复制代码）。
> RikkaHub（AGPL）只学设计，不抄代码。

## 一、头像：不是字母，是真正的自定义

Operit 有一套完整的 avatar 子系统，不是单个 Image：
- `app/src/main/java/com/ai/assistance/operit/core/avatar/common/model/AvatarModel.kt`
- `.../core/avatar/common/model/AvatarType.kt`（类型：图片/骨骼/帧序列等）
- `.../core/avatar/common/control/AvatarController.kt`、`AvatarSetting*`
- `.../core/avatar/common/factory/AvatarRender*`、`AvatarModel*`
- `.../core/avatar/common/state/AvatarEmotion*`、`AvatarMood*`、`AvatarState.kt`
- 角色选择/卡片 UI：`app/src/main/java/com/ai/assistance/operit/ui/features/chat/components/CharacterSelectorPanel.kt`（23KB）
- 角色列表/记忆：`ui/features/chat/components/CharacterSelectorPanel.kt`、`core/chat/...`

结论：我们要做的最小版 = 「名字 + 头像图片（相册/文件选择，本地存）+ 可选 emoji 兜底」；
完整版再跟 AvatarModel/AvatarType（图片 / 帧动画 / 骨骼）与情绪状态。
UI 上头像出现在：每条 AI 消息头、聊天页顶部助手条、工作台问候区、设置里的助手卡片。

## 二、模型与密钥：独立页面，不是设置里的两行

Operit 把「模型 / API Key / Provider」做成独立入口（底部或侧栏进入），页面里：
- Provider 列表（DeepSeek / OpenAI / Claude / Gemini …），每个 provider 一张卡：名称、baseURL、模型下拉、Key 状态、测试按钮
- 模型参数：temperature、maxTokens、reasoning effort 等
- Key 管理：多 key、掩码、余额/错误状态

我们的对应物（M1 里的 `SettingsScreen` 只是占位）应拆成：
- `:ui:settings` 里的 `ModelProvidersScreen`（独立页）
- `ProviderCard`（可复制 Operit 的字段与布局）
- Key 存储：先 SharedPreferences，M3 换 Keystore
- 从设置首页进入，不在首页列表里直接编辑

待办：去 Operit 源码里定位 `ui/features/settings`、`ui/features/model`、`data/collects/Model*`、`ApiKey*` 的实现，把字段/交互/文案抄成清单，再在 Compose 里按同一结构画。

## 三、其它要学的页面（按优先级）

1. 聊天页消息结构（已参考）：头像+名字、思考折叠（`web-chat/.../CustomXmlRenderer.tsx` 的 `EXPAND_THINKING_PROCESS_STORAGE_KEY`）、只读工具成组摘要、工具卡 `ToolDisplayComponents.tsx`。
2. 设置首页：分组卡片、每行图标+标题+副标题+右值。
3. 工作台/任务页：状态卡、能力卡、快捷入口（我们已有雏形）。
4. 终端页：深色、快捷键条（Operit terminal 子模块）。
5. 插件/工具包页：列表 + 权限 chips + 开关（Operit 的 ToolPkg）。

## 四、纪律

- 能复制就复制（LGPL 允许），复制后在 `NOTICE` 记来源与 commit。
- 不自己发明交互；遇到 Operit 有的，先照它的信息架构与文案，再换我们的 token。
- RikkaHub 只学：留白、字号层级、少颜色、单强调色；不抄代码。

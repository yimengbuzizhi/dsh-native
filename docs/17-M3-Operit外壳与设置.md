# 17 · M3：Operit 外壳 / 设置与模型页 / 头像（2026-11-07）

> 基线提交 `27d6ed0`。上一篇 `14-M2增量` 里「ChatScreen 刷新」的坑在本轮**已根治**。
> 源码级规格见 `docs/16-Operit源码精读-UI.md`；本篇只写本轮做了什么、证据、下一步。

---

## 一、一句话

**外壳和信息架构换成了 Operit 的样子**：抽屉导航、分组设置卡、独立「模型与参数配置」页、真实图片头像、
Operit 式思考折叠；聊天状态模型重写，流式逐字刷新不再靠 key hack。

---

## 二、做完的事（含证据）

| 项 | 结果 | 证据 |
|---|---|---|
| 聊天状态模型 | `ChatUiState`(data class + StateFlow) → `ChatSessionState`（Compose snapshot：`mutableStateOf` 字段 + `mutableStateListOf` 消息）。删掉 `App.kt` 里的 `key(messages.size, streaming, …)` 强制重建。真机流式逐字、思考块增长均正常 | `docs/shots/m3-chat-tools.png`、`m3-chat-table.png` |
| 外壳 | 底部四 Tab → Operit 式抽屉：`DSH` + 状态胶囊（模型名/就绪）+ 三宫格统计（会话/工具/插件）+「功能」分组（工作台/会话/插件/设置）+ 底部（关于/设置）；落地页仍是工作台 | `m3-drawer.png`、`m3-workbench.png` |
| 工作台 | 顶栏拆两行（☰ + 通知/头像；问候 + 大字），去掉卡片重叠；统计接真实值（会话/插件/工具数/v4） | `m3-workbench.png` |
| 设置首页 | 照 Operit `SettingsScreen`：账号 / 个性化 / AI模型配置 / 数据和权限 / 关于，分组标题带图标，卡片内是「图标+标题+副标题+箭头」行 | `m3-settings.png` |
| 模型与参数配置（独立页） | 选择模型配置 + 测试连接；API设置（提供商/端点/密钥或账号令牌/模型名 + 能力开关）；模型参数（maxTokens/temperature/思考强度）；高级设置。提供商 4 个：DeepSeek 账号 / DeepSeek API / Anthropic / 自定义 | `m3-model-config.png`、`m3-model-params.png` |
| 测试连接 | 真发一条 `max_tokens=32` 的最小请求，成功/失败原文回显（不是假按钮） | 代码 `AppViewModel.testConnection()` |
| 助手配置（头像） | 名字/简介 + 真实图片头像：相册选择→复制到 `filesDir/avatars/assistant_*.img`→`SettingsStore` 存路径；无图时首字兜底；`DshAvatar` 以 Bitmap LRU 缓存渲染（未引入 Coil） | `m3-assistant-avatar.png` |
| 显示与行为 | 默认展开思考过程（Operit 偏好键同名语义）、工具审批方式、主题（跟随系统） | `m3-behavior.png` |
| 聊天页 | 连续 assistant 消息合并成一个「回合」（只画一次头像/名字，Operit 的 step 分组）；思考块按 Operit 复刻（20dp 箭头旋转 90°、主色@70% 标题、正文 24dp 缩进 + 渐隐竖线、限高 300dp、流式呼吸标题）；结束后展开与否跟偏好 | `m3-chat-tools.png` |
| Markdown | 新增表格渲染（Operit 有表格）；代码块横向滚动、列表、引用、行内样式保留 | `m3-chat-table.png` |
| 会话列表 | 预览改为「最后一条有正文的消息的 text 块」，剔掉 reasoning 与 markdown 记号 | `m3-sessions.png` |
| 契约 #1（部分） | `ContentBlock` 增加 `signature`；流式 `signature_delta` 落库到 v4 的 reasoning 块；多轮请求回放 `thinking{thinking, signature}` | `core/model`、`core/session`、`AppViewModel.buildRequest` |
| 工具名契约 | 上游桌面 15 号工具是 `pwsh`；Android 按 `docs/port/02 §3.3` 换成 `bash`（只改 name/description/两处参数描述），**修掉了 bash 工具没被发出去的问题** | `PromptPack.androidApiTools()` |

---

## 三、拍板记录

1. **导航用抽屉不用底部 Tab**：用户 2026-11-07 明确「信息架构照搬 Operit，不要自研」。Operit 的主壳就是抽屉。
   落地页保留工作台（沿用 2026-11-06 第 9 条）。
2. **设置页按 Operit 分组**，而不是 Rikka 卡片：Operit 的分组标题 + 卡片行信息密度更适合功能多的设置页；
   聊天页仍保持 Rikka 的留白（09 文档的「按页面分工」结论不变）。
3. **头像不引入 Coil**：只做本地文件/URI 的 Bitmap 解码 + LRU，够用且不加依赖；等做角色卡多图时再评估。
4. **角色卡延期到 M4**：人设/开场白要进 system 提示词，会动契约 #1 的段拼装，等 §3 动态拼装做完再开。
5. **`expand_thinking_process_default` 语义微调**：Operit 是「结束后总是折叠」；DSH 的「默认展开」开启时结束后保持展开
   （否则用户看不到刚生成的思考，与用户「聊天页要能显示思考过程」的要求冲突）。关闭时保持 Operit 行为。

---

## 四、本轮发现并已修的坑

1. **bash 工具根本没发出去**：`tools.json` 是桌面快照（`pwsh`），按 `registry.names` 过滤后 `bash` 被丢弃。
   模型在思考里说「这个会话没有 bash 工具」。修法见上表最后一行。
2. **思考块 shimmer 用 Canvas + SrcAtop 画成了灰块**：改成标题呼吸亮度（Compose 1.7 的 `TextStyle.copy(brush=…)` 不可用）。
3. **工作台输入框「发送」按钮只 `newChat()` 不发送**：改成 `onSend(text)`（先 newChat 再 send）。
4. **工作台统计是假的**（工具 0 / deepseek-chat 写死）：全部接真实设置与工具数。
5. **`cd` + .NET `[IO.File]::ReadAllText(相对路径)` 会解析到进程 CWD**：PowerShell 里改文件一律用绝对路径。

---

## 五、下一步（按优先级）

1. **system 提示词动态拼装**（docs/port/02 §3）：当前是桌面 19 段快照 + 32 个工具描述，但我们只发 8 个工具；
   需要按段 order/name 排序、空段丢弃、`join("\n\n")`，并把 `tool:pwsh` 段换成 `tool:bash`。
   这是契约 #1 最后一块硬骨头。
2. **工具补到 32**：优先 `ask_user_question`（提问 UI）、`web_fetch`/`web_search`、`present`（产物卡）、
   `working_directory`；schema 从 `tools.json` 原样取（name 不存在的按 Android 规则改）。
3. **门 2 对拍台**：录制/回放/差异报告；先用 account 通道录一次真实请求当 fixture。
4. **会话列表性能**：只读首帧 header + 尾部摘要，别全量解压（现在 7 条还行，41 条会慢）。
5. **旧数据迁移**：只读导入 `/sdcard/DeepSeekHarness` 备份包。
6. **门 4**：Operit quickjs/terminal/shower 子模块编入验证。
7. UI 继续抠：抽屉三宫格的图标+角标（Operit 有图标，我们现在只有数字）、会话列表头像用助手图、
   消息时间戳/长按复制、输入框附件与全屏按钮。

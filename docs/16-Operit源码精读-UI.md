# 16 · Operit 源码精读：UI 规格与照搬清单

> 2026-11-07｜用户要求：「只看截图太浅，看源码更仔细」。
> 本文是**逐文件读 Operit 源码**后抽出的规格：组件几何、字号、颜色、文案、交互、状态。
> 所有路径相对 `D:\dsh\native\.thirdparty-src\Operit\app\src\main\java\com\ai\assistance\operit\`。
> 许可：Operit 为 LGPL-3.0，**可直接复制代码**，复制处在 `NOTICE` 记来源。

---

## 一、导航壳：抽屉，不是底部 Tab

源码：`ui/main/`（`MainScreen`/`LocalTopBarActions`），截图对照：抽屉标题 `Operit AI`。

结构（自上而下）：

| 区块 | 内容 |
|---|---|
| 品牌 | 28sp 粗体标题 `Operit AI`；下面一个状态胶囊（绿点 + `WiFi`） |
| 三宫格统计 | 图标 + 角标数字 + 下方小字：`包管理 25` / `权限 正常` / `工作流 0` |
| 分组标题 | `AI 功能`（小字、灰、左对齐） |
| 列表 | `AI 对话`（当前项：整行蓝色填充 + 圆角 12 + 白字）/ `助手配置` / `记忆库` / `工具箱` |
| 底部 | `关于` / `使用手册` / `设置` 三个图标+小字，均匀分布 |

对话页顶栏：`☰` + 标题（`助手- xxx`）+ 右侧两个图标按钮（终端、分屏）。
顶栏下方一条功能条：历史（时钟）、工作区（方块）、**头像圆形 + 助手名**（点击开角色选择）、右侧 `0` 角标。

> DSH 映射：`工作台` 作为落地页放第一项；`AI 对话`→会话/聊天；`助手配置`→助手（名字/头像/角色卡）；`工具箱`→插件；底部 `关于`/`设置`。
> 决策理由：用户 2026-11-07 明确「UI 能照搬就照搬，信息架构学它」，故把底部四 Tab 换成 Operit 抽屉；
> 落地页仍为工作台（沿用 2026-11-06 已拍板的第 9 条）。

---

## 二、设置页：分组标题 + 卡片 + 行

源码：`ui/features/settings/screens/SettingsScreen.kt`

```kotlin
// L361-402 分组
SettingsSection(title, icon, containerColor) {
    // 标题行：Icon 18dp(tint=primary) + 6dp + Text(titleSmall, Bold, primary)
    // 卡片：Card(fillMaxWidth, 默认圆角) -> Column(padding 12dp)
}
// L404-452 行
CompactSettingsItem(title, subtitle, icon, onClick) {
    // Row(clip 6dp, clickable, padding 8dp)
    //   Icon 20dp(tint=primary) + 12dp
    //   Column(weight 1f): Text(bodyMedium, Medium, maxLines 1) + Text(bodySmall, onSurfaceVariant, maxLines 1)
    //   Icon(ChevronRight 16dp, tint=onSurfaceVariant)
}
```

页面顺序与文案（照抄，替换未实现项）：

1. `账号`（icon AccountCircle）：GitHub 账户 / 登录 GitHub（我们：DeepSeek 账号 → 令牌状态）
2. `个性化`（Person）：用户资料设置 / 语言设置 / 主题和外观 / 显示与行为 / 布局调整
3. `AI模型配置`（Settings）：**模型与参数配置**（"模型参数和API配置"）/ 功能模型配置 / 语音服务配置
4. `提示词配置`（Message）：提示词设置 / 人设卡生成 / Waifu模式设置
5. `上下文与总结`（Analytics）
6. `数据和权限`（Security）：工具权限 / 数据备份 / 对话历史管理 / Token 用量统计 / 性能监视
7. `隐私与数据清理`（DeleteSweep）
8. `外部调用`（SettingsEthernet）

---

## 三、模型与参数配置页（重点）

源码：`ui/features/settings/screens/ModelConfigScreen.kt`（LazyColumn，spacedBy 4dp，padding 16/8）

1. **选择模型配置卡**（圆角 12dp、surface、0.7dp outlineVariant@50%、elevation 1）
   - 标题 `选择模型配置`（titleMedium Medium）+ 右侧 `+ 新建`（OutlinedButton，高 28dp，圆角 16，primary 0.8dp 描边，14dp Add 图标 + "新建" 12sp）
   - 下拉字段：Surface(圆角 8dp, surfaceVariant@50%)，Row padding(16,12)：名称 bodyLarge Medium + 展开箭头
   - 操作行 FlowRow：`重命名` / `删除`（error 色）/ `测试连接`（Dns 图标；测试中换成 16dp 进度圈 + `取消`）
   - 测试结果卡：每项 = 图标(CheckCircle/Info/Warning) + 名称 + 状态文本（可展开）
2. `ModelApiSettingsSection` —— API设置
3. `ContextSummarySettingsSection` —— 上下文设置
4. `ThinkingConfigurationsSection` —— 思考配置（"按规则顺序匹配思考参数，首条命中生效"）
5. `ModelParametersSection` —— 模型参数设置
6. `CustomHeadersSettingsSection` —— 自定义请求头（"API请求头字段配置"）
7. `AdvancedSettingsSection` —— 高级设置（请求队列控制 / 使用API密钥池）

### 3.1 API设置里用的四个原语（`sections/ModelApiSettingsSection.kt` L1519-1788）

这些就是我们要复刻的「Operit 设置卡语言」：

| 原语 | 几何 | 字号/颜色 |
|---|---|---|
| `SettingsSectionHeader(icon,title)` | Icon 24dp + 8dp + 标题 | titleMedium / Bold / primary 图标 |
| `SettingsTextField(title, subtitle, value, …)` | Surface 圆角 10dp，surfaceVariant@35%，padding 12dp；内层输入框 Surface 圆角 6dp，surfaceVariant@65%，padding(12,10) | 标题 bodyMedium Medium；副标题 bodySmall onSurfaceVariant；输入文字 14sp SemiBold **primary 色** |
| `SettingsSelectorRow(title, subtitle, value, onClick)` | Surface 圆角 10dp，surfaceVariant@45%，padding 16dp；右侧 value + KeyboardArrowDown(primary) | value bodyMedium primary，maxLines 1 |
| `SettingsSwitchRow(title, subtitle, checked)` | Surface 圆角 10dp，surfaceVariant@35%，padding 12dp；右侧 Material3 Switch | 标题 bodyMedium Medium；副标题 bodySmall |
| `SettingsInfoBanner(text)` | Surface 圆角 8dp，tertiaryContainer；Info 图标 + 12dp 文案 | bodySmall onTertiaryContainer |

外层统一 `Card(圆角 12dp, surface, elevation 1dp)` + `Column(padding 16dp, spacedBy 12dp)`。

### 3.2 字段与文案（settings/values/strings.xml 原文）

- `API设置` / `API提供商`（副标题 `点击选择API提供商`）
- `API端点`（副标题 `例如: https://api.openai.com/v1/chat/completions`），下方补充 `实际请求地址：…` 与端点补全提示
- `API密钥`（副标题：未设置时 `未设置密钥，可直接输入或使用密钥池`，已设置时掩码显示）
- `模型名称`（副标题 `例如: gpt-4, claude-3-opus-20240229... (可用逗号分隔多个模型)`，尾随 `FormatListBulleted` 拉取模型列表）
- 开关：`模型支持识图` / `模型支持音频解析` / `模型支持视频解析` / `启用 DeepSeek 网络搜索` / `模型支持ToolCall`（副标题解释「关闭时用软件内工具调用机制…」）

### 3.3 模型参数（`sections/ModelParametersSection.kt`）

- 卡片标题 `模型参数设置` + Tune 图标 + 展开/收起箭头（整行可点）
- 展开后：说明文字 + `ScrollableTabRow` 四个 Tab：`生成参数` / `创意参数` / `重复参数` / `自定义参数`
- 每个参数 = 一行：名称 + 描述 + 滑杆 + 数值 + 开关；参数含 maxTokens、temperature、topP、topK、presencePenalty、frequencyPenalty、repetitionPenalty
- 底部 `重置参数` / `保存参数`；保存后 Snackbar `参数已保存`

---

## 四、头像与角色卡

### 4.1 数据模型（`data/model/CharacterCard.kt`）

```
id, name, description, characterSetting(角色设定/引导词), openingStatement(开场白),
otherContentChat, otherContentVoice, attachedTagIds, advancedCustomPrompt, marks,
chatModelBindingMode/chatModelConfigId/chatModelIndex, memoryProfileBindingMode/…,
toolAccessConfig, isDefault, createdAt, updatedAt
```

还支持酒馆（SillyTavern）角色卡 JSON：`spec/spec_version/data{name,description,personality,first_mes,avatar,…}`。

### 4.2 头像选择（`ui/features/settings/components/AvatarPicker.kt`）

- 圆形 70dp，2dp primary 描边，surfaceVariant 底，点按选图；无图时 Person 图标 35dp
- 下方 `重置`（OutlinedButton 高 32dp + Refresh 16dp）
- 图片以 **URI 字符串**存 DataStore，Coil `rememberAsyncImagePainter(Uri.parse(uri))` 渲染，`ContentScale.Crop`

### 4.3 角色选择面板（`ui/features/chat/components/CharacterSelectorPanel.kt`）

- 遮罩 `Black@40%`，点空白关闭；面板从**顶部滑入**（slideInVertically，300ms EaseOutCubic），
  padding(top 60dp, start/end 20dp)，Surface 圆角 16dp，shadow 16dp（spot=primary@10%），padding 16dp
- 头部：`选择角色`（titleMedium SemiBold）+ 右侧 `N 个角色`（bodySmall）+ 排序图标 32dp + 编辑图标 32dp
- 列表 `heightIn(max=320dp)`，`spacedBy(6dp)`；先 `群组` 后 `角色卡` 两个小标题（labelSmall，onSurfaceVariant）
- 角色行：Surface 圆角 12dp，选中 = primary@8% 底 + 1dp primary@30% 描边；padding 12dp；
  头像 36dp 圆形（secondaryContainer 底 + Person 18dp 兜底）；名字 bodyLarge SemiBold（选中 primary），
  描述 bodySmall，最多一行；右侧选中勾 Check 16dp primary

---

## 五、思考过程块（聊天页）

源码：`ui/features/chat/components/part/CustomXmlRenderer.kt` L725-1000
+ `ui/features/chat/components/part/XmlCanvasBlockComponents.kt` L51-206

行为：

1. 偏好键 **`expand_thinking_process_default`**（DataStore boolean，默认 false）：
   **思考进行中**时展开态跟偏好；**思考结束后总是折叠**。
2. 标题行 `CanvasExpandableHeaderRow`：
   - 箭头 `KeyboardArrowRight` 20dp，primary@70%，展开时旋转 0°→90°（300ms；自动收起那次用 snap 跳过动画）
   - 标题 `思考过程`（labelMedium，颜色 = 正文色@70%）
   - **流式中**标题上有扫光 shimmer：1400ms 线性循环，渐变 high=primary@95% → titleColor@20%，140px 宽
   - 整行可点，ripple，语义 role=Button、stateDescription=展开/收起
3. 正文：`AnimatedVisibility` 展开（220ms expandVertically+fadeIn / shrinkVertically+fadeOut），
   左侧 24dp 缩进 + `CanvasIndentedGuide` 竖线（1dp，primary@20%，上下渐隐，圆角 999）；
   容器 `heightIn(max = 300.dp)` + 内部滚动；点正文可切「全高/限高」；
   `animateContentSize(240ms)`，正文自动贴底。

> DSH 已有等价物（折叠卡），改为这套几何与行为即可。

---

## 六、DSH 落地映射

| Operit | DSH 落地 |
|---|---|
| 抽屉 `AI 对话/助手配置/工具箱/设置` | `工作台` / `会话(聊天)` / `助手` / `插件` / `设置` |
| 模型与参数配置 | `ModelConfigScreen`：provider=DeepSeek账号/DeepSeek API/Anthropic/自定义；端点默认 `https://api.deepseek.com/anthropic`；密钥掩码；模型名 + 拉列表（暂不做）；温度/maxTokens/topP/思考开关 |
| 头像 URI 存 DataStore | 头像图片复制到 `filesDir/avatars/`，SettingsStore 存路径；无图时显示首字母兜底 |
| 角色卡 | 先做单助手（name/avatar/description/characterSetting），表结构按 CharacterCard 预留多卡 |
| 思考过程块 | 复刻标题行 + 竖线 + 300dp 限高 + shimmer；偏好开关放到 `显示与行为` |
| 设置卡语言 | `:ui:design` 新增 `SettingsSectionHeader/SettingsTextField/SettingsSelectorRow/SettingsSwitchRow/SettingsInfoBanner/CollapsibleCard` |

---

## 七、纪律

1. 组件几何与字号**照抄**（dp/sp 不改），只换颜色 token（DSH 主色 `#2F6FE0`，深色底）。
2. 文案直接复用 Operit 的中文串；DSH 专有处替换（如「GitHub 账户」→「DeepSeek 账号」）。
3. 复制代码的文件在 `NOTICE` 追加：来源仓库、commit、文件路径。

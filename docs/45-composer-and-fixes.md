# 45 · 输入条 v2（照 Operit Agent 输入区）+ 三处交互修复

> 2026-11-07 23:2x｜基线 `675d97b`｜真机 PLT120 / Android 15 全程验证。
>
> 用户原话（本轮开工指令）：
> 「无法使用联网搜索和识图选项。在 DS 输出期间，用户会不受控制地往上弹，直到 DS 输出完成才能看到完整内容。
> 询问用户选项点完以后，看不出来刚刚选的哪个。输入栏不是 operit 去读源码重做，右上角三个点可以删除了重复了。」
>
> 五件事一次做完：输入条重排（读 Operit 源码）、流式滚动、ask_user 选中态（真根因是重组）、
> 识图/联网搜索真开关、删掉重复的 ⋮ 菜单。截图：`docs/shots/45-*.png`。

---

## 一、输入条 v2（`ChatComposer.kt` 整文件重写）

读的是 Operit 的**新 Agent 输入区**，不是旧版 Kotlin `ChatInputBottomBar`：

- `web-chat/src/ui/features/chat/components/style/input/agent/AgentChatInputSection.tsx`
- `web-chat/src/ui/features/chat/util/styles/chat-composer.css` 的
  `.agent-input-card` / `.agent-input-field` / `.agent-input-bottom` / `.agent-model-pill` /
  `.agent-icon-button` / `.agent-action-orb` / `.agent-action-progress`

| Operit | 本轮实现 |
|---|---|
| 卡片第一行是 textarea（min 44 / max 144px），右端「全屏编辑」30px 圆钮 | 多行输入（最多 8 行）+ 30dp 全屏钮（`OpenInFull`） |
| 第二行：模型 pill 在左（带下箭头）→ 弹性空白 → 齿轮 / ＋ / 发送圆钮 | 同序。pill = 12dp 圆角 + 1dp 描边 + 最多 190dp，点开模型面板 |
| 发送圆钮 36px，外面套 40px 进度环（生成中转） | 36dp 渐变圆 + 44dp Canvas 进度环（`rememberInfiniteTransition` 转 96° 弧） |
| 全屏编辑对话框 | `FullscreenEditor`：Dialog + 大输入区 + 「完成」，吃 `imePadding` |

三个面板都从输入条直接开（`ModalBottomSheet`，22dp 顶圆角）：

| 面板 | 入口 | 内容 |
|---|---|---|
| 模型 | 模型 pill | 当前模型（品牌底）· 模型支持识图开关 · 联网搜索开关 · 打开模型配置（跳二级页） |
| 输入设置 | 齿轮 | 计划模式开关 · 目标 · 提醒 · 导出 Markdown |
| 添加 | ＋ | 相册图片 / 拍照 / 截图当前屏幕 |

删掉的旧元素：

- 底部「模型芯片 + 计划芯片 + Enter 发送」整行（信息全部进面板）；
- 旧「＋」面板里的计划模式/目标/提醒（与顶栏 ⋮ 菜单同一份功能，见 §六）。

## 二、流式输出不再拽用户滚动

**旧实现的 bug**（`ChatScreen.kt`）：

```kotlin
val nearBottom = !listState.canScrollForward || lastVisible >= rows.lastIndex - 1
```

最后一条 assistant 回复很长时，用户往上翻、但最后一条仍在屏幕里 → 仍判「贴底」→
每来一个 token 就 `animateScrollToItem(lastIndex)` 把人拽回底部。表现就是
「输出期间不受控制地往上弹，等输出完才看得到」。

**现在**：

1. `NestedScrollConnection` 只认手势：手指下拖（`available.y > 0` 且 `UserInput`）立刻 `followOutput = false`；
   滑回底部（`!canScrollForward`）恢复 `true`。
2. 跟随效果改成 `withFrameNanos {}` 后 `scrollBy(200_000f)`——等一帧让新内容参与测量，
   `scrollBy` 到头会被 clamp，不会越界，也不会像 `animateScrollToItem` 那样反复起动画。
3. 「回到底部」按钮同时把 `followOutput` 置回 true。

证据 `45-scroll-hold.png`：生成中滚到「其四/其五」，2 秒后**同一位置**，发送键仍是「停止」。
生成结束后 `45-...`（s29）自动贴底，显示「十、结语」。

## 三、ask_user 选项「点完看不出来选了哪个」——根因是快照不观察

`AsUserDialog` 里原本是：

```kotlin
val selected = remember(questions) { mutableStateMapOf<String, MutableSet<String>>() }
set.add(option.label)   // ← 不会触发重组
```

`mutableStateMapOf` 只观察 **map 的增删**；对 `MutableSet` 内部 `add/remove` 没有任何快照记录，
所以状态其实改了、界面一直画旧值。这正是用户看到的「点完不知道选了哪个」（提交时答案是对的）。

修法：`mutableStateMapOf<String, List<String>>()`，每次点击整体替换（Compose 能观察赋值）。

UI 同时加粗选中态（Operit 的 radio/checkbox 语汇）：

- 左侧单选圆 / 多选方：选中 = 品牌实心 + 白勾；
- 整行：品牌底 + 1.5dp 品牌边 + 标题品牌色加粗 + 右侧「已选」；
- 确认键文案「提交（已选 n）」；
- 题目右侧「可多选」提示。

会话里也回显：`ask_user_question` 的工具卡把结果文本
（`- [id] 问题` + `→ 答案`）解析成「问题灰字 + ✓ 答案」两行，默认展开，不用点开原始 JSON。

证据：`45-ask-selected.png`（选中态）、`45-ask-result.png`（工具卡里的「✓ A方案甲」）。

## 四、联网搜索 / 识图：从占位开关变成真开关

`ModelConfigScreen` 里这两个开关以前是 `enabled = false, onCheckedChange = {}`——用户点不动，
所以「无法使用」。现在：

| 开关 | 存储 | 生效点 |
|---|---|---|
| 模型支持识图 | `vision_<model>`（按模型名记忆；deepseek-flash 默认开） | `currentImageRoute()` / `imageInputAvailable`：关掉后 `read_image` 拒绝、聊天带图发送被拒（红条说明）；打开 = 用户自己声明模型能看图 |
| 启用 DeepSeek 网络搜索 | `web_search_<model>`（默认开） | `offeredToolNames()`：关掉后请求的 `tools` 里没有 `web_search`，system 的工具清单也不列 |

两个入口：**设置 → 模型与参数配置**，以及**输入条 → 模型面板**（顺手把「打开模型配置」也接上了）。

**顺手修的架构问题**：`EngineDeps.apiTools` 之前是 `List`（构造时算一次），
引擎是 `by lazy` 单例 → 开关改了要等进程重启才生效；注释却写着「每次请求现算」。
现在改成 `() -> List<ApiTool>`，`TurnEngine` 每请求调用一次。

证据（真机）：

```
关：adb shell run-as xin.dshaa.nativeapp grep -c web_search files/req1.json  → 0
开：adb shell run-as xin.dshaa.nativeapp grep -c web_search files/req2.json  → 1
prefs：<boolean name="web_search_deepseek-flash" value="false|true" />
```

## 五、删掉重复的右上角「⋮」

聊天顶栏原来是：菜单 / 标题 / 新对话 / 搜索 / ⋮。
⋮ 里的「目标、提醒、计划模式」与输入条面板完全重复（用户原话「重复了」），整块删除；
「导出 Markdown」挪进输入条齿轮面板，仍走原来的 `SessionMarkdown` + `writeSessionMarkdown`。

顶栏现在：菜单 / 标题 / 新对话 / 搜索。证据 `45-export.png`（「已导出 Markdown」弹窗从齿轮进来）。

## 六、顺手修的三处

1. **`state.attachError` 从来没显示过**：附件失败、模型不识图都只写状态，界面无感知。
   现在输入条卡片上方一条红条（截图 s12 里能看到「截图失败：请确认无障碍服务已开启」）。
2. **全屏编辑对话框没吃 IME inset**：键盘压住编辑区 → 加 `imePadding()`。
3. 上面 §四 的 `apiTools` 惰性化。

## 七、验证（真机 PLT120 / Android 15，无线调试 42847）

| 项 | 结果 |
|---|---|
| `:app:assembleDebug` | BUILD SUCCESSFUL（APK ≈ 98.1MB） |
| `:core:engine:test` `:core:tools:test` | BUILD SUCCESSFUL |
| 输入条 | 新布局正常；键盘弹起时卡片贴在键盘上（s36） |
| 模型面板 | 当前模型 + 两个开关 + 打开模型配置；跳转成功（s13/s14） |
| 设置页开关 | 两枚开关都在、默认开、切换后 prefs 落盘（s7） |
| 开关 → 请求 | 关 = 请求体无 `web_search`；开 = 有（req1/req2） |
| ask_user | 选中态清晰、提交后工具卡回显「✓ A方案甲」（s18/s20） |
| 流式滚动 | 生成中上翻后 2 秒位置不变（s26/s27），结束后自动贴底（s29） |
| 导出 | 齿轮 → 导出 Markdown → 「已导出」弹窗（s31） |
| 全屏编辑 | 打开后键盘不遮编辑区（s35） |

## 八、下一步

1. 虚拟屏按键语义（docs/44 §三 旧账）：`controller.key(3/4/187)` 影响主屏，要按 displayId 注入或 `am start --display`。
2. 输入条继续贴 Operit：长按发送键出菜单、token/上下文用量条（`.agent-popup-context-meter`）、
   模型下拉里直接切模型（现在只有「打开模型配置」）。
3. 工作区面板仿 Operit：`shortWorkspacePath()`、打开目录、导入已有目录扫会话。
4. 无障碍开启后的回归：读屏 dump / 截图 / tap / swipe / input_text。
5. 助手配置 AssistantScreen 按角色卡重做。
6. 其余页面一致化（卡片/标题/间距/抽屉入口）。

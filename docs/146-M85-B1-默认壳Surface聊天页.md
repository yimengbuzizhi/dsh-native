# 146 · M85-B1：默认壳 `builtin.shell.whale` 的 Surface 聊天页（第一片）

> 2026-11-13 22:4x。设计基线 `docs/144` §8 M85-B；上一片 `docs/145`（M85-A）。
> 真机：OPPO A55 5G（PEMM00 / 720×1600 / Android 13），无线调试 `192.0.2.10:45111`。
> 默认仍是 legacy 原生壳（`dsh_shell=native`）；`builtin.shell.whale` 可切换、可停用、可同 id 覆盖。

---

## 1. 这一片做了什么

把**默认鲸歌聊天页**做成一个真正的插件壳：`builtin.shell.whale`，用 Surface 原语拼出
消息/Markdown/思考/工具卡/审批/上下文/停止，宿主没有为它加任何「聊天组件」。

- 壳随 APK 打包：`app/src/main/assets/builtin-plugins/builtin.shell.whale/`（package.json + index.js），
  首次启动走和用户插件**同一套**安装/试运行/开关；版本变了自动用 APK 内文件覆盖（配置保留）。
- 宿主侧只加通用能力：
  - `SurfaceRow` 增加 `thinking` / `toolCalls` / `error`；
  - `list` 支持 item 模板里再嵌 `list bind:"item.toolCalls"`（工具卡列表）；
  - 绑定新增 `chat.context.*`、`chat.pendingApproval.*`、`item.thinking`、`item.toolCount`、
    `item.name/arguments/result`、`item.hasThinking/hasToolCalls/hasError/running/isError`；
  - `surface.command` 新增 `approval`（allow / always），落在 `AppViewModel.resolveApproval`；
  - state 增加 `chat.context`、`chat.pendingApproval`、`chat.pendingQuestions`、消息 `thinking/toolCalls`。
- 修复一个真实崩溃：工具卡列表在父 `LazyColumn` 的 item 里再嵌 `LazyColumn`，会被测到无限高约束；
  嵌套列表改用普通 `Column`（行数很小），顶层消息列表仍用 `LazyColumn`。

---

## 2. 真机验收

| # | 场景 | 结果 | 证据 |
|---|---|---|---|
| 1 | 内置壳安装 | `install ok=true`，版本 1.0.0 首次装、1.0.1 自动升级；`dsh_shell_list` 里两个壳 | `docs/m85b-shells-1.json`、logcat `DshBundled` |
| 2 | 切到默认壳 | 主界面是 Surface 聊天页（标题/会话条/消息/上下文/输入框/发送/停止） | `docs/shots/m85b-01-whale-home.png`、`m85b-04-whale-relaunch.png` |
| 3 | 工具回合 | 思考块 + Markdown 正文 + bash 工具卡（参数/结果）+ 上下文 1% | `m85b-06-approval.png`、`m85b-08-tool-card.png`、`m85b-09-tool-card.png` |
| 4 | 审批 | `DshApproval: ask tool=bash` → 壳里出现「需要确认 / 允许 / 总是允许 / 拒绝」→ 点允许 → `resolve allow=true`，回合继续并结束 | `m85b-14-approval-card.png`、`m85b-15-approval-allowed.png`、`docs/m85b-logcat.txt` |
| 5 | 流式 + 停止 | 长故事请求：2.5s 截到思考/流式状态，点停止 → `stop requested` → turn end 2.9s，按钮回「发送」 | `m85b-12-whale-stream.png`、`m85b-13-whale-stop.png`、`docs/m85b-logcat.txt` |
| 6 | 同会话对拍 | 同一条「读文件」会话：Surface 壳显示思考/工具卡/最终正文；切 `native` 后 legacy 显示同一会话的最终正文/工具折叠/用量；再切回壳不丢状态 | `m85b-10-legacy-same-session.png`、`m85b-11-legacy-tool-card.png` |
| 7 | 回原生 | `dsh_shell=native`、`treeMounted=false`，legacy 可继续用 | `docs/m85b-shells-2.json` |

关键日志：

```text
DshBundled: 内置插件 builtin.shell.whale install ok=true ... 1.0.1
DshShell: dispatch=shell.mount ... COMPLETED
DshApproval: ask session=... tool=bash foreground=true
DshShell: tap action=approvalAllow → DshApproval: resolve allow=true always=false
DshShell: tap action=stop → DshTurn: stop requested → turn end after 2933ms
```

单测：`:core:plugins:test` **117/117**（新增嵌套 list item、approval 命令白名单两条）；
`:core:engine:test` / `:core:llm:test` / `:core:session:test` 全绿。

---

## 3. 明确没做（B2 / B 后续）

- **附件**：图片/文件/截图/引用 chip 还没有入口；`pickFile`/`attachImage` 等命令未实现。
- **思考折叠**：当前思考固定显示 3 行，没有「展开/收起」；工具卡也没有逐条折叠、耗时/状态头。
- **问题卡**：`ask_user_question` 的答题 UI 未做（state 已带 `pendingQuestions`）。
- **计划模式 / goal / 日程 / 用量**：用量只在 state/旧壳，Surface 壳未显示；plan/goal 交互未迁。
- **抽屉/设置/模型切换/搜索**：仍依赖 legacy 或后续 M85-C；当前壳只做聊天主区 + 会话横条。
- **长列表压测**：1000 条消息还没跑；`list` 顶层已虚拟化，但嵌套工具卡/思考的布局未压测。
- **逐项对拍**：B1 只对了「同一会话的最终状态 + 一次工具卡 + 一次审批 + 一次停止」；
  扫描/搜索/审批「总是允许」「拒绝」按钮未逐一点击，legacy 的快捷键/抽屉行为未对。
- **默认切换**：本片不把默认改成插件壳；M85-C 删 legacy 前必须先把 B2 补齐并把默认壳设为鲸歌。
- 内置壳被用户删除后，下次启动会被重新安装（M85-C 前要定「删除/恢复内置」语义）。

---

## 4. 文件

- 内置壳：`app/src/main/assets/builtin-plugins/builtin.shell.whale/{package.json,index.js}`
- 安装器：`app/data/BundledPlugins.kt`（zip 打包 → `PluginStore.installFile`，版本升级）
- 绑定/数据：`app/ui/surface/PluginShellScreen.kt`（`SurfaceRow`、context、approval、toolCalls）
- 渲染：`app/ui/surface/SurfaceRenderer.kt`（嵌套 list 用 Column；自动滚动）
- 宿主命令：`app/chat/AppViewModel.kt`（`approval`、state 扩展）
- 协议：`core/plugins/SurfaceSpec.kt`（`approval` 命令）

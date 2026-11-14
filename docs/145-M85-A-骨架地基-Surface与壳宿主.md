# 145 · M85-A 骨架地基：Surface 协议 v1 + 壳宿主 + 最小壳

> 2026-11-13 22:3x。设计基线 `docs/144`；本批只做「插件能不能当主界面」，**不迁移、不删除原生 UI**。
> 真机：OPPO A55 5G（PEMM00 / 720×1600 / Android 13），无线调试 `192.0.2.10:45111`。
> 验收插件：`docs/m85a-minimal-shell`（第三方格式，ID `m85a.chat`）。

---

## 1. 结论

**「主界面由插件提供」已经在真机上跑通**：一个只用 Surface 18 原语拼出来的第三方壳
（`m85a.chat`）可以整体替换宿主主界面，完成发消息（真模型）、流式文本进插件消息列表、
停止、切会话、新会话、回原生壳；切回原生后无残留。

同时明确边界：本批**没有**把默认鲸歌 UI 迁成 Surface，也**没有**删 legacy；那些是 M85-B/C。
M85-A 只证明协议和壳宿主成立，且回退路径可用。

---

## 2. 实现

| 层 | 内容 | 文件 |
|---|---|---|
| 协议解析 | 18 原语白名单、`key`/`bind`/`onTap|onSubmit|onChange`、`list` item 模板、节点/深度/文本上限、动作 id 规则 | `core/plugins/SurfaceSpec.kt` |
| 插件模型 | `PluginShell(id,label,hasState,hasAction,hasEvent)`、`PluginRecord.shells`、持久化、试运行/热重载解析 | `core/plugins/PluginModel.kt`、`PluginReload.kt`、`app/data/PluginStore.kt` |
| JS 契约 | `ctx.ui.registerShell({id,label,mount,onState,onAction,onEvent})`；常驻 runtime 的 `shell.mount/state/action/event`；`ctx.surface.command(name,args)` | `core/plugins/PluginModule.kt`（PRELUDE / RESIDENT_DISPATCH） |
| 权限 | `ui` 权限从「未实现」改为**已实现**（导入即允许）；`surface.command` 在沙箱里要求 `ui` | `core/plugins/PluginPermissions.kt`、`PluginSandbox.kt` |
| 渲染 | JSON 树 → Compose；`bind` 在组合里直读宿主 state；`list` 用 LazyRow/LazyColumn + key + 自动跟到底部 | `app/ui/surface/SurfaceRenderer.kt` |
| 壳宿主 | 选择 / mount 10s / dispatch 3s / 连续 3 次失败回退原生 / 结构变化才 `shell.state` | `app/data/PluginShellHost.kt` |
| 宿主接线 | 冷启动 `dsh_shell`、`shellActive`、state 构造、命令白名单、dump | `app/chat/AppViewModel.kt`、`app/MainActivity.kt` |
| 主界面替换 | 有插件壳时整个 `DshApp` 主区分支到 `PluginShellScreen`，否则原 `DshAppContent` | `app/ui/App.kt`、`app/ui/surface/PluginShellScreen.kt` |
| 演示壳 | 会话横条 + 消息列表 + 输入框 + 发送/停止 + 新会话 + 回原生 | `docs/m85a-minimal-shell/` |

### 2.1 Shell 协议 v1（本批落地形态）

宿主 → 壳（JSON，dispatch 进同一常驻 runtime）：

```text
shell.mount  {id, state}                 → {ok, tree:{title?,tree}}   （10s）
shell.state  {id, state}                 → {ok, tree}                 （3s；只发结构变化）
shell.action {id, action, value, state}  → {ok, tree}                 （3s）
shell.event  {id, event, state}          → {ok, tree}                 （预留）
```

壳 → 宿主（同步，白名单在 `AppViewModel.shellCommand`）：

```text
ctx.surface.command('send', {text})       stop / newSession / openSession{id|index}
selectSession{id|index}                   setDraft{text}
openSettings（M85-A：切回原生设置页）       closeShell（切回原生壳）
```

`state` 字段：`chat{title,sessionId,status,draft,streaming,turn{active,step},messages[]}`、
`sessions[{id,title,updatedAt,current}]`、`settings{model,assistantName,themeMode,accent,planActive}`、
`theme`、`capabilities{surface,commands,bindRoots}`。消息带 `id/role/text/blocks/streaming/seq/error`。

### 2.2 绑定与性能纪律

- `bind` 只允许只读路径：根 `chat/sessions/settings/theme/capabilities/item`；标量必须指到字段，
  只有会话列表允许整根 `sessions`；`item.*` 只在 `list` 的 item 模板里合法。
- 文本/流式 token **不跨 JS**：`SurfaceTreeView` 在 Compose 组合里读 `chat.visibleMessages`，
  消息对象替换（`messages[i] = copy(...)`）直接触发重组；实测长回复流式期间只有
  `shell.state` 在结构变化（新消息/回合状态）时发出，不是每 token 一次。
- `chat.visibleMessages` 与原生聊天页一致地过滤 `TOOL` 消息和内部注入
  （`goal/tool-goal/tool-jobs/subagent-settled/skill-catalog/skill-invocation/time-context/compact-checkpoint`）；
  `chat.messages` 保留全量给后续工具卡/思考渲染。

### 2.3 调试通道（仅 debug）

```text
--es dsh_shell <插件id[#壳id]|native>   冷启写 pref；热启走 onNewIntent（必须 -f 0x20000000）
--es dsh_shell_list <绝对路径>          等 3.5s 落盘：pref/active/resident/treeMounted/treeError/shells[]
```

`dsh_shell` 只接受「已启用 + 有 `ui` 授权 + 声明了壳」的插件；失效自动回原生（pref 保留，
插件重新启用后按 pref 恢复）。

---

## 3. 安全

- `ui` 权限导入即允许（和 `fs:workspace` 同一档），但只给「注册整壳 + 使用白名单命令」；
  壳拿不到任意宿主方法。
- `surface.command` 三道门：沙箱要求 `ui` 授权 → `SurfaceSpec.COMMANDS` 白名单 → 主线程执行。
- Surface 解析强制：未知原语拒绝、节点 ≤2000、深度 ≤24、文本 ≤8000、动作 id 正则、未知属性丢弃；
  渲染器只做 18 种原语，不把 JSON 当代码执行。
- `image` 本批只支持**本地文件路径**（不借壳绕过 `net` 权限）；`webview` 本批只渲染 `html`
  内容，远程 `url` 明确显示「M85-F 开放」。
- 插件壳崩溃/超时：mount 超时 10s、dispatch 超时 3s、连续 3 次失败自动回原生并 Toast；pref 改写
  `native`，不会卡在空白主屏。

---

## 4. 单测

`:core:plugins:test` **115/115 通过**（14 个 suite），本批新增：

- `SurfaceSpecTest` 11 条：18 原语/ list 模板 / bind 白名单（含 `sessions` 整根、拒绝 `item.*` 越权）/
  未知原语 / 缺 tree / 动作 id / 未知属性丢弃 / 文本截断 / 节点与深度上限 / 裸节点 / state 收窄。
- `PluginModuleTest.parsesShellsAndResidentDispatch`：`registerShell` 元数据、重复 id、resident
  `shells` 与 `__dshShellDispatch`。
- `PluginSandboxTest.surfaceCommandRequiresUiGrantAndForwardsToHost`：无 `ui` → `PERMISSION_DENIED`；
  有 `ui` → 转发到 bridge。
- `PluginPermissionsTest` / `PluginReloadTest`：更新 `ui` 已实现后的自动授权预期。

---

## 5. 真机验收

设备：OPPO A55 5G，720×1600，Android 13；`dsh_llm_provider` off（走真实 DeepSeek 账号）；
无障碍 Bound；导入插件 36 个（新增 `m85a.chat`）。

| # | 动作 | 结果 | 证据 |
|---|---|---|---|
| 1 | 导入 `docs/m85a-minimal-shell`（打包成 zip） | 已导入、1 个权限（ui）、常驻 runtime 起来 | `docs/m85a-shells-1.json`、logcat `debug import ok=true` |
| 2 | 冷启 `--es dsh_shell m85a.chat` | 主界面是插件 Surface 树（标题/会话条/消息/输入框/发送） | `docs/shots/m85a-01-shell-home.png`、`m85a-13-shell-v3.png`、`m85a-shells-2.json`（`treeMounted=true`） |
| 3 | 发消息（真模型） | turn start → 流式文本进插件消息列表 → turn end | `m85a-18-stream2s.png`（部分故事 + 停止键）、`m85a-19-done.png`（完整故事 + 发送键） |
| 4 | 停止 | 点停止 → `stop requested` → turn end 2.6s，按钮从「停止」回「发送」 | `m85a-29-stop-before.png`、`m85a-30-stop-after.png`、`docs/m85a-logcat.txt` |
| 5 | 切会话 | 点会话 chip → `openSession` → 当前 chip 高亮、消息列表换成该会话 | `m85a-22-switch-session.png`、logcat `action=openSession ... index=6` + `shell.state` |
| 6 | 新会话 | 点「新会话」→ 空消息列表、发送键可用 | `m85a-28-new-session2.png`、logcat `action=newSession` |
| 7 | 回原生 | `--es dsh_shell native`（`-f 0x20000000`）→ 原生聊天页（HELLO-K 会话），无插件壳残留 | `m85a-27-native-chat.png`、`m85a-shells-3.json`（`pref=native`、`active=""`、`treeMounted=false`） |
| 8 | dump | `--es dsh_shell_list` 落盘壳清单/运行时/树状态 | `m85a-shells-1/2/3.json` |

关键日志（`docs/m85a-logcat.txt`）：

```text
DshShell: set active=m85a.chat#m85a.chat
DshShell: dispatch=shell.mount ... status=COMPLETED
DshShell: action=send → DshTurn: turn start → shell.state → turn end
DshShell: action=stop → DshTurn: stop requested → turn end after 2639ms
DshShell: set active=native
```

---

## 6. 限制 / 未验（不许当成已完成）

- 只验证「主界面」替换，**没有**把原生 22 页/设置/抽屉/onboarding 迁进 Surface；原生 UI 原样保留。
- 最小壳只显示文本消息（Markdown 原语已接）；工具卡、思考折叠、审批、附件、引用、用量、日程、
  plan/goal、长会话 1000 条压测都**未验**——这些是 M85-B 的对拍内容。
- `chat.visibleMessages` 过滤规则在宿主侧，属于 M85-A 的便利投影；M85-B 迁默认壳时要把
  「哪些消息可见」真正变成壳/投影插件的语义。
- `openSettings` 在 M85-A 是「切回原生设置页」；`deleteSession`、`selectModel`、
  `openPlugins`、`notify/copy/pickFile/openFloating/back` 未实现（命令表里也先不放）。
- `webview` 只渲染 html；远程 url、WebView 壳后端（整屏网页 UI）在 M85-F。
- 壳事件 `shell.event` 协议已留但宿主还没有触发点；壳热重载（JS 改完不重启）未做。
- 没有并排安装 0.3.2 APK 比对（同包名/数据红线）。
- 无障碍在 install 后会掉，已重新设回 **Bound**；设备其余状态未改（`dsh_llm_provider` off）。

---

## 7. 下一步（M85-B）

1. 用同一套 Surface 原语迁**聊天页**：消息气泡/Markdown/思考/工具卡/审批/附件/停止/上下文栏；
   缺原语先补协议（可选库组件要标注非强制），不许把智能组件塞回宿主。
2. `builtin.shell.whale` 作为内置插件提供 Surface 聊天页，和 legacy 原生壳**同会话对拍**：
   同一轮回复/工具卡/停止行为一致；legacy 可随时回退。
3. 对拍通过后才进 M85-C：22 页 + 设置 + 抽屉 + onboarding 全迁，**真删 legacy 原生 UI 代码**，
   M84-B 142 项改造成壳页面回归 142/142，作为 C 的硬验收。
4. 用户 2026-11-13 补充的四条继续当红线：
   - native 默认实现（agent/storage/prompt/model）必须同契约、可停用、可同 id 覆盖、不享受特权；
   - Surface 18 原语先压测再加页，不跳过 M85-A/B 直接删 UI；
   - root/NFC/Shizuku 等特权能力走 `capability/v1`（M85-F），不进骨架硬编码；
   - M85-C 必须真删 legacy，「新壳 + 旧 UI 并存」不算完成。

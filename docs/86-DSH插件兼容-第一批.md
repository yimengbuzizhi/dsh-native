# M57 · DSH 社区插件兼容（第一批：清单/提示词/通知/能力审计）

> 2026-11-09。用户明确要求「DSH 插件方面一定尽力做到全部都能在鲸歌里使用，多去 GitHub 看
> 插件到底有哪些类型」。本批先做只读调研 + 兼容层第一批；命令执行、客户端 UI、常驻 hook
> 放到下一批。

## 一、调研结论（两份独立报告）

### 1. 本机 DSH 安装包（app.asar，@deepseek-ai/* 244 个包）

- 官方**没有** `ctx.notifications`、`ctx.ui`、`ctx.prompt`。真实扩展点是：
  `ctx.tools`、`ctx.systemPrompt`、`ctx.commands`、`ctx.llm`、`ctx.agents`、`ctx.slots`
  （浏览器侧）、`ctx.configForms`（客户端）、`ctx.theme`/`ctx.locale`（客户端）、
  `ctx.jobs`、`ctx.goals`、`ctx.approval`、`ctx.skills`、`ctx.subagents`、`ctx.sandbox`。
- 提示词：`ctx.systemPrompt.section({name, order, text, complete?})`，
  另有 `context()` / `variable()` / `suppressRuntimeContext()`；order 升序、同 order 按 name；
  `complete:true` 是「接管整个 system」，多个生效会装配失败。
- 命令：`ctx.commands.register({name, description, input:{hint,images}, handler({agent,rawInput})})`；
  结果 `{kind:'success'|'error', text}`，**不进模型历史**；生命周期写 `command/run|done`。
- 通知：**不存在标准 API**；社区实现是浏览器 `new Notification()` + WebAudio。
- UI：`dsh.client` + `exports["./client"]` + `window.__ModuleLoader__` + `ctx.slots.register`
  （React DOM），不是 iframe、不能脱离 Web GUI 执行；手机只能做声明式降级。
- 工具：`defineTool` 的 `output:{schema, render}` 必填；`execute(args, exec)` 只返回 JSON 值；
  没有 approval 字段，审批走 `tools/pre-execute` / `agent.approval`。

### 2. GitHub 社区 13 个插件实测（jsDelivr 抓源码）

- **13/13 都声明了 `dsh.client`**（浏览器半侧），12/13 带 `dsh.bundle.patch`；
  纯工具只是子集（7/13）；设置卡 6/13；`agent/*`/`llm/*` 钩子 5/13；通知 2/13。
- **0/13** 使用 `ctx.commands.register`、`ctx.systemPrompt.section`、`agent.inject`
  （官方一等扩展点，社区使用率极低，但价值高）。
- 典型类型：纯工具（blender/mineru）、工具+设置卡、主题/桌宠（whale-pet/aqua）、
  侧栏话题（topic-for-each-turn）、TTS/通知（tts/notify-sounds）、LLM 适配器
  （subscriptions/antigravity）、市场/管理器（大量 settings + webServer）。
- 鲸歌边界：浏览器 bundle 本身不可执行；`ctx.webServer`/Node 主进程/Electron 不可移植；
  工具、提示词段、命令、设置卡、通知可以模拟；slots 只能降级成原生声明式组件。

## 二、本批已做

### 1. 清单识别（P1/P2 的前置）

`PluginManifest` / `PluginRecord` 新增并持久化：

- `clientUi`：`package.json` 的 `dsh.client` 存在 → true；插件页显示
  「含浏览器半侧（dsh.client）：手机暂不执行 Web UI；工具/提示词/命令仍按声明接入」。
- `bundlePatch`：`dsh.bundle.patch`（字符串或数组取第一个），插件页展示。
- `enginesDsh`：`engines.dsh`，插件页展示（暂不做版本门，避免误拒插件）。

### 2. 能力审计（P0：不再静默吞掉）

JS shim 里这些以前全是 `__noop`，现在记录到 `__dsh.unsupported` 并持久化：

- `ctx.sessionProjections.register`
- `ctx.llm.register`
- `ctx.ui.registerRoute` / `ctx.ui.registerView`
- `ctx.on` / `ctx.effect`
- `ctx.systemPrompt.variable` / `suppressRuntimeContext`

插件详情里用警示色列出「用到但鲸歌未支持的能力」。**没有把 apply 直接打挂**：
很多插件只是可选地注册 hook，直接抛错会让纯工具插件也装不上。这是有意的取舍。

### 3. 提示词段 + 上下文（P4）

- JS shim 新增 `ctx.systemPrompt.section/context`：校验 name、order、text；
  text 允许函数，注册时用当前 ctx 求值一次（手机没有常驻运行时，函数型按静态快照）。
- dry run 返回 `promptSections` / `contexts`，写进插件记录。
- 每次请求的 system prompt：宿主提示词之后，按 order/name 拼所有启用插件的
  `promptSections`，再拼 `contexts`（DSH 是 user-role 快照，手机先并入 system 并注明）。
- `complete:true` 不启用（会整体接管 system，权限过大）；插件页会注明。

### 4. 斜杠命令（P5 的注册半侧）

- JS shim 新增 `ctx.commands.register({name, description, handler})`：名字校验
  `[a-z0-9_-]{1,64}`、去重、handler 存进 `__dshDefs['command:<name>']`。
- dry run 返回 `commands` 并持久化；插件页列出 `/name — description`。
- **执行链路还没接**：插件页明确写「命令执行链路下一版接入」，不会假装能用。

### 5. 通知（P6，鲸歌私有扩展点）

- JS：`ctx.whale.notify({title, body, level})`，以及全局 `new Notification(title,{body})`
  重定向到同一个宿主方法（社区通知插件就是这种写法）。
- 宿主：`PluginNotifier` 建 `dsh_plugin_notify` 通道（名称「插件通知」），
  没有 POST_NOTIFICATIONS 权限时静默丢弃；点通知回 `MainActivity`。
- 执行路径：`PluginToolRuntime` 的 `callSync` 拦截 `plugin.notify`，调 `PluginNotifier`；
  试运行阶段不会弹通知。

### 6. 插件详情展示

插件详情新增：界面扩展、组合包补丁、要求 DSH 版本、提示词段（含 order）、命令、
未支持能力列表。

## 三、真机验证（OPPO A55 5G，720×1600，Android 13，`192.0.2.10:43405`）

测试插件 `whale-compat-test`（工具 + 提示词段 + 上下文 + 命令 + `ctx.on`/`llm.register`/
`sessionProjections.register` + `new Notification` + `dsh.client` + `dsh.bundle.patch`）：

```text
debug import ok=true 已导入 whale-compat-test 1.0.0；注册 1 个工具：compat_echo
plugins.json:
  "clientUi":true,"bundlePatch":"cordis.patch.yml","enginesDsh":">=0.2.0",
  "promptSections":[{"name":"compat:test","order":150,"text":"（whale-compat-test）…"}],
  "contexts":[...],"commands":[{"name":"compat",...}],
  "unsupported":["ctx.on","llm.register","sessionProjections"]
工具调用: debug call name=compat_echo … isError=false text=compat echo: 你好
通知: dumpsys notification → android.title=whale-compat-test，
      channel=dsh_plugin_notify（插件通知）
请求体: --es dsh_dump_request 落盘，system 末尾出现
  “（whale-compat-test）这条提示词段由插件注册，鲸歌已把它拼进 system prompt。”
  tools 里出现 compat_echo
```

## 四、下一批（按优先级）

1. **命令执行**：`PluginModule.commandScript` 调 `handler({agent, rawInput, attachments})`，
   走 `command/run|done`；`AppViewModel.submit` 在「未知命令」之前派发；
   `/` 面板列出插件命令；执行结果显示为 command notice，不进模型历史。
2. **常驻 QuickJS + hook**：当前每次调用重建实例，`ctx.on('session/event')` 之类无法持续；
   需要常驻实例、事件白名单（session/event、agent/pre-step、tools/post-execute、llm/stream）、
   超时与异常隔离、卸载清理。
3. **组合包（bundle patch）展开**：解析 `cordis.patch.yml` 的 `insert` 行，列出子插件；
   现在只显示 `bundlePatch` 路径，不会展开。
4. **客户端 UI 声明式降级**：不执行 `lib/client.js`；解析 `dsh.client.inject`/slot 白名单，
   映射到原生横幅/列表/悬浮球；明确拒绝 `root` slot。
5. **设置卡**：`configForms` + schemastery → 原生表单（现在只能编辑 JSON）。
6. **LLM 适配器**：只支持 fetch + API Key 型；OAuth 型明确不支持。

## 五、文件清单

- `core/plugins/PluginModel.kt`：Manifest/Record/DryRun 新字段 + 序列化
- `core/plugins/PluginPackage.kt`：解析 `dsh.client` / `dsh.bundle.patch` / `engines.dsh`
- `core/plugins/PluginModule.kt`：JS shim（systemPrompt/commands/whale.notify/Notification/
  unsupported）+ dry run 解析
- `core/plugins/PluginReload.kt`：热重载保留/更新新字段
- `app/data/PluginStore.kt`：安装/重载写入新字段
- `app/data/PluginToolRuntime.kt`：`plugin.notify` 宿主拦截
- `app/data/PluginNotifier.kt`：Android 通知通道
- `app/chat/AppViewModel.kt`：system prompt 拼插件段/上下文
- `app/ui/plugins/PluginsScreen.kt`：插件详情展示新能力

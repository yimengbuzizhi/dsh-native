# 128 · M84-E2：外部插件 API（二）——`llm.register` / `ui.registerRoute` / 社区插件矩阵

> 2026-11-13 20:1x。目标来自 `docs/115 §E`。本批完成两件原语：**配置式模型供应商**
> `ctx.llm.register` 和**页面路由登记** `ctx.ui.registerRoute`；`ctx.ui.registerView` 与
> `ctx.sessionProjections.register` 如实保留 unsupported。另做 10 个社区插件 + 8 个验收插件的真机矩阵。
> 证据：`docs/m84e2-matrix.json`、`docs/m84e2-provider-log.txt`、`docs/m84e2-providers-*.json`、
> 截图 `docs/shots/128-01…128-07`。本批未发版。

## 一、做了什么

### 1. `ctx.llm.register`：配置式供应商

手机端没有常驻 JS 运行时，接不了 DSH 桌面端「插件提供一个 `stream()` 回调」的完整形态。
本批做最小可用版：**插件登记配置，宿主包装成内置通道**。

JS 侧（`core/plugins/PluginModule.kt` 的 prelude）：

```js
ctx.llm.register({
  id: 'my-provider',        // 必填，[A-Za-z0-9][A-Za-z0-9._-]{0,63}
  label: '我的供应商',
  protocol: 'openai',       // 可省略 = 跟随宿主当前协议；也接受 anthropic
  baseUrl: 'https://...',   // 可省略 = 跟随宿主端点
  apiKey: '...',            // 可省略 = 跟随宿主 key（仅当端点也跟随宿主时）
  model: 'my-model'         // 可省略 = 跟随宿主当前模型
})
```

- 登记结果写进试运行 JSON（`llmProviders`），再进 `PluginRecord` / `plugins.json`；
  安装、重载、热重载三条路径都带上（`PluginStore` / `PluginReload`）。
- 宿主侧新增 `core/llm/ConfiguredLlmProvider.kt`：把配置解析成
  `LlmProvider`，按 `protocol` 交给 `LlmClient`（Anthropic Messages）或 `OpenAiClient`
  （OpenAI 兼容）；写了 `model` 就把请求体 `model` 改写。
- **key 回落规则（防泄漏）**：插件没写 `baseUrl` 才允许跟随宿主 key；一旦写了自定义端点，
  key 为空就按「未配置 API Key」失败，不会把宿主的 key 发给插件的端点。
- `AppViewModel.syncPluginLlmProviders()` 跟着 `syncPluginTools()` 走：
  只注册**启用中**插件的供应商；停用/删除时收回；每次重建先 `registerBuiltinProviders()`
  再按插件顺序注册，避免插件同名覆盖 `deepseek`/`openai` 后停用导致内置通道消失。
- `--es dsh_dump_providers` 新增 `pluginProviders`（id/label/protocol/model/baseUrl/owner/hasKey），
  **不写 apiKey**。
- 无 id / 无 stream 的 `ctx.llm.register({})`（兼容性探针常见写法）静默忽略；
  带 `stream` 函数的 provider 记为 `llm.register(stream)` unsupported，不假装支持。

**限制（照抄）**：没有 JS `stream` 回调、没有「拉模型列表」、没有供应商选择 UI（C3 可选）。
插件配置里的 `apiKey` 和插件 `config` 一样以明文存在 App 私有目录 `files/plugins.json`，
不写日志、不写 dump；「用哪个供应商」目前只能靠 `--es dsh_llm_provider <id>`（仅 debug）。

### 2. `ctx.ui.registerRoute`：页面入口登记

```js
ctx.ui.registerRoute({ path: '/my-page', title: '我的页面', description: '...' })
```

- 元数据（id/path/title/description）进插件记录；插件详情页新增「页面路由」段。
- 点「打开插件界面」= 跑插件**自己的** `dsh.client`（复用 M70 的
  `ClientPluginWindowService` / `pluginui`），不是宿主按插件 id 画原生页。
- 插件没有 `dsh.client` 入口时不假装成功：沿用原提示「找不到 client 入口」。
- 插件停用后路由不生效：详情页标「插件已停用：路由不生效」，按钮隐藏。
- `ctx.ui.registerView` **仍是 unsupported**（往宿主页面插任意 Composable 需要常驻运行时/视图协议）。

### 3. `ctx.sessionProjections.register`：明确 unsupported

每事件回调同样依赖常驻 JS 运行时 + 事件回传协议，本批不做假的「只登记名字」。
调用会进 `unsupported`，插件页显示 `sessionProjections`；能力清单里注明原因。

### 4. 能力清单

`PluginCapabilities.VERSION` 从 `m84.1` → **`m84.2`**：
26 个原语中 **24 个已实现**（原 22），仍未实现：`ui.view`、`session.projections`。
插件页标题「能力清单 24 / 26 个原语已实现」（截图 `128-04`）。

### 5. 单测

- `PluginModuleTest` 新增 `parsesLlmProvidersAndRoutes`：脚本里出现 `__dsh.llmProviders.push` /
  `__dsh.routes.push`；坏 id、重复 id 丢弃；`registerView`/`sessionProjections` 仍是 unsupported；
  旧试运行结果缺字段按空表读。16/16 通过。
- `PluginServicesTest` 更新：`llm.register` / `ui.route` 已实现，`ui.view` / `session.projections`
  未实现。全量构建（`:app:assembleDebug` + tools/plugins/engine/prompt/llm/session 六个测试任务）
  `BUILD SUCCESSFUL`。

## 二、真机验收（OPPO A55 5G，Android 13）

演示插件 `m84e2-demo`（源码 `docs/m84e2-demo-plugin/`，zip 导入）：2 条供应商配置 + 1 条路由 +
1 个工具 + `client.js`。

### 1. 正向：插件登记的 id 真跑一轮

```
I DshProvider: plugin provider id=e2.demo.provider protocol=openai model=deepseek-chat baseUrl=host apiKey=host requestModel=deepseek-flash plugin=m84e2-demo
I DshTurnHook: step-start turn=1 step=1
I DshTurnHook: step-end turn=1 step=1
```

- 宿主设置 `deepseek-openai` / `https://api.deepseek.com` / `deepseek-flash`；
- 插件没写 `protocol`，宿主解析为当前协议 `openai`；写了 `model: deepseek-chat`，
  请求体模型从 `deepseek-flash` 改成 `deepseek-chat`，一轮正常结束；
- 截图 `128-05`（聊天页出现回复）。完整日志见 `docs/m84e2-provider-log.txt`。

第一次按 `anthropic` 写死时真机返回 404（宿主是 OpenAI 兼容端点），因此改成
**空协议跟随宿主**；这也是插件作者最容易踩的坑，已写进能力清单说明。

### 2. 负向：模型名真的到上游

插件另登记 `e2.demo.probe`，模型写成 `m84-e2-model-does-not-exist`，真机一轮：

```
I DshTurnHook: error code=invalid_request_error status=400
message=The supported API model names are deepseek-flash, deepseek-v4-pro, but you passed m84-e2-model-does-not-exist.
```

上游错误原文点名插件写的模型 → 证明不是「注册了但没用」。

### 3. 生命周期：停用即收回

插件页关掉 `m84e2-demo` 后 dump：`llm=[debug.echo,debug.slow,debug.tool,deepseek,openai]`、
`pluginProviders=[]`、`llmActive=""`；内置通道还在。启用时 dump 含 `e2.demo.probe` /
`e2.demo.provider`，`llmActive=e2.demo.provider`（`docs/m84e2-providers-*.json`）。

### 4. 路由与插件界面

- 插件详情显示两条供应商、路由 `/m84e2`，以及 unsupported `ui.registerView` /
  `sessionProjections`（截图 `128-01`、`128-02`）；
- 点「打开插件界面」→ 插件自己的 `client.js` 被加载并 `apply`，
  插件里 `setContentSize(300, 140)` 触发窗口 `resize 600x280`（截图 `128-03`，日志见 §四.4）；
- 真实社区 UI 插件 `@dsh-external/dsh-client-ui-pet` 也能被加载并 `plugin applied`，
  只缺一张动画帧 `media/pet/idle_0/42.png`（截图 `128-06`）；
- 停用 `m84e2-demo` 后详情页标「供应商已从注册表收回」「路由不生效」，打开按钮隐藏
  （截图 `128-07`），dump 同步为空。

### 5. 兼容层回归

`whale-compat-test` 重载后 `ok=true`：提示词段 1、上下文 1、命令 1、工具 1、`error=null`，
`unsupported` 从 `ctx.on+llm.register+sessionProjections` 收敛为 `sessionProjections`。
旧版会因 `ctx.llm.register({})` 误报错误，E2 起静默忽略。

## 三、10 个社区插件矩阵（真机）

完整结果 JSON：`docs/m84e2-matrix.json`（18 行 = 10 个主插件 + 8 个验收/夹具）。
「能装」以设备上已安装记录 + 本次重装/重载为准；未跑的执行标 `not_run`，不写「通过」。

| # | 插件 | 类型 | 工具/提示词 | 真机执行 | 结果 |
|---|---|---|---|---|---|
| 1 | `dsh-hello` | DSH | 1 工具 | `hello {name:matrix}` | ✅ `Hello, matrix!` |
| 2 | `whale-compat-test` | DSH | 1 工具 + 1 提示词段 + 1 上下文 + 1 命令 | `compat_echo` | ✅ `compat echo: matrix`；重载后 unsupported 只剩 `sessionProjections` |
| 3 | `operit-time` | ToolPkg | 3 工具 | `get_time` | ✅ 返回 ISO/本地时间 JSON |
| 4 | `operit-env-demo` | ToolPkg | 2 工具 | `env_echo` | ✅ manifest Config 进 env（示例值） |
| 5 | `operit-extended-file-tools` | ToolPkg | 9 工具（fs:any） | `file_exists /sdcard` | ✅ `exists=true,isDirectory=true` |
| 6 | `operit-extended-http` | ToolPkg | 4 工具（net:any） | `http_request example.com` | ⚠️ **15s 未返回**，宿主不崩，列未解决 |
| 7 | `operit-zhipu-search` | ToolPkg | 3 工具 | `search deepseek` | ⚠️ 工具可用；返回「未设置 API Key」，且插件把错误放在正常 text（isError=false） |
| 8 | `operit-system-tools` | ToolPkg | 33 工具（device/shell/fs:any） | `get_device_info` | ✅ 返回机型/Android/内存；deviceId 已打码 |
| 9 | `@dsh-external/dsh-client-ui-pet` | 真实 DSH UI | 0 工具，`lib/client.js` | 打开插件窗口 | ✅ `plugin applied`；缺 1 张帧图 |
| 10 | `dsh-dafeiyu` | UI 夹具 | 0 工具，声明 `dsh.client` 但无入口 | 打开插件窗口 | ⚠️ 如实报「找不到 client 入口」 |

附录（同一份 JSON）：`demo-echo` ✅、`bridge-denied` ✅（权限拒绝原文）、`svc-provider-a` /
`svc-consumer-b` ✅（provide→inject）、`zstd-demo` ⚠️（相对路径按插件目录解析，报文件不存在）、
`dsh-ventus-whale` / `dsh-client-ui-pet` ⚠️（同 dafeiyu 夹具无 client bundle）、`m84e2-demo`
（本批验收，已停用）、`suppress-runtime-demo`（E1，保持停用）。

矩阵暴露的 3 个真实问题：

1. **`operit-extended-http` 的 `http_request` 两次真机都不返回**（第二次等 15s）：宿主进程存活、
   无崩溃日志。可能卡在 QuickJS 内 `ctx.http`/fetch 结算，需要单独复现；本批不修、不掩盖。
2. **UI 夹具不等于 UI 插件**：`dsh-ventus-whale` / `dsh-dafeiyu` / `dsh-client-ui-pet` 只有
   `index.js` + 清单，没有 client bundle；真正能跑的是 `@dsh-external/dsh-client-ui-pet`
   （`exports["./client"]`）。矩阵把「清单声明」和「真的能开」分开记。
3. **错误语义不统一**：`operit-zhipu-search` 把失败放在正常工具文本里（`isError=false`），
   `zstd-demo` 用 `isError=true`；模型侧无法统一判断，只能原样展示。

## 四、限制 / 未做

- **没有常驻 JS 运行时**：所有登记都是 apply 时快照；运行期动态增删供应商/路由要重载插件。
- `llm.register` 只接受配置；`stream` 回调、模型列表拉取、鉴权刷新未做。
- 没有供应商选择 UI（C3）；当前只能用调试通道 `--es dsh_llm_provider <id>` 指定。
- `ui.registerRoute` 只登记元数据 + 打开 `dsh.client`；**不能**把任意 Composable 插进宿主页面。
- `ui.registerView` / `sessionProjections` 仍 unsupported（非「已做」）。
- `apiKey` 明文存在 App 私有 `files/plugins.json`（与插件 config 同级）；没有加密。
- 自定义端点 + 空 key 会直接报未配置，不回落宿主 key（有意为之，防泄漏）。
- `ctx.on` 仍只有订阅名登记，没有运行期事件回调；Node 内置模块子集未重新做矩阵。
- `operit-extended-http` 超时未解决；`zstd-demo` 只验了错误路径。
- 本批未发版。

## 五、下一步：F 空壳 + 全装配

1. **空壳**：23 个内置插件全关 + 用户插件全停（29+ 个）冷启动，仍能进聊天/插件页/设置页；
   截图 + 日志 + 冷启动耗时。
2. **全装配**：内置全开 + 用户插件按需开启，功能对齐 0.3.2；M84-B 的 22 页 / 23 内置插件语义回归；
   `builtin.floating.demo` 默认关。
3. **冷启动 < 3s**：至少 5 次取中位数，记录测量方法。
4. 收尾再清理设备上早前的验收插件（svc-*、ocr-demo、zstd-demo、bridge-denied、bundle-demo、operit/* 等），
   不要在 F 之前误删。

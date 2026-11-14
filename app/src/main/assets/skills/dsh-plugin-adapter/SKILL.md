---
name: dsh-plugin-adapter
description: 把 DSH（DeepSeek Harness）社区插件迁到鲸歌 Android 原生版：先判断哪些能力搬得动，再按当前宿主契约改包、装测。覆盖工具/命令/提示词/页面/加载页/整壳/回合/存储/模型/能力/投影的适配判断，以及 npm、Cordis、DOM、Electron 等不可搬部分的替代方案。
whenToUse: 用户拿来一个 DSH 插件（npm 包 / GitHub 仓库 / dist/index.js / src/index.ts / cordis.patch.yml）问「能不能在鲸歌用」「怎么改成鲸歌插件」「插件导入失败」，或提到 defineTool / @deepseek-ai/dsh-tools / schemastery / cordis / dsh.client / root slot 时加载。
---

# DSH / 社区插件 → 鲸歌插件适配

目标：**先判定、再搬迁、最后真机验证**。鲸歌的插件链是 QuickJS 宿主（无 Node、无 npm、无 DOM、无 Electron），但「插件化」已经覆盖角色而不只是工具：工具、斜杠命令、提示词段/整体提示词、页面与客户端窗口、启动加载页、整壳（Surface/WebView）、回合、只读存储、模型供应商、能力提供方、会话投影、跨插件服务都能接。

角色级 API、限制与验收细节以技能 `dsh-plugin-authoring` 和宿主 `plugin_dev_*` 的实际返回为准；本技能只负责「旧插件怎么落到这些角色上」。两者冲突时以作者技能/宿主实现为准，不要照搬本文里的示例参数名。

## 0. 先读包，再下结论

必看：`package.json`（`main`、`dsh.client`、`dsh.bundle.patch`、`dependencies`）、入口 JS、`dist/` 是否有可直接跑的构建产物。

| 插件里有什么 | 现在的处置 |
|---|---|
| `ctx.tools.register(defineTool(...))` | 工具半侧可直接搬，先试导入 |
| `ctx.commands.register` / `ctx.systemPrompt.section` / `ctx.systemPrompt.context` | 已支持，通常零改动 |
| `ctx.systemPrompt.register`（整体提示词） | 已支持，作为「提示词」角色，需用户主动选中 |
| `ctx.sessionProjections.register` / `ctx.on(...)` / `ctx.effect(...)` | 已支持（常驻 runtime）；投影/订阅需按会话重放语义改写，见作者技能 |
| `ctx.ui.registerShell` / `ctx.ui.registerLoading` | 已支持；整壳和加载页都是「可替换角色」，装好还要用户选中 |
| `ctx.ui.registerRoute` / `registerPage` + `dsh.client` | 已支持：插件自己的 `__ModuleLoader__` 客户端 bundle 跑在插件窗口 |
| `ctx.agent.register` | 支持 text-only 回合插件；工具/LLM 流式原语仍 UNSUPPORTED |
| `ctx.storage.register` | 只读存储（v1）；没有写入契约 |
| `ctx.llm.register`（配置式或 `stream`） | 支持；不要填真实密钥 |
| `ctx.capability.register` | 支持；调用入口是插件页/调试入口 |
| React / DOM / Electron / Qt / PowerShell helper | 只有 Web 半侧可跑（客户端 bundle）；原生 helper 不能执行 |
| `insert:` 展开的 cordis 组合包、npm 子插件 | 不展开、不自动安装；当普通单包处理 |
| MCP server / 纯主题 / 纯 prompt 包 | 不走这条链，如实说明 |

**第一步永远是**：读 `package.json` + `main` 指向的构建后 JS。TS 源码不能直接装。

## 1. 旧的适配结论已过期（别按记忆照抄）

这些说法在 M71 之前成立，现在**不成立**：

- ❌「鲸歌只保留模型工具这一半」→ 现在工具、命令、提示词、页面、加载页、整壳、回合、存储、模型、能力、投影都能接。
- ❌「任何接管 DSH 根界面的 root slot 一律拒绝」→ 现在插件可以 `registerShell` 提供整壳，默认仍是内置 `builtin.shell.native`，`builtin.shell.whale` 只是可选第二套；是否切换由用户在「插件 → 角色 → 界面」里决定，安装不等于选中。
- ❌「`ctx.on` / `ctx.llm` / `ctx.sessionProjections` / `ctx.ui.register*` 只登记不回调」→ 这些都已实现（加载页、整壳、投影、事件订阅、模型流均有真实 dispatch）。
- ❌「`shell` 权限每次调用都要用户确认」→ 按当前审批策略：`ask` 模式询问、`auto` 模式允许；授权是插件页逐项授予，不是每次弹窗。
- ❌「插件 UI 不执行，只能做原生替身」→ 客户端窗口与整壳都是一等能力；原生替身只在 Web 半侧不可移植时考虑。

仍然成立的硬边界：无 npm 安装、无相对 `import`/`require` 解析、无 DEX/APK 动态加载、无完整 Cordis 服务树、无桌面 helper、`ctx.ui.registerView` 未实现、`ctx.agent` 的 `tools.invoke`/`llm.stream`/`storage.append` 返回 UNSUPPORTED、storage v1 只读。

## 2. 包形状与清单

```
my-plugin/
  package.json      # 必需
  index.js          # 入口（默认 lib/index.js；建议显式 main）
  client.js         # 仅页面/客户端窗口需要
```

```json
{
  "name": "hello-dsh",
  "id": "hello-dsh",
  "version": "0.1.0",
  "description": "从 DSH 插件 hello 适配",
  "main": "index.js",
  "permissions": ["ui"],
  "dsh": { "client": { "platform": "web", "entry": "client.js" } },
  "exports": { "./client": "./client.js" }
}
```

- `id` 可省（默认 name），1–80 字符，字母数字与 `@ . _ / -`；**不要**用 `builtin.*`。
- 只声明真正用到的权限，见第 5 节。
- zip 限制：≤2000 文件、单文件 4 MiB、解压总计 32 MiB、无绝对路径/`..`/重复路径。
- 同 id 安装 = 升级：保留新 schema 兼容配置、重新启用、旧危险授权要重新确认；保留旧包可回退。

## 3. 工具适配（最常见）

入口导出 `apply(ctx, config)`，ESM/CJS 都接受。DSH 的 `defineTool` 与 schemastery 子集已 shim，纯工具插件多数可零改动：

```js
export function apply(ctx) {
  ctx.tools.register({
    name: 'hello',
    description: '按名字打招呼',
    parameters: { type: 'object', properties: { name: { type: 'string' } }, required: ['name'] },
    async execute(args) { return '你好，' + String(args.name) + '！' }
  })
}
```

改造要点：

- **参数**：DSH 的「参数项上的 `required: true`」DSL 会被转成 JSON Schema；认不出的 schema 不会进模型工具表（试运行会报）。优先直接写 JSON Schema。
- **`output.schema` / `output.render`**：删除；把 render 文案并进 `execute` 返回值（字符串最稳，或可 JSON 序列化值；对象有 `text` 取 `text`）。
- **`execute(args, exec)`**：`exec.signal/agent` 没有；长任务靠 await，宿主有超时与停止。
- **模块**：`@deepseek-ai/dsh-tools`、`@deepseek-ai/schemastery`、`node:fs`、`node:path`、`node:os`、`node:child_process`、`fetch`、`console`、`setTimeout` 有 shim；`axios`/`lodash` 等改为原生 JS 或内联；相对 import 必须打包成单文件。
- **状态**：工具调用会新建 QuickJS 并重新 `apply`，不能用工具闭包保存跨调用状态；`apply` 只登记，副作用放执行函数。
- **通知**：`notify` 权限当前**未实现**，不要用插件通知作为交付前提；需要提示就在返回值里写清楚。

## 4. 其余角色的落点（要点，细节见作者技能）

- **命令**：`ctx.commands.register({name, description, handler})`；`/name 参数` 执行，结果显示在命令提示条，不进模型历史。
- **提示词段**：`ctx.systemPrompt.section/context`；`text`/`variable` 在 apply 时求值成快照，不做每请求动态执行；`complete:true` 不启用。
- **整体提示词**：`ctx.systemPrompt.register({id,label,assemble(req)})`，需要 `prompt` 权限与用户选中。
- **页面**：Host 侧 `ctx.ui.registerRoute/registerPage` + 清单 `dsh.client`；客户端必须是 `__ModuleLoader__` bundle，不是裸 ESM/HTML。当前客户端面只保证 `sessions/get`、`effect`、`slots`（实际渲染 `shell.overlay`）等最小集，`ctx.on` 是占位。
- **启动加载页**：`ctx.ui.registerLoading({id,label,tree})`，`loading/v1` 静态树；预算 8 个/包、单声明 ≤32 KiB UTF-8、≤128 节点、深度 ≤12、单 text ≤2000 字符；只能 bind `boot.phase/message/elapsed`。装好后在「插件 → 角色 → 启动加载页」选择，下次冷启动读快照。
- **整壳**：`ctx.ui.registerShell({id,label,backend:'surface'|'webview',mount,onState,onAction,onEvent})`；Surface 原语 17 种、≤2000 节点/24 层；WebView 返回 `{title,html}` 片段，用 `DshWebShell.action/command/state`。整壳命令（宿主侧 `ctx.surface.command`）包含 `send/stop/cancelQueuedPrompt/resumeQueuedPrompts/setSendWhileRunningMode/newSession/openSession/selectSession/openSettings/closeShell/setDraft/approval/pickFile/screenshot/removeAttachment/setShellView/answer/goal/deleteSchedule/focusSession`；命令在主线程同步执行并有超时，别在 onTap 里做长任务。
- **回合**：`ctx.agent.register({id,label,run(turn,api)})`，v1 只跑纯文本轮，`api` 仅 notify/status/abort/tools.names；不要宣称自主工具回合。
- **存储**：`ctx.storage.register({list,load})`，v1 只读；`exportList/exportLoad` 返回宿主信封，先看 `ok`。
- **模型**：`ctx.llm.register` 配置式（protocol/baseUrl/model/apiKey）或 `stream(request,emit)`；`emit` 增量文本，最终返回 `{text, toolCalls?, stopReason?, usage?}`，缺 usage 如实缺失。
- **能力**：`ctx.capability.register({id,label,kind,invoke})`；返回 JSON 或 `{ok,value}`；不自动获得 root/NFC/Shizuku/ADB。
- **服务/投影/事件**：`provide/inject` 是 JSON 快照；`ctx.effect` 返回同步 cleanup；投影 init/apply/wire 必须同步且返回可存值，改结构升级 `stateVersion`。
- **组合包**：`dsh.bundle.patch` 只解析展示元数据，`insert:` 不展开，子插件不自动安装——交付说明必须写清。

## 5. 权限（当前表）

| 声明 | 含义 | 授予方式 |
|---|---|---|
| `fs:workspace` | 当前工作区读写（canonical 校验防越界） | 导入即允许 |
| `fs:any` | App 可访问的任意路径 | 插件页确认 |
| `net:<host>` / `net:any` | 仅 https，按域名/子域；本机/内网默认拒绝 | 插件页确认 |
| `shell` | 以 App 身份执行命令（非 root） | 插件页确认 |
| `device` / `memory` | 设备信息/操作，鲸歌记忆库 | 插件页确认 |
| `ui` / `agent` / `storage` / `prompt` / `capability` | 注册对应角色 | 导入即允许，**选中才生效** |
| `notify` | 系统通知 | **本版未实现** |
| 其它写法 | 未知 | 不授予、不生效 |

安装/启用 ≠ 选中角色 ≠ 授权危险能力；AI 不得改授权表或绕过审批来完成验证，缺授权就如实记录。

## 6. 安装与验证流程

1. `plugin_dev_list`：看已装插件、工具/壳/loadings 登记与权限，确认是否升级同 id。
2. `plugin_dev_scaffold`：`template` 可取 `tool`/`surface-shell`/`webview-shell`/`loading`；不覆盖已有目录。
3. `plugin_dev_test`：隔离跑 apply，检查 `tools/shells/loadings/unsupported/missingServices` 与错误；这是试运行，不是功能验收。
4. `plugin_dev_install` → `plugin_dev_enable`；改包后用 `plugin_dev_reload`。
5. 真实调用：工具用 `plugin_dev_call` + 输入条/模型调用；页面点开插件窗口；整壳/加载页/回合/存储/提示词要用户在角色页选中后做冷启动或真实回合验证。
6. `plugin_dev_test_shell` 只校验树/HTML 形状，不切当前壳，也不证明画面已验收。

没有工具时：zip 交付到「包管理 → 插件 → 导入 zip」，用插件详情的试运行/启用/重载/授权/页面入口。

真机观测（debug 包）：

- `--es dsh_ui_probe <路径>`：全局 writer/草稿/队列/发送模式等只读快照；
- `--es dsh_dump_plugins <路径>`：插件启用/停用清单；
- `--es dsh_shell_list <路径>` / `--es dsh_loading_list <路径>`：壳与加载页候选、当前选中；
- `--es dsh_plugin_zip <路径>` / `--es dsh_plugin_grant <id>` / `--es dsh_plugin_off <id>`：安装、授权、停用；
- `--es dsh_dump_request <路径>`：看 request 里 system/tools 的实际注入（验提示词段）。

## 7. 交付前清单

- [ ] 包能导入，`plugin_dev_test` 无错，工具/角色数量符合预期。
- [ ] 工具的必填/可选/错误参数与真实 execute 各跑一次。
- [ ] 权限：未授权确实拒绝、已授权按预期；日志/返回值无凭据。
- [ ] 角色：需要用户选中的（页面/整壳/加载页/回合/存储/提示词）明确告诉用户去哪选，不宣称已生效。
- [ ] 生命周期：停用/重载后角色撤下、cleanup 生效；重启与会话重放不重复外部副作用。
- [ ] UI：窄屏/横屏/深浅色/长文本/键盘遮挡；整壳保留停止与恢复原生壳入口。
- [ ] 加载页：真实冷启动看所选快照首帧，记录 loadingFirstDraw/coreReady/shellFirstDraw（renderer 证据，不是呈现时间）。
- [ ] 无法搬的部分逐条列清原因（npm 依赖、DOM、原生 helper、Cordis 服务树、UNSUPPORTED 原语）。

## 8. 交付话术

> 这个 DSH 插件按鲸歌当前插件链适配好了：`package.json` + `index.js`（+ `client.js`），工具 `xxx`、命令 `yyy`、权限 `…`。
> 导入后启用即可用；页面/整壳/加载页这类角色需要你在「插件 → 角色」里选中才会生效。

部分适配时写清：搬了哪些角色、没搬哪些、为什么、以及用户需要手动选中的步骤。

## 9. 参考

- 作者技能：`dsh-plugin-authoring`（角色 API、loading/v1 预算、整壳原语、验收步骤以它为准）。
- 源码入口（仓库根）：`core/plugins/PluginModule.kt`（Host ctx/shim）、`PluginSandbox.kt`（fs/net/shell）、`PluginPermissions.kt`（权限表）、`PluginServices.kt`、`SurfaceSpec.kt`、`LoadingSpec.kt`、`PluginPackage.kt`、`PluginClientEntry.kt`、`BundlePatch.kt`。
- 客户端宿主：`app/src/main/assets/client-host/host.js`；整壳桥：`app/.../ui/surface/WebViewShellScreen.kt`；加载快照与恢复：`app/.../data/LoadingSnapshotStore.kt`、`app/.../ui/boot/`。

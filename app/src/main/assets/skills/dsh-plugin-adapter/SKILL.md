---
name: dsh-plugin-adapter
description: 判断一个 DSH / 社区插件在鲸歌该走哪套宿主：真 DSH / cordis / npm 插件优先装进 Node 宿主（nodehost_install），保留原包、不改成原生；只有用户明确要（或插件本来就是）鲸歌原生 QuickJS 格式时，才按角色改包走 plugin_dev_*。包含 Node 侧装测/诊断/隔离与原生侧适配映射。
whenToUse: 用户拿来一个 DSH 插件（npm 包 / GitHub 仓库 / dist/index.js / src/index.ts / cordis.patch.yml）问「能不能在鲸歌用」「怎么装」「导入/加载报错」，或提到 cordis、defineTool、@deepseek-ai/dsh-tools、dsh.client、plugin_manager 时加载。
---

# DSH / 社区插件 → 鲸歌：先选宿主，再搬

鲸歌现在有两条插件链，**先判断再动手**：

| 插件是什么 | 走哪条 | 怎么做 |
|---|---|---|
| 真 DSH / cordis 插件（`export function apply(ctx)`、`@deepseek-ai/dsh-tools`、npm 依赖、`cordis.patch.yml`） | **Node 宿主**（首选） | 原包装进 Node 宿主；**不改写成原生插件**。用 `nodehost_install` + `nodehost_*` 装测 |
| 已经是鲸歌原生格式（`package.json` + `apply` + `plugin_dev_*`，或用户点名要原生插件） | 原生 QuickJS | 按角色改包，用 `plugin_dev_test/install/enable` |
| 需要页面 / 整壳 / slot / WebView 插件 UI | 原生 QuickJS（Node 宿主 v1 没有界面桥） | 按原生 `ui` 角色改，或如实说明 Node 版没有界面 |
| 只是简单工具/命令/提示词，用户接受换格式 | 原生 QuickJS | 按第 4 节改；先说明这是分叉，不跟上游自动更新 |

**产品纪律（docs/185 D10）**：真 DSH 插件默认**不改写成 Kotlin/QuickJS 原生插件**。能跑就装在 Node 宿主；跑不了先补 Node 环境/依赖，再让 AI 改插件自身的 node 侧适配；最后才说「不支持」。不要为了一个插件在原生里加专属落点。

## 0. 先读包，再下结论

必看：`package.json`（`main`、`dsh.client`、`dsh.bundle.patch`、`dependencies`/`peerDependencies`）、入口 JS、`dist/` 是否有可直接跑的 ESM 构建产物。

- 入口是 `export const name` / `export function apply(ctx)` → Node 宿主。
- 入口是 `module.exports` / TS 源码 → 先构建成 ESM 再装；Node 宿主用 `import()` 加载。
- 有 `dependencies` 且不是 `@deepseek-ai/*` → 必须把依赖随包打进 `node_modules/`（不能在手机上 `npm install`）。
- 有绑定桌面/Electron/原生 helper 的代码 → Node 宿主也跑不了，如实标不支持。

## 1. Node 宿主路线（真 DSH 插件首选）

**包形状**：

```text
my-plugin/
  package.json      # name / version / main；main 指向 ESM 入口
  index.js          # export const name；export function apply(ctx, config)
  node_modules/     # 可选：插件自己的依赖（随包带）
```

**装法**：把目录/zip 放在工作区 → `nodehost_install {path:'my-plugin'}`（目录会自动打成单根 zip，手机不需要 `zip` 命令）；`nodehost_uninstall {id}` 卸载。宿主开着会自动重启；关着只落盘。

**可用 `ctx` 能力（v1）**：

- `ctx.tools.register(defineTool({name,description,parameters,output,execute}))` → 原生侧变成 `node_<name>`，Ready 后进模型工具表。
- `ctx.systemPrompt.section/context` → 原生宿主 Ready 后拉取合并。
- `ctx.commands.register({name,description,input,handler})` → `/name`。
- `ctx.llm.register({id,label,protocol,baseUrl,model,apiKey?})` → 声明式供应商，原生走自己的 HTTP 通道；只有 `stream()` 回调的供应商会如实标不可用。
- `ctx.provide/inject` 等 cordis 依赖只在 Node 宿主内部生效。
- **没有**页面/整壳/slot/WebView/npm 安装/`dsh web`/turn engine；不要承诺。

**装测**：

1. `nodehost_doctor` 先看 runtime/ABI/清单/坏插件；OFF/FAILED 时按 findings 的 fix 处理。
2. `nodehost_status` / `nodehost_plugins` 看 enabled/state/pid/tool_count/已加载模块与错误。
3. `nodehost_install` → 等 Ready → `nodehost_plugins` 确认 `ok=true`、工具/命令/供应商列出 → 真实调用一次 `node_<tool>`。
4. 失败看 `nodehost_logs {source:'host'}`（脱敏）和 `nodehost_plugins.error`；修工作区包后重装同 id。
5. 宿主坏了：`nodehost_autofix`（先 `dry_run:true` 看计划）或 `nodehost_repair` 单步。`reinstall-runtime` 只换运行时，插件/配置不动。
6. 插件连续 2 轮加载失败会被自动隔离（卸载 + 通知 + 审计）；插件把宿主进程搞崩时无法归因到单个插件，只能看 crash loop 日志逐个排查。
7. 宿主 control 有 3 次/5 分钟退避；写操作走审批 + `node-audit.jsonl`。
8. **v1 不允许 AI 改设备上已安装插件的 JS**：改工作区源包再装，或隔离后让用户修。

细节以 `dsh-node-plugin-authoring` 为准。

## 2. 旧结论已过期（别按记忆照抄）

- ❌「DSH 插件只能改写成鲸歌原生插件」→ **错**。真 DSH 插件优先原样装 Node 宿主；改原生是用户明确选择的分叉。
- ❌「鲸歌只保留模型工具这一半」→ 原生链现在工具、命令、提示词、页面、加载页、整壳、回合、存储、模型、能力、投影都能接；Node 链有工具/提示词/命令/模型供应商。
- ❌「任何接管 DSH 根界面的 root slot 一律拒绝」→ 原生可 `registerShell`；是否切换由用户在「插件 → 角色 → 界面」决定。
- ❌「`ctx.on` / `ctx.llm` / `ctx.sessionProjections` / `ctx.ui.register*` 只登记不回调」→ 原生链已实现；Node 链的 `ctx` 是真 cordis。
- ❌「`shell` 权限每次调用都要用户确认」→ 按审批策略：`ask` 询问、`auto` 允许；授权是插件页逐项授予。
- ❌「AI 不能改插件授权/配置/角色」→ `plugin_dev_grant/revoke/config/role` 一直可用（docs/190-F 已永久删除「AI 全控制」总开关），走工具审批 + `plugin-audit.jsonl`；系统权限（通知/Shizuku/无障碍/悬浮窗）仍只由系统弹窗授予。
- ❌「插件 UI 不执行，只能做原生替身」→ 原生客户端窗口与整壳是一等能力；Node 宿主 v1 没有 UI 桥，这两件事不要混。

仍然成立的硬边界（原生链）：无 npm 安装、无相对 `import`/`require` 解析、无 DEX/APK 动态加载、无完整 Cordis 服务树、无桌面 helper、`ctx.ui.registerView` 未实现、`ctx.agent` 的 `tools.invoke`/`llm.stream`/`storage.append` 返回 UNSUPPORTED、storage v1 只读。

## 3. 原生 QuickJS 路线（只在明确要原生时）

包形状与装测：见 `dsh-plugin-authoring`。最小包：

```text
my-plugin/
  package.json      # name/id/version/main/permissions
  index.js          # apply(ctx, config)
  client.js         # 仅页面/客户端窗口需要
```

```json
{
  "name": "whale-hello",
  "id": "whale-hello",
  "version": "0.1.0",
  "main": "index.js",
  "permissions": []
}
```

装测工具：`plugin_dev_list` → `plugin_dev_test` → `plugin_dev_install`（新装默认停用）→ `plugin_dev_enable` → 真实调用/页面验证；改包用 `plugin_dev_reload`，删除先停用再 `plugin_dev_delete`。没有工具时交付 zip 到「包管理 → 插件 → 导入 zip」。

### 原生角色映射（改包时用）

| 插件里有什么 | 原生落点 |
|---|---|
| `ctx.tools.register(defineTool(...))` | 工具；`ctx.tools.restrict/guard/describe/on` 已支持（describe/on 需 `tools:intercept`） |
| `ctx.on('agent/request'|'llm/stream'|'system-prompt/assemble'|'agent/pre-step')` | 请求中间件，需 `llm:request` |
| `ctx.storage.on('before-append'|'after-load')` / 插件记录 veto | 存储钩子，需 `storage:hooks`；v1 只观测+拒绝 |
| `ctx.ui.slots.register/action` | 插槽，需 `ui:slot`；8 个槽位 |
| `ctx.commands.register` / `ctx.systemPrompt.section/context` | 命令 / 提示词段，通常零改动 |
| `ctx.systemPrompt.register` | 整体提示词角色，需用户选中 |
| `ctx.sessionProjections.register` / `ctx.on` / `ctx.effect` | 常驻 runtime；投影/订阅按会话重放语义改写 |
| `ctx.ui.registerShell` / `registerLoading` | 整壳 / 启动加载页，装好还要用户选中 |
| `ctx.ui.registerRoute/registerPage` + `dsh.client` | 插件自己的 `__ModuleLoader__` 客户端 bundle |
| `ctx.agent.register` | text-only 回合；工具/LLM 流原语 UNSUPPORTED |
| `ctx.storage.register` | 只读存储 v1 |
| `ctx.llm.register` | 配置式或 `stream`；不要填真实密钥 |
| `ctx.capability.register` | 能力；模型用 `capability` 工具按 id 调用 |
| React/DOM/Electron/Qt/PowerShell helper | 只有 Web 半侧可跑；原生 helper 不能执行 |
| `insert:` 组合包 / npm 子插件 | 不展开、不自动安装 |

## 4. 原生权限（当前表）

| 声明 | 含义 | 授予方式 |
|---|---|---|
| `fs:workspace` | 当前工作区读写（canonical 防越界） | 导入即允许 |
| `fs:any` | App 可访问任意路径 | 插件页确认 |
| `net:<host>` / `net:any` | 仅 https，按域名/子域；本机/内网默认拒绝 | 插件页确认 |
| `shell` | 自动选身份：root → Shizuku → App；返回 identity | 插件页确认 |
| `device` / `memory` | 设备信息/操作、记忆库 | 插件页确认 |
| `ui` / `agent` / `storage` / `prompt` / `capability` | 注册对应角色 | 导入即允许，选中才生效 |
| `ui:slot` / `tools:intercept` / `llm:request` / `storage:hooks` | 插槽 / 改工具 / 改请求 / 写钩子 | 插件页确认（restrict/guard 不需要） |
| `notify` | 系统通知 | 插件页确认 + Android 13+ 系统权限 |
| 其它写法 | 未知 | 不授予、不生效 |

## 5. 装测与交付

**Node 路线**：`nodehost_install` 成功后必须等 Ready，`nodehost_plugins` 里模块 `ok=true`、工具列出，再真实调用一次 `node_<name>`；失败日志/隔离步骤见第 1 节。

**原生路线**：`plugin_dev_test` 无错 → 安装（默认停用）→ 启用 → 真实 execute / 页面 / 角色冷启动验证；停用/重载后角色撤下、cleanup 生效。

交付话术（Node）：

> 这个 DSH 插件按原包装进了鲸歌 Node 宿主：`package.json` + `index.js`，工具 `xxx`、命令 `yyy`。导入后启用即可；Node 宿主 v1 没有插件界面，页面/整壳类请走原生插件。装测结果：……，未验：……

交付话术（原生）：

> 这个插件按鲸歌原生格式适配好了：工具 `xxx`、命令 `yyy`、权限 `…`。导入后启用即可；页面/整壳/加载页需要在「插件 → 角色」里选中才生效。与原包相比没搬的部分：……

## 6. 参考

- Node 作者技能：`dsh-node-plugin-authoring`；原生作者技能：`dsh-plugin-authoring`。
- Node 宿主：`app/.../node/{NodePluginHost,NodeHostProcess,NodeHostBridge,NodePluginInstaller,NodeRuntimeInstaller,NodeHostTools}.kt`、`app/src/main/assets/node/runtime.zip` 内 `dsh/host.mjs`；证据 `docs/190-evidence/b-node/`。
- 原生源码：`core/plugins/PluginModule.kt`、`PluginSandbox.kt`、`PluginPermissions.kt`、`PluginServices.kt`、`SurfaceSpec.kt`、`LoadingSpec.kt`、`PluginPackage.kt`、`PluginClientEntry.kt`、`BundlePatch.kt`、`PluginMiddlewareSwitch.kt`、`core/session/SessionWriteHooks.kt`、`core/engine/ToolInputGuard.kt`；App 侧 `PluginDevTools.kt`、`PluginAuditLog.kt`、`BuiltinPlugins.kt`。
- 客户端宿主：`app/src/main/assets/client-host/host.js`；整壳桥：`app/.../ui/surface/WebViewShellScreen.kt`；加载快照/恢复：`app/.../data/LoadingSnapshotStore.kt`、`app/.../ui/boot/`。

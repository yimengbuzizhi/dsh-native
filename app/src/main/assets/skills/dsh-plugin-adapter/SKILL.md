---
name: dsh-plugin-adapter
description: 判断一个 DSH / 社区插件在鲸歌该走哪套宿主：真 DSH / cordis / npm 插件优先装进 Node 宿主（nodehost_install），保留原包、不改成原生；只有用户明确要（或插件本来就是）鲸歌原生 QuickJS 格式时，才按角色改包走 plugin_dev_*。原生链已支持多文件 dsh.package.json（多脚本 + assets + client）与单文件 .js/.yaml/.md/.json 导入，zip/多文件包导入时由用户选归属（原生 / Node）。包含 Node 侧装测/诊断/隔离、宿主环境事实与原生侧适配映射。
whenToUse: 用户拿来一个 DSH 插件（npm 包 / GitHub 仓库 / dist/index.js / src/index.ts / cordis.patch.yml / dsh.package.json 多文件包 / 单个 .js）问「能不能在鲸歌用」「怎么装」「导入/加载报错」「导入时选原生还是 Node」，或提到 cordis、defineTool、@deepseek-ai/dsh-tools、dsh.client、plugin_manager、plugin:// 资源、ctx.data 时加载。
---

# DSH / 社区插件 → 鲸歌：先选宿主，再搬

鲸歌现在有两条插件链，**先判断再动手**：

| 插件是什么 | 走哪条 | 怎么做 |
|---|---|---|
| 真 DSH / cordis 插件（`export function apply(ctx)`、`@deepseek-ai/dsh-tools`、npm 依赖、`cordis.patch.yml`） | **Node 宿主**（首选） | 原包装进 Node 宿主；**不改写成原生插件**。用 `nodehost_install` + `nodehost_*` 装测 |
| 已经是鲸歌原生格式（`package.json` + `apply` + `plugin_dev_*`，或 `dsh.package.json` 多文件包，或用户点名要原生插件） | 原生 QuickJS | 按角色改包，用 `plugin_dev_test/install/enable` |
| 需要页面 / 整壳 / slot / WebView 插件 UI | 原生 QuickJS（Node 宿主不能注册 cordis 页面 API；但带 web client 的 Node 插件可用容器打开 `client.js`） | 按原生 `ui` 角色改，或如实说明 Node 版没有 cordis 界面注册 |
| 只是简单工具/命令/提示词，用户接受换格式 | 原生 QuickJS | 按第 4 节改；先说明这是分叉，不跟上游自动更新 |

**产品纪律（docs/185 D10）**：真 DSH 插件默认**不改写成 Kotlin/QuickJS 原生插件**。能跑就装在 Node 宿主；跑不了先补 Node 环境/依赖，再让 AI 改插件自身的 node 侧适配；最后才说「不支持」。不要为了一个插件在原生里加专属落点。

**0.3.9 两条新事实**（别按旧记忆回答）：

- 原生链不再是「只能单文件入口」：`dsh.package.json` 支持多 `script` + `assets` + `client`，包内相对 `require('./x.js')` / `import`、`ctx.assets`（plugin:// 只读）、`ctx.data`（data:// 读写）都可用；多脚本会由安装器自动生成 `__dsh_multi_entry.js` 引导入口。细节见 `dsh-plugin-authoring` 的「多文件包」节。
- 导入 zip / 多文件包时，界面**一定先弹「归属」确认框**（原生 / Node 可改选）；单文件 `.js/.yaml/.yml/.md/.json` 不经此框，直接按原生声明式/脚本导入。AI 不能替用户点这个框——让用户选，或按清单判据建议一侧。

## 0. 先读包，再下结论

必看：`package.json`（`main`、`dsh.client`、`dsh.bundle.patch`、`dependencies`/`peerDependencies`）、入口 JS、`dist/` 是否有可直接跑的 ESM 构建产物。原生多文件包看 `dsh.package.json`（`contributions` 里有哪些 `script`/`client`，`assets` 指了哪些文件）。

- 入口是 `export const name` / `export function apply(ctx)` → Node 宿主。
- 入口是 `module.exports` / TS 源码 → 先构建成 ESM 再装；Node 宿主用 `import()` 加载。
- 依赖先看运行时：它带完整 dsh 生产树（`@deepseek-ai/*` 及 cordis/cosmokit/zod 等传递依赖，约 200 个顶层包），包名命中就不必随包带；树里没有的第三方依赖才必须打进 `node_modules/`（手机上不能 `npm install`）。
- 有绑定桌面/Electron/原生 helper 的代码 → Node 宿主也跑不了，如实标不支持。

## 0.1 导入入口（0.3.9）：单文件直接装，zip 先选归属

| 用户拿来的东西 | 走哪条 | 必须知道 |
|---|---|---|
| 单个 `.js` / `.mjs` / `.cjs` | 原生（自动生成清单） | 识别 Operit ToolPkg 或 DSH `apply(ctx)` 脚本；id 自动生成 `local.script.<名字>`、version `0.1.0`、**不声明任何权限** → 需要 fs/net/shell 的插件别走单文件，改成 `dsh.package.json` 或 `package.json` + zip |
| 单个 `.yaml` / `.yml` / `.md` / `.json` | 原生声明式 | 只登记贡献物（`skill`/`prompt` 等），本版不执行；不经归属框 |
| zip / 多文件包 | 界面先弹「归属」确认（原生 / Node 可改选） | Node 侧走 `NodePluginInstaller`，原生侧走 `PluginStore`；同 id 已在另一侧 = 迁移：先装新侧再删旧侧，旧配置/开关重置，同一个 id 不会双装。zip 里连 `package.json`/`manifest.json`/`dsh.package.json` 都读不出时不出框，直接按原生导入并报错。 |
| 目录 | `plugin_dev_test/install`（原生）或 `nodehost_install`（Node） | 目录导入由宿主自己打包，手机不需要 `zip` |

判据（用户 2026-11-23 拍板）：`dependencies`/`peerDependencies`/`optionalDependencies` 里出现 `@deepseek-ai/*`，或清单有 `exports["./client"]` / `dsh.client` → 默认 Node；其余默认原生。判错可以改，让用户在确认框里改选。单个 `.js` 没有归属框，一律按原生跑——真 DSH/cordis 插件别用单文件导入（`@deepseek-ai/dsh-tools`、`inject`、`ctx.plugin` 这些在原生沙箱里跑不起来），直接走第 1 节。

## 1. Node 宿主路线（真 DSH 插件首选）

**包形状**：

```text
my-plugin/
  package.json      # name / version / main；main 指向 ESM 入口
  index.js          # export const name；export function apply(ctx, config)
  node_modules/     # 可选：插件自己的依赖（随包带）
```

**装法**：把目录/zip 放在工作区 → `nodehost_install {path:'my-plugin'}`（目录会自动打成单根 zip，手机不需要 `zip` 命令）；`nodehost_uninstall {id}` 卸载。宿主开着会自动重启；关着只落盘。`nodehost_install` 只收目录或 `.zip`，路径必须在工作区或 `files/node` 下（`NodeHostTools.withinAllowedRoots`）。

**宿主环境事实（0.3.9）**：

- PATH 是系统目录优先（`/system/bin`、`/system/xbin`、`/product/bin`），`files/toolchain/*` 只垫底；`NODE_PATH` 指向运行时 `node_modules`。
- CA 只用系统只读目录（`/apex/com.android.conscrypt/cacerts` 或 `/system/etc/security/cacerts`），不再把 `files/toolchain/cacert.pem` 当信任根——插件改不了宿主的 TLS 信任根。
- 重装同一个插件：只有**同一模块路径**（同一 `./plugins/<目录>/<入口>`）才继承旧 `config`；跨目录同 id 会移除旧清单条目但**不继承配置、也不删旧目录**（别答应「改名重装还保留密钥」）。

**可用 `ctx` 能力（v1）**：

- `ctx.tools.register(defineTool({name,description,parameters,output,execute}))` → 原生侧变成 `node_<name>`，Ready 后进模型工具表；Ready 时从 `hello` 握手取工具表，`nodehost_repair reload` 会再拉一次 manifest，动态注册的工具也能刷新出来。
- `ctx.systemPrompt.section/context` → 原生宿主 Ready 后拉取合并。
- `ctx.commands.register({name,description,input,handler})` → `/name`。
- `ctx.llm.register({id,label,protocol,baseUrl,model,apiKey?})` → 声明式供应商，原生走自己的 HTTP 通道；只有 `stream()` 回调的供应商会如实标不可用。
- `ctx.provide/inject` 等 cordis 依赖只在 Node 宿主内部生效。
- 带 web client（`exports["./client"]` 或 `dsh.client`）的 Node 插件，可在插件页用通用容器打开它的 `client.js`（`hasClient` 记录在 `node-host-plugins.json`）；这不是 cordis 页面 API，容器仍是原生侧那个 WebView 宿主。
- **没有** `ctx.ui` 的页面/整壳/slot/WebView 注册、npm 安装、`dsh web`、turn engine；不要承诺。

**装测**：

1. `nodehost_doctor` 先看 runtime/ABI/清单/坏插件；OFF/FAILED 时按 findings 的 fix 处理。
2. `nodehost_status` / `nodehost_plugins` 看 enabled/state/pid/tool_count/已加载模块与错误。
3. `nodehost_install` → 等 Ready → `nodehost_plugins` 确认 `ok=true`、工具/命令/供应商列出 → 真实调用一次 `node_<tool>`。
4. 失败看 `nodehost_logs {source:'host'}`（脱敏）和 `nodehost_plugins.error`；修工作区包后重装同 id。
5. 宿主坏了：`nodehost_autofix`（先 `dry_run:true` 看计划）或 `nodehost_repair` 单步。`reinstall-runtime` 只换运行时，插件/配置不动。
6. 插件连续 2 轮加载失败会被自动隔离（卸载 + 通知 + 审计）；插件把宿主进程搞崩时无法归因到单个插件，只能看 crash loop 日志逐个排查。
7. 宿主 control 有 3 次/5 分钟退避；写操作走审批 + `node-audit.jsonl`。
8. **v1 不允许 AI 改设备上已安装插件的 JS**：改工作区源包再装，或隔离后让用户修。插件 config 可以在插件页按 JSON 编辑（写入 `node-host-plugins.json` 并重启宿主）；AI 侧没有配置工具，只能请用户改。

细节以 `dsh-node-plugin-authoring` 为准。

## 2. 旧结论已过期（别按记忆照抄）

- ❌「DSH 插件只能改写成鲸歌原生插件」→ **错**。真 DSH 插件优先原样装 Node 宿主；改原生是用户明确选择的分叉。
- ❌「鲸歌只保留模型工具这一半」→ 原生链现在工具、命令、提示词、页面、加载页、整壳、回合、存储、模型、能力、投影都能接；Node 链有工具/提示词/命令/模型供应商。
- ❌「任何接管 DSH 根界面的 root slot 一律拒绝」→ 原生可 `registerShell`；是否切换由用户在「插件 → 角色 → 界面」决定。
- ❌「`ctx.on` / `ctx.llm` / `ctx.sessionProjections` / `ctx.ui.register*` 只登记不回调」→ 原生链已实现；Node 链的 `ctx` 是真 cordis。
- ❌「`shell` 权限每次调用都要用户确认」→ 按审批策略：`ask` 询问、`auto` 允许；授权是插件页逐项授予。
- ❌「AI 不能改插件授权/配置/角色」→ `plugin_dev_grant/revoke/config/role` 一直可用（docs/190-F 已永久删除「AI 全控制」总开关），走工具审批 + `plugin-audit.jsonl`；系统权限（通知/Shizuku/无障碍/悬浮窗）仍只由系统弹窗授予。
- ❌「插件 UI 不执行，只能做原生替身」→ 原生客户端窗口与整壳是一等能力；Node 宿主不能注册 cordis 页面 API，但带 web client 的 Node 插件能用同一个容器打开 `client.js`，别把两者混为一谈。
- ❌「原生插件必须单文件、相对 `require` 不解析」→ 0.3.9 起支持 `dsh.package.json` 多文件包、包内相对 `require('./x.js')`/`import`、`ctx.assets`/`ctx.data`、fs 二进制读写与 `node:path`。

仍然成立的硬边界（原生链）：无 npm 安装、无 DEX/APK 动态加载、无完整 Cordis 服务树、无桌面 helper、`ctx.ui.registerView` 未实现、`ctx.agent` 的 `tools.invoke`/`llm.stream`/`storage.append` 返回 UNSUPPORTED、storage v1 只读。包内相对模块**已支持**（`module.resolve` + `__dshModuleCache`），别再写「无相对 import/require 解析」。

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

多文件包（0.3.9）用 `dsh.package.json` 代替 `package.json`：`contributions` 里可以有多个 `script`（各有 `entry`）、`assets` 素材和 `client` 半侧；安装器会自动生成 `__dsh_multi_entry.js` 把它们串起来，`format` 写成 `whale-lite` 即可。完整示例、上限与校验文案见 `dsh-plugin-authoring` 的「多文件包（dsh.package.json，0.3.9）」。注意：`dsh.package.json` 里其它 kind（`skill`/`prompt`/`page`/`theme`/`macro`/`mcp`/`tool.http`/`preset`/`asset`）本版**只在插件详情里列成「贡献物」**，真正进运行时的只有 `script` 和 `client`——别告诉用户「装了主题就换肤了」。

装测工具：`plugin_dev_list` → `plugin_dev_test` → `plugin_dev_install`（新装默认停用）→ `plugin_dev_enable` → 真实调用/页面验证；改包用 `plugin_dev_reload`，删除先停用再 `plugin_dev_delete`。没有工具时交付 zip 到「包管理 → 插件 → 导入 zip」（会先让用户选原生/Node）。

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
| `shell` | 以 App 身份跑 `/system/bin/sh -c`（无自动 root/Shizuku 提权）；模型侧 `bash` 工具另有 `identity` 参数，插件 `ctx.shell.exec` 没有 | 插件页确认 |
| `device` / `memory` | 设备信息/操作、记忆库 | 插件页确认 |
| `ui` / `agent` / `storage` / `prompt` / `capability` | 注册对应角色 | 导入即允许，选中才生效 |
| `ui:slot` / `tools:intercept` / `llm:request` / `storage:hooks` | 插槽 / 改工具 / 改请求 / 写钩子 | 插件页确认（restrict/guard 不需要） |
| `notify` | 系统通知 | 插件页确认 + Android 13+ 系统权限 |
| （无需声明）`plugin://` / `data://` | 插件自带资源只读 / 插件私有数据目录读写 | 不需要 `fs:any`，路径由 `PluginPathGuard` 限制在包内/数据目录内 |
| 其它写法 | 未知 | 不授予、不生效 |

## 5. 装测与交付

**Node 路线**：`nodehost_install` 成功后必须等 Ready，`nodehost_plugins` 里模块 `ok=true`、工具列出，再真实调用一次 `node_<name>`；失败日志/隔离步骤见第 1 节。

**原生路线**：`plugin_dev_test` 无错 → 安装（默认停用）→ 启用 → 真实 execute / 页面 / 角色冷启动验证；停用/重载后角色撤下、cleanup 生效。

交付话术（Node）：

> 这个 DSH 插件按原包装进了鲸歌 Node 宿主：`package.json` + `index.js`，工具 `xxx`、命令 `yyy`。导入后启用即可；Node 宿主不能注册 cordis 页面/整壳，要界面就改原生插件（带 `client.js` 的 Node 包可以用插件页「打开界面」在容器里跑）。装测结果：……，未验：……

交付话术（原生）：

> 这个插件按鲸歌原生格式适配好了：工具 `xxx`、命令 `yyy`、权限 `…`。导入后启用即可；页面/整壳/加载页需要在「插件 → 角色」里选中才生效。与原包相比没搬的部分：……

导入相关要说清的一句（用户自己点确认框时）：

> 这个包我识别为「原生 / Node」（判据：……）；zip 导入会弹归属确认，你选哪一侧就装到哪一侧。同一个 id 装在另一侧会迁移（旧侧删除、配置重置）。

## 6. 参考

- Node 作者技能：`dsh-node-plugin-authoring`；原生作者技能：`dsh-plugin-authoring`。
- Node 宿主：`app/.../node/{NodePluginHost,NodeHostProcess,NodeHostBridge,NodePluginInstaller,NodeRuntimeInstaller,NodeHostTools}.kt`、`app/src/main/assets/node/runtime.zip` 内 `dsh/host.mjs`；证据 `docs/190-evidence/b-node/`。
- 原生源码：`core/plugins/PackageIr.kt`（`dsh.package.json` IR / 上限 / `PackageImporter`）、`PluginModule.kt`、`PluginSandbox.kt`、`PluginPermissions.kt`、`PluginServices.kt`、`SurfaceSpec.kt`、`LoadingSpec.kt`、`PluginPackage.kt`、`PluginClientEntry.kt`、`BundlePatch.kt`、`PluginMiddlewareSwitch.kt`、`core/session/SessionWriteHooks.kt`、`core/engine/ToolInputGuard.kt`；App 侧 `PluginDevTools.kt`、`PluginAuditLog.kt`、`BuiltinPlugins.kt`、`PluginStore.kt`、`chat/AppViewModel.kt`（`beginPluginImport`/`confirmPluginImport`）、`ui/plugins/PluginsScreen.kt`（归属确认框）。
- 客户端宿主：`app/src/main/assets/client-host/host.js`；整壳桥：`app/.../ui/surface/WebViewShellScreen.kt`；加载快照/恢复：`app/.../data/LoadingSnapshotStore.kt`、`app/.../ui/boot/`。

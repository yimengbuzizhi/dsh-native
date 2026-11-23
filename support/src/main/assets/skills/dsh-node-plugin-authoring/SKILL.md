---
name: dsh-node-plugin-authoring
description: 为鲸歌 Node 宿主（真 Node + 真 cordis）编写、改、打包、安装、诊断真实 DSH 插件：工具、提示词段/上下文、斜杠命令、模型供应商，以及用 nodehost_* 工具看状态/日志/装插件/修宿主。真 DSH 插件优先用本技能；改 QuickJS 原生插件用 dsh-plugin-authoring。
whenToUse: 用户要求「做一个 DSH / cordis / Node 插件」「把 npm 上的 DSH 插件装进鲸歌」「Node 插件装不上 / 加载失败 / 宿主坏了」「用 nodehost_* 管理 Node 宿主」时加载。
---

# 鲸歌 Node 宿主：真 DSH / cordis 插件开发与装测

鲸歌有**两套插件宿主**。本技能只管 **Node 宿主**（真 Node 进程 + 真 cordis + 真 dsh 包）；另一套是冷启动内的 QuickJS 原生插件（`plugin_dev_*`、`ctx.ui.registerShell` 等），见 `dsh-plugin-authoring`。

**先分清目标**：用户拿来一个 npm / GitHub 上的真实 DSH 插件，或要写 `export function apply(ctx)` 的 cordis 插件 → 走 Node 宿主，**不要**改写成 QuickJS 原生插件。只有当用户明确要「鲸歌原生插件」或插件只做简单工具/界面且愿意换格式时，才看原生技能。

## Node 宿主是什么（先看事实，不要猜）

- 独立 Node 进程：`files/node/runtime/<版本>/dsh/host.mjs`，里面是真 `@deepseek-ai/cordis` + dsh 的 system-prompt / tools / projections 服务，另有鲸歌补的 `llm` / `commands` 服务。
- 插件按 `files/node/data/node-host-plugins.json` 逐个 `import()` + `ctx.plugin(mod, config)`；模块路径 `./plugins/<目录>/<入口>` 相对 `data/`，裸包名（如 `@deepseek-ai/dsh-tool-todo`）从运行时解析。
- 宿主 Ready 后，插件注册的工具会以 `node_<原名>` 并进模型工具表；**没 Ready 就没有这些工具**。App 其余功能不依赖宿主。
- 只有 **arm64** 打包了 `libnode.so`；x86_64 模拟器上宿主必然 FAILED（`nodehost_doctor` 会说清 ABI），不要在那种设备上反复重试。
- v1 **没有** Node 侧界面/容器桥：插件不能注册页面、整壳、slot、WebView。要界面就走原生插件，或让用户改用支持界面的原生插件。Node 插件交付的是工具/提示词/命令/模型供应商这些数据面能力。

## 包形状

```text
my-node-plugin/
  package.json      # name / version / main 必须真实
  index.js          # ESM：export const name + export function apply(ctx, config)
  node_modules/     # 可选：插件自己的依赖（不打 npm install，必须随包带）
```

```json
{
  "name": "my-node-plugin",
  "version": "0.1.0",
  "main": "index.js"
}
```

```js
import { defineTool } from '@deepseek-ai/dsh-tools'

export const name = 'my-node-plugin'
export const inject = ['tools', 'systemPrompt', 'llm', 'commands']

export function apply(ctx, config) {
  ctx.tools.register(defineTool({
    name: 'my_echo',
    description: 'Echo the given text.',
    parameters: {
      text: { type: 'string', required: true, description: 'Text to echo.' },
    },
    output: {
      schema: {
        type: 'object',
        additionalProperties: false,
        properties: { text: { type: 'string', required: true } },
      },
      render: (_args, value) => [{ type: 'text', text: value.text }],
    },
    execute(args) {
      return { text: String(args?.text ?? '') }
    },
  }))
}
```

要点：

- 入口必须是 **ESM**（`export`），宿主用 `import()` 加载；不要交 `module.exports = ...` 或 TS 源码。
- 插件名/工具名都要稳定、唯一；和宿主已有工具重名会被后注册覆盖或报错。
- 依赖：Android 上没有 `npm install`。插件 zip 里要么自带 `node_modules/`，要么只用运行时已有的 `@deepseek-ai/*` / cordis / zod 等包。不要把整个桌面 dsh 仓库塞进来。
- `apply` 只做登记；副作用放 `execute`，否则每次重载/启动都会执行。
- 配置：清单条目里的 `config` 会原样传给 `apply(ctx, config)`；重装同 id 默认保留旧 config。
- zip 限额（`NodePluginInstaller`）：zip ≤64MB、单文件 ≤32MB、解压后 ≤256MB、条目 ≤20000、压缩比 ≤200×。`nodehost_install` 也接受**目录**，宿主会按单根目录打包，所以手机上不需要 `zip` 命令。

## 宿主提供的 `ctx` 能力（v1）

| 能力 | 用法 | 原生侧表现 |
|---|---|---|
| 工具 | `ctx.tools.register(defineTool({name,description,parameters,output,execute}))` | 注册成 `node_<name>`，Ready 后进模型工具表；审批/审计走原生工具链 |
| 提示词段 | `ctx.systemPrompt.section({name,order,text})`；`ctx.systemPrompt.context(...)` 同形 | 宿主 Ready 后原生拉取；与原生段同一张表按 order/name 合并 |
| 斜杠命令 | `ctx.commands.register({name,description,input,handler})`；handler 返回 `{kind:'success'\|'error', text}` | 输入框 `/name` 命中后转发；结果进命令提示条，不自动进模型历史 |
| 模型供应商 | `ctx.llm.register({id,label,protocol:'openai'\|'anthropic',baseUrl,model,apiKey?})` | 声明式：原生走自己的 HTTP 通道；不给 baseUrl/key 则跟随宿主设置 |
| 模型（仅回调） | `ctx.llm.register({id,label,stream(request,emit){...}})` | 原生桥调不到 JS 回调，会**如实标不可用**；不要宣称能用 |
| cordis 依赖 | `ctx.provide(name, value)` / `inject = [...]` | 只在 Node 宿主内部生效 |

不支持（别写进交付说明）：页面/整壳/slot/WebView、npm 安装、动态 `require` 相对文件、桌面 Electron/helper、`dsh web`、dsh turn engine / agent loop。**不要为了一个插件在原生里加专属落点。**

## 装测流程（先 doctor，再动手）

1. **看现状**：`nodehost_status` 看 enabled/state/pid/tool_count/last_error；`nodehost_doctor` 看 runtime/ABI/清单/坏插件；`nodehost_plugins` 看清单与已加载模块。
2. 宿主 OFF/FAILED 时先别装：`nodehost_doctor` 的 findings 有 fix 才动手。`nodehost_control start` 会打开开关并启动；写操作走全局审批并写审计（`node-audit.jsonl`）。
3. 在工作区写插件目录（`package.json` + `index.js` + 可选 `node_modules`），不要直接改设备上已安装插件的 JS。
4. `nodehost_install {path:"my-node-plugin"}`（目录或 zip）；宿主开着会自动重启，关着则只落盘。
5. 等 Ready 后 `nodehost_status` 确认 `tool_count` / `plugins` 变化；`nodehost_plugins` 确认模块 `ok=true`；再真实调用一次 `node_<工具名>`（会走原生审批）。
6. 失败就看 `nodehost_logs {source:'host', lines:60}`（返回前脱敏）和 `nodehost_plugins` 的 `error`；修好工作区包后重装同 id，或 `nodehost_repair quarantine` 隔离坏插件。
7. 宿主自己坏了：`nodehost_autofix {dry_run:true}` 先看计划，再 `nodehost_autofix`；单步可用 `nodehost_repair {action:'reload'|'restart'|'reinstall-runtime'|'quarantine'|'clear-cache'}`。`reinstall-runtime` 只换运行时，插件与配置不动。

`nodehost_control` 有 **3 次/5 分钟**退避；宿主自己连续崩 3 次进 FAILED 且下次开机跳过自动启动（横幅说明原因）。`nodehost_install/uninstall/control/repair/autofix` 都是写类，会弹审批并审计；只读的 status/plugins/logs/doctor 不弹。

## 失败与隔离（v1 边界）

- 插件加载失败（`hello.plugins[].ok=false`）连续 **2 轮** → 原生自动隔离：卸载该插件 + 通知 + 审计，其余插件照常。
- 如果插件把整个 Node 进程搞崩，`hello` 到不了，**无法自动归因到单个插件**；只会看到宿主 crash loop。先看 `nodehost_logs` 最后几行，再逐个禁用/移出可疑插件。
- **v1 不允许 AI 改设备上已安装插件的 JS**。要修就改工作区里的源包，再 `nodehost_install` 覆盖；或隔离后让用户装修复版。
- `nodehost_repair reload` 只重拉注册/提示词段；改了插件代码用 `restart` 或重装。

## 交付前检查

- [ ] `package.json` 的 name/main 与实际入口一致；入口是 ESM；没有 TS/裸相对 import。
- [ ] 依赖要么随包带 `node_modules`，要么只用运行时 `@deepseek-ai/*`；没有真实密钥硬编码。
- [ ] `nodehost_install` 成功；`nodehost_status` Ready；`nodehost_plugins` 里该模块 `ok=true`、工具/命令/供应商列出。
- [ ] 至少真实调用一次工具（`node_<name>`）；错误参数也跑一次；日志无凭据。
- [ ] 失败场景：坏包/加载失败时 `nodehost_logs` 有可读错误；隔离后其余插件仍可用。
- [ ] 报告写清：插件 id/版本、装了哪些能力、哪些没验（尤其需要重启/真机 READY 的部分）、已知限制。

## 参考（仓库根）

- 宿主实现：`app/.../node/NodePluginHost.kt`、`NodeHostProcess.kt`、`NodeHostBridge.kt`、`NodePluginInstaller.kt`、`NodeRuntimeInstaller.kt`；运行时 `app/src/main/assets/node/runtime.zip` 内 `dsh/host.mjs`。
- 管理工具：`app/.../node/NodeHostTools.kt`、`builtin.node.host` 组、`docs/190-evidence/b-node/`。
- 原生 QuickJS 插件：`dsh-plugin-authoring`；把老 DSH 插件搬到原生格式只在用户明确要求时做，见 `dsh-plugin-adapter`。

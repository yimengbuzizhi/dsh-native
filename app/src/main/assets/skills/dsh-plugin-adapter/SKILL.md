---
name: dsh-plugin-adapter
description: 把 DSH（DeepSeek Harness）社区插件改造成鲸歌可直接导入的插件包。用于用户拿来一个 DSH 插件（npm 包 / GitHub 仓库 / dist/index.js / src/index.ts），需要判断能不能直接导入、不能时怎么改、改完怎么打包和验证。
whenToUse: 用户提到「DSH 插件」「社区插件」「把这个插件改成鲸歌能用」「插件导入失败」「defineTool / cordis / dsh-tools」时加载。
---

# DSH 插件 → 鲸歌插件适配

目标：**尽量不重写**，把 DSH 工具插件改成鲸歌 `包管理 → 插件 → 导入 zip` 能直接跑的包。
鲸歌的插件运行时是 QuickJS，没有 Node、没有 npm、没有 DOM；只保留「模型可调用的工具」这一半。

---

## 0. 先判断：这个 DSH 插件属于哪一类

看插件仓库/包里的 `package.json`：

| 特征 | 类型 | 能不能搬 |
|---|---|---|
| `main` 指向 `dist/index.js`，代码里只有 `export name/inject/apply` + `ctx.tools.register(defineTool(...))` | 纯工具插件 | **大概率能直接导入**，先试 |
| 依赖 `@deepseek-ai/dsh-tools`、`@deepseek-ai/schemastery`、`node:fs`、`node:child_process` | 工具插件 | 鲸歌已 shim 这些模块，通常能跑 |
| 有 `client.js` / React / `ctx.ui.registerView` / `cordis.patch.yml` 里的 UI 行 | 界面插件 | Host 侧工具/命令/提示词照常接入；Web UI 不执行。界面本身是核心价值时走「原生替身」（3.9） |
| 依赖第三方 npm 包（axios、lodash、zod 之外的东西） | 工具插件 | 需要把依赖内联/改写，工作量看依赖大小 |
| 是 MCP server / 纯 prompt / 主题包 | 不是 DSH 工具插件 | 不走这个流程 |

**第一步永远是：把插件仓库拉下来，读 `package.json` 和 `main` 指向的 JS。**

---

## 1. 鲸歌插件包长什么样

一个 zip，根目录（或单独一层目录）里：

```
my-plugin/
  package.json      # 必需
  index.js          # 入口，默认 main
  ...               # 其他文件（不能超过 4MB/文件、32MB 总量）
```

`package.json` 最小示例：

```json
{
  "name": "hello-dsh",
  "id": "hello-dsh",
  "version": "0.1.0",
  "description": "从 DSH 插件 hello 适配",
  "main": "index.js",
  "permissions": []
}
```

- `name` 必填；`id` 可省略（默认 = name），只允许字母数字 `@ . _ / -`。
- 入口是 **ESM 或 CJS 模块**，必须导出 `apply(ctx, config)`。
- `permissions` 是鲸歌的能力声明，不是 DSH 的：
  - `fs:workspace`：读写当前工作区；
  - `fs:any`：读写任意路径（危险，尽量别用）；
  - `net:api.example.com`：只允许 https 访问这个域名/子域名；
  - `shell`：执行 shell（每次调用要用户确认）。
  没声明的能力，运行时直接拒绝。

入口形状：

```js
export const name = 'hello'
export function apply(ctx) {
  ctx.tools.register({
    name: 'hello',
    description: 'Greet a person by name.',
    parameters: {
      type: 'object',
      properties: { name: { type: 'string' } },
      required: ['name']
    },
    async execute(args) {
      return `Hello, ${args.name}!`
    }
  })
}
```

`execute` 返回字符串最稳；返回对象时鲸歌会 JSON 序列化后给模型。

---

## 2. 直接导入的兼容层（先试，不要急着改）

鲸歌运行时已经提供：

- `defineTool(def)`：直接返回 def，并把 DSH 的 `parameters` DSL 转成 JSON Schema；
- `require('@deepseek-ai/dsh-tools')` / `import { defineTool } from '@deepseek-ai/dsh-tools'`；
- `require('@deepseek-ai/schemastery')` / `import z from '@deepseek-ai/schemastery'`（够用的 schemastery 子集）；
- `node:fs` / `fs` / `node:fs/promises` → 沙箱文件系统；
- `node:child_process` → `shell.exec`（需要 `shell` 权限）；
- `node:path`、`node:os`、全局 `fetch`、`console`、`setTimeout`。

所以对纯工具插件：

1. 拿它**构建后的** `dist/index.js`（不要直接拿 TS 源文件）；
2. 建上面的 `package.json`，`main` 指向这个 JS；
3. 把 `dist/index.js` 一起打进 zip；
4. 导入 → 启用 → 看工具数和参数 schema；
5. 用「包」引用或直接让模型调用工具，验证 execute。

如果导入后报 `Cannot find module`、`xxx is not a function`、`SyntaxError`，再进入第 3 节改。

---

## 3. 常见改造点

### 3.1 `parameters`：DSH DSL 和 JSON Schema 都认

DSH 写法（不用改，运行时会转）：

```js
parameters: {
  city: { type: 'string', required: true, description: '城市名' },
  days: { type: 'number' }
}
```

等价的 JSON Schema（想手改时用）：

```js
parameters: {
  type: 'object',
  properties: {
    city: { type: 'string', description: '城市名' },
    days: { type: 'number' }
  },
  required: ['city']
}
```

规则：
- `required: true` 在参数项上 → 收进根 `required` 数组；
- `enum`、`default`、`description`、`items` 原样保留；
- 嵌套对象用 `type:'object', properties:{...}`；
- 鲸歌只认 JSON Schema；认不出的参数会导致工具不注入（包管理里会显示「参数认不出」）。

### 3.2 `output.schema` / `output.render`：删掉

鲸歌没有 canonical value / render 管线。DSH 的：

```js
output: {
  schema: { type: 'string' },
  render: (_args, value) => [{ type: 'text', text: value }]
},
async execute(args) { return 'text' }
```

改成：

```js
async execute(args) { return 'text' }
```

如果 `execute` 返回对象而 `render` 负责拼文案，把 render 的逻辑搬进 execute，直接返回最终字符串。

### 3.3 `exec` 第二参数：可以忽略

DSH 的 `execute(args, exec)` 里 `exec.signal`、`exec.agent` 在鲸歌没有。
删掉对 `exec` 的使用；长任务用 `await` 完成即可（鲸歌有超时中断）。

### 3.4 import / require 替换

| DSH 里的 | 鲸歌里 |
|---|---|
| `import { defineTool } from '@deepseek-ai/dsh-tools'` | 保留（已 shim） |
| `import z from '@deepseek-ai/schemastery'` | 保留（已 shim，子集） |
| `import { readFile } from 'node:fs/promises'` | 保留（映射到沙箱 fs） |
| `import { execSync } from 'node:child_process'` | 保留（映射到 shell.exec，要 `shell` 权限） |
| `import axios from 'axios'` | 改成 `fetch`，或把请求代码内联 |
| `import _ from 'lodash'` | 用原生 JS 重写那几行 |
| `import React ...` | **不支持**，这是 UI 半侧 |

`fetch` 只允许 https，且域名必须在 `permissions` 里声明 `net:<host>`。

### 3.5 Config

DSH 的：

```js
export const Config = z.object({
  greeting: z.string().default('Hello')
})
export function apply(ctx, config) { ... config.greeting ... }
```

鲸歌支持同样的 schemastery 子集；`Config` 会变成插件配置页，默认值由宿主合并后传给 `apply(ctx, config)`。
如果 `Config` 用了鲸歌不支持的 schemastery 高级类型，退化成普通对象默认值：

```js
export function apply(ctx, config) {
  const greeting = (config && config.greeting) || 'Hello'
}
```

### 3.6 多文件 / 相对 import

鲸歌**不解析** `import './foo.js'` 这种相对模块。必须：
- 用打包器把插件打成单文件；或
- 手动把被依赖的代码内联进入口文件。

### 3.7 提示词 / 命令 / 通知（Host 侧，已支持）

DSH 插件常注册的不只是工具，鲸歌 M57/M58 已接：

| DSH 写法 | 鲸歌行为 |
|---|---|
| `ctx.systemPrompt.section({name, order, text})` | 持久化，每次请求按 order/name 拼到 system 末尾；`complete:true` 不启用 |
| `ctx.systemPrompt.context({...})` | 与 section 同一条管线（按静态文本注入） |
| `ctx.commands.register({name, description, handler})` | 出现在 `/help`「插件命令」；输入 `/name 参数` 由 `PluginModule.commandScript` 执行 handler，结果显示在命令提示条，不进模型历史 |
| `ctx.whale.notify(...)` / 全局 `new Notification(...)` | 转 Android 通知（通道 `dsh_plugin_notify`）；试运行不弹 |
| `ctx.on(...)` / `ctx.llm.register` / `ctx.sessionProjections.register` / `ctx.ui.register*` | 记入「用到但鲸歌未支持的能力」，插件详情警示；常驻 hook 还没做，不会真的回调 |

命令 handler 返回值：字符串 → 直接显示；`{kind:'success', text}` 取 text；`{kind:'error', text}` 显示失败；Promise 会 await。

### 3.8 组合包：cordis.patch.yml

`package.json` 的 `dsh.bundle.patch` 目前只是**登记路径**，鲸歌还没展开 `insert:` 行。
组合包暂时当普通插件导入（入口 main），子插件不会自动装；交付说明要写清楚。

### 3.9 客户端 UI（dsh.client）

- `dsh.client` 表示插件带浏览器半侧，鲸歌用**通用客户端插件宿主**（M70）跑它：
  插件详情 →「打开插件窗口」，宿主页加载插件安装目录里的 `exports["./client"]`，
  提供 `__ModuleLoader__`、React 18、`ctx.get('sessions')`、`ctx.effect/on/slots` 的最小面。
- **不要再按插件 id 写死原生窗口**（M59/M62/M64/M65/M66 的老做法已全部删除）：
  用户要的是插件自己的界面，不是鲸歌重画一个。
- 跑不起来时先看宿主兼容层（`assets/client-host/`）缺什么能力，补宿主，不改插件；
  插件依赖的 Host 侧 Node 接口（`/dsh-whale/*`、`/api/ventus-whale/*` 等）由宿主适配层提供。
- 桌面专属原生 Helper（Electron / Qt / PowerShell）Android 不能执行：只跑 Web 半侧，
  跑不了就如实标「Android 不支持」，不许拿别的窗口冒充。
- 任何要求接管 DSH 根界面的 root slot：明确拒绝。

---

## 4. 打包与导入

1. 新建目录 `my-plugin/`，放 `package.json` + `index.js`（+ 其他运行时文件）；
2. 压缩成 zip（根目录直接是 `package.json`，或只有一层 `my-plugin/`，两种都认）；
3. 鲸歌：设置 → 包管理 → 插件 → 右下角 `+` → 选 zip；
4. 启用开关；看行内「N 个工具 / 已接入 N」；
5. 点行看详情：工具名、参数 schema、试运行报错；
6. 用输入条「包」引用该插件，或直接对话让模型调用。

包限制：单文件 ≤ 4MB、解压总量 ≤ 32MB、最多 2000 个文件、不能有绝对路径/`..`。

---

## 5. 验证清单（交付前逐条过）

- [ ] `package.json` 是合法 JSON，`main` 指向的文件在 zip 里；
- [ ] 入口 export 了 `apply`（ESM `export function apply` 或 CJS `exports.apply`）；
- [ ] 没有相对 import / 第三方 npm 依赖；
- [ ] `parameters` 是 JSON Schema 或 DSH DSL（能被转换）；
- [ ] `execute` 返回字符串/可 JSON 序列化的值，不返回内容块；
- [ ] 网络域名写进 `permissions` 的 `net:`；
- [ ] 文件读写声明 `fs:workspace`，shell 声明 `shell`；
- [ ] 导入后工具数 > 0，详情里没有「参数认不出」；
- [ ] 命令：`/help` 里能看到，执行一次结果正确；
- [ ] 提示词段：详情能看到 name/order，`--es dsh_dump_request` 的 system 末尾有；
- [ ] 通知：工具调用时 Android 通知出现；
- [ ] 界面插件：先判断「工具半侧可搬」还是「需要原生替身」，再决定交付形态；
- [ ] 真机调用一次工具，结果正确。

---

## 6. 给用户的交付话术

> 这个 DSH 插件是纯工具插件，已按鲸歌格式改好：`package.json` + `index.js`，工具 `xxx`，权限 `net:...`。
> 直接导入 zip 即可；UI 部分（如果有）没有搬，只保留模型工具。

如果只能部分适配，明确写：
- 哪些工具搬了；
- 哪些依赖/UI 没搬；
- 为什么（npm 包 / DOM / 长驻进程 / 需要 Node API）。

---

## 7. 参考

- DSH 官方工具规范：`docs/cookbook/adding-a-tool.md`
- DSH 最小插件教程：`docs/user/develop/basic/tool.md`
- 社区参考实现：`codeasier/dsh-plugins` 的 `packages/dsh-plugin-hello`
- 鲸歌插件运行时：`core/plugins/PluginModule.kt`（`defineTool` / `__require` shim）、`PluginSandbox.kt`（fs/net/shell 权限）

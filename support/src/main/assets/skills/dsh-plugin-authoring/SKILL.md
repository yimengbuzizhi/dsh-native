---
name: dsh-plugin-authoring
description: 为鲸歌 Android 原生版编写、适配、试运行、安装、启停和排查 QuickJS JS 插件；覆盖工具、工具/请求/存储中间件与事件钩子、UI 插槽、静态启动加载页、页面、Surface/WebView 整壳、回合、只读存储、提示词、模型供应商、能力提供方及事件投影。真 DSH/cordis/Node 插件见 dsh-node-plugin-authoring，不要用本技能改写成原生。
whenToUse: 用户要求「写鲸歌原生插件」「AI 做插件并装测」「让 AI 授予/撤销插件权限、改配置、选角色」「修改鲸歌界面/壳」「适配社区插件到原生格式」「插件开发、安装或加载报错」时加载；真 DSH/npm/cordis 插件先看 dsh-node-plugin-authoring。
---

# 鲸歌原生插件开发与装测

本技能针对鲸歌 Android 原生运行时（冷启动内的 QuickJS 宿主）。以当前工具 schema 和宿主返回值为准；桌面 DSH 的 npm 安装、Cordis 全量服务、Electron、DEX/APK 动态加载不适用于这条 JS 插件链。

**先分清宿主**：真 DSH / cordis 插件（`@deepseek-ai/dsh-tools`、npm 依赖、`export function apply(ctx)`）优先装进 **Node 宿主**，用 `nodehost_*` 工具，见 `dsh-node-plugin-authoring`；本技能只处理鲸歌原生格式，或用户明确要求把某个插件改写成原生格式的分叉。把已有 DSH/社区插件迁到原生角色，见 `dsh-plugin-adapter`；与本技能冲突的旧能力说明（例如「只支持工具半侧」「root slot 一律拒绝」）不要照搬。

## 先做出能检查的包，再安装验证

1. 用 `plugin_dev_list` 看已有插件、工具/壳登记和权限，确认目标与是否升级同 id 包。按需读取目标源码或插件详情中的公开能力信息；不要读取凭据来凑示例。
2. 用 `plugin_dev_scaffold` 或工作区文件工具生成包。只实现用户需要的角色，声明实际使用的权限。工具的具体参数先看本次暴露的 schema，不猜参数名。
3. 用 `plugin_dev_test` 对包试运行，检查返回的错误、tools、shells、loadings、unsupported、missingServices；参数 schema 与其它角色登记再核对源码和插件详情，不假设工具返回所有内部字段。试运行会执行模块与 `apply`，不是纯语法检查；也不等于工具 execute、页面交互或外部 API 已验收。
4. 修完后用 `plugin_dev_install` 安装（**导入默认停用**，新包不会自动进角色），再按用户要求用 `plugin_dev_enable` 启用；修改已安装包后用 `plugin_dev_reload` 重载并重新查看登记。沿用宿主现有审批与权限机制，不能把安装/启用等同于危险权限授权。
5. 实际调用新增工具，或打开插件页面检查交互；整壳/agent/storage/prompt/model 注册成功不等于已被选中。用户要求装测时完成可执行验证，不停在「可以安装」。安装整壳不自动切换当前壳。
6. 回报插件 id/版本、包位置、权限、实际验证及未验证项。出错先停用本插件、重载已知可用版本；角色切换按设置里的恢复入口操作，不删除用户会话或擅自改授权表。删除用 `plugin_dev_delete`：正在使用/运行中的插件会被拒绝，先 `plugin_dev_enable {enabled:false}` 停用（会自动回退内置角色）再删。

工具参数速查（path 相对 ToolContext 当前目录解析并受工作区边界约束；执行前核对当前 schema）：

| 工具 | 参数 | 行为 |
|---|---|---|
| `plugin_dev_list` | `{}` 或 `{id:'whale-hello'}` | 列表或按 id 查询版本、路径、工具、壳、loadings、开关、权限及错误；loadings 含 id/label/protocol/selectionValue，不返回树、配置/服务值或当前选中状态。 |
| `plugin_dev_scaffold` | `{path:'my-plugin',id:'whale-hello',template:'tool'}` | 父目录须存在，不覆盖现有目录；id 为 1–80 字符且不可为 builtin.*。生成清单/入口/说明；template 还可为 surface-shell、webview-shell、loading。 |
| `plugin_dev_test` | `{path:'my-plugin'}` | 目录或 zip，隔离运行 apply 收集工具/壳/loadings 元数据；不执行工具、不落 registry、不接用户文件/网络/设备能力；不验证冷启动画面。 |
| `plugin_dev_install` | `{path:'my-plugin'}` | 目录或 zip，边界检查及试运行通过后暂存/备份/替换/持久化，失败回滚；旧配置只保留新 schema 支持的键；**新装默认停用**（升级保留原开关）。 |
| `plugin_dev_enable` | `{id:'whale-hello',enabled:true}` | 启停，不自动选择壳或授危险权限；停用会清掉指向它的角色选择并静默回退内置。 |
| `plugin_dev_delete` | `{id:'whale-hello'}` | 删除插件目录并清角色选择；正在使用/运行中的插件会被拒绝，先停用再删。 |
| `plugin_dev_reload` | `{id:'whale-hello'}` | 先隔离测试已安装包，再重载并刷新登记。 |
| `plugin_dev_call` | `{id:'whale-hello',tool:'whale_hello',args:{name:'鲸歌'}}` | 真实执行已启用且接入的插件工具，沿用参数校验、权限、超时与当前审批，可能产生副作用。 |
| `plugin_dev_test_shell` | `{id:'whale-hello',shell:'hello-shell',action:'restore',value:{}}` | 临时运行真实 apply/mount/state/action，校验 Surface 树或非空 HTML；合成空会话，宿主命令只记录不执行，不切当前壳，也不证明实际画面已验收。单壳时可省 shell，action/value 可省。 |
| `plugin_dev_test_capability` | `{capability:'hello.echo',args:{text:'你好'}}` | 真正调用已授权能力；不绕过提供方权限或超时，可能产生实际效果。 |
| `plugin_dev_grant` | `{id:'whale-hello',permissions:['storage:hooks']}` 或 `{id,all:true}` | 授予清单里声明过、需要确认的权限（清单外/未实现的会被丢弃）；写审计并弹插件页「撤销」横幅；没有额外的「AI 全控制」总开关。 |
| `plugin_dev_revoke` | `{id,permissions:['storage:hooks']}` 或 `{id,all:true}` | 对称撤销；`all:true` 记 `revoke_all`（清空全部已授予项）。 |
| `plugin_dev_config` | `{id:'whale-hello',config:{greeting:'嗨'}}` 或 `{id,reset:true}` | 按插件 Config schema 写入，不认识的键报错；`reset:true` 恢复默认；写审计。 |
| `plugin_dev_role` | `{role:'shell',value:'native'}` / `{role:'model',value:'deepseek'}` | 切 `shell/loading/agent/storage/prompt/model` 六个角色之一；`value` 用角色候选的选择值：壳/回合/存储/提示词的内置实现写 `native`，加载页写 `builtin.loading.native`，模型写 `deepseek`/`openai`，第三方写插件 id 或 `插件id#实现id`；目标不可用时报「角色选择失败」。 |
| `plugin_dev_middleware_state` | `{}` | 只读查「插件中间件总开关」当前状态（`on`/`off` + 说明），无审批、不写审计；扩展点插件没效果时先查它。 |
| `plugin_dev_middleware` | `{enabled:true}` / `{enabled:false}` | 开关**插件中间件总开关**（全局旁路）。关着时所有扩展点钩子不生效、扩展点插件看起来没效果；写操作走审批并记审计。 |

同 id 的 `install` 沿用重装语义：保留新 schema 兼容的用户配置，**保留原来的启用开关**（新装默认停用，不会因为重装被悄悄启用/停用），收回旧危险授权后须重新确认；`reload` 会保留新清单仍声明的已有授权。安装失败的回滚覆盖进程内异常，不代表断电或被系统杀进程时具备完整事务恢复。

list/test 在现有只读审批名单；其余沿用现有写工具审批策略，ask 模式询问、auto 模式允许，不另加「每次都必须问」的规则。危险权限默认走插件页逐项授权；需要 AI 代授时用 `plugin_dev_grant`（清单 sanitize + 工具审批 + 审计，见下节），不要手改授权文件、不要绕审批。若因此无法完成实际功能验证，如实记录所缺授权。

工具未暴露时用当前可用文件工具制作 zip，交付到鲸歌「包管理 → 插件 → 导入 zip」，使用插件详情里的试运行、启用、重载、授权和页面入口。不用桌面 `plugin_manager` / npm 命令替代原生安装。`plugin_dev_*` 返回的状态和限制优先于示例步骤。

## 工具输入上限与显式错误（M88-G，0.3.5）

模型侧每次工具调用的参数先过 `core:engine/ToolInputGuard`（按 UTF-8 字节）：普通工具 **64KB**（含插件工具，以及 `plugin_dev_call` 里嵌套的 `args`），`write`/`edit` **512KB**。超限、JSON 半截或非法时本次调用**不执行**，返回显式 error tool_result（「工具输入过长：N 字节 > 上限 M 字节」/「工具参数不是合法 JSON（可能被截断）」），回合继续，模型会拆小重试。插件工具别设计成必须一次塞超大 JSON：优先传文件路径、分页/分段参数；恰好 64KB 放行，中文按真实 UTF-8 字节算。

## AI 改插件授权 / 配置 / 角色（M88-E，docs/190-F 收口）

`plugin_dev_grant/revoke/config/role` 让 AI 直接改插件授权、配置和选中角色；`plugin_dev_middleware` 另管全局旁路开关。**「AI 全控制」总开关已在 docs/190-F 永久删除**：这四个工具永远可调，不再有 `ai_plugin_control_disabled` 错误；约束只剩下面几层。

- **清单与审批**：`grant` 只授清单里声明过、且需要用户确认的权限（`all:true` = 全部声明项）；清单外/未实现的会被 `sanitizeGrants` 丢弃。系统权限（通知、Shizuku、无障碍、悬浮窗）仍只由系统弹窗授予——`grant notify` 只算插件声明通过，不等于拿到 Android `POST_NOTIFICATIONS`。调用仍走 `approval_mode`：ask 弹审批卡，auto 放行。
- **审计与撤销**：每次操作追加到 `files/plugin-audit.jsonl`（`actor` 为 `ai`/`user`，`action` 为 `grant`/`revoke`/`revoke_all`/`config`/`role`/`middleware`，`target`/`detail`）；超过 512KB 轮转为 `.1`（保留一份，读回先旧后新）。插件页横幅「AI 已授予 <id>：<权限> · 撤销」，用户点撤销写入一条 `actor:"user"` 的 revoke；插件详情「最近操作（审计）」列最近 5 条，权限行标「已允许 · AI 授予」由审计回放得出（AI grant 加、任何来源的 revoke 减、revoke_all 清空）。
- **中间件总开关（M88-I）**：**扩展点插件「没效果」时先想到这个**——关着时工具/请求/存储/事件钩子全部旁路（插槽照常），插件本身没坏。用 `plugin_dev_middleware_state` 查当前状态（只读），需要时用 `plugin_dev_middleware {enabled:true}` 打开（写工具，走审批并记审计）；这是全局开关，默认开，关它只用于排障，不要用改 prefs 等旁路。
- **最小权限**：只授本次验证真正需要的项，优先逐项 `permissions`，少用 `all:true`；验证完把不用的撤销，并在交付说明里写清 AI 授予/撤销了什么。

`plugin_dev_config` 按插件 Config schema 校验后写入（`PluginStore.setConfig`），`reset:true` 恢复默认；不认识的键会报错。`plugin_dev_role` 只切 `shell/loading/agent/storage/prompt/model` 六个角色；`value` 用「插件 → 角色」页候选的选择值——壳/回合/存储/提示词的内置实现写 `native`，加载页特殊为 `builtin.loading.native`，模型写 `deepseek`/`openai`（或 `builtin.model.*`），第三方写插件 id 或 `插件id#实现id`；写错/目标停用会返回「角色选择失败（原因见插件页提示）」，切角色本身不授予权限。四个工具同属 `builtin.plugin.dev` 组——「插件 → 内置 → 插件开发」关掉后它们与其它 `plugin_dev_*` 一起从工具表消失。

## 包与模块

```text
my-plugin/
  package.json
  index.js
  client.js       # 仅页面/独立 WebView 客户端需要
```

```json
{
  "name": "whale-hello",
  "id": "whale-hello",
  "version": "0.1.0",
  "description": "鲸歌示例工具",
  "main": "index.js",
  "permissions": []
}
```

zip 根直接放清单，或保留一个公共顶层目录。`main` 显式填写；package.json 不填时默认是 `lib/index.js`。id 用稳定的字母数字、`@ . _ / -`，不要拿版本号当 id。zip 最多 2000 个文件、单文件 4 MiB、解压总计 32 MiB；不含绝对路径、`..`、重复路径。升级保留旧版本包以便回退。

入口接受 ESM/CJS，导出 `apply(ctx, config)`；ESM 声明各占独立行，转换器不是完整打包器。Host QuickJS 无 DOM/完整 Node、无 npm 依赖安装；相对 import/require 不解析，依赖要内联到入口。已实现 `defineTool`、schemastery 子集、部分 fs/path/os/events/buffer/http/child_process shim，不表示支持整个对应 npm/Node API；未知成员可能是宽松占位，没抛错不能当验收成功。`cordis.patch.yml` 组合包只解析展示部分元数据，不会安装 npm 子插件或执行完整补丁。

配置可在清单写 `config: {schema: <JSON Schema>, default: {...}}`，或导出支持的 schemastery `Config`；宿主合并默认值和用户值再传 `apply`。不要把 token/密钥硬编码进源码、zip、日志或服务快照。

## 最小工具：先让这一条真实跑通

`index.js`：

```js
export const name = 'whale-hello'
export function apply(ctx, config) {
  ctx.tools.register({
    name: 'whale_hello',
    description: '按名称打招呼',
    parameters: {
      type: 'object',
      properties: { name: { type: 'string', description: '称呼' } },
      required: ['name']
    },
    async execute(args) {
      return '你好，' + String(args.name) + '！'
    }
  })
}
```

工具名避免和内置/其它插件冲突，先登记者生效。参数优先写 JSON Schema；无法识别的 schema 不会进入模型工具表。返回字符串或可 JSON 序列化值；对象有 `text` 字段时宿主取该字段。不要依赖桌面 output.render 管线或完整 exec.agent/exec.signal。工具调用会新建 QuickJS、重新模块加载和 apply，不能用工具闭包保存跨调用状态；apply 只登记能力，副作用放执行函数，避免试运行/重载时重复操作。

文件示例（清单需 `fs:workspace`）：`ctx.fs.readFileSync('notes.txt', 'utf8')`、`ctx.fs.writeFileSync('notes.txt', text)`。网络示例（需实际域名的 `net:api.example.com` 授权）：

```js
const reply = await ctx.http.get('https://api.example.com/status')
if (!reply.ok) throw new Error('HTTP ' + reply.status)
return reply.body
```

## 角色与可用 API

角色登记在 Host 的 `apply` 中；除说明外，回调可以返回 Promise。声明权限与已获授权都要满足，注册不会绕过选中/调用条件。

| 目标 | 登记接口与契约 |
|---|---|
| 模型工具 | `ctx.tools.register({name, description, parameters, execute})`；`ctx.tools.names()` 只列当前注册环境中的工具，不是任意宿主工具执行入口。 |
| 斜杠命令 | `ctx.commands.register({name:'hello', description, handler({rawInput, attachments}){ return '结果' }})`；命令名不带 `/`，只含小写字母数字 `_ -`，结果显示命令提示，不自动入模型历史。 |
| 提示词段 | `ctx.systemPrompt.section({name:'hello.policy', order:100, text:'…'})`；`context` 同形。函数 text 和 `variable(name, valueOrFn)` 在 apply 时求值保存快照，不是每请求动态执行。`complete:true` 不启用。 |
| 整体 prompt | `ctx.systemPrompt.register({id:'hello.prompt', label:'示例提示词', assemble(req){ return req.base + '\n请保持简洁。' }})`；权限 `prompt`。req 含 base/model/provider/tools/planMode/personaPrefix/personaSuffix/variables，返回 string 或 `{text}`；选择后才接入。 |
| 页面 | `ctx.ui.registerRoute({id:'hello', title:'你好', description:'说明', page:true})` 或 `registerPage({id,title})`；渲染来自插件自己的 dsh.client。与内置页面同 id 的整页可能替换该页，改用户界面前说明实际影响。 |
| 启动加载页 | `ctx.ui.registerLoading({id,label,tree})`；权限 `ui`，`loading/v1` 仅静态 JSON 树，单独选择后下次启动读快照；无 mount/onAction/HTML，见加载页专节。 |
| 整壳 | `ctx.ui.registerShell({id,label,backend,mount,onState,onAction,onEvent})`；权限 `ui`，backend 为 surface（默认）或 webview，见下节。 |
| agent | `ctx.agent.register({id:'hello.agent', label:'示例回合', async run(turn, api){ if(api.abort()) return {stopReason:'aborted',text:''}; api.status('处理…'); return {stopReason:'completed',text:'完成'} }})`；权限 `agent`。turn 含 turnId/sessionId/input/state；run 的第二参数只开放 notify/status/abort/tools.names 等当前宿主原语。 |
| storage | `ctx.storage.register({id,label,list(){return [...]},load(id){return jsonlText}})`；权限 `storage`，v1 **只读**。list 返回会话摘要数组（含 id/title/updatedAt），load 返回会话 JSONL 字符串或 `{doc}`；需符合宿主会话格式。 |
| model | `ctx.llm.register({id,label,protocol:'openai',baseUrl,model,apiKey})` 配置式接内置 OpenAI/Anthropic 通道；或提供 `stream(request, emit)` 自产回合，request 为统一 Anthropic 形状。不要在配置式例子里填真实密钥。 |
| capability | `ctx.capability.register({id:'hello.echo',label:'回声',description:'返回输入',kind:'utility',invoke(args){return args}})`；权限 `capability`，返回 JSON 值或 `{ok:true,value}`，失败 `{ok:false,error}`。它注册实现，不自动获得 root/NFC/Shizuku/ADB 等底层权限；0.3.5 起模型可用 `capability` 工具按 id 调用（插件需启用且授予 `capability`），内置能力也可用 `capability`/`notify` 工具。 |

agent 的 `api.tools.invoke`、`api.llm.stream`、`api.storage.append` 当前明确返回 `{ok:false,code:'UNSUPPORTED'}`，不能宣称实现完整自主工具回合。storage 无 create/append/save 写入契约，不能把 readOnly:false 变成写库。`ctx.storage.exportList()` / `exportLoad(sessionId)` 是带 storage 授权的内置会话只读导出原语，返回宿主信封；先检查 ok 再取 sessions/doc，不能把信封直接当 JSONL。

自定义 model 的最小离线验证（不接外网）：

```js
ctx.llm.register({
  id: 'hello-model', label: '离线演示',
  stream(request, emit) {
    emit('你好')
    return { text: '你好', stopReason: 'end_turn' }
  }
})
```

`emit('文本片段')` 是增量文本；也接受 `{type:'thinking',text}`、`{type:'tool_use',id,name,input}`。最终返回 `{text, toolCalls?, stopReason?, usage?}`；工具调用结果交宿主正常工具循环处理。不要把 emit 前缀在最终 text 中重复拼接，也不要编造 usage；缺 usage 就如实缺失。此供应商回调与 agent.run 的 api.llm.stream 是两条不同接口。

## 静态启动加载页：loading/v1

清单 `permissions` 加 `ui`，Host 入口在 `apply` 里调用真实 API；可先用 `plugin_dev_scaffold` 的 `template:'loading'` 生成同形模板：

```js
export function apply(ctx) {
  ctx.ui.registerLoading({
    id: 'startup', label: '我的启动加载页',
    tree: { type: 'column', padding: 24, gap: 12, align: 'center', children: [
      { type: 'icon', name: 'hourglass', size: 40, color: 'brand' },
      { type: 'text', text: '正在准备…', size: 24, weight: 'bold' },
      { type: 'progress' },
      { type: 'text', bind: 'boot.message', color: 'sub' },
      { type: 'text', bind: 'boot.elapsed', size: 12, color: 'faint' }
    ] }
  })
}
```

声明只接受 `id`、`label`、`tree`。加载页 id 为 1–80 个字符，首字符字母或数字，其余仅字母数字及 `._:-`；与允许 `@`、`/` 的包 id 分开。label 默认使用 id，至多 120 字符。不要增加 `version`、`backend`、`mount`、回调或计算表达式；协议由宿主确定为 `loading/v1`。

预算按当前 Android 源码执行：每包至多 8 个不同 id，每个完整声明（包含 id/label/tree）序列化后的 UTF-8 至多 32 KiB，树至多 128 节点，根深度为 0、最大深度为 12，单个 text 至多 2000 字符。中文按 UTF-8 字节计入 32 KiB，不只按 JS 字符串长度计算。未知字段、函数、重复 id、超限、危险原语都会使试运行/安装失败；不会静默删掉后继续安装。

| 节点或属性 | 允许范围 |
|---|---|
| 原语 | `column` / `row` / `stack` / `text` / `progress` / `icon` / `spacer` / `divider`；只有前三项接受 `children`。 |
| 通用属性 | `padding`、`gap`、`corner` 为 0–96 有限数；`background` 使用主题 token；`align` 为 start/center/end。 |
| text | `text` 或只读 `bind`；size 10–48，weight normal/medium/bold，color 使用主题 token。bind 存在时优先显示绑定值。 |
| 绑定 | 仅 `boot.phase`、`boot.message`、`boot.elapsed`；elapsed 为显示用秒字符串，例如 `1s`，不代表进度百分比。 |
| progress | 省略 value 为不确定进度，value 只允许 0–1 静态数；color 使用主题 token，无 bind 进度接口。 |
| icon / spacer | size 0–96；图标名仅 whale/hourglass/extension/info，icon 的 color 使用主题 token。 |
| 主题 token | background/surface/brand/text/sub/faint/warn；不接受自定义颜色值。 |

加载页不接受按钮、输入、actions、image、文件路径、网络资源、WebView/HTML、聊天/设置绑定或宿主命令。错误恢复按钮由宿主提供，不放进插件树。样式应兼容窄屏/横屏/系统字体与深浅色；不在 apply 发网或延时，也不以动画计时阻塞启动。

安装启用和选择是两个步骤：先 `plugin_dev_test` 检查 `loadings` 的 `id/label/protocol/selectionValue`，再安装；在「插件 → 角色 → 启动加载页」主动选择。`selectionValue` 形如 `包id#声明id`，是候选标识，列表没有断言它已选中。默认实现为 `builtin.loading.native`；加载页选择与整壳选择独立，工具不会替用户自动选择。

选择/重载/启停/授权等宿主变更会用已校验的登记刷新独立小快照；下次冷启动在创建 AppViewModel 前只读该快照，不扫描插件 registry、读取入口或执行 QuickJS 来显示加载页。快照有大小与 checksum 校验，写入先同步临时文件再原子替换。它记录宿主最近一次校验结果；直接在宿主流程外改包文件不会自动让旧快照失效，修改后必须 reload 或重新安装。把配置/私密文本写进树也会进入快照，不把凭据当展示内容。

当前启动时序为：读快照/主题 → 所选加载树首次 Android draw 回调 → 创建 VM/登记核心 → 等当前所需 agent/prompt/流式 model resident → 主界面。首 draw 是 Android 提交/回调证据，不能宣称屏幕已呈现或 WebView DOM 已可见。10 秒是当前必需角色的等待上限，超时进入失败恢复，不当成功放行。历史文档中的「≤1.2s」是启动优化目标，当前没有加载插件可设置的 `budgetMs` / `delay` / `minDuration` API，也没有总启动 1.2 秒硬保证；需分别量测 loadingFirstDraw、coreReady、shellFirstDraw。

恢复与验收按真实结果处理：

1. 缺失/损坏/超大/校验不符/选择不匹配的快照，本次直接用默认加载页。停用、缺 ui、删除提供方或登记失败在 refresh 时回默认并给原因；正常启动后再看角色页提示。恢复默认只处理加载页，不修复故障 agent/model 等其它角色。
2. 启动失败时用宿主「重试」「使用默认加载页」；核心登记已完成时才有「管理插件 / 修复角色」。进入原生插件管理后，停用故障插件或恢复相应内置角色，再返回并重试。恢复面板不执行第三方插件页面；不要清数据、删会话或改授权表解决启动问题。
3. 选择后做真正进程冷启动，确认所选静态树先画出，再量测核心和主界面；试运行、离屏 shell 探针或独立快照读写均不能替代这一步。随后覆盖停用/撤销 ui/坏升级后回退、坏快照、失败重试与恢复插件管理、onboarding/安全模式、旋转/热 Intent，保留原始证据并逐项报告未验部分。

## 页面与两种整壳

页面/独立客户端在清单增加：

```json
{
  "dsh": { "client": { "platform": "web", "entry": "client.js" } },
  "exports": { "./client": "./client.js" }
}
```

上述字段合并进已有清单，不覆盖 name/main 等。客户端入口必须是宿主兼容的 `__ModuleLoader__` bundle；裸 ESM/HTML 不会自动变成可加载 client。最小 `client.js`：

```js
window.__ModuleLoader__.load({
  id: 'whale-hello',
  factory: function () {
    return { apply: function (ctx) {
      ctx.slots.register({name:'shell.overlay',id:'hello'}, function () {
        return window.React.createElement('div', {style:{padding:24}}, '你好，鲸歌')
      })
    }}
  }
})
```

这是独立 WebView 内的页面，不是 Android 系统浮窗。Host ctx 与浏览器 ctx 不同：client 只有最小 sessions/get、effect、slots 等兼容面；当前 client `ctx.on` 是占位，不能等同 Host 常驻事件订阅。当前 slots 实际渲染 shell.overlay，不能许诺桌面全部 slots。JS 创建系统浮窗尚未开放；`ctx.ui.registerView` 未实现。需要替换主界面走 registerShell，不再套用旧技能里一概拒绝 root slot 的产品限制。

Surface 壳（清单 permissions 加 `ui`）：

```js
ctx.ui.registerShell({
  id: 'hello-shell', label: '你好壳', backend: 'surface',
  mount() {
    return {type:'column',padding:16,children:[
      {type:'text',text:'你好，鲸歌'},
      {type:'button',label:'恢复原生壳',onTap:'restore'}
    ]}
  },
  onAction(action, value, state) {
    if (action === 'restore') ctx.surface.command('closeShell', {})
    return this.mount(state)
  }
})
```

mount(state)、onState(state)、onAction(action,value,state)、onEvent(name,state) 返回 UI 树。可用原语：column/row/stack/scroll/list/text/markdown/image/icon/button/textfield/switch/slider/divider/spacer/progress/chip/webview。节点属性直接放节点上；只读 bind 根为 chat/sessions/settings/theme/capabilities，list 模板内可用 item。最多 2000 节点、24 层、文本字段最多 8000 字符。事件 action id 为字母数字及 `._:-`；未知属性被丢弃，别编造容器或执行表达式。

WebView 壳仍在 Host 注册，`backend:'webview'`，mount/onState 返回 `{title:'示例',html:'<button onclick="DshWebShell.command(\'closeShell\',{})">恢复原生壳</button>'}`。html 是塞进固定文档根的片段；不要依赖 innerHTML 插入的 script 执行。浏览器用 `DshWebShell.action(name,value)` 把动作交回 onAction，或 `DshWebShell.command(name,args,onResult)` 调白名单命令；`DshWebShell.state()` 读状态。Host 使用 `ctx.surface.command(name,args)`，两边不要混用。

命令包括 send（`{text}`）/stop/newSession/openSession/selectSession/openSettings/closeShell/setDraft（`{text}`）/approval/pickFile/screenshot/removeAttachment/setShellView/answer/goal/deleteSchedule/focusSession，以及生成中发送的 cancelQueuedPrompt（`{id}`）/resumeQueuedPrompts/setSendWhileRunningMode（`{mode:'queue'|'steer'}`）。命令在宿主主线程同步执行并有超时，onTap/onAction 里只做短动作。复杂参数按当前宿主 schema/源码确认；不要自动批准工具、假造回答或使用恢复按钮隐藏授权。

壳拿到的 `chat` 状态包含 `sessionId/title/status/draft/streaming/error/readOnly/usage/queueNotice/hasQueuedPrompts/queuePaused/queuedPrompts[{id,text,delivery,status,pausedReason,createdAt,imageCount,fileCount,canCancel}]`、`pendingImages/pendingFiles` 等只读投影；队列条目是宿主快照，`canCancel` 只在 pending 为真，不要自己维护第二份队列状态。

## 服务、投影、事件与清理

```js
ctx.provide('helloSettings', { greeting: '你好' })
ctx.inject(['helloSettings'], function (scope) {
  const settings = scope.helloSettings
  // 依赖就绪后在这里登记使用 settings 的能力
})
ctx.on('turn/end', function (event, meta) {
  console.log(meta.sessionId, event.type)
})
ctx.effect(function () {
  return function () { console.log('hello cleanup') }
})
ctx.sessionProjections.register({
  key: 'hello.turns', stateVersion: 1,
  init(header, inheritedEventCount) { return {count:0} },
  apply(state, event) {
    return event.type === 'turn/end' ? {count:state.count + 1} : state
  },
  wire: {view(state) { return {count:state.count} }}
})
```

provide/inject 是跨插件 **JSON 快照**，不是共享函数/类/原生对象；缺必需依赖时回调不执行并登记 missingDeps。可选依赖写 `{name:'helloSettings',optional:true}`。`ctx.plugin(subPlugin, config)` 只内联 apply，不会新装包。

投影 init/apply/wire.view 必须同步、返回 JSON 可存值；不关心的事件返回同一 state 引用，更新时返回新对象。宿主按会话隔离状态、重放日志，checkpoint 按 stateVersion/seq 恢复；改变状态结构时升级 stateVersion，不能把恢复当每次重置。schema 不做完整 zod 校验。

订阅用真实会话事件名（turn/start、turn/end、tool/result 等），或 session/event、*；回调 `(event,{name,sessionId,header})`。Host 对异步事件回调不等待，仅记异常；不要靠它建立必须完成的事务。已开启的会话会重放历史，副作用要处理重复投递。注册投影/订阅/effect/模型流/角色的启用插件可有常驻 runtime，停用/重载/删除先清理再关闭；工具 execute 仍是独立实例，别把两种状态生命周期混淆。effect 返回同步 cleanup，异步 cleanup 不阻塞关闭。

## 0.3.5 中间件 / 插槽 / 存储钩子（M87-A~F）

0.3.5 起新增四类「链式扩展点」和一个新权限 `storage:hooks`。共同纪律（docs/181 §3.1）：

- 链顺序 = 插件清单顺序（= 启用顺序），同级再按注册先后；没有隐式优先级。中间件签名 `async (payload, next) => result`，不拥有决策时必须 `await next(...)` 并返回它的结果；返回 `null`/`undefined` = 放弃改写，继续链；抛异常 = 本中间件跳过并记 `DshExt` 诊断，不影响其它插件与主流程。
- 单次 JS 往返预算 50ms；超时按跳过处理。工具/回合本身不受 50ms 限制。
- 中间件只改「这一次请求/这一次工具结果」，**不改写会话历史**；工具结果改写时工具卡会标「已由 <插件> 改写」并记录原始哈希。
- 插件页「扩展点」页签只列**已启用**插件：注册项、顺序、生效/缺授权/未常驻、本次进程的命中/异常/最近耗时；工具 `post-execute` 还展示 dispatch 前后 12 位 SHA-256。停用插件不出现（连「声明存在」占位也没有，搜索也搜不到——0.3.5 M88-A 起与角色页同一规则）；删除后「导入」列表、角色页、扩展点页签都不再出现；「内置」页签仍列停用项，可在那里重新打开。插件页「扩展点」页签顶部的「插件中间件总开关」可一键旁路工具/请求/存储/事件钩子（插槽与插件注册不受影响，用于排障）；AI 侧对应 `plugin_dev_middleware_state` 查状态、`plugin_dev_middleware` 开关（M88-I）。
- 插件日志用 `console.log/info/warn/error`（宿主进 `DshProjection: resident <id> console:`）。**没有 `ctx.log`**；`ctx.logger` 是 no-op，不要用它当验收日志。

### 工具内部（`tools:intercept` 只约束 describe/on）

```js
ctx.tools.restrict(['bash', 'shower_*'])                 // 只能删工具（同步，不需权限）
ctx.tools.guard('bash', (call) => call.args.command.includes('rm -rf'))  // 同步拒绝
ctx.tools.describe('bash', { description: '…', parameters: { … } })      // 改 schema，需 tools:intercept
ctx.tools.on('pre-execute', async (call, next) => next({ ...call, args: mutate(call.args) }))
ctx.tools.on('post-execute', async (res, next) => next({ ...res, text: res.text.replace('a', 'b') }))
ctx.tools.on('result', (res) => { /* 只读观察 */ })
```

`restrict/guard` 只能收紧、不需要 `tools:intercept`；`describe/pre-execute/post-execute/result` 需要。`post-execute` v1 只改 `text`/`isError`，图片原样保留。guard 拒绝和 restrict 禁用都会落 `isError` 工具结果（工具卡红色）。

### 请求 / 提示词内部（`llm:request`）

```js
ctx.on('system-prompt/assemble', async (prompt, next) => next(prompt.replace('旧段', '新段')))
ctx.on('agent/request', async (req, next) => next({ ...req, model: 'deepseek-v4-pro' }))
ctx.on('llm/stream', async (flow, next) => next(rewrite(flow)))
ctx.on('agent/pre-step', async (step, next) => next({ ...step, inject: ['插件注入的一条 user 记录'] }))
```

`agent/request` 只改本次请求副本；`llm/stream` 改写会同时进 UI 与会话日志（模型看到的就是改写后的值）；`agent/pre-step` 只能注入、不能删本步消息。注意：step 1 的 `step.lastUserText` 可能取到宿主先落的技能目录通知，而不是用户原始输入；需要按原始输入判断时，在 `storage.on('before-append')` 里同步记标记（本仓 M87-E fixture 的做法）。

### UI 插槽（`ui:slot`）

```js
ctx.ui.slots.register({
  slot: 'chat.composer.dock', id: 'hello', order: 10, when: { session: 'any' },
  tree: { type: 'row', gap: 8, children: [
    { type: 'text', text: 'HELLO', color: 'sub' },
    { type: 'button', text: '点我', onTap: 'hello/tap' },
    { type: 'text', bind: 'state.hits', color: 'success' }
  ] }
})
ctx.ui.slots.action('hello/tap', async (payload, host) => ({ state: { hits: Number(host.state.hits || 0) + 1 } }))
```

0.3.5 槽位：`chat.composer.dock`、`chat.node.tool`、`chat.node.assistant`、`chat.node.user`、`chat.message.actions`、`settings.rows`、`drawer.items`、`shell.overlay`。Surface 子树预算（≤64 节点/≤12 层/单文本≤2000/16 KiB）、只读 `state.*`/`view.*` 绑定、动作 id、主题 token、未知字段拒绝都沿用；`shell.overlay` 仍禁 `webview`/`image`。动作抛错只记 `slot action rejected`，UI 状态不变。插槽不受「插件中间件总开关」影响。

### 存储 / 事件钩子（`storage:hooks`，危险权限）

```js
ctx.storage.on('before-append', async (event, next) => {
  if (event.type === 'user/message' && JSON.stringify(event.data.content).includes('拒绝我')) {
    return { allow: false, reason: '插件拒绝写入' }
  }
  return next(event)   // v1 只做观测 + 拒绝，不改写
})
ctx.storage.on('after-load', async (session) => {
  console.log('after-load', session.sessionId, session.eventCount)
})
ctx.on('session/event', (e) => {
  // 只对插件记录（source.kind=plugin-*，如 agent/pre-step 注入的 user/message）可 veto；
  // 普通会话事件不可 veto。非 veto 分支必须 return undefined（return null 会被当成 veto）。
  if (e.type === 'user/message' && e.data?.source?.kind === 'plugin-inject' &&
      JSON.stringify(e.data.content).includes('VETO-ME')) return { veto: true, reason: '…' }
  return undefined
})
```

- `before-append` 只问 `user/message` 与 `tool/result`；拒绝的事件**不落盘、不占 seq**，UI 显示插件拒绝原因。工具结果被拒时宿主补一条 `isError:true` 的 trusted 结果保持 `tool_use/tool_result` 配对，模型看到的是宿主 fallback（「原始输出未落盘」），不是工具输出。
- `after-load` 在打开会话、重放完已有事件后投递一次（有 checkpoint 先 restore），不是每条事件一次。
- `session/event` 订阅者会收到落盘后的观察回调；对插件记录额外有一次落盘前的 veto 检查（所以同一记录可能被调两次）。停用/重载后钩子随常驻 runtime 撤下。
- 总开关关闭时：before-append 连 gate 都不进、after-load 不投、`session/event` 不投递；插槽照常。

## 权限与不支持项

- `fs:workspace`：按当前工作区解析，canonical 校验阻止越界；不是插件目录，也不是只读。`fs:any`：仍受 Android App 可访问范围限制。
- `net:<域名>` 或 `net:any`：需宿主授权，仅 HTTPS，本机/链路本地等地址受限制，重定向逐跳检查。不要为通过测试擅自扩大到 net:any。
- `shell`：需宿主危险权限确认；执行时**自动选择身份：root 优先，其次 Shizuku，最后 App**（0.3.5 起 `root.exec` 已并入 `shell.exec`），返回的 `identity` 说明实际身份，不假装 root；已授权后不会保证每条 shell 都再问一次。`device`/`memory` 同样沿用宿主权限与设备条件，声明不等于授权。
- **内置插件组可开关**：插件页「内置」页签每个内置插件都有开关，关掉即从工具表/页面入口注销。与插件相关：`builtin.plugin.dev`（「插件开发」，全部 `plugin_dev_*`，含 AI 控制四工具）、`builtin.node.host`（「Node 宿主」，9 个 `nodehost_*`）、`builtin.capability`（「能力与通知」，`capability`/`notify`）、`builtin.web`（「联网」，`web_search`/`web_fetch`/`curl`）、`builtin.fs`（`read`/`read_image`/`write`/`edit`/`glob`/`grep`）、`builtin.shell`（`bash`/终端）。模型侧 HTTP 用内置 `curl`（只允许 http/https，不回显请求头与 `set-cookie`；`http_request` 只是旧会话别名，模型看不到）；插件自己的网络仍走 `ctx.http` + `net:` 授权。若 `plugin_dev_*` 没出现在工具表，先确认「插件开发」组没被关。
- `ui`/`agent`/`storage`/`prompt`/`capability` 按当前宿主导入默认规则登记与授予，角色选择/调用还有各自条件。0.3.5 新增的 `ui:slot`（插槽）、`tools:intercept`（改描述/参数/结果）、`llm:request`（改请求/流/提示词）、`storage:hooks`（写钩子/事件 veto）都是危险权限、默认需逐项确认、不自动授权；`restrict`/`guard` 只收紧，不需要 `tools:intercept`。AI 可经 `plugin_dev_grant/revoke` 改已声明授权（总开关 + 工具审批 + 审计，见 M88-E 节），但仍不能改系统权限或授权文件、不能绕过工具审批。
- 通知桥依宿主场景与 Android 通知条件工作；`notify` 权限已实现（默认仍需确认），Android 13+ 还要系统 `POST_NOTIFICATIONS`。不能用 `Notification.permission='granted'` 占位值证明系统通知权限已取得。
- UNSUPPORTED/NOT_SUPPORTED、未知能力、缺依赖、PERMISSION_DENIED 均应检查并如实报告。别用空结果当成功、换别的窗口冒充目标 UI，或用 stub 冒充设备原生能力。
- QuickJS 与 App 同进程，是能力调用边界与超时控制，不是内核隔离。长任务避免同步死循环；不运行 npm lifecycle、不加载 DEX，不假设 Electron/桌面 helper 可运行。

## 交付前最小检查

- 工具：正确参数、缺参数/错误参数、真实 execute；注册不等于执行通过，工具数为零的页面/角色包也可能正常。
- 权限：未授权时确实拒绝；已授权能力按预期；错误和超时可读，日志无凭据。试运行也会跑 apply，确认 apply 无安装/发网/删文件副作用。
- AI 代授（用到时）：`plugin_dev_grant/revoke` 只授清单内权限、审计有记录、插件页横幅能撤销；没有额外总开关（docs/190-F 已删除）；系统权限仍由系统弹窗授予。
- 生命周期：停用/重载后工具与角色撤下，cleanup 可用，重启/历史重放不重复产生外部副作用；升级保留可回退包。
- UI：窄屏/宽屏、横竖屏、深浅色、中文长文本、键盘遮挡、可滚动/可点击、状态和错误可见；主界面保留停止与恢复原生壳入口，未授权审批和提问按真实用户操作处理。
- 加载页：真实 registerLoading → list/test 元数据 → 安装选择 → 进程冷启动所选快照首 draw → 必需角色就绪 → 主界面；另验停用/坏声明/坏快照与失败恢复，记录每段耗时，不以静态登记通过宣称冷启动已验。
- 分别报告「源码自检」「试运行」「安装启用」「实际功能/页面验证」完成程度；没执行的步骤不能写通过。

源码核对入口（相对原生仓库根）：`core/plugins/.../PluginPackage.kt`、`PluginModule.kt`、`PluginSandbox.kt`、`PluginPermissions.kt`、`PluginServices.kt` 中 PluginCapabilities、`SurfaceSpec.kt`、`LoadingSpec.kt`、`PluginClientEntry.kt`；加载快照 `app/.../data/LoadingSnapshotStore.kt`，启动闸门与恢复 `app/.../ui/boot/BootHost.kt`、`BootLoadingScreen.kt`、`BootRecoveryPanel.kt`，常驻调度 `app/.../data/PluginResidentRuntime.kt`，插件开发/AI 控制工具 `app/.../data/PluginDevTools.kt`、审计 `PluginAuditLog.kt`、内置组 `BuiltinPlugins.kt`，工具输入闸门 `core:engine/ToolInputGuard.kt`，页面桥 `app/src/main/assets/client-host/host.js`，整壳桥 `app/.../ui/surface/WebViewShellScreen.kt`。注释/旧技能与实际 dispatch 不一致时读实现确认，不能凭宽松 shim 推断能力。

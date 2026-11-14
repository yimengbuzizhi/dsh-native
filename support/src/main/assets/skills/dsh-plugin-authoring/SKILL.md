---
name: dsh-plugin-authoring
description: 为鲸歌 Android 原生版编写、适配、试运行、安装、启停和排查 JS 插件；覆盖工具、静态启动加载页、页面、Surface/WebView 整壳、回合、只读存储、提示词、模型供应商、能力提供方及事件投影。
whenToUse: 用户要求「写插件」「AI 做插件并装测」「修改鲸歌界面/壳」「适配社区插件」「插件开发、安装或加载报错」时加载。
---

# 鲸歌原生插件开发与装测

本技能针对鲸歌 Android 原生运行时。以当前工具 schema 和宿主返回值为准；桌面 DSH 的 npm 安装、Cordis 全量服务、Electron、DEX/APK 动态加载不适用于这条 JS 插件链。把已有 DSH/社区插件迁到这些角色，见 `dsh-plugin-adapter`；与本技能冲突的旧能力说明（例如「只支持工具半侧」「root slot 一律拒绝」）不要照搬。

## 先做出能检查的包，再安装验证

1. 用 `plugin_dev_list` 看已有插件、工具/壳登记和权限，确认目标与是否升级同 id 包。按需读取目标源码或插件详情中的公开能力信息；不要读取凭据来凑示例。
2. 用 `plugin_dev_scaffold` 或工作区文件工具生成包。只实现用户需要的角色，声明实际使用的权限。工具的具体参数先看本次暴露的 schema，不猜参数名。
3. 用 `plugin_dev_test` 对包试运行，检查返回的错误、tools、shells、loadings、unsupported、missingServices；参数 schema 与其它角色登记再核对源码和插件详情，不假设工具返回所有内部字段。试运行会执行模块与 `apply`，不是纯语法检查；也不等于工具 execute、页面交互或外部 API 已验收。
4. 修完后用 `plugin_dev_install` 安装，再按用户要求用 `plugin_dev_enable` 启停；修改已安装包后用 `plugin_dev_reload` 重载并重新查看登记。沿用宿主现有审批与权限机制，不能把安装/启用等同于危险权限授权。
5. 实际调用新增工具，或打开插件页面检查交互；整壳/agent/storage/prompt/model 注册成功不等于已被选中。用户要求装测时完成可执行验证，不停在「可以安装」。安装整壳不自动切换当前壳。
6. 回报插件 id/版本、包位置、权限、实际验证及未验证项。出错先停用本插件、重载已知可用版本；角色切换按设置里的恢复入口操作，不删除用户会话或擅自改授权表。

工具参数速查（path 相对 ToolContext 当前目录解析并受工作区边界约束；执行前核对当前 schema）：

| 工具 | 参数 | 行为 |
|---|---|---|
| `plugin_dev_list` | `{}` 或 `{id:'whale-hello'}` | 列表或按 id 查询版本、路径、工具、壳、loadings、开关、权限及错误；loadings 含 id/label/protocol/selectionValue，不返回树、配置/服务值或当前选中状态。 |
| `plugin_dev_scaffold` | `{path:'my-plugin',id:'whale-hello',template:'tool'}` | 父目录须存在，不覆盖现有目录；id 为 1–80 字符且不可为 builtin.*。生成清单/入口/说明；template 还可为 surface-shell、webview-shell、loading。 |
| `plugin_dev_test` | `{path:'my-plugin'}` | 目录或 zip，隔离运行 apply 收集工具/壳/loadings 元数据；不执行工具、不落 registry、不接用户文件/网络/设备能力；不验证冷启动画面。 |
| `plugin_dev_install` | `{path:'my-plugin'}` | 目录或 zip，边界检查及试运行通过后暂存/备份/替换/持久化，失败回滚；旧配置只保留新 schema 支持的键。 |
| `plugin_dev_enable` | `{id:'whale-hello',enabled:true}` | 启停，不自动选择壳或授危险权限。 |
| `plugin_dev_reload` | `{id:'whale-hello'}` | 先隔离测试已安装包，再重载并刷新登记。 |
| `plugin_dev_call` | `{id:'whale-hello',tool:'whale_hello',args:{name:'鲸歌'}}` | 真实执行已启用且接入的插件工具，沿用参数校验、权限、超时与当前审批，可能产生副作用。 |
| `plugin_dev_test_shell` | `{id:'whale-hello',shell:'hello-shell',action:'restore',value:{}}` | 临时运行真实 apply/mount/state/action，校验 Surface 树或非空 HTML；合成空会话，宿主命令只记录不执行，不切当前壳，也不证明实际画面已验收。单壳时可省 shell，action/value 可省。 |
| `plugin_dev_test_capability` | `{capability:'hello.echo',args:{text:'你好'}}` | 真正调用已授权能力；不绕过提供方权限或超时，可能产生实际效果。 |

同 id 的 `install` 沿用重装语义：保留新 schema 兼容的用户配置，重新启用插件，收回旧危险授权后须重新确认；`reload` 会保留新清单仍声明的已有授权。安装失败的回滚覆盖进程内异常，不代表断电或被系统杀进程时具备完整事务恢复。

list/test 在现有只读审批名单；其余沿用现有写工具审批策略，ask 模式询问、auto 模式允许，不另加「每次都必须问」的规则。危险权限仍走插件页逐项授权；若因此无法完成实际功能验证，如实记录所缺授权，不修改授权表。

工具未暴露时用当前可用文件工具制作 zip，交付到鲸歌「包管理 → 插件 → 导入 zip」，使用插件详情里的试运行、启用、重载、授权和页面入口。不用桌面 `plugin_manager` / npm 命令替代原生安装。`plugin_dev_*` 返回的状态和限制优先于示例步骤。

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
| capability | `ctx.capability.register({id:'hello.echo',label:'回声',description:'返回输入',kind:'utility',invoke(args){return args}})`；权限 `capability`，返回 JSON 值或 `{ok:true,value}`，失败 `{ok:false,error}`。它注册实现，不自动获得 root/NFC/Shizuku/ADB 等底层权限；能力调用目前由插件页/调试宿主入口发起。 |

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

## 权限与不支持项

- `fs:workspace`：按当前工作区解析，canonical 校验阻止越界；不是插件目录，也不是只读。`fs:any`：仍受 Android App 可访问范围限制。
- `net:<域名>` 或 `net:any`：需宿主授权，仅 HTTPS，本机/链路本地等地址受限制，重定向逐跳检查。不要为通过测试擅自扩大到 net:any。
- `shell`：需宿主危险权限确认，以 App 身份执行，不是 root；已授权后不会保证每条 shell 都再问一次。`device`/`memory` 同样沿用宿主权限与设备条件，声明不等于授权。
- `ui`/`agent`/`storage`/`prompt`/`capability` 按当前宿主导入默认规则登记与授予，角色选择/调用还有各自条件。AI 不改 grantedPermissions 或绕开审批来装测。
- 通知桥依宿主场景与 Android 通知条件工作；`notify` 权限在当前权限表仍标未实现，不能用 Notification.permission='granted' 占位值证明系统通知权限已取得。
- UNSUPPORTED/NOT_SUPPORTED、未知能力、缺依赖、PERMISSION_DENIED 均应检查并如实报告。别用空结果当成功、换别的窗口冒充目标 UI，或用 stub 冒充设备原生能力。
- QuickJS 与 App 同进程，是能力调用边界与超时控制，不是内核隔离。长任务避免同步死循环；不运行 npm lifecycle、不加载 DEX，不假设 Electron/桌面 helper 可运行。

## 交付前最小检查

- 工具：正确参数、缺参数/错误参数、真实 execute；注册不等于执行通过，工具数为零的页面/角色包也可能正常。
- 权限：未授权时确实拒绝；已授权能力按预期；错误和超时可读，日志无凭据。试运行也会跑 apply，确认 apply 无安装/发网/删文件副作用。
- 生命周期：停用/重载后工具与角色撤下，cleanup 可用，重启/历史重放不重复产生外部副作用；升级保留可回退包。
- UI：窄屏/宽屏、横竖屏、深浅色、中文长文本、键盘遮挡、可滚动/可点击、状态和错误可见；主界面保留停止与恢复原生壳入口，未授权审批和提问按真实用户操作处理。
- 加载页：真实 registerLoading → list/test 元数据 → 安装选择 → 进程冷启动所选快照首 draw → 必需角色就绪 → 主界面；另验停用/坏声明/坏快照与失败恢复，记录每段耗时，不以静态登记通过宣称冷启动已验。
- 分别报告「源码自检」「试运行」「安装启用」「实际功能/页面验证」完成程度；没执行的步骤不能写通过。

源码核对入口（相对原生仓库根）：`core/plugins/.../PluginPackage.kt`、`PluginModule.kt`、`PluginSandbox.kt`、`PluginPermissions.kt`、`PluginServices.kt` 中 PluginCapabilities、`SurfaceSpec.kt`、`LoadingSpec.kt`、`PluginClientEntry.kt`；加载快照 `app/.../data/LoadingSnapshotStore.kt`，启动闸门与恢复 `app/.../ui/boot/BootHost.kt`、`BootLoadingScreen.kt`、`BootRecoveryPanel.kt`，常驻调度 `app/.../data/PluginResidentRuntime.kt`，页面桥 `app/src/main/assets/client-host/host.js`，整壳桥 `app/.../ui/surface/WebViewShellScreen.kt`。注释/旧技能与实际 dispatch 不一致时读实现确认，不能凭宽松 shim 推断能力。

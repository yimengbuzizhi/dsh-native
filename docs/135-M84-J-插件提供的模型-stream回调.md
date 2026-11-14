# 135 · M84-J 插件提供的模型（`llm.register` 的 `stream` 回调）

> 2026-11-13 21:1x（Asia/Shanghai）｜开发版仍是 **0.3.2 / versionCode 5**，本批未发布。
> 承接 `docs/133`/`docs/134` 的常驻 JS 运行时：这一片让插件**自己产出模型回合**，不再只能登记端点/key。
> 能力清单 `PluginCapabilities.VERSION = m84.5`，27 条里 **26 条 implemented**，仍 unsupported 的只剩 `ui.view`。

---

## 一、做了什么

| 位置 | 变更 |
|---|---|
| `PluginModule` PRELUDE | `ctx.llm.register({..., stream})` 里 `stream` 是函数时记 `hasStream:true` 并把 provider 存进 `__dshStreamProviders[id]`；无效 id 照旧记 unsupported。 |
| `PluginModule.RESIDENT_DISPATCH` | 新 `llm.stream`：`{id, request}` → 调 `provider.stream(request)`，**支持返回 Promise**（native 的 dispatch 本来就会等 thenable 结算）；返回 `string` / `{text}` 归一成 `{ok:true,text,...}`。 |
| `PluginLlmProvider` / `plugins.json` | 新增 `hasStream`（试运行元数据 + 持久化 + `dsh_dump_providers` 也写）。 |
| `core:llm/JsCallbackLlmProvider` | 把插件返回值包成最小流：`MessageStart → BlockStart(text) → TextDelta → BlockStop → MessageDelta(end_turn) → MessageStop`；异常/超时/非 JSON 归一成 `LlmEvent.Failed(code=PLUGIN_STREAM_ERROR)`。 |
| `PluginResidentRuntime` | 起常驻 runtime 的条件再加上「有 `stream` 供应商」；新增 `callProvider(pluginId, providerId, requestJson, 180s)`；dump 增加每个插件的 `streams`；**重装/重载会重启 runtime**（见下）。 |
| `AppViewModel.syncPluginLlmProviders` | `hasStream` 的 provider 注册成 `JsCallbackLlmProvider`（`call` 把请求 dispatch 给常驻运行时），其余仍是配置式 `ConfiguredLlmProvider`；停用插件时一起收回。 |
| 插件详情 UI | 供应商行新增「自带 stream（模型由插件产出）」，说明从「插件不能提供 stream」改成真实语义。 |

### 顺手修的生命周期 bug

以前常驻运行时的「保持」条件只看 `path + version`：**同版本重装插件**（`--es dsh_plugin_zip` 再导一次）或**点重载**
不会换掉旧闭包。现在：

- `sync` 比较 `installedAt`（重装会更新）→ 先 `stop` 再 `start`；
- `AppViewModel.reloadPlugin` 显式 `pluginResidentRuntime.invalidate(id)` 后再 `sync`。

真机验证：同一 zip 第二次导入时日志出现 `resident stopped plugin=m84j-demo` → `resident started ... streams=m84j.echo`。

### 协议

```text
host → JS  __dshDispatch('llm.stream', { id: 'm84j.echo', request: {…完整 Anthropic 请求体…} })
JS → host  { ok: true, text: '...' }        // 或 { ok:false, error }
```

- `request` 是 `ProtocolJson.encodeRequest(ApiRequest)` 的原文（system、messages、tools、model…全在）；
- 插件 `stream(request)` 可以是 `async`：里面 `await` 宿主的异步能力（fetch / timer）也能结算，
  因为 native 的 dispatch 等根 Promise；
- 宿主只消费 `text`：**不接** `toolCalls` / `thinking` / usage / 多块；一次请求一整块文本，不是逐 token 流。

---

## 二、真机验收（OPPO A55 5G，PEMM00，Android 13）

演示插件 `docs/m84j-demo-plugin`（`m84j-demo` 1.0.0）：`ctx.llm.register({ id:'m84j.echo', stream: async (request) => {...} })`，
从请求体里挑出真正的用户输入（跳过 runtime context / system-reminder），`await setTimeout(30)` 后返回
`M84J_STREAM_OK <用户输入>`；同插件另注册 `m84j_ping` 工具。

| # | 操作 | 证据 |
|---|---|---|
| 1 | 导入 `m84j-demo.zip` | `DshProvider: plugin stream provider id=m84j.echo plugin=m84j-demo`；`DshProjection: resident started ... streams=m84j.echo`（`docs/m84j-log.txt`） |
| 2 | 冷启 `--es dsh_llm_provider m84j.echo --es dsh_send HELLO-K` | `DshProvider: plugin stream provider id=m84j.echo model=deepseek-flash requestChars=71565`；插件 console `m84j request messages=1 tools=128`；`stream plugin=m84j-demo provider=m84j.echo chars=71`；聊天回复 **`M84J_STREAM_OK HELLO-K`**（截图 `135-01.png`）——请求体真的到了插件、回复真的来自插件 |
| 3 | `--es dsh_dump_providers` | `llm` 含 `m84j.echo`，`pluginProviders[0].hasStream=true`（`docs/m84j-1-providers.json`） |
| 4 | 热启 `--es dsh_plugin_off m84j-demo` + dump | `resident stopped`；`llm` 里 `m84j.echo` 消失、`pluginProviders=[]`（`docs/m84j-2-providers-off.json`） |
| 5 | 停用状态下再跑一轮（`--es dsh_send FALLBACK-J`，debug 通道仍指向 `m84j.echo`） | 正常回落到宿主协议：真实 DeepSeek 回复、`19177 in / 395 out`（截图 `135-02.png`），没有卡死 |
| 6 | 重新启用 + `--es dsh_llm_provider off` | `resident started ... streams=m84j.echo`；`llmActive=""`、`pluginProviders` 仍有 `m84j.echo`（`docs/m84j-3-providers-final.json`） |

单测：`:core:plugins:test`、`:core:llm:test` 通过（新增 `hasStream` 解析断言，provider 列表从 1 变 2）。

---

## 三、限制（照实说）

1. **只取 text**：插件返回的 `toolCalls` / thinking / usage 不会被消费；插件模型不能调宿主工具。
   真正的逐 token 流 / 工具回合要另做事件协议（下一片）。
2. **一次请求一整块**：`MessageStart → 一个 TextDelta → BlockStop`；大文本会一次性出现，不是逐字。
3. **超时 180s**：超过就 `dispatch` 中断并 `LlmEvent.Failed`；长任务插件要自己在这之内返回。
4. **没有并发隔离**：同一个插件的 stream 与投影/事件共用一条 JS 线程；stream 跑的时候事件会在队列里等。
5. **供应商选择**：仍靠 `--es dsh_llm_provider <id>` / C3 选择器；插件停用后 `llmActive`（debug 通道）值
   可能还是旧 id，但注册表里没有它，`LlmProviders.resolve` 会回落宿主协议（真机第 5 步已验）。
6. 未做：`ui.registerView`、独立插件页进抽屉/设置、投影 checkpoint、`operit-extended-http` 15s、
   M84-B 142 项重跑；本批未发版。

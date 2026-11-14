# 138 · M84-M 插件模型的工具回合与逐 token 流

> 2026-11-13 21:4x（Asia/Shanghai）｜开发版仍是 **0.3.2 / versionCode 5**，本批未发布。
> 承接 `docs/135`（J：插件自己产出模型回合，但只取 `text`）：这一片让插件模型的回答**能流**、
> **能调工具**——`stream(request, emit)` 的 `emit` 是逐块增量通道，返回值的 `toolCalls` 折成
> `tool_use` 块，走宿主原有工具执行/工具卡/tool_result 回传。
> 能力清单 `PluginCapabilities.VERSION = m84.6`，27 条里 **26 条 implemented**（仍 unsupported 的只剩非上游预留 `ui.view`）。

---

## 一、做了什么

| 位置 | 变更 |
|---|---|
| `PluginModule.RESIDENT_DISPATCH` | `llm.stream` 调 `provider.stream(request, emit)`；`emit(chunk)` 同步 `__dshHostJson('llm.emit', {callId, chunk})`，宿主拒绝就抛错（插件能感知）；返回值归一成 `{ok,text,toolCalls,stopReason,usage,model,emitted}`。 |
| `PluginResidentRuntime` | 每个常驻运行时一张 `callId → 接收器` 表；`callSync('llm.emit')` 在 JS 线程上直接回调（只入队，不阻塞）；`callProvider(..., onChunk)` 生成 `callId`、dispatch 前后登记/收回。日志加 `stream ... chars=… chunks=…`。 |
| `core:llm/JsCallbackLlmProvider` | 改成「工作线程 dispatch + 阻塞队列收块」：`call(requestJson, emit)` 在 IO 线程跑，Flow 侧 200ms 轮询队列即时 `emit(LlmEvent)`（用户点停止能在 200ms 内退出）。块协议见下；最终结果的 `toolCalls`/`stopReason`/`usage` 折成标准事件。 |
| `AppViewModel.syncPluginLlmProviders` | `call` 改成两参 lambda，把 `emit` 接到 `callProvider(onChunk = …)`。 |
| 插件详情 UI | 供应商说明改成真实语义：`emit` 逐块文本、`toolCalls` 走宿主工具回合。 |
| 能力清单 | `m84.5 → m84.6`；`llm.register` 的 note 更新（工具调用 + 逐块流）。 |

### 协议（插件 → 宿主）

```text
emit('文本')                                   → TextDelta（首次自动开 text 块）
emit({type:'text', text})                      → 同上
emit({type:'thinking', text}) / {type:'signature', signature}
emit({type:'tool_use', id, name, input})       → BlockStart(tool_use) + ToolInputDelta(整块 JSON) + BlockStop
emit({type:'usage', input, output}) / {type:'stop', reason}
返回 { text, toolCalls:[{id,name,input}], stopReason, usage }
```

- `stopReason` 缺省按「有工具块 → `tool_use`，否则 `end_turn`」推断；
- 返回值里的 `text` 与已流文本**不重复渲染**：没流过就整块补，流过前缀只补后缀；
- `MessageStart` 不再一上来就发，等第一块（或最终结果）才发，这样插件先 `emit` usage 时 `inputTokens` 是对的；
- 工具参数**一次给全**（不是 `input_json_delta` 逐字）；文本是真逐块。

### 为什么不用再改 C 侧

`__dshCallSync`（`js_bridge.cpp` 的 `HostCallSync`）本来就在 JS 线程同步调 Kotlin；常驻运行时 `dispatch`
阻塞等结算期间，插件 `emit` 走这条已有通道回到宿主，所以不需要新的 JNI 入口、也不需要回调 ID 表
（`callId` 只是防并发串台）。

---

## 二、真机验收（OPPO A55 5G，PEMM00，Android 13）

演示插件 `docs/m84m-demo-plugin`（`m84m-demo` 1.0.0）：`m84m.agent` 供应商 + `m84m_ping` 工具。
第一轮 `emit {usage}` + `emit('正在调用 ')` + `emit('m84m_ping…')`，返回一个 `m84m_ping` 的工具调用；
第二轮（带 `tool_result`）逐 6 字 `emit` 最终答复，并返回同一句 `text`。

| # | 操作 | 证据 |
|---|---|---|
| 1 | 导入 `m84m-demo.zip`（`--es dsh_plugin_zip`） | `Debug import ok=true 已导入 m84m-demo 1.0.0；注册 1 个工具：m84m_ping`；`DshProjection: resident started plugin=m84m-demo ... streams=m84m.agent` |
| 2 | 冷启 `--es dsh_llm_provider m84m.agent --es dsh_send M84M` | 第一轮：`m84m request messages=1 tools=129 toolResult=no` → `stream ... chars=179 chunks=3`；随后 `tool/call`、`tool/result`；第二轮：`m84m request messages=3 tools=129 toolResult=yes` → `stream ... chars=156 chunks=8`（截图 `138-01/138-02`） |
| 3 | 聊天界面 | 工具卡 **`m84m_ping {"text":"M84M"}` 绿勾**；流式文本「正在调用 m84m_ping…」；最终 **`M84M_TOOL_OK pong-m84m:M84M REQ=M84M`**；用量 `40 in / 36 out`（插件给的 usage + 流式长度）——证明 `tool_result` 真的回到插件、第二轮文本真的由插件产出 |
| 4 | 重装后再跑一轮（`M84M2`，验证 chunks 日志） | 第一轮 `chunks=3`、第二轮 `chunks=8`；`M84M_TOOL_OK pong-m84m:M84M2 REQ=M84M2`（截图 `138-03/138-04`） |
| 5 | `--es dsh_dump_providers` | `llm` 含 `m84m.agent`；`pluginProviders` 两条 `hasStream=true`（`docs/m84m-providers.json`） |
| 6 | `--es dsh_dump_tools` | `m84m_ping` 在 `all` 与 `offered` 里（`docs/m84m-tools.json`），请求里 `tools=129` 与宿主一致 |
| 7 | `--es dsh_plugin_off m84m-demo` + dump | `plugin providers=[m84j.echo]`、`resident stopped plugin=m84m-demo`；`m84m.agent` 从 `llm` 与 `pluginProviders` 消失（`docs/m84m-providers-off.json`） |
| 8 | `--es dsh_plugin_on m84m-demo --es dsh_llm_provider off` | 恢复：`resident started ... streams=m84m.agent`、`active=null`、无障碍 Bound |

单测：`:core:llm:test` 新增 `JsCallbackLlmProviderTest`（9 条：老式 `{text}`、逐块流 + 后缀补齐、
不重复整块、结果 `toolCalls` 折块、`emit` 工具块先关文本块、字符串/usage 块、失败信封、非 JSON/抛异常、
请求体原样到达）；`:core:llm:test`、`:core:plugins:test` 全绿。

---

## 三、限制（照实说）

1. **工具参数一次给全**：没有 `input_json_delta` 逐字；大参数（>10KB）会整块出现。
2. **thinking 块**能流（`emit({type:'thinking'})`），但**签名/工具结果**之外的块宿主不额外校验；
   插件返回的 `thinking` 不参与「签名回传」的严格语义。
3. **一次请求一个 provider 调用**：`stream` 与投影/事件共用同一条 JS 线程，流式期间事件排队（沿用 J 的限制）；
   并发两次 `stream`（同插件）会串行，`callId` 保证 `emit` 不串台。
4. **取消**：用户点停止时 Flow 200ms 内退出，但插件侧 JS 仍在跑，直到它自己返回或 180s 超时
   （宿主没有取消 JS 的手段）。
5. **超时 180s**：超时归一成 `LlmEvent.Failed(code=PLUGIN_STREAM_ERROR)`，不卡回合。
6. 未做：`ui.registerView`（非上游预留）、投影 checkpoint、`operit-extended-http` 15s 复现、
   M84-B 142 项在 M 之后重跑（下一片）；本批未发版。

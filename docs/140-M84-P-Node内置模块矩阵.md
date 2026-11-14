# 140 · M84-P Node 内置模块矩阵（真实插件实跑）

> 2026-11-13 22:0x（Asia/Shanghai）｜开发版仍是 **0.3.2 / versionCode 5**，本批未发布。
> 补的是「外部插件用 Node 生态写法」的兼容面：`path / fs / os / Buffer / events / timers /
> child_process / http`。用**真实导入插件 + 真实工具调用**在真机上跑，不用模拟。
> 能力清单 `PluginCapabilities.VERSION = m84.6`（27 条 26 implemented）不变。

---

## 一、做了什么

| 位置 | 变更 |
|---|---|
| `PluginModule` PRELUDE | 新增 **`Buffer`**（UTF-8 / hex / base64 / latin1；`from` / `alloc` / `concat` / `byteLength` / `isBuffer` / `toString` / `equals` / `toJSON`）；新增 **`EventEmitter`**（`on` / `addListener` / `prependListener` / `once` / `off` / `removeListener` / `removeAllListeners` / `emit` / `listenerCount` / `listeners` / `setMaxListeners`）与 `events.once`；`node:buffer` / `node:events` / `node:timers` 三个模块入口。 |
| `PluginModule` HOST_BRIDGE | `globalThis.Buffer` 暴露给插件（`process` / `console` / `fetch` 同一个地方）。 |
| 能力面 | `path`（join/resolve/dirname/basename/sep）、`fs`（同步 + `promises`）、`os`、`node:https/http` 的 `get/request`、`child_process`（execSync/exec，`spawn` 仍抛错）此前已有；这一片补齐 `Buffer` / `events` / `timers` 并把整张矩阵**跑实**。 |
| 新工具 | `tools/check-plugin-js.py`：把 `PluginModule.kt` 里的 `PRELUDE / HOST_BRIDGE / RESIDENT_DISPATCH` 抽出来交给 `node --check`（改 JS 前后先跑，避免要装机才发现语法错）。 |

---

## 二、真机验收（OPPO A55 5G，PEMM00，Android 13）

演示插件 `docs/m84p-node-matrix-plugin`（`m84p-node-matrix` 1.0.0，工具 `m84p_node_matrix`，
权限 `fs:workspace` / `shell` / `net:example.com`）。工具里 `import path/fs/os/Buffer/EventEmitter/
setTimeout/execSync`，跑 22 条断言，结果当工具结果返回。

| 步骤 | 证据 |
|---|---|
| `--es dsh_plugin_zip …` 导入 | `debug import ok=true 已导入 m84p-node-matrix 1.0.0；注册 1 个工具：m84p_node_matrix`（`import` 就说明 PRELUDE/HOST_BRIDGE 在 QuickJS 里解析通过） |
| `--es dsh_plugin_grant m84p-node-matrix` | `已允许 m84p-node-matrix：fs:workspace、shell、net:example.com` |
| `--es dsh_plugin_call m84p_node_matrix` | `isError=false text=M84P_NODE_MATRIX 22/22`（`docs/m84p-node-matrix.txt` 全文） |

22 条全部 PASS（节选原样）：

```text
PASS path.join :: a/b/c.txt            PASS os.platform :: android
PASS path.dirname :: /x/y              PASS os.homedir :: /data/user/0/xin.dshaa.nativeapp/files/workspace
PASS fs.writeFileSync/readFileSync :: matrix-矩阵-123
PASS fs.promises :: async-ok           PASS Buffer.from utf8→hex :: e9b2b8e6ad8c
PASS fs.readdirSync :: entries=7       PASS Buffer base64 往返 :: 鲸歌
PASS Buffer.byteLength :: 6            PASS Buffer.alloc/concat/equals :: a=AAA joined=AAAAA
PASS 全局 Buffer :: 78                 PASS EventEmitter :: on:1|once:1|on:2 count=1
PASS node:timers setTimeout :: elapsed=32ms
PASS child_process.execSync :: m84p-node
PASS fetch https://example.com :: status=200 bytes=577
```

（`tests` 里另跑 `:core:plugins:test` 全绿；JS 语法用 `tools/check-plugin-js.py` 预检 `OK`。）

---

## 三、兼容面与限制（照实说）

| 模块 | 支持 | 限制 |
|---|---|---|
| `node:path` | join / resolve / dirname / basename / sep | 纯字符串拼接，不做 `..` 归一；`resolve` 不认绝对路径语义 |
| `node:fs` | 同步读写/存在/列目录/stat/mkdir/rm/rename/copy + `promises` | **只处理文本**；给 `Buffer` 会被 `String()` 成 `[object Object]`；没有 `createReadStream` |
| `node:os` | platform / homedir / tmpdir / EOL | 没有 cpus/networkInterfaces |
| `node:buffer` + 全局 `Buffer` | from / alloc / concat / byteLength / isBuffer / toString / equals / toJSON（UTF-8、hex、base64、latin1） | 不接 `ArrayBuffer`/TypedArray；不支持 `'ucs2'`/`'utf16le'`；`write*` 系列没有 |
| `node:events` | EventEmitter 常用方法 + `events.once` | 没有 `captureRejections`、`error` 事件特判、`prependOnceListener` |
| `node:timers` | setTimeout / clearTimeout / setInterval / clearInterval / setImmediate | 走宿主 `timer.wait`（单线程，长 delay 不精确）；`setInterval` 上限 1000 次 |
| `node:child_process` | execSync / exec（需 shell 权限） | `spawn` 仍抛「暂不支持」 |
| `node:https` / `node:http` | `get/request` 最小回调式 | body 一次给完，不流式 |
| 全局 `fetch` | `{status, ok, headers, body}` | 同一套域名白名单（`net:*` / `net:host`） |

未做：`node:crypto` / `node:url` / `node:stream` / `require('fs').createReadStream` —— 用到再补（不假装有）。

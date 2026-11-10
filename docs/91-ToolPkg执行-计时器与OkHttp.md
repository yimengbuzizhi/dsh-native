# 91 · Operit ToolPkg 执行打通（QuickJS 计时器 + OkHttp body，M63 收尾）

> 时间：2026-11-10 01:2x（Asia/Shanghai）
> 设备：OPPO A55 5G（PEMM00，720×1600，Android 13）
> 上接：HANDOFF §9（M63 元数据解析已通，`get_time` 执行待验）

## 一、问题

M63 上一棒把 Operit 方言的 METADATA 解析修好后，`operit-time` 能注册出
`toolpkg_status / get_time / format_time` 三个工具；但真正执行 `get_time` 时报：

```
DshPlugin: debug call name=get_time args= isError=true
ReferenceError: 'setTimeout' is not defined
  at <anonymous> (time.js:904:48)
```

Operit 包体不是 DSH 插件：它们在 QuickJS 全局环境里直接用 `setTimeout`、`OkHttp`、
`Tools.*`。鲸歌的 PRELUDE 里没有计时器；OkHttp shim 有，但拿 `fetch` 的响应时
`response.body` 是 `undefined`。

## 二、修复 1：QuickJS 全局计时器

`core/plugins/PluginModule.kt` 的 PRELUDE 新增：

- `setTimeout(fn, delay, ...args)` / `clearTimeout(id)`
- `setInterval`（最多 1000 次，避免插件的死循环拖住工具线程）/ `clearInterval`
- `setImmediate` / `clearImmediate`

实现借已有的异步宿主通道（和 `net.fetch` 同一条）：

```js
__dshCallAsync('timer.wait', JSON.stringify({ ms: wait })).then(fire, ...)
```

`core/plugins/PluginSandbox.kt` 新增 `timer.wait`：在插件宿主线程池里
`Thread.sleep(ms)`（上限 10 分钟），完成后回灌 QuickJS 解析 Promise。
QuickJS runtime 本来就是「一次调用一个」，到点回调在同一个工具调用内完成。

## 三、修复 2：`fetch` 响应补 `body`

根因：`ToolPkg` 的 OkHttp shim（`ToolPkg.kt` 的 `__okhttp()`）按 Operit 的 API 取
`response.content`，而 shim 实现是 `content: response.body`；可 `PluginModule` 的
`__dshFetch` 只提供了标准 fetch 的 `text()/json()`，没有 `body` 字段 → `undefined`，
包内再 `.replace()` 就炸 `cannot read property 'replace' of undefined`。

修复：`__dshFetch` 的响应对象补 `body: r.body`（非标准字段，但 Operit 包依赖；
标准 fetch 用法不受影响）。

## 四、真机验证

### 4.1 `get_time` / `format_time`（修复 1 后）

```
adb shell am start -n xin.dshaa.nativeapp/.MainActivity \
  --es dsh_plugin_call get_time --es dsh_plugin_args_b64 e30=

I DshPlugin: debug call name=get_time args={} isError=false text={
  "timestamp": 1794244587362,
  "iso": "2026-11-09T17:16:27.362Z",
  "local": "11/10/2026, 01:16:27 AM",
  "date": { "year": 2026, "month": 11, "day": 10, "weekday": "11/10/2026" },
  "time": { "hours": 1, "minutes": 16, "seconds": 27 }
}

--es dsh_plugin_call format_time（无参数）
I DshPlugin: debug call name=format_time args= isError=false text={
  "iso": "2026-11-09T17:21:31.110Z",
  "date": "2026-11-10",
  "time24h": "01:21:31",
  "time12h": "01:21:31 AM"
}
```

### 4.2 `duckduckgo` 导入（权限 `net:any`）

```
adb push operit-duckduckgo.zip /sdcard/Download/
--es dsh_plugin_zip /sdcard/Download/operit-duckduckgo.zip
--es dsh_plugin_grant all     # 第一次要单独跑一次，让插件记录里出现后再授权

I DshPlugin: toolpkg wrapped id=operit-duckduckgo
I DshPlugin: debug import ok=true 已导入 operit-duckduckgo 1.0.0；注册 3 个工具：toolpkg_status、search、fetch_content
I DshPlugin: debug grant target=all 已允许 operit-duckduckgo：net:any | ...
```

### 4.3 `fetch_content`（修复 2 后，真联网）

```
--es dsh_plugin_call fetch_content --es dsh_plugin_args_b64 eyJ1cmwiOiJodHRwczovL2V4YW1wbGUuY29tIn0=

I DshPlugin: plugin operit-duckduckgo/fetch_content [info] 正在抓取内容: https://example.com
I DshPlugin: plugin operit-duckduckgo/fetch_content [info] 成功抓取并解析内容 (171 字符)
I DshPlugin: debug call name=fetch_content args={"url":"https://example.com"} isError=false
  text=Example Domain This domain is for use in documentation examples ...
```

### 4.4 `search`（链路通，但目标域名在本网络不可达）

```
I DshPlugin: plugin operit-duckduckgo/search [info] 正在从DuckDuckGo搜索: DeepSeek
E DshPlugin: plugin operit-duckduckgo/search [error] 函数 search 执行失败!
  错误: failed to connect to html.duckduckgo.com/66.220.148.145 (port 443)
        from /192.0.2.11 (port 43274) after 10000ms
```

→ 权限门、OkHttp shim、宿主网络通道都走到了，失败在「设备网络到
`html.duckduckgo.com` 连不上」（国内网络）。**不是代码没接**；换可达网络/镜像域名可再验。

## 五、本批没做 / 限制

- `Tools.*`（system_tools / code_runner / browser…）仍会抛
  「这个 Operit 包依赖 Tools.xxx，鲸歌暂不支持」；要写兼容层或逐包改写。
- `setInterval` 上限 1000 次是保守值；如果包真的常驻轮询，需要改成「宿主事件循环」而不是
  在工具调用内等待。
- 计时器会占用插件宿主线程池的一条线程；缓存线程池，不会卡住主线。
- DuckDuckGo 搜索只在设备网络不可达，未换网络复验。

## 六、下一步

- 换可达网络或换镜像域名验 `search` 结果解析。
- `time` 包的 `get_time` / `format_time` 都已单独调过；crossref / various_search 等同样只依赖
  OkHttp 的包可以批量试。
- `Tools.*` 兼容层另立一批（先读全 `assets/packages/*.js` 的 Tools 调用面）。

# 168 · M85-F2：WebView 壳后端（shell/v1 的第三种后端）

> 2026-11-14 09:2x。按 `docs/144` §5/§8 的 M85-F 第二项做 **WebView 壳后端**：
> 整屏 WebView + 与 Surface 同一套 `shell.mount/state/action/event` 与 `surface.command` 桥，
> 第三方插件可提供；原生壳（默认）与 Surface 壳（可选）不动。
> 真机：OPPO A55 5G（PEMM00 / 720×1600 / Android 13）；本批未发布（0.3.2 / versionCode 5）。
> 演示插件：`docs/m85f-webview-shell/`。

---

## 1. 结论

1. **第三种后端成立**：插件用 `ctx.ui.registerShell({..., backend:'webview'})` 声明壳，
   `shell.mount/state/action/event` 返回 `{html}`（整屏 HTML 片段）而不是 Surface 树；
   宿主用 `WebViewShellScreen` 渲染，仍是 `shell/v1` 的同一个壳宿主（`PluginShellHost`），
   超时/连续失败回退原生壳的逻辑不变。
2. **同一套命令桥**：WebView 页面里的按钮/输入通过 `DshWebShell.action(...)` 回到宿主
   `shell.action`；发消息/停止/新会话/切会话/回原生仍走 `surface.command` 白名单
   （`send/stop/newSession/openSession/closeShell`…），插件拿不到其它宿主方法。
3. **真机闭环**：WebView 壳显示会话条/消息/输入框；发消息出现流式回复（500ms 整段重绘）、
   停止键生效、点「原生」回到原界面且会话内容连续（无残留）。
4. **失败可回退**：mount 连续失败 3 次自动回原生壳 + Toast（本片顺手修了两个真机踩到的宿主 bug，
   见 §2.3）。
5. **M84-B 142/142 PASS**（M85-F1+F2 最终 APK，见 §4）。

---

## 2. 实现

### 2.1 契约与插件侧

- `PluginShell` 新增 `backend: String = "surface"`（`surface` / `webview`），进
  `plugins.json` 持久化与试运行解析；未知后端按 `surface`（不猜新协议）。
- `ctx.ui.registerShell({id,label,backend:'webview',mount,onState,onAction,onEvent})`：
  mount 仍必需（返回 html 片段）；`__dshShellDispatch` 识别 `{title?,html}` 原样返回，
  Surface 壳仍包成 `{tree}`。
- 调试 dump `dsh_shell_list` 的 `shells[]` 新增 `backend`。

### 2.2 宿主渲染

- `PluginShellHost`：新增 `html` 状态与 `actionRaw(action,payloadJson)`；
  `start/requestState/action/event` 都按后端判断；`applied` 按后端校验 tree/html；
  `Target` 带 `backend`。
- `WebViewShellScreen`（新）：
  - 固定外壳文档（`#dsh-root` + 基础样式 + 桥），插件 HTML 片段塞进 `#dsh-root`；
  - 设置 `javaScriptEnabled/domStorageEnabled`，`allowFileAccess=false`、禁止外部导航、
    `blockNetworkLoads=true`（插件 HTML 不能绕过 `net` 权限直接加载远程资源）；
  - `addJavascriptInterface(dshNative)` 只暴露 `command(name,args,id)` 与 `action(action,value)`；
  - 结构变化仍按 `shellSignature` dispatch `shell.state`；流式期间每 500ms 推一次
    （WebView 没有 Surface 的 `bind`，v1 是整段重绘）。
- `DshApp`：`surfaceShell.backend == "webview"` → `WebViewShellScreen`，否则原 `PluginShellScreen`；
  onboarding 仍由 native 壳提供。
- `shellStateJson` 的消息新增 `sourceKind`（WebView 壳可像原生/Surface 一样过滤内部注入消息）。

### 2.3 真机踩到并修掉的两个宿主 bug

1. **webview 返回被当成 Surface 树**：`__dshShellDispatch` 把 `{title,html}` 包成 `{tree}`，
   宿主找不到 `html`，mount 一直判失败。已按 `html` 字段分流。
2. **mount 失败无限重试**：`PluginShellHost.start` 每次重试都把 `failures` 清零，
   连续失败永远到不了 3 次回退；已改成「只有换目标才清零」。
3. **同一壳重复下发重置空白**：`setActiveShell` 对同一个 target 再次调用会 `reset()` 掉已 mount 的壳
   （真机热启 intent 重投时表现为 WebView 有画面、Compose 却回到 loading）。
   已改成同 target 直接返回。

---

## 3. 真机验收

| # | 场景 | 操作 | 结果 | 证据 |
|---|---|---|---|---|
| 1 | 导入 WebView 壳 | `--es dsh_plugin_zip /sdcard/Download/m85f-webview-shell.zip`（冷启） | 已导入、`ui` 权限自动授予、`shells=m85f.webview` 常驻运行时起来 | logcat `resident started plugin=m85f.webview-shell ... shells=m85f.webview` |
| 2 | 切到 WebView 壳 | `--es dsh_shell m85f.webview-shell`（热启） | `shell resolved=m85f.webview-shell:m85f.webview`；`shell.mount COMPLETED`；整屏是插件 HTML 界面（标题/会话条/输入框/发送） | `docs/shots/m85f-10-webview-shell.png`、`docs/m85f-shells.json`（`backend=webview`） |
| 3 | 发消息 + 流式 | 点输入框输入 `hello` → 发送 | `raw action=send` → `shell.action` → 流式回复每 ~500ms `shell.state` 重绘；页面自动滚到底 | `docs/shots/m85f-11-webview-send.png`、`docs/m85f-webview-logcat.txt` |
| 4 | 停止 | 发送长任务后点「停止」 | `raw action=stop` → 按钮从「停止」回「发送」，正文停在当前位置 | `docs/shots/m85f-13-webview-streaming.png`、`m85f-14-webview-stopped.png` |
| 5 | 切会话 | 顶部会话 chip 点 `M85E_model_blocked` | `shell.action openSession` → `surface.command openSession` → 当前 chip 高亮、消息列表/标题切换 | `docs/shots/m85f-17-webview-sessions.png`、`m85f-18-webview-open-session.png`、logcat |
| 6 | 回原生 | 点顶部「原生」 | `surface.command closeShell` → `set active=native` → 原界面恢复，同一会话内容连续（无残留） | `docs/shots/m85f-16-native-fallback.png`、logcat |
| 7 | 停用 WebView 壳 | `dsh_plugin_off m85f.webview-shell` | 解析回 null → 原生界面；插件停用后不再出现在 shell 候选 | `docs/m85f-shells.json` |
| 8 | 无崩溃 / 无障碍 | 全程 | 无 `FATAL EXCEPTION` / `ANR in`；无障碍 Bound | logcat / dumpsys |

---

## 4. M84-B 142/142（M85-F1+F2 最终 APK）

- 脚本 `tools/m84b-page-check.py`，只改 `OUT`（`docs/m84b-check-m85f.json`），142 条断言未改；
- 结果：**142/142 PASS**，time `2026-11-14 09:29:28`，原始 log `docs/m85f-f2-142.log`；
- 本片不改页面归属/开关，142 作为等价回归。

---

## 5. 文件

- 新增：`app/ui/surface/WebViewShellScreen.kt`、`docs/m85f-webview-shell/{package.json,index.js}`
- 修改：`core/plugins/{PluginModel,PluginModule}.kt`（backend / html 分流）、
  `app/data/PluginShellHost.kt`（html/actionRaw/失败计数修复）、
  `app/chat/AppViewModel.kt`（Target.backend、dump backend、`setActiveShell` 同 target 保护、
  state 消息 `sourceKind`）、`app/ui/App.kt`（webview 分支）、
  `app/ui/surface/PluginShellScreen.kt`（`shellSignature` 改 internal 复用）
- 证据：`docs/m85f-shells.json`、`docs/m85f-webview-logcat.txt`、`docs/shots/m85f-1*.png`

---

## 6. 没做 / 限制（不许当成已完成）

1. **没有 Surface 的 `bind` 等价物**：流式每 500ms 整段重绘 HTML，滚动/输入焦点在流式中会被重置；
   长会话开销比 Surface 大。v1 只用于演示「第三种后端 + 命令桥」，不是默认壳。
2. **页面资源只能用内联 HTML/CSS/JS**：`allowFileAccess=false`、`blockNetworkLoads=true`；
   插件想加载远程资源必须通过 `ctx.http`（net 权限）自己取，不能借 WebView 绕过。
3. **Surface 原语的 `webview.url` 仍不开放**（只支持 `html`）：开放远程 URL 会绕过 `net` 权限，
   本片只做整壳后端；在协议层保持「不放开」比偷偷放开安全。
4. **`shell.event` 已接线但演示没用到**；WebView 的 `action` 只回传 `value`（没有 Surface 的
   row/index 语义）。
5. 未发布；发布树/tag/Release 一律未动。

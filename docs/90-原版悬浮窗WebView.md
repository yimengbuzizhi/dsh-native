# 90 · 3D 鲸鱼娘改跑插件原版窗口（透明 WebView，M64）

> 时间：2026-11-10 01:1x（Asia/Shanghai）
> 设备：OPPO A55 5G（PEMM00，720×1600，Android 13，density 320）
> 上接：docs/89（M62 原生 Canvas 复刻——**用户明确否决，本批替换**）
> 上游：`meto-ventus/dsh-ventus-whale` @ `c439211`（2026-08-24，jsDelivr `@main`）

## 一、用户要求（原话）

> 「那个悬浮窗，它改造的和我预期的根本不是一样子 我要的是完完全全的把那个悬浮窗给移过来
> 不是自己把那个悬浮窗再画一遍」

M62 的 `WhalePetService` 是**鲸歌自己用 Canvas 重画的低多边形虎鲸**，只借了状态机，用户不认。
本批按「原版窗口」重做：**窗口里的 3D 模型、材质、动画、爱心、工具栏、改名/重置/拖动按钮、
按钮样式与排版，全部是插件上游代码**；鲸歌只提供宿主能力（窗口生命周期、位置、状态文案）。

## 二、为什么是 WebView，而不是继续原生

上游窗口是浏览器里的 three.js WebGL 浮窗：

| 上游事实 | Android 的现实 |
| --- | --- |
| `dsh.client.platform = web`，`./client → lib/client.js`（477KB） | bundle 依赖 DSH client runtime、`dsh-client-ui-settings/slots`、React 外部依赖；没有 DSH Web 宿主跑不起来 |
| `WhalePet.tsx`（495 行）把窗口 portal 到 `document.body`，three.js 画在 canvas | 需要一个真正的浏览器环境 |
| `whale-model.ts`（1305 行）：SVG 轮廓采样成扫掠体、尾鳍/背鳍/胸鳍、眼白/瞳孔、爱心 sprite、游动/眨眼/悬浮动画 | 不是「一张贴图」，原生重画必然失真 |

结论：**SYSTEM_ALERT_WINDOW 里放透明 WebView，页面就是上游 React 组件 + three.js 模型**。
这不是「再画一遍」，是把插件的窗口原样搬进 WebView。

## 三、实现

### 3.1 页面（`web/ventus-whale/`）

- `src/` 原样保存上游源码（从 jsDelivr 取）：
  `WhalePet.tsx`、`whale-model.ts`、`whale-pet.module.css`、`whale-config-store.ts`、`protocol.ts`；
- `src/entry.tsx`：把 `WhalePet` 挂到 `#root`，并用 `window.DSHNative` 实现上游同一个
  `WhaleApi`（`state()` / `update(patch)`），替换上游的 `/api/ventus-whale/*` HTTP 接口；
- `build.mjs`：esbuild → IIFE，CSS module 用同名字符串映射（selectors 原样），依赖
  `three@0.147.0`（上游 peer 版本）、`react@18.3.1`；
- 产物 `app/src/main/assets/whale/{index.html, whale.js(591KB), pet.css}`。

桥（`addJavascriptInterface(...,"DSHNative")`）：

| 方向 | 方法 | 用途 |
| --- | --- | --- |
| JS→native | `state()` / `update(patchJson)` | 上游配置读写（caption 持久化为 `whale3d_name`） |
| JS→native | `setEditable(bool)` | 改名输入框聚焦时临时放开 `FLAG_NOT_FOCUSABLE`，IME 才能弹 |
| JS→native | `setMoveMode(bool)` | 上游「拖动」模式 → native 接管单指拖窗口 |
| JS→native | `log` / `ready` | logcat `DshWhaleWeb`，页面就绪后推配置/状态 |
| native→JS | `__ventusApplyConfig(json)` | 整份 `WhaleConfig` 广播（上游监听 `ventus-whale:config`） |
| native→JS | `__ventusSetStatus(text)` | 宿主的鲸鱼状态胶囊（插件本身没有状态字，这是鲸歌加的） |

### 3.2 悬浮窗（`fish/WhalePetService.kt`）

- `TYPE_APPLICATION_OVERLAY` + `FLAG_HARDWARE_ACCELERATED` + `PixelFormat.TRANSLUCENT`，
  透明 WebView 硬件层加载 `file:///android_asset/whale/index.html`；
- 窗口尺寸 = `176dp × 角色大小` + 顶部 112dp（工具栏/状态胶囊）/ 左右 32dp / 底部 36dp；
  `config.x/y` 传窗口内边距（CSS px = dp），`config.scale = 1`（窗口已放大，避免二次缩放）；
- 单指手势全部交给页面（拖拽旋转、点按爱心、双击跳、工具栏）；
- 第二根手指 / 上游「拖动」模式下的单指拖动 → native 移动整个窗口，松手写
  `whale3d_x/whale3d_y`；「拖动」结束后自动点回上游「退出拖动」按钮；
- 长按 650ms 收起（写 `whale3d_enabled=false` + Toast）；
- 前台收起 / 退后台出现沿用 M59 的 `DshApplication` 优先级：3D 鲸鱼娘 > 大肥鱼 > 悬浮球；
- 状态：`BigFish.state` → 顶部胶囊「思考中… / 需要你点一下 / 忙起来了… / 这轮做完了！/ 出错了」，
  不参与 3D 绘制。

## 四、真机证据

| 文件 | 内容 |
| --- | --- |
| `docs/shots/89-04-whale-webview.png` | 桌面上的**原版 three.js 鲸鱼**（透明背景，DeepSeek 蓝，非 Canvas 网格） |
| `89-05-whale-webview-thinking.png` | 状态胶囊「思考中…」（BigFish thinking） |
| `89-06-whale-webview-tap.png` | 点按后出现**上游两行工具栏**（改名/重置/拖动 + 爱心开/转圈）+ 爱心 |
| `89-07-whale-webview-toolbar.png` | 触屏点按后工具栏常驻 |
| `89-08-whale-webview-rotate.png` | 单指拖拽后模型旋转（真实 three.js 旋转） |
| `89-09-whale-webview-rename.png` | 点「改名」→ IME 弹出（窗口临时可聚焦） |
| `89-10-whale-webview-renamed.png` | 输入 `XiaoJing` 回车 → 上游 caption 显示；prefs `whale3d_name=XiaoJing` |
| `89-12-whale-webview-moved.png` | 上游「拖动」模式：窗口从 (288,451) 移到 (202,375)，鲸鱼/工具栏/caption 一起移动 |
| `89-13-whale-webview-flip.png` | 点「转圈」抓到翻转中（白肚朝上，上游 somersault 动画） |
| `89-15-whale-webview-collapsed.png` | 长按收起（prefs `whale3d_enabled=false`） |
| `89-16-whale-webview-restored.png` | 重新打开：位置 (202,375) 与名字 XiaoJing 都恢复 |
| `89-17-whale-hidden-foreground.png` | 回前台自动收起（只剩鲸歌聊天页） |
| `89-18-whale-webview-home-again.png` | 再回桌面重新出现 |
| `89-20-ventus-detail-webview.png` | 插件详情：「界面扩展」明确写 WebView 原版窗口，不是原生替身 |
| `89-22-ventus-detail-original-window.png` | 收紧映射后的详情标题「原版窗口已接入：dsh-ventus-whale」 |
| `89-21-whale-final.png` / `89-23-whale-final-mapping.png` | 最终装机版本：原版模型 + 名字 caption + 保存的位置 |

logcat（`DshWhale3D` / `DshWhaleWeb`）：

```
overlay 416x648 at 288,451 pet=176.0dp scale=1.0
DshWhaleWeb: ventus whale mounted
web page ready (bridge)
page finished: file:///android_asset/whale/index.html
move mode = true
move mode = false
```

WebView DevTools 探针（`adb forward tcp:9223 localabstract:webview_devtools_remote_<pid>`，CDP `Runtime.evaluate`）：

```
title: Ventus Whale
href: file:///android_asset/whale/index.html
canvasCount: 1
canvasRect: x=16 y=112 176x176
webgl: Mali-G57 MC2
toolbar: 改名|重置|拖动|爱心开|转圈
status: 思考中…
bodyBg: rgba(0,0,0,0)
```

→ 页面里是插件真实的 React 组件与 WebGL canvas，不是鲸歌画的位图/Canvas。

## 五、没做 / 限制（诚实标注）

- **没有直接执行 477KB 的 `lib/client.js` 本体**：它注册在 DSH client runtime 上，依赖
  settings slots / React 外部依赖。本批重打包的是同一份窗口源码（`WhalePet` + `whale-model`），
  窗口内容与交互即上游代码；插件设置页（`VentusSettingsPage`）与侧栏「召唤虎鲸」入口没有搬，
  由鲸歌设置页代替。
- 宿主映射只认 `dsh-ventus-whale`（包名含 `ventus`）：`dsh-plugin-whale`、`dsh-client-ui-pet`、
  `dsh-whale-girl-pet` 等**不再套用这只鲸鱼**（它们的模型/帧未随包分发，套用等于拿别的鲸鱼冒充）；
  没命中的插件按普通插件处理，详情页提示「界面不执行」。
- 上游窗口默认在浏览器视口右侧中部；鲸歌窗口位置由 native 管理（`whale3d_x/y`），
  「重置」按钮只重置模型旋转，不会把窗口移回右侧中部。
- 双指拖窗口沿用 M59/M62 的手势，adb 无法注入双指，未单独截图；它与已验证的「拖动」模式
  共用 `moveWindow()` / `savePosition()`。
- WebView 渲染进程内存未测；主题 token 用 DSH 深色值兜底（插件 CSS 引用的
  `line-normal/line-strong/sidebar-nav-item-hover` 在当前 DSH 版本没有导出）。
- 页面内没有 DSH 的 `ctx`，插件 host 侧（会话事件、余额等）与窗口无关，仍按 M59–M63 的宿主链路处理。

## 六、升级上游

见 `web/ventus-whale/README.md`：覆盖 `src/` 五个上游文件 → `node build.mjs` → 装机回归
（旋转/点按/工具栏/改名/拖动/转圈/长按）。npm 上 `dsh-ventus-whale` 是 404，以 GitHub
`meto-ventus/dsh-ventus-whale` 为准。

## 七、下一步

- 大肥鱼（`dsh-dafeiyu`）是否也值得走 WebView/素材帧路线：它的真实窗口是 Qt/PySide Helper，
  跑不了；素材包 242MB，只适合挑 1–2 组动作帧，先不动。
- 其它悬浮窗插件按同样流程评估：先找真实 client bundle / 模型 / CSS，再决定 WebView 还是原生替身。
- M63：已接通（`get_time` 返回真实时间；`duckduckgo/fetch_content` 真联网成功），
  计时器与 OkHttp body 的修复见 docs/91。

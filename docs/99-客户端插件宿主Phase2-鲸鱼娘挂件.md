# 99 · 客户端插件宿主 Phase 2：dsh-plugin-whale（鲸鱼娘挂件）真机跑通

> 时间：2026-11-10 22:2x–23:0x（Asia/Shanghai）
> 设备：OPPO A55 5G（PEMM00，720×1600，Android 13，无线调试 `192.0.2.10:41759`）
> 上游：`D:\dsh\upstream\dsh-plugin-whale`（Makesupper，MIT，`lib/client.js` 143,127 字节，逐字节原样导入）
> 用户要求（2026-11-10 22:1x，最高优先级）：**跑插件自己的 `dsh.client`**，不许再按插件 id 画原生窗口；
> 原生替身 M71 已全部删除，通用宿主是唯一入口。

---

## 一、做了什么

### 1. 真包导入（不是夹具）

设备上原来的 `files/plugins/dsh-plugin-whale` 是 474 字节夹具（`index.js` + `package.json`，没有 client）。
本批把**真实包装成 zip 导入**（`package.json` / `lib/` / `assets/`（8 个 WAV，1.5MB）/ `locale/` /
`icon.svg` / `cordis.patch.yml`，87 个文件、2.12MB，zip 1.19MB）：

- 打包坑：`Compress-Archive` 写的是反斜杠路径（`dsh-plugin-whale\assets\`），`PluginPackage` 直接判定
  「压缩包里有不安全的路径」拒绝导入；改用 Python `zipfile` 逐文件写正斜杠路径才通过。
- 导入覆盖同 id 旧记录（`PluginStore.installFrom` 里 `dir.deleteRecursively()` + `registry.upsert`），
  **不需要先删夹具**。
- 结果：`debug import ok=true 已导入 dsh-plugin-whale 0.1.0；试运行报错（见详情）；组合包 insert 1 个子插件`；
  记录 `clientUi=true`，`error=TypeError: not a function at apply (lib/index.js:812:35)`。

**试运行报错是如实结果**：插件的 Node 半侧（`lib/index.js` → `WhaleService` → `node:path`/`webServer`/
`ctx.provide`）在 Android 上没有宿主，QuickJS 里跑不起来；**浏览器半侧（`dsh.client`）不受影响**——
它由宿主 WebView 直接从安装目录载入。所以鲸鱼娘的界面/功德/木鱼全在，只是插件的 3 个 Node 工具没注册。

### 2. 通用宿主补的三件事（`app/src/main/assets/client-host/`）

| 补的东西 | 位置 | 说明 |
|---|---|---|
| **宿主适配层机制** | `host.js` + `adapters/<插件id>.js` | 载入插件 bundle **之前**先载 `assets/client-host/adapters/<归一化 id>.js`（404 也继续）；适配层拿到 `window.__DSH_HOST__`（`kvGet/kvSet/setContentSize/native/onSession/currentSession/log`） |
| **网络出口** | `host.js` | `window.__DSH_TRANSPORT__ = { fetch }` + `window.fetch` 拦截；先问适配层 `window.__DSH_HOST_FETCH__(url, init)`（返回 Response 形状就采用，返回 null 走真网络）。鲸鱼娘 client 优先用 `globalThis.__DSH_TRANSPORT__.fetch`，正好接住 |
| **原生 KV** | `ClientPluginWindowService.Bridge.kvGet/kvSet` | 落 `dsh` prefs，键名 `pluginhost_<归一化插件id>_<key>`（键只允许字母数字 `_ - .`）。**不暴露 App 自己的键**（API key 在同一个 prefs 文件里） |
| **窗口尺寸** | `Bridge.setContentSize(w,h)` + `resizeWindow()` | CSS px ≈ dp；**右下角固定**（变大往左上长），插件里 `position:fixed;right/bottom` 的挂件屏幕位置不动 |
| **指针事件** | `host.js` `dispatchPointer()` | 单指触摸除合成 `mousedown/mousemove/mouseup` 外，再合成 `pointerdown/move/up`（插件的拖动 pill 用 pointer 事件） |
| **面板滚动** | `host.js` `nearestScrollable()` | 触摸起点在 `overflow-y:auto/scroll` 的祖先里时，单指上下拖改为直接改 `scrollTop`（触摸被 `preventDefault` 挡住，原生滚动不会发生），滚动中不合成 `mousemove` |
| **会话快照补 token** | `ClientPluginWindowService.sessionJson()` | 加 `reply/replySeq/tokens/tokensSeq`，适配层据此播报与折算功德 |

### 3. 适配层 `adapters/dsh-plugin-whale.js`（宿主兼容层，不改插件逻辑）

按插件自己的 Node 半侧（`src/services/client-api.ts` + `whale-service.ts` + `merit-storage.ts`）**同语义**实现：

- `/dsh-whale/state`（含 `apiVersion:2 / todayMerit / totalMerit / todayTokens / totalTokens / pendingTokens /
  tokensPerMerit / accompanimentIntervalMs / modelUrl / todayKey / currentSpeech / speechSeq / ackedSpeechSeq /
  speechQueue / knockSeq / auto / records`）、`/knock`（`count` 夹 1..108）、`/speech-ack`
  （`seq` / `accompanimentIntervalMs` 200..60000 / `modelUrl` / `text` 试听）、`/auto`、`/tokens-per-merit`
  （±1..±1000 万，0 拒绝）；`/state` 直出 state，其余 `{state}`；
- 功德账本落 native KV（`whale_ledger`，结构同上游 `merit_history.json`：`days{日期:{merit,tokens}}` +
  `pendingTokens`），杀进程/重装页面都不丢（真机验证：重装 APK 后体重仍是 1kg）；
- token → 功德照 `consumeTokensAndKnock(tokens, step, 108, capTokens=false)`：`pendingTokens` 攒满
  `tokensPerMerit`（默认 10000）折 1kg，`tokens` 同时记入当天桶；
- 语音来源换成会话快照：`onSession` 收到新的 `replySeq` 就按上游 `extractFinalConclusionText` +
  `splitTextIntoSpeechSentences`（两个纯函数逐字照搬 `llm-speech-service.ts`）取**最后一段**切句入队，
  client 每 750ms 轮询 `/state` 取 `speechQueue`，显示后回 `/speech-ack`；
- 窗口：默认 306×302（挂件实际占 272×251 + 右下 28/36 留白）、设置面板 356×620、收起 230×180；
- Android WebView 没有 Web Speech：载入插件前把 localStorage `dsh_whale_client_prefs.voiceEnabled` 预置
  `false`，走插件自带的「静音也显示 + 敲一下」降级路径（每句 900ms、有口型动作）；
- 兼容样式 `compat/dsh-plugin-whale.css`：把根元素钉在窗口右下角（插件自己的拖动 pill 会写 inline
  `left/top`；适配层把拖动翻译成**移动真正的悬浮窗**，`pointerdown/move` 那一套已经能跑）。

### 4. token 统计接通（M71 遗留的「可选」项，本批做了）

- `core:engine`：`TurnUi` 新增 `setTokenUsage(inputTokens, outputTokens)`（默认空实现），
  `TurnEngine` 每个 step 成功收口时和 `setUsage` 一起调；
- `AppViewModel.ComposeTurnUi` 覆写 → `BigFish.onTokenUsage()` 按轮累加，`onTurnEnd` 把
  `tokens/tokensSeq` 写进快照（顺手把 `onIdle`/`onTurnStart` 的序号带上，避免重复折算）；
- `OpenAiClient`：流式 `prompt_tokens` 往往只在最后一个 chunk 的 `usage` 里出现，之前只读第一个 chunk →
  `inputTokens` 恒 0；现在任意 chunk 的 `usage.prompt_tokens/completion_tokens` 都收，流结束时补一条
  `MessageStart` 纠正（引擎对 `MessageStart` 是赋值语义，重复发只是更新计数）。

---

## 二、一个必须记下来的坑：Android 12+ 会把「手势穿透的悬浮窗」压成 0.8 透明度

第一次真机截图（`docs/shots/99-01.png`、`99-02.png`）界面整体发灰、下层 App 的问候语透过卡片看得一清二楚。
查了半天：

- 不是 `backdrop-filter`（用 CDP 关掉没变化）、不是 touch proxy 小窗（`setTouchRegion([])` 拆掉没变化）、
  也不是 WebView 的 `setBackgroundColor(TRANSPARENT)`；
- `adb shell dumpsys window windows` 里这个窗口写着 **`mAttrs={... fmt=TRANSLUCENT alpha=0.8 ...}`**，
  `mShownAlpha=0.8`；而 M66 那个同样是 `TYPE_APPLICATION_OVERLAY` 的鲸鱼娘挂件窗口是 `alpha` 缺省（1.0）。
- 两次唯一的标志位差别：M70 起为了「旁边点滑动能穿透」，渲染窗加了 `FLAG_NOT_TOUCHABLE`。
  **去掉 `FLAG_NOT_TOUCHABLE` 后 alpha 立刻恢复 1.0**（`99-03`/`99-04` 就是去掉之后的颜色）。
  即 Android 12+ 把「不可触摸的 app 遮挡窗」当成 untrusted overlay，强制 80% 不透明度
  （和「不可信触摸/遮挡」那条系统策略同源）。

**采用的解法（`passThrough`）**：

- **有宿主适配层的插件**（`assets/client-host/adapters/<id>.js` 存在）→ 窗口**可触摸**（alpha 1.0），
  适配层用 `setContentSize` 把窗口收到插件 UI 的实际包围盒（鲸鱼娘 306×302），
  **穿透靠几何**：窗口外的触摸自然落到下层 App。代价是窗口内极小空白（约 6/16/28/35 px 的留白）会吃触摸。
- **没有适配层的插件**（例如 `dsh-client-ui-pet`）→ 保持 M70 的「`FLAG_NOT_TOUCHABLE` + proxy 小窗转发
  MotionEvent」，穿透按 UI 矩形精确，但**界面被系统压到 0.8**（宠物是照片型素材，M70 用户没提过；
  这条作为已知限制记在这里，后续可以给 pet 也写适配层收窗口）。
- 兜底接口 `DSHNative.setPassThrough(enabled)` 留着，但 alpha 上限是**加窗口时**按标志算的，
  运行中切回来不一定马上恢复 1.0，所以优先「加窗口时就选对」（`hasHostAdapter()` 判断）。

---

## 三、真机验证（2026-11-10 22:2x–22:5x）

设备：`--es dsh_client_plugin dsh-plugin-whale`（force-stop 后冷启动）。

| # | 验证 | 证据 |
|---|---|---|
| 1 | logcat：`overlay plugin=dsh-plugin-whale entry=lib/client.js 600x760 at 108,584 passThrough=false` → `host adapter loaded` → `plugin applied slots=0` | `99-01` 之前那次日志（同链路） |
| 2 | 插件自己的 3D 鲸鱼娘挂件出现：舞台 canvas（chibi 立绘）+ 问候气泡 + 体重徽章 + 工具栏（▶ 伴奏 / 🔇 朗读 / ⚙ 鲸鱼设置 / ⚖️ 体重簿）+ token 条 + 拖动 pill + 收起 pill | `99-01.png`（0.8 半透明版）、`99-03.png`（修好后） |
| 3 | 点舞台 → 敲木鱼：`体重 0kg → 1kg → 2kg`，`knockSeq/todayMerit` 同步涨，账本 `records=[{dateKey:'2026-11-10', count:2}]` | CDP `__whaleStateJson()` |
| 4 | 点「⚙ 鲸鱼设置」→ 窗口自动 **356×620**，面板 5 个 tab（💬 播报伴奏 / 🔔 木鱼音色 / 🎙️ 发音人 / 🧊 3D模型 / ⚡ Token），卡片 `[17,144,322,333]` 完整不裁 | `99-04.png` |
| 5 | 面板里单指上滑 → `scrollTop 0 → 107.5`（木鱼音色 tab，`scrollHeight 491 > clientHeight 384`），滚动链路通 | CDP 读数 |
| 6 | 点 ✕ → 面板关、窗口回到 **306×302**，挂件回到 `[6,16,272,251]` | CDP 读数 |
| 7 | 真模型：`--es dsh_send "只回复两个字：OK"` → 气泡变 **`OK`**，`speechSeq=1 / ackedSpeechSeq=1 / speechQueue 空`，体重 **3kg**（播报时自动伴奏敲了一下） | `99-05.png` |
| 8 | token 落账：`todayTokens=2 / totalTokens=2 / pendingTokens=2`、`tokensPerMerit=10000`，token 条 `⚡ 投喂: 2 Tokens · 体重: 3kg`（这轮 API 只报了 output 2；`OpenAiClient` 的 `prompt_tokens` 修正本批已改，见 §一.4） | CDP 读数 |
| 9 | 账本持久化：重装 APK 后打开插件窗，体重仍是 `1kg`（native KV 落 prefs，不依赖 WebView localStorage） | 重装后第一张 `99-03.png` 体重 1kg |
| 10 | tab 用**真手指点**切换（`input tap` → 选中 ⚡ Token / 🔊 木鱼音色），不是 CDP 假点 | CDP `active` 文本 |

未验证（设备在验证途中无线调试掉线，见 §五）：

- HOME 后窗口是否留在桌面（旧几何下验证过 96-09，新几何 306×302 还没复测）；
- 插件详情页「打开插件窗口」按钮真机点一遍（本批仍走 `--es dsh_client_plugin`）；
- `dsh-client-ui-pet` 在 host.js 改动后的回归（指针事件 + 适配层加载链会不会影响 Phase 1）；
- 206 字以上的长回复切句（本批只有 `OK`）。

---

## 四、诚实限制

1. **插件的 Node 半侧跑不起来**：`lib/index.js` 试运行报 `TypeError: not a function at apply`（缺
   `ctx.provide` / `node:path` / `webServer`），所以插件自带的 3 个工具没有注册；插件详情页会显示
   「试运行报错」，这是真话，不是宿主坏了。
2. **没有 Web Speech 语音包**：只显示字幕 + 木鱼节拍，不朗读；设置面板「发音人」是空状态。
3. **设置面板的文本输入框弹不出键盘**（悬浮窗 `FLAG_NOT_FOCUSABLE`），3D模型 URL 只能看不能改。
4. **token 数偏小**：本轮只有 2（DeepSeek 流式默认只在最后 chunk 给 `usage`；`OpenAiClient` 已改成
   任意 chunk 收 `prompt_tokens` 并在流末尾补发 `MessageStart`，但本批装机验证时用的是改前的 APK，
   改后的 effect 还没真机复测）。
5. **0.8 透明度的老路还在**：没有适配层的插件（pet）继续走 `FLAG_NOT_TOUCHABLE`，界面被系统压到 0.8；
   要根治得给它们也写适配层收窗口（Phase 3/4 一起做）。
6. 窗口尺寸是**按挂件实测包围盒写死的常量**（306×302 / 356×620 / 230×180），插件改版改布局时要跟着调。

---

## 五、本批收尾状态 / 下一棒要做的

- 提交：本文件 + `docs/shots/99-01…99-05.png` + 代码（host.js / adapters / compat / ClientPluginWindowService /
  BigFish / EngineContracts / TurnEngine / OpenAiClient / AppViewModel）。
- **设备在 22:5x 无线调试掉线**（`adb devices` 里 `192.0.2.10:41759` 变成 offline，随后端口不再监听；
  扫 30000–47000 只发现 `46888` 一个接受连接的端口，`adb connect` 仍 offline，判断是无线调试被关/换端口）。
  重新连上后先做三件事：
  1. 重装 APK（HEAD 的 APK 比设备上的多一个 `OpenAiClient` token 修正 + 重新编译产物一致性）；
  2. HOME 后确认挂件留在桌面（新几何 306×302）；再开一次 pet 窗口做 Phase 1 回归；
  3. 插件详情页点一次「打开插件窗口」，确认按钮链路（M70/M71 都没点过）。
- Phase 3（`dsh-ventus-whale`）照 `docs/97` 的路线：React vendor 已就位，补 `ctx.slots` 设置页 +
  `/api/ventus-whale/*` 适配层；Phase 4 大肥鱼只跑 Web 半侧（`shell.overlay` + `/plugins/dsh-dafeiyu/config`）。
  这两个插件都属于「有适配层 → 窗口可触摸」这一类，记得同样用 `setContentSize` 收窗口，别再踩 0.8 透明度的坑。

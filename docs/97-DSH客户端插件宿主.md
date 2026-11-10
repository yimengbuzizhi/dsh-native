# 97 · DSH 客户端插件宿主（M70 设计与单插件落地）

> 时间：2026-11-10 21:3x（Asia/Shanghai）
> 设备：OPPO A55 5G（PEMM00，720×1600，Android 13）；**本批开始时无线调试不可达**，真机验证待补。
> 用户 2026-11-10 21:2x 明确：之前把「大肥鱼 / 鲸鱼娘 / 小鲸鱼」这类 DSH 悬浮窗插件做成了
> **鲸歌自己的原生窗口**，这不是他要的——他要的是**在软件上适配插件本身**（插件自己的 UI 跑起来），
> 不是软件里再做一个。用户的选择：
> 1. 路线 **A：通用客户端插件宿主**，先做单插件验证；
> 2. 现有原生替身 **全部删掉，只保留插件原版**；
> 3. 桌面专属窗口（Electron/Qt）**只跑它的 Web client 部分**（页面内小角色/设置页），不跑原生 Helper。

---

## 一、之前到底做了什么（调查）

| 插件 | 上游真实窗口 | 鲸歌原实现 | 性质 |
|---|---|---|---|
| `dsh-dafeiyu`（大肥鱼） | Electron 主进程 + Qt/PySide/Swift Helper；`lib/client.js` 是 shell.overlay 小角色 + 设置卡 | `fish/BigFish.kt` 状态机 + `BigFishService` Canvas 悬浮窗 | **原生重做**，docs/88 自己写「不是原版窗口」 |
| `dsh-ventus-whale`（鲸鱼娘） | `lib/client.js`（477KB）：body portal 的 React+three 3D 浮窗 + settings slot | 抽出 `WhalePet.tsx`/`whale-model.ts`/CSS 重打包（`web/ventus-whale/entry.tsx` 换宿主桥） | 原版组件，但不是原版插件 entry |
| `dsh-client-ui-pet`（序列帧桌宠） | `lib/client.js`（56KB，`__ModuleLoader__` + DOM）+ `/media/pet` 素材 | 原版 client.js 跑在透明 WebView，自写 `page.js` | 接近适配，但按插件写死 |
| `dsh-plugin-whale`（鲸鱼娘挂件） | `lib/client.js`（140KB IIFE）+ `/dsh-whale/*` 宿主接口 | 原版 client.js + 页面内自实现接口 | 接近适配，但按插件写死 |
| `dsh-pet-whale` 等其它 | 各自 Web client | 没有映射：只有工具，没窗口 | 未适配 |

根因：**没有通用 DSH 客户端插件宿主**。`NativePetAdapters` 按插件 id 硬映射到 4 个写死的窗口；
`PluginRecord.clientUi` 只用来在插件页写一句「手机不执行」；没有任何代码加载插件的
`dsh.client` 入口（`__ModuleLoader__`）。

---

## 二、六个目标插件的可行性（读了上游包/源码）

| 插件 | `dsh.client` | 客户端形态 | 宿主侧依赖 | 可行性 |
|---|---|---|---|---|
| `dsh-client-ui-pet` | 有（`exports["./client"]`） | 自包含 IIFE，`__ModuleLoader__`，无 require；DOM 桌宠 + `/media/pet` 图片 | 图片代理桥（可映射到插件目录） | **Phase 1 单插件验证** |
| `dsh-plugin-whale` | 有 | 自包含 IIFE，无 require；DOM/Canvas 挂件 | `/dsh-whale/state|knock|speech` 宿主接口（M66 已写过一个版本） | Phase 2：宿主适配层 |
| `dsh-ventus-whale` | 有 | `__ModuleLoader__` + `require('react')/react-dom/client/jsx-runtime`；body portal + settings slot | React 18 + slots shim + `/api/ventus-whale/*` | Phase 3：vendor React + 适配层 |
| `dsh-dafeiyu` | 有 | `require('react')`；`shell.overlay` 小角色 + 设置卡 | `/plugins/dsh-dafeiyu/config` JSON 接口（Node host 提供） | 只能跑 Web 半侧；接口需适配层，否则设置卡报错 |
| `dsh-dafeiyu-pet` | 有 | `require('react')`；**只有设置页** | 桌宠是下载的原生 helper；设置走 `127.0.0.1:7811` | 只能跑设置页；桌宠本身 Android 无法执行 |
| `dsh-widget`（oioiphainon） | **没有** `dsh.client` | Windows PowerShell 桌面挂件（`desktop/FatFishDesktop.ps1`） | Win32 | **Android 无法适配**，只能如实标不支持 |

结论：真正能「跑插件自己的窗口」的是前三个 Web client；两个大肥鱼只能跑 Web 半侧；
`dsh-widget` 是 Windows 专属，不能假装支持。

---

## 三、通用宿主架构（Phase 1）

```
┌──────────────────────────── App 进程 ────────────────────────────┐
│ ClientPluginWindowService（遮罩窗，透明 WebView，每个插件一个）      │
│  ├─ WebView 载入 https://dsh-plugin.local/host/index.html?plugin=… │
│  ├─ shouldInterceptRequest：/host/* → assets/client-host/*          │
│  │                          /plugin/<id>/* → files/plugins/<id>/*  │
│  ├─ JS 桥 DSHNative：state/log/ready/moveWindowBy/endDrag/collapse  │
│  └─ BigFish 状态 → session 快照（running/nodes/pending）            │
└──────────────────────────────────────────────────────────────────┘
                              │
┌──────────────────── 宿主页（assets/client-host）────────────────────┐
│ __ModuleLoader__（收集 entry → factory(require) → apply(ctx)）      │
│ require 白名单：react / react-dom / react-dom/client / jsx-runtime  │
│ ctx：get('sessions')（BigFish 快照 + 订阅）、effect、on(空)、slots   │
│ 触摸 → 鼠标事件合成（单击/拖动/双击；双指留给 native 拖窗）          │
│ 素材：__DSH_PROXY_BRIDGE__ 指向 /plugin/<id>，图片请求走拦截         │
└──────────────────────────────────────────────────────────────────┘
```

设计要点：

1. **插件文件从安装目录读，不从 App 资源读**：`files/plugins/<id>/package.json` 的
   `dsh.client` + `exports["./client"]`（兼容字符串/`{default}`）解析出入口；
   这样换插件版本/换插件都不用重编 App。
2. **一个插件一个窗口**，窗口位置按插件 id 存 `pluginwin_<id>_x/y`；双指拖窗、
   双指轻点收起、通知栏常驻可关。
3. **会话面**：`ctx.get('sessions')` 按 DSH 形状提供（`list/binding/scope`，
   `subscribe` 可监听），数据来自 `BigFish` 快照；插件自己决定怎么响应。
4. **slots 最小实现**：`register/inject/snapshot`，`shell.overlay` 渲染为全屏层；
   `settings.*` 先不渲染（设置页留到 Phase 3 的插件设置面板）。
5. **require 白名单**：React 18.3.1 UMD（`react.production.min.js` +
   `react-dom.production.min.js`）+ `react-dom/client`、`react/jsx-runtime` 两个 shim；
   其它模块名直接报「宿主未提供模块 X」，不静默。
6. **安全**：WebView 只加载本地拦截域，插件文件只读；插件本身仍和自己安装时一样是
   第三方代码（与桌面端同一风险模型），不额外给 Android 权限。

---

## 四、Phase 1 实现（本批）

新增：

- `core/plugins/PluginClientEntry.kt`：入口解析（无 Android 依赖，带单元测试）；
- `app/pluginui/ClientPluginWindowService.kt`：通用遮罩窗（WebView + 文件拦截 + 手势 + 会话桥）；
- `app/pluginui/ClientPluginWindows.kt`：解析插件记录 → 启动/关闭窗口；
- `app/src/main/assets/client-host/index.html` + `host.js` + `vendor/react*.js`；
- 插件详情页「打开插件窗口」按钮（有 `dsh.client` 且入口可解析时显示）；
- 调试通道 `--es dsh_client_plugin <插件id>`（锁屏/无 UI 也能验）。

Phase 1 只验证 `dsh-client-ui-pet`：它是自包含 client、无 require、素材只需图片代理，
能一次验证「读安装目录 → 入口 → 模块加载 → ctx → 会话 → 窗口」整条链路。

---

## 五、迁移与删除顺序（用户要求「全部删掉，只保留插件原版」）

按「先用插件原版跑通，再删对应原生窗口」的顺序，避免删完没有替代。
**2026-11-10 22:1x 用户追加**：三组原生替身设置项「赶紧删掉」，不必等插件迁完 →
M71 已把 `WhaleWidgetService` / `WhalePetService` / `BigFishService` + 设置项/映射/资源全部删除
（见 `docs/98`）。剩下的是「让插件原版跑起来」：

1. ~~Phase 1 验证 pet → 删 `PetWebService` + `web/ui-pet` 打包页 + 设置项「桌面宠物」~~（M70 已做）；
2. **Phase 2 验证 whale**（原生 `WhaleWidgetService` 已删，通用宿主是唯一入口）→
   补 `/dsh-whale/*` 适配层，跑插件自己的 `lib/client.js`；
3. **Phase 3 验证 ventus**（原生 `WhalePetService` 已删）→ React vendor 已就位，补
   `ctx.slots` 设置页 + `/api/ventus-whale/*`；
4. **Phase 4 大肥鱼 Web 半侧**（原生 `BigFishService` 已删，状态机 `BigFish.kt` 保留作会话快照）→
   `shell.overlay` + `/plugins/dsh-dafeiyu/config`；`dsh-widget` 明确标「Windows 专属，Android 不支持」；
5. `NativePetAdapters` 与 `DshApplication` 的悬浮窗优先级已在 M71 整段删除。

---

## 六、已知限制（先写清楚）

- **原生 Helper 窗口不可能跑**：大肥鱼的 Qt/PySide、`dsh-widget` 的 PowerShell/Win32；
  只能跑它们的 Web client，或如实标不支持。
- **插件 Host 侧 Node 代码不执行**：`ctx.webServer`、`node:fs`、子进程都没有；
  依赖自己本地 HTTP 服务的客户端（`dsh-dafeiyu-pet` 的 7811）会显示失败/空设置页，
  需要逐个写宿主适配层，不能假装成功。
- **Phase 1 只支持 classic script/IIFE 形式的 client bundle**；ESM + import map 未做。
- **Web Speech / 桌面通知 / 原生窗口 API** 在 WebView 里不完整，插件若依赖要单独适配。

---

## 七、真机验证（Phase 1：dsh-client-ui-pet）

- 设备：OPPO A55 5G，无线调试 `192.0.2.10:41759`；M70 APK 已装。
- 真实插件：`npm dsh-client-ui-pet@0.3.0` 的 `package.json` + `lib/client.js` 原样，素材用
  M65 转好的 1006 张 WebP（宿主把 `.png` 回退到 `.webp`），打成 zip 导入；插件 id 是
  `@dsh-external/dsh-client-ui-pet`，安装目录被 PluginStore 洗成 `-dsh-external-dsh-client-ui-pet`。
- 验证记录：
  - `--es dsh_client_plugin "@dsh-external/dsh-client-ui-pet"` → logcat
    `DshClientHost: overlay … 600x760` → `plugin applied … slots=0` → 屏上出现**原版**桌宠（96-01）；
  - 点按 → 换动作（96-02）；`--es dsh_fish_demo thinking` → CDP 读到
    `img.src = …/media/pet/think_0/37.png`，**会话联动真的驱动了插件自己的动作状态机**（96-05/96-07）；
  - 按 HOME → 窗口留在桌面（96-09）；返回 App 正常。
- **触摸穿透**（用户 2026-11-10 21:56 反馈「鲸鱼娘旁边点击滑动都没反应」）：
  - 原因：透明 WebView 窗口整块吃触摸；
  - 实现：渲染窗口加 `FLAG_NOT_TOUCHABLE`，页面把「可见且可交互的 DOM 矩形并集」
    经 `DSHNative.setTouchRegion` 上报，native 用另一块**只接触摸的 proxy 小窗**盖在同一位置，
    单指 `MotionEvent` 转发给 WebView、双指仍 native 拖窗/轻点收起；
    （`ViewTreeObserver.InternalInsetsInfo` 是 @hide，编译期不可用，试过）
  - 验证：点输入框 → `dumpsys input_method` `mInputShown=true` + 键盘弹出（96-11）；
    宠物自己的点按仍走插件逻辑（96-02/96-12）。
- **兼容样式**：插件 `mount()` 在 `<img>` 撑开前就按 `getBoundingClientRect()` 算吸附点，
  会把 corner-br 算成 top=364（宠物只剩 16px）。`client-host/compat/<插件id>.css`
  给 pet 加 `min-height:130px`（+ 有 inline top 时 `bottom:auto`），属宿主兼容层，不改插件逻辑（96-07）。
- **已删原生替身**：`PetWebService` + `assets/pet/`（1006 帧/11.7MB）+「桌面宠物」设置项 +
  `NativePetAdapters.PETWEB` + `--ez dsh_uipet`；APK 129.4MB → 118.6MB。设置页不再有重复的宠物开关。
- 已知问题：宠物拖动/吸附偶尔会把位置落到别的角（插件自己的位置持久化 + 合成鼠标事件），
  重置办法：清掉该 WebView 的 `dshPetPrefs.v3`；插件面板/设置卡超出 300×380 窗口会被裁
  （Phase 2 再做按面板扩窗）；键盘弹起时宠物会浮在键盘上方（窗口不跟随 App resize）。
- 尚未验证：whale / ventus / 大肥鱼 Web 半侧（Phase 2–4）。
  **2026-11-10 22:1x 用户追加后（M71，docs/98）**：三组原生替身（大肥鱼 / 3D 鲸鱼娘 / 鲸鱼娘挂件）
  已按用户要求先删除，设置项与 Manifest service 都没了；HOME 时不会再冒旧的鲸鱼娘挂件，
  只剩通用宿主打开的插件原版窗口。

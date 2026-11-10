# 95 · Operit 兼容层二期：记忆库 / env 配置 / 真 PTY / visit_key / 读屏（M68，真机验证）

> 时间：2026-11-10 02:5x–03:1x（Asia/Shanghai）
> 设备：OPPO A55 5G（PEMM00，720×1600，Android 13，无线调试 `192.0.2.10:43405`）
> 上接：`docs/94`（M67 包管理 + `Tools.*` 兼容层，§五 的 5 条下一步就是本批）
> 用户点名：「包管理搬 Operit 自带包」的收尾——把 10 个「需要 API Key」的死包、`Tools.Memory`、
> 终端、`visit_key`、无障碍手势全部接到鲸歌真实能力上，不再留「暂不支持」。

---

## 一、本批做完的五件事

| # | 上批状态 | 本批 |
|---|---|---|
| 1 | `Tools.Memory.*` 明确抛错 | 8 个方法全接 `MemoryStore`（新 `memory` 权限） |
| 2 | `getEnv(...)` 未接，10 个包装上就 ReferenceError | METADATA `env` → 插件配置页 → `getEnv` 读当前配置 |
| 3 | `Tools.System.terminal.*` 是 `shell.exec` 模拟，不跨调用 | 接 `:thirdparty:terminal` 真 PTY（`cd` 跨调用保留） |
| 4 | `visit_key` 续页明确抛错 | 宿主侧 visitKey 缓存 + `link_number` 续页（Operit 原文错误串） |
| 5 | 无障碍关着，`Tools.UI.*` 只验失败路径 | 无障碍已开；tap/swipe/截图/读屏全部真机跑通 |

---

## 二、实现

### 2.1 `getEnv` + METADATA `env` → 插件配置（`core/plugins/ToolPkg.kt`）

Operit 的 `env` 是「包级环境变量」，宿主在设置里填、包体用 `getEnv(name)` 读；`EnvVar` 的两种写法
（纯字符串 / 对象）和 `required` 默认 true 都照 `ToolPackage.kt` 的 `EnvVarSerializer` 归一
（新增 `ToolPkg.envSpecs`）。

生成出来的插件是这样的（`ToolPkg.wrap` 里新增）：

- `export const Config = __envSchema()`：env → `{type:"object", properties:{NAME:{type:"string",description,default}}}`，
  → [`PluginSchema`] 认它 → `plugins.json` 的 `configSchema` → 插件详情「配置（Config）」自动出输入框；
- `export function apply(ctx, config)` 把生效配置存进 `__toolpkgConfig`
  （`PluginSchema.effectiveText` = manifest 默认 → 用户值 → schema 默认值）；
- 包体拿到 `getEnv(name)`：**没填返回空字符串**（Operit 语义，不是 undefined、不抛错）；
- **必填 env 没填时，工具直接拒绝执行**并给中文说明（Operit `PackageManager` 同款行为，
  以前是包体自己 `getEnv` 到 undefined 再炸）；
- `toolpkg_status` 诊断工具改成**只在需要诊断时注册**（METADATA 解析失败 或 这个包有 env），
  以前 31 个包各注册一个，`工具名冲突` 把 logcat 刷满。

### 2.2 `Tools.Memory.*` → 内置记忆工具（新 `memory` 权限）

- `app/memory/MemoryPluginHost.kt`：`create/update/deleteMemory/move/link/queryLinks` 直接
  复用现有 8 个记忆工具（文案本来就逐字对齐 Operit），`updateLink/deleteLink` 用 `MemoryStore`
  实现；`_normalizeCallerCardId` 也补了（Operit 的包会调它，缺了会 TypeError）。
- `core/plugins/ToolPkgTools.kt`：`Tools.Memory` 从「unsupportedGroup 代理」换成真实对象，
  camelCase → snake_case 的映射照 `JsTools.kt` 的 `Tools.Memory` 实现。
- 新权限 `memory`（`PluginPermissionKind.MEMORY`，危险项、需逐项确认）：
  `PluginSandbox.hostCall` 门 `memory.*`；包管理按源码 `Tools.Memory.` 自动推断这条权限。
- 失败路径：宿主返回 `{ok:false,error}` → `__dshHostOk` 抛 Error → 包的 `wrapToolExecution`
  变成 `{success:false,message}`，和 Operit 的失败形状一致。

### 2.3 `Tools.System.terminal.*` → 真 PTY

- `app/device/tools/TerminalPluginHost.kt`：`create/exec/input/screen/close` **复用** `TerminalTools`
  的会话逻辑（新增 `invokeForHost`），不复制第二份（复制过一个月就分叉）。
- 终端会话以 App 身份跑任意命令，所以按 `shell` 权限门（`terminal.*` 没有 shell 授权会直接拒）。
- `tools.Terminal.terminal.*` 返回 Operit 形状：`{sessionId,sessionName,state}` /
  `{success,stdout,output,exitCode,timedOut}` / `{sessionId,state,rows,cols,content}`。

### 2.4 `visit_key` 续页（`core/plugins/PluginVisitCache.kt`）

照 Operit `StandardWebVisitTool` 的语义：

- 每次 `visit` 成功生成 UUID v4 形状的 `visitKey`，把**这一份** `links/imageLinks` 存进宿主进程级缓存
  （按插件隔离、LRU 32 条）；返回对象里带 `visitKey`。
- `visit({visit_key, link_number})`：`link_number` 必须是纯数字（`1.5` 直接 `Invalid link number.`），
  越界 `Link number out of bounds.`，没有这个 key `Invalid visit key.`（**Operit 英文原文**，包按 message 判断）。
- 续页抓到的是**新** `visitKey`（链可以一直续）；返回的 `url` 是「被跟的那条链接」，不是重定向后的 URL
  （`various_search` 依赖这个行为）。
- `links` 不再去重/截断（120 上限去掉），并**原样存进缓存**，保证 `link_number` 与返回给包的下标一致。
- `include_image_links` / `headers` / `user_agent` / `user_agent_preset` 都接；`imageLinks` 只在要求时收集。

### 2.5 `Tools.UI.getPageInfo`（读屏）+ 无障碍真机

- `AndroidPluginHostBridge.ui.pageInfo`：无障碍根节点 BFS → Operit `UIPageResultData` 形状
  （`packageName` / `activityName` / `uiElements[SimplifiedUINode{className,text,contentDesc,resourceId,bounds,isClickable,children}]`），
  上限 250 节点 / 深度 24。
- JS 侧补 `toString()`：`Current Application: … / Current Activity: … / UI Elements:` + `◢/▶` 树
  （Operit 的包常直接 `complete(info.toString())`）。
- 诚实点：**`activityName` 取不到**（无障碍只给包名，App 内也没有读前台 Activity 的实现），留空并写 `(unknown)`。

---

## 三、真机验证（全部 logcat / 截图，不是代码推断）

### 3.1 `getEnv` + 配置（夹具 `D:\dsh\operit-fixtures\operit-env-demo.zip`）

METADATA 声明的 env：`DEMO_API_KEY`（required）、`DEMO_BASE_URL`（optional，defaultValue
`https://api.example.com`）。工具 `env_echo` 只读 getEnv、不联网。

```
未配置时：  isError=true  Error: Operit 包「env_demo」需要先填环境变量：DEMO_API_KEY
                         （在插件详情 → 配置 里填，保存后重试）
配了两个值：{"apiKey":"demo-key-123","apiKeyLength":12,
            "baseUrl":"https://demo.example.com/v1","notDeclared":"","notDeclaredIsEmptyString":true}
只留 API Key：{"apiKey":"demo-key-123","baseUrl":"https://api.example.com", …}   ← schema 默认值生效
```

截图 `95-08-env-config-page.png`：插件详情「配置（Config）」里自动出现两行
（必填：没填时工具会直接报错 / 可选：有默认值），值就是上面两个，注册工具
`toolpkg_status`、`env_echo` 都「已接入」。

### 3.2 `Tools.Memory` 8 个方法（夹具 `operit-bridge-demo` + 真包 `extended_memory_tools`）

一次 `memory_roundtrip` 全过（真机返回，节选）：

```
createA   Successfully created memory: 'M68 验收 A' (UUID: 80bf06cb-…)
createB   Successfully created memory: 'M68 验收 B' (UUID: 23218561-…)
link      Successfully linked memory: 'M68 验收 A' -> 'M68 验收 B' (Type: related, Strength: 0.9)
queryLinks  Memory Links (1): - #4: 'M68 验收 A' -> 'M68 验收 B' (Type: related, Weight: 0.9)
updateLink  Successfully updated memory link #4 (Type: depends, Weight: 0.4)
deleteLink  Successfully deleted memory link #4
update      Successfully updated memory from 'M68 验收 B' to 'M68 验收 B（改名）'
move        Successfully moved 1 memories to 'M68验收/子目录'
query       Snapshot ID: … --- Title: M68 验收 B（改名）…
getByTitle  Title: M68 验收 A / Content: alpha content …
```

- 截图 `95-01-memory-with-m68.png`：记忆库 6 条 / 3 个文件夹，图谱里能看到 `M68 验收 A`、
  `M68 验收 B（改名）`、`M68 真包验收`；截图 `95-02-ui-tap-folders.png` 的文件夹面板里有
  `M68验收 / 子目录`（`move` 真的建了嵌套文件夹）。
- 真包 `extended_memory_tools`：9 个工具里 6 个与内置记忆工具同名（内置先占，模型的这 6 个能力照样有），
  只有 `update_memory_link` / `delete_memory_link` / `update_user_preferences` 是包独有的。
  真机调用 `update_memory_link`（包独有）→ 返回 `错误: 工具执行时发生意外错误: 找不到匹配的记忆链接`
  ——错误文案来自本批的 `MemoryPluginHost`，证明确实走的是「包 → `Tools.Memory.updateLink` → 宿主」这条路。
- 验收数据已清掉（`memory_cleanup` 删了 A/B/改名/真包验收 4 条），记忆库回到用户的 3 条。

### 3.3 真 PTY 终端（一次 `term_roundtrip`）

```
create  → sessionId 69bac2cd-… state=READY（rootfs 在 /data/data/…/files/usr/…/proot-distro/installed-rootfs/ubuntu）
exec    echo M68-$((6*7)) && pwd && whoami   → "M68-42\n/root\nroot"
exec    mkdir -p /tmp/m68 && echo persisted > /tmp/m68/a.txt
exec    cd /tmp/m68                          → (no output)
exec    pwd && cat a.txt                     → "/tmp/m68\npersisted"      ← cd 跨调用保留（真 PTY）
screen  rows=60  尾部 "root@localhost:/tmp/m68# pwd && cat a.txt\n/tmp/m68\npersisted"
```

logcat 同时有 `TerminalManager: Read chunk: 'LOGIN_SUCCESSFUL TERMINAL_READY'`、
`proot: … installed-rootfs/ubuntu/usr/bin/mesg`（真 proot Ubuntu 在跑）。

### 3.4 `visit_key` 续页

```
首页 https://www.iana.org/help/example-domains
→ visitKey 56652849-38a3-4185-8055-d6dd80a56cc0, title "Example Domains", linkCount 30,
  firstThree: Domains / Protocols / Numbers（绝对 URL）
→ visit({visit_key, link_number: 2})
  follow.url = https://www.iana.org/protocols, title "Protocol Registries",
  contentHead = "Protocol Registries …"（真实正文）, 新 visitKey ≠ 旧 visitKey
→ visit({visit_key:'not-a-real-key', link_number:1}) → "Invalid visit key."
```

（`https://example.com` 那版页面没有 `<a>`，所以那条链是 `Link number out of bounds.`——语义正确。）

### 3.5 无障碍 + `Tools.UI.*`（设备上已开「DSH 无障碍」）

```
设备：settings enabled_accessibility_services=xin.dshaa.nativeapp/…DshAccessibilityService、accessibility_enabled=1
dumpsys：Bound services:{Service[label=DSH 无障碍, capabilities=161, …]}

ui_page_info → {"packageName":"xin.dshaa.nativeapp","activityName":"",
               "text":"Current Application: xin.dshaa.nativeapp / Current Activity: (unknown) /
                       UI Elements: ◢ [FrameLayout] [0,0][720,1600] … [TextView] T:\"6 条记忆 · 3 个文件夹\" …"}
ui_tap    (64,255)  → 点「文件夹」→ 截图 95-02 打开文件夹面板（真的动了）
ui_swipe  (360,1300)→(360,500) → 截图 95-06（顶部：从本地导入 / operit-bridge-demo） vs
                                 95-07（同一页滚到 operit-extended-http / crossref / 12306）
ui_screenshot → /data/user/0/xin.dshaa.nativeapp/files/screenshots/plugin-shot-20261110-030551.png
                （已取回 docs/shots/95-05-plugin-screenshot.png，74334 字节与设备一致）
```

踩坑记录：**ColorOS 上 `am force-stop` 之后无障碍服务不会自动重新绑定**，必须先
`settings put secure enabled_accessibility_services …` + `accessibility_enabled 1` 再启动，
否则 `A11y.isReady()` 一直是 false（本批一开始误判成「开了没用」）。

### 3.6 静态归类变化（包管理「包」tab）

去掉「Memory / terminal.input / terminal.screen / UI.getPageInfo」四个不支持项、并修好
「纯字符串 env 漏识别」之后重扫 31 个包：

| | M67 | M68 |
|---|---|---|
| 可用 | 10 | **12** |
| 部分可用 | 11 | **9** |
| 需要 API Key | 10 | **10** |

转成「可用」的是 `extended_memory_tools`（记忆库）与 `super_admin`（终端屏幕/输入）。
「需要 API Key」的 10 个现在都有配置页可填，不再一调用就 ReferenceError。

---

## 四、本批没做 / 限制（必须说实话）

1. **`update_user_preferences`（真包）仍未接**：它走 Operit 原生 `toolCall("update_user_preferences")`，
   鲸歌没有用户偏好库；调用会明确报「原生工具 … 暂不支持」。
2. **`activityName` 取不到**：`Tools.UI.getPageInfo` 的 `activityName` 恒为空字符串
   （无障碍只给包名，App 内没有读前台 Activity 的实现）。
3. **终端首次建会话依赖 rootfs**：本机已展开（`files/usr/.../installed-rootfs/ubuntu`），实测建会话 + 4 条命令
   在 75 秒窗口内完成；**换新设备第一次建会话要展开 61MB rootfs，上游 `createNewSession` 有 30 秒硬上限，
   可能超时**（超时会关掉会话、重试从头再来）。工具会原样报错，不假装成功。
4. **`visit_key` 缓存有上限**：宿主 LRU 只留 32 份、只存链接表（不存正文），且按插件隔离；
   Operit 是无上限 `ConcurrentHashMap`。跨进程/重启即失效（Operit 也是进程级）。
5. **`Tools.Files.download` 还不吃 `visit_key`+`image_number`**：Operit 的 `download_file` 支持从同一次
   visit 的图片链接里取第 N 张，鲸歌暂时只能按 URL 下载（记在 §五）。
6. **`memory` 权限是新加的危险能力**：老插件重新导入/重装后要重新确认（`grant all` 也认它）；
   包管理的权限推断按源码调用面，静态扫描不等于每个工具都跑过。
7. 本批连带的调试脚手架：`AppViewModel` 的调试调用通道加了 2.5 秒等待（避让冷启动的无障碍绑定），
   日志截断从 1200 提到 4000 字符——只影响 `--es dsh_plugin_call` 这条验收通道，不影响正常对话。

---

## 五、下一步

1. `Tools.Files.download` 支持 `visit_key` + `image_number`（和 `PluginVisitCache` 共用，错误用 Operit 原文
   `Invalid visit key.` / `Index out of bounds.`），把 `various_search` 的图片搜索闭环。
2. `update_user_preferences`：如果要做，得先给鲸歌定「用户偏好」存哪（memory 里一条特殊记忆？独立表？），
   不要临时拍。
3. 常驻 QuickJS + hooks（`session/event`、`agent/pre-step`、`tools/post-execute`、`llm/stream`）——
   仍是 DSH 插件那条线最大的缺口，量大，先设计。
4. token 统计接通（`TurnEngine` 已有 input/outputTokens，落库后鲸鱼娘功德才能真的跟 token 走）。
5. 输入浮层精修（用户说过「展开有些粗糙」）。

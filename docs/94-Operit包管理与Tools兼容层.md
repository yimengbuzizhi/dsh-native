# 94 · Operit 包管理 + `Tools.*` 兼容层（M67，真机验证）

> 时间：2026-11-10 02:2x–02:4x（Asia/Shanghai）
> 设备：OPPO A55 5G（PEMM00，720×1600，Android 13，无线调试 `192.0.2.10:43405`）
> 上接：`docs/91`（M63 执行链路：QuickJS 计时器 + OkHttp `response.body`）
> 用户点名：「包管理搬 Operit 自带包」——本批把这句话落到可用状态，不是只摆卡片。

---

## 一、目标

Operit 自带 31 个 ToolPkg（`Operit/app/src/main/assets/packages/` 下的 .js），它们不是 DSH 插件：
没有 `apply(ctx)`，靠 `OkHttp`、`Tools.*`、全局 `toolCall` 这些宿主 API 运行。M63 只做到
`time` / `duckduckgo` 两个包能跑，`Tools.*` 一用就抛「暂不支持」。

本批做两件事：

1. **`Tools.*` 兼容层**：把 Operit 包里实际用到的调用面接到鲸歌宿主能力上；
2. **包管理 →「包」tab**：31 个包随 App 打包，列表里能看支持情况、一键安装（走插件链路）。

---

## 二、`Tools.*` 兼容层

### 2.1 调用面 → 宿主能力

先扫描全部 31 个包的 `Tools.*` 调用（344 处、约 200 个不同方法），按「鲸歌真的能做」分组实现。
实现在 `core/plugins/ToolPkgTools.kt`（JS，~700 行），宿主能力在 `core/plugins/PluginSandbox.kt`。

| Operit 调用面 | 鲸歌实现 | 说明 |
|---|---|---|
| `Tools.Files.exists/info/list` | `fs.info` / `fs.dir` | 返回 Operit 的 `FileExistsData` / `DirectoryListingData` 形状 |
| `Tools.Files.read/readPart/readBinary/write/writeBinary` | `fs.read` / `fs.readBinary` / `fs.write` / `fs.writeBinary` | 二进制走 Base64；上限 8MB |
| `Tools.Files.deleteFile/move/copy/mkdir` | `fs.remove` / `fs.rename` / `fs.copy` / `fs.mkdir` | 失败返回 `FileOperationData{successful:false,details}`，不炸包 |
| `Tools.Files.zip/unzip` | `fs.zip` / `fs.unzip` | 目录默认保留顶层目录名；解压逐条校验路径（zip-slip） |
| `Tools.Files.apply`（replace/delete/create） | 纯 JS：读文本 → 只替换第一处 → 写回 | github 包的 `apply_local_replace/delete` 用它 |
| `Tools.Files.download` | `net.fetch(binary:true)` + `fs.writeBinary` | 二进制响应走 Base64 |
| `Tools.Files.open/share` | `host.call file.open/file.share` | FileProvider + Intent（用户会看到系统弹层） |
| `Tools.System.sleep` | `timer.wait` | M63 的计时器通道 |
| `Tools.System.shell` / `terminal.*` | `shell.exec` | 终端会话在**本次工具调用内**有效（不跨调用保持 PTY），无 `input/screen` |
| `Tools.System.getDeviceInfo/listApps/startApp/stopApp/getSetting/setSetting/toast/sendBroadcast` | `host.call device.*` | 走 app 侧 `AndroidPluginHostBridge`；停止其他应用需要 Shizuku/root，如实返回失败 |
| `Tools.Net.visit` | `net.fetch` + JS 侧 HTML→文本/链接/图片解析 | 返回 `VisitWebResultData` 字段；`visit_key` 续页**不支持**（明确抛错） |
| `Tools.Net.uploadFile` | `net.upload` | multipart/form-data，字段/文件/Content-Type 由包指定 |
| `Tools.UI.tap/longPress/swipe/pressKey/setText/clickElement/captureScreenshot` | `host.call ui.*` → `A11y` | 与内置设备工具同一条无障碍链路；坐标是**像素**（Operit 同款） |
| `Tools.Memory/Chat/Workflow/SoftwareSettings/FFmpeg`、`Tools.Net.browser*`、蓝牙、定位、通知、子 Agent、静默安装 | **明确抛错**（中文说明） | 不做空壳；包组合状态会在「包」tab 标「部分可用」 |

### 2.2 宿主桥与权限

- 新增 `core/plugins/PluginHostBridge.kt`：`call(method, payloadJson): String?`，`null` = 宿主没有这个能力。
- app 侧实现 `data/AndroidPluginHostBridge.kt`：设备信息/应用列表/启停应用/设置读写/Toast/广播/
  文件打开分享/无障碍手势/截图。
- **`device` 权限从「未实现」改为可用**（危险能力，仍要用户在插件页逐项确认），
  解释文案改成真实范围（不含 root/Shizuku/静默安装）。
- `PluginToolRuntime` 把桥传给 `PluginSandbox`；`AppViewModel` 构造时注入。

### 2.3 OkHttp 与 `toolCall`

- OkHttp shim 补 `client.get/post/put/delete`、响应 `json()/text()/header()/statusMessage`；
  修了内部 `method` 字段与 `method()` 函数同名导致 `client.get()` 把函数当 HTTP 方法的 bug。
- 新增全局 `toolCall` 最小兼容：`extended_http_tools` 直接调原生 `http_request/multipart_request`，
  映射到 `net.fetch` / `net.upload`；`manage_cookies` 明确抛错。

### 2.4 包管理「包」tab

- `app/src/main/assets/operit-packages/`：31 个 .js **原样** + LGPL-3.0 `LICENSE` + `NOTICE.md`
  （上游 repo、路径、检出提交 `d16211b`）。
- `data/OperitBundledPackages.kt`：
  - 运行时解析 METADATA（名称/描述/分类/工具数/env）；
  - 按源码实际调用面推断清单权限（`net:any` / `fs:any` / `shell` / `device`）；
  - 兼容状态 = 静态扫描：`可用` / `部分可用`（命中已知不支持面，写明原因）/ `需要 API Key`
    （METADATA 有 env 或包体用 `getEnv(...)`）；
  - 安装 = 现拼 `package.json + <包>.js` 的 zip → `PluginStore.installFile`，与导入 zip 同一条链路。
- `PackagesScreen`：TabRow 变成「插件 / 包 / 技能 / MCP」；包行显示分类/工具数/权限/状态/原因，
  未安装 →「安装」，已安装 →「已安装」（点开插件详情去授权）。
- 31 个包静态归类：**可用 10、部分可用 11、需要 API Key 10**。

---

## 三、真机验证（全部来自 logcat / 截图，不是代码推断）

### 3.1 导入与工具注册

7 个测试包逐个导入（每个都是 1KB 级 zip：`package.json` + 原包 .js）：

```
operit-crossref            注册 6 个工具
operit-extended-file-tools 注册 9 个工具
operit-system-tools        注册 33 个工具
operit-google-search       注册 4 个工具
operit-various-search      注册 14 个工具
operit-extended-http       注册 4 个工具
operit-12306               注册 9 个工具
```

（日志：`DshPlugin: toolpkg wrapped id=...` / `debug import ok=true ...`）

### 3.2 文件工具（`Tools.Files`）

```
file_exists /sdcard/Download
→ {path, exists:true, isDirectory:true, size:0, env:"android", ok:true}

zip_files  src → out.zip
→ {operation:"zip", successful:true, details:"已压缩 2 个条目 → .../out.zip"}

file_info out.zip
→ {exists:true, fileType:"file", size:271, permissions:"rw-", lastModified:"2026-11-09T18:32:25.000Z"}

unzip_files out.zip → unzipped/
→ successful:true（落盘 .../unzipped/src/a.txt、b.txt，adb find 已确认）

copy_file / move_file
→ successful:true（落盘 moved.txt，adb find 已确认）

copy_file 目录 src → dircopy（recursive:true）
→ successful:true；adb find 确认 dircopy/a.txt、dircopy/b.txt 都在
```

### 3.3 网络工具（`Tools.Net.visit` / OkHttp）

```
search_by_keyword {"query":"machine learning","rows":2}   （crossref，OkHttp client.get）
→ Found 3090762 results (showing 2):
  === Article 1 ===
  Title: Machine learning with sklearn
  DOI: 10.1093/oso/9780198828044.003.0003
  ...（真实 Crossref API 数据）

search_bing {"query":"DeepSeek"}   （various_search，Tools.Net.visit + HTML→文本）
→ {platform:"bing", content:"DeepSeek - 搜索 ... 百度百科 ... deepseek.com ..."}

http_request GET https://api.crossref.org/works?rows=1   （extended_http，全局 toolCall）
→ {statusCode:200, contentType:"application/json", headers:{...真实响应头...}, content:"{...}"}
```

### 3.4 系统 / 设备（`Tools.System.*` + `device` 授权）

```
get_device_info
→ {deviceId, model:"PEMM00", manufacturer:"OPPO", androidVersion:"13", sdkVersion:33,
   screenResolution:"720x1504", totalMemory:"5.5 GB", availableMemory:"2.8 GB",
   totalStorage:"107.5 GB", availableStorage:"79.6 GB", networkType, accessibilityService:false, ...}

list_installed_apps {"include_system_apps":false}
→ {includesSystemApps:false, packages:["cn.wps.moffice.lite", "com.coloros.calculator", ...]}

get_system_setting {"setting":"screen_brightness"}
→ {namespace:"system", setting:"screen_brightness", value:"2400"}

device_status   （daily_life 包，导入自「包」tab）
→ {battery:{level:100,charging:false}, memory:{...}, storage:{...}, device:{...}}
```

### 3.5 12306（诚实失败）

```
get_stations_code_in_city {"city":"北京"}
→ 错误: 查询失败: Error: get LCQuery path failed.
```

站名 JS 已取到（否则会报 `get station name js file content failed`），失败在 12306 的
`/otn/lcQuery/init` 页面里没有 `var lc_search_url = '...'`——站点反爬/返回页不同，不是兼容层没接。

### 3.6 包管理「包」tab

- 截图 `docs/shots/94-02-operit-packages-tab.png`：31 个内置包、分类/工具数/权限/状态/原因、安装按钮；
- `94-03`：滚动到 Draw 分类，10 个「需要 API Key」的包都写明了 env 名字；
- `94-04`：搜索 `time` → 命中「时间 / 系统工具 / 日常生活工具包」；
- `94-05`：点「安装」日常生活工具包 → 插件 17→18，行变「已安装」，
  logcat：`内置包安装 operit-daily-life ok=true；注册 18 个工具`；
- `94-01` / `94-06`：包管理首屏（插件 18 · 包 31 · 技能 2 · MCP 2）。

---

## 四、本批没做 / 限制（必须说实话）

1. **`Tools.Memory` 没接**：Operit 记忆工具和鲸歌内置 8 个记忆工具重叠，需要按 DSH 语义映射（下一批）。
2. **`getEnv(...)` 没接**：10 个需要 API Key 的包在「包」tab 标「需要 API Key」，装上后调用会
   ReferenceError；Operit 的 env 配置页没搬。
3. **`visit_key` 续页**：`Tools.Net.visit` 不返回 visitKey，`various_search` 的「逐条跟进链接」
   会退化到直接 URL（能出结果，但没有 follow 页解析）。
4. **终端不跨调用**：`Tools.System.terminal` 是 `shell.exec` 模拟，`create/exec/close` 在单次工具
   调用内有效；`input/screen` 明确不支持（Operit 真 PTY 在鲸歌已有 `thirdparty:terminal`，可下批接桥）。
5. **无障碍没开**：设备当前 `enabled_accessibility_services=null`，`Tools.UI.*` 会返回
   「无障碍服务未连接」。桥的代码路径已编入，但没有真机手势/截图证据（下一批开无障碍再验）。
6. **工具名冲突**：`google_search` 和 `various_search` 都注册 `search_web`，后者先装先占用；
   `google_search` 的 `search_web` 不会被注入（`search_scholar` 正常）。
7. **静态分类有误差**：`可用/部分可用/需要 API Key` 是扫描源码得出的，不等于每个工具都跑过。
   本批真机跑过的是：文件 8 个、crossref 6 个中的 1 个、various_search 的 bing、extended_http、
   system_tools 的 4 个、daily_life 的 2 个、time 2 个、duckduckgo 2 个。
8. **权限推断按调用面**：包里没用 `Tools.Files` 却写死 `/sdcard` 路径的，可能漏 `fs:any`；
   装上后调用会拿到权限错误（不是静默失败）。

---

## 五、下一步

> **2026-11-10 03:1x 更新：下面 1–4 已在 M68 做完，见 `docs/95`。**
> 剩余：`Tools.Files.download` 的 `visit_key` + `image_number`、真包的 `update_user_preferences`。

1. ~~`Tools.Memory.*` → 现有 `MemoryStore`（8 个方法能一一对上）~~ → M68 已完成（`memory` 权限）。
2. ~~`getEnv` → 插件配置页：METADATA env 生成 Config schema，值存 `plugins.json`，`getEnv` 读配置~~ →
   M68 已完成（含「必填 env 没填就拒绝执行」）。
3. ~~`Tools.System.terminal.*` → `thirdparty:terminal` 的真 PTY~~ → M68 已完成（`cd` 跨调用保留）。
4. ~~开无障碍后补 `Tools.UI.*` 真机证据（tap/swipe/截图）；`getPageInfo` 用内置 `dump_ui` 映射~~ →
   M68 已完成（`getPageInfo` 按 Operit `UIPageResultData` 结构映射，`activityName` 取不到，如实留空）。
5. ~~`visit_key`：宿主侧缓存「最近一次 visit 的链接表」，支持 `link_number` 续页~~ →
   M68 已完成，且按 Operit 语义改成「按 visitKey 缓存整份结果、可多份并存」（`PluginVisitCache`，LRU 32）。


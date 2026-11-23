# 190 下一轮任务书：返回栈 / 提示词 / Node 自治

> 交接日期：2026-11-23 夜。上一轮（docs/188、T1–T8）已收尾，HEAD `f318939`，工作树干净。
> **本轮 tokens 无限制、不用考虑经费**；子代理（`subagent` / `subagent_fork`）可以随便开，只把
> **构建 / 模拟器与真机操作 / 提交** 串行化。
> **当前没有无线调试设备**：先用本机模拟器做能做的，做不了的列进「待真机」清单等用户给设备，**不要**
> 写「真机通过」。
> 版本仍 `0.3.5 / versionCode 8`：**不升版、不打 tag、不建 Release、不动 `D:\dsh\native-publish`**。

---

## 0. 一句话

把用户这三类反馈做完：**① 导航返回栈 bug；② 提示词体系（串段排查 + 内置提示词可关 + 全英文化）；③ Node 宿主自治（AI 全控 / 自修 / 降级可用 / 启动弹窗）**，外加两项小清理（长期删除「AI 全控」开关、助手配置提示词长度提示）。模拟器先行、真机补验。

---

## 1. 环境与铁律

### 1.1 仓库与基线

- 工作目录 `D:\dsh`，仓库 `D:\dsh\native`，分支 `feat/integration-187`，HEAD `f318939`（本轮开始前干净）。
- 版本 `0.3.5 / versionCode 8`；发布树 `D:\dsh\native-publish` 停在 `89b1455`，**不要动**。
- APK：`app/build/outputs/apk/debug/app-debug.apk`（≈190,679,841 B，含 x86_64 与 arm64 两套通用库；
  `libnode.so` 等 Node/终端库**只有 arm64**）。
- 测试基线：`1056 用例 / 0 失败`（22 模块、137 suite，于 `f318939` 实测），数字只增不减。

### 1.2 设备现状（硬约束）

- **没有无线真机**（`192.0.2.10:35789` 已不可用）；`adb devices` 目前为空。
- 本机只有 **x86_64 模拟器**：`emulator -avd FBD_API30`（Android 11 / API 30 / google_apis / x86_64 / 1080×2340 / RAM 1536M）。
- ABI 影响（**不是 bug，别乱修**）：
  - Node 宿主、原生插件运行时（QuickJS）、终端（bash/pty）、proot 在 x86_64 上**不可用**（库只有 arm64）→
    Node 宿主会走到 `FAILED`（缺 `libnode.so` / ABI 不符）；
  - 这意味着 **C（启动弹窗）能验「解压 → 启动 → 失败」与取消/重试，验不了 READY**；
  - **B（Node 自治）** 能验工具面、错误路径、`nodehost_doctor` 的 ABI 诊断、假状态；**READY/真启动/真修复待真机**；
  - A / D / E / F / G 在模拟器上完全可验。
- 用户随时可能给真机：**设备到位后按 §6 清单补验**，别把「待真机」写成通过。

### 1.3 红线（违反即回滚）

1. 不升版、不打 tag、不建 Release、不动 `native-publish`、不 push（本地 commit 见 §7）。
2. 不改 `session.v4`；不给 `@deepseek-ai/dsh` 打补丁；Node 插件不得起 dsh turn engine / agent loop / `dsh web`。
3. 不为单个插件加原生专属落点（host shim / 工具必须通用）。
4. **不读/不输出/不提交设备 prefs 里的 API key**（只允许 grep `dsh_shell/approval_mode/node_plugin_host_enabled/llm_provider_id/theme_mode/accent_id/onboarding_done` 这类键）。
5. 中文文件（`.md`、JSON 里的中文、`docs/**`）**只能用 read/edit/write 工具**读写；严禁 PowerShell `Get-Content -Raw` + `Set-Content/WriteAllText` 改写（会按 ANSI 读坏）。
6. 同一时刻**只有一个 agent** 跑 Gradle / 操作模拟器或真机 / 提交；构建前说一声「我要占构建」。
7. 不删 `docs/181-185-evidence`；不 qing 理用户会话/插件（除自己造的测试数据）。
8. **未验证不写「通过」**：模拟器上没验的、真机上没跑的，如实写「待真机」。

---

## 2. 已锁定的决定（用户已拍板，勿重议）

- **A**：补一级页返回栈（与二级页 `subReturnPage` 对称）；**聊天页返回仍退出 App**，不改。
- **C**：非模态小卡片；READY 1s 自动消失；FAILED 保持；按钮「收起 / 取消启动 / 重试 / 查看日志」；不提供永久「不再提示」。
- **E**：新内置插件 `builtin.prompt.core`「提示词：鲸歌内置规则」，默认开；关闭 = 不拼内置 sections + 计划策略，**保留**用户人设/系统提示词 + 用户装的插件段 + 运行时上下文；助手配置页加提示 + 跳转。
- **F**：永久删除「AI 全控」开关（prefs/接口/AppViewModel/PluginDevTools gate/错误码/契约测试），写类仍走全局审批 + 审计。
- **D**：先取证（marker 测试 + 抓最终请求体）再决定修哪层；**提示词不设硬上限**，加字数/token 估算与超长提醒。
- **G**：模型可见文本全英文（提示词资源 + 工具 description/参数描述 + 工具回灌给模型的提示语）；**UI/注释/日志保持中文**。
- **B**：v1 不允许 AI 直接改插件 JS；失败默认跳过自动启动 + 横幅；反复崩溃插件自动隔离；工具前缀 `nodehost_*`（避开宿主 `node_*` 动态工具）。

---

## 3. 任务包（按推荐顺序）

每包都写清：现状 → 落点 → 改法 → 验收。**验收分「模拟器」与「待真机」两栏，别混。**

### A. 设置页返回键回不到聊天（P0，0.5–1h）

**根因**：`ui/native-shell/.../ui/shell/native/NativeShellRoot.kt`
- `go()`（L110-124）把 `backPage` 无条件写成 `Settings` 且不保存设置页自己的来路；`backFromPage()`（L203-209）只跳转不恢复；
- 设置首页「记忆库/包管理/子代理/任务/终端/虚拟屏/无障碍/会话」都走 `onOpenFeature = { nav.go(...) }`（`NativePageDispatch.kt` L445-447），
  于是：设置 → 记忆库 → 返回 → 设置（`backPage` 仍是设置）→ 再按返回原地不动。
- 二级页那半边已有 `subReturnPage`（H3 修复），一级页漏了。

**改法**：
1. 加 `pageReturnPage/pageReturnSub` 两个 `rememberSaveable`，`go`/`goFromDrawer`/`openSubFromDrawer` 打开前保存 `backPage/backSub`，
   `backFromPage()` 跳转后恢复；`goFromChat`/`toChat` 清空。
2. 把状态机抽成 `NativeNavBackPolicy` 纯函数（`onPrimaryOpen` / `onBackFromPrimary` / `onSubOpen` / `onSubClose`），JVM 单测覆盖：
   聊天→设置→记忆库→返回→返回→聊天；设置→会话→返回→返回；设置→隐私→会话；抽屉→设置→记忆库→返回；聊天→模型配置→返回；设置→外观→返回→返回。
3. 真机/模拟器回归：`DshPage route` 日志 + 截图。

**验收**：模拟器上 6 条路径全部回到聊天；T8 的「切壳插件页 Tab 保留」不回归（`PluginsTabMemory`）。

### F. 永久删除「AI 插件全控」开关（0.5h）

**现状**：UI 已无入口，但残留：`SettingsStore.aiPluginControl` / `setAiPluginControl` / `KEY_AI_PLUGIN_CONTROL`；
`NativeShellHost.aiPluginControlEnabled` / `setAiPluginControl`；`AppViewModel` L1069 / L9831-9839；
`PluginDevTools` 构造参数 + L168/205/238/265/297 五处 gate + `AI_PLUGIN_CONTROL_DISABLED` + `aiControlError`；
`PluginDevToolsContractTest.kt` L81 断言。

**改法**：整条链路删除；`plugin_dev_grant/revoke/config/role` 永远可调；更新契约测试为「无关闭态」；
`docs/HANDOFF.md`、`docs/183/184` 属历史，不改，只在 `docs/190-evidence/` 里记「已永久删除」。

**验收**：全量测试通过；`grep -r ai_plugin_control` 只剩历史文档；模拟器上调一次 `plugin_dev_grant` 成功。

### D. 提示词「串段」取证与修复（1.5–2.5h）

**现象**：用户报「系统提示词过长时，文字会跑到人设里（或反过来）」——**不是 UI 溢出，是模型收到的内容串了**。

**现状（已核）**：`persona` → `deployment:persona-prefix`（order 0，前面自动补「你的名字是「X」」）；
`system` → `deployment:persona-suffix`（order 10200）；`PromptPack.systemFor` 各自成段、按 order 拼接；
`AssistantProfile` JSON 键位正确；`app/src/main/.../AppViewModel.kt` L3459-3460 / L10320-10321 映射正确。
目前**没有**找到必然串段的代码路径，所以先取证。

**取证（必做）**：
1. 设一份测试助手：人设首行 `PERSONA::AAA`，系统提示词首行 `SYSTEM::BBB`，各写 2000+ 字；记下当前值以便还原。
2. 发一轮真实请求（debug 通道），抓**最终请求体**：`files/requests.log` / `files/cache-debug.jsonl`（`--ez dsh_cache_debug true --es dsh_send ...`）
   或 `DshLlm` 请求日志；确认两个 marker 在 system 里的段落与顺序。
3. dump `assistant_profiles` JSON（只读，脱敏）看落盘两个字段是否已串。

**分支**：
- 落盘串 → 修保存链路（`SettingsStore.updateAssistantProfile` / `AssistantProfile` 序列化），按字段重建。
- 落盘对、请求串 → 修 `PromptPack.systemFor` 的段合并/order（重点：同名段覆盖、`extraSections`、`plan:policy`、预设 apply 的 prefix/suffix 映射）。
- 都不串 → 判定为「长系统提示词影响模型对人设的遵循」，**不改拼装**；在助手配置页加字数/≈token 计数与超长提醒（>8k tokens 建议拆分），文档写清结论。

**验收**：模拟器上 marker 测试通过（两字段各 2000+ 字不串）；测试后助手配置还原成用户原值。

### C. Node 启动小弹窗（2–3h）

**现状**：只有插件页一张状态卡（`PluginsScreen.kt` L361，`NodeHostStatusUi` 7 档）；无全局启动提示；
首次解压 25MB/2526 条目，进度常量 `NodeRuntimeInstaller.TOTAL_ENTRIES = 2184`（实际 2526，会冲到头）。

**改法**：
1. `NodeBootOverlay` 挂在 `NativeShellRoot` 顶层（任何页面可见）：标题（准备运行时/解压 x%/启动中/重启第 n 次/已就绪/失败）+
   进度条 + 阶段细节 + 已用秒数 + 工具数；按钮「收起 / 取消启动 / 重试 / 查看日志」。
2. 触发：用户开开关、开机自动启动、AI `nodehost_control start`；READY 后 1s 自动消失并轻提示；FAILED 保持。
3. 进度：解压时按真实 zip 条目数算百分比（运行时读中央目录，或常量改 2526 + 断言测试）；回调 ~100ms 节流。
4. 首次文案：「首次解压约 25MB，之后 1–3 秒」。
5. 需要 `NativeShellHost` 暴露结构化 `NodeBootProgressUi(phase, percent, detail, elapsedMs, error)` 与 `nodeRetry/nodeStop/nodeOpenLogs`。

**验收**：
- 模拟器：真实走到「解压 → 启动 → 失败（x86_64 缺 libnode）」，验卡片四态、取消、重试、收起；截图存 `docs/190-evidence/c-boot/`。
- **待真机**：READY 自动消失、1–3 秒完成、切后台不打断。

### E. 内置插件：鲸歌自带提示词开关（2–3h）

**改法**：
1. 新增 `BuiltinPlugins` 条目 `builtin.prompt.core`（显示名「提示词：鲸歌内置规则」，默认开，kind=Prompt 或独立分组）。
2. `PromptPack.systemFor` 加 `includeBuiltinSections: Boolean = true`；false 时不并入 `sections`（含 `plan:policy`），
   只留 `personaPrefix/personaSuffix/extraSections`；`PromptProviders.build` 透传。
3. `AppViewModel` 从 `builtinStore.isEnabled(builtin.prompt.core)` 传值；关闭时 `DshPrompt` 日志标注。
4. 助手配置页顶部提示「鲸歌内置提示词已关闭」+ 跳内置插件页。
5. JVM 测试：关闭时内置段名一个不剩、persona/suffix/插件段仍在；默认行为逐字节不变（现有 `SystemAssemblyTest` 兜底）。

**验收**：模拟器上开关切换 + 抓请求体确认 sections 消失；默认开启时与 `f318939` 行为一致。

### G. 提示词 + 工具提示词全英文化（4–6h，可 2 个子代理并行）

**范围（模型可见）**：
1. `core/prompt/src/main/resources/prompt/0.2.2-rc.1/*`：`sections.json`（剩 1 行中文）、`tools-android.json`（1 行）、
   `tools.json`、`system.txt`、`plan-policy.txt`。
2. 所有工具元数据 `description` + 参数 `description`（含 JSON schema 内的说明）：
   `core/tools`（bash/read/write/grep/glob/curl/web/schedule/goal/plan/skill/workflow/subagent/present/ask/todo…）、
   app 侧 `device/tools`（device_info/dump_ui/tap/虚拟屏等）、terminal、memory、capability、`plugin_dev_*`。
3. 工具**回灌给模型**的短提示/错误（如「Node 宿主未就绪（…）」「参数非法」）；用户弹窗/Toast 保持中文。
**不动**：工具名、参数键名、代码注释、日志、UI 文案。
**做法**：按模块切两包（`core/tools` 一包、app 侧一包）并行，只改字符串、逐字保语义、schema 结构不变。
**验收**：新增 JVM 测试「模型可见工具元数据（description/参数描述）不得含 CJK」+「prompt 资源不得含 CJK」；
全量测试；模拟器发一轮确认请求里 system 与工具表全英文。

### B. Node 宿主自治（4–6h，可分两小步）

**目标**：AI 能看/能动/能修 Node 宿主；宿主坏了 App 仍可用；插件不兼容时自动隔离。

**现状**：`plugin_dev_*` 16 个只覆盖原生插件；`PluginDevTools/PluginStore` 完全不碰 Node；
Node 侧 API 都在（`NodePluginHost`：`start/stop/reload/tailLogs/manifestEntries/plugins/installPluginZip/uninstallPlugin/rssKb`；
状态机 `Disabled/Extracting/Starting/Restarting/Ready/Failed/Stopped`；崩溃策略；`node-audit.jsonl`），但没有 AI 工具。

**新增 builtin 组 `builtin.node.host`（默认开）**，工具必须**静态注册**（宿主 off/failed 也能调，不走 `NodeToolSurface`）：

| 工具 | 类型 | 作用 |
|---|---|---|
| `nodehost_status` | 只读 | enabled/state/pid/port/RSS/version/runtime 版本/启动耗时/崩溃次数/工具与插件计数/最后错误 |
| `nodehost_plugins` | 只读 | 清单 + 已加载模块（ok/error/tools）+ 失败原因 + 已注册模型/命令 |
| `nodehost_logs` | 只读 | 宿主 stdout/stderr tail + 审计 tail，grep/lines；脱敏 |
| `nodehost_doctor` | 只读 | runtime 文件/版本/zip 条目、host.mjs、清单解析、磁盘、端口、ABI、上次崩溃、失败插件 → findings + 修复动作 |
| `nodehost_control` | 写 | start / stop / restart（reason、退避、≤3/5min） |
| `nodehost_repair` | 写（审批） | reload / restart / reinstall-runtime（不动插件）/ quarantine(id) / clear-cache |
| `nodehost_autofix` | 写（审批） | doctor → 阶梯修复 → 返回「找到什么/做了什么/现在状态/证据」 |
| `nodehost_install` / `nodehost_uninstall` | 写 | Node 插件 zip 安装 / 卸载（走 `NodePluginInstaller` 限额） |

**韧性**：上次以 Failed 结束 → 本次开机跳过自动启动 + 横幅；反复崩溃插件自动 quarantine（禁用+通知+审计）；
宿主连续崩 3 次 → Failed（已有）+ 不自动拉起。**提示词**新增 `device:node-admin` 段（英文），告诉模型先 `doctor` 再动手。
**安全**：写类走全局审批档；全部进审计；日志脱敏；路径限 `filesDir/node`、`files/plugins`、工作区。

**验收**：
- 模拟器：`nodehost_status/plugins/logs/doctor` 可用（宿主 FAILED 时）；`doctor` 要能指出「x86_64 缺 libnode」；
  `nodehost_control start` 走到 FAILED 并返回原因；UI 上弹窗与状态卡一致。
- **待真机**：READY、`kill -9` 重启/计数、改名 `host.mjs` 后 `reinstall-runtime` 修好、必崩夹具插件被 quarantine 后其余可用、脱敏抽查。

---

## 4. 顺序与批次（建议）

| 批次 | 内容 | 门禁 |
|---|---|---|
| 1 | A + F | 全量测试 + 模拟器 6 条导航 + `grep ai_plugin_control` 清干净 |
| 2 | D + C | D 取证结论落文档；C 三态截图（模拟器）+ 待真机清单 |
| 3 | E + G | 提示词测试 + 英文约束测试 + 模拟器抓一轮请求体 |
| 4 | B | 单测 + 模拟器错误路径；真机到位后补 READY/修复 |

每批结束：`assembleDebug` + 全量测试（命令见 §5.3）+ 证据落 `docs/190-evidence/<包>/` + 更新 `docs/188-evidence/README.md` 的「本轮做了什么」。

---

## 5. 模拟器操作手册

### 5.1 启动 / 装包

```powershell
$adb = 'C:\Android\platform-tools\adb.exe'
# 启动（后台跑；首次冷启 1–3 分钟；RAM 1536M）
Start-Process 'C:\Android\emulator\emulator.exe' -ArgumentList @('-avd','FBD_API30','-no-snapshot-load','-no-boot-anim','-gpu','swiftshader_indirect')
& $adb wait-for-device
# 等 sys.boot_completed=1（轮询或 sleep 后检查）
& $adb -s emulator-5554 shell getprop sys.boot_completed
# 装包（≈190MB，30–90s）
cd D:\dsh\native
& $adb -s emulator-5554 install -r --no-streaming -t app\build\outputs\apk\debug\app-debug.apk
```

注意：模拟器 RAM 只有 1536M，Compose + 33 个 dex 首次启动慢；不要同时跑 Gradle 构建。

### 5.2 通用命令

```powershell
$d = 'emulator-5554'
& $adb -s $d shell "am force-stop xin.dshaa.nativeapp"
& $adb -s $d shell "am start -W -n xin.dshaa.nativeapp/.MainActivity --es dsh_shell liquid"
& $adb -s $d shell 'input keyevent 3'
& $adb -s $d shell 'logcat -d -v time' | Out-File -Encoding utf8 <file>   # 只用 -d 快照，禁 -s 流式
cmd /c "$adb -s $d exec-out screencap -p > D:\...\x.png"                   # 必须 cmd /c 重定向
& $adb -s $d shell 'uiautomator dump /sdcard/x.xml'; cmd /c "$adb -s $d exec-out cat /sdcard/x.xml > ..."
```

- 调试 extra **只在冷启动生效**（每次 `am force-stop` 再 `am start`）；热启动只认 `dsh_floating*`/`dsh_virtual_*` 等少量。
- 模拟器 `adb root` 可用（google_apis），但**没有 app 可用的 su**；终端/虚拟屏/shell 身份相关 **不要**在模拟器上纠结。
- 模拟器上 Node：解压会正常跑（runtime.zip 与架构无关），启动时因缺 `libnode.so` 进 FAILED——这是**预期**，正好用来验 C/B 的失败路径。

### 5.3 构建与测试

```powershell
cd D:\dsh\native
$env:JAVA_HOME='C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot'
.\.tools\gradle-8.9\bin\gradle.bat --console=plain :app:assembleDebug `
  :app:testDebugUnitTest :support:testDebugUnitTest :core:engine:test :core:plugins:test `
  :core:session:test :core:attachment:testDebugUnitTest :core:tools:test :core:prompt:test `
  :core:primitives:test :core:llm:test :core:protocol:test :core:model:test `
  :core:agent-api:test :core:agents:test :core:backup:test :core:capability-api:test `
  :core:input:test :core:jobs:test :core:legacy:test :core:parity:test :core:skill:test :core:workflow:test
```

汇总 XML：`Get-ChildItem -Recurse -Filter 'TEST-*.xml' | Where FullName -match '\\build\\test-results\\'`，
累加 `tests/failures/errors`；基线 **1056/0**。

---

## 6. 设备到位后要补的真机清单

1. **C**：真实 READY（首次解压 25MB → 1–3 秒启动）、自动消失、切后台不打断、失败重试。
2. **B**：`nodehost_control start` → READY；`kill -9` 宿主 → 自动重启/计数；改名 `host.mjs` → `doctor` 指出 → `reinstall-runtime` 修好；必崩夹具插件 → `autofix` 隔离后其余可用。
3. **G/E/D**：真机抓一轮真实请求体，确认系统提示词与工具表全英文、内置提示词开关生效、marker 不串。
4. **A**：真机六条导航路径 + 从聊天/抽屉/设置三种入口。
5. 真机状态还原：`dsh_shell=liquid`、`node_plugin_host_enabled=false`、`approval_mode=ask`、模型默认、测试数据清理、`force-stop` 收尾。

---

## 7. 提交与文档

- 允许**本地 commit**（不 push、不 tag、不升版），建议每批一次；提交前：`git diff --check`（源码）、`git status --short`、全量测试 0 失败。
- 证据放 `docs/190-evidence/<a-nav|d-prompt|f-ai-control|c-boot|e-builtin-prompt|g-english|b-node>/`：截图、dump、logcat 快照、结论 md。
- 每批更新 `docs/188-evidence/README.md` 的「本轮做了什么」表格（T 系列新增行）与 `docs/190-evidence/README.md`（可新建索引）。
- 不提交：历史 `docs/035/179/181/182/183/185`、m86* 系列、APK、仓库外快照。

---

## 8. 汇报格式（最终给用户）

≤20 行：每包「达成/部分/待真机 + 证据路径 + 关键数字」；模拟器实测与真机待验分开列；测试数字（含新增用例）；
事故/未还原项单列；设备与仓库末态（branch/HEAD/版本/工作树）；明确「未升版、未 tag、未 push」。

---

## 9. 已知坑（沿用上一轮，别重踩）

1. `adb logcat -s TAG:I` 流式会挂 → 只用 `logcat -d` 快照。
2. 截图必须 `cmd /c "adb ... exec-out screencap -p > x.png"`；PowerShell `>` 写坏 PNG。
3. 调试 extra 只在冷启动生效；每次 `am force-stop` 再 `am start -W`。
4. 中文文件只用 read/edit/write；PowerShell 5.1 的 `Get-Content` 默认按 ANSI 读 UTF-8。
5. Kotlin KDoc 里 `/*` 会嵌套吞文件；`ui/` 不能 import `app/`；`DshTheme2.colors` 不能在 `drawBehind{}` 读。
6. `runtime.zip` 若改动必须升 `RUNTIME_VERSION` 并重打（当前 `node26.4.0-dsh0.2.2-rc.5-p3.1`，SHA256 `7C438281B2DDDC502DD13900FEF353FF236435474319008C915448B8C80D70C5`）——本轮**不打算动**。
7. `:app:testDebugUnitTest` 等任务有缓存：要真实数字先删 `build/test-results` 或加 `--no-build-cache`。
8. 模拟器不是真机：所有「性能/内存/真机行为」结论必须标「待真机」。

---

## 10. 关键文件索引

- 导航：`ui/native-shell/src/main/java/xin/dshaa/nativeapp/ui/shell/native/NativeShellRoot.kt`、`NativeNavBackPolicy.kt`、`pages/NativePageDispatch.kt`
- 提示词：`core/prompt/src/main/java/xin/dshaa/core/prompt/{PromptPack,PromptProvider}.kt`、`resources/prompt/0.2.2-rc.1/*`、`AppViewModel.kt`（L7094/L7200/L10932/L10950）
- 助手配置：`ui/native-shell/.../ui/settings/AssistantScreen.kt`、`ui/shell-api/.../data/{AssistantProfile,SettingsStore}.kt`、`ui/design/.../component/Settings.kt`
- 插件控制：`app/.../data/PluginDevTools.kt`、`BuiltinPlugins.kt`、`ui/shell-api/.../NativeShellHost.kt`
- Node：`app/.../node/{NodePluginHost,NodePluginInstaller,NodeRuntimeInstaller,NodeCrashPolicy,NodeAuditLog}.kt`、`chat/AppViewModel.kt`（L8245/L8313）
- 插件页：`ui/native-shell/.../ui/plugins/PluginsScreen.kt`、`pages/NativePluginsPage.kt`、`ui/shell-api/.../PluginExtensions.kt`

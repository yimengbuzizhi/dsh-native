# 03 · 借鉴 Operit：调研与方案修订

> 调研日期 2026-11-06｜对象：[AAswordman/Operit](https://github.com/AAswordman/Operit)（Android 版）
> 结论：**5 个硬难点里有 4 个它已经做完了**，方案据此修订（见 §五）。

---

## 一、它是什么（实测数据）

| 项 | 值 |
|---|---|
| 仓库 | `AAswordman/Operit`，9,297 star / 805 fork，默认分支 `main` |
| 语言/UI | **Kotlin + Jetpack Compose**，仓库 592 MB，最近提交 2026-11-06（今天） |
| License | **LGPL-3.0-only**（`LICENSE` 为 GNU LGPL v3 全文） |
| 平台 | Android 8.0+（API 26），**仅 arm64-v8a** |
| Gradle 模块 | `:app`、`:terminal`（独立仓库子模块）、`:quickjs`、`:mnn`、`:llama`、`:mmd`、`:fbx`、`:dragonbones`、`:showerclient` |
| 外部依赖 | 构建需 `subpack.zip` / `jniLibs.zip` / `libs.zip` 三份归档（**这点别学**） |

### 核心目录结构（`app/src/main/java/com/ai/assistance/operit/`）

```
api/           对外入口（AIForegroundService 在这里）
core/
  chat/        会话与消息
  tools/       默认工具：SystemToolPrompts.kt / ToolRegistration.kt
               defaultTool/{standard,debugger,admin,root,accessbility}
               javascript/JsTools.kt（JS 桥）
  config/      配置（含全部工具 prompt 与 schema）
  workflow/    工作流引擎
  subpack/     子包
data/          ObjectBox 实体
services/      ChatServiceCore、FloatingChatService、core/*Delegate
ui/features/   Compose 界面（chat、settings、market…）
plugins/       ToolPkg 加载
integrations/  MCP / 外部集成
```

---

## 二、它已经解决的问题 ↔ 我们方案里的难点

| 我们的难点（`01-方案` §五） | Operit 的答案 | 可借鉴度 |
|---|---|---|
| **1. 终端 PTY**（Android 无 `openpty`，要 JNI/helper） | `OperitTerminalCore`：独立 Android 模块，`TerminalManager` 单例 + `TerminalService`（AIDL `ITerminalService`/`ITerminalCallback`），Kotlin Flow 暴露状态，多会话、支持 **Ubuntu 24.04 PRoot** 用户空间 | ★★★★★ 可直接用 |
| **2. 会话格式 v0→v4** | 无对应（他们自有格式） | ☆ 只能自己搬 |
| **3. 流式渲染性能** | `RENDERER_ARCH.md`：自研 **KMP 状态机**流式 Markdown 解析器（`StreamKmpGraph` / `StreamPlugin` / 两阶段块级+内联解析 / `BatchNodeUpdater` 批量重组） | ★★★★★ 直接省 3–4 周 |
| **4. 后台保活** | `AIForegroundService` 持有 `ChatRuntimeHolder`，内含 **MAIN / FLOATING 两个 `ChatServiceCore`**；`ChatViewModel` 只做 UI，不再持有运行时 | ★★★★☆ 架构照搬 |
| **5. 数据迁移** | 无对应（不同产品） | ☆ |
| **插件机制（悬而未决）** | **ToolPkg**：`.toolpkg` = ZIP + `manifest.json` + `main.js`，JS 跑在 **QuickJS JNI**（`:quickjs` 模块），支持子包、资源、i18n、WASM、Compose DSL 写 UI、生命周期/消息 hook、IPC | ★★★★★ 直接定案 |
| 工具注册与权限分层 | `SystemToolPrompts.kt`（schema 单一来源）→ `ToolRegistration.kt`（名字→执行器）→ `ToolGetter.kt`（**按权限档位选实现**：standard / debugger / root / admin / accessibility） | ★★★★★ 架构照搬 |
| 虚拟屏 | `showerclient`：内置 `shower-server.jar`（`app_process` 拉起）+ `ShowerController`（建虚拟显示、触控/按键注入、截图）+ 自带视频解码与 `ShowerSurfaceView` | ★★★★★ 直接替换我们的 vscreen |
| 浏览器自动化 | 内置多标签浏览器 + Agent 读页面结构 | ★★★ 可参考交互设计 |

---

## 三、可直接复用的东西（按"拿法"分三档）

### A 档 · 整模块引入（改动最小）

1. **`:terminal` ← OperitTerminalCore**
   - 形式：git submodule（他们自己也是 submodule）
   - 接口：`TerminalManager`（Flows）或 `TerminalService`（AIDL，UI 与终端可跨进程）
   - 省：PTY 实现 + 多会话 + 状态同步，**至少 3 周**，且消灭一个 P0 技术风险
2. **`:quickjs` ← QuickJS JNI 模块**
   - 形式：git submodule + CMake
   - 依赖：NDK（本机已有）
   - 用途：跑 ToolPkg / 用户脚本 / 自定义 provider
3. **流式 Markdown 渲染器**
   - 形式：把他们的 renderer 模块代码搬进 `:core:markdown-stream`（或在 `:ui:chat` 里）
   - 省：3–4 周，并直接解决"1000 轮不卡"的性能目标
4. **`:thirdparty:shower` ← showerclient（虚拟屏）**
   - 形式：模块引入（自带 `shower-server.jar` 与 JNI/视频解码）
   - 我们只需实现 `ShellRunner`（走 Shizuku / root）+ 注册一个 `exported` 广播接收器
   - 调用面：`ShowerServerManager.ensureServerStarted` → `ShowerController.ensureDisplay(w,h,dpi,bitrate)`
     → `touchDown/Move/Up`、`key`、`requestScreenshot`、`ShowerSurfaceView`
   - **替换我们现有的 vscreen**（`app_process` 核心 + 8998 桥 + 预览窗 + 指纹机制全部退役）
   - 省：整套虚拟屏桥与画面传输（约 3–4 周），且比我们的成熟
5. **原生 ripgrep**
   - 他们仓库 `tools/` 里带 Android 版 rg 产物；我们旧版也是自带 `runtime/bin/rg`
   - 形式：直接复用二进制，不再自己编

### B 档 · 照搬架构（重写但结构一致）

4. **工具三层**：`ToolPrompts`（schema）→ `ToolRegistry`（注册）→ `ToolResolver`（按权限档位选实现）
   - 映射我们的现实：`standard`=普通 App 权限，`shizuku`=ADB 级，`root`=su，`accessibility`=无障碍
   - 好处：同一个工具名在不同权限下换实现，AI 看到的 schema 不变
5. **前台服务持有会话运行时**：`ChatRuntimeHolder { mainCore, floatingCore }`
   - 直接解决旧版"引擎一停就断、切后台就崩"的问题
6. **工具 schema 单一来源 + 改参数 checklist**（他们写成了 `DEFAULT_TOOLS_ARCH.md`）
   - 我们的契约 #2 正好需要这个：schema 只在一处定义，导出即对拍基线

### C 档 · 只借鉴思路（不碰代码）

7. ToolPkg 的 manifest 设计（`api_version`、`requires` 依赖与加载顺序、子包、资源、i18n）
8. Compose DSL 插件 UI（插件能画界面）—— 我们 M3 之后再考虑
9. 商店/市场、角色卡、记忆图谱、本地模型 —— **不做**，见 §六

---

## 四、许可证：这是"能不能复制粘贴"的总开关

Operit 主仓和 terminal core **都是 LGPL-3.0-only**。LGPL 对"复制源码"的要求比 MIT/Apache 严：

| 做法 | 是否合规 | 说明 |
|---|---|---|
| 只读文档 / 看思路 / 自己重写 | ✅ 无约束 | 架构、模式不受版权保护 |
| 独立模块引入（submodule / AAR） | ✅ 可行 | 该模块保持 LGPL，需随包附许可证与源码获取方式 |
| 把源码**拷进**我们自己的类里、且**整个 App 闭源** | ⚠️ 高风险 | 属于"组合作品"，需提供 LGPL 部分源码 + 允许重新链接（APK 很难满足） |
| 把源码拷进来、**整个 App 也开源（LGPL 兼容）** | ✅ 合规 | 保留版权声明、标注修改、按 LGPL 分发 |

**结论（用户 2026-11-06 决定）**：新的原生 App **开源，采用 LGPL-3.0**。
因此上表最后一行适用：**可以直接复制源码**，只需保留版权声明、标注修改、随源码分发许可证。
我们仍保留两条纪律以降低维护成本：

- 能整模块引入的（terminal / shower / quickjs）优先 submodule，不复制进 `core/*`；
- 复制的文件顶部写来源与 commit，根目录 `NOTICE` 汇总，升级时按 commit 对比合并。

---

## 五、方案修订（相对 `01-方案` v1）

### 5.1 取消自研的四件

| 原计划 | 修订后 |
|---|---|
| 自研 PTY（JNI/helper） | 用 `:terminal`（OperitTerminalCore） |
| 自研流式 Markdown 渲染 | 移植 Operit 的 KMP 流式渲染器 |
| 插件机制三选一 | 定案：**QuickJS + ToolPkg 格式**（子集） |
| 自研虚拟屏（vscreen + app_process 核心 + 8998 桥） | **showerclient**（server.jar + Controller + 视频组件） |

### 5.2 模块表增删

```diff
 :app
 :core:model / :core:protocol / :core:prompt / :core:llm / :core:agent / :core:session / :core:tools / :core:fs / :core:shell
-:core:term（自研 PTY）
+:core:markdown-stream      流式渲染（移植）
+:plugins:runtime            QuickJS + ToolPkg 加载
+:plugins:api                插件对外 API（工具/UI/hook）
+:thirdparty:terminal        OperitTerminalCore（LGPL，独立模块）
+:thirdparty:shower          showerclient（LGPL，替换 vscreen）
 :device:*                  shizuku / a11y / overlay / browser
 :parity                    对拍台
```

### 5.3 P0 三门 → 四门

新增第 4 门：**复用验证**——把 `:terminal`、`:quickjs`、`:shower` 编进我们的工程，
跑通「开一个终端会话」「加载一个最小 ToolPkg」「建一块虚拟屏并截一张图」。两周内可验。

### 5.4 工期

| 阶段 | v1 | v2 |
|---|---|---|
| P0 | 6 周 | **5 周**（少两个未知，多一个集成验证） |
| M1 能聊天 | 12 周 | 12 周（不变，会话/提示词只能自己搬） |
| M2 能干活 | 12 周 | **9 周**（工具架构照搬 + 插件运行时现成） |
| M3 像 App | 14 周 | **11 周**（流式渲染现成） |
| M4 收口 | 12 周 | **10 周** |
| **合计** | ~13 个月 | **~9–10 个月** |

### 5.5 新增风险

| 风险 | 说明 | 应对 |
|---|---|---|
| 许可证 | 闭源时不能直接拷 | 先定开源策略；LGPL 模块隔离 |
| 上游漂移 | Operit 很活跃（今天还在提交），terminal/quickjs 接口会变 | 锁 commit，不跟 main；升级按季度 |
| 生态错位 | ToolPkg 是 Operit 的格式，与 DSH 插件不兼容 | 明确：原生版不承诺兼容 DSH 插件；我们的 5 个插件改为原生实现 |
| 构建复杂度 | 他们需要三份外部归档 | 只引 terminal/quickjs，避免引入 mnn/llama/avator |

---

## 六、明确不借鉴的东西

1. **592 MB 巨型仓库的结构**：角色动画、MMD/FBX、DragonBones、本地模型（MNN/llama.cpp）、市场、记忆图谱、Ubuntu 用户空间 —— 全是他们的产品特色，不是我们的需求。
2. **ObjectBox**：我们用 Room 就够，别引入新数据库。
3. **三份外部依赖归档的构建方式**：那是他们的历史包袱。
4. **他们的 UI 信息架构**：功能过多，我们会做减法。

---

## 七、需要拍板的 3 件事

1. **新 App 开不开源？**（决定能否直接搬 LGPL 代码，见 §四）—— **已定：开源**
2. **终端**：直接 submodule 引入 OperitTerminalCore，还是照它的接口自己实现一份（闭源时的替代）？—— 定：直接用
3. **插件体系**：采用 ToolPkg（子集）作为原生版的扩展格式，还是保留 DSH 插件包格式、我们自己写运行时？（前者省 4–6 周，后者保生态连续性）—— 定：ToolPkg

---

## 八、复制优先矩阵（全模块过一遍）

原则（用户 2026-11-06 明确定调）：**能复制就直接复制，能不写的就不写，能不用自己的就不用。**

| # | 我们的模块 | Operit 对应 | 结论 |
|---|---|---|---|
| 1 | 终端 | `:terminal`（OperitTerminalCore） | **模块引入** |
| 2 | 虚拟屏 | `showerclient` + `shower-server.jar` | **模块引入，替换 vscreen** |
| 3 | 流式 Markdown | `StreamKmpGraph` / `StreamMarkdownRenderer` | **复制源码** |
| 4 | 插件运行时 | `:quickjs` + ToolPkg 加载器 | **模块引入 + 复制加载器** |
| 5 | 工具注册 / 权限档位 | `core/tools/ToolRegistration.kt`、`ToolGetter.kt` | **复制架构与代码** |
| 6 | 工具 schema | `core/config/SystemToolPrompts.kt`（结构） | **借结构**，内容按 DSH 契约填 |
| 7 | 文件/搜索工具 | `core/tools/defaultTool/*` + 原生 rg | **复制实现**，按契约 #2/#4 对齐 |
| 8 | Shell 工具 | 同上（bash/pwsh 执行器） | **复制实现** |
| 9 | 无障碍 | `AccessibilityService` + a11y 工具集 | **复制** |
| 10 | Shizuku | 他们的 Shizuku 集成层 | **复制** |
| 11 | 悬浮窗 / 悬浮球 | `FloatingChatService` + 悬浮 UI | **复制** |
| 12 | 浏览器 + 网页 Agent | 内置多标签浏览器（在 `app` 内，不是独立模块） | **抽取复制**（成本待评估，M2 前定） |
| 13 | 模型 Provider | 他们已接 OpenAI/Anthropic/Gemini/兼容端点 | **复制 provider 层**，DeepSeek 按契约补 |
| 14 | 聊天会话运行时 | `ChatServiceCore` + 前台服务双槽位 | **复制架构**，消息模型换 DSH |
| 15 | 设置 / 权限引导 / 通知 | `ui/features/settings` 等 | **复制组件** |
| 16 | 会话格式 v0→v4 | 无 | **只能自研**（DSH 特有） |
| 17 | 提示词与工具行为 | 无 | **只能自研**（DSH 特有） |
| 18 | 控制台 / 主题 4 层 | 无等价物 | **只能自研**（DSH 产品特性，不是实现选择） |
| 19 | 会话管理 / 修复 / 回收站 | 无 | **只能自研** |
| 20 | 对拍台 | 无 | **只能自研**（我们的验收体系） |

**统计**：20 个模块里 **15 个可以不自己写**，只剩 5 个是 DSH 独有的（会话、提示词、控制台、会话管理、对拍）。

### 复制纪律（LGPL 合规）

1. 每个复制文件保留原版权头，文件顶部加一行：`// Ported from Operit (AAswordman/Operit) @<commit> — LGPL-3.0`
2. 建立 `NOTICE` 文件，列出所有复制来源与 commit
3. 修改处用 `// [DSH]` 标注，便于将来合并上游
4. 整个 App 以 **LGPL-3.0** 发布（与依赖一致），源码公开
5. 锁 commit：不跟 Operit main；升级单独走一次「同步分支」

---

## 九、v2.1 工期

| 阶段 | v1 | v2 | v2.1（虚拟屏也换掉） |
|---|---|---|---|
| P0 | 6 周 | 5 周 | 5 周（门 4 多验一个 shower） |
| M1 | 12 周 | 12 周 | 11 周（provider/设置可复制） |
| M2 | 12 周 | 9 周 | **8 周**（虚拟屏/无障碍/Shizuku 都复制） |
| M3 | 14 周 | 11 周 | 10 周 |
| M4 | 12 周 | 10 周 | 9 周 |
| **合计** | ~13 个月 | ~9–10 个月 | **~8–9 个月** |

真正剩下的工作量集中在：**DSH 会话格式 + 提示词/工具行为对齐 + 控制台主题 + 对拍台**。


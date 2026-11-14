# 鲸歌 · DSH Native

> [!WARNING]
> **开发阶段｜Work in Progress**
>
> 本项目仍在**活跃开发中**：功能、界面、数据格式和接口都可能随时改动，**不保证稳定性**，
> 请勿用于生产环境。目前所有发行版都是**预发布（pre-release）**。
>
> 非常需要各位用户的反馈：遇到 bug、功能缺失、体验问题，欢迎提
> [Issues](https://github.com/yimengbuzizhi/dsh-native/issues)，或加 QQ 群 **1131659935**（群名「鲸歌」）。
> 你的反馈会直接影响后续开发方向。

在 Android 手机上运行的 AI Agent 客户端：**Kotlin + Jetpack Compose 原生 UI**，
内置 Kotlin 原生 Agent 内核（不依赖 Node、不依赖 WebView）。

> 本项目是 DSH（DeepSeek Harness）手机版的原生重写。DSH 的协议、提示词与工具 schema
> 以 npm 包 `@deepseek-ai/dsh` 为基线移植（见 `upstream.toml`），App 代码全部自写。

## 一切皆插件（Everything is a Plugin）

鲸歌的目标不是「支持插件」，而是**宿主只留骨架**：工具、页面、提示词、模型、回合循环、会话存储、
界面壳、启动加载页、设备能力……每一类都可以由插件**提供、替换或停用**。仓库里的内置实现同样以
「内置插件」注册，和第三方插件走同一套开关、权限与回退路径。

> 默认界面仍是原来的原生 Compose UI（`builtin.shell.native`），体验不变。
> 插件化是「可替换」，不是重画界面。

### 能被替换的角色

| 角色 | 插件接口 | 内置默认实现 | 说明 |
|---|---|---|---|
| 工具 | `ctx.tools.register` | `builtin.fs` / `builtin.shell` / `builtin.web` … | 先登记者生效；参数需能转成 JSON Schema |
| 页面 | `ctx.ui.registerRoute/registerPage` + `dsh.client` | `builtin.page.*`（原 Kotlin 页面） | 第三方页面由插件自己的客户端 bundle 渲染 |
| 系统提示词 | `ctx.systemPrompt.register`（prompt/v1） | `builtin.prompt.dsh` | 整体拼装可换；段/变量仍可叠加 |
| 模型通道 | `ctx.llm.register`（model/v1，配置式或 `stream`） | `builtin.model.deepseek` / `builtin.model.openai` | 插件可直接产出 token 与工具调用 |
| 回合循环 | `ctx.agent.register`（agent/v1） | `builtin.agent.dsh`（原 Kotlin TurnEngine） | JS 回合 v1 只支持纯文本轮 |
| 会话存储 | `ctx.storage.register`（storage/v1） | `builtin.storage.jsonl` | 第三方 v1 只读；写入仍由内置存储负责 |
| 界面壳 | `ctx.ui.registerShell`（surface / webview） | `builtin.shell.native`（默认）、`builtin.shell.whale`（可选） | 壳负责整屏 UI；停用/崩溃自动回原生 |
| 启动加载页 | `ctx.ui.registerLoading`（loading/v1） | `builtin.loading.native` | 静态树 + 快照，冷启动首帧前显示 |
| 能力 | `ctx.capability.register`（capability/v1） | notify / clipboard / share 平台原语 | root / NFC / Shizuku / ADB 由插件提供 |
| 事件/投影/服务 | `ctx.on` / `ctx.sessionProjections` / `provide`+`inject` | — | 投影 checkpoint 按会话持久化 |

### 怎么用

1. 在插件页导入 zip（也可以让 AI 现场生成插件包），启用插件。
2. 在「插件 → 角色」里选中要用的实现（界面 / 回合 / 存储 / 提示词 / 模型 / 加载页）。
   **安装 ≠ 选中**，不选就一直是内置实现。
3. 出问题直接停用：宿主退回内置实现并给安全提示，不丢会话数据；启动失败时可用
   「重试 / 使用默认加载页 / 修复角色」恢复。

### 边界（不承诺）

- 插件运行在 **QuickJS**（与 App 同进程）：是能力调用边界与超时控制，不是内核隔离。
- 无 npm 安装、无 Node/DOM/Electron、无 DEX/APK 动态加载；相对 import 不解析，依赖要内联。
- 客户端插件只跑 Web 半侧；`ctx.ui.registerView` 未实现；`ui.view` 未实现（上游也没有这个 API）。
- agent/v1 的 `tools.invoke` / `llm.stream` / `storage.append` 仍返回 UNSUPPORTED；
  storage/v1 只读（不能新建/写入/回收站/分支）。
- 危险权限（`fs:any` / `net:*` / `shell` / `device` / `memory`）必须在插件页逐项授权，
  不随安装自动获得；没有提供方时如实显示「不可用」。

### 想深入

- 设计与终点：`docs/115-一切皆插件的终点.md`、`docs/144-M85-宿主骨架与一切皆插件-设计.md`
- 插件作者指南（随 App 以内置技能提供）：`dsh-plugin-authoring`；
  DSH/社区插件适配：`dsh-plugin-adapter`
- 各版本发布记录与已知限制：`docs/177-发布-v0.3.3.md`、[Releases](https://github.com/yimengbuzizhi/dsh-native/releases)

## 功能

- **对话**：流式输出、思考过程折叠、工具调用卡片、审批、计划模式、语音输入条、多模态图片、
  引用附件折叠 chip（模型拿全文，界面只显示可展开的一行）
- **多会话并行**：A 会话生成中可以切到 B 继续发消息，互不阻塞；后台会话在等审批/提问时，
  聊天页顶部会出横幅提示并可一键切过去
- **Agent 内核**：工具循环、自动/手动审批、子代理（subagent）、目标（goal）、后台任务（jobs）、
  定时任务、技能（SKILL.md）、上下文自动压缩
- **助手配置**：多份角色卡（名字/头像/简介/人设/开场白/系统提示词），侧边栏一键切换；
  人设与系统提示词按 DSH 语义注入，插件自带的助手配置只读展示
- **插件**：内置能力以「内置插件」形式随包提供（工具 / 页面 / 提示词 / 模型 / 回合 / 存储 /
  界面壳 / 加载页，见上节），全部可停用、可同 id 替换；第三方插件本地导入（QuickJS），
  兼容 **DSH 社区插件**（客户端界面宿主、提示词变量与预设插件、权限/配置模型）与
  **Operit 工具包**（31 个内置包一键安装，`Tools.*` 兼容层）。
  APK 只带宿主、内置插件与适配层，**第三方插件本体不随安装包分发**
- **记忆库**：Operit 式记忆库（SQLite、文件夹、图谱、搜索、8 个记忆工具）
- **设备能力**（可选，需要 Shizuku / 无障碍 / 悬浮窗权限）：
  - 读屏、点击、手势、截图（无障碍）
  - 虚拟屏（Shower：在后台显示另一个 App，可转发触摸）
  - 终端（PTY / proot Ubuntu rootfs）
  - 悬浮球（应用不在前台自动出现）
- **会话**：v4 会话格式、工作区、备份/恢复、旧版备份只读导入
- **模型**：多家供应商接入、模型列表多选、只切换已启用的模型
- **隐私与数据清理**：占用统计 + 网页缓存/Cookie、临时文件、头像缓存一键清理（二次确认）
- **外壳**：RikkaHub 式聊天落地 + 抽屉，Operit 式设置分组，深色/浅色主题、自定义强调色；
  宽屏（折叠屏/平板）常驻侧边栏

## 构建

要求：JDK 17+、Android SDK（compileSdk 34）、Gradle 8.9。

```bash
./gradlew :app:assembleDebug
```

产物：`app/build/outputs/apk/debug/app-debug.apk`

首次构建会从 Google/Maven Central 拉依赖；`local.properties` 里写 `sdk.dir=<Android SDK 路径>`。

## 模块

| 目录 | 内容 |
|---|---|
| `app/` | Android 应用（启动/加载页宿主、UI 入口、设备桥、插件管理） |
| `core/` | Agent 内核：engine / tools / prompt / session / protocol / llm / plugins / workflow / jobs / agents / skills / attachment / parity / `agent-api` / `capability-api` 角色契约 |
| `ui/shell-api/` | 壳与页面的宿主门面（`NativeShellHost`、`ShellConversation`、角色/设置类型） |
| `ui/native-shell/` | 内置原生界面插件 `builtin.shell.native` 的实现（原 Compose 页面） |
| `support/` | 设备/悬浮窗/附件等支撑层 |
| `ui/design/` | Compose 设计系统（颜色、字阶、组件） |
| `thirdparty/` | Operit 的 showerclient 与 OperitTerminalCore（源码复制，LGPL-3.0，见 `NOTICE.md`） |
| `docs/` | 设计、契约、移植与真机验收记录（开发过程文档） |
| `parity/` | 门 2 对拍夹具（SSE 录制/回放，逐字节比对） |

## 使用前提

- 一个 DeepSeek 账号或 API Key（设置 → 模型与参数配置）
- 设备能力都是可选的：不装 Shizuku 也能纯聊天；要用虚拟屏/终端才需要 Shizuku
- 社区插件（`dsh.client` / DSH 插件包 / Operit ToolPkg）需要自己导入，安装包不内置第三方插件

## 反馈与贡献

- **项目仍在开发阶段，非常需要真实使用反馈**：BUG、卡顿、交互不顺、缺什么功能，都可以提
  [Issues](https://github.com/yimengbuzizhi/dsh-native/issues)，或加 **QQ 群 1131659935**。
- 提问题时尽量带上：手机型号 / Android 版本 / App 版本（设置 → 关于 → 版本）、复现步骤；
  闪退或设备工具异常时附 logcat 会很有帮助。
- 欢迎 PR；提交前请确认 `./gradlew :app:assembleDebug` 能通过。
- 所有发行版都是**预发布（pre-release）**，不保证稳定，请按预览版对待。
- 每个版本的更新内容见 [Releases](https://github.com/yimengbuzizhi/dsh-native/releases)。

<img src="docs/assets/qq-group.jpg" width="260" alt="QQ 群 1131659935（鲸歌）">

## 许可证

[LGPL-3.0](LICENSE)。第三方来源与许可声明见 [NOTICE.md](NOTICE.md)。

## 致谢

- [Operit](https://github.com/AAswordman/Operit)（LGPL-3.0）：showerclient、OperitTerminalCore、
  Operit 自带工具包、部分 UI 信息架构
- [RikkaHub](https://github.com/rikkahub/rikkahub)（AGPL-3.0）：只参考信息架构与排版，未复制代码
- [QuickJS](https://github.com/bellard/quickjs)（MIT）：插件/工作流 JS 引擎
- [React](https://react.dev)（MIT）：客户端插件宿主的 React 18.3.1 UMD 运行时
- DSH 上游 `@deepseek-ai/dsh`：协议、提示词与工具 schema

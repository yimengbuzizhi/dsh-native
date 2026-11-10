# 83 · OpenAI 兼容供应商 + 所有文件可引用 + DSH 工具插件直导（2026-11-09，M55 第三批）

## 一、OpenAI Chat Completions 供应商

用户要求：模型配置要支持 OpenAI 格式的全部供应商，能拉模型列表。

### 供应商列表（`SettingsStore.providers`）

| id | 名称 | 协议 | 默认端点 |
|---|---|---|---|
| `deepseek-api` | DeepSeek API 密钥 | Anthropic Messages | `https://api.deepseek.com/anthropic` |
| `deepseek-openai` | DeepSeek（OpenAI 格式） | OpenAI | `https://api.deepseek.com` |
| `anthropic` | Anthropic 官方 | Anthropic Messages | `https://api.anthropic.com` |
| `openai` | OpenAI 官方 | OpenAI | `https://api.openai.com/v1` |
| `openai-compatible` | OpenAI 兼容（自定义） | OpenAI | `https://api.openai.com/v1`（可改成 Ollama / vLLM / 硅基流动 / OneAPI…） |
| `custom` | 自定义（Anthropic 格式） | Anthropic Messages | `https://api.deepseek.com/anthropic` |

`SettingsStore.protocol` 根据 provider 给出 `anthropic` / `openai`；模型配置页「高级设置」的协议说明随它变。

### 新增 `core/llm/OpenAiClient.kt`

内部仍用 `ApiRequest` / `LlmEvent`（Anthropic 形状），客户端负责翻译：

- 请求：system → system 消息；`ApiBlock.Text/Image` → content / image_url(data URL)；
  assistant `tool_use` → `tool_calls`；user `tool_result` → `role:"tool"`；tools → function 定义；
  认证 `Authorization: Bearer`；URL 兼容 `{base}/v1/chat/completions`。
- 流：`choices[0].delta.content` → TextDelta；`reasoning_content` / `reasoning` → ThinkingDelta；
  `tool_calls[].function.arguments` → ToolInputDelta；`finish_reason` 映射
  `length → max_tokens`、`tool_calls → tool_use`、`stop → end_turn`。
- `AppViewModel.testConnection()`、TurnEngine 的 `stream` 按 `settings.protocol` 选客户端；
  `providerLabel()` 同步返回 `openai` / `anthropic` / `deepseek`。

真机证据：`docs/shots/83-01-openai-turn.png` —— provider=`deepseek-openai`、model=`deepseek-chat`，
一轮真实请求返回「OK」（DeepSeek API key 只在设备配置里，没有落仓库）。

## 二、所有文件都能引用（照 Operit）

Operit 的做法：任何文件都复制到本地 → 消息里只放
`<attachment id="路径" filename="…" type="…" size="…"/>` → 模型用 `read_file` / `read_file_full`
或 PDF 直读通道取。鲸歌对应改成：

- 图片：原图片管线（内容寻址 + base64 + 视觉），上限 50MB；
- 文本 ≤512KB：仍内联成 `[附件: 名字]` + 正文；
- 文本 >512KB / 二进制（PDF、Word、zip、apk…）：原样复制到
  `<workspace>/.dsh/attachments/<时间戳>-<文件名>`，待发 chip 显示大小（`73 B`），
  发送时拼 `<attachment id=".dsh/attachments/…" filename="…" type="…" size="…"/>`，
  再补一句「带 attachment 的附件已复制到工作区，可用 read / read_image 工具按路径读取」。

为什么复制到工作区：鲸歌 `read` / `read_image` 的 `resolvePath` 有 workspace 路径守卫，
复制进去后不用放宽任何安全边界；复制上限 50MB。

证据：
- `docs/shots/83-06-pdf-reference.png` PDF 作为待发附件（这份测试 PDF 内容是 ASCII，走了文本内联；
  二进制路径引用用下面的 zip 验证）；
- `docs/shots/83-08-zip-reference.png` 二进制 zip 引用；
- 设备 `files/workspace/.dsh/attachments/1794236085586-dsh-hello.zip`（789 B）实际存在。

## 三、DSH 工具插件直导 + 适配 skill

### 运行时兼容（`core/plugins/PluginModule.kt`）

DSH 插件是 Cordis 插件：`package.json` + `dist/index.js`，`export name/inject/apply`，
`ctx.tools.register(defineTool({ parameters DSL, output, execute }))`。
鲸歌原有 `PluginModule` 已经有 ESM 转换、`defineTool` / `@deepseek-ai/dsh-tools` /
`@deepseek-ai/schemastery` / `node:path` 的 shim；这批补上：

1. `defineTool` 把 DSH `parameters` DSL 转成 JSON Schema（`required:true` → 根 `required`，
   `description/enum/default/items/嵌套 object` 保留），已经是 JSON Schema 的原样放行；
2. `node:fs` / `node:fs/promises` 映射到沙箱 `__dshFs`（原来只是空实现）；
3. `node:child_process` 映射到 `shell.exec`（要 `shell` 权限）；
4. `node:os` 最小实现。

真机验证（社区参考插件 `@codeasier/dsh-plugin-hello` 的 JS 等价包）：
- 导入：`已导入 dsh-hello 0.1.0；注册 1 个工具：hello`，`injected=[hello]`；
- 调用：`debug call name=hello args={"name":"Ada"} isError=false text=Hello, Ada!`
  （Config 默认值 `Hello` 也生效）。

### 内置 skill：`dsh-plugin-adapter`

`app/src/main/assets/skills/dsh-plugin-adapter/SKILL.md`，由 `data/BundledSkills.kt`
在 App 启动时复制到 `files/skills/dsh-plugin-adapter/SKILL.md`（user-dsh 根，不进 workspace）。
内容：DSH 插件分类（工具 / UI / npm 依赖）、鲸歌插件包格式、参数 DSL↔JSON Schema、
`output.render` 删除、import/require 替换表、Config、打包、验证清单、交付话术。

用途：用户把 DSH 插件源码/包丢给 AI，AI 加载这个 skill 后按规则改成鲸歌可导入 zip，
再走包管理导入。

### 还没搬的 DSH 扩展点

DSH 插件不只有工具：还有提示词注入、AI 预设（agent-preset）、悬浮窗/UI（client 半侧）、
命令、通知等。这批只覆盖 **Host 侧工具插件**；提示词/预设/悬浮窗需要继续读 DSH 源码
（`@deepseek-ai/dsh-agent-preset*`、`dsh-agent-instructions`、`dsh-client-ui-*`）后另开兼容层。

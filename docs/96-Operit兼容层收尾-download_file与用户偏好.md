# 96 · Operit 兼容层收尾：`download_file`（visit_key 图片下载）+ 用户偏好 `user.md`（M69）

> **2026-11-10 21:2x 暂停：用户改优先级，要求先做「DSH 客户端插件宿主」（docs/97），本批未实施。
> 本文保留为设计草稿；§五/§六/§七 未填，不要当成已完成。**

> 时间：2026-11-10 03:2x–（Asia/Shanghai）
> 设备：OPPO A55 5G（PEMM00，720×1600，Android 13，无线调试 `192.0.2.10:43405`）
> 上接：`docs/95`（M68 兼容层二期）§五 的两条尾巴；HANDOFF §8 的 P0。
> 本批目标：把 `docs/94 §五` 留下的最后两条「暂不支持」清掉，并顺手修一个把
> Android 工具 schema 整份吞掉的 JSON 语法错误。

---

## 一、本批范围

| # | 上批状态 | 本批 |
|---|---|---|
| 1 | `Tools.Files.download` 只收 URL，`visit_key`/`image_number` 明确抛错 | 按 Operit `download_file` 语义接 `PluginVisitCache`；包侧 `Tools.Files.download` 与全局 `toolCall("download_file")` 都通；**给模型补一个原生 `download_file` 工具**，图片搜索闭环 |
| 2 | 真包 `update_user_preferences` 走 Operit 原生 `toolCall` → 鲸歌报「暂不支持」 | 定「用户偏好 = 单份 `user.md` 文档」；`toolCall("update_user_preferences"/"update_user_profile")` → 宿主 `memory.*` → `UserProfileStore`；`<user_profile>` 注入 system prompt；记忆库提供查看/编辑入口 |
| 3 | （本批勘察发现）`tools-android.json` 少一个逗号，`PromptPack.load` 解析失败静默返回空表 | 修 JSON + 加单元测试，保证 device/terminal/memory 工具的 schema 真的发给模型 |

---

## 二、`download_file`：Operit 语义与鲸歌落地

### 2.1 原版语义（`StandardFileSystemTools.kt:4272` + `JsTools.kt:142`）

Operit 里有两层：

1. **原生工具 `download_file`**：参数 `url` / `visit_key` / `link_number` / `image_number` /
   `destination` / `headers` / `environment`；`url` 为空时要求
   `visit_key` +（`link_number` 或 `image_number` 二选一）；`link_number` 优先于 `image_number`；
   从 `StandardWebVisitTool` 的进程级缓存里取整份 visit 结果，按 1 起下标取链接；
   错误原文：`Either url or (visit_key + link_number/image_number) is required` /
   `Invalid visit key.` / `Index out of bounds.` / `URL must start with http:// or https://`。
2. **`Tools.Files.download`**（JS 工具层）：只是
   `download: (urlOrOptions, destination, environment, headers) => toolCall("download_file", params)`；
   第一个参数可以是 URL 字符串，也可以是 `{url|visit_key|link_number|image_number|...}` 对象；
   **返回 `FileOperationData` 对象**（包体读 `result.successful` / `result.details`，如 `zhipu_draw.js:167`）。

### 2.2 鲸歌实现

**A. 包侧 `Tools.Files.download`（`core/plugins/ToolPkgTools.kt`）**

- 两种调用形状都收：`download(url, destination, environment, headers)` 与
  `download({visit_key, image_number, destination})`；
- `url` 为空时 `visit_key` +（`link_number`|`image_number`）→ `net.visit.get` 取回缓存，
  1 起下标，越界/无 key 用 Operit 英文原文（包按 message 判断）；
- 幂等：`headers` 可以是对象或 JSON 字符串；
- 返回 `FileOperationData{operation:'download', env, path, successful, details}`，
  **失败不抛异常**（Operit 的 download 也是返回 `successful:false`，包自己决定抛不抛）。

**B. 全局 `toolCall("download_file")`（`core/plugins/ToolPkg.kt`）**

`__operitToolCall` 目前只映射 `http_request`/`multipart_request`。新增
`download_file` → 同一个 `__toolDownloadFile(params)` 实现；包侧与 `Tools.Files.download`
共用一份，不再复制第二份下载逻辑。

**C. 模型侧原生工具 `download_file`（新）**

为什么必须有：`various_search` 的 6 个图片搜索工具会返回 `visitKey` + `Images:` 编号，
Operit 的模型下一步就是调原生 `download_file(visit_key, image_number, destination)`；
鲸歌此前没有这个工具，图片搜索只能停在「看到图片 URL」，闭环是断的。

- 只在**有启用的 Operit 格式插件**时注册（`PluginRecord.format == PluginFormat.OPERIT.label`）；
  没有 Operit 包的纯 DSH 用户不会多一个陌生工具；
- 工具实现放 app 模块（要同时用 `core:plugins` 的 visit 缓存和 `core:tools` 的 `ToolContext`）：
  `app/device/tools/OperitDownloadTool.kt`；
- visitKey 用 `PluginVisitCache.getAny(key)`（跨插件只读查找，key 是 UUID，只有拿到 key 才读得到）；
  **包与包之间仍按插件隔离**（`net.visit.get` 行为不变）；
- 目标路径走 `resolvePath`（相对路径按会话 cwd，逃逸工作区被拒绝），
  与内置文件工具同一条沙箱；下载上限 64MB，超时 20s connect / 60s read；
- 返回文本统一成 Operit `FileOperationData` JSON（`operation/env/path/successful/details`），
  失败也是 `successful:false`，不抛栈；
- schema 追加进 `core/prompt/.../tools-android.json`（描述照抄 Operit 英文原文）。

**D. 缓存**

`PluginVisitCache` 只加一个只读方法 `getAny(key)`；写入路径不变（按插件隔离）。
模型工具读的是「所有插件写过的 key」，和 Operit 的全局 map 语义一致；DSH 插件自己的
`net.visit.get` 仍然只能读自己的。

### 2.3 为什么不用 `link_number` 下载图片

Operit 的系统提示词明确写了「下载图片不要用 link_number 乱点页面链接，请用
download_file 的 visit_key + image_number」。鲸歌照抄这个约束：`image_number` 只查
`imageLinks`，`link_number` 只查 `links`。

---

## 三、用户偏好：存哪、怎么读、怎么改

### 3.1 Operit 现在的做法（`MemoryQueryToolExecutor.kt:710-805`）

- 新一代工具叫 `update_user_profile`（参数 `markdown`，**整体替换**），存进
  `MemorySpaceProfileDocumentRepository` 的 `user.md`（每个记忆空间一份，上限 12000 字符）；
- `update_user_preferences` 是**兼容旧包的别名**（`ToolRegistration.kt:849` 注释写明
  「released packages and persisted calls」）：把 `birth_date/gender/personality/identity/
  occupation/ai_style` 六个字段追加成一段 `## Imported profile update` 列表，写进同一份 `user.md`；
- `user.md` 非空时，`ConversationService` 把它拼进 system prompt：
  `<user_profile source="memory-space/<id>/user.md">…</user_profile>`。

### 3.2 鲸歌的选择：单份 `user.md` 文件（不是记忆记录，也不是新表）

**存 `files/user.md`（UTF-8，App 私有），由 `UserProfileStore` 独占读写。**

考虑过的三种方案：

| 方案 | 结论 | 原因 |
|---|---|---|
| A. 记忆库里一条特殊记忆 | 否 | 记忆是带标题/文件夹/标签/链接的检索单元；偏好文档会污染 `query_memory`/图谱；`update_user_preferences` 的「追加一段」语义会把一条记忆越写越乱；Operit 本来也是文档而不是记忆 |
| B. `memory.db` 新表 | 否 | 单文档没有必要建表；文件更容易原子写、导出、备份、给用户直接编辑 |
| C. `files/user.md` 单文件 | **采用** | 与 Operit 的 `user.md` 同名同语义；注入 system prompt 只需一次读文件；12k 字符上限按字符数校验；以后要加「多记忆空间」再按目录拆 |

格式就是 Markdown，不强制结构：用户可以自己写；`update_user_preferences` 追加的段落
标题与字段标签照 Operit 原文：

```markdown
## Imported profile update

- Gender: 女
- Occupation: 测试工程师
```

### 3.3 读写与注入

- `UserProfileStore`（app/memory）：
  - `load(): String`；`save(markdown): Result`（**超过 12000 字符拒绝**，不截断——Operit 也是报错）；
  - `appendPreferences(updates: List<Pair<String,String>>): Result`：读旧文 → 末尾追加
    `## Imported profile update` 段 → 超限则整体拒绝（不留半份）；
  - `clear()`。
- 宿主方法（`PluginHostBridge`）：
  - `memory.updateUserPreferences`（六个字段，返回 Operit 原文
    `Successfully preserved the preference update in user.md`）；
  - `memory.updateUserProfile`（`markdown`，返回 `Successfully updated user.md`）；
  - 两个都走 `PluginSandbox` 的 `memory.*` 权限门（和 `Tools.Memory.*` 同一档，危险项）。
- `ToolPkg.kt` 的 `__operitToolCall`：新增 `update_user_preferences` / `update_user_profile`
  → `host.call`；返回值是**字符串**（包体用 `result.length > 0` 判成功，和 Operit 一致）。
- 提示词：`AppViewModel.buildEngine` 的 `systemPrompt` lambda 在宿主提示词之后、插件段之前追加
  `<user_profile source="user.md">…</user_profile>`（仅当非空）；格式照 Operit。
- UI：记忆库顶栏加「用户偏好」按钮 → 对话框（等宽文本编辑 / 保存 / 清空 / 字数上限提示）。
  存进去的内容下一轮请求就会出现在 system prompt 里。

### 3.4 权限与诚实边界

- 偏好属于记忆数据，走 `memory` 权限；没有授权的包调 `toolCall("update_user_preferences")`
  会拿到「插件没有记忆库权限」的失败，不会静默写入。
- `update_user_preferences` 只是**兼容适配**：真正的模型侧偏好编辑在鲸歌由用户自己
  （记忆库 UI）完成；没有把 Operit 的 6 字段结构化 profile 运行时搬过来。
- 不做「自动从对话提取偏好」——那是记忆库二期的自动保存/提取，不在本批。

---

## 四、顺带修的 bug：`tools-android.json` 语法错误

`core/prompt/src/main/resources/prompt/0.2.2-rc.1/tools-android.json` 在
`get_terminal_session_screen` 与 `query_memory` 之间少一个 `},`（M56 加记忆工具时引入）。
`PromptPack.load` 用 `runCatching` 解析，失败就 `emptyList()`，于是 **M56 之后所有
device/terminal/memory 工具的 schema 都没发给模型**（registry 里有、请求 tools 里没有）。
本批修 JSON，并加一个单测保证 4 份 prompt 资源都能解析、android 工具名齐全。

---

## 五、实现

（待填：文件清单与关键代码）

---

## 六、真机验证

（待填：命令、logcat、截图、文件证据）

---

## 七、本批没做 / 限制

（待填）

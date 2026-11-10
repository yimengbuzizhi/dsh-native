# M56 · 记忆库（照搬 Operit）

> 2026-11-09。上一棒（M55）的「记忆」附件只是把当前会话最近 40 条压成文本；
> 用户要求「记忆库直接搬 Operit，不要再用会话记录顶替」。本批按 Operit 源码
> `D:\dsh\native\.thirdparty-src\Operit` 逐文件读完后重做数据层、图谱页与工具。

## 一、照搬了什么

### 1. 数据模型（Operit `data/model/Memory.kt`、`DocumentChunk.kt`）

`Memory` 全字段：`id/uuid/title/content/contentType/source/credibility/importance/
documentPath/isDocumentNode/chunkIndexFilePath/folderPath/createdAt/updatedAt/
lastAccessedAt`，以及标签、链接、文档分块。SQLite 表：

- `memories`（uuid 唯一、folder_path 可空、时间戳 epoch millis）
- `tags` / `memory_tags`（标签按 name 全局唯一，区分大小写）
- `links`（`(source_id,target_id,type)` 唯一，weight clamp 0..1）
- `document_chunks`（同一文档 chunk_index 0-based 连续）
- `folders`（空文件夹；Operit 用「文件夹说明」占位记忆实现，鲸歌单独一张表，
  避免占位节点出现在图谱里）

`normalizeFolderPath` 逐字照搬：trim → 空→null → `\`→`/` → 按 `/` 切、每段 trim、丢空段。

**未分类 = `folder_path IS NULL`**。UI 下拉里的「未分类」在落库前映射回 null
（Operit 会把字面量写进数据库，导致该记忆从「未分类」筛选里消失；鲸歌没有复刻这个 bug）。

### 2. 搜索（Operit `MemoryRepository.runSearchMemoriesWithDebug` 的纯关键词子集）

- 切词：有 `|` 按 `|`，否则按空白；`*`/空串返回范围内全部；`keywords` 为空返回空。
- token：小写、长度 2..24、含字母/数字/CJK；中文用 Android ICU 断词 + CJK bigram
  近似 Operit 的 Jieba；按长度降序 `take(32)`。
- 评分（照搬 RRF / coverage / importance）：
  - 标题候选：命中 token 数↓ → importance↓ → updatedAt↓ 排名；
    分数 `1/(60+rank) * importance * keywordWeight * (1 + 0.6*matched/total)`
  - 标签候选：同式，`tagWeight`（默认 0）
  - 反向包含：用户问题里直接出现记忆标题，`1/(60+rank) * importance * keywordWeight`
  - 图扩张：前 10 名沿出/入边传播 `score*link.weight*edgeWeight + 0.03*edgeWeight`
    （邻居可以不在当前文件夹作用域内）
  - 阈值默认 0.025（UI），工具侧默认 0（与 Operit `query_memory` 一致）
- **语义检索没搬**：Operit 的 embedding 由 `CloudEmbeddingService` + HNSW 索引提供，
  鲸歌没有对应服务；`semanticWeight ≡ 0`、语义分项恒为 0。这是本批最大的功能边界，
  已在 HANDOFF 里明说。

### 3. 图谱（Operit `getMemoryGraph/getGraphForMemories/getGraphForFolder`）

- 节点 id = memory.uuid、label = title；颜色：文档紫、首标签 `Person` 绿、`Concept` 蓝、其余灰。
- 边 id = link.id，只保留两端都在节点集合内的边；跨文件夹边 `isCrossFolderLink=true`（虚线）。
- 搜索命中后把直接邻居扩进图（与 Operit 一致）。
- `GraphVisualizer.kt`（1236 行 Canvas 力导向图）逐字节搬到
  `app/.../memory/MemoryGraphView.kt`，只改了包名/import/AppLogger→Log。
- 额外修了两个在 720×1600 真机才暴露的问题（Operit 桌面密度下不会出现）：
  1. **自动适配视口**：原版 3 个节点会被斥力甩到屏幕外；布局收敛后按节点包围盒
     计算 scale/offset，第一次自动把整张图缩进屏幕（用户手势后不再干预）。
  2. **点击命中**：原版把 `detectTapGestures` 和 `detectTransformGestures` 放在同一个
     `coroutineScope` 里，这台机器上 tap 不触发；拆成两个 `pointerInput` modifier，
     点击回调用 `rememberUpdatedState` 拿最新 scale/offset，并把 `graph` 加进 key
     （否则首次组合捕获空图）。改完后点节点/边都正常。

### 4. 文件夹（Operit `FolderNavigator.kt` 的交互）

全部记忆 / 未分类 / 文件夹树（层级缩进、展开折叠）、长按重命名/删除、
顶部「新建文件夹」、刷新；删除只把**直属**记忆移到未分类（子文件夹保留，与 Operit 语义一致）。
`getAllFolderPaths` 的「空文件夹」由 `folders` 表维持。

### 5. 记忆工具（Operit `MemoryQueryToolExecutor` + `ToolRegistration`）

注册 8 个工具，schema 写在 `core/prompt/.../tools-android.json`：

| 工具 | 关键行为 |
|---|---|
| `query_memory` | query 必填；limit 默认 20、`*` 且未给 limit = 全部；threshold 默认 0；snapshot（32 个/profile、LRU、seen 去重、三行头部）；`*` 时普通记忆 Content 只取前 10 字符 + `...`；文档节点用 query 二次检索块 |
| `get_memory_by_title` | 精确标题、取第一条；文档块参数优先级 `query > chunk_range > chunk_index`；1-based 块号；**没给块参数时用标题当搜索词取 1 块**（Operit 隐藏行为）；错误文案照抄 |
| `create_memory` | title/content 均必填；source 默认 `ai_created`；tags 逗号分隔；不查重；输出 `Successfully created memory: '…' (UUID: …)` |
| `update_memory` | old_title 必填；缺省字段保持；`tags` 出现即整体替换（空串=清空）；`folder_path` 出现且为空=未分类；输出 `Successfully updated memory from '…' to '…'` |
| `delete_memory` | 精确标题；连同 links/chunks 一起删 |
| `link_memories` | 两端标题精确查找；(source,target,type) 已存在则更新 weight/description；输出 `Successfully linked memory: 'A' -> 'B' (Type: t, Strength: w)` |
| `query_memory_links` | 按 link_id/source/target/type 过滤，limit 1..200，输出 `Memory Links (n): - #id: 'A' -> 'B' (Type: t, Weight: w)` |
| `move_memory` | titles 或 source_folder_path 二选一，target_folder_path 必填（空串=未分类） |

工具在 `AppViewModel` 构造时追加进 `ToolRegistry`，`offeredToolNames()` 自动带上，
下一次请求就能看到；schema 缺失时会被 `apiTools` 过滤掉，所以 tools-android.json 必须同步。

### 6. 输入栏「记忆」（替换 M55 临时方案）

`ChatComposer` 的「记忆」改为打开 `MemoryPickerDialog`：列出记忆库全部记忆、
按关键词过滤、多选后生成 `memory-*.txt` 文本附件（标题 + 文件夹/标签/来源/重要度/正文，
正文每条最多 6000 字符）。AI 也可以用 `query_memory` 自己查。

## 二、本批没搬（必须明说）

1. **embedding / HNSW / 云向量检索**：没有 embedding 服务；语义搜索、向量维度统计、
   重建索引、搜索设置里的云端向量配置全部未做。
2. **多记忆空间（profile）**：Operit 的 profile 选择器、按空间隔离的 DB、`user.md`
   注入（`<user_profile>`）未做；鲸歌当前是单一记忆库。
3. **自动保存 / 自动提取**：Operit 回复结束入队、≥5 条候选才跑、每会话 ≤20 条、
   48 条窗口、失败重试；本批没做。AI 目前用 `create_memory` 显式保存。
4. **AI 自动分类文件夹**、**记忆 JSON 导入/导出**、**mergeMemories**、
   **update_user_profile / update_user_preferences** 未做。
5. **搜索设置 / 模拟搜索 UI**：权重与调试面板未搬（数据层已经支持 keyword/tag/edge 权重）。
6. **PDF/Word 文档抽取**：文档导入只收文本（UTF-8）；PDF/Word 走既有附件的路径引用，
   还没有文本抽取。

## 三、真机验证（OPPO A55 5G，720×1600，Android 13）

设备：`192.0.2.10:43405`（无线调试端口当天变了，已重连）。

| 证据 | 说明 |
|---|---|
| `docs/shots/85-04-memory-graph-fit3.png` | 3 条记忆的图谱；自动适配后全部在屏内 |
| `docs/shots/85-05-memory-info.png` | 点节点 → 记忆详情（内容/文件夹/标签/来源/重要性/可信度/时间/UUID） |
| `docs/shots/85-06-memory-folder.png` | 文件夹抽屉：全部 / 未分类 / 项目→鲸歌（树、展开、新建） |
| `docs/shots/85-07-memory-link.png` | 连接模式选两个节点 → 连接弹层（类型/权重/描述） |
| `docs/shots/85-08-memory-edge.png` | 建立连接后的边与 `related` 标签 |
| `docs/shots/85-09-memory-search.png` | 搜索 `DSH` → 命中 2 条（结果 + 直接邻居） |
| `docs/shots/85-10-attach-sheet.png` | 输入栏附件菜单，「记忆」文案改为「从记忆库选几条引用给 AI」 |
| `docs/shots/85-11-memory-picker.png` | 记忆选择器（搜索、多选、文件夹/标签） |
| `docs/shots/85-12-memory-attached.png` | 引用 2 条后生成 `memory-*.txt` 待发附件 + Toast |

工具链路（`--es dsh_plugin_call` 调试通道，不走模型）：

```text
create_memory → Successfully created memory: '鲸歌 M56 记忆库' (UUID: 14299b32-…)
create_memory → '用户偏好：中文直接' / 'DSH 插件适配'
query_memory  → Title/Content/Source/Tags/Created 分块
query_memory_links → - #1: 'DSH 插件适配' -> '鲸歌 M56 记忆库' (Type: related, Weight: 1.0)
```

## 四、文件清单

- 新增：`app/src/main/java/xin/dshaa/nativeapp/memory/`
  - `MemoryModels.kt`（模型 + Graph 别名）
  - `MemoryStore.kt`（SQLite + 搜索/图谱/文件夹/文档块）
  - `MemoryViewModel.kt`（UI 状态与动作）
  - `MemoryScreen.kt`（页面编排：搜索/FAB/抽屉/弹层）
  - `MemoryGraphView.kt`（Operit GraphVisualizer 移植 + 视口适配/点击修复）
  - `FolderNavigator.kt`（文件夹树/新建/重命名/删除）
  - `MemoryDialogs.kt`（编辑/详情/边/连接/批量删除/文档分块）
  - `MemoryPickerDialog.kt`（输入栏记忆选择器 + 引用正文）
  - `MemoryTools.kt`（8 个记忆工具）
- 修改：`App.kt`（Screen.Memory + 路由）、`SettingsScreen.kt`（功能与工具入口）、
  `ChatComposer.kt`（记忆附件改选择器）、`AppViewModel.kt`（注册工具）、
  `core/prompt/.../tools-android.json`（8 个工具 schema）、`docs/06-功能对等清单.md`。

## 五、下一步

1. DSH 社区插件能力面（用户最高优先级）：按调研报告做 P0 静态清单/P1 bundle patch/
   P2 客户端半侧降级/P4 提示词段/P5 命令/P6 通知/P7 常驻 hook。
2. Operit 自带包（`assets/packages/*.js`）与 `Tools.*` 兼容层。
3. 记忆库第二期：自动保存/提取（用当前 LLM）、多记忆空间、搜索设置/模拟面板。

# 42 · 聊天附件：用户发图（相册 / 拍照 / 截图 → 模型）

> 2026-11-07 晚｜基线 `5dec5ed`｜上一棒交接的下一步第 1 条：
> **「聊天附件（用户图）接入：AttachmentStore 已就绪，打通 UI 选图 → 待发附件 → TurnInput → user message image 块 → 请求体」**。
> 本轮仍**没有连接手机**（用户「先搬功能，先不测试」），验收 = 源码 / 构建 / 单测。

---

## 一、一句话结果

| 项 | 之前 | 现在 |
|---|---|---|
| 输入条「＋」 | 拍照/相册、截图是灰的占位（Toast「还未接入」） | **相册图片 / 拍照 / 截图当前屏幕** 三个真入口 |
| 待发附件 | 无 | 输入条上方 **58dp 缩略图条**，右上角 ✕ 逐张移除；只发图片不写字也可以 |
| 会话格式 | 只有工具结果能带 image 块 | **用户消息也能带**：`user/message.content = [text…, image{attachment}…]`，附件不进 JSONL |
| 请求体 | 只有 tool_result 里的 image | 用户图片 = **句柄文本 + `{"type":"image","source":{"type":"base64",…}}`**（上游 user 分支同形） |
| 回放 | 工具图能重开显示 | 用户图同样按 `attachmentId` 从附件存储读回，重开会话/追问时都在 |
| 标题 | 空文字消息会被拒绝 | 只发图片时标题自动用「图片」 |

---

## 二、协议与落库（与上游 v4 / Anthropic wire 对齐）

### 2.1 v4 会话

用户消息和工具结果共用同一个 image 块形状（`core:model` 的 `ImageRef.toContentBlock()` 是唯一实现）：

```json
{"type":"user/message","data":{
  "role":"user",
  "content":[
    {"type":"text","text":"这张图是什么"},
    {"type":"image","attachment":{
      "attachmentId":"sha256:<64hex>","mediaType":"image/png",
      "bytes":1234,"width":720,"height":1570,
      "name":"photo.jpg","originalDimensions":{"width":1440,"height":3140}
    }}
  ],
  "source":{"kind":"user"},
  "id":"<uuid>"
}}
```

- 块顺序：**text 在前、image 随后**；空 text 块不落库（只发图时 content 里只有 image）。
- `image` 块只存引用，附件本体在 `files/attachments/v1/objects/<sha 前两位>/<sha>`（内容寻址，同图只落一份）。
- `SessionCodec` 解析 `"image"` → `BlockType.IMAGE`（raw 原样保留），`ContentBlock.toImageRef()` 还原引用。

### 2.2 请求体（`core:engine` 的 `buildRequest`）

user 与 tool_result 两条分支共用一个 `imageApiBlocks()`：

```
{"type":"text","text":"Image \"photo.jpg\" (sha256:…); request preview 720x1570px. It may be resized or re-encoded; …"}
{"type":"image","source":{"type":"base64","media_type":"image/png","data":"<base64>"}}
```

- 句柄文案逐字对齐上游 `requestImageHandleText`（没有归一化副本路径的分支）。
- 附件字节丢了（存储被清）→ **整块跳过**，不阻断这一轮（上游坏附件降级语义）。
- 图片在 text 之后逐张展开；同角色消息仍按契约 #1 合并。

### 2.3 附件准入（`core:attachment`，复用 `read_image` 的全套）

源上限 50MB / 单边 65535px / 6400 万像素；归一化到 2048²、长边 8192、4MB；
干净小图（≤4MB、≤2048²、无旋转）**原字节透传**。App 侧在读取时只多读 1 字节做「超限」判断，
不会把 200MB 文件整读进内存。

媒体类型**按文件头识别**（`sniffImageMediaType`），不信任 Photo Picker 的 `image/*` 通配：
JPEG/PNG/WebP/GIF 之外（如 HEIC）会提示「不支持的图片格式」。

---

## 三、数据流

```
相册 Photo Picker ─┐
系统相机（FileProvider 临时文件）─┼→ AppViewModel.attachImage/attachPhoto/attachScreenshot
无障碍截图（A11y.takeScreenshot）─┘        │
                                          ├→ AttachmentStore.saveImage(bytes, mediaType, name)
                                          │      （ImageNormalizer 归一化 + sha256 落盘）
                                          ↓
                            ChatSessionState.pendingImages: [PendingImage(ref, path)]
                                          │  （输入条缩略图；发送前可删除）
                                          ↓ 用户按发送
                       AppViewModel.send(text) → TurnInput(text, images=[ImageRef…])
                                          ↓
   TurnEngine：user/message 落库（text + image 块）；UI 消息 content 同步带 image 块
                                          ↓
   请求体：text… + （句柄文本 + base64 image）×N
```

- 发送成功**立即清空** `pendingImages`；发送被拒（未知命令 / 模型不支持图 / 流式中）时草稿和图片都保留。
- 切换会话/新建会话会换掉整个 `ChatSessionState`，未发送的草稿图片随之丢弃（与文字草稿一致）。
- 选图是异步的：落盘完成时若用户已切走会话（`chat !== state`），附件照常入库但**不会**出现在新会话。

---

## 四、界面上做了什么

| 位置 | 实现 |
|---|---|
| ＋菜单 | 「相册图片」（`PickMultipleVisualMedia(9)`，多选逐张入列）→「拍照」（`TakePicture` + FileProvider）→「截图当前屏幕」（无障碍 `takeScreenshot`）；下面保留计划模式/目标/提醒 |
| 待发条 | 输入框上方 `LazyRow`：58dp 圆角缩略图（`decodeSampledBitmap` 降采样解码）+ 右上角半透明 ✕；同图可重复添加，✕ 只移除一张 |
| 错误提示 | 输入条上方一行 `labelSmall` 红字（读不到/超 50MB/格式不支持/归一化失败/截图失败/模型不支持），下次操作自动清空 |
| 用户气泡 | 图片在文字上方（最多 300dp 宽、260dp 高、圆角 12dp、`ContentScale.Fit`），点击全屏预览（复用工具图同一个 `ImagePreviewDialog`）；图片丢失时显示「（N 张图片已丢失）」 |
| 发送按钮 | 有字**或**有待发图片即为可发送态；文字为空也允许（image-only 消息） |

**权限**：Photo Picker 不需要存储权限；`ACTION_IMAGE_CAPTURE` 由系统相机应用完成，本 App 不申请 `CAMERA`，
只在 `file_paths.xml` 增加 `<cache-path>` 供相机写临时原图（读完后立即删除）。

**线程**：无障碍截图在 `Dispatchers.IO` 上等待 `takeScreenshot` 回调（最多 5 秒），不冻结主线程；
归一化/落盘/读 Uri 全部在 IO 上。

---

## 五、决策与理由（交接要求：遇选择自己拍板并留一句）

1. **图片能力前置硬门**：只有 `deepseek-flash` 声明 `inputModalities=["text","image"]`（上游模型目录）；
   带图发送前就拦住并提示，而不是等 API 报错。同时把 `ToolContext.imageRoute` 从 `val` 改成 `var`，
   在每轮 `startTurn`/子代理启动时按当前设置重绑——修掉「换模型后 `read_image` 的门还是旧快照」的隐患。
2. **斜杠命令不能带图**：命令走的是不产生模型消息的执行路径，图片没有落点；返回 false 保留草稿并提示，
   不做「悄悄丢图」。
3. **只发图片允许**：上游 user 消息本来就允许无 text；标题回退用「图片」，避免空标题。
4. **相机不申请 CAMERA 权限**：系统相机代拍即可；只有需要 App 内自绘取景器时才申请（后续可做多拍/裁剪）。
5. **不做非图片文件附件**：`file` 块与上游 `file` 内容类型还没移植；这轮只做用户图（用户交接的下一步第 1 条）。

---

## 六、改动文件

| 文件 | 改动 |
|---|---|
| `core/model/ImageBlock.kt`（新） | `ImageRef.toAttachmentJson()` / `toContentBlock()` / `ContentBlock.toImageRef()`：v4 image 块唯一转换 |
| `core/session/SessionRepository.kt` | 新增 `appendUserMessageContent(texts, images, source)`；工具结果的 image 块改用同一 helper |
| `core/engine/EngineContracts.kt` | `TurnInput.images: List<ImageRef>` |
| `core/engine/TurnEngine.kt` | 用户消息落库带 image 块；请求体 user 分支展开「句柄+base64」；tool 分支共用 `imageApiBlocks()`；标题回退「图片」 |
| `core/tools/Tool.kt` | `ToolContext.imageRoute` 改 `var`（按当前模型重绑） |
| `app/chat/ChatSessionState.kt` | `pendingImages`（`SnapshotStateList`）+ `attachError` + `PendingImage(ref, path)` |
| `app/chat/AppViewModel.kt` | `attachImage(Uri)` / `attachPhoto(File)` / `attachScreenshot()` / `removePendingImage`；发送链路带图；模型能力门；调试通道 `--es dsh_attach_image` |
| `app/ui/chat/ChatScreen.kt` | ＋菜单三个真入口、待发缩略图条、错误行、用户气泡图片渲染、image-only 发送 |
| `app/ui/App.kt` | 把四个附件回调接到 `ChatScreen` |
| `app/MainActivity.kt` | 调试 extra `dsh_attach_image` |
| `app/res/xml/file_paths.xml` | 增加 `cache-path`（相机临时原图） |

---

## 七、单测（本轮新增 5 条）

| 测试 | 锁定什么 |
|---|---|
| `core:session UserImageMessageTest.userMessageCarriesTextThenImageBlock` | v4：text→image 顺序、attachment 全字段（含 `originalDimensions`/`name`）、投影还原、`source.kind=user` |
| `…imageOnlyMessageHasNoEmptyTextBlock` | 只发图时 content 只有一个 image 块（没有空 text） |
| `…brokenImageBlockProjectsToNull` | 缺 `attachmentId` 的坏块保留为 IMAGE 但引用为 null（请求/UI 跳过，不炸整轮） |
| `core:engine TurnEngineTest.userImageIsPersistedAndSerialized` | 一轮里：v4 落库带 image；请求体 = CTX+正文+句柄+base64（`AQIDBA==`）；UI 消息带 image；标题 |
| `…imageOnlyTurnUsesImageTitleAndSkipsEmptyText` | image-only：content 只有 image、标题「图片」、请求里没有空 Text |

---

## 八、真机验收清单（设备回来后按顺序）

```powershell
# 1) 相册/发送：把一张图推进待发列表并自动发一句（验前半链路）
adb shell am start -n xin.dshaa.nativeapp/.MainActivity --es dsh_attach_image /sdcard/Download/shot.png --es dsh_send "这张图是什么"
# 2) 只发图（UI 手动）：＋ → 相册图片 → 选 1 张 → 直接点发送
# 3) 拍照（UI 手动）：＋ → 拍照 → 系统相机 → 确认 → 缩略图出现 → 发送
# 4) 截图（UI 手动，先开无障碍）：＋ → 截图当前屏幕 → 缩略图 → 发送
# 5) 重开回放：打开刚才的会话，用户气泡里的图应该还在（附件按 attachmentId 读回）
# 6) 能力门：设置里把模型切到 deepseek-v4-pro → 选图发送 → 应提示「不支持图片输入」，草稿与图都保留
# 7) 失败路径：选一个 HEIC/损坏文件 → 红字提示，不产生待发图
# 8) 真机留档：m23-chat-attachment-1.png（待发缩略图）、-2.png（发送后用户气泡）、-3.png（重开回放）
```

---

## 九、已知限制 / 下一步

1. **非图片附件**（`file` 块、PDF/文本）未做：需要先定上游 `file` 内容块的请求体映射。
2. **HEIC/AVIF** 不支持（`sniffImageMediaType` 只认 PNG/JPEG/WebP/GIF）；后续可在归一化前加系统解码。
3. **无图片 token 估算 / 请求体积上限**：目前全部内联 base64，长会话大量图片会顶到请求上限；
   对应上游 `image-tokens` / `image-offload`，是后续独立一棒。
4. **相机只有单拍**：没有多拍/裁剪/旋转 UI；拍照结果走系统相机默认行为。
5. **拍照临时文件**在 `cache/camera/`，读取后立即删除；进程被杀死时可能残留，系统会随缓存清理。

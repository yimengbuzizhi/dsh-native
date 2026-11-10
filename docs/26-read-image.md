# 26 · read_image：把图片交给模型（第 16 个工具）

> 2026-11-07 02:2x｜基线 `2073200`（M7 web_search 之后）。
> 目标：补上手机 agent 最核心的一环——**模型能真的「看见」图片**（截图/照片），
> 并把工具数从 15 提到 **16**。会话里只存引用，附件走内容寻址存储（与上游一致）。

---

## 一、一句话结果

真机 + 真实 API 跑通：

```
用户：用 read_image 读取 agent-shot.png，告诉我这张图是什么界面、屏幕顶部显示的时间。
模型：read_image({"file_path":"agent-shot.png"}) → <path>…</path><type>image</type>… + image 块
     → 「这是与 DSH（deepseek-flash）的聊天界面，顶部显示的时间是 02:06。」
```

模型答对了只有看图才知道的信息（截图内容 + 顶部时间 **02:06**）。

**历史重放也验过**：关掉 App、重开同一会话（`--es dsh_open_session <id>`），追问
「截图底部输入框里的提示文字是什么？」→
模型答「发消息...，前面那个「+」是图标」。这段文字在之前的**工具输出文本里没有出现过**，
只能来自会话里的 image 块 → 证明 v4 持久化 + 请求重建这两段都通了。

证据（`docs/shots/`）：
`m8-read-image-1.png`（工具卡缩略图 + 流式回答）、`m8-read-image-2.png`、`m8-reopen.png`（重开会话回放图片）、
`m8-history-replay.png`（追问验证历史图片重放）、`m8-read-image-approval.png`（审批=ask 下 read_image 不弹窗）。

测试：`:core:tools` 单测 **16 个**（ReadImageToolTest）、`:core:attachment` 单测 **8 个**
（AttachmentStoreTest 3 + ImageGeometryTest 5）+ **真机 instrumented 5 个**（ImageNormalizerInstrumentedTest）。

---

## 二、上游怎么做的（`@deepseek-ai/dsh-tool-fs` 的 `read-image`）

1. **后缀先声明类型**（png/jpg/jpeg/webp/gif），没有后缀就靠文件头嗅探（PNG/JPEG/GIF/WebP 魔数）。
   后缀不认识 → 直接拒绝，不读文件。
2. **模型能力门**：解析当前路由（provider/model），要求模型目录里**明确声明** `inputModalities` 含 `image`；
   `deepseek-flash` 有（`["text","image"]`），`deepseek-v4-pro` 没有。不满足就拒绝，避免读完才发现模型看不了。
3. **附件服务**做真正的准入：源上限 50MB / 单边 65535px / 6400 万像素；归一化到
   **≤2048² 像素、长边 ≤8192、≤4MB** 的 8-bit sRGB 单帧图（sharp：方向、去元数据、质量阶梯）。
   已经是干净小图 → 字节透传。
4. 输出两段：模型面对的信封文本 + image 块。

```
<path>{displayPath}</path>
<type>image</type>
<content>
{mediaType} image, {w}x{h} px, {bytes} bytes[ (downscaled from {ow}x{oh} px; multiply coordinates by X to locate features in the original file)]
</content>
```

5. **wire 形状**（`dsh-llm-deepseek.serialize`）：image 块在请求里会先插一句句柄文本，再放 base64 图：

```json
{"type":"tool_result","tool_use_id":"…","content":[
  {"type":"text","text":"<path>…</path>…"},
  {"type":"text","text":"Image \"shot.png\" (sha256:…); request preview 720x1570px. It may be resized or re-encoded; source dimensions, format, and byte size may differ."},
  {"type":"image","source":{"type":"base64","media_type":"image/png","data":"…"}}
]}
```

6. **附件不进 JSONL**：会话消息里的 image 块只放引用
   `{"type":"image","attachment":{"attachmentId":"sha256:<hex>","mediaType","bytes","width","height","name"?,"originalDimensions"?}}`，
   字节存在 `<DSH_HOME>/attachments/v1/objects/<sha 前两位>/<sha>`。

---

## 三、原生实现

| 文件/模块 | 职责 |
|---|---|
| `core:model/Image.kt` | `ImageRef`（与上游 `ImageAttachmentRef` 同形）：attachmentId / mediaType / bytes / width / height / name? / originalDimensions? |
| `core:tools/Tool.kt` | `AttachmentHost`（saveImage）+ `ImageRoute`（能力门）+ `ImageStoreException`（稳定错误码）；`ToolResult.images` |
| `core:tools/fs/ReadImageTool.kt` | 纯 JVM：参数/后缀/能力门 → 读字节 + 魔数嗅探 → 调 host → 上游逐字信封；错误文案逐条照抄 |
| `core:attachment/AttachmentStore.kt` | 内容寻址落盘（`objects/<sha2>/<sha256>`，`sha256:` 前缀 id）、幂等写、按 id 读回 |
| `core:attachment/ImageNormalizer.kt` | Android 侧归一化：BitmapFactory 解码（bounds→采样解码）、EXIF 方向、2048² 预算（上游算法逐位一致）、PNG/JPEG 编码阶梯到 ≤4MB |
| `app/AppViewModel` | 注入 `attachments`/`imageRoute`；工具卡结果带 image 列表；v4 `tool/result` 写 image 块；请求时从存储读回字节（base64）+ 句柄文本 |
| `app/ui/chat/ChatScreen` | 工具卡内缩略图（限高 260dp）+ 尺寸/字节说明 + 点击全屏预览 |
| `core/session/SessionRepository` | `appendToolResult(..., images)` 写 `{type:"image", attachment:{…}}` |
| `settings.gradle.kts` / `app` | 新增 `:core:attachment` Android library 模块并接入 |

设计取舍（拍板理由写在这）：
- **位图解码/编码放 `core:attachment`，不放 `core:tools`**：core:tools 是纯 JVM，工具逻辑要能直接单测；
  Android 的 BitmapFactory 只在 host 实现里出现（和 goal/web_search 的 host 注入同一套路）。
- **附件用内容寻址而不是「会话目录里放一份」**：与上游一致（附件不参与会话格式），
  同一张图被读两次只落一份盘；会话搬到别处只丢图不坏会话。
- **小图字节透传**：截图（720×1570，≤4MB）走透传，零重编码、零画质损失；大图才归一化。

---

## 四、真机证据

1. **首次读取**（auto approve）：工具卡里显示缩略图 → 模型回答截图内容与顶部时间 02:06。
2. **重开回放**：`--es dsh_open_session 8933d4d4-…` 打开同一会话，缩略图从附件存储重新解码显示。
3. **历史重放**：追问只出现在图里的输入框占位符 → 模型答「发消息...」。
4. **审批策略**：`--ez dsh_ask_approval true` 下 read_image 属于只读白名单，**不弹审批**（截图里没有对话框，卡片直接成功）。
5. **wire 形状**：`--es dsh_dump_request` 落盘的请求体里，tool_result 的 content 依次是
   `text(信封)` → `text(句柄)` → `image{source:{type,media_type,data}}`；tools 数组 16 个，含 `read_image`。

v4 落盘实测（`tools/session-dump.ps1` 解开）：

```json
{"type":"tool/result","seq":6,"data":{"message":{"role":"tool","toolCallId":"call_…",
 "content":[
  {"type":"text","text":"<path>…agent-shot.png</path>\n<type>image</type>\n<content>\nimage/png image, 720x1570 px, 48054 bytes\n</content>"},
  {"type":"image","attachment":{"attachmentId":"sha256:2977fcae…","mediaType":"image/png","bytes":48054,"width":720,"height":1570,"name":"agent-shot.png"}}
 ],"isError":false}}}
```

---

## 五、测试

- `ReadImageToolTest`（16）：空路径 / 不支持的后缀 / `.hidden` 当无后缀 / 无附件服务 / 模型不支持图片 /
  文件不存在 / 目录不是普通文件 / 无后缀嗅探 / 后缀与字节不符 / 非图字节 / 信封逐字 / 缩小后的坐标倍率 /
  各 `ImageStoreException` 的错误文案 / 四种魔数识别 / 结果带 ImageRef。
- `AttachmentStoreTest`（3）：内容寻址幂等、不同字节不同 id、非法 id 不越界。
- `ImageGeometryTest`（5）：上游 `requestImageDimensions` 的逐位行为（4000×3000 → 2364×1773、
  5000×1000 → 4578×916 的逐像素回退、长边上限）。
- `ImageNormalizerInstrumentedTest`（5，真机）：小 PNG 字节透传、3000×4000 PNG 缩小进预算、
  5000×3000 JPEG 重编码为 JPEG、垃圾字节 → INVALID_IMAGE、声明类型与字节不符 → IMAGE_TYPE_MISMATCH。

命令：

```powershell
.tools\gradle-8.9\bin\gradle.bat :core:tools:test :core:attachment:test
.tools\gradle-8.9\bin\gradle.bat :core:attachment:connectedDebugAndroidTest
```

---

## 六、顺手修的门 2 问题（否则 verify 永远红）

1. **夹具过期**：`device-smoke` 是在 11 个工具时录的（13220 字节请求），M7 之后 15→16 个工具，
   请求体必然变；本轮用真实端点**重录**（2 个请求），`verify` 两次 run 110 行 **PASS**。
2. **record 会打到回放器**：`verify` 把 `base_url` 写进设置并持久化，紧接着 `record` 不显式指定端点
   就会录到「连接失败」的空夹具。`record` 现在固定带 `--es dsh_base_url default`。
3. **verify 完不还原**：跑完 App 还指着 `127.0.0.1:18080`、审批还是 auto。新增 `Restore-App-Environment`：
   force-stop → 带 `dsh_base_url default`/`dsh_ask_approval true` 启动 → 再 force-stop
   （App 已在前台时 `am start` 只投 onNewIntent，MainActivity 不读 extras，必须 force-stop）。
4. **新增 `tools/session-dump.ps1`**：把设备上多 zstd 帧的会话拉下来解成 JSONL
   （Java + zstd-jni `setContinuous(true)`）。前面排查 `tool/result` 形状就靠它。

---

## 七、已知限制（诚实清单）

1. **归一化不是逐字节等价**：上游用 sharp 的 libvips 质量阶梯 + 元数据/位深检查；我们用 Android 编码器。
   像素预算算法逐位一致、信封逐字一致，但重编码字节不会与上游相同。透传路径（小图）是逐字节相同的。
2. **透传不检查元数据/位深**：上游要求「无元数据、单帧、8-bit sRGB」才透传；我们只按大小/像素/EXIF 方向判断。
   实际影响：带 EXIF 的 JPEG 会走归一化；带奇怪元数据的 PNG 可能原样上传。
3. **没有 Files API 上传/图片卸载**：全部内联 base64；远期大图会顶到请求体积，需要移植 image-offload / compaction。
4. **读不到工作区外的图**：`resolvePath` 仍按 workspace 沙箱；`/sdcard` 截图要先由 App 自己的工具落到工作区
   （真正的 screenshot 工具需要 MediaProjection，另立项）。
5. **用户还不能自己发图**：聊天输入条没有附件按钮（这是 image 块的另一半用途，M2 附件功能时补）。
6. **图片不进 token 统计**：usage 里只有服务端返回的 input_tokens（含图），UI 不单独展示图片 token。

---

## 八、踩到的坑

1. **Node 解不了多 zstd 帧**：`zlib.zstdDecompressSync` 和 `createZstdDecompress` 都只解第一帧（220 字节 header），
   必须用 zstd-jni 的 `ZstdInputStream.setContinuous(true)` 才能读完整会话。
2. **PowerShell 里 .NET 相对路径按进程 cwd 解析**：`[IO.File]::ReadAllBytes("docs\...")` 实际找 `D:\dsh\docs\...`；
   一律用绝对路径。
3. **`adb exec-out ... < file` 会挂住**：stdin 关了，远端 `sh` 的管道不结束 → 命令超时。
   base64 文本走 `type file | adb shell "run-as pkg sh -c 'cat > /绝对路径'"` 可以（相对路径不行：`sh -c` 的 cwd 不是 app 数据目录）。
4. **`am start` 在 App 已在前台时不触发 onCreate**：只投 onNewIntent，`MainActivity` 的调试 extras 全部失效 →
   还原设置必须 force-stop 再启动。
5. **PowerShell 5.1 的 `-Latest` 开关绑定报 SwitchParameter 错**：改成 `-Output` 参数后发现冲突仍在，
   最后用显式 `-Session` 路径（工具保留 `-Latest`，真机上暂用显式路径）。
6. **edit 工具写 PS 脚本会丢 UTF-8 BOM**：PS 5.1 会把无 BOM 的中文脚本按 GBK 读坏；
   每次改完 `tools/*.ps1` 都要补 BOM（`new UTF8Encoding($true)`）。

---

## 九、下一步

1. **`screenshot` 工具**（MediaProjection 授权一次 + 截当前屏 → 自动 read_image）：手机 agent 真正的眼睛。
2. 继续补工具到 20+：`schedule_*`（4 个）、`list_agents`/`send_message`、`skill`、`exit_plan_mode`。
3. 用户附件：输入条「+」→ 相册/相机 → 同一个附件存储 + user message 的 image 块（复用本轮基础设施）。
4. 图片 token 估算 + 请求体积上限（移植 image-tokens / image-offload），避免长会话里 base64 撑爆。
5. 搜索卡 UI（docs/25 留下的小尾巴）与阅读型工具的结果卡统一。

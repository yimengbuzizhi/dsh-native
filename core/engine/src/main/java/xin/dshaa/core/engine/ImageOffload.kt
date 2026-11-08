package xin.dshaa.core.engine

import xin.dshaa.core.model.ChatMessage
import xin.dshaa.core.model.ImageOffloadTarget
import xin.dshaa.core.model.ImageRef
import xin.dshaa.core.model.MessageRole
import xin.dshaa.core.model.markOffloadedImages
import xin.dshaa.core.model.toImageRef
import xin.dshaa.core.protocol.ApiRequest
import xin.dshaa.core.protocol.ContentBlock as ApiBlock

/**
 * 图片卸载（M47，上游 `dsh-compaction-image-offload` + `dsh-llm` 的 `requiredImageOffload()`）。
 *
 * 上游的触发链：适配器按路由的图片预算（张数 / 字节）检查请求，超了就抛
 * `IMAGE_OFFLOAD_REQUIRED`，并带上「还要卸几张」；执行器从**模型请求顺序**里挑最老的、
 * 还没卸过的输入图片，落一条 `image/offload{targets:[{seq,imageIndexes}]}`，然后同一个 step
 * 重发请求。选中永久生效：后续请求都换成占位文本（附件名 + 只读路径），
 * 换更大预算或压缩都不会自动恢复。
 *
 * 原生没有 Files 上传通道，图片按 base64 直传，所以把适配器的本地预算检查搬进引擎：
 * 请求组装完之后按**实际 base64 长度**算一遍，超预算就合成一条同样的失败。
 * 预算默认值逐字抄上游 `dsh-llm-deepseek`：600 张 / 20 MiB 内联 / 10 MiB 字节量子 /
 * 20 张数量量子（`imageOffloadCountQuantum`）。
 *
 * M48 补上第二条触发（上游 `compaction/summary-error`）：摘要请求只含被压缩选区，
 * 它自己超图片预算时，在被压缩选区里挑最老的图卸载，再原地重试摘要（同一个 compaction bracket）。
 *
 * 与上游的差别（docs/71 / docs/72）：
 * 1. 触发点在引擎而不是适配器（主请求与摘要请求都是本地预算检查，省一次真实网络往返）；
 * 2. 占位文本里的「可读副本路径」用 App 附件存储的私有绝对路径（工具在 App 进程里能读）。
 */
object ImageOffload {

    /** 上游 `IMAGE_OFFLOAD_REQUIRED_CODE`。 */
    const val REQUIRED_CODE = "IMAGE_OFFLOAD_REQUIRED"

    /** 上游 `dsh-llm-deepseek` 默认值：一次请求最多 600 张图。 */
    const val DEFAULT_MAX_IMAGES = 600

    /** 上游默认内联图片总字节预算 20 MiB（base64 表示）。 */
    const val DEFAULT_MAX_BYTES = 20 * 1024 * 1024

    /** 超字节预算时按 10 MiB 的整数倍卸载（上游 `inlineImageOffloadByteQuantum`）。 */
    const val DEFAULT_BYTE_QUANTUM = 10 * 1024 * 1024

    /** 超张数时按 20 张的整数倍卸载（上游 `imageOffloadCountQuantum`）。 */
    const val DEFAULT_COUNT_QUANTUM = 20

    /** 防御性上限：每卸载一轮至少少一张图，正常 1–3 轮就够；40 足够覆盖 600 张的极端会话。 */
    const val MAX_RETRIES = 40

    data class Budget(
        val maxImages: Int = DEFAULT_MAX_IMAGES,
        val maxBytes: Int = DEFAULT_MAX_BYTES,
        val byteQuantum: Int = DEFAULT_BYTE_QUANTUM,
        val countQuantum: Int = DEFAULT_COUNT_QUANTUM,
    )

    val DEFAULT_BUDGET = Budget()

    fun isRequired(code: String?): Boolean = code == REQUIRED_CODE

    /**
     * 上游 `requiredImageOffload()`：按请求里**保留下来的**图片出现顺序（最老在前）算
     * 还要卸几张；0 = 这次请求没超预算。
     *
     * 字节按实际 base64 表示算（`source.data.length`），与适配器 `base64Length()` 同值：
     * 只算 `offloaded` 标记**不在**请求里的图（卸载后的图已经是占位文本，不计价）。
     */
    fun requiredCount(request: ApiRequest, budget: Budget = DEFAULT_BUDGET): Int {
        val lengths = ArrayList<Int>()
        fun visit(blocks: List<ApiBlock>) {
            blocks.forEach { block ->
                when (block) {
                    is ApiBlock.Image -> lengths += block.source.data.length
                    is ApiBlock.ToolResult -> block.content?.let { visit(it) }
                    else -> Unit
                }
            }
        }
        request.messages.forEach { visit(it.content) }
        if (lengths.isEmpty()) return 0

        val total = lengths.sumOf { it.toLong() }
        val excessCount = maxOf(0, lengths.size - budget.maxImages)
        val excessBytes = maxOf(0L, total - budget.maxBytes)
        if (excessCount == 0 && excessBytes == 0L) return 0

        val removeCount = if (excessCount == 0) 0 else ceilDiv(excessCount, budget.countQuantum) * budget.countQuantum
        val removeBytes = if (excessBytes == 0L) {
            0L
        } else {
            ceilDiv(excessBytes, budget.byteQuantum.toLong()) * budget.byteQuantum
        }
        var count = 0
        var removedBytes = 0L
        for (length in lengths) {
            // 逐字抄上游：量子为 1 时 >=，否则严格 >（见 dsh-compaction-image-offload 的
            // requiredImageOffload → offloadedImagePrefixCount）。
            val byteTargetMet = removeBytes == 0L ||
                if (budget.byteQuantum == 1) removedBytes >= removeBytes else removedBytes > removeBytes
            if (count >= removeCount && byteTargetMet) break
            removedBytes += length
            count += 1
        }
        return count
    }

    /**
     * 上游 `offloadOldestImages()`：按模型请求顺序从最老的 user / tool 消息开始挑，
     * 跳过 assistant（模型输出）和已经卸过的出现位置；[count] 张选满即停。
     *
     * `imageIndexes` 是该消息内**深度优先**的 image 下标，已卸的也占一个下标。
     */
    fun selectOldest(messages: List<ChatMessage>, count: Int): List<ImageOffloadTarget> {
        if (count <= 0) return emptyList()
        var remaining = count
        val targets = ArrayList<ImageOffloadTarget>()
        for (message in messages) {
            if (remaining == 0) break
            if (message.role != MessageRole.USER && message.role != MessageRole.TOOL) continue
            val seq = message.seq ?: continue
            val indexes = ArrayList<Int>()
            var imageIndex = 0
            for (block in message.content) {
                if (block.type != xin.dshaa.core.model.BlockType.IMAGE) continue
                val ref = block.toImageRef()
                if (ref != null && !ref.offloaded) {
                    indexes += imageIndex
                    remaining -= 1
                    if (remaining == 0) break
                }
                imageIndex += 1
            }
            if (indexes.isNotEmpty()) targets += ImageOffloadTarget(seq, indexes)
        }
        return targets
    }

    /**
     * 上游 `offloadedImageText()` 的逐字移植：占位文本 = 附件身份 + 归一化副本只读路径，
     * 让模型仍能用文件工具读到原图（路径不可用时明确说「让用户重新发」）。
     */
    fun placeholderText(ref: ImageRef, readonlyPath: String?): String {
        val identity = if (ref.name == null) {
            ref.attachmentId
        } else {
            "\"${ref.name}\" (${ref.attachmentId})"
        }
        val head = "[image omitted to fit request image limits; $identity."
        if (readonlyPath == null) {
            return "$head No local normalized image path is available; ask the user to attach it again if needed.]"
        }
        val extension = when (ref.mediaType) {
            "image/png" -> ".png"
            "image/jpeg" -> ".jpg"
            "image/webp" -> ".webp"
            "image/gif" -> ".gif"
            else -> ".png"
        }
        return head +
            " Normalized copy (read-only; may be resized or re-encoded): \"$readonlyPath\"" +
            " (${ref.width}x${ref.height}px, ${ref.mediaType})." +
            " Source dimensions, format, and byte size may differ." +
            " Copy to a writable path ending in $extension before editing.]"
    }

    /**
     * 把 [targets] 应用到 [messages]：按 seq 找消息、按深度优先下标打 `offloaded` 标记，
     * 返回新列表（未命中的消息原样保留）。上游 `offloadMessageImages()` 的无异常版本：
     * 会话面里找不到的 seq 直接跳过（事件是 append-only 的，回放可能已不在这段选区）。
     */
    fun markOffloaded(messages: List<ChatMessage>, targets: List<ImageOffloadTarget>): List<ChatMessage> {
        if (messages.isEmpty() || targets.isEmpty()) return messages
        val bySeq = targets.associateBy { it.seq }
        return messages.map { message ->
            val seq = message.seq ?: return@map message
            val target = bySeq[seq] ?: return@map message
            message.markOffloadedImages(target.imageIndexes.toSet())
        }
    }

    private fun ceilDiv(value: Int, div: Int): Int = (value + div - 1) / div

    private fun ceilDiv(value: Long, div: Long): Long = (value + div - 1) / div
}

/**
 * 摘要请求超图片预算（M48，上游 `compaction/summary-error` 收到的 `LlmError`）：
 * 被压缩选区自己的摘要请求超过路由图片预算时抛出；[count] = 还要卸几张，
 * [message] = 原始失败文案（没有人接手时按普通摘要失败收口）。
 */
class SummaryImageOffloadRequired(
    val count: Int,
    message: String,
) : Exception(message)

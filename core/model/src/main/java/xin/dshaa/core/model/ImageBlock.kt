package xin.dshaa.core.model

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * 会话 v4 的 image 块（上游 `ImageAttachmentRef` 的线上形状）：
 * `{type:"image", attachment:{attachmentId, mediaType, bytes, width, height[, name][, originalDimensions]}}`。
 *
 * 会话 JSONL 只存引用，附件本体在内容寻址存储里（对齐上游「附件不参与会话格式」）。
 * 这里放「引用 ↔ 块」的唯一一份转换：`core:session`（写 v4）、`core:engine`（组请求）、
 * App（UI 缩略图/待发附件）都走它，避免三处各写一份键名。
 */
fun ImageRef.toAttachmentJson(): JsonObject = buildJsonObject {
    put("attachmentId", attachmentId)
    put("mediaType", mediaType)
    put("bytes", bytes)
    put("width", width)
    put("height", height)
    if (name != null) put("name", name)
    if (originalWidth != null && originalHeight != null) {
        put(
            "originalDimensions",
            buildJsonObject {
                put("width", originalWidth)
                put("height", originalHeight)
            },
        )
    }
}

/** v4 image 块（会话消息 content[] 里的一个元素）。 */
fun ImageRef.toContentBlock(): ContentBlock = ContentBlock(
    type = BlockType.IMAGE,
    raw = buildJsonObject {
        put("type", "image")
        put("attachment", toAttachmentJson())
    },
)

/**
 * 从 v4 image 块解析回引用；没有 `attachmentId`/`mediaType` 返回 null（旧版/损坏块跳过，
 * 与上游「坏附件降级」一致：图丢失不阻断整轮请求）。
 */
fun ContentBlock.toImageRef(): ImageRef? {
    val root = raw ?: return null
    val obj = runCatching { root["attachment"]?.jsonObject }.getOrNull() ?: root
    val attachmentId = runCatching { obj["attachmentId"]?.jsonPrimitive?.contentOrNull }.getOrNull() ?: return null
    val mediaType = runCatching { obj["mediaType"]?.jsonPrimitive?.contentOrNull }.getOrNull() ?: return null
    val original = runCatching { obj["originalDimensions"]?.jsonObject }.getOrNull()
    return ImageRef(
        attachmentId = attachmentId,
        mediaType = mediaType,
        bytes = runCatching { obj["bytes"]?.jsonPrimitive?.intOrNull }.getOrNull() ?: 0,
        width = runCatching { obj["width"]?.jsonPrimitive?.intOrNull }.getOrNull() ?: 0,
        height = runCatching { obj["height"]?.jsonPrimitive?.intOrNull }.getOrNull() ?: 0,
        name = runCatching { obj["name"]?.jsonPrimitive?.contentOrNull }.getOrNull(),
        originalWidth = runCatching { original?.get("width")?.jsonPrimitive?.intOrNull }.getOrNull(),
        originalHeight = runCatching { original?.get("height")?.jsonPrimitive?.intOrNull }.getOrNull(),
        offloaded = runCatching { root["offloaded"]?.jsonPrimitive?.booleanOrNull }.getOrNull() ?: false,
    )
}

/**
 * 给 image 块标上 `offloaded:true`（M47，上游投影 `offloadMessageImages()`）：
 * 保留原块的全部字段，只在根上补标记；非 image 块原样返回。
 */
fun ContentBlock.markImageOffloaded(): ContentBlock {
    if (type != BlockType.IMAGE) return this
    val source = raw ?: return this
    return copy(
        raw = buildJsonObject {
            source.forEach { (key, value) -> if (key != "offloaded") put(key, value) }
            put("offloaded", true)
        },
    )
}

/** 按**出现顺序**（含已卸载的）给一张消息里选中的 image 标上卸载标记。 */
fun ChatMessage.markOffloadedImages(indexes: Set<Int>): ChatMessage {
    if (indexes.isEmpty()) return this
    var imageIndex = 0
    val marked = content.map { block ->
        if (block.type != BlockType.IMAGE) return@map block
        val index = imageIndex++
        if (index in indexes) block.markImageOffloaded() else block
    }
    return if (marked == content) this else copy(content = marked)
}

/** 会话面消息（[SessionMessage]）的同名投影，给 `SessionCodec.surfaceOf` 回放用。 */
fun SessionMessage.markOffloadedImages(indexes: Set<Int>): SessionMessage {
    if (indexes.isEmpty()) return this
    var imageIndex = 0
    val marked = content.map { block ->
        if (block.type != BlockType.IMAGE) return@map block
        val index = imageIndex++
        if (index in indexes) block.markImageOffloaded() else block
    }
    return if (marked == content) this else copy(content = marked)
}

/**
 * 一条 `image/offload` 的选中目标：会话面消息 seq + 该消息内**深度优先**的图片下标
 * （上游 `{targets:[{seq, imageIndexes}]}`；下标把已卸载的图片也算进去）。
 */
data class ImageOffloadTarget(val seq: Long, val imageIndexes: List<Int>)

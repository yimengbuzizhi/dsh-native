package xin.dshaa.core.model

/**
 * 会话里的一张图片（与上游 `ImageAttachmentRef` 同形）。
 *
 * 附件本体不进 JSONL（上游约定：附件在独立存储、消息里只放 `attachmentId`），
 * 所以这个引用要能独立寻址到 `files/attachments/v1/objects/<sha 前两位>/<sha>`。
 */
data class ImageRef(
    val attachmentId: String,
    val mediaType: String,
    val bytes: Int,
    val width: Int,
    val height: Int,
    val name: String? = null,
    /** 归一化把图片缩小了才有值（上游 `originalDimensions`）。 */
    val originalWidth: Int? = null,
    val originalHeight: Int? = null,
    /**
     * 请求限额卸载（M47，上游 `ImageBlock.offloaded`）：`image/offload` 事件选中过的出现位置。
     * 只是**投影态**，不写进 v4 消息；回放时由 `SessionCodec.surfaceOf` 按事件重新标上。
     */
    val offloaded: Boolean = false,
)

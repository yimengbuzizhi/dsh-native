package xin.dshaa.ui.shellapi.chat

/**
 * M85-C3-3b：通知附件抓取结果（聊天输入条 → 宿主设备能力）。
 *
 * 抓取实现仍在宿主的 `device.attach.AttachmentCapture`（要通知使用权、拼通知文本）；
 * UI 只消费结果并提示，避免 `ui:native-shell` 反向依赖 app。
 */
data class NotifyAttachResult(
    val ok: Boolean,
    val message: String,
    val needsGrant: Boolean = false,
)

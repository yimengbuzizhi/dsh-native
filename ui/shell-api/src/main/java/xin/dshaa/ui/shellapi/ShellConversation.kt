package xin.dshaa.ui.shellapi

import xin.dshaa.core.tools.AskAnswer
import xin.dshaa.ui.shellapi.chat.ChatSessionState
import xin.dshaa.ui.shellapi.chat.SessionBadge

/** 当前来源的会话摘要；不携带本机会话文件路径。 */
data class ConversationSessionUi(
    val id: String,
    val title: String,
    val updatedAt: Long,
    val running: Boolean = false,
    val badge: SessionBadge? = null,
    val legacy: Boolean = false,
)

/** 默认保留本机功能；远程只展示协议已提供的功能。 */
data class ChatUiCapabilities(
    val attachments: Boolean = true,
    val localCommands: Boolean = true,
    val modelSettings: Boolean = true,
    val inputSettings: Boolean = true,
    val workspace: Boolean = true,
    val messageMutation: Boolean = true,
    val toolNavigation: Boolean = true,
    val contextMeter: Boolean = true,
    val agentFeatures: Boolean = true,
    val localQueue: Boolean = true,
) {
    companion object {
        val Local = ChatUiCapabilities()
        val Remote = ChatUiCapabilities(false, false, false, false, false, false, false, false, false, false)
    }
}

/** 界面仅向当前来源提交；远程投影不进入本机 LiveChat 或 JSONL。 */
interface ShellConversation {
    /** null = 本机；非空 = 稳定的远程配置 id。 */
    val remoteProfileId: String?
    val isLocal: Boolean get() = remoteProfileId == null
    /** 包含来源和会话身份，用于隔离搜索、引用与弹层状态。 */
    val stateKey: String
    val state: ChatSessionState?
    val sessions: List<ConversationSessionUi>
    val capabilities: ChatUiCapabilities
    val assistantName: String
    val assistantAvatar: String
    val model: String?
    val toolCount: Int?
    val canSubmit: Boolean
    val connectionNotice: String?

    fun selectTarget(profileId: String?)
    fun ensureDraft()
    fun newChat()
    fun submit(text: String): Boolean
    fun stop()
    fun openSession(id: String)
    fun reconnect()
    fun answer(answers: List<AskAnswer>)
    /** 同步远程草稿；本机状态已由 ChatScreen 持有，可用默认空实现。 */
    fun updateDraft(text: String) {}
}

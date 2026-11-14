package xin.dshaa.nativeapp.remote

import xin.dshaa.core.tools.AskAnswer
import xin.dshaa.ui.shellapi.ConversationSessionUi
import xin.dshaa.ui.shellapi.NativeShellHost
import xin.dshaa.ui.shellapi.chat.ChatSessionState
import xin.dshaa.ui.shellapi.chat.SessionBadge

/** Only the local operations used by the router; no repository, path or writer crosses this boundary. */
internal interface LocalConversation {
    val state: ChatSessionState?
    val sessions: List<ConversationSessionUi>
    val assistantName: String
    val assistantAvatar: String
    val model: String?
    val toolCount: Int
    fun newChat()
    fun submit(text: String): Boolean
    fun stop()
    fun openSession(id: String)
    fun answer(answers: List<AskAnswer>)
}

internal class HostLocalConversation(private val host: NativeShellHost) : LocalConversation {
    override val state get() = host.chat
    override val sessions get() = host.sessions.map { summary ->
        val badge = host.badgeFor(summary.id)
        ConversationSessionUi(summary.id, summary.title, summary.updatedAt, badge == SessionBadge.RUNNING, badge, summary.legacy)
    }
    override val assistantName get() = host.settings.assistantName
    override val assistantAvatar get() = host.settings.assistantAvatar
    override val model get() = host.chat?.model ?: host.settings.model
    override val toolCount get() = host.toolCount
    override fun newChat() = host.newChat()
    override fun submit(text: String) = host.submit(text)
    override fun stop() = host.stop()
    override fun openSession(id: String) { host.sessions.firstOrNull { it.id == id }?.let(host::openSession) }
    override fun answer(answers: List<AskAnswer>) = host.resolveQuestions(answers)
}

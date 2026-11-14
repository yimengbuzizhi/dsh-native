package xin.dshaa.ui.shellapi.remote

enum class RemotePhase { Idle, Testing, Connected, Failed }

/** Display metadata only. The profile's token never enters this DTO. */
data class RemoteProfileUi(
    val id: String,
    val name: String,
    val baseUrl: String,
    val hasToken: Boolean,
    val lastConnectedAt: Long = 0L,
)

/** 远程会话列表的一行（来自 session/list 的 projections）。 */
data class RemoteSession(
    val id: String,
    val title: String,
    val cwd: String,
    val updatedAt: Long,
    val running: Boolean,
    val model: String,
    val lastMessageAt: Long,
)

data class RemoteAskOption(val label: String, val description: String)

data class RemoteAskQuestion(
    val id: String,
    val header: String,
    val question: String,
    val options: List<RemoteAskOption>,
    val multiSelect: Boolean = false,
    val detail: String? = null,
)

/** 一条工具卡：tool/call 建卡、tool/result 回填。 */
data class RemoteToolCard(
    val callId: String,
    val name: String,
    val arguments: String,
    val result: String? = null,
    val isError: Boolean = false,
)

enum class RemoteRole { User, Assistant, Tool, Notice }

/** 聊天里的一条：用户/助手正文、工具卡、系统提示。 */
data class RemoteChatMessage(
    val key: String,
    val role: RemoteRole,
    val text: String = "",
    val thinking: String = "",
    val tool: RemoteToolCard? = null,
    val question: RemoteAskQuestion? = null,
    val questionCallId: String = "",
    val answered: Boolean = false,
    val streaming: Boolean = false,
    val time: Long = 0L,
    /** Full tool question list; question above remains the first-item compatibility view. */
    val questions: List<RemoteAskQuestion> = emptyList(),
    val seq: Long? = null,
)

data class RemoteChatState(
    val sessionId: String,
    val title: String,
    val messages: List<RemoteChatMessage> = emptyList(),
    val running: Boolean = false,
    val historyLoading: Boolean = true,
    val notice: String? = null,
    val profileId: String = "",
    val model: String = "",
    val draft: String = "",
    /** Retained text for an uncertain/failed submission; never retried automatically. */
    val failedPrompt: String? = null,
    val questionsSending: Set<String> = emptySet(),
)

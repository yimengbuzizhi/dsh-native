package xin.dshaa.nativeapp.remote

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.text.input.TextFieldValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import xin.dshaa.core.model.BlockType
import xin.dshaa.core.model.ChatMessage
import xin.dshaa.core.model.ContentBlock
import xin.dshaa.core.model.MessageRole
import xin.dshaa.core.tools.AskAnswer
import xin.dshaa.core.tools.AskOption
import xin.dshaa.core.tools.AskQuestion
import xin.dshaa.ui.shellapi.ChatUiCapabilities
import xin.dshaa.ui.shellapi.ConversationSessionUi
import xin.dshaa.ui.shellapi.NativeShellHost
import xin.dshaa.ui.shellapi.ShellConversation
import xin.dshaa.ui.shellapi.chat.ChatSessionState
import xin.dshaa.ui.shellapi.chat.SessionBadge
import xin.dshaa.ui.shellapi.remote.RemoteChatMessage
import xin.dshaa.ui.shellapi.remote.RemoteChatState
import xin.dshaa.ui.shellapi.remote.RemotePhase
import xin.dshaa.ui.shellapi.remote.RemoteRole
import xin.dshaa.ui.shellapi.remote.ShellRemote
import java.io.Closeable

/**
 * UI routing only. Local state is returned by reference; remote states never enter LiveChat,
 * SessionRepository, local submit/commands, or any JSONL writer. All calls belong on Main.
 * An injected lifecycle scope is preferred; close() only cancels this adapter's observer.
 */
class ConversationAdapter internal constructor(
    private val local: LocalConversation,
    private val remote: ShellRemote,
    scope: CoroutineScope? = null,
) : ShellConversation, Closeable {
    constructor(host: NativeShellHost, remote: ShellRemote, scope: CoroutineScope? = null) :
        this(HostLocalConversation(host), remote, scope)

    private val ownedScope = if (scope == null) CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate) else null
    private val observerScope = scope ?: ownedScope!!
    override var remoteProfileId by mutableStateOf<String?>(null)
        private set
    private var activation by mutableStateOf(0L)
    private val cache = mutableMapOf<Pair<String, String>, ChatSessionState>()
    private val lastProjection = mutableMapOf<Pair<String, String>, RemoteChatState>()
    private val waitingStates = mutableMapOf<String, ChatSessionState>()
    private val observedGeneration = mutableMapOf<String, Long>()
    private var displayedKey: String? = null
    private var draftRequested = false
    private var createRequestedAt: Pair<Long, String>? = null
    private var pendingQuestion: QuestionTarget? = null
    private data class QuestionTarget(val profile: String, val session: String, val generation: Long, val callId: String)

    private val observer = observerScope.launch {
        snapshotFlow { Observation(remoteProfileId, remote.generation, remote.chat, remote.phase, remote.sending) }
            .collect { if (it.profile != null && it.profile == remoteProfileId) syncRemote() }
    }
    private data class Observation(val profile: String?, val generation: Long, val chat: RemoteChatState?, val phase: RemotePhase, val sending: Boolean)

    override val state: ChatSessionState?
        get() = if (isLocal) local.state else syncRemote()
    override val stateKey: String
        get() = if (isLocal) "local:${local.state?.sessionId ?: System.identityHashCode(local.state)}"
        else remoteIdentity(remoteProfileId!!, remote.chat?.takeIf { it.profileId == remoteProfileId }?.sessionId ?: "draft") + ":$activation:${remote.generation}"
    override val capabilities get() = if (isLocal) ChatUiCapabilities.Local else ChatUiCapabilities.Remote
    override val assistantName: String get() = if (isLocal) local.assistantName else
        remote.profiles.firstOrNull { it.id == remoteProfileId }?.name ?: "远程 DSH"
    override val assistantAvatar: String get() = if (isLocal) local.assistantAvatar else ""
    override val model: String? get() = if (isLocal) local.model else
        remote.chat?.takeIf { it.profileId == remoteProfileId }?.model?.takeIf { it.isNotBlank() }
    override val toolCount: Int? get() = if (isLocal) local.toolCount else null
    override val canSubmit: Boolean get() = if (isLocal) local.state?.readOnly == false && local.state?.stopping == false
        else remote.activeProfileId == remoteProfileId && remote.chat?.profileId == remoteProfileId && remote.canSend
    override val connectionNotice: String? get() = if (isLocal || remote.activeProfileId != remoteProfileId) null else
        remote.errorText ?: remote.chat?.takeIf { it.profileId == remoteProfileId }?.notice ?:
        remote.statusText.takeIf { remote.phase != RemotePhase.Connected || !remote.followConnected }
    override val sessions: List<ConversationSessionUi> get() = if (isLocal) local.sessions
    else if (remote.activeProfileId != remoteProfileId) emptyList() else remote.sessions.map {
        ConversationSessionUi(it.id, it.title, it.updatedAt, it.running, if (it.running) SessionBadge.RUNNING else null)
    }

    override fun selectTarget(profileId: String?) {
        if (profileId == remoteProfileId) return
        // A rejected selection must leave the current draft/create/question ownership intact.
        if (profileId == null) remote.deactivate() else if (!remote.selectProfile(profileId)) return
        pendingQuestion = null; displayedKey = null; draftRequested = false; createRequestedAt = null
        remoteProfileId = profileId; activation++
        if (profileId != null) { remote.connect(); syncRemote() }
    }
    override fun ensureDraft() {
        if (isLocal) { if (local.state == null) local.newChat(); return }
        draftRequested = true
        syncRemote()
    }
    override fun newChat() {
        if (isLocal) { local.newChat(); return }
        pendingQuestion = null; displayedKey = null; createRequestedAt = null
        remote.closeChat(); draftRequested = true; syncRemote()
    }
    override fun submit(text: String): Boolean {
        if (isLocal) return local.submit(text)
        if (!canSubmit) return false
        val accepted = remote.trySend(text) // Existing wire protocol always uses mode=queue, including running turns.
        if (accepted) syncRemote()
        return accepted
    }
    override fun stop() { if (isLocal) local.stop() else if (remote.activeProfileId == remoteProfileId) remote.stopTurn() }
    override fun openSession(id: String) {
        if (isLocal) { local.openSession(id); return }
        if (remote.activeProfileId != remoteProfileId) return
        val summary = remote.sessions.firstOrNull { it.id == id } ?: return
        pendingQuestion = null; displayedKey = null; draftRequested = false; createRequestedAt = null
        remote.openSession(summary); syncRemote()
    }
    override fun reconnect() {
        if (!isLocal && remote.activeProfileId == remoteProfileId) {
            createRequestedAt = null
            if (remote.chat == null) draftRequested = true
            remote.reconnect()
        }
    }
    override fun updateDraft(text: String) {
        if (isLocal) {
            local.state?.let { if (it.draft.text != text) it.draft = TextFieldValue(text) }
            return
        }
        val profile = remoteProfileId ?: return
        val current = syncRemote() ?: return
        if (current.draft.text != text) current.draft = TextFieldValue(text)
        if (remote.activeProfileId == profile && remote.chat?.profileId == profile) remote.updateDraft(text)
    }
    override fun answer(answers: List<AskAnswer>) {
        if (isLocal) { local.answer(answers); return }
        val captured = pendingQuestion ?: return
        val current = remote.chat ?: return
        if (captured.profile != remoteProfileId || captured.profile != remote.activeProfileId ||
            captured.profile != current.profileId || captured.session != current.sessionId ||
            captured.generation != remote.generation || !remote.followConnected ||
            captured.callId in current.questionsSending) return
        val question = current.messages.firstOrNull { it.questionCallId == captured.callId } ?: return
        if (!RemoteProtocol.questionAnswerable(question)) return
        val knownIds = question.questions.map { it.id }.toSet()
        if (knownIds.isEmpty() || answers.size != knownIds.size || answers.map { it.id }.toSet() != knownIds) return
        remote.answerQuestions(captured.callId, answers)
    }

    /** Synchronous read plus Main observer keeps message cards and failure-restored drafts current. */
    private fun syncRemote(): ChatSessionState? {
        val profile = remoteProfileId ?: return null
        if (remote.activeProfileId != profile) return waitingStates.getOrPut(profile) { ChatSessionState("") }
        val generation = remote.generation
        val oldGeneration = observedGeneration.put(profile, generation)
        if (oldGeneration != null && oldGeneration != generation) {
            pendingQuestion = null; createRequestedAt = null; displayedKey = null
        }
        val initialSource = remote.chat?.takeIf { it.profileId == profile }
        if (initialSource == null) {
            val waiting = waitingStates.getOrPut(profile) { ChatSessionState("") }
            waiting.title = "新远程会话"
            waiting.statusText = connectionNotice
            if (draftRequested && remote.phase == RemotePhase.Connected && !remote.sending &&
                createRequestedAt != (generation to profile)) {
                createRequestedAt = generation to profile
                remote.createSession()
            } else if (draftRequested && remote.phase == RemotePhase.Idle) remote.connect()
            return waiting
        }
        var source = initialSource
        val pendingDraft = waitingStates[profile]?.draft?.text.orEmpty()
        if (draftRequested && createRequestedAt != null && source.draft.isEmpty() && pendingDraft.isNotEmpty()) {
            waitingStates[profile]?.draft = TextFieldValue("")
            remote.updateDraft(pendingDraft)
            // updateDraft replaces the immutable transport snapshot; project that same snapshot now.
            val createdSessionId = source.sessionId
            source = remote.chat?.takeIf { it.profileId == profile && it.sessionId == createdSessionId } ?: source
        }
        draftRequested = false; createRequestedAt = null
        val identity = profile to source.sessionId
        val state = cache.getOrPut(identity) { ChatSessionState(source.model) }
        if (lastProjection[identity] !== source) {
            projectRemote(state, source)
            lastProjection[identity] = source
        }
        val display = remoteIdentity(profile, source.sessionId) + ":$activation:$generation"
        if (displayedKey != display) { pendingQuestion = null; displayedKey = display }
        val question = source.messages.firstOrNull {
            RemoteProtocol.questionAnswerable(it) && it.questionCallId !in source.questionsSending
        }
        pendingQuestion = question?.let { QuestionTarget(profile, source.sessionId, generation, it.questionCallId) }
        state.pendingQuestions = question?.questions?.map { AskQuestion(it.id, it.question, it.header,
            it.options.map { option -> AskOption(option.label, option.description) }, it.multiSelect, it.detail) }
        state.statusText = source.notice ?: if (source.running) "远程任务运行中 · 新消息将排队" else null
        return state
    }

    override fun close() { observer.cancel(); ownedScope?.cancel() }

    companion object {
        /** Length-prefixing avoids collisions even when remote ids contain ':' or '/'. */
        internal fun remoteIdentity(profile: String, session: String) = "remote:${profile.length}:$profile:${session.length}:$session"

        internal fun projectRemote(state: ChatSessionState, source: RemoteChatState) {
            val prefix = remoteIdentity(source.profileId, source.sessionId)
            state.sessionId = "$prefix:session"; state.title = source.title; state.model = source.model
            state.streaming = source.running; state.stopping = false; state.readOnly = false
            state.error = null; state.usageText = null; state.contextUsage = null; state.contextWindow = 0
            state.pendingApproval = null; state.goal = null; state.planActive = false; state.planPending = null
            state.queuedPrompts = emptyList(); state.queueNotice = null
            if (state.draft.text != source.draft) state.draft = TextFieldValue(source.draft)
            val converted = source.messages.flatMap { projectMessage(prefix, it) }
            if (state.messages.toList() != converted) { state.messages.clear(); state.messages.addAll(converted) }
        }

        internal fun projectMessage(prefix: String, message: RemoteChatMessage): List<ChatMessage> {
            val id = "$prefix:${message.key.length}:${message.key}"
            val content = buildList {
                if (message.thinking.isNotEmpty()) add(ContentBlock(BlockType.REASONING, text = message.thinking))
                if (message.text.isNotEmpty()) add(ContentBlock(BlockType.TEXT, text = message.text))
            }
            val tool = message.tool
            if (tool != null) {
                val callId = "$prefix:call:${tool.callId.length}:${tool.callId}"
                return buildList {
                    add(ChatMessage("$id:call", MessageRole.ASSISTANT,
                        content + ContentBlock(BlockType.TOOL_CALL, toolCallId = callId, name = tool.name, arguments = tool.arguments),
                        createdAt = message.time, seq = message.seq))
                    tool.result?.let { result -> add(ChatMessage("$id:result", MessageRole.TOOL,
                        listOf(ContentBlock(BlockType.TEXT, text = result)), createdAt = message.time,
                        error = if (tool.isError) "远程工具失败" else null, toolCallId = callId, seq = message.seq)) }
                }
            }
            val role = when (message.role) {
                RemoteRole.User -> MessageRole.USER
                RemoteRole.Assistant -> MessageRole.ASSISTANT
                RemoteRole.Tool, RemoteRole.Notice -> MessageRole.SYSTEM
            }
            return listOf(ChatMessage(id, role, content, createdAt = message.time,
                streaming = message.streaming, seq = message.seq, sourceKind = if (role == MessageRole.USER) "user" else null))
        }
    }
}

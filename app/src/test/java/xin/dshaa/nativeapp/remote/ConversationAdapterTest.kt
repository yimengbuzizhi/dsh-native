package xin.dshaa.nativeapp.remote

import androidx.compose.ui.text.input.TextFieldValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import org.junit.Assert.*
import org.junit.Test
import xin.dshaa.core.model.BlockType
import xin.dshaa.core.model.ChatMessage
import xin.dshaa.core.model.ContentBlock
import xin.dshaa.core.model.MessageRole
import xin.dshaa.core.tools.AskAnswer
import xin.dshaa.ui.shellapi.ChatUiCapabilities
import xin.dshaa.ui.shellapi.ConversationSessionUi
import xin.dshaa.ui.shellapi.chat.ChatSessionState
import xin.dshaa.ui.shellapi.chat.SessionBadge
import xin.dshaa.ui.shellapi.remote.*

/** Public routing/state behavior with a narrow local boundary, no NativeShellHost or Android fake. */
class ConversationAdapterTest {
    private class Local : LocalConversation {
        override var state: ChatSessionState? = ChatSessionState("local-model").apply { sessionId = "local-session" }
        override val sessions = listOf(ConversationSessionUi("local-session", "Local", 1, badge = SessionBadge.DONE))
        override val assistantName = "Local assistant"
        override val assistantAvatar = "local-avatar"
        override val model get() = state?.model ?: "default-model"
        override val toolCount = 17
        val submissions = mutableListOf<String>()
        val opened = mutableListOf<String>()
        val answers = mutableListOf<List<AskAnswer>>()
        var created = 0
        var stopped = 0
        var accept = true
        override fun newChat() { created++; state = ChatSessionState("local-model") }
        override fun submit(text: String): Boolean { submissions += text; return accept }
        override fun stop() { stopped++ }
        override fun openSession(id: String) { if (sessions.any { it.id == id }) opened += id }
        override fun answer(answers: List<AskAnswer>) { this.answers += answers }
    }
    private class Remote : ShellRemote {
        override val profiles = listOf(RemoteProfileUi("A", "Computer A", "http://fixture/a", true),
            RemoteProfileUi("B", "Computer B", "http://fixture/b", true))
        override var activeProfileId: String? = null
        override var generation = 0L
        override val baseUrl = "http://fixture"
        override val token = "public-test-token"
        override var phase = RemotePhase.Idle
        override var statusText = "Connecting"
        override var errorText: String? = null
        override var sessions = listOf(RemoteSession("same", "Remote", "C:/remote-private", 2, false, "remote-model", 2))
        override val sessionsLoading = false
        override var chat: RemoteChatState? = null
        override var sending = false
        override var followConnected = true
        override val canSend get() = phase == RemotePhase.Connected && followConnected && chat?.historyLoading == false && !sending
        val selected = mutableListOf<String>()
        val submitted = mutableListOf<String>()
        val answered = mutableListOf<Pair<String, List<AskAnswer>>>()
        val drafts = mutableListOf<String>()
        private val cached = mutableMapOf<String, RemoteChatState>()
        var connections = 0
        var creations = 0
        var reconnected = 0
        var stopped = 0
        var autoConnect = true
        var accept = true
        private fun cache() { chat?.let { cached[it.profileId] = it } }
        override fun selectProfile(id: String): Boolean {
            if (profiles.none { it.id == id }) return false
            cache(); selected += id; activeProfileId = id; generation++
            chat = cached[id]; phase = RemotePhase.Idle; sending = false
            return true
        }
        override fun connect() { connections++; if (autoConnect) phase = RemotePhase.Connected }
        override fun disconnect() { cache(); generation++; chat = null; phase = RemotePhase.Idle }
        override fun refreshSessions() {}
        override fun createSession() { creations++; sending = true }
        override fun openSession(session: RemoteSession) {
            cache(); chat = RemoteChatState(session.id, session.title, profileId = activeProfileId!!, historyLoading = false)
        }
        override fun closeChat() { cache(); chat = null; sending = false }
        override fun reconnect() { reconnected++ }
        override fun updateDraft(text: String) { drafts += text; chat = chat?.copy(draft = text) }
        override fun trySend(text: String): Boolean { if (!canSend || !accept || text.isBlank()) return false; submitted += text; return true }
        override fun send(text: String) { trySend(text) }
        override fun stopTurn() { stopped++ }
        override fun answerQuestions(callId: String, answers: List<AskAnswer>) { answered += callId to answers }
        override fun answerQuestion(callId: String, question: RemoteAskQuestion, selected: String) {}
        override fun updateBaseUrl(value: String) {}
        override fun updateToken(value: String) {}
        override fun testConnection() {}
        fun show(profile: String = activeProfileId!!, session: String = "same", draft: String = "", messages: List<RemoteChatMessage> = emptyList()) {
            chat = RemoteChatState(session, "$profile session", messages, profileId = profile, model = "$profile-model", draft = draft, historyLoading = false)
            sending = false
        }
    }
    private fun adapter(local: Local, remote: Remote): ConversationAdapter {
        // Deterministic synchronous projection: production's observer adds no routing behavior.
        val stoppedObserver = Job().apply { cancel() }
        return ConversationAdapter(local, remote, CoroutineScope(stoppedObserver))
    }
    private fun questions() = listOf(
        RemoteAskQuestion("one", "First", "Choose one", listOf(RemoteAskOption("A", "first"))),
        RemoteAskQuestion("two", "Second", "Choose several", listOf(RemoteAskOption("B", "second"), RemoteAskOption("C", "third")), true, "details"),
    )
    private fun ask(call: String = "call", answered: Boolean = false, result: String? = """{"pending":true}""") = RemoteChatMessage(
        "t:$call", RemoteRole.Tool, tool = RemoteToolCard(call, "ask_user_question", "{}", result),
        questionCallId = call, questions = questions(), answered = answered,
    )

    @Test fun localStateRemainsSameReferenceAndActionsRetainAcceptance() {
        val local = Local(); val remote = Remote()
        adapter(local, remote).use { router ->
            assertSame(local.state, router.state)
            assertEquals(ChatUiCapabilities.Local, router.capabilities)
            assertEquals("local:local-session", router.stateKey)
            assertEquals("Local assistant", router.assistantName)
            assertEquals("local-avatar", router.assistantAvatar)
            assertEquals("local-model", router.model)
            assertEquals(17, router.toolCount)
            assertEquals(local.sessions, router.sessions)
            router.updateDraft("local draft")
            assertEquals("local draft", local.state!!.draft.text)
            local.accept = false
            assertFalse(router.submit("rejected"))
            assertEquals("local draft", local.state!!.draft.text)
            local.accept = true
            assertTrue(router.submit("accepted"))
            router.openSession("missing"); router.openSession("local-session"); router.stop()
            val answer = listOf(AskAnswer("local-q", listOf("yes")))
            router.answer(answer)
            assertEquals(listOf("local-session"), local.opened)
            assertEquals(listOf(answer), local.answers)
            assertEquals(1, local.stopped)
            assertTrue(remote.submitted.isEmpty()); assertEquals(0, remote.connections)
            local.state!!.readOnly = true; assertFalse(router.canSubmit)
            local.state!!.readOnly = false; local.state!!.stopping = true; assertFalse(router.canSubmit)
            local.state = null; router.ensureDraft(); router.ensureDraft()
            assertEquals(1, local.created)
        }
    }

    @Test fun targetRoundTripKeepsLocalAndPerProfileDraftsWithSameSessionId() {
        val local = Local(); val remote = Remote()
        val localState = local.state!!
        localState.draft = TextFieldValue("local draft")
        localState.messages += ChatMessage("local-message", MessageRole.USER, listOf(ContentBlock(BlockType.TEXT, "local history")))
        val originalMessages = localState.messages.toList()
        adapter(local, remote).use { router ->
            router.selectTarget("A"); remote.show(messages = listOf(ask())); val a = router.state!!; val aKey = router.stateKey
            router.updateDraft("A draft"); router.stop(); assertTrue(router.submit("A prompt"))
            router.selectTarget("B"); remote.show(messages = listOf(ask())); val b = router.state!!; val bKey = router.stateKey
            router.updateDraft("B draft")
            assertNotSame(a, b); assertNotEquals(a.sessionId, b.sessionId); assertNotEquals(aKey, bKey)
            assertNotEquals(a.messages.first().content.last().toolCallId, b.messages.first().content.last().toolCallId)
            assertEquals(ChatUiCapabilities.Remote, router.capabilities)
            assertEquals("Computer B", router.assistantName); assertEquals("", router.assistantAvatar)
            assertEquals("B-model", router.model); assertNull(router.toolCount)
            assertEquals(listOf("same"), router.sessions.map { it.id })
            router.selectTarget("A"); assertSame(a, router.state); assertEquals("A draft", router.state!!.draft.text)
            assertNotEquals(aKey, router.stateKey)
            router.selectTarget(null)
            assertSame(localState, router.state); assertEquals("local draft", router.state!!.draft.text)
            assertEquals(originalMessages, localState.messages.toList())
            assertEquals("B draft", b.draft.text)
            assertTrue(local.submissions.isEmpty()); assertTrue(local.answers.isEmpty()); assertEquals(0, local.stopped)
            assertEquals(listOf("A prompt"), remote.submitted); assertEquals(1, remote.stopped)
        }
    }

    @Test fun generationInvalidatesCapturedQuestionEvenIfProfileAndSessionIdRepeat() {
        val local = Local(); val remote = Remote()
        adapter(local, remote).use { router ->
            router.selectTarget("A"); remote.show(messages = listOf(ask())); val oldKey = router.stateKey
            assertEquals(2, router.state!!.pendingQuestions!!.size)
            remote.generation++
            router.answer(questions().map { AskAnswer(it.id) })
            assertTrue(remote.answered.isEmpty()); assertNotEquals(oldKey, router.stateKey)
            assertEquals(2, router.state!!.pendingQuestions!!.size)
            router.answer(questions().map { AskAnswer(it.id) })
            assertEquals(1, remote.answered.size)
            router.selectTarget("B"); remote.show(messages = listOf(ask()))
            router.answer(questions().map { AskAnswer(it.id) })
            assertEquals(1, remote.answered.size)
        }
    }

    @Test fun foreignSnapshotCannotProjectSubmitOrAnswerThroughSelectedTarget() {
        val remote = Remote()
        adapter(Local(), remote).use { router ->
            router.selectTarget("A"); remote.show(messages = listOf(ask())); val a = router.state!!
            remote.show(profile = "B", messages = listOf(ask()))
            val waiting = router.state!!
            assertNotSame(a, waiting); assertTrue(waiting.messages.isEmpty()); assertFalse(router.canSubmit)
            assertFalse(router.submit("must not route")); router.answer(questions().map { AskAnswer(it.id) })
            assertTrue(remote.submitted.isEmpty()); assertTrue(remote.answered.isEmpty())
            remote.activeProfileId = "B"; remote.errorText = "B error"
            assertTrue(router.sessions.isEmpty()); assertNull(router.connectionNotice)
        }
    }

    @Test fun rejectedTargetSelectionRetainsDraftCreationAndCurrentQuestion() {
        val remote = Remote()
        adapter(Local(), remote).use { router ->
            router.selectTarget("A"); remote.show(draft = "keep", messages = listOf(ask()))
            val current = router.state!!; val key = router.stateKey
            router.selectTarget("missing")
            assertEquals("A", router.remoteProfileId); assertEquals(key, router.stateKey); assertSame(current, router.state)
            assertEquals("keep", current.draft.text)
            router.answer(questions().map { AskAnswer(it.id) }); assertEquals(1, remote.answered.size)
        }
    }

    @Test fun ensureDraftCreatesOnlyRemoteSessionOncePerAttemptAndKeepsFailedDraft() {
        val local = Local(); val remote = Remote().apply { autoConnect = false }
        adapter(local, remote).use { router ->
            router.selectTarget("A"); router.ensureDraft(); router.updateDraft("pending remote draft")
            assertEquals(0, remote.creations); assertEquals(0, local.created)
            remote.phase = RemotePhase.Connected
            router.ensureDraft(); router.ensureDraft(); assertEquals(1, remote.creations)
            remote.sending = false; remote.errorText = "create failed"
            repeat(3) { assertEquals("pending remote draft", router.state!!.draft.text) }
            assertEquals(1, remote.creations); assertTrue(remote.submitted.isEmpty())
            router.reconnect(); assertEquals(1, remote.reconnected)
            router.ensureDraft(); assertEquals(2, remote.creations)
            remote.show(session = "created")
            // The very first projection must include the transferred draft, not an obsolete snapshot.
            assertEquals("pending remote draft", router.state!!.draft.text)
            assertEquals("pending remote draft", remote.chat!!.draft)
            assertTrue(remote.submitted.isEmpty()); assertEquals(0, local.created)
        }
    }

    @Test fun waitingDraftAndCreationAttemptsAreIsolatedBetweenProfiles() {
        val remote = Remote()
        adapter(Local(), remote).use { router ->
            router.selectTarget("A"); router.ensureDraft(); router.updateDraft("A waiting")
            router.selectTarget("B"); router.ensureDraft(); router.updateDraft("B waiting")
            remote.show(session = "B-created"); assertEquals("B waiting", router.state!!.draft.text)
            router.selectTarget("A"); remote.sending = false
            router.ensureDraft(); remote.show(session = "A-created")
            assertEquals("A waiting", router.state!!.draft.text)
            assertEquals(3, remote.creations); assertTrue(remote.submitted.isEmpty())
        }
    }

    @Test fun runningRemoteSubmissionUsesRemoteAcceptanceAndFailureRestoresProjection() {
        val local = Local(); val remote = Remote()
        adapter(local, remote).use { router ->
            router.selectTarget("A"); remote.show(draft = "send me")
            remote.chat = remote.chat!!.copy(running = true)
            val state = router.state!!
            assertTrue(state.streaming); assertTrue(router.canSubmit)
            assertEquals("远程任务运行中 · 新消息将排队", state.statusText)
            remote.accept = false; assertFalse(router.submit("send me")); assertEquals("send me", state.draft.text)
            remote.accept = true; assertTrue(router.submit("send me"))
            remote.chat = remote.chat!!.copy(draft = "", failedPrompt = "send me")
            assertEquals("", router.state!!.draft.text)
            remote.chat = remote.chat!!.copy(draft = "send me", notice = "unknown result")
            assertSame(state, router.state); assertEquals("send me", state.draft.text); assertEquals("unknown result", router.connectionNotice)
            assertEquals(listOf("send me"), remote.submitted); assertTrue(local.submissions.isEmpty())
        }
    }

    @Test fun questionsPreserveMultiSelectCustomAndSkipWhileRejectingIncompleteOrDuplicateBatches() {
        val remote = Remote()
        adapter(Local(), remote).use { router ->
            router.selectTarget("A"); remote.show(messages = listOf(ask()))
            val projected = router.state!!.pendingQuestions!!
            assertEquals(listOf("one", "two"), projected.map { it.id })
            assertTrue(projected[1].multiSelect); assertEquals("details", projected[1].detail)
            assertEquals(listOf("B", "C"), projected[1].options.map { it.label })
            router.answer(listOf(AskAnswer("one")))
            router.answer(listOf(AskAnswer("one"), AskAnswer("one")))
            router.answer(listOf(AskAnswer("one"), AskAnswer("unknown")))
            assertTrue(remote.answered.isEmpty())
            val full = listOf(AskAnswer("one"), AskAnswer("two", listOf("B", "C"), "custom"))
            router.answer(full)
            assertEquals("call" to full, remote.answered.single())
            remote.chat = remote.chat!!.copy(questionsSending = setOf("call"))
            router.answer(full); assertEquals(1, remote.answered.size)
            assertNull(router.state!!.pendingQuestions)
            remote.chat = remote.chat!!.copy(questionsSending = emptySet(), messages = listOf(ask(answered = true)))
            assertNull(router.state!!.pendingQuestions)
            remote.chat = remote.chat!!.copy(messages = listOf(ask(result = "settled")))
            assertNull(router.state!!.pendingQuestions)
        }
    }

    @Test fun questionCompletionOrDisconnectBetweenProjectionAndAnswerRejectsStaleAction() {
        val remote = Remote()
        adapter(Local(), remote).use { router ->
            router.selectTarget("A"); remote.show(messages = listOf(ask())); router.state
            val full = questions().map { AskAnswer(it.id) }
            remote.chat = remote.chat!!.copy(messages = listOf(ask(result = "completed")))
            router.answer(full); assertTrue(remote.answered.isEmpty())
            remote.show(messages = listOf(ask())); router.state
            remote.followConnected = false; router.answer(full); assertTrue(remote.answered.isEmpty())
        }
    }

    @Test fun toolProjectionPairsNamespacedCallsAndResultsAndRetainsErrors() {
        val source = RemoteChatState("same", "Remote", profileId = "A", messages = listOf(
            RemoteChatMessage("tool", RemoteRole.Tool, text = "before tool", thinking = "reason", time = 12, seq = 7,
                tool = RemoteToolCard("shared-call", "pwsh", "{\"x\":1}", "failed output", true)),
        ))
        val state = ChatSessionState("old")
        ConversationAdapter.projectRemote(state, source)
        val call = state.messages[0]; val result = state.messages[1]
        assertEquals(MessageRole.ASSISTANT, call.role); assertEquals(MessageRole.TOOL, result.role)
        assertEquals(listOf(BlockType.REASONING, BlockType.TEXT, BlockType.TOOL_CALL), call.content.map { it.type })
        val block = call.content.last()
        assertEquals("pwsh", block.name); assertEquals("{\"x\":1}", block.arguments)
        assertEquals(block.toolCallId, result.toolCallId); assertEquals(result, state.toolResults[block.toolCallId])
        assertEquals("failed output", result.content.single().text); assertEquals("远程工具失败", result.error)
        assertEquals(12L, result.createdAt); assertEquals(7L, result.seq)
        ConversationAdapter.projectRemote(state, source.copy(messages = listOf(source.messages.single().copy(tool = source.messages.single().tool!!.copy(result = null)))))
        assertEquals(1, state.messages.size); assertTrue(state.toolResults.isEmpty())
    }

    @Test fun projectionReplacesStreamingMessagesWithoutMutatingLocalOrInventingCapabilities() {
        val state = ChatSessionState("old").apply { usageText = "local usage"; contextWindow = 123; planActive = true; planPending = true }
        val first = RemoteChatState("s", "Title", profileId = "A", model = "m", running = true, draft = "draft", messages = listOf(
            RemoteChatMessage("u", RemoteRole.User, text = "hello"),
            RemoteChatMessage("live", RemoteRole.Assistant, text = "one", thinking = "think", streaming = true),
            RemoteChatMessage("notice", RemoteRole.Notice, text = "notice"),
        ))
        ConversationAdapter.projectRemote(state, first)
        assertEquals(listOf(MessageRole.USER, MessageRole.ASSISTANT, MessageRole.SYSTEM), state.messages.map { it.role })
        assertEquals("user", state.messages[0].sourceKind); assertTrue(state.messages[1].streaming)
        assertEquals("think", state.messages[1].content.first().text)
        assertEquals("draft", state.draft.text); assertEquals("m", state.model); assertTrue(state.streaming)
        assertNull(state.usageText); assertNull(state.contextUsage); assertEquals(0, state.contextWindow)
        assertFalse(state.planActive); assertNull(state.planPending); assertNull(state.goal); assertTrue(state.queuedPrompts.isEmpty())
        ConversationAdapter.projectRemote(state, first.copy(running = false, messages = listOf(RemoteChatMessage("durable", RemoteRole.Assistant, text = "done"))))
        assertFalse(state.streaming); assertEquals(1, state.messages.size); assertEquals("done", state.messages.single().content.single().text)
    }

    @Test fun lengthPrefixedIdentityDoesNotCollideForDelimiterContainingIds() {
        assertNotEquals(ConversationAdapter.remoteIdentity("a:b", "c"), ConversationAdapter.remoteIdentity("a", "b:c"))
        val prefix = ConversationAdapter.remoteIdentity("a/b", "same")
        val user = ConversationAdapter.projectMessage(prefix, RemoteChatMessage("call:x", RemoteRole.User, text = "x")).single()
        val tool = ConversationAdapter.projectMessage(prefix, RemoteChatMessage("x", RemoteRole.Tool, tool = RemoteToolCard("x", "tool", "{}"))).single()
        assertNotEquals(user.id, tool.id)
    }
}

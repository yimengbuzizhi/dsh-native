package xin.dshaa.nativeapp.chat

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import xin.dshaa.core.llm.LlmConfig
import xin.dshaa.core.llm.LlmEvent
import xin.dshaa.core.llm.LlmProvider
import xin.dshaa.core.model.BlockType
import xin.dshaa.core.model.SessionEvent
import xin.dshaa.core.protocol.ApiRequest
import xin.dshaa.core.protocol.ContentBlock
import xin.dshaa.core.session.PromptDelivery
import xin.dshaa.core.session.PromptQueueStatus
import xin.dshaa.core.session.PromptQueueStore
import xin.dshaa.core.session.QueuedPrompt
import xin.dshaa.core.session.SessionSession
import xin.dshaa.ui.shellapi.NativeShellHost
import xin.dshaa.ui.shellapi.chat.ChatSessionState
import java.io.File
import java.io.FileOutputStream
import java.util.Base64
import java.util.UUID

/**
 * Debug-only provider for [PromptQueueDeviceProbe]. The AppVM wrapper registers/selects it through
 * its normal debug override and restores the previous override afterwards. No network or tools.
 *
 * Each fixture's INIT request emits a visible prefix, leaves a 2.5-second enqueue window, then
 * completes (or emits ERROR / max_tokens). Later queue/steer inputs finish without that delay. The
 * decision comes from the latest fixture user input, so an old INIT cannot poison a resumed turn.
 */
class DebugM86QueueProvider(private val initialDelayMs: Long = 2_500L) : LlmProvider {
    init { require(initialDelayMs in 1_500L..2_500L) }

    override val id: String = ID
    override val label: String = "Debug M86-G Queue（不联网）"

    override fun stream(request: ApiRequest, config: LlmConfig): Flow<LlmEvent> = flow {
        val tag = request.messages.asSequence().filter { it.role == "user" }
            .flatMap { it.content.asSequence().filterIsInstance<ContentBlock.Text>() }
            .flatMap { TAG.findAll(it.text).map { match -> match.value } }
            .lastOrNull()
        if (tag == null) {
            emit(LlmEvent.Failed("M86-G fixture tag missing", code = "M86G_FIXTURE_INPUT"))
            return@flow
        }
        emit(LlmEvent.MessageStart(id = "m86g-fixture", model = config.model, inputTokens = 0))
        emit(LlmEvent.BlockStart(index = 0, type = "text", toolId = null, toolName = null))
        emit(LlmEvent.TextDelta(index = 0, text = PARTIAL))
        val initial = tag.contains(":INIT:")
        if (initial) delay(initialDelayMs)
        if (initial && tag.endsWith(":ERROR]")) {
            emit(LlmEvent.Failed("M86-G deterministic fixture error", code = "M86G_FIXTURE_ERROR"))
            return@flow
        }
        emit(LlmEvent.TextDelta(index = 0, text = " done"))
        emit(LlmEvent.BlockStop(index = 0))
        emit(LlmEvent.MessageDelta(
            stopReason = if (initial && tag.endsWith(":MAX_TOKEN]")) "max_tokens" else "end_turn",
            outputTokens = 2,
        ))
        emit(LlmEvent.MessageStop)
    }

    companion object {
        const val ID = "debug.m86g.queue"
        internal const val PARTIAL = "M86G fixture partial"
        private val TAG = Regex("\\[M86G:[A-Za-z0-9_-]+:[A-Za-z0-9_-]+:(?:INIT|INPUT):[A-Z0-9_]+]")
    }
}

/**
 * Real Android/AppVM queue fixture. Run on the AppVM's own viewModelScope, after confirming there
 * are no user writers. The wrapper must capture/restore the exact original LiveChat (including a
 * draft/null foreground), settings/model/role selections and debug provider preferences in finally.
 * This runner only changes sendWhileRunningMode and the current model's vision flag, restoring both.
 *
 * [run]'s session callback returns the *current* LiveChat handle; awaitIdle must wait for completed
 * writer jobs AND AppVM completion callbacks (activeTurns empty), not just state.streaming=false.
 * dropAndReopen must assert idle, remove the current fixture LiveChat from lives, then await the
 * normal openSessionById(id). A focus/open that reuses lives does not exercise disk/recover.
 *
 * All sends/attachments/cancel/resume/stop go through NativeShellHost. The only queue mutation made
 * directly is a claim while idle in the restart fixture, modelling a crash after claim/before append.
 * No session events are forged. No files/sessions are deleted; fixtures remain inspectable. Reports
 * contain only newly created session IDs, counts, allowlisted event source fields and check results.
 */
object PromptQueueDeviceProbe {
    suspend fun run(
        host: NativeShellHost,
        session: () -> SessionSession?,
        queueStore: PromptQueueStore,
        context: Context,
        fixtureDir: File,
        activeTurnCount: () -> Int,
        awaitIdle: suspend () -> Unit,
        dropAndReopen: suspend (String) -> Unit,
        writeReport: suspend (JsonObject) -> Unit,
    ): JsonObject = withContext(Dispatchers.Main.immediate) {
        val runner = Runner(host, session, queueStore, context, fixtureDir, activeTurnCount, awaitIdle, dropAndReopen)
        val oldMode = host.settings.sendWhileRunningMode
        val oldVision = host.settings.visionEnabled
        var cancelled: CancellationException? = null
        try {
            runner.check("no_user_active_turns", activeTurnCount() == 0)
            runner.fixtures()
        } catch (e: CancellationException) {
            cancelled = e
            runner.failure("runner_cancelled")
        } catch (e: ProbeFailure) {
            runner.failure(e.checkId)
        } catch (_: Exception) {
            // Provider/IO/host errors can contain endpoint paths or message content: never serialize them.
            runner.failure("runner_host_or_io_failure")
        } finally {
            withContext(NonCancellable) {
                runner.stopFixtureWriters()
                host.settings.setSendWhileRunningMode(oldMode)
                host.settings.setVisionEnabled(oldVision)
            }
        }
        val report = runner.report()
        withContext(NonCancellable) { writeReport(report) }
        cancelled?.let { throw it }
        report
    }

    private class ProbeFailure(val checkId: String) : IllegalStateException(checkId)

    private class Runner(
        val host: NativeShellHost,
        val session: () -> SessionSession?,
        val store: PromptQueueStore,
        val context: Context,
        val fixtureDir: File,
        val activeTurnCount: () -> Int,
        val awaitIdle: suspend () -> Unit,
        val dropAndReopen: suspend (String) -> Unit,
    ) {
        val runId = UUID.randomUUID().toString().replace("-", "")
        val created = LinkedHashMap<String, ChatSessionState>()
        val launched = LinkedHashSet<ChatSessionState>()
        val handles = LinkedHashMap<String, SessionSession>()
        val results = ArrayList<JsonObject>()
        var completed = false
        var cleanupOk = true

        fun check(id: String, ok: Boolean) {
            results += buildJsonObject { put("id", id); put("passed", ok) }
            if (!ok) throw ProbeFailure(id)
        }

        fun failure(id: String) {
            if (results.none { it["id"]?.jsonPrimitive?.contentOrNull == id }) {
                results += buildJsonObject { put("id", id); put("passed", false) }
            }
        }

        fun tag(case: String, action: String, initial: Boolean = false): String =
            "[M86G:$runId:$case:${if (initial) "INIT" else "INPUT"}:$action]"

        suspend fun waitFor(id: String, predicate: () -> Boolean) {
            val deadline = System.nanoTime() + 12_000_000_000L
            while (!predicate()) {
                if (System.nanoTime() >= deadline) { check(id, false); return }
                delay(20)
            }
            check(id, true)
        }

        suspend fun idle(id: String) {
            withTimeout(20_000L) { awaitIdle() }
            check(id, activeTurnCount() == 0 && created.values.none { it.streaming || it.stopping })
        }

        fun currentHandle(state: ChatSessionState): SessionSession {
            val handle = session()
            check("fixture_current_handle", handle != null && handle.header.id == state.sessionId &&
                created[state.sessionId] === state)
            return requireNotNull(handle).also { handles[it.header.id] = it }
        }

        suspend fun start(case: String, ending: String = "OK"): ChatSessionState {
            check("${case}_starts_idle", activeTurnCount() == 0)
            host.newChat()
            val state = host.chat ?: throw ProbeFailure("${case}_new_chat")
            check("${case}_new_draft", state.sessionId == null && state.messages.isEmpty() && !state.readOnly)
            launched += state
            check("${case}_initial_submit", host.submit(tag(case, ending, initial = true)))
            waitFor("${case}_session_created") {
                state.sessionId != null && session()?.header?.id == state.sessionId
            }
            val id = requireNotNull(state.sessionId)
            check("${case}_unique_session", !created.containsKey(id))
            created[id] = state
            currentHandle(state)
            waitFor("${case}_streaming_partial") {
                state.sessionId != null && state.streaming && state.messages.any { message ->
                    message.content.any { it.type == BlockType.TEXT && it.text == DebugM86QueueProvider.PARTIAL }
                }
            }
            return state
        }

        fun enqueue(state: ChatSessionState, case: String, action: String): QueuedPrompt {
            check("${case}_${action}_still_streaming", host.chat === state && state.streaming)
            val before = state.queuedPrompts.map { it.id }.toSet()
            check("${case}_${action}_submit", host.submit(tag(case, action)))
            val added = state.queuedPrompts.filter { it.id !in before }
            check("${case}_${action}_durable_queue", added.size == 1)
            val entry = added.single()
            check("${case}_${action}_queue_ui_matches_disk", store.list(entry.owner).any { it == entry })
            return entry
        }

        fun source(event: SessionEvent): JsonObject? {
            val data = event.data as? JsonObject ?: return null
            return ((data["message"] as? JsonObject) ?: data)["source"] as? JsonObject
        }

        fun sourceString(event: SessionEvent, key: String): String? = source(event)?.get(key)?.jsonPrimitive?.contentOrNull

        fun delivered(handle: SessionSession, promptId: String): List<SessionEvent> =
            handle.events.filter { it.type == "user/message" && sourceString(it, "promptId") == promptId }

        fun turnAt(handle: SessionSession, target: SessionEvent): Int? {
            var turn: Int? = null
            for (event in handle.events) {
                if (event.type == "turn/start") turn = (event.data as? JsonObject)?.get("turn")?.jsonPrimitive?.intOrNull
                if (event === target) return turn
            }
            return null
        }

        fun deliveryChecks(id: String, handle: SessionSession, entries: List<QueuedPrompt>, delivery: String) {
            val events = entries.map { delivered(handle, it.id) }
            check("${id}_each_delivered_once", events.all { it.size == 1 })
            check("${id}_source_is_user_$delivery", events.flatten().all {
                sourceString(it, "kind") == "user" && sourceString(it, "delivery") == delivery
            })
            val seqs = events.map { it.single().seq ?: -1L }
            check("${id}_fifo", seqs.zipWithNext().all { (a, b) -> a > 0 && a < b })
            check("${id}_acked", entries.all { entry -> store.list(entry.owner).none { it.id == entry.id } })
        }

        fun balanced(id: String, handle: SessionSession) {
            val events = handle.events
            check("${id}_balanced_turns", events.count { it.type == "turn/start" } == events.count { it.type == "turn/end" })
            check("${id}_balanced_steps", events.count { it.type == "step/start" } == events.count { it.type == "step/end" })
        }

        suspend fun fixtures() {
            host.settings.setSendWhileRunningMode("queue")
            val fifo = start("fifo")
            val q1 = enqueue(fifo, "fifo", "ONE")
            val cancelled = enqueue(fifo, "fifo", "CANCEL")
            val q2 = enqueue(fifo, "fifo", "TWO")
            host.cancelQueuedPrompt(cancelled.id)
            check("fifo_cancel_ui_and_disk", fifo.queuedPrompts.none { it.id == cancelled.id } &&
                store.list(cancelled.owner).none { it.id == cancelled.id })
            idle("fifo_writer_finished")
            val fh = currentHandle(fifo)
            deliveryChecks("queue", fh, listOf(q1, q2), "queue")
            check("fifo_cancel_never_delivered", delivered(fh, cancelled.id).isEmpty())
            check("queue_separate_turns", listOf(q1, q2).map { turnAt(fh, delivered(fh, it.id).single()) }.let {
                it.all { turn -> turn != null && turn > 1 } && it.distinct().size == 2
            })
            check("queue_ui_empty", fifo.queuedPrompts.isEmpty())
            balanced("queue", fh)

            host.settings.setSendWhileRunningMode("steer")
            val steer = start("steer")
            val s1 = enqueue(steer, "steer", "ONE")
            val s2 = enqueue(steer, "steer", "TWO")
            check("steer_saved_as_steer", listOf(s1, s2).all { it.delivery == PromptDelivery.STEER })
            idle("steer_writer_finished")
            val sh = currentHandle(steer)
            deliveryChecks("steer", sh, listOf(s1, s2), "steer")
            check("steer_one_turn_multiple_steps", sh.events.count { it.type == "turn/start" } == 1 &&
                sh.events.count { it.type == "step/start" } >= 3 &&
                listOf(s1, s2).all { turnAt(sh, delivered(sh, it.id).single()) == 1 })
            check("steer_after_assistant_boundary", listOf(s1, s2).all { entry ->
                val event = delivered(sh, entry.id).single()
                sh.events.takeWhile { it !== event }.lastOrNull { it.type == "assistant/message" } != null
            })
            balanced("steer", sh)

            host.settings.setSendWhileRunningMode("queue")
            val stopped = start("stop")
            val stopEntry = enqueue(stopped, "stop", "AFTER_STOP")
            host.stop()
            idle("stop_writer_finished")
            val stopHandle = currentHandle(stopped)
            check("stop_retains_paused_queue", stopped.queuedPrompts.singleOrNull()?.let {
                it.id == stopEntry.id && it.pausedReason != null && it.status == PromptQueueStatus.PENDING
            } == true)
            val stoppedEvents = stopHandle.events.size
            delay(180)
            check("stop_no_automatic_delivery", stopHandle.events.size == stoppedEvents &&
                delivered(stopHandle, stopEntry.id).isEmpty() && activeTurnCount() == 0)
            check("stop_aborted_turn", stopHandle.events.any { event ->
                event.type == "turn/end" && ((event.data as? JsonObject)?.get("reason") as? JsonObject)
                    ?.get("kind")?.jsonPrimitive?.contentOrNull == "aborted"
            })
            host.resumeQueuedPrompts()
            idle("stop_manual_resume_finished")
            deliveryChecks("stop_resume", stopHandle, listOf(stopEntry), "queue")
            balanced("stop", stopHandle)

            failurePause("error", "ERROR")
            failurePause("max_tokens", "MAX_TOKEN")
            attachments()
            isolation()
            restart()
            completed = true
        }

        suspend fun failurePause(case: String, ending: String) {
            val state = start(case, ending)
            val entry = enqueue(state, case, "RESUME")
            idle("${case}_writer_finished")
            val handle = currentHandle(state)
            check("${case}_visible_failure", state.error != null)
            check("${case}_paused_without_delivery", state.queuedPrompts.singleOrNull()?.let {
                it.id == entry.id && it.pausedReason != null
            } == true && delivered(handle, entry.id).isEmpty())
            val eventCount = handle.events.size
            delay(180)
            check("${case}_stays_idle_until_resume", handle.events.size == eventCount && activeTurnCount() == 0)
            host.resumeQueuedPrompts()
            idle("${case}_manual_resume_finished")
            deliveryChecks("${case}_resume", handle, listOf(entry), "queue")
            check("${case}_resume_clears_failure", state.error == null)
            balanced(case, handle)
        }

        suspend fun attachments() {
            val png = makeImageFixture()
            val state = start("attachments")
            check("attachments_add_text_while_running", host.attachTextFile("m86g-fixture.txt", "M86G fixture attachment", state))
            host.attachImage(Uri.fromFile(png), state)
            waitFor("attachments_add_image_while_running") { state.pendingImages.size == 1 || state.attachError != null }
            check("attachments_both_pending", state.streaming && state.pendingFiles.size == 1 &&
                state.pendingImages.size == 1 && state.attachError == null)
            val refs = state.pendingImages.map { it.ref }
            val files = state.pendingFiles.toList()
            host.settings.setVisionEnabled(false)
            check("attachments_rejected_send", !host.submit(tag("attachments", "PAYLOAD")))
            check("attachments_rejection_retains_draft", state.pendingFiles.toList() == files &&
                state.pendingImages.map { it.ref } == refs && state.queuedPrompts.isEmpty())
            host.settings.setVisionEnabled(true)
            val entry = enqueue(state, "attachments", "PAYLOAD")
            check("attachments_cleared_only_after_durable_queue", state.pendingFiles.isEmpty() &&
                state.pendingImages.isEmpty() && entry.payload.images == refs &&
                entry.payload.textBlocks.size >= 2 && store.list(entry.owner).any { it == entry })
            idle("attachments_writer_finished")
            val handle = currentHandle(state)
            deliveryChecks("attachments", handle, listOf(entry), "queue")
            val content = (delivered(handle, entry.id).single().data as? JsonObject)?.get("content") as? JsonArray
            check("attachments_delivered_text_and_image_blocks", content != null &&
                content.count { (it as? JsonObject)?.get("type")?.jsonPrimitive?.contentOrNull == "text" } >= 2 &&
                content.count { (it as? JsonObject)?.get("type")?.jsonPrimitive?.contentOrNull == "image" } == 1)
            balanced("attachments", handle)
        }

        suspend fun makeImageFixture(): File = withContext(Dispatchers.IO) {
            val directory = fixtureDir.canonicalFile
            val roots = listOf(context.filesDir.canonicalFile, context.cacheDir.canonicalFile)
            check("attachments_fixture_is_app_private", roots.any {
                directory.path.startsWith(it.path + File.separator)
            })
            check("attachments_fixture_directory", directory.isDirectory || directory.mkdirs())
            val file = File(directory, "m86g-$runId.png")
            check("attachments_fixture_new_file", file.createNewFile())
            // A complete 2x2 RGBA PNG. It is imported via ContentResolver/attachImage, not PendingImage.
            val bytes = Base64.getDecoder().decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAIAAAACCAYAAABytg0kAAAAFUlEQVR4nGOUy1v1n4GBgYEJRIAwACD8Ajnup4pJAAAAAElFTkSuQmCC",
            )
            FileOutputStream(file).use { it.write(bytes); it.fd.sync() }
            file
        }

        suspend fun isolation() {
            val a = start("isolation_a")
            val aEntry = enqueue(a, "isolation_a", "PAUSED_A")
            host.stop()
            idle("isolation_a_stopped")
            val aHandle = currentHandle(a)
            val b = start("isolation_b")
            val bEntry = enqueue(b, "isolation_b", "ONLY_B")
            idle("isolation_b_finished")
            val bHandle = currentHandle(b)
            deliveryChecks("isolation_b", bHandle, listOf(bEntry), "queue")
            check("isolation_a_paused_while_b_runs", store.list(aEntry.owner).singleOrNull()?.pausedReason != null &&
                delivered(aHandle, aEntry.id).isEmpty())
            check("isolation_no_cross_session_sources", delivered(bHandle, aEntry.id).isEmpty() &&
                delivered(aHandle, bEntry.id).isEmpty() && b.queuedPrompts.isEmpty())
            // Explicit captured-target attachment must remain on background A, including async image IO.
            check("isolation_targeted_text_attachment", host.attachTextFile("m86g-target-a.txt", "M86G captured target", a))
            val png = File(fixtureDir.canonicalFile, "m86g-$runId.png")
            host.attachImage(Uri.fromFile(png), a)
            waitFor("isolation_targeted_image_attachment") { a.pendingImages.size == 1 || a.attachError != null }
            check("isolation_pending_attachments_stay_on_target", a.pendingFiles.size == 1 && a.pendingImages.size == 1 &&
                b.pendingFiles.isEmpty() && b.pendingImages.isEmpty())
            host.focusSession(requireNotNull(a.sessionId))
            waitFor("isolation_focus_a") { host.chat === a }
            host.resumeQueuedPrompts()
            idle("isolation_a_resume_finished")
            deliveryChecks("isolation_a", currentHandle(a), listOf(aEntry), "queue")
            check("isolation_resume_does_not_consume_later_draft", a.pendingFiles.size == 1 && a.pendingImages.size == 1)
            balanced("isolation_a", aHandle)
            balanced("isolation_b", bHandle)
        }

        suspend fun restart() {
            val before = start("restart")
            val entry = enqueue(before, "restart", "RECOVER")
            host.stop()
            idle("restart_writer_stopped")
            val oldHandle = currentHandle(before)
            val id = requireNotNull(before.sessionId)
            // Crash-window setup: claim the real UI-saved item, with no fabricated history/ack.
            store.resume(entry.owner)
            val claimed = store.claim(entry.owner)
            check("restart_setup_unappended_claim", claimed?.id == entry.id &&
                claimed?.status == PromptQueueStatus.CLAIMED && delivered(oldHandle, entry.id).isEmpty())
            dropAndReopen(id)
            val reopened = host.chat
            check("restart_uses_new_live_and_handle", reopened != null && reopened !== before && reopened.sessionId == id &&
                session() !== oldHandle && session()?.header?.id == id)
            created[id] = requireNotNull(reopened)
            val recovered = currentHandle(reopened)
            check("restart_claim_returns_pending_paused", reopened.queuedPrompts.singleOrNull()?.let {
                it.id == entry.id && it.status == PromptQueueStatus.PENDING && it.pausedReason != null
            } == true)
            val eventCount = recovered.events.size
            delay(180)
            check("restart_requires_manual_resume", activeTurnCount() == 0 && recovered.events.size == eventCount &&
                delivered(recovered, entry.id).isEmpty())
            host.resumeQueuedPrompts()
            idle("restart_resume_finished")
            deliveryChecks("restart", recovered, listOf(entry), "queue")
            dropAndReopen(id)
            val again = host.chat
            check("restart_second_reopen", again != null && again !== reopened && again.sessionId == id)
            created[id] = requireNotNull(again)
            val againHandle = currentHandle(again)
            host.resumeQueuedPrompts()
            idle("restart_second_resume_idle")
            check("restart_delivery_not_replayed", delivered(againHandle, entry.id).size == 1 &&
                again.queuedPrompts.isEmpty() && store.list(entry.owner).isEmpty())
            balanced("restart", againHandle)
        }

        suspend fun stopFixtureWriters() {
            try {
                // Only touch state objects created by this run. Never stop an unrelated user turn.
                (launched + created.values).forEach { state ->
                    if (state.streaming || state.stopping) {
                        state.sessionId?.let { host.focusSession(it) }
                        if (host.chat === state) host.stop()
                    }
                }
                if (launched.isNotEmpty()) withTimeout(20_000L) { awaitIdle() }
                cleanupOk = activeTurnCount() == 0
            } catch (_: Exception) {
                cleanupOk = false
            }
            if (!cleanupOk) failure("fixture_cleanup_idle")
        }

        fun report(): JsonObject = buildJsonObject {
            put("fixture", "M86-G-AppVM")
            put("version", 1)
            put("completed", completed)
            put("passed", completed && cleanupOk && results.all { it["passed"] == JsonPrimitive(true) })
            put("createdSessionCount", created.size)
            put("createdSessionIds", JsonArray(created.keys.map(::JsonPrimitive)))
            put("checks", JsonArray(results))
            put("sessions", buildJsonArray {
                handles.forEach { (id, handle) -> add(buildJsonObject {
                    put("sessionId", id)
                    put("eventCount", handle.events.size)
                    put("turnCount", handle.events.count { it.type == "turn/start" })
                    put("stepCount", handle.events.count { it.type == "step/start" })
                    put("userMessageCount", handle.events.count { it.type == "user/message" })
                    put("assistantMessageCount", handle.events.count { it.type == "assistant/message" })
                    put("toolResultCount", handle.events.count { it.type == "tool/result" })
                    put("sources", buildJsonArray {
                        handle.events.filter { it.type == "user/message" }.forEach { event ->
                            val original = source(event) ?: return@forEach
                            add(buildJsonObject {
                                event.seq?.let { put("seq", it) }
                                // Exclude summary, prompt text, paths, catalog payload and arbitrary plugin metadata.
                                listOf("kind", "promptId", "delivery").forEach { key ->
                                    (original[key] as? JsonPrimitive)?.contentOrNull?.let { put(key, it) }
                                }
                            })
                        }
                    })
                }) }
            })
        }
    }
}

package xin.dshaa.nativeapp.chat

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import xin.dshaa.core.model.BlockType
import xin.dshaa.core.model.MessageRole
import xin.dshaa.core.tools.AskAnswer
import xin.dshaa.ui.shellapi.ChatUiCapabilities
import xin.dshaa.ui.shellapi.NativeShellHost
import xin.dshaa.ui.shellapi.chat.ChatSessionState
import xin.dshaa.ui.shellapi.remote.RemotePhase
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Real Android remote/conversation probe against tools/m86h-remote-fixture.py and adb reverse 18786.
 * The AppVM wrapper must assert no active local turns and restore its exact LiveChat/target afterwards.
 * Only two freshly created fixture profiles are changed/removed. No local submit/newChat is called.
 * Reports contain fixture checks and aggregate local JSONL digests, never local names, paths or text.
 */
object RemoteConversationDeviceProbe {
    suspend fun run(
        host: NativeShellHost,
        directory: File,
        writeReport: (String) -> Unit,
    ): JsonObject = withContext(Dispatchers.Main.immediate) {
        val runner = Runner(host, directory, writeReport)
        var cancelled: CancellationException? = null
        try {
            runner.prepare()
            runner.fixtures()
            runner.completed = true
        } catch (error: CancellationException) {
            cancelled = error
            runner.failure("runner_cancelled")
        } catch (error: ProbeFailure) {
            runner.failure(error.id)
        } catch (_: Exception) {
            // Network/IO exceptions can contain device paths or addresses; keep only a stable check id.
            runner.failure("runner_host_or_fixture_io_failure")
        } finally {
            withContext(NonCancellable) { runner.cleanup() }
        }
        val report = runner.report()
        withContext(NonCancellable + Dispatchers.IO) { runner.publish(report) }
        cancelled?.let { throw it }
        report
    }

    private class ProbeFailure(val id: String) : IllegalStateException(id)
    private data class Inventory(val files: Map<String, String>, val digest: String)

    private class Runner(val host: NativeShellHost, val directory: File, val writeReport: (String) -> Unit) {
        private val remote = host.remote
        private val conversation = host.conversation
        private val runId = UUID.randomUUID().toString().replace("-", "")
        private val originalActive = remote.activeProfileId
        private val originalProfiles = remote.profiles.map { it.id }.toSet()
        private val originalLocal = host.chat
        private val originalDraft = originalLocal?.draft
        private val created = mutableListOf<String>()
        private val results = mutableListOf<JsonObject>()
        private val expectedPrompts = mutableMapOf("a" to 0, "b" to 0)
        private val http = OkHttpClient.Builder().connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS).callTimeout(6, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false).build()
        private var reportFile: File? = null
        private var before: Inventory? = null
        private var after: Inventory? = null
        private var a = ""
        private var b = ""
        var completed = false
        private var cleanupOk = true

        fun failure(id: String) {
            if (results.none { it["id"]?.jsonPrimitive?.contentOrNull == id }) {
                results += buildJsonObject { put("id", id); put("passed", false) }
            }
        }
        private suspend fun check(id: String, ok: Boolean) {
            results += buildJsonObject { put("id", id); put("passed", ok) }
            withContext(Dispatchers.IO) { publish(report(running = true)) }
            if (!ok) throw ProbeFailure(id)
        }
        private suspend fun waitFor(id: String, predicate: suspend () -> Boolean) {
            val deadline = System.nanoTime() + 10_000_000_000L
            while (!predicate()) {
                if (System.nanoTime() >= deadline) { check(id, false); return }
                delay(40)
            }
            check(id, true)
        }
        private suspend fun stable(id: String, durationMs: Long, predicate: () -> Boolean) {
            val deadline = System.nanoTime() + durationMs * 1_000_000
            while (System.nanoTime() < deadline) {
                if (!predicate()) { check(id, false); return }
                delay(40)
            }
            check(id, predicate())
        }
        private suspend fun request(backend: String?, path: String, body: JsonObject? = null): JsonObject = withContext(Dispatchers.IO) {
            val base = ROOT + if (backend == null) "" else "/$backend"
            val builder = Request.Builder().url(base + path)
            // Public fixture constants only; never access ShellRemote.token or any user's token storage.
            if (backend != null) builder.header("Authorization", "Bearer m86h-$backend")
            if (body == null) builder.get() else builder.post(body.toString().toRequestBody(JSON_TYPE))
            http.newCall(builder.build()).execute().use { response ->
                if (!response.isSuccessful) throw ProbeFailure("fixture_control_http_failure")
                Json.parseToJsonElement(response.body?.string().orEmpty()).jsonObject
            }
        }
        private suspend fun control(backend: String, vararg values: Pair<String, JsonElement>) =
            request(backend, "/api/debug/control", JsonObject(values.toMap()))
        private suspend fun debug(backend: String) = request(null, "/api/debug/state").getValue(backend).jsonObject
        private fun JsonObject.count(key: String): Int = this["counts"]?.jsonObject?.get(key)?.jsonPrimitive?.intOrNull ?: 0
        private fun JsonObject.number(key: String): Int = this[key]?.jsonPrimitive?.intOrNull ?: 0
        private fun JsonObject.allIdle(): Boolean = this["sessions"]?.jsonObject?.values.orEmpty().all {
            it.jsonObject["running"]?.jsonPrimitive?.booleanOrNull == false
        }
        private fun active(profile: String): Boolean = conversation.remoteProfileId == profile && remote.activeProfileId == profile &&
            remote.chat?.profileId == profile && remote.phase == RemotePhase.Connected && remote.followConnected && remote.chat?.historyLoading == false
        private suspend fun open(profile: String, label: String) {
            conversation.selectTarget(profile)
            waitFor("${label}_connected_list") { remote.activeProfileId == profile && remote.phase == RemotePhase.Connected &&
                !remote.sessionsLoading && remote.sessions.any { it.id == SHARED } }
            conversation.openSession(SHARED)
            waitFor("${label}_shared_follow_ready") { active(profile) && remote.chat?.sessionId == SHARED && conversation.state != null }
        }
        private fun state(): ChatSessionState = conversation.state ?: throw ProbeFailure("remote_projected_state_missing")
        private fun body(state: ChatSessionState): String = state.messages.joinToString("\n") { message ->
            message.content.filter { it.type == BlockType.TEXT }.joinToString("") { it.text }
        }
        private fun toolIds(state: ChatSessionState): Set<String> = state.messages.flatMap { message ->
            message.content.filter { it.type == BlockType.TOOL_CALL && it.name == "pwsh" }.mapNotNull { it.toolCallId }
        }.toSet()
        private suspend fun submit(backend: String, name: String, text: String) {
            check("${name}_target_is_fixture", conversation.remoteProfileId in created && !conversation.isLocal)
            conversation.updateDraft(text)
            check("${name}_accepted_for_submission", conversation.canSubmit && conversation.submit(text))
            expectedPrompts[backend] = expectedPrompts.getValue(backend) + 1
        }
        private suspend fun settled(name: String, marker: String) {
            waitFor("${name}_durable_echo_and_turn_end") {
                val current = conversation.state
                current != null && !remote.sending && !current.streaming && remote.chat?.running == false &&
                    current.messages.any { it.role == MessageRole.USER && it.content.any { part -> part.text == marker.trim() } } &&
                    current.messages.any { it.role == MessageRole.ASSISTANT && it.content.any { part -> part.text.contains("已处理第") } }
            }
        }

        suspend fun prepare() {
            withContext(Dispatchers.IO) {
                require(directory.isDirectory || directory.mkdirs())
                val runDirectory = File(directory, "remote-$runId")
                require(runDirectory.mkdir())
                reportFile = File(runDirectory, "report.json")
            }
            before = inventory(host.sessionsDir)
            conversation.selectTarget(null)
            check("local_state_identity_before", conversation.state === originalLocal && host.chat === originalLocal)
            if (originalLocal != null) conversation.updateDraft("M86H_LOCAL_$runId")
            // Release only this isolated fixture's in-memory work left by a prior interrupted probe.
            request("a", "/api/debug/finish", buildJsonObject {})
            request("b", "/api/debug/finish", buildJsonObject {})
            waitFor("fixture_backends_idle_before_reset") { debug("a").allIdle() && debug("b").allIdle() }
            control("a", "reset" to JsonPrimitive(true))
            control("b", "reset" to JsonPrimitive(true))
            a = remote.saveProfile(null, "M86 Fixture A", "$ROOT/a", "m86h-a").orEmpty()
            if (a.isNotEmpty() && a !in originalProfiles) created += a
            b = remote.saveProfile(null, "M86 Fixture B", "$ROOT/b", "m86h-b").orEmpty()
            if (b.isNotEmpty() && b !in originalProfiles) created += b
            check("two_dedicated_profiles_created", a.isNotEmpty() && b.isNotEmpty() && a != b && created.size == 2)
        }

        suspend fun fixtures() {
            open(a, "a")
            check("remote_capabilities_hide_local_unknown_data", conversation.capabilities == ChatUiCapabilities.Remote &&
                conversation.toolCount == null && state().contextUsage == null && state().contextWindow == 0 &&
                state().usageText == null && state().pendingApproval == null && state().queuedPrompts.isEmpty())
            val aFirst = "M86H_A_FIRST_$runId"
            submit("a", "a_first", aFirst); settled("a_first", aFirst)
            val aState = state()
            val aSession = aState.sessionId
            val aCalls = toolIds(aState)
            check("a_tool_result_pairing", aCalls.size == 1 && aCalls.all { it in aState.toolResults } &&
                remote.chat?.messages?.any { it.tool?.callId == "m86h-tool-1" } == true)
            conversation.updateDraft("M86H_A_DRAFT_$runId")
            val aDraft = state().draft.text
            open(b, "b")
            val bFirst = "M86H_B_FIRST_$runId"
            submit("b", "b_first", bFirst); settled("b_first", bFirst)
            val bState = state()
            val bCalls = toolIds(bState)
            check("same_session_and_tool_ids_are_namespaced", remote.chat?.sessionId == SHARED && aSession != bState.sessionId &&
                aState !== bState && aCalls.isNotEmpty() && bCalls.isNotEmpty() && aCalls.intersect(bCalls).isEmpty() &&
                bCalls.all { it in bState.toolResults } && remote.chat?.messages?.any { it.tool?.callId == "m86h-tool-1" } == true)
            check("a_b_message_contents_stay_separate", body(aState).contains(aFirst) && !body(aState).contains(bFirst) &&
                body(bState).contains(bFirst) && !body(bState).contains(aFirst))
            conversation.updateDraft("M86H_B_DRAFT_$runId")
            val bDraft = state().draft.text
            conversation.selectTarget(null)
            check("local_state_and_draft_after_a_b", conversation.state === originalLocal && host.chat === originalLocal &&
                (originalLocal == null || originalLocal.draft.text == "M86H_LOCAL_$runId"))
            open(a, "a_return")
            check("a_draft_preserved", state() === aState && state().draft.text == aDraft)
            open(b, "b_return")
            check("b_draft_preserved", state() === bState && state().draft.text == bDraft)
            open(a, "a_questions")

            control("a", "scenario" to JsonPrimitive("ask"), "toolDelayMs" to JsonPrimitive(80), "askDelayMs" to JsonPrimitive(80))
            val ask = "M86H_ASK_$runId"
            submit("a", "timed_questions", ask)
            waitFor("timed_multi_questions_remain_answerable_after_pending") {
                val questions = state().pendingQuestions
                !state().streaming && !remote.sending && questions != null && questions.size == 2 && questions.any { it.id == "extras" && it.multiSelect } &&
                    remote.chat?.messages?.any { it.questions.size == 2 && it.tool?.result?.contains("pending") == true && !it.answered } == true
            }
            conversation.answer(listOf(AskAnswer("choice", listOf("B")), AskAnswer("extras", listOf("X", "Y"), "M86H_CUSTOM_$runId")))
            waitFor("multi_select_custom_answer_delivered_once") {
                debug("a")["answers"]?.jsonArray?.size == 1 && state().pendingQuestions.isNullOrEmpty()
            }
            val answers = debug("a")["lastAnswers"]?.jsonObject?.get("answer")?.jsonObject?.get("answers")?.jsonArray.orEmpty()
            check("answer_payload_names_every_question", answers.size == 2 && answers.any {
                val item = it.jsonObject
                item["id"]?.jsonPrimitive?.contentOrNull == "choice" && item["selected"] == buildJsonArray { add("B") }
            } && answers.any {
                val item = it.jsonObject
                item["id"]?.jsonPrimitive?.contentOrNull == "extras" && item["selected"] == buildJsonArray { add("X"); add("Y") } &&
                    item["custom"]?.jsonPrimitive?.contentOrNull == "M86H_CUSTOM_$runId"
            })

            control("a", "scenario" to JsonPrimitive("hold"), "toolDelayMs" to JsonPrimitive(600))
            val hold = "M86H_HOLD_$runId"
            submit("a", "hold", hold)
            waitFor("running_remote_accepts_queue") { state().streaming && !remote.sending && conversation.canSubmit }
            control("a", "scenario" to JsonPrimitive("simple"), "toolDelayMs" to JsonPrimitive(80))
            val queued = "M86H_QUEUED_$runId"
            submit("a", "running_queue", queued)
            waitFor("running_queue_wire_mode") { debug("a").number("promptCalls") == expectedPrompts.getValue("a") && !remote.sending }
            check("wire_uses_only_existing_queue_mode", debug("a")["requests"]?.jsonArray.orEmpty().all {
                it.jsonObject["mode"]?.jsonPrimitive?.contentOrNull == "queue"
            })
            val cancels = debug("a").count("session/cancel")
            conversation.stop()
            waitFor("remote_stop_reaches_cancel_and_queued_turn_finishes") {
                debug("a").count("session/cancel") == cancels + 1 && debug("a").allIdle() &&
                    !state().streaming && !remote.sending && body(state()).contains(queued)
            }

            val opens = debug("a").count("followOpens")
            val promptsBeforeReconnect = debug("a").number("promptCalls")
            request("a", "/api/debug/drop-ws", buildJsonObject {})
            waitFor("websocket_reconnects_without_prompt_replay") {
                debug("a").count("followOpens") > opens && active(a) && debug("a").number("promptCalls") == promptsBeforeReconnect
            }

            control("a", "promptMode" to JsonPrimitive("reject"))
            val rejected = "  M86H_REJECT_$runId  "
            val acceptedBeforeReject = debug("a").number("acceptedPrompts")
            submit("a", "http_reject", rejected)
            waitFor("http_reject_restores_original_draft") { !remote.sending && state().draft.text == rejected &&
                remote.chat?.failedPrompt == rejected && !body(state()).contains(rejected.trim()) }
            conversation.reconnect()
            waitFor("rejected_draft_survives_reconnect") { active(a) && state().draft.text == rejected }
            check("rejected_prompt_not_retried_or_accepted", debug("a").number("promptCalls") == expectedPrompts.getValue("a") &&
                debug("a").number("acceptedPrompts") == acceptedBeforeReject)

            control("a", "promptMode" to JsonPrimitive("accept-drop"), "eventDelayMs" to JsonPrimitive(1000))
            val uncertain = "  M86H_ACCEPT_DROP_$runId  "
            submit("a", "accept_drop", uncertain)
            waitFor("unknown_ack_temporarily_restores_original_draft") { !remote.sending && state().draft.text == uncertain &&
                remote.chat?.failedPrompt == uncertain }
            settled("accept_drop", uncertain)
            waitFor("durable_rpc_echo_clears_only_recovered_draft") { state().draft.text.isEmpty() && remote.chat?.failedPrompt == null }
            conversation.reconnect()
            waitFor("accept_drop_history_reconnect_ready") { active(a) }
            check("accepted_prompt_not_automatically_resent", debug("a").number("promptCalls") == expectedPrompts.getValue("a") &&
                debug("a").number("duplicateRequestIds") == 0)
            control("a", "promptMode" to JsonPrimitive("normal"), "eventDelayMs" to JsonPrimitive(50))

            // Retire an A list/create operation already admitted by the server before displaying B.
            val createBefore = debug("a").count("session/create")
            val listBefore = debug("a").count("session/list")
            control("a", "listDelayMs" to JsonPrimitive(1200), "createDelayMs" to JsonPrimitive(1200), "snapshotDelayMs" to JsonPrimitive(1200))
            remote.refreshSessions()
            conversation.newChat() // The current target is asserted remote; this creates no local session.
            waitFor("slow_a_operations_admitted") { debug("a").count("session/create") > createBefore &&
                debug("a").count("session/list") > listBefore }
            open(b, "b_during_slow_a")
            val bKey = conversation.stateKey
            val bIdentity = state().sessionId
            stable("late_a_http_callbacks_do_not_pollute_b", 1600) {
                active(b) && conversation.stateKey == bKey && state().sessionId == bIdentity &&
                    remote.sessions.all { it.title.startsWith("B") } && !body(state()).contains("M86H_A_FIRST_$runId")
            }
            control("a", "listDelayMs" to JsonPrimitive(0), "createDelayMs" to JsonPrimitive(0), "snapshotDelayMs" to JsonPrimitive(0))

            // An intentional fixture-only bad credential makes connection/create unavailable without
            // accessing any saved user token. The waiting draft must transfer after credential repair.
            conversation.selectTarget(null)
            check("fixture_bad_credential_saved", remote.saveProfile(b, "M86 Fixture B", "$ROOT/b", "m86h-intentionally-invalid") == b)
            conversation.selectTarget(b)
            conversation.ensureDraft()
            val unsent = "M86H_UNSENT_DRAFT_$runId"
            conversation.updateDraft(unsent)
            waitFor("failed_remote_connection_retains_draft") { remote.phase == RemotePhase.Failed && state().draft.text == unsent }
            check("failed_remote_submit_keeps_draft", !conversation.submit(unsent) && state().draft.text == unsent)
            check("fixture_credential_repaired", remote.saveProfile(b, "M86 Fixture B", "$ROOT/b", "m86h-b") == b)
            conversation.reconnect()
            waitFor("repaired_remote_create_transfers_unsent_draft") { active(b) && remote.chat?.sessionId != SHARED &&
                state().draft.text == unsent && remote.chat?.draft == unsent }
            check("all_fixture_prompts_sent_exactly_once", debug("a").number("promptCalls") == expectedPrompts.getValue("a") &&
                debug("b").number("promptCalls") == expectedPrompts.getValue("b") &&
                debug("a").number("duplicateRequestIds") == 0 && debug("b").number("duplicateRequestIds") == 0)
            conversation.selectTarget(null)
            check("local_reference_and_probe_draft_unchanged_at_end", conversation.state === originalLocal && host.chat === originalLocal &&
                (originalLocal == null || originalLocal.draft.text == "M86H_LOCAL_$runId"))
        }

        suspend fun cleanup() {
            try {
                // These requests affect only the dedicated in-memory fixture service.
                runCatching { request("a", "/api/debug/finish", buildJsonObject {}) }
                runCatching { request("b", "/api/debug/finish", buildJsonObject {}) }
                conversation.selectTarget(null)
                created.forEach { id -> if (!remote.removeProfile(id)) cleanupOk = false }
                if (originalActive != null && !remote.selectProfile(originalActive)) cleanupOk = false
                remote.deactivate()
                if (originalLocal != null && host.chat === originalLocal && originalDraft != null) originalLocal.draft = originalDraft
                check("fixture_profiles_removed_and_active_restored", remote.profiles.map { it.id }.toSet() == originalProfiles &&
                    remote.activeProfileId == originalActive && created.none { id -> remote.profiles.any { it.id == id } })
                check("local_reference_and_original_draft_restored", host.chat === originalLocal &&
                    (originalLocal == null || originalLocal.draft == originalDraft))
            } catch (_: Exception) {
                cleanupOk = false; failure("fixture_profile_or_draft_cleanup_failed")
            }
            try {
                after = inventory(host.sessionsDir)
                check("local_jsonl_file_set_and_hashes_unchanged", before != null && before!!.files == after!!.files)
            } catch (_: Exception) {
                cleanupOk = false; failure("local_jsonl_inventory_failed_or_changed")
            }
            http.dispatcher.cancelAll(); http.connectionPool.evictAll(); http.dispatcher.executorService.shutdown()
        }

        fun report(running: Boolean = false): JsonObject = buildJsonObject {
            put("fixture", "M86-H-device-remote-conversation"); put("version", 1); put("running", running)
            put("completed", completed); put("passed", completed && cleanupOk && results.all { it["passed"] == JsonPrimitive(true) })
            put("checks", JsonArray(results)); put("passedCount", results.count { it["passed"] == JsonPrimitive(true) }); put("total", results.size)
            put("createdProfileCount", created.size); put("createdLocalSessions", 0); put("localSubmitCalls", 0)
            put("fixtureExpectedPromptCalls", JsonObject(expectedPrompts.mapValues { JsonPrimitive(it.value) }))
            put("localJsonl", buildJsonObject {
                before?.let { put("beforeCount", it.files.size); put("beforeDigest", it.digest) }
                after?.let { put("afterCount", it.files.size); put("afterDigest", it.digest) }
                put("mapEqual", before != null && after != null && before!!.files == after!!.files)
            })
            put("screenshotsVerified", false); put("realDesktopVerified", false)
        }
        fun publish(report: JsonObject) {
            val text = report.toString()
            reportFile?.writeText(text + "\n", Charsets.UTF_8)
            writeReport(text)
        }
    }

    private suspend fun inventory(directory: File): Inventory = withContext(Dispatchers.IO) {
        val root = directory.canonicalFile
        require(root.isDirectory)
        val files = sortedMapOf<String, String>()
        root.walkTopDown().onEnter { it.canonicalFile.toPath().startsWith(root.toPath()) }.forEach { file ->
            if (file.isFile && file.name.contains("jsonl", ignoreCase = true)) {
                require(file.canonicalFile.toPath().startsWith(root.toPath()))
                val digest = MessageDigest.getInstance("SHA-256")
                file.inputStream().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        digest.update(buffer, 0, count)
                    }
                }
                files[file.relativeTo(root).invariantSeparatorsPath] = digest.digest().hex()
            }
        }
        val combined = MessageDigest.getInstance("SHA-256")
        files.forEach { (name, hash) ->
            combined.update(name.toByteArray(Charsets.UTF_8)); combined.update(0.toByte())
            combined.update(hash.toByteArray(Charsets.US_ASCII)); combined.update('\n'.code.toByte())
        }
        Inventory(files, combined.digest().hex())
    }
    private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it.toInt() and 255) }
    private const val ROOT = "http://127.0.0.1:18786"
    private const val SHARED = "m86h-shared"
    private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()
}

package xin.dshaa.nativeapp.device.tools

import android.app.Application
import android.util.Base64
import android.util.Log
import com.ai.assistance.operit.terminal.TerminalManager
import com.ai.assistance.operit.terminal.data.SessionInitState
import com.ai.assistance.operit.terminal.data.TerminalSessionData
import com.ai.assistance.operit.terminal.provider.type.HiddenExecResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import xin.dshaa.core.tools.DshTool
import xin.dshaa.core.tools.ToolContext
import xin.dshaa.core.tools.ToolResult
import java.util.UUID

/**
 * Operit 终端工具的 Android 原生实现（docs/77）：给 AI 用的**有状态 PTY 会话**。
 *
 * 与 `bash`（每次 `sh -c` 起新进程）的差别：这里的会话由 `:thirdparty:terminal` 的
 * [TerminalManager] 持有，`cd` / 环境变量 / 交互式程序（`cat`、`vim`、`apt` 的密码提示）
 * 都能跨调用延续；AI 可以建会话、跑命令收完整输出、读当前屏幕、写输入、发 Ctrl+C、关会话。
 *
 * 工具名/参数逐字对齐 Operit `SystemToolPromptsInternal.kt:57-173`：
 * `create_terminal_session` / `execute_in_terminal_session` / `execute_hidden_terminal_command` /
 * `input_in_terminal_session` / `close_terminal_session` / `get_terminal_session_screen`。
 *
 * 输出收集：`commandExecutionEvents` 是**没有 replay** 的 SharedFlow，必须先订阅再发命令；
 * 用 `commandId` 过滤，`isCompleted` 的 `outputChunk` 是最终输出（优先于逐行过程量）。
 */
class TerminalTools(private val app: Application) {

    private val manager: TerminalManager by lazy { TerminalManager.getInstance(app) }

    val tools: List<DshTool> = listOf(
        tool("create_terminal_session") { args, _ -> createSession(args) },
        tool("execute_in_terminal_session") { args, _ -> executeInSession(args) },
        tool("execute_hidden_terminal_command") { args, _ -> executeHidden(args) },
        tool("input_in_terminal_session") { args, _ -> inputInSession(args) },
        tool("close_terminal_session") { args, _ -> closeSession(args) },
        tool("get_terminal_session_screen") { args, _ -> sessionScreen(args) },
    )

    private fun tool(name: String, block: suspend (JsonObject, ToolContext) -> ToolResult): DshTool =
        object : DshTool {
            override val name: String = name

            override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult = block(args, ctx)
        }

    /**
     * M68：给 Operit `Tools.System.terminal.*` 兼容层复用**同一套**会话逻辑。
     *
     * 为什么不另写一份：创建/执行/收输出/Pty 超时/Ctrl+C 落定的细节都在上面这些方法里，
     * 复制一份只会让两条链路慢慢分叉（一条修了另一条没修）。这里只做名字到方法的映射，
     * 参数沿用内置工具的 snake_case（`session_id` / `command` / `timeout_ms` / `input` / `control`）。
     */
    suspend fun invokeForHost(action: String, args: JsonObject): ToolResult = when (action) {
        "create" -> createSession(args)
        "exec" -> executeInSession(args)
        "input" -> inputInSession(args)
        "screen" -> sessionScreen(args)
        "close" -> closeSession(args)
        else -> ToolResult.err("未知的终端操作：$action")
    }

    // ---------------- create_terminal_session ----------------

    private suspend fun createSession(args: JsonObject): ToolResult {
        val name = args.str("session_name")?.trim().orEmpty()
        if (name.isEmpty()) return ToolResult.err("session_name is required")

        // 同名会话存在就复用（Operit 语义）；不复用已死掉/被移除的 PTY。
        val existing = manager.terminalState.value.sessions.find { it.title == name }
        if (existing != null) {
            return ToolResult.ok(
                buildString {
                    appendLine("session_id: ${existing.id}")
                    appendLine("session_name: $name")
                    appendLine("is_new_session: false")
                    appendLine("state: ${existing.initState.name}")
                    if (existing.initState != SessionInitState.READY) {
                        appendLine(
                            "note: the session is still initializing. Call execute_in_terminal_session " +
                                "after it reaches state READY.",
                        )
                    }
                }.trimEnd(),
                meta = mapOf("session_id" to existing.id),
            )
        }

        val created = try {
            manager.createNewSession(name)
        } catch (e: Exception) {
            return ToolResult.err(
                "failed to create terminal session \"$name\": ${e.message}. " +
                    "The first session may need to unpack the Ubuntu rootfs (this can take a while); " +
                    "retry create_terminal_session in a minute. ${sessionsHint()}",
            )
        }
        return ToolResult.ok(
            buildString {
                appendLine("session_id: ${created.id}")
                appendLine("session_name: $name")
                appendLine("is_new_session: true")
                appendLine("state: ${created.initState.name}")
            }.trimEnd(),
            meta = mapOf("session_id" to created.id),
        )
    }

    // ---------------- execute_in_terminal_session ----------------

    private suspend fun executeInSession(args: JsonObject): ToolResult {
        val sessionId = args.str("session_id")?.trim().orEmpty()
        if (sessionId.isEmpty()) return ToolResult.err("session_id is required")
        val rawCommand = args.str("command") ?: return ToolResult.err("command is required")
        val timeoutMs = (args.long("timeout_ms") ?: DEFAULT_EXEC_TIMEOUT_MS).coerceIn(1_000L, MAX_TIMEOUT_MS)

        val session = sessionById(sessionId) ?: return sessionMissing(sessionId)
        if (session.initState != SessionInitState.READY) {
            return ToolResult.err(
                "terminal session $sessionId is not ready yet (state=${session.initState.name}). " +
                    "Wait for create_terminal_session to report state READY, then retry.",
            )
        }

        // 交互式程序（cat/vim/密码提示）里没有「命令完成事件」：写进去就返回。
        if (session.isInteractiveMode) {
            if (session.sessionWriter == null) return inputFailed(sessionId)
            manager.sendCommandToSession(sessionId, rawCommand, UUID.randomUUID().toString())
            return ToolResult.ok(
                buildString {
                    appendLine("session_id: $sessionId")
                    appendLine("interactive: true")
                    appendLine(
                        "note: the session is waiting for interactive input (prompt: " +
                            "\"${session.interactivePrompt.take(200)}\"); the command was written as " +
                            "terminal input. Read the screen with get_terminal_session_screen and send " +
                            "more keys with input_in_terminal_session.",
                    )
                }.trimEnd(),
                meta = mapOf("session_id" to sessionId, "interactive" to "true"),
            )
        }

        val prepared = prepareCommand(rawCommand)
        // `clear` 是 TerminalManager 的特例：它只把 clear 写进 PTY，不建命令历史、也不发完成事件，
        // 按普通命令等会一直等到 timeout。这里写完就返回。
        if (prepared.command == "clear") {
            manager.sendCommandToSession(sessionId, "clear", UUID.randomUUID().toString())
            delay(300)
            return ToolResult.ok(
                "session_id: $sessionId\ntimed_out: false\nnote: cleared the terminal screen\noutput:\n(screen cleared)",
                meta = mapOf("session_id" to sessionId, "timed_out" to "false"),
            )
        }
        val commandId = UUID.randomUUID().toString()
        val done = CompletableDeferred<String>()
        val streamed = StringBuilder()
        var timedOut = false
        var completion: String? = null
        var elapsedMs = 0L
        val startedAt = System.currentTimeMillis()

        coroutineScope {
            // SharedFlow 没有 replay：先订阅（UNDISPATCHED 让 collect 立刻注册）再发命令。
            val collector = launch(start = CoroutineStart.UNDISPATCHED) {
                manager.commandExecutionEvents.collect { event ->
                    if (event.commandId != commandId) return@collect
                    if (event.isCompleted) {
                        done.complete(event.outputChunk)
                    } else if (event.outputChunk.isNotEmpty()) {
                        if (streamed.isNotEmpty()) streamed.append('\n')
                        streamed.append(event.outputChunk)
                    }
                }
            }
            try {
                manager.sendCommandToSession(sessionId, prepared.command, commandId)
                completion = withTimeoutOrNull(timeoutMs) { done.await() }
                if (completion == null) {
                    timedOut = true
                    if (!manager.sendInterruptSignalToSession(sessionId)) {
                        Log.w(TAG, "timeout interrupt could not be delivered to session $sessionId")
                    }
                    // 等 PTY 回到提示符（最多 3s），把 ^C 之后的尾部输出也带上。
                    completion = withTimeoutOrNull(CANCEL_SETTLE_MS) { done.await() }
                }
            } finally {
                collector.cancel()
            }
            elapsedMs = System.currentTimeMillis() - startedAt
        }

        val output = (completion?.takeIf { it.isNotEmpty() } ?: streamed.toString()).trimEnd()
        return ToolResult.ok(
            buildString {
                appendLine("session_id: $sessionId")
                appendLine("timed_out: $timedOut")
                appendLine("elapsed_ms: $elapsedMs")
                prepared.note?.let { appendLine("note: $it") }
                if (timedOut) {
                    appendLine(
                        "note: the command did not return to a prompt within ${timeoutMs}ms; " +
                            "sent Ctrl+C to the session." +
                            if (completion == null) " The session did not settle within ${CANCEL_SETTLE_MS}ms." else "",
                    )
                }
                appendLine("output:")
                append(truncate(output).ifEmpty { "(no output)" })
            }.trimEnd(),
            meta = mapOf("session_id" to sessionId, "timed_out" to timedOut.toString()),
        )
    }

    // ---------------- execute_hidden_terminal_command ----------------

    private suspend fun executeHidden(args: JsonObject): ToolResult {
        val command = args.str("command")?.trim().orEmpty()
        if (command.isEmpty()) return ToolResult.err("command is required")
        val executorKey = args.str("executor_key")?.trim()?.ifEmpty { "default" } ?: "default"
        val timeoutMs = (args.long("timeout_ms") ?: DEFAULT_HIDDEN_TIMEOUT_MS).coerceIn(1_000L, MAX_TIMEOUT_MS)

        val result = manager.executeHiddenCommand(command, executorKey, timeoutMs)
        val timedOut = result.state == HiddenExecResult.State.TIMEOUT
        val body = buildString {
            appendLine("executor_key: $executorKey")
            appendLine("timed_out: $timedOut")
            appendLine("exit_code: ${result.exitCode}")
            if (result.state != HiddenExecResult.State.OK) appendLine("state: ${result.state.name}")
            if (result.error.isNotBlank()) appendLine("error: ${result.error.trim()}")
            appendLine("output:")
            append(truncate(result.output.ifBlank { result.rawOutputPreview }).ifEmpty { "(no output)" })
        }.trimEnd()
        return if (timedOut || result.isOk) ToolResult.ok(body) else ToolResult.err(body)
    }

    // ---------------- input_in_terminal_session ----------------

    private suspend fun inputInSession(args: JsonObject): ToolResult {
        val sessionId = args.str("session_id")?.trim().orEmpty()
        if (sessionId.isEmpty()) return ToolResult.err("session_id is required")
        val hasInput = args.containsKey("input")
        val input = args.str("input") ?: ""
        val control = normalizeControl(args.str("control"))
        if (!hasInput && control == null) {
            return ToolResult.err("pass input and/or control (e.g. input=\"y\" + control=\"enter\")")
        }

        val session = sessionById(sessionId) ?: return sessionMissing(sessionId)
        if (session.initState != SessionInitState.READY) {
            return ToolResult.err(
                "terminal session $sessionId is not ready yet (state=${session.initState.name}); input was not sent.",
            )
        }

        if (control == null) {
            if (input.isEmpty()) {
                return ToolResult.ok("wrote 0 chars to session $sessionId (input was empty)")
            }
            if (!manager.sendInputToSession(sessionId, input)) return inputFailed(sessionId)
            return ToolResult.ok("wrote ${input.length} chars to session $sessionId")
        }

        if (isModifierControl(control)) {
            val written = when (control) {
                "ctrl", "control" -> sendCtrlCombination(sessionId, hasInput, input)
                "alt", "meta", "cmd" -> {
                    requireInput(control, hasInput, input)?.let { return it }
                    writeOrFail(sessionId, "\u001b$input")
                }
                "shift" -> {
                    requireInput(control, hasInput, input)?.let { return it }
                    writeOrFail(sessionId, input.uppercase())
                }
                else -> ToolResult.err("unsupported modifier control: $control")
            }
            return written
        }

        val sequence = controlToSequence(control)
            ?: return ToolResult.err(
                "unsupported control: $control. Supported: enter/tab/esc/up/down/left/right/home/end/" +
                    "pageup/pagedown/backspace/delete, or ctrl + input=c for Ctrl+C.",
            )
        var wrote = 0
        if (hasInput && input.isNotEmpty()) {
            if (!manager.sendInputToSession(sessionId, input)) return inputFailed(sessionId)
            wrote += input.length
        }
        if (!manager.sendInputToSession(sessionId, sequence)) return inputFailed(sessionId)
        wrote += sequence.length
        return ToolResult.ok("wrote $wrote chars to session $sessionId (control=$control)")
    }

    private suspend fun sendCtrlCombination(sessionId: String, hasInput: Boolean, input: String): ToolResult {
        requireInput("ctrl", hasInput, input)?.let { return it }
        if (input.length != 1) {
            return ToolResult.err("ctrl requires a single character in input (e.g. input=\"c\" for Ctrl+C)")
        }
        val char = input[0]
        if (char.equals('c', ignoreCase = true)) {
            return if (manager.sendInterruptSignalToSession(sessionId)) {
                ToolResult.ok("sent Ctrl+C to session $sessionId")
            } else {
                inputFailed(sessionId)
            }
        }
        val code = ctrlCode(char) ?: return ToolResult.err(
            "unsupported ctrl combination: Ctrl+$char (supported: A..Z, @ [ \\ ] ^ _ and ?)",
        )
        return writeOrFail(sessionId, code.toChar().toString())
    }

    private fun requireInput(control: String, hasInput: Boolean, input: String): ToolResult? =
        if (!hasInput) ToolResult.err("control=$control requires input (e.g. input=\"c\" for Ctrl+C)") else null

    private fun writeOrFail(sessionId: String, payload: String): ToolResult =
        if (manager.sendInputToSession(sessionId, payload)) {
            ToolResult.ok("wrote ${payload.length} chars to session $sessionId")
        } else {
            inputFailed(sessionId)
        }

    private fun inputFailed(sessionId: String): ToolResult = ToolResult.err(
        "could not write to terminal session $sessionId (the PTY writer is gone; the session may have exited). " +
            sessionsHint(),
    )

    // ---------------- close_terminal_session ----------------

    private fun closeSession(args: JsonObject): ToolResult {
        val sessionId = args.str("session_id")?.trim().orEmpty()
        if (sessionId.isEmpty()) return ToolResult.err("session_id is required")
        val session = sessionById(sessionId) ?: return sessionMissing(sessionId)
        manager.closeSession(sessionId)
        return ToolResult.ok("closed terminal session $sessionId (${session.title})")
    }

    // ---------------- get_terminal_session_screen ----------------

    private fun sessionScreen(args: JsonObject): ToolResult {
        val sessionId = args.str("session_id")?.trim().orEmpty()
        if (sessionId.isEmpty()) return ToolResult.err("session_id is required")
        val session = sessionById(sessionId) ?: return sessionMissing(sessionId)

        val screen = session.ansiParser.getScreenContent()
        val rows = screen.size
        val cols = if (rows > 0) screen[0].size else 0
        val lines = screen.map { row ->
            buildString { row.forEach { append(it.char) } }.trimEnd()
        }.toMutableList()
        while (lines.isNotEmpty() && lines.last().isEmpty()) lines.removeAt(lines.lastIndex)
        val content = lines.joinToString("\n")
        return ToolResult.ok(
            buildString {
                appendLine("session_id: $sessionId")
                appendLine("state: ${session.initState.name}")
                appendLine("rows: $rows")
                appendLine("cols: $cols")
                appendLine("content:")
                append(content.ifEmpty { "(screen is empty)" })
            }.trimEnd(),
            meta = mapOf("session_id" to sessionId, "rows" to rows.toString(), "cols" to cols.toString()),
        )
    }

    // ---------------- helpers ----------------

    private fun sessionById(sessionId: String): TerminalSessionData? =
        manager.terminalState.value.sessions.find { it.id == sessionId }

    private fun sessionMissing(sessionId: String): ToolResult = ToolResult.err(
        "terminal session $sessionId does not exist (it may have exited or been closed). " +
            "Call create_terminal_session with a session_name to get a new one. ${sessionsHint()}",
    )

    private fun sessionsHint(): String {
        val sessions = manager.terminalState.value.sessions
        if (sessions.isEmpty()) return "No terminal sessions are open."
        return "Open sessions: " + sessions.take(10).joinToString("; ") {
            "${it.title}=${it.id} (${it.initState.name})"
        }
    }

    /**
     * 多行命令包成一行 `bash -c`：PTY 一次喂多行时，bash 在第一条命令的提示符处就会触发
     * 「命令完成」，后续行的输出会丢（`currentExecutingCommand` 已经清空）。base64 保证脚本
     * 内容不经 shell 引号/转义，执行语义（cwd、环境变量）不变。
     */
    private fun prepareCommand(raw: String): PreparedCommand {
        val trimmed = raw.trim()
        if (!trimmed.contains('\n')) return PreparedCommand(trimmed, null)
        val encoded = Base64.encodeToString(raw.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        return PreparedCommand(
            "bash -c \"$(printf %s '$encoded' | base64 -d)\"",
            "multi-line command was executed through bash -c so the PTY returns to a single prompt",
        )
    }

    private fun truncate(text: String): String =
        if (text.length <= MAX_OUTPUT_CHARS) text
        else "…(output truncated to tail)\n" + text.takeLast(MAX_OUTPUT_CHARS)

    private fun normalizeControl(raw: String?): String? {
        val value = raw?.trim()?.lowercase()
        if (value.isNullOrEmpty()) return null
        return when (value) {
            "return" -> "enter"
            "escape" -> "esc"
            "arrowup" -> "up"
            "arrowdown" -> "down"
            "arrowleft" -> "left"
            "arrowright" -> "right"
            "pgup", "page_up" -> "pageup"
            "pgdn", "page_down" -> "pagedown"
            "del" -> "delete"
            else -> value
        }
    }

    private fun isModifierControl(control: String): Boolean =
        control in setOf("ctrl", "control", "alt", "meta", "cmd", "shift")

    private fun ctrlCode(char: Char): Int? = when (val upper = char.uppercaseChar()) {
        in 'A'..'Z' -> upper.code - 'A'.code + 1
        '@' -> 0
        '[' -> 27
        '\\' -> 28
        ']' -> 29
        '^' -> 30
        '_' -> 31
        '?' -> 127
        else -> null
    }

    private fun controlToSequence(control: String): String? = when (control) {
        "enter" -> "\r"
        "tab" -> "\t"
        "esc" -> "\u001b"
        "up" -> "\u001b[A"
        "down" -> "\u001b[B"
        "left" -> "\u001b[D"
        "right" -> "\u001b[C"
        "home" -> "\u001b[H"
        "end" -> "\u001b[F"
        "pageup" -> "\u001b[5~"
        "pagedown" -> "\u001b[6~"
        "backspace" -> "\u007f"
        "delete" -> "\u001b[3~"
        else -> null
    }

    private data class PreparedCommand(val command: String, val note: String?)

    private fun JsonObject.str(key: String): String? =
        runCatching { this[key]?.jsonPrimitive?.contentOrNull }.getOrNull()

    /** `timeout_ms` 允许数字或数字字符串（模型常把整数写成 "5000"）。 */
    private fun JsonObject.long(key: String): Long? = runCatching {
        val primitive = this[key]?.jsonPrimitive ?: return null
        primitive.longOrNull ?: primitive.contentOrNull?.trim()?.toLongOrNull()
    }.getOrNull()

    private companion object {
        const val TAG = "DshTerminalTools"
        const val DEFAULT_EXEC_TIMEOUT_MS = 1_800_000L
        const val DEFAULT_HIDDEN_TIMEOUT_MS = 120_000L
        const val MAX_TIMEOUT_MS = 86_400_000L
        const val CANCEL_SETTLE_MS = 3_000L
        const val MAX_OUTPUT_CHARS = 30_000
    }
}

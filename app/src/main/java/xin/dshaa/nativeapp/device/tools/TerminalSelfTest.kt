package xin.dshaa.nativeapp.device.tools

import android.app.Application
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import xin.dshaa.core.tools.ToolContext
import xin.dshaa.core.tools.ToolRegistry
import xin.dshaa.core.tools.ToolResult
import xin.dshaa.nativeapp.device.DeviceShell
import java.io.File

/**
 * 终端工具验收脚本（docs/77）：**不经过模型**，直接按 `registry` 调 6 个终端工具，
 * 把每一步的完整结果写进 `filesDir/terminal-selftest.log`，并在 logcat 打 `DshTerminal`。
 *
 * 触发：`am start -n xin.dshaa.nativeapp/.MainActivity --ez dsh_terminal_selftest true`（仅 debug 包）。
 * 加 `--ez dsh_terminal_selftest_keep true` 则不关会话，并直接落到终端页截图（docs/shots/77-*）。
 *
 * 序列：建会话 → echo → 环境变量/`cd` 跨命令延续 → 多行脚本 → 读屏幕 → `sleep 60` 超时
 * （工具自己发 Ctrl+C）→ 并发 `sleep 60` + `input_in_terminal_session ctrl+c` → 隐藏执行器 →
 * `cat` 交互输入 + Ctrl+D → 关会话（再关一次应报错）。
 */
object TerminalSelfTest {

    suspend fun run(
        app: Application,
        registry: ToolRegistry,
        ctx: ToolContext,
        keepSession: Boolean = false,
    ) = coroutineScope {
        val outFile = File(app.filesDir, "terminal-selftest.log")
        val transcript = StringBuilder()
        val startedAt = System.currentTimeMillis()
        var failures = 0

        fun log(line: String) {
            transcript.append(line).append('\n')
            Log.i(TAG, line.take(LOG_CHUNK))
        }

        fun verify(label: String, ok: Boolean) {
            if (!ok) failures++
            log("CHECK $label: ${if (ok) "PASS" else "FAIL"}")
        }

        suspend fun step(name: String, args: String): ToolResult {
            log("=== [+${System.currentTimeMillis() - startedAt} ms] $name $args")
            val result = try {
                registry.execute(name, Json.parseToJsonElement(args).jsonObject, ctx)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ToolResult.err("call crashed: ${e.message}")
            }
            result.text.chunked(LOG_CHUNK).forEach { log(it) }
            log("--- isError=${result.isError}")
            return result
        }

        try {
            log("terminal self-test start: ${java.util.Date()}")

            // 0. 6 个工具都注册进了 registry（schema 只有注册了才进请求）
            val expectedTools = listOf(
                "create_terminal_session",
                "execute_in_terminal_session",
                "execute_hidden_terminal_command",
                "input_in_terminal_session",
                "close_terminal_session",
                "get_terminal_session_screen",
            )
            val registered = registry.names
            log("registered tools (${registered.size}): ${registered.joinToString()}")
            verify("all 6 terminal tools registered", expectedTools.all { it in registered })

            // 1. 建会话
            val created = step("create_terminal_session", """{"session_name":"ai-selftest"}""")
            val sessionId = Regex("session_id:\\s*(\\S+)").find(created.text)?.groupValues?.get(1).orEmpty()
            verify("create_terminal_session returns session_id", !created.isError && sessionId.isNotEmpty())
            if (sessionId.isEmpty()) {
                log("ABORT: no session_id, remaining steps skipped")
                return@coroutineScope
            }

            // 2. clear 是 TerminalManager 的特例（不建命令历史），工具必须立刻返回而不是等超时
            val cleared = step(
                "execute_in_terminal_session",
                """{"session_id":"$sessionId","command":"clear"}""",
            )
            verify(
                "clear returns immediately",
                !cleared.isError && cleared.text.contains("cleared") && cleared.text.contains("timed_out: false"),
            )

            // 3. 基本命令 + 输出
            val echo = step(
                "execute_in_terminal_session",
                """{"session_id":"$sessionId","command":"echo hello-dsh-terminal"}""",
            )
            verify("echo output", !echo.isError && echo.text.contains("hello-dsh-terminal"))

            // 3. 会话状态：环境变量 + cd 跨调用延续
            val env = step(
                "execute_in_terminal_session",
                """{"session_id":"$sessionId","command":"export DSH_MARK=42; echo mark=${'$'}DSH_MARK; pwd"}""",
            )
            verify("export in session", !env.isError && env.text.contains("mark=42"))

            val cd1 = step(
                "execute_in_terminal_session",
                """{"session_id":"$sessionId","command":"cd /tmp && pwd"}""",
            )
            verify("cd /tmp", !cd1.isError && cd1.text.contains("/tmp"))
            val cd2 = step(
                "execute_in_terminal_session",
                """{"session_id":"$sessionId","command":"pwd"}""",
            )
            verify("cwd persists on next call", !cd2.isError && cd2.text.contains("/tmp"))
            val env2 = step(
                "execute_in_terminal_session",
                """{"session_id":"$sessionId","command":"echo env=${'$'}DSH_MARK"}""",
            )
            verify("env persists on next call", !env2.isError && env2.text.contains("env=42"))

            // 4. 多行脚本（工具包成一行 bash -c）
            val multi = step(
                "execute_in_terminal_session",
                """{"session_id":"$sessionId","command":"for i in 1 2 3; do\n  echo line-${'$'}i\ndone"}""",
            )
            verify(
                "multi-line script output",
                !multi.isError && multi.text.contains("line-1") && multi.text.contains("line-3"),
            )

            // 5. 读可见屏幕
            val screen = step("get_terminal_session_screen", """{"session_id":"$sessionId"}""")
            verify("screen has session output", !screen.isError && screen.text.contains("hello-dsh-terminal"))

            // 6. 超时：工具自己发 Ctrl+C
            val timeoutStarted = System.currentTimeMillis()
            val timeout = step(
                "execute_in_terminal_session",
                """{"session_id":"$sessionId","command":"sleep 60","timeout_ms":3000}""",
            )
            val timeoutElapsed = System.currentTimeMillis() - timeoutStarted
            verify("timeout reports timed_out=true", !timeout.isError && timeout.text.contains("timed_out: true"))
            verify("timeout returns in <15s (Ctrl+C settled)", timeoutElapsed < 15_000)

            // 7. 显式 Ctrl+C 中断运行中的命令
            val longRun = async {
                step(
                    "execute_in_terminal_session",
                    """{"session_id":"$sessionId","command":"sleep 60","timeout_ms":120000}""",
                )
            }
            delay(1_500)
            val interrupt = step(
                "input_in_terminal_session",
                """{"session_id":"$sessionId","control":"ctrl","input":"c"}""",
            )
            verify("ctrl+c accepted", !interrupt.isError && interrupt.text.contains("Ctrl+C"))
            val longResult = longRun.await()
            verify(
                "ctrl+c interrupts sleep (no timeout)",
                !longResult.isError && longResult.text.contains("timed_out: false"),
            )

            // 8. 隐藏执行器（非 PTY）
            val hidden = step(
                "execute_hidden_terminal_command",
                """{"command":"echo hidden-ok; pwd","executor_key":"selftest"}""",
            )
            verify("hidden executor output", !hidden.isError && hidden.text.contains("hidden-ok"))

            // 8b. M76 系统身份：identity=shell 走 Shizuku（uid 2000），identity=root 走 su。
            // 没有 Shizuku/root 时必须返回明确的「不可用」错误，而不是静默退回 app 身份。
            val shellIdentity = step(
                "execute_hidden_terminal_command",
                """{"command":"id","identity":"shell"}""",
            )
            if (DeviceShell.hasShizuku()) {
                verify(
                    "identity=shell returns uid=2000 via Shizuku",
                    !shellIdentity.isError && shellIdentity.text.contains("uid=2000(shell)"),
                )
            } else {
                verify(
                    "identity=shell without Shizuku gives a clear error",
                    shellIdentity.isError && shellIdentity.text.contains("identity=shell is unavailable"),
                )
            }
            val rootIdentity = step(
                "execute_hidden_terminal_command",
                """{"command":"id","identity":"root"}""",
            )
            if (DeviceShell.hasRoot()) {
                verify(
                    "identity=root returns uid=0",
                    !rootIdentity.isError && rootIdentity.text.contains("uid=0(root)"),
                )
            } else {
                verify(
                    "identity=root without su gives a clear error",
                    rootIdentity.isError && rootIdentity.text.contains("identity=root is unavailable"),
                )
            }

            // 9. 交互式程序：cat + input/enter + ctrl+d
            val catRun = async {
                step(
                    "execute_in_terminal_session",
                    """{"session_id":"$sessionId","command":"cat","timeout_ms":60000}""",
                )
            }
            delay(1_500)
            step("input_in_terminal_session", """{"session_id":"$sessionId","input":"hello-cat"}""")
            step("input_in_terminal_session", """{"session_id":"$sessionId","control":"enter"}""")
            delay(500)
            val eof = step(
                "input_in_terminal_session",
                """{"session_id":"$sessionId","control":"ctrl","input":"d"}""",
            )
            verify("ctrl+d accepted", !eof.isError)
            val catResult = catRun.await()
            verify("interactive cat got input", !catResult.isError && catResult.text.contains("hello-cat"))

            // 10. 关会话；再关一次应当报错（截图模式下保留会话，方便看终端页）
            if (keepSession) {
                log("=== keepSession=true: session $sessionId left open for the screenshot")
            } else {
                val closed = step("close_terminal_session", """{"session_id":"$sessionId"}""")
                verify("close_terminal_session", !closed.isError)
                val closedAgain = step("close_terminal_session", """{"session_id":"$sessionId"}""")
                verify("closing a missing session errors", closedAgain.isError)
            }
        } finally {
            log("=== done in ${System.currentTimeMillis() - startedAt} ms, failures=$failures")
            log("RESULT: ${if (failures == 0) "PASS" else "FAIL"} ($failures check(s) failed)")
            withContext(Dispatchers.IO) {
                runCatching { outFile.writeText(transcript.toString()) }
                    .onFailure { Log.e(TAG, "write transcript failed", it) }
            }
            Log.i(TAG, "transcript written to ${outFile.absolutePath} (failures=$failures)")
        }
    }

    private const val TAG = "DshTerminal"
    private const val LOG_CHUNK = 3_000
}

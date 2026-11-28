package xin.dshaa.core.tools

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import xin.dshaa.core.tools.shell.BashTool
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * `bash identity=shell/root`（docs/198 §二）的契约：
 * 整条命令交给 [ShellHost.runElevated]，回报**实际**身份、保留超时/退出码语义，
 * 身份不可用时给可执行提示而不是裸 `Permission denied`。
 */
class BashToolIdentityTest {

    private fun workspace(): File = Files.createTempDirectory("bash-identity-test").toFile()

    private fun args(command: String, build: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit = {}): JsonObject =
        buildJsonObject {
            put("description", "identity probe")
            put("command", command)
            build()
        }

    private class FakeShellHost(
        private val supported: Boolean = true,
        private val reply: (ElevatedRequest) -> ElevatedResult,
    ) : ShellHost {
        var last: ElevatedRequest? = null

        override fun bashPath(): String? = null
        override fun curlPath(): String? = null
        override fun environment(): Map<String, String> = emptyMap()
        override fun supportsElevatedIdentity(): Boolean = supported

        override suspend fun runElevated(request: ElevatedRequest): ElevatedResult {
            last = request
            return reply(request)
        }
    }

    @Test
    fun shellIdentityRunsWholeCommandThroughHostAndReportsActualIdentity() = runBlocking {
        val ws = workspace()
        val host = FakeShellHost { request ->
            assertEquals("pm list packages", request.command)
            assertEquals(ws.canonicalFile, request.cwd.canonicalFile)
            assertEquals("/system/bin:/system/xbin:/product/bin", request.env["PATH"])
            assertEquals("42", request.env["DSH_PROBE"], "模型给的 env 必须透传到提权命令")
            ElevatedResult(
                ok = true,
                identity = "shell (uid 2000 via Shizuku)",
                exitCode = 0,
                timedOut = false,
                output = "package:com.android.shell\n",
                truncated = false,
            )
        }
        val result = BashTool().execute(
            args("pm list packages") {
                put("identity", "shell")
                put("env", buildJsonObject { put("DSH_PROBE", "42") })
            },
            ToolContext(ws, shell = host),
        )
        assertNotNull(host.last, "identity=shell 必须走提权通道而不是本地 shell")
        assertTrue(result.text.startsWith("identity: shell (uid 2000 via Shizuku)"), result.text)
        assertTrue(result.text.contains("package:com.android.shell"), result.text)
        assertTrue(!result.isError)
    }

    @Test
    fun rootIdentityFailureReturnsHostInstructions() = runBlocking {
        val host = FakeShellHost {
            ElevatedResult(
                ok = false,
                identity = "root",
                exitCode = -1,
                timedOut = false,
                output = "",
                truncated = false,
                error = "identity=root is unavailable: no su/root on this device.",
            )
        }
        val result = BashTool().execute(args("id") { put("identity", "root") }, ToolContext(workspace(), shell = host))
        assertTrue(result.isError)
        assertEquals("identity=root is unavailable: no su/root on this device.", result.text)
    }

    @Test
    fun nonzeroExitIsReportedWithIdentityHeader() = runBlocking {
        val host = FakeShellHost {
            ElevatedResult(
                ok = false,
                identity = "shell (uid 2000 via Shizuku)",
                exitCode = 255,
                timedOut = false,
                output = "Error: java.lang.SecurityException\n",
                truncated = false,
            )
        }
        val result = BashTool().execute(
            args("settings get secure x") { put("identity", "shell") },
            ToolContext(workspace(), shell = host),
        )
        assertTrue(result.text.contains("identity: shell (uid 2000 via Shizuku)"), result.text)
        assertTrue(result.text.trimEnd().endsWith("[exit code: 255]"), result.text)
    }

    @Test
    fun timeoutReturnsPartialOutputAndErrorFlag() = runBlocking {
        val host = FakeShellHost {
            ElevatedResult(
                ok = false,
                identity = "shell (uid 2000 via Shizuku)",
                exitCode = -1,
                timedOut = true,
                output = "partial line\n",
                truncated = false,
            )
        }
        val result = BashTool().execute(
            args("dumpsys") { put("identity", "shell"); put("timeoutMs", 1_500) },
            ToolContext(workspace(), shell = host),
        )
        assertTrue(result.isError)
        assertTrue(result.text.contains("timed out after 1500ms"), result.text)
        assertTrue(result.text.contains("partial line"), result.text)
    }

    @Test
    fun backgroundAndInvalidIdentityAreRejectedBeforeExecution() = runBlocking {
        val host = FakeShellHost { error("must not run") }
        val ctx = ToolContext(workspace(), shell = host)
        val background = BashTool().execute(
            args("id") {
                put("identity", "root")
                put("run_in_background", kotlinx.serialization.json.JsonPrimitive(true))
            },
            ctx,
        )
        assertTrue(background.text.contains("does not support run_in_background"), background.text)

        val invalid = BashTool().execute(args("id") { put("identity", "daemon") }, ctx)
        assertTrue(invalid.text.contains("identity must be one of"), invalid.text)
        assertTrue(host.last == null, "非法/后台请求不能进提权通道")
    }

    @Test
    fun hostWithoutElevatedSupportGivesClearError() = runBlocking {
        val host = FakeShellHost(supported = false) { error("must not run") }
        val result = BashTool().execute(args("id") { put("identity", "shell") }, ToolContext(workspace(), shell = host))
        assertTrue(result.isError)
        assertTrue(result.text.contains("unavailable in this deployment"), result.text)
    }
}

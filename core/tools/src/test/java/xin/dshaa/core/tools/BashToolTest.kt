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
import kotlin.test.assertTrue

/**
 * `bash` 工具的执行端契约（2026-11-20 能力补全）：托管 `DSH_*` 变量、`env`、`stdin`、
 * `<workspace>/.dsh/bin` 的 PATH 前缀、截断时完整输出落盘、后台不接受 stdin。
 *
 * 执行端在两套 shell 下都跑（Windows 用 cmd，其他用 sh），所以每条命令都按 [windows] 分支。
 */
class BashToolTest {

    private val windows: Boolean
        get() = System.getProperty("os.name").orEmpty().lowercase().contains("win")

    private fun workspace(): File = Files.createTempDirectory("bash-tool-test").toFile()

    /** `description` 是模型侧必填项，这里跟着一起给，保证测试走的是真实调用形态。 */
    private fun args(command: String, build: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit = {}): JsonObject =
        buildJsonObject {
            put("description", "probe command")
            put("command", command)
            build()
        }

    private fun lines(text: String): List<String> = text.trimEnd().lines().map { it.trim() }

    @Test
    fun managedVariablesAndEnvReachTheCommand() = runBlocking {
        val ws = workspace()
        val ctx = ToolContext(ws, jobOwner = { "sess-1" })
        val command = if (windows) {
            "echo %DSH_PROBE% & echo %DSH_WORKSPACE% & echo %DSH_SESSION% & echo %DSH_TIMEOUT_MS%"
        } else {
            "echo \"\$DSH_PROBE\"; echo \"\$DSH_WORKSPACE\"; echo \"\$DSH_SESSION\"; echo \"\$DSH_TIMEOUT_MS\""
        }
        val result = BashTool().execute(
            args(command) {
                put("env", buildJsonObject { put("DSH_PROBE", "hello-env") })
                put("timeoutMs", 30_000)
            },
            ctx,
        )
        val out = lines(result.text)
        assertEquals("hello-env", out[0], "模型给的 env 必须进子进程：${result.text}")
        assertTrue(out[1].equals(ws.absolutePath, ignoreCase = true), "DSH_WORKSPACE 应是工作区：${result.text}")
        assertEquals("sess-1", out[2], "DSH_SESSION 应是当前会话 id：${result.text}")
        assertEquals("30000", out[3], "DSH_TIMEOUT_MS 应是本次超时：${result.text}")
    }

    @Test
    fun stdinIsFedAndClosedSoCommandsDoNotHang() = runBlocking {
        val result = BashTool().execute(
            args("sort") { put("stdin", "beta\nalpha\n") },
            ToolContext(workspace()),
        )
        assertEquals(listOf("alpha", "beta"), lines(result.text), "stdin 应喂给进程并随后关闭管道")
    }

    @Test
    fun workspaceBinDirectoryIsPrependedToPath() = runBlocking {
        val ws = workspace()
        val bin = File(ws, ".dsh/bin").apply { mkdirs() }
        val command = if (windows) "echo %PATH%" else "echo \"\$PATH\""
        val result = BashTool().execute(args(command), ToolContext(ws))
        assertTrue(
            lines(result.text).first().startsWith(bin.absolutePath),
            "PATH 应以 <workspace>/.dsh/bin 开头：${result.text.take(160)}",
        )
    }

    @Test
    fun longOutputIsTruncatedToTailAndFullOutputIsSaved() = runBlocking {
        val ws = workspace()
        File(ws, "big.txt").writeText("A".repeat(40_000))
        val command = if (windows) "type big.txt" else "cat big.txt"
        val result = BashTool().execute(args(command), ToolContext(ws))
        val firstLine = result.text.lineSequence().first()
        assertTrue(
            firstLine.startsWith("…(output truncated to tail; full output: "),
            "截断声明必须带落盘路径：$firstLine",
        )
        val path = Regex("full output: (.+?)\\)").find(firstLine)?.groupValues?.get(1)?.trim()
            ?: error("没有解析到落盘路径：$firstLine")
        val log = File(path)
        assertTrue(log.isFile, "完整输出应真的落盘：$path")
        assertEquals(40_000, log.readText().length, "落盘文件应是完整输出")
        assertTrue(result.text.length < 40_000, "返回给模型的文本应被截到尾部 30k")
    }

    @Test
    fun timeoutStillKillsTheCommand() = runBlocking {
        val command = if (windows) "ping -n 30 127.0.0.1 >nul" else "sleep 30"
        val result = BashTool().execute(
            args(command) { put("timeoutMs", 1_000) },
            ToolContext(workspace()),
        )
        assertEquals("command timed out after 1000ms", result.text)
    }

    @Test
    fun backgroundRejectsStdin() = runBlocking {
        val result = BashTool().execute(
            args("echo hi") {
                put("stdin", "nope\n")
                put("run_in_background", kotlinx.serialization.json.JsonPrimitive(true))
            },
            ToolContext(workspace()),
        )
        assertTrue(
            result.text.startsWith("stdin is not supported with run_in_background"),
            "后台任务不能带 stdin：${result.text}",
        )
    }
}

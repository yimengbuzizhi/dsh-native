package xin.dshaa.core.parity

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ParityCompareTest {

    private fun tempDir(): File = Files.createTempDirectory("parity-test").toFile()

    private fun writeRun(
        dir: File,
        uuid: String,
        ts: Long,
        result: String = "a.txt",
        extraError: Boolean = false,
        baseUrl: String = "https://api.deepseek.com/anthropic",
    ) {
        dir.mkdirs()
        RunRecorder(dir).use { rec ->
            rec.record("run.meta") {
                put("scenario", "smoke")
                put("model", "deepseek-flash")
                put("prompt", "看看目录里有什么")
                put("baseUrl", baseUrl)
            }
            rec.record("llm.request") {
                put("n", 1)
                put(
                    "body",
                    "{\"model\":\"deepseek-flash\",\"system\":\"cwd=/data/user/0/xin.dshaa.nativeapp/files/workspace\"}",
                )
            }
            rec.record("tool.call") {
                put("n", 1)
                put("name", "bash")
                put("args", "{\"command\":\"ls -1\"}")
                put("result", result)
                put("isError", false)
            }
            rec.record("turn.end") {
                put("createdAt", ts)
                put("sessionId", uuid)
                put("messages", buildJsonObject { put("count", 3) })
            }
            if (extraError) rec.record("error") { put("message", "boom") }
        }
    }

    @Test
    fun `时间戳 UUID 工作区路径归一后 0 diff`() {
        val a = tempDir()
        val b = tempDir()
        writeRun(a, uuid = "11111111-2222-3333-4444-555555555555", ts = 1_750_000_000_000)
        writeRun(b, uuid = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee", ts = 1_760_000_000_123)
        val report = ParityCompare.compare(a, b, workdir = "/data/user/0/xin.dshaa.nativeapp/files/workspace")
        assertEquals("PASS", report.status, report.text())
        assertEquals("smoke", report.scenario)
        assertEquals(4, report.linesA)
    }

    @Test
    fun `真实差异只报第一个并给出路径`() {
        val a = tempDir()
        val b = tempDir()
        writeRun(a, uuid = "11111111-2222-3333-4444-555555555555", ts = 1_750_000_000_000)
        writeRun(b, uuid = "11111111-2222-3333-4444-555555555555", ts = 1_750_000_000_000, result = "b.txt")
        val report = ParityCompare.compare(a, b)
        assertEquals("FAIL", report.status)
        val diff = assertNotNull(report.firstDiff)
        assertEquals("tool.call", diff.stage)
        assertTrue(diff.path.endsWith(".result"), "差异路径应定位到 result，实际 ${diff.path}")
        assertEquals("a.txt", diff.expected)
        assertEquals("b.txt", diff.actual)
    }

    @Test
    fun `回放器端口与真实端点不同都不算差异`() {
        val a = tempDir()
        val b = tempDir()
        writeRun(a, uuid = "11111111-2222-3333-4444-555555555555", ts = 1, baseUrl = "http://127.0.0.1:18080")
        writeRun(b, uuid = "11111111-2222-3333-4444-555555555555", ts = 1, baseUrl = "https://api.deepseek.com/anthropic")
        val report = ParityCompare.compare(a, b)
        assertEquals("PASS", report.status, report.text())
    }

    @Test
    fun `行数不一致也算 FAIL`() {
        val a = tempDir()
        val b = tempDir()
        writeRun(a, uuid = "11111111-2222-3333-4444-555555555555", ts = 1)
        writeRun(b, uuid = "11111111-2222-3333-4444-555555555555", ts = 1, extraError = true)
        val report = ParityCompare.compare(a, b)
        assertEquals("FAIL", report.status)
        assertEquals(4, report.linesA)
        assertEquals(5, report.linesB)
    }
}

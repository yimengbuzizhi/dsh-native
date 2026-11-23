package xin.dshaa.nativeapp.node

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * docs/187 §九 第 3 项：`node-audit.jsonl` 的字段与 start/done 两行配对（JVM）。
 */
class NodeAuditLogTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun writesStartAndDoneLinesWithAllRequiredFields() {
        val dir = temp.newFolder("files")
        val audit = NodeAuditLog(dir)
        val sha = NodeAuditLog.sha256("""{"note":"hello"}""")
        audit.start("t1", "hello_probe", sha, "auto", 4242)
        audit.done("t1", "hello_probe", sha, "auto", ok = true, error = null, ms = 37, pid = 4242)

        val lines = java.io.File(dir, "node-audit.jsonl").readLines()
        assertEquals(2, lines.size)
        val start = Json.parseToJsonElement(lines[0]).jsonObject
        val done = Json.parseToJsonElement(lines[1]).jsonObject

        assertEquals("start", (start["phase"] as JsonPrimitive).content)
        assertEquals("node", (start["host"] as JsonPrimitive).content)
        assertEquals("t1", (start["callId"] as JsonPrimitive).content)
        assertEquals("hello_probe", (start["tool"] as JsonPrimitive).content)
        assertEquals(sha, (start["argsSha256"] as JsonPrimitive).content)
        assertEquals("auto", (start["approval"] as JsonPrimitive).content)
        assertEquals(4242L, (start["pid"] as JsonPrimitive).long)

        assertEquals("done", (done["phase"] as JsonPrimitive).content)
        assertTrue((done["ok"] as JsonPrimitive).content.toBoolean())
        assertEquals(JsonNull, done["error"])
        assertEquals(37L, (done["ms"] as JsonPrimitive).long)
        assertNotNull(done["ts"])
    }

    @Test
    fun failureLineCarriesErrorText() {
        val dir = temp.newFolder("files")
        val audit = NodeAuditLog(dir)
        val sha = NodeAuditLog.sha256("{}")
        audit.start("t2", "hello_fail", sha, "ask", -1)
        audit.done("t2", "hello_fail", sha, "ask", ok = false, error = "boom", ms = 5, pid = -1)

        val done = Json.parseToJsonElement(java.io.File(dir, "node-audit.jsonl").readLines()[1]).jsonObject
        assertEquals("boom", (done["error"] as JsonPrimitive).content)
        assertEquals("ask", (done["approval"] as JsonPrimitive).content)
    }

    @Test
    fun sha256IsStableAndHidesPlainArgs() {
        val a = NodeAuditLog.sha256("""{"a":1}""")
        val b = NodeAuditLog.sha256("""{"a":1}""")
        assertEquals(a, b)
        assertEquals(64, a.length)
        assertTrue(a.all { it in "0123456789abcdef" })
    }
}

package xin.dshaa.core.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * M88-G（docs/183 §7）+ docs/187 §8.4：工具输入上限。
 *
 * 默认 64KB；`write` / `edit` 16MB（`limitFor` 可注入，clamp 64KB..64MB）；UTF-8 字节计算；
 * 超限与半截 JSON 都返回显式错误；恰好等于上限放行。16MB 边界用小的 override 测，不真分配 16MB。
 */
class ToolInputGuardTest {

    @Test
    fun defaultLimitIs64KbAndWriteEditAre16Mb() {
        assertEquals(64 * 1024, ToolInputGuard.limitFor("fake_tool"))
        assertEquals(64 * 1024, ToolInputGuard.limitFor("bash"))
        assertEquals(16 * 1024 * 1024, ToolInputGuard.limitFor("write"))
        assertEquals(16 * 1024 * 1024, ToolInputGuard.limitFor("edit"))
        assertEquals(16 * 1024 * 1024, ToolInputGuard.LARGE_MAX_BYTES)
    }

    @Test
    fun largeLimitIsInjectableAndClampedWithoutAllocating16Mb() {
        // docs/187 §8.4.3：limitFor 收一个可注入上限（高级设置/调试用，只对大内容工具生效）。
        assertEquals(128 * 1024, ToolInputGuard.limitFor("write", largeLimitBytes = 128 * 1024))
        assertEquals(64 * 1024, ToolInputGuard.limitFor("write", largeLimitBytes = 1), "低于 64KB 要 clamp")
        assertEquals(
            64 * 1024 * 1024,
            ToolInputGuard.limitFor("write", largeLimitBytes = Int.MAX_VALUE),
            "高于 64MB 要 clamp",
        )
        assertEquals(
            64 * 1024,
            ToolInputGuard.limitFor("bash", largeLimitBytes = 8 * 1024 * 1024),
            "普通工具不吃注入值",
        )
    }

    @Test
    fun injectedLimitBoundaryIsInclusive() {
        // 用小的 override 测边界，避免真的分配 16MB。
        val atLimit = "{\"c\":\"" + "a".repeat(128 * 1024 - 8) + "\"}"
        assertEquals(128 * 1024, ToolInputGuard.byteSize(atLimit))
        assertNull(ToolInputGuard.violation("write", atLimit, largeLimitBytes = 128 * 1024), "恰好等于上限放行")
        assertNotNull(ToolInputGuard.parseStrict(atLimit))

        val over = "{\"c\":\"" + "a".repeat(128 * 1024 - 7) + "\"}"
        assertEquals(128 * 1024 + 1, ToolInputGuard.byteSize(over))
        val message = assertNotNull(ToolInputGuard.violation("write", over, largeLimitBytes = 128 * 1024))
        assertTrue(message.contains("Tool input too long"), message)
        assertTrue(message.contains("128KB"), message)
    }

    @Test
    fun oversizedInputReturnsActionableError() {
        val raw = "{\"q\":\"" + "a".repeat(64 * 1024) + "\"}"
        val message = assertNotNull(ToolInputGuard.violation("fake_tool", raw))
        assertTrue(message.contains("Tool input too long"), message)
        assertTrue(message.contains("${ToolInputGuard.byteSize(raw)} bytes"), message)
        assertTrue(message.contains("65536 bytes"), message)
        assertTrue(message.contains("append"), message)
        assertTrue(message.contains("append"), message)
    }

    @Test
    fun exactlyAtLimitIsAllowed() {
        val raw = "{\"q\":\"" + "a".repeat(64 * 1024 - 8) + "\"}"
        assertEquals(64 * 1024, ToolInputGuard.byteSize(raw))
        assertNull(ToolInputGuard.violation("fake_tool", raw))
        assertNotNull(ToolInputGuard.parseStrict(raw))
    }

    @Test
    fun sixHundredKbContentPassesForWriteAndEditButNotNormalTools() {
        // docs/187 §8.4.4：一次 600KB 的 write/edit 不再被 512KB 旧上限拒绝；普通工具仍 64KB。
        val raw = "{\"file_path\":\"a.txt\",\"content\":\"" + "a".repeat(600 * 1024) + "\"}"
        assertNotNull(ToolInputGuard.violation("fake_tool", raw), "普通工具仍按 64KB 拒绝")
        assertNull(ToolInputGuard.violation("write", raw))
        assertNull(ToolInputGuard.violation("edit", raw))
    }

    @Test
    fun halfJsonAndNonObjectJsonAreRejected() {
        assertTrue(assertNotNull(ToolInputGuard.violation("fake_tool", "{\"q\":\"半截")).contains("not valid JSON"))
        assertTrue(assertNotNull(ToolInputGuard.violation("fake_tool", "[1,2,3]")).contains("not valid JSON"))
        assertNull(ToolInputGuard.violation("fake_tool", ""), "空参数按 {} 处理")
        assertNull(ToolInputGuard.violation("fake_tool", "{}"))
    }

    @Test
    fun truncatedJsonMessagePointsAtWriteAppend() {
        // docs/187 §8.4.3：arguments were truncated的文案要引导「拆分为多次 write/append」，不是让模型原样重试。
        val message = assertNotNull(ToolInputGuard.violation("write", "{\"content\":\"半截"))
        assertTrue(message.contains("arguments were truncated"), message)
        assertTrue(message.contains("write/append"), message)
        assertTrue(message.contains("append=false"), message)
    }

    @Test
    fun utf8BytesAreCountedNotCharacters() {
        val raw = "{\"q\":\"" + "中".repeat(30) + "\"}"
        assertTrue(ToolInputGuard.byteSize(raw) > raw.length)
        assertEquals(raw.length + 2 * 30, ToolInputGuard.byteSize(raw))
    }
}

package xin.dshaa.core.session

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 收尾指令逐字节对齐上游 `dsh-tool-goal/lib/types/wrapup.js`。
 *
 * 夹具不是手抄的：`tools/wrapup-oracle.mjs` 从上游源码里抽出 `renderWrapupContext`
 * 现算两种文案（含中文、引号、换行的 objective/reason），写进 test resources。
 */
class GoalWrapupTest {

    private fun fixture(name: String): String =
        javaClass.classLoader!!.getResourceAsStream("goal-wrapup/$name")!!.use { it.readBytes().toString(Charsets.UTF_8) }

    @Test
    fun `complete matches the upstream wrapup bytes`() {
        val objective = fixture("objective.txt")
        assertEquals(fixture("complete.txt"), GoalWrapup.complete(objective))
    }

    @Test
    fun `blocked matches the upstream wrapup bytes`() {
        val objective = fixture("objective.txt")
        val reason = fixture("blocked-reason.txt")
        assertEquals(fixture("blocked.txt"), GoalWrapup.blocked(objective, reason))
    }

    @Test
    fun `wrapup quotes the objective and the blocked reason like JSON stringify`() {
        val text = GoalWrapup.complete("a \"quoted\" \\ line\nbreak")
        assertEquals("Objective: \"a \\\"quoted\\\" \\\\ line\\nbreak\"", text.lines()[1])
        val blocked = GoalWrapup.blocked("obj", "no \"key\"")
        assertEquals("Blocked: \"no \\\"key\\\"\"", blocked.lines()[2])
    }

    @Test
    fun `summary bound truncates at 120 utf16 units`() {
        val objective = "目".repeat(200)
        val summary = GoalWrapup.summary("complete", objective)
        assertEquals(GoalWrapup.SUMMARY_MAX_CHARS, summary.length)
        assertEquals(119, summary.substringBeforeLast('\u2026').length)
        assertEquals("complete: $objective".take(119) + "\u2026", summary)

        val short = GoalWrapup.summary("blocked", "短目标")
        assertEquals("blocked: 短目标", short)
    }
}

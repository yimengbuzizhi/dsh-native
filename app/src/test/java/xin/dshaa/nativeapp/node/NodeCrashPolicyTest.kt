package xin.dshaa.nativeapp.node

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * docs/187 §九 第 5 项：崩溃循环策略（纯时间戳，不依赖进程/真机）。
 *
 * 判据：
 * - 5 分钟内累计 ≥3 次 → 不再重启（`Failed`）；
 * - 只有连续运行 ≥60s 才把历史清零（起 1 秒就崩的宿主不能无限重来）。
 */
class NodeCrashPolicyTest {

    @Test
    fun threeCrashesWithinFiveMinutes_stopRestarting() {
        val policy = NodeCrashPolicy()
        assertTrue(policy.onExit(0, "crash-1").restart)
        assertTrue(policy.onExit(1_000, "crash-2").restart)
        val third = policy.onExit(2_000, "crash-3")
        assertFalse("5 分钟内第 3 次必须收手", third.restart)
        assertEquals(3, third.attempt)
        assertEquals(3, policy.crashTimes().size)
    }

    @Test
    fun stableRunOfSixtySeconds_clearsCrashHistory() {
        val policy = NodeCrashPolicy()
        policy.onReady(0)
        assertTrue(policy.onExit(1_000, "crash-early").restart)
        // 第二段跑了 61s 才崩：稳定运行，历史清零 → 这次算第 1 次，可重启。
        policy.onReady(10_000)
        val afterStable = policy.onExit(71_000, "crash-after-stable")
        assertTrue("稳定 ≥60s 后应清零重来", afterStable.restart)
        assertEquals(1, afterStable.attempt)
        assertEquals(1, policy.crashTimes().size)
    }

    @Test
    fun crashesOlderThanWindow_doNotCount() {
        val policy = NodeCrashPolicy()
        policy.onExit(0, "crash-1")
        policy.onExit(1_000, "crash-2")
        // 第 3 次在 301s：第一次已滑出 5 分钟窗口，只剩 2 次 → 仍可重启。
        val third = policy.onExit(301_000, "crash-3")
        assertTrue(third.restart)
        assertEquals(2, third.attempt)
    }

    @Test
    fun resetLetsUserRetryAfterFail() {
        val policy = NodeCrashPolicy()
        repeat(3) { policy.onExit(it * 1_000L, "crash") }
        assertFalse(policy.onExit(3_000, "crash-4").restart)
        policy.reset()
        assertTrue("用户主动重试应重新获得 3 次配额", policy.onExit(4_000, "retry").restart)
    }
}

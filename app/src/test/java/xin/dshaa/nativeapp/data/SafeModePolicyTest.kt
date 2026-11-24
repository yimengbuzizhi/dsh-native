package xin.dshaa.nativeapp.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 2026-11-24 用户口径：一分钟之内重启应用 5 次进入安全模式。
 * 只测纯判定逻辑；SharedPreferences 读写在 [SafeMode] 里，靠真机验证。
 */
class SafeModePolicyTest {

    private val t0 = 1_700_000_000_000L

    @Test fun theFifthLaunchWithinAMinuteTriggers() {
        // 已有 4 次（窗口内），第 5 次（now）触发
        val prior = listOf(t0, t0 + 5_000, t0 + 12_000, t0 + 20_000)
        val now = t0 + 30_000
        assertTrue(SafeModePolicy.shouldTrigger(prior, now))
        assertEquals(4, SafeModePolicy.recent(prior, now).size)
    }

    @Test fun theFourthLaunchDoesNotTrigger() {
        // 已有 3 次，第 4 次还不该触发
        val prior = listOf(t0, t0 + 1_000, t0 + 2_000)
        assertFalse(SafeModePolicy.shouldTrigger(prior, t0 + 3_000))
    }

    @Test fun launchesOlderThanTheWindowDoNotCount() {
        // 4 次都在 10 分钟前：窗口里 0 次 + 这次 = 1，不触发
        val old = listOf(t0 - 600_000, t0 - 590_000, t0 - 580_000, t0 - 570_000)
        assertFalse(SafeModePolicy.shouldTrigger(old, t0))
        assertEquals(listOf<Long>(), SafeModePolicy.recent(old, t0))
    }

    @Test fun exactlyAtWindowEdgeCounts() {
        val starts = listOf(t0 - SafeModePolicy.WINDOW_MS, t0 - 1_000, t0 - 2_000, t0 - 3_000)
        assertEquals(4, SafeModePolicy.recent(starts, t0).size)
        assertTrue(SafeModePolicy.shouldTrigger(starts, t0))
    }

    @Test fun recentIsSortedAndDeduplicated() {
        val starts = listOf(t0 - 3_000, t0 - 1_000, t0 - 3_000, t0 - 2_000)
        assertEquals(listOf(t0 - 3_000, t0 - 2_000, t0 - 1_000), SafeModePolicy.recent(starts, t0))
    }

    @Test fun consecutiveFailedBootsTriggerWithoutRapidRestarts() {
        // 用户 2026-11-24 第二条：插件加载期崩溃，不必凑够 5 次、也不用挤在一分钟里。
        assertFalse(SafeModePolicy.failedBootsTrigger(1))
        assertTrue(SafeModePolicy.failedBootsTrigger(2))
        assertTrue(SafeModePolicy.failedBootsTrigger(3))
    }
}

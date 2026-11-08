package xin.dshaa.core.session

import xin.dshaa.core.model.ScheduleException
import xin.dshaa.core.model.ScheduleTiming
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 提醒的持久化（`schedules.json`）：CRUD + 投递记账 + 原子写可重读。 */
class ScheduleStoreTest {

    private fun tempStore(): Pair<ScheduleStore, File> {
        val dir = Files.createTempDirectory("dsh-schedules").toFile()
        val file = File(dir, "schedules.json")
        return ScheduleStore(file) to file
    }

    private fun timing() = ScheduleTiming(kind = "after_seconds", afterSeconds = 60)

    @Test
    fun `create persists and reloads`() {
        val (store, file) = tempStore()
        val task = store.create("s1", "喝水", "提醒我喝水", timing(), nextRunAt = 1_000, now = 0)
        assertTrue(task.id.startsWith("sched-"))
        assertEquals(1, store.list().size)

        val reloaded = ScheduleStore(file).list()
        assertEquals(1, reloaded.size)
        assertEquals("喝水", reloaded[0].title)
        assertEquals("提醒我喝水", reloaded[0].prompt)
        assertEquals("after_seconds", reloaded[0].timing.kind)
        assertEquals(60L, reloaded[0].timing.afterSeconds)
        assertEquals(1_000L, reloaded[0].nextRunAt)
        assertTrue(reloaded[0].active)
    }

    @Test
    fun `list is scoped to the session`() {
        val (store, _) = tempStore()
        store.create("s1", "A", "a", timing(), nextRunAt = 2_000)
        store.create("s2", "B", "b", timing(), nextRunAt = 1_000)
        assertEquals(listOf("A"), store.listForSession("s1").map { it.title })
        assertEquals(2, store.list().size)
        // 全局列表按下次触发时间排序
        assertEquals(listOf("B", "A"), store.list().map { it.title })
    }

    @Test
    fun `update keeps the schedule when only text changes`() {
        val (store, file) = tempStore()
        val task = store.create("s1", "A", "a", timing(), nextRunAt = 5_000, now = 0)
        val updated = store.update(task.id, "s1", title = "B", prompt = null, timing = null, nextRunAt = null, now = 10)
        assertEquals("B", updated.title)
        assertEquals("a", updated.prompt)
        assertEquals(5_000L, updated.nextRunAt)
        assertEquals(10L, updated.updatedAt)
        assertEquals("B", ScheduleStore(file).list().single().title)
    }

    @Test
    fun `update and delete refuse another session's task`() {
        val (store, _) = tempStore()
        val task = store.create("s1", "A", "a", timing(), nextRunAt = 5_000)
        assertEquals("schedule_not_found", assertFailsWith<ScheduleException> {
            store.update(task.id, "s2", title = "B", prompt = null, timing = null, nextRunAt = null)
        }.code)
        assertEquals("schedule_not_found", assertFailsWith<ScheduleException> {
            store.delete(task.id, "s2")
        }.code)
        assertEquals(1, store.list().size)
    }

    @Test
    fun `markDelivered advances or ends the task`() {
        val (store, _) = tempStore()
        val task = store.create("s1", "A", "a", timing(), nextRunAt = 5_000)
        val delivered = store.markDelivered(task.id, deliveredAt = 6_000, nextRunAt = 9_000)
        assertEquals(6_000L, delivered.lastDeliveredAt)
        assertEquals(9_000L, delivered.nextRunAt)
        assertTrue(delivered.active)

        val ended = store.markDelivered(task.id, deliveredAt = 10_000, nextRunAt = null)
        assertFalse(ended.active)
        assertEquals(9_000L, ended.nextRunAt)
    }

    @Test
    fun `removeForSession drops only that session`() {
        val (store, _) = tempStore()
        store.create("s1", "A", "a", timing(), nextRunAt = 1_000)
        store.create("s2", "B", "b", timing(), nextRunAt = 2_000)
        store.removeForSession("s1")
        assertEquals(listOf("B"), store.list().map { it.title })
        assertNull(store.listForSession("s1").firstOrNull())
    }

    @Test
    fun `corrupt file reads as empty instead of crashing`() {
        val (store, file) = tempStore()
        file.writeText("not json")
        assertEquals(emptyList(), store.list())
    }
}

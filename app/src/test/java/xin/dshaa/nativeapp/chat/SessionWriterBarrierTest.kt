package xin.dshaa.nativeapp.chat

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 删除工作区时的顺序证明：目录移动只能在 writer 的 NonCancellable 收尾之后发生。
 * 全部用 CompletableDeferred / join 排定顺序，不用 sleep。
 */
class SessionWriterBarrierTest {

    @Test
    fun joinWaitsForNonCancellableCleanup() = runBlocking {
        val events = mutableListOf<String>()
        val started = CompletableDeferred<Unit>()
        val cleanupGate = CompletableDeferred<Unit>()
        val writer = launch(Dispatchers.Default) {
            try {
                started.complete(Unit)
                awaitCancellation()
            } finally {
                // 引擎的收尾（tool/result、step/end、turn/end）就在 NonCancellable 里写
                withContext(NonCancellable) {
                    cleanupGate.await()
                    synchronized(events) { events += "writer-cleanup" }
                }
            }
        }
        started.await()
        val barrier = SessionWriterBarrier()
        barrier.track("/ws/a", writer)
        assertEquals(1, barrier.pending("/ws/a"))

        writer.cancel()
        // UNDISPATCHED：joinAll 会立刻跑到 join 并挂起——此时收尾还没写完
        val move = launch(start = CoroutineStart.UNDISPATCHED) {
            barrier.joinAll("/ws/a")
            synchronized(events) { events += "move" }
        }
        assertFalse("writer 收尾没完成前不能移动目录：$events", events.contains("move"))

        cleanupGate.complete(Unit)
        move.join()
        assertEquals(listOf("writer-cleanup", "move"), events)
        assertEquals(0, barrier.pending("/ws/a"))
    }

    @Test
    fun cancelAndJoinAllCancelsOnlyItsOwnWorkspace() = runBlocking {
        val events = mutableListOf<String>()
        val cleanupA = CompletableDeferred<Unit>()
        val startedA = CompletableDeferred<Unit>()
        val a = launch(Dispatchers.Default) {
            try {
                startedA.complete(Unit)
                awaitCancellation()
            } finally {
                withContext(NonCancellable) {
                    cleanupA.await()
                    synchronized(events) { events += "a-cleanup" }
                }
            }
        }
        val b = launch(Dispatchers.Default) { awaitCancellation() }
        startedA.await()

        val barrier = SessionWriterBarrier()
        barrier.track("/ws/a", a)
        barrier.track("/ws/b", b)

        val joined = launch(start = CoroutineStart.UNDISPATCHED) {
            assertEquals(1, barrier.cancelAndJoinAll("/ws/a"))
            synchronized(events) { events += "move-a" }
        }
        assertFalse("A 收尾没完成不能移动", events.contains("move-a"))
        assertTrue("B 不能因为删 A 被取消", b.isActive)

        cleanupA.complete(Unit)
        joined.join()
        assertEquals(listOf("a-cleanup", "move-a"), events)
        assertTrue(b.isActive)
        assertEquals(1, barrier.pending("/ws/b"))
        b.cancelAndJoin()
    }

    @Test
    fun completedWritersAreNotTracked() = runBlocking {
        val barrier = SessionWriterBarrier()
        val done = launch(Dispatchers.Default) { }
        done.join()
        barrier.track("/ws/done", done)
        assertEquals(0, barrier.pending("/ws/done"))
        assertEquals(0, barrier.joinAll("/ws/done"))
        assertEquals(0, barrier.cancelAndJoinAll("/ws/never-tracked"))
    }
}

package xin.dshaa.core.session

import kotlinx.serialization.json.*
import xin.dshaa.core.model.ImageRef
import xin.dshaa.core.model.SessionEvent
import java.io.File
import java.nio.file.Files
import kotlin.test.*

class PromptQueueStoreTest {
    private fun directory() = File(Files.createTempDirectory("prompt-queue-test").toFile(), "prompt-queue")
    private val owner = PromptQueueOwner("builtin.storage.jsonl", "s1", "/workspace")

    @Test fun payloadAndClaimSurviveReloadAndAckIsIdempotent() {
        val dir = directory()
        val image = ImageRef("sha", "image/png", 20, 2, 3, "图.png", 4, 6)
        val payload = PromptPayload("hello", listOf("hello", "attachment body"), listOf(image))
        val store = PromptQueueStore(dir)
        val queued = store.enqueue(owner, payload, PromptDelivery.STEER, now = 42)
        assertEquals(queued, PromptQueueStore(dir).list(owner).single())
        assertEquals("user", queued.source().getValue("kind").jsonPrimitive.content)
        assertEquals(queued.id, queued.source().getValue("promptId").jsonPrimitive.content)
        assertEquals(PromptQueueStatus.CLAIMED, store.claim(owner, PromptDelivery.STEER)?.status)
        assertNull(PromptQueueStore(dir).claim(owner))
        assertFalse(store.cancel(owner, queued.id))
        assertTrue(store.ack(owner, queued.id, 12))
        assertFalse(store.ack(owner, queued.id, 12))
        assertTrue(PromptQueueStore(dir).list().isEmpty())
    }

    @Test fun fifoPauseCancelAndOwnerIsolation() {
        val store = PromptQueueStore(directory())
        val first = store.enqueue(owner, PromptPayload("first"))
        val second = store.enqueue(owner, PromptPayload("second"))
        val other = owner.copy(sessionId = "s2")
        store.enqueue(other, PromptPayload("other"))
        store.pause(owner, "stopped")
        assertNull(store.claim(owner))
        assertEquals("other", store.claim(other)?.payload?.text)
        assertFalse(store.cancel(other, first.id))
        assertTrue(store.cancel(owner, second.id))
        store.resume(owner)
        assertEquals(first.id, store.claim(owner)?.id)
    }

    @Test fun recoveryDeduplicatesRawHistoryAndPausesUnappendedClaim() {
        val dir = directory()
        val store = PromptQueueStore(dir)
        val sent = store.enqueue(owner, PromptPayload("already durable"))
        store.claim(owner)
        val other = owner.copy(storageId = "other.storage")
        val foreign = store.enqueue(other, PromptPayload("foreign"))
        val events = listOf(SessionEvent("user/message", seq = 3, data = buildJsonObject {
            put("source", sent.source())
        }))
        store.recover(owner, events)
        assertTrue(store.list(owner).isEmpty())
        assertEquals(foreign, store.list(other).single())
        val unappended = store.enqueue(owner, PromptPayload("recover me"))
        store.claim(owner)
        PromptQueueStore(dir).recover(owner, events)
        val recovered = store.list(owner).single()
        assertEquals(unappended.id, recovered.id)
        assertEquals(PromptQueueStatus.PENDING, recovered.status)
        assertNotNull(recovered.pausedReason)
        assertNull(store.claim(owner))
        store.resume(owner)
        assertEquals(unappended.id, store.claim(owner)?.id)
    }

    @Test fun wrongOwnerAndUndurableAcknowledgmentsDoNotDelete() {
        val store = PromptQueueStore(directory())
        val entry = store.enqueue(owner, PromptPayload("keep"))
        assertFailsWith<IllegalArgumentException> { store.ack(owner, entry.id, 0) }
        assertFailsWith<IllegalArgumentException> { store.ack(owner, entry.id, 2) }
        assertFalse(store.ack(owner.copy(workspacePath = "/elsewhere"), entry.id, 2))
        assertEquals(entry, store.list().single())
    }

    @Test fun corruptFileAndFailedPersistenceNeverBecomeSuccessfulEmptyWrites() {
        val dir = directory()
        val store = PromptQueueStore(dir)
        val queueFile = File(dir, "queue.json")
        queueFile.writeText("broken")
        assertFails { store.list() }
        assertFails { store.enqueue(owner, PromptPayload("not accepted")) }
        assertEquals("broken", queueFile.readText())
        val safeDir = directory()
        val safe = PromptQueueStore(safeDir)
        File(safeDir, "queue.json.tmp").mkdir()
        assertFails { safe.enqueue(owner, PromptPayload("cannot persist")) }
        assertFalse(File(safeDir, "queue.json").exists())
    }

    @Test fun queueLimitsRejectWithoutDroppingExistingEntries() {
        val store = PromptQueueStore(directory())
        assertFailsWith<IllegalArgumentException> { store.enqueue(owner, PromptPayload("")) }
        repeat(100) { store.enqueue(owner, PromptPayload("message-$it")) }
        assertFailsWith<IllegalArgumentException> { store.enqueue(owner, PromptPayload("overflow")) }
        assertEquals(100, store.list(owner).size)
        val other = owner.copy(sessionId = "s2")
        assertNotNull(store.enqueue(other, PromptPayload("independent")))
    }

    @Test fun explicitSessionDeletionDiscardsOnlyItsOwnerAfterWorkerHasStopped() {
        val dir = directory()
        val store = PromptQueueStore(dir)
        store.enqueue(owner, PromptPayload("claimed"))
        store.claim(owner)
        val otherStorage = owner.copy(storageId = "readonly")
        val otherWorkspace = owner.copy(workspacePath = "/another")
        val otherSession = owner.copy(sessionId = "s2")
        listOf(otherStorage, otherWorkspace, otherSession).forEach { store.enqueue(it, PromptPayload("keep")) }
        store.discard(owner)
        assertTrue(PromptQueueStore(dir).list(owner).isEmpty())
        assertEquals(setOf(otherStorage, otherWorkspace, otherSession), store.list().map { it.owner }.toSet())
        store.discard(owner)
        assertEquals(3, store.list().size)
    }

    @Test fun rejectsSessionDirectoriesAndSymlinkQueueFilesWithoutTouchingTargets() {
        val base = Files.createTempDirectory("queue-guard").toFile()
        val sessions = File(base, "sessions").apply { mkdir() }
        val marker = File(sessions, "keep").apply { writeText("session") }
        assertFailsWith<IllegalArgumentException> { PromptQueueStore(sessions) }
        val dir = File(base, "prompt-queue")
        PromptQueueStore(dir)
        val linked = runCatching { Files.createSymbolicLink(File(dir, "queue.json").toPath(), marker.toPath()) }.isSuccess
        if (linked) assertFailsWith<IllegalArgumentException> { PromptQueueStore(dir).list() }
        assertEquals("session", marker.readText())
    }
}

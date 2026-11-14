package xin.dshaa.core.session

import kotlinx.serialization.json.*
import xin.dshaa.core.model.ImageRef
import xin.dshaa.core.model.SessionEvent
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

/** Identity includes storage and workspace so switching either cannot deliver to the wrong session. */
data class PromptQueueOwner(val storageId: String, val sessionId: String, val workspacePath: String)
data class PromptPayload(val text: String, val textBlocks: List<String> = emptyList(), val images: List<ImageRef> = emptyList())
enum class PromptDelivery { QUEUE, STEER }
enum class PromptQueueStatus { PENDING, CLAIMED }
data class QueuedPrompt(
    val id: String,
    val owner: PromptQueueOwner,
    val payload: PromptPayload,
    val delivery: PromptDelivery,
    val createdAt: Long,
    val status: PromptQueueStatus = PromptQueueStatus.PENDING,
    val pausedReason: String? = null,
) {
    fun source(): JsonObject = buildJsonObject {
        put("kind", "user"); put("promptId", id); put("delivery", delivery.name.lowercase())
    }
}

/**
 * Durable outbox, separate from model-visible session history. Pass a dedicated `prompt-queue`
 * child of an existing app-private directory. IO/parse/atomic-replace failures propagate;
 * no operation deletes a session or silently treats corrupt state as an empty queue.
 */
class PromptQueueStore(directory: File) {
    private val root: File
    private val file: File
    init {
        require(directory.name == "prompt-queue") { "queue directory must be named prompt-queue" }
        val parent = requireNotNull(directory.absoluteFile.parentFile).canonicalFile
        require(parent.isDirectory) { "queue parent directory must exist" }
        require(!Files.isSymbolicLink(directory.toPath())) { "queue directory cannot be a symbolic link" }
        root = File(parent, "prompt-queue")
        require(directory.canonicalFile == root) { "queue directory escapes parent" }
        check(root.isDirectory || root.mkdir()) { "cannot create queue directory" }
        file = File(root, "queue.json")
        validateFiles()
    }

    private fun validateFiles() {
        require(!Files.isSymbolicLink(root.toPath()) && root.canonicalFile == root) { "queue directory changed" }
        listOf(file, File(root, "queue.json.tmp")).forEach {
            require(!Files.isSymbolicLink(it.toPath()) && it.canonicalFile.parentFile == root) { "unsafe queue file" }
            require(!it.exists() || it.isFile) { "queue path is not a regular file" }
        }
    }

    @Synchronized
    fun list(owner: PromptQueueOwner? = null): List<QueuedPrompt> = load().filter { owner == null || it.owner == owner }

    @Synchronized
    fun enqueue(owner: PromptQueueOwner, payload: PromptPayload, delivery: PromptDelivery = PromptDelivery.QUEUE,
                now: Long = System.currentTimeMillis()): QueuedPrompt {
        require(owner.storageId.isNotBlank() && owner.sessionId.isNotBlank() && owner.workspacePath.isNotBlank()) { "queue owner is incomplete" }
        require(payload.text.isNotBlank() || payload.textBlocks.any { it.isNotBlank() } || payload.images.isNotEmpty()) { "empty prompt" }
        require(payload.images.size <= 100 && payload.images.all { it.attachmentId.isNotBlank() }) { "invalid image references" }
        val before = load()
        require(before.size < 1000 && before.count { it.owner == owner } < 100) { "prompt queue is full" }
        val entry = QueuedPrompt("prompt-${UUID.randomUUID()}", owner,
            payload.copy(textBlocks = payload.textBlocks.toList(), images = payload.images.toList()), delivery, now)
        save(before + entry)
        return entry
    }

    /** One outstanding claim per owner. Never remove data before its user/message has been persisted. */
    @Synchronized
    fun claim(owner: PromptQueueOwner, delivery: PromptDelivery? = null): QueuedPrompt? {
        val before = load()
        if (before.any { it.owner == owner && it.status == PromptQueueStatus.CLAIMED }) return null
        val entry = before.firstOrNull { it.owner == owner && it.status == PromptQueueStatus.PENDING &&
            it.pausedReason == null && (delivery == null || it.delivery == delivery) } ?: return null
        val claimed = entry.copy(status = PromptQueueStatus.CLAIMED)
        save(before.map { if (it.id == entry.id) claimed else it })
        return claimed
    }

    /** Call after durable user/message(promptId); idempotent after a previous ack. */
    @Synchronized
    fun ack(owner: PromptQueueOwner, id: String, seq: Long): Boolean {
        require(seq > 0) { "delivery must have a durable seq" }
        val before = load()
        val entry = before.firstOrNull { it.id == id && it.owner == owner } ?: return false
        require(entry.status == PromptQueueStatus.CLAIMED) { "prompt has not been claimed" }
        save(before.filterNot { it.id == id })
        return true
    }

    @Synchronized
    fun pause(owner: PromptQueueOwner, reason: String) {
        require(reason.isNotBlank())
        val before = load()
        save(before.map { if (it.owner == owner) it.copy(pausedReason = reason) else it })
    }

    @Synchronized
    fun resume(owner: PromptQueueOwner) {
        val before = load()
        save(before.map { if (it.owner == owner) it.copy(pausedReason = null) else it })
    }

    /** Claimed entries cannot be cancelled: their append may already be in progress. */
    @Synchronized
    fun cancel(owner: PromptQueueOwner, id: String): Boolean {
        val before = load()
        val entry = before.firstOrNull { it.id == id && it.owner == owner } ?: return false
        if (entry.status == PromptQueueStatus.CLAIMED) return false
        save(before.filterNot { it.id == id })
        return true
    }

    /** Explicit session deletion only: the caller must first stop and join its writer. */
    @Synchronized
    fun discard(owner: PromptQueueOwner) {
        val before = load()
        if (before.any { it.owner == owner }) save(before.filterNot { it.owner == owner })
    }

    /**
     * Call once after reopening a session, before any worker starts. Deduplicate against ALL raw
     * session events (not just the compacted surface). Undelivered claims become paused pending.
     */
    @Synchronized
    fun recover(owner: PromptQueueOwner, events: List<SessionEvent>) {
        val delivered = events.filter { it.type == "user/message" }.mapNotNull { event ->
            val data = event.data as? JsonObject ?: return@mapNotNull null
            val message = data["message"] as? JsonObject ?: data
            val source = message["source"] as? JsonObject ?: return@mapNotNull null
            if (source["kind"]?.jsonPrimitive?.contentOrNull != "user") return@mapNotNull null
            source["promptId"]?.jsonPrimitive?.contentOrNull
        }.toSet()
        save(load().mapNotNull { entry ->
            when {
                entry.owner != owner -> entry
                entry.id in delivered -> null
                else -> entry.copy(status = PromptQueueStatus.PENDING,
                    pausedReason = entry.pausedReason ?: "应用重新启动，待继续发送")
            }
        })
    }

    private fun load(): List<QueuedPrompt> {
        validateFiles()
        if (!file.exists()) return emptyList()
        require(file.length() <= MAX_BYTES) { "queue file exceeds limit" }
        val doc = Json.parseToJsonElement(file.readText(Charsets.UTF_8)).jsonObject
        require(doc.getValue("version").jsonPrimitive.int == 1) { "unsupported queue version" }
        val entries = doc.getValue("entries").jsonArray.map { decode(it.jsonObject) }
        require(entries.size <= 1000 && entries.map { it.id }.distinct().size == entries.size) { "invalid queue entries" }
        return entries
    }

    private fun save(entries: List<QueuedPrompt>) {
        validateFiles()
        val bytes = buildJsonObject {
            put("version", 1); put("entries", JsonArray(entries.map(::encode)))
        }.toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_BYTES) { "prompt queue exceeds size limit" }
        val temp = File(root, "queue.json.tmp")
        java.io.FileOutputStream(temp).use { output -> output.write(bytes); output.fd.sync() }
        // Same-directory atomic replacement; never fall back to truncating the durable queue.
        Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    private fun encode(entry: QueuedPrompt): JsonObject = buildJsonObject {
        put("id", entry.id); put("storageId", entry.owner.storageId); put("sessionId", entry.owner.sessionId)
        put("workspacePath", entry.owner.workspacePath); put("createdAt", entry.createdAt)
        put("delivery", entry.delivery.name); put("status", entry.status.name)
        entry.pausedReason?.let { put("pausedReason", it) }
        put("text", entry.payload.text); put("textBlocks", JsonArray(entry.payload.textBlocks.map(::JsonPrimitive)))
        put("images", buildJsonArray { entry.payload.images.forEach { image -> add(buildJsonObject {
            put("attachmentId", image.attachmentId); put("mediaType", image.mediaType); put("bytes", image.bytes)
            put("width", image.width); put("height", image.height); image.name?.let { put("name", it) }
            image.originalWidth?.let { put("originalWidth", it) }; image.originalHeight?.let { put("originalHeight", it) }
            put("offloaded", image.offloaded)
        }) } })
    }

    private fun decode(obj: JsonObject): QueuedPrompt {
        fun str(key: String) = obj.getValue(key).jsonPrimitive.content
        val images = obj.getValue("images").jsonArray.map { value ->
            val image = value.jsonObject
            ImageRef(image.getValue("attachmentId").jsonPrimitive.content, image.getValue("mediaType").jsonPrimitive.content,
                image.getValue("bytes").jsonPrimitive.int, image.getValue("width").jsonPrimitive.int,
                image.getValue("height").jsonPrimitive.int, image["name"]?.jsonPrimitive?.contentOrNull,
                image["originalWidth"]?.jsonPrimitive?.intOrNull, image["originalHeight"]?.jsonPrimitive?.intOrNull,
                image["offloaded"]?.jsonPrimitive?.booleanOrNull ?: false)
        }
        return QueuedPrompt(str("id"), PromptQueueOwner(str("storageId"), str("sessionId"), str("workspacePath")),
            PromptPayload(str("text"), obj.getValue("textBlocks").jsonArray.map { it.jsonPrimitive.content }, images),
            PromptDelivery.valueOf(str("delivery")), obj.getValue("createdAt").jsonPrimitive.long,
            PromptQueueStatus.valueOf(str("status")), obj["pausedReason"]?.jsonPrimitive?.contentOrNull)
    }

    private companion object { const val MAX_BYTES = 16 * 1024 * 1024 }
}

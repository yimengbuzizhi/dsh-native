package xin.dshaa.nativeapp.memory

import android.app.Application
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import xin.dshaa.core.tools.DshTool
import xin.dshaa.core.tools.ToolContext
import xin.dshaa.core.tools.ToolResult
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * 记忆库工具集（照搬 Operit 的 8 个记忆工具）：
 * query_memory / get_memory_by_title / create_memory / update_memory / delete_memory /
 * link_memories / query_memory_links / move_memory。
 *
 * schema 在 `core/prompt/.../tools-android.json`（与其它工具一致，不在这里手写）；
 * 文案对齐 Operit `MemoryQueryToolExecutor`（成功/失败用英文，模型侧与 Operit 同形）。
 */
class MemoryTools(app: Application) {
    private val store = MemoryStore.get(app)

    val tools: List<DshTool> = listOf(
        QueryMemoryTool(store),
        GetMemoryByTitleTool(store),
        CreateMemoryTool(store),
        UpdateMemoryTool(store),
        DeleteMemoryTool(store),
        LinkMemoriesTool(store),
        QueryMemoryLinksTool(store),
        MoveMemoryTool(store),
    )
}

private fun JsonObject.str(key: String): String? =
    runCatching { this[key]?.jsonPrimitive?.contentOrNull }.getOrNull()?.takeIf { it.isNotBlank() }

private fun JsonObject.int(key: String): Int? =
    runCatching { this[key]?.jsonPrimitive?.intOrNull }.getOrNull()

private fun JsonObject.float(key: String): Float? =
    runCatching { this[key]?.jsonPrimitive?.floatOrNull }.getOrNull()

private fun JsonObject.bool(key: String): Boolean? =
    runCatching { this[key]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() }.getOrNull()

private val displayTime = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
private fun time(ms: Long): String = displayTime.format(Date(ms))

private fun memoryBlock(memory: Memory, content: String = memory.content): String = buildString {
    appendLine("Title: ${memory.title}")
    appendLine("Content: $content")
    appendLine("Source: ${memory.source}")
    appendLine("Tags: ${memory.tags.joinToString(", ")}")
    append("Created: ${time(memory.createdAt)}")
}

/** 文档节点：按 query 找分块；没有 query 就取前 5 块。 */
private suspend fun MemoryStore.documentBlock(memory: Memory, query: String?, limit: Int = 5): String {
    val total = totalChunkCount(memory.id)
    val chunks = if (query.isNullOrBlank()) {
        chunksFor(memory.id).take(limit)
    } else {
        searchChunksInDocument(memory.id, query, limit)
    }
    if (chunks.isEmpty()) {
        return "Document: ${memory.title} ($total chunks)\nNo matching chunks for query '${query ?: ""}'."
    }
    return buildString {
        appendLine("Title: ${memory.title}")
        appendLine("Content: Document (${total} chunks)")
        chunks.take(limit).forEach { chunk ->
            appendLine("Chunk ${chunk.chunkIndex + 1}/$total:")
            appendLine(chunk.content)
        }
        appendLine("Source: ${memory.source}")
        appendLine("Tags: ${memory.tags.joinToString(", ")}")
        append("Created: ${time(memory.createdAt)}")
    }
}

private class QueryMemoryTool(private val store: MemoryStore) : DshTool {
    override val name = "query_memory"

    /** Operit 的 snapshot：每个 profile 最多 32 个，按 lastAccess LRU 淘汰；这里进程内即可。 */
    private data class SnapshotState(
        val seen: MutableSet<Long> = mutableSetOf(),
        var lastAccessAtMs: Long = System.currentTimeMillis(),
    )

    private val snapshots = LinkedHashMap<String, SnapshotState>()

    private fun snapshot(id: String): Pair<SnapshotState, Boolean> = synchronized(snapshots) {
        val existing = snapshots[id]
        if (existing != null) {
            existing.lastAccessAtMs = System.currentTimeMillis()
            existing to false
        } else {
            val created = SnapshotState(lastAccessAtMs = System.currentTimeMillis())
            snapshots[id] = created
            if (snapshots.size > 32) {
                val oldest = snapshots.entries.minByOrNull { it.value.lastAccessAtMs }?.key
                if (oldest != null && oldest != id) snapshots.remove(oldest)
            }
            created to true
        }
    }

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val query = args.str("query") ?: return ToolResult.err("Missing or empty required parameter: query")
        val folderPath = args.str("folder_path")
        val explicitLimit = args.int("limit")
        val limit = explicitLimit ?: if (query.trim() == "*") Int.MAX_VALUE else 20
        val threshold = args.float("threshold")?.toDouble()
            ?: 0.0
        if (threshold < 0) return ToolResult.err("Invalid threshold. Expected a non-negative number.")
        return try {
            val snapshotId = args.str("snapshot_id") ?: UUID.randomUUID().toString()
            val (snapshot, snapshotCreated) = snapshot(snapshotId)
            val found = store.searchMemories(query, folderPath, relevanceThreshold = threshold)
            val excluded = found.count { it.id in snapshot.seen }
            val memories = found.filterNot { it.id in snapshot.seen }.take(limit.coerceAtLeast(1))
            memories.forEach { snapshot.seen += it.id }

            val header = buildList {
                add("Snapshot ID: $snapshotId")
                if (snapshotCreated) add("Snapshot created: true")
                if (excluded > 0) add("Excluded by snapshot: $excluded")
            }.joinToString("\n")

            if (memories.isEmpty()) {
                return ToolResult.ok(if (header.isBlank()) "No relevant memories found." else "$header\nNo relevant memories found.")
            }
            val wildcard = query.trim() == "*"
            val blocks = memories.map { memory ->
                if (memory.isDocumentNode) {
                    store.documentBlock(memory, query, limit.coerceAtMost(5))
                } else {
                    val content = if (wildcard) {
                        val summary = memory.content.take(10)
                        if (memory.content.length > 10) "$summary..." else summary
                    } else {
                        memory.content
                    }
                    memoryBlock(memory, content)
                }
            }
            ToolResult.ok(header + "\n---\n" + blocks.joinToString("\n---\n"))
        } catch (e: Exception) {
            ToolResult.err("Failed to execute memory query: ${e.message}")
        }
    }
}

private class GetMemoryByTitleTool(private val store: MemoryStore) : DshTool {
    override val name = "get_memory_by_title"

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val title = args.str("title") ?: return ToolResult.err("title parameter is required")
        val memory = store.findMemoryByTitle(title)
            ?: return ToolResult.err("Memory not found with title: $title")
        return try {
            if (!memory.isDocumentNode) return ToolResult.ok(memoryBlock(memory))
            val chunkRange = args.str("chunk_range")
            val chunkIndex = args.int("chunk_index")
            val query = args.str("query")
            val limit = args.int("limit") ?: 20
            val total = store.totalChunkCount(memory.id)
            when {
                query != null -> {
                    val chunks = store.searchChunksInDocument(memory.id, query, limit)
                    if (chunks.isEmpty()) return ToolResult.err("No matching chunks found")
                    ToolResult.ok("Document: ${memory.title}\n" + chunks.joinToString("\n---\n") {
                        "Chunk ${it.chunkIndex + 1}/$total:\n${it.content}"
                    })
                }
                chunkRange != null -> {
                    val parts = chunkRange.split("-").mapNotNull { it.trim().toIntOrNull() }
                    if (parts.size != 2) {
                        return ToolResult.err("Invalid chunk_range format. Expected 'start-end' (e.g., '3-7')")
                    }
                    val start = (parts[0].coerceAtLeast(1) - 1).coerceAtMost((total - 1).coerceAtLeast(0))
                    val end = (parts[1].coerceAtLeast(1) - 1).coerceAtMost((total - 1).coerceAtLeast(0))
                    if (total == 0 || start > end) {
                        return ToolResult.err("Chunk range out of bounds. Document has $total chunks. Valid range: 1-$total")
                    }
                    val chunks = (start..end).mapNotNull { store.chunkByIndex(memory.id, it) }
                    ToolResult.ok("Document: ${memory.title}\n" + chunks.joinToString("\n---\n") {
                        "Chunk ${it.chunkIndex + 1}/$total:\n${it.content}"
                    })
                }
                chunkIndex != null -> {
                    val index = (chunkIndex.coerceAtLeast(1) - 1).coerceAtMost((total - 1).coerceAtLeast(0))
                    val chunk = store.chunkByIndex(memory.id, index)
                        ?: return ToolResult.err("Chunk index out of bounds. Document has $total chunks. Valid range: 1-$total")
                    ToolResult.ok("Chunk ${chunk.chunkIndex + 1}/$total:\n${chunk.content}")
                }
                // Operit 隐藏行为：文档节点没给块参数时，用标题当搜索词取 1 块
                else -> ToolResult.ok(store.documentBlock(memory, memory.title, 1))
            }
        } catch (e: Exception) {
            ToolResult.err("Failed to retrieve document chunks: ${e.message}")
        }
    }
}

private class CreateMemoryTool(private val store: MemoryStore) : DshTool {
    override val name = "create_memory"

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val title = args.str("title")
        val content = args.str("content")
        if (title == null || content == null) {
            return ToolResult.err("Both title and content parameters are required")
        }
        val contentType = args.str("content_type") ?: "text/plain"
        val source = args.str("source") ?: "ai_created"
        val folderPath = args.str("folder_path") ?: ""
        val tags = args.str("tags")?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }
        return try {
            val memory = store.createMemory(
                title = title,
                content = content,
                contentType = contentType,
                source = source,
                folderPath = folderPath,
                tags = tags,
            )
            ToolResult.ok("Successfully created memory: '$title' (UUID: ${memory.uuid})")
        } catch (e: Exception) {
            ToolResult.err("Failed to create memory: ${e.message}")
        }
    }
}

private class UpdateMemoryTool(private val store: MemoryStore) : DshTool {
    override val name = "update_memory"

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val oldTitle = args.str("old_title")
            ?: return ToolResult.err("old_title parameter is required to identify the memory")
        val memory = store.findMemoryByTitle(oldTitle)
            ?: return ToolResult.err("Memory not found with title: $oldTitle")
        val newTitle = args.str("new_title") ?: oldTitle
        val tags = if (args.containsKey("tags")) {
            (args["tags"]?.jsonPrimitive?.contentOrNull ?: "").split(",").map { it.trim() }.filter { it.isNotEmpty() }
        } else {
            null
        }
        return try {
            val updated = store.updateMemory(
                memory = memory,
                newTitle = newTitle,
                newContent = args.str("content") ?: memory.content,
                newContentType = args.str("content_type") ?: memory.contentType,
                newSource = args.str("source") ?: memory.source,
                newCredibility = args.float("credibility") ?: memory.credibility,
                newImportance = args.float("importance") ?: memory.importance,
                newFolderPath = if (args.containsKey("folder_path")) {
                    args["folder_path"]?.jsonPrimitive?.contentOrNull.orEmpty()
                } else {
                    memory.folderPath
                },
                newTags = tags,
            )
            if (updated == null) ToolResult.err("Failed to update memory")
            else ToolResult.ok("Successfully updated memory from '$oldTitle' to '$newTitle'")
        } catch (e: Exception) {
            ToolResult.err("Failed to update memory: ${e.message}")
        }
    }
}

private class DeleteMemoryTool(private val store: MemoryStore) : DshTool {
    override val name = "delete_memory"

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val title = args.str("title")
            ?: return ToolResult.err("title parameter is required to identify the memory")
        val memory = store.findMemoryByTitle(title)
            ?: return ToolResult.err("Memory not found with title: $title")
        return if (store.deleteMemory(memory.id)) {
            ToolResult.ok("Successfully deleted memory: '$title'")
        } else {
            ToolResult.err("Failed to delete memory: '$title'")
        }
    }
}

private class LinkMemoriesTool(private val store: MemoryStore) : DshTool {
    override val name = "link_memories"

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val sourceTitle = args.str("source_title")
        val targetTitle = args.str("target_title")
        if (sourceTitle == null || targetTitle == null) {
            return ToolResult.err("Both source_title and target_title parameters are required")
        }
        val type = args.str("link_type") ?: "related"
        val weight = args.float("weight") ?: 0.7f
        val description = args.str("description") ?: ""
        val source = store.findMemoryByTitle(sourceTitle)
            ?: return ToolResult.err("Source memory not found with title: $sourceTitle")
        val target = store.findMemoryByTitle(targetTitle)
            ?: return ToolResult.err("Target memory not found with title: $targetTitle")
        val link = store.linkMemories(source.id, target.id, type, weight, description)
            ?: return ToolResult.err("Failed to link memories")
        return ToolResult.ok("Successfully linked memory: '$sourceTitle' -> '$targetTitle' (Type: $type, Strength: ${link.weight})")
    }
}

private class QueryMemoryLinksTool(private val store: MemoryStore) : DshTool {
    override val name = "query_memory_links"

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val linkId = args.int("link_id")
        val sourceTitle = args.str("source_title")
        val targetTitle = args.str("target_title")
        val linkType = args.str("link_type")
        val limit = (args.int("limit") ?: 20).coerceIn(1, 200)
        val memories = store.allMemories().associateBy { it.id }
        val sourceId = sourceTitle?.let { title ->
            memories.values.firstOrNull { it.title == title }?.id
                ?: return ToolResult.err("Source memory not found with title: $title")
        }
        val targetId = targetTitle?.let { title ->
            memories.values.firstOrNull { it.title == title }?.id
                ?: return ToolResult.err("Target memory not found with title: $title")
        }
        val links = store.allLinks().filter { link ->
            (linkId == null || link.id == linkId.toLong()) &&
                (sourceId == null || link.sourceId == sourceId) &&
                (targetId == null || link.targetId == targetId) &&
                (linkType == null || link.type == linkType)
        }.sortedByDescending { it.id }.take(limit)
        if (links.isEmpty()) return ToolResult.ok("No memory links found.")
        return ToolResult.ok(
            buildString {
                appendLine("Memory Links (${links.size}):")
                links.forEach { link ->
                    val source = memories[link.sourceId]?.title ?: "?"
                    val target = memories[link.targetId]?.title ?: "?"
                    appendLine("- #${link.id}: '$source' -> '$target' (Type: ${link.type}, Weight: ${link.weight})")
                    if (link.description.isNotBlank()) appendLine("  Description: ${link.description}")
                }
            }.trimEnd(),
        )
    }
}

private class MoveMemoryTool(private val store: MemoryStore) : DshTool {
    override val name = "move_memory"

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val targetRaw = args["target_folder_path"]?.jsonPrimitive?.contentOrNull
            ?: return ToolResult.err("target_folder_path parameter is required")
        val target = normalizeFolderPath(targetRaw)
        val titles = args.str("titles")
            ?.split(",", "\n", "|")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
        val sourceFolder = normalizeFolderPath(args.str("source_folder_path"))
        if (titles == null && sourceFolder == null) {
            return ToolResult.err("Provide titles and/or source_folder_path to select memories to move")
        }
        val all = store.allMemories()
        val targets = when {
            titles != null -> all.filter { it.title in titles }
            else -> all.filter { normalizeFolderPath(it.folderPath) == sourceFolder }
        }
        if (targets.isEmpty()) return ToolResult.err("No matching memories found to move")
        val ok = store.moveMemoriesToFolder(targets.map { it.id }, targetRaw)
        if (!ok) return ToolResult.err("Failed to move selected memories")
        return ToolResult.ok("Successfully moved ${targets.size} memories to '${target ?: "uncategorized"}'")
    }
}

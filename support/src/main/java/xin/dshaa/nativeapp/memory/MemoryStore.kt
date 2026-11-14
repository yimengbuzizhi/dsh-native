package xin.dshaa.nativeapp.memory

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * 鲸歌记忆库存储层（照搬 Operit `MemoryRepository` 的行为，换掉 ObjectBox）。
 *
 * 为什么不用 Room/ObjectBox：
 * - Operit 用 ObjectBox + ToMany 关系；鲸歌没有这套依赖，SQLiteOpenHelper 足够表达
 *   Memory/Tag/Link/DocumentChunk 四张表，且不用加 KSP；
 * - 向量检索（embedding/HNSW/云 embedding）依赖 Operit 的 CloudEmbeddingService，
 *   鲸歌没有对应服务，第一版只做 Operit 的**关键词/标签/图邻居**检索，语义检索明确缺席。
 *
 * 表：
 * - memories：Memory 全部标量字段（uuid 唯一、folder_path 可空、时间戳 epoch millis）
 * - tags / memory_tags：多对多标签
 * - links：source_id/target_id + type/weight/description（(source,target,type) 唯一）
 * - document_chunks：文档记忆的分块（0-based）
 * - folders：空文件夹（Operit 用占位记忆实现，鲸歌单独一张表更干净，UI 行为一致）
 */
class MemoryStore private constructor(context: Context) {

    private val helper = Helper(context.applicationContext)

    companion object {
        private const val TAG = "MemoryStore"
        private const val DB_NAME = "memory.db"
        private const val DB_VERSION = 1
        /** Operit SEARCH_RELEVANCE_THRESHOLD。 */
        const val SEARCH_RELEVANCE_THRESHOLD = 0.025

        @Volatile
        private var instance: MemoryStore? = null

        fun get(context: Context): MemoryStore =
            instance ?: synchronized(this) {
                instance ?: MemoryStore(context.applicationContext).also { instance = it }
            }

        /** Operit `splitSearchKeywords`：有 `|` 按 `|` 切，否则按空白切。 */
        fun splitKeywords(query: String): List<String> =
            if (query.contains('|')) {
                query.split('|').map { it.trim() }.filter { it.isNotEmpty() }
            } else {
                query.split(Regex("\\s+")).map { it.trim() }.filter { it.isNotEmpty() }
            }
    }

    private val db: SQLiteDatabase get() = helper.writableDatabase

    // ---------------------------------------------------------------- 读

    suspend fun allMemories(): List<Memory> = withContext(Dispatchers.IO) {
        db.query("memories", null, null, null, null, null, "updated_at DESC").use { c ->
            val list = c.readMemories()
            attachTags(list)
        }
    }

    suspend fun findMemoryById(id: Long): Memory? = withContext(Dispatchers.IO) {
        db.query("memories", null, "id = ?", arrayOf(id.toString()), null, null, null).use { c ->
            if (!c.moveToFirst()) return@withContext null
            attachTags(listOf(c.readMemory())).firstOrNull()
        }
    }

    suspend fun findMemoryByUuid(uuid: String): Memory? = withContext(Dispatchers.IO) {
        db.query("memories", null, "uuid = ?", arrayOf(uuid), null, null, null).use { c ->
            if (!c.moveToFirst()) return@withContext null
            attachTags(listOf(c.readMemory())).firstOrNull()
        }
    }

    /** Operit：按标题精确匹配（大小写敏感），取第一条。 */
    suspend fun findMemoryByTitle(title: String): Memory? = withContext(Dispatchers.IO) {
        db.query("memories", null, "title = ?", arrayOf(title), null, null, "id ASC", "1").use { c ->
            if (!c.moveToFirst()) return@withContext null
            attachTags(listOf(c.readMemory())).firstOrNull()
        }
    }

    suspend fun findMemoriesByTitle(title: String): List<Memory> = withContext(Dispatchers.IO) {
        db.query("memories", null, "title = ?", arrayOf(title), null, null, "id ASC").use { c ->
            attachTags(c.readMemories())
        }
    }

    suspend fun folderPaths(): List<String> = withContext(Dispatchers.IO) {
        val paths = linkedSetOf<String>()
        db.rawQuery("SELECT path FROM folders", null).use { c ->
            while (c.moveToNext()) normalizeFolderPath(c.getString(0))?.let { paths += it }
        }
        db.rawQuery("SELECT DISTINCT folder_path FROM memories WHERE folder_path IS NOT NULL AND folder_path != ''", null).use { c ->
            while (c.moveToNext()) normalizeFolderPath(c.getString(0))?.let { paths += it }
        }
        paths.sorted()
    }

    // ---------------------------------------------------------------- 写

    /** UI 下拉里的「未分类」是显示值，落库必须是 null（Operit 把它写成字面量，是个 bug）。 */
    private fun folderForStorage(folderPath: String?): String? =
        if (folderPath?.trim() == UNCATEGORIZED) null else normalizeFolderPath(folderPath)

    suspend fun createMemory(
        title: String,
        content: String,
        contentType: String = "text/plain",
        source: String = "user_input",
        folderPath: String = "",
        tags: List<String>? = null,
        isDocumentNode: Boolean = false,
        documentPath: String? = null,
        credibility: Float = 0.5f,
        importance: Float = 0.5f,
    ): Memory = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val normalizedTags = tags?.map { it.trim() }?.filter { it.isNotEmpty() }?.distinct().orEmpty()
        val values = ContentValues().apply {
            put("uuid", UUID.randomUUID().toString())
            put("title", title)
            put("content", content)
            put("content_type", contentType)
            put("source", source)
            put("credibility", credibility.coerceIn(0f, 1f))
            put("importance", importance.coerceIn(0f, 1f))
            put("document_path", documentPath)
            put("is_document_node", if (isDocumentNode) 1 else 0)
            put("folder_path", folderForStorage(folderPath))
            put("created_at", now)
            put("updated_at", now)
            put("last_accessed_at", now)
        }
        val id = db.insertOrThrow("memories", null, values)
        replaceTags(id, normalizedTags)
        findMemoryById(id) ?: error("memory insert failed")
    }

    suspend fun updateMemory(
        memory: Memory,
        newTitle: String,
        newContent: String,
        newContentType: String = memory.contentType,
        newSource: String = memory.source,
        newCredibility: Float = memory.credibility,
        newImportance: Float = memory.importance,
        newFolderPath: String? = memory.folderPath,
        newTags: List<String>? = null,
    ): Memory? = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val values = ContentValues().apply {
            put("title", newTitle)
            put("content", newContent)
            put("content_type", newContentType)
            put("source", newSource)
            put("credibility", newCredibility.coerceIn(0f, 1f))
            put("importance", newImportance.coerceIn(0f, 1f))
            put("folder_path", folderForStorage(newFolderPath))
            put("updated_at", now)
        }
        val changed = db.update("memories", values, "id = ?", arrayOf(memory.id.toString()))
        if (changed <= 0) return@withContext null
        if (newTags != null) replaceTags(memory.id, newTags.map { it.trim() }.filter { it.isNotEmpty() }.distinct())
        // 内容变了就重建文档分块（普通记忆没有分块，删除是空操作）
        if (memory.isDocumentNode && newContent != memory.content) {
            // 文档节点的 content 是预览，分块由导入时写入；这里不重建，避免覆盖。
        }
        findMemoryById(memory.id)
    }

    suspend fun deleteMemory(id: Long): Boolean = withContext(Dispatchers.IO) {
        db.beginTransaction()
        try {
            db.delete("memory_tags", "memory_id = ?", arrayOf(id.toString()))
            db.delete("document_chunks", "memory_id = ?", arrayOf(id.toString()))
            db.delete("links", "source_id = ? OR target_id = ?", arrayOf(id.toString(), id.toString()))
            val removed = db.delete("memories", "id = ?", arrayOf(id.toString())) > 0
            db.setTransactionSuccessful()
            removed
        } finally {
            db.endTransaction()
        }
    }

    suspend fun deleteMemoriesByIds(ids: Set<Long>): Int = withContext(Dispatchers.IO) {
        var removed = 0
        ids.forEach { id -> if (deleteMemory(id)) removed++ }
        removed
    }

    // ---------------------------------------------------------------- 标签

    private fun replaceTags(memoryId: Long, tagNames: List<String>) {
        db.delete("memory_tags", "memory_id = ?", arrayOf(memoryId.toString()))
        tagNames.forEach { name ->
            val tagId = tagIdFor(name)
            db.insertWithOnConflict(
                "memory_tags",
                null,
                ContentValues().apply {
                    put("memory_id", memoryId)
                    put("tag_id", tagId)
                },
                SQLiteDatabase.CONFLICT_IGNORE,
            )
        }
    }

    private fun tagIdFor(name: String): Long {
        db.query("tags", arrayOf("id"), "name = ?", arrayOf(name), null, null, null, "1").use { c ->
            if (c.moveToFirst()) return c.getLong(0)
        }
        val values = ContentValues().apply { put("name", name) }
        return db.insertOrThrow("tags", null, values)
    }

    private fun attachTags(memories: List<Memory>): List<Memory> {
        if (memories.isEmpty()) return memories
        val ids = memories.map { it.id }
        val placeholders = ids.joinToString(",") { "?" }
        val map = HashMap<Long, MutableList<String>>()
        db.rawQuery(
            "SELECT mt.memory_id, t.name FROM memory_tags mt JOIN tags t ON t.id = mt.tag_id " +
                "WHERE mt.memory_id IN ($placeholders) ORDER BY t.id ASC",
            ids.map { it.toString() }.toTypedArray(),
        ).use { c ->
            while (c.moveToNext()) {
                map.getOrPut(c.getLong(0)) { mutableListOf() } += c.getString(1)
            }
        }
        return memories.map { it.copy(tags = map[it.id].orEmpty()) }
    }

    // ---------------------------------------------------------------- 链接

    suspend fun allLinks(): List<MemoryLink> = withContext(Dispatchers.IO) {
        db.query("links", null, null, null, null, null, "id ASC").use { c ->
            buildList {
                while (c.moveToNext()) add(c.readLink())
            }
        }
    }

    suspend fun linksFor(memoryId: Long): List<MemoryLink> = withContext(Dispatchers.IO) {
        db.query(
            "links", null, "source_id = ? OR target_id = ?",
            arrayOf(memoryId.toString(), memoryId.toString()), null, null, "id ASC",
        ).use { c ->
            buildList {
                while (c.moveToNext()) add(c.readLink())
            }
        }
    }

    /**
     * Operit `linkMemories`：同 (source,target,type) 已存在则更新 weight/description。
     * 返回 null 表示两端记忆不存在或自连。
     */
    suspend fun linkMemories(
        sourceId: Long,
        targetId: Long,
        type: String = "related",
        weight: Float = 1.0f,
        description: String = "",
    ): MemoryLink? = withContext(Dispatchers.IO) {
        if (sourceId == targetId) return@withContext null
        val cleanType = type.trim().ifBlank { "related" }
        val cleanWeight = weight.coerceIn(0f, 1f)
        db.query(
            "links", arrayOf("id"), "source_id = ? AND target_id = ? AND type = ?",
            arrayOf(sourceId.toString(), targetId.toString(), cleanType), null, null, null, "1",
        ).use { c ->
            if (c.moveToFirst()) {
                val id = c.getLong(0)
                db.update(
                    "links",
                    ContentValues().apply {
                        put("weight", cleanWeight)
                        put("description", description)
                    },
                    "id = ?", arrayOf(id.toString()),
                )
                return@withContext MemoryLink(id, sourceId, targetId, cleanType, cleanWeight, description)
            }
        }
        val values = ContentValues().apply {
            put("source_id", sourceId)
            put("target_id", targetId)
            put("type", cleanType)
            put("weight", cleanWeight)
            put("description", description)
        }
        val id = db.insertOrThrow("links", null, values)
        MemoryLink(id, sourceId, targetId, cleanType, cleanWeight, description)
    }

    suspend fun updateLink(linkId: Long, type: String, weight: Float, description: String): Boolean =
        withContext(Dispatchers.IO) {
            val changed = db.update(
                "links",
                ContentValues().apply {
                    put("type", type.ifBlank { "related" })
                    put("weight", weight.coerceIn(0f, 1f))
                    put("description", description)
                },
                "id = ?", arrayOf(linkId.toString()),
            )
            changed > 0
        }

    suspend fun deleteLink(linkId: Long): Boolean = withContext(Dispatchers.IO) {
        db.delete("links", "id = ?", arrayOf(linkId.toString())) > 0
    }

    // ---------------------------------------------------------------- 搜索

    /**
     * Operit `searchMemories` 的纯关键词子集（照搬 RRF + coverage + importance + 图扩张）：
     * - 关键词按 `|` 或空白切分；`*` / 空串返回范围内全部；
     * - token：小写、长度 2..24、含字母/数字/CJK；中文用 Android ICU 断词补充分片（近似 Operit 的 Jieba）；
     * - 标题/标签候选按「命中 token 数↓、importance↓、updatedAt↓」排名，分数 =
     *   `1/(60+rank) * importance * weight * (1 + 0.6 * matched/total)`；
     * - 反向包含（用户问题里出现记忆标题）额外加分；
     * - 图扩张：前 10 名沿出/入边传播 `score*linkWeight*edgeWeight + 0.03*edgeWeight`；
     * - 语义/向量权重没有实现（鲸歌没有 embedding 服务），semanticScore 恒为 0。
     */
    suspend fun searchMemories(
        query: String,
        folderPath: String? = null,
        keywordWeight: Double = 10.0,
        tagWeight: Double = 0.0,
        edgeWeight: Double = 0.4,
        relevanceThreshold: Double = SEARCH_RELEVANCE_THRESHOLD,
    ): List<Memory> = withContext(Dispatchers.IO) {
        val scope = memoriesInFolder(folderPath)
        val trimmed = query.trim()
        if (trimmed.isEmpty() || trimmed == "*") return@withContext scope
        val keywords = splitKeywords(trimmed)
        if (keywords.isEmpty()) return@withContext emptyList()
        val tokens = buildLexicalTokens(keywords)
        if (tokens.isEmpty()) return@withContext emptyList()

        val scores = HashMap<Long, Double>()
        fun addScore(id: Long, value: Double) {
            scores[id] = (scores[id] ?: 0.0) + value
        }

        val coverageFor = { matched: Int -> 1.0 + 0.6 * matched.toDouble() / tokens.size }
        val rrf = { rank: Int -> 1.0 / (60.0 + rank) }

        // 1. 标题候选
        scope.mapNotNull { memory ->
            val matched = tokens.count { tokenMatches(memory.title, it) }
            if (matched > 0) memory to matched else null
        }.sortedWith(
            compareByDescending<Pair<Memory, Int>> { it.second }
                .thenByDescending { it.first.importance }
                .thenByDescending { it.first.updatedAt },
        ).forEachIndexed { index, (memory, matched) ->
            if (keywordWeight > 0) {
                addScore(memory.id, rrf(index + 1) * memory.importance * keywordWeight * coverageFor(matched))
            }
        }

        // 2. 标签候选
        if (tagWeight > 0) {
            scope.mapNotNull { memory ->
                val matched = tokens.count { token ->
                    memory.tags.any { tag -> tokenMatches(tag, token) }
                }
                if (matched > 0) memory to matched else null
            }.sortedWith(
                compareByDescending<Pair<Memory, Int>> { it.second }
                    .thenByDescending { it.first.importance }
                    .thenByDescending { it.first.updatedAt },
            ).forEachIndexed { index, (memory, matched) ->
                addScore(memory.id, rrf(index + 1) * memory.importance * tagWeight * coverageFor(matched))
            }
        }

        // 3. 反向包含：用户问题里直接出现记忆标题
        if (keywordWeight > 0) {
            scope.filter { memory ->
                memory.title.length >= 2 && trimmed.contains(memory.title, ignoreCase = true)
            }.forEachIndexed { index, memory ->
                addScore(memory.id, rrf(index + 1) * memory.importance * keywordWeight)
            }
        }

        // 4. 图扩张（邻居可以不在当前文件夹作用域内）
        if (scores.isNotEmpty() && edgeWeight > 0) {
            val links = allLinks()
            val top = scores.entries.sortedByDescending { it.value }.take(10)
            top.forEach { (id, sourceScore) ->
                links.forEach { link ->
                    val other = when (id) {
                        link.sourceId -> link.targetId
                        link.targetId -> link.sourceId
                        else -> return@forEach
                    }
                    addScore(other, sourceScore * link.weight * edgeWeight + 0.03 * edgeWeight)
                }
            }
        }

        val threshold = relevanceThreshold.coerceAtLeast(0.0)
        val orderedIds = scores.filterValues { it >= threshold }
            .entries.sortedByDescending { it.value }
            .map { it.key }
        if (orderedIds.isEmpty()) return@withContext emptyList()
        val scopeById = scope.associateBy { it.id }
        val missing = orderedIds.filter { it !in scopeById }
        val extra = if (missing.isEmpty()) emptyMap() else allMemories().associateBy { it.id }
        orderedIds.mapNotNull { scopeById[it] ?: extra[it] }
    }

    /** Operit token 过滤：长度 2..24，含字母/数字或 CJK。 */
    private fun isValidToken(token: String): Boolean =
        token.length in 2..24 && token.any { it.isLetterOrDigit() || it in '\u4e00'..'\u9fff' }

    /** 小写、通配保留、中文 ICU 断词 + bigram 近似 Jieba（Operit 用 Jieba SEARCH 模式）。 */
    private fun buildLexicalTokens(keywords: List<String>): List<String> {
        val out = LinkedHashSet<String>()
        keywords.forEach { raw ->
            val token = raw.trim().lowercase()
            if (token.isEmpty()) return@forEach
            if (token.contains('*')) {
                if (isValidToken(token.replace("*", ""))) out += token
                return@forEach
            }
            if (isValidToken(token)) out += token
            segmentChinese(token).forEach { piece ->
                val clean = piece.trim().lowercase()
                if (isValidToken(clean)) out += clean
            }
        }
        return out.sortedByDescending { it.length }.take(32)
    }

    private fun segmentChinese(text: String): List<String> {
        val pieces = mutableListOf<String>()
        try {
            val iterator = android.icu.text.BreakIterator.getWordInstance(java.util.Locale.CHINA)
            iterator.setText(text)
            var start = iterator.first()
            var end = iterator.next()
            while (end != android.icu.text.BreakIterator.DONE) {
                val word = text.substring(start, end)
                if (word.isNotBlank()) pieces += word
                start = end
                end = iterator.next()
            }
        } catch (_: Exception) {
            // ICU 不可用时退化为 bigram（下面统一补）
        }
        // CJK bigram：补足中文长句的召回（Operit 的 Jieba 分片更准，但方向一致）
        val cjkRun = StringBuilder()
        fun flush() {
            if (cjkRun.length >= 2) {
                for (i in 0 until cjkRun.length - 1) pieces += cjkRun.substring(i, i + 2)
            }
            cjkRun.clear()
        }
        text.forEach { ch ->
            if (ch in '\u4e00'..'\u9fff') cjkRun.append(ch) else flush()
        }
        flush()
        return pieces
    }

    private fun tokenMatches(text: String, token: String): Boolean {
        if (!token.contains('*')) return text.contains(token, ignoreCase = true)
        val regexBody = token.split('*').filter { it.isNotEmpty() }
            .joinToString(".*") { java.util.regex.Pattern.quote(it) }
        return runCatching {
            Regex(regexBody, RegexOption.IGNORE_CASE).containsMatchIn(text)
        }.getOrDefault(false)
    }

    private fun memoriesInFolder(folderPath: String?): List<Memory> {
        val all = db.query("memories", null, null, null, null, null, "updated_at DESC").use { c ->
            attachTags(c.readMemories())
        }
        val normalized = normalizeFolderPath(folderPath)
        if (normalized == null) {
            return if (folderPath == UNCATEGORIZED) all.filter { normalizeFolderPath(it.folderPath) == null } else all
        }
        return all.filter { memory ->
            val path = normalizeFolderPath(memory.folderPath) ?: return@filter false
            path == normalized || path.startsWith("$normalized/")
        }
    }

    // ---------------------------------------------------------------- 图谱

    suspend fun memoryGraph(): MemoryGraph = withContext(Dispatchers.IO) {
        val memories = allMemories()
        buildGraph(memories, allLinks())
    }

    suspend fun graphForFolder(folderPath: String): MemoryGraph = withContext(Dispatchers.IO) {
        val normalized = normalizeFolderPath(folderPath)
        val all = allMemories()
        val memories = if (normalized == null) {
            all.filter { normalizeFolderPath(it.folderPath) == null }
        } else {
            all.filter { memory ->
                val path = normalizeFolderPath(memory.folderPath) ?: return@filter false
                path == normalized || path.startsWith("$normalized/")
            }
        }
        buildGraph(memories, allLinks())
    }

    /** Operit `getGraphForMemories`：把命中记忆的直接邻居也拉进来。 */
    suspend fun graphForMemories(memories: List<Memory>): MemoryGraph = withContext(Dispatchers.IO) {
        val links = allLinks()
        val byId = allMemories().associateBy { it.id }
        val expanded = LinkedHashMap<Long, Memory>()
        memories.forEach { expanded[it.id] = it }
        val ids = memories.map { it.id }.toSet()
        links.forEach { link ->
            if (link.sourceId in ids) byId[link.targetId]?.let { expanded[it.id] = it }
            if (link.targetId in ids) byId[link.sourceId]?.let { expanded[it.id] = it }
        }
        buildGraph(expanded.values.toList(), links)
    }

    private fun buildGraph(memories: List<Memory>, links: List<MemoryLink>): MemoryGraph {
        val memoryIds = memories.map { it.id }.toSet()
        val nodes = memories.map { memory ->
            MemoryNode(
                id = memory.uuid,
                label = memory.title,
                color = when {
                    memory.isDocumentNode -> Color(0xFF9575CD)
                    memory.tags.firstOrNull() == "Person" -> Color(0xFF81C784)
                    memory.tags.firstOrNull() == "Concept" -> Color(0xFF64B5F6)
                    else -> Color.LightGray
                },
            )
        }
        val folderById = memories.associate { it.id to folderLabel(it.folderPath) }
        val edges = links.filter { it.sourceId in memoryIds && it.targetId in memoryIds }
            .map { link ->
                MemoryEdge(
                    id = link.id,
                    sourceId = memories.first { it.id == link.sourceId }.uuid,
                    targetId = memories.first { it.id == link.targetId }.uuid,
                    label = link.type,
                    weight = link.weight,
                    isCrossFolderLink = folderById[link.sourceId] != folderById[link.targetId],
                )
            }
        return MemoryGraph(nodes = nodes, edges = edges.distinctBy { it.id })
    }

    // ---------------------------------------------------------------- 文件夹

    suspend fun createFolder(folderPath: String): Boolean = withContext(Dispatchers.IO) {
        val normalized = normalizeFolderPath(folderPath) ?: return@withContext false
        db.insertWithOnConflict(
            "folders", null,
            ContentValues().apply {
                put("path", normalized)
                put("created_at", System.currentTimeMillis())
            },
            SQLiteDatabase.CONFLICT_IGNORE,
        )
        true
    }

    suspend fun renameFolder(oldPath: String, newPath: String): Boolean = withContext(Dispatchers.IO) {
        val old = normalizeFolderPath(oldPath) ?: return@withContext false
        val new = normalizeFolderPath(newPath) ?: return@withContext false
        if (old == new) return@withContext true
        db.beginTransaction()
        try {
            // 记忆：该文件夹及其子文件夹的路径前缀替换
            db.rawQuery(
                "SELECT id, folder_path FROM memories WHERE folder_path = ? OR folder_path LIKE ?",
                arrayOf(old, "$old/%"),
            ).use { c ->
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    val current = c.getString(1)
                    val replaced = if (current == old) new else new + current.removePrefix(old)
                    db.update(
                        "memories",
                        ContentValues().apply { put("folder_path", replaced) },
                        "id = ?", arrayOf(id.toString()),
                    )
                }
            }
            db.rawQuery(
                "SELECT path FROM folders WHERE path = ? OR path LIKE ?",
                arrayOf(old, "$old/%"),
            ).use { c ->
                val rows = buildList {
                    while (c.moveToNext()) add(c.getString(0))
                }
                rows.forEach { path ->
                    val replaced = if (path == old) new else new + path.removePrefix(old)
                    db.delete("folders", "path = ?", arrayOf(path))
                    db.insertWithOnConflict(
                        "folders", null,
                        ContentValues().apply {
                            put("path", replaced)
                            put("created_at", System.currentTimeMillis())
                        },
                        SQLiteDatabase.CONFLICT_IGNORE,
                    )
                }
            }
            db.setTransactionSuccessful()
            true
        } finally {
            db.endTransaction()
        }
    }

    suspend fun deleteFolder(folderPath: String) = withContext(Dispatchers.IO) {
        val normalized = normalizeFolderPath(folderPath) ?: return@withContext
        db.beginTransaction()
        try {
            db.update(
                "memories",
                ContentValues().apply { putNull("folder_path") },
                "folder_path = ?", arrayOf(normalized),
            )
            db.delete("folders", "path = ?", arrayOf(normalized))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    suspend fun moveMemoriesToFolder(memoryIds: List<Long>, targetFolderPath: String): Boolean =
        withContext(Dispatchers.IO) {
            val target = folderForStorage(targetFolderPath)
            db.beginTransaction()
            try {
                memoryIds.forEach { id ->
                    db.update(
                        "memories",
                        ContentValues().apply { put("folder_path", target) },
                        "id = ?", arrayOf(id.toString()),
                    )
                }
                db.setTransactionSuccessful()
                true
            } finally {
                db.endTransaction()
            }
        }

    // ---------------------------------------------------------------- 文档块

    /** 照搬 Operit `createMemoryFromDocument`：文档节点 + 按空行分块。 */
    suspend fun createDocumentMemory(
        title: String,
        originalPath: String,
        text: String,
        folderPath: String = "",
    ): Memory = withContext(Dispatchers.IO) {
        val memory = createMemory(
            title = title,
            content = "这是一个文档节点，包含了文件 '$originalPath' 的内容。",
            contentType = "text/plain",
            source = "document_import",
            folderPath = folderPath,
            isDocumentNode = true,
            documentPath = originalPath,
        )
        val chunks = chunkDocument(text)
        db.beginTransaction()
        try {
            chunks.forEachIndexed { index, chunk ->
                db.insertWithOnConflict(
                    "document_chunks", null,
                    ContentValues().apply {
                        put("memory_id", memory.id)
                        put("chunk_index", index)
                        put("content", chunk)
                    },
                    SQLiteDatabase.CONFLICT_REPLACE,
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        findMemoryById(memory.id) ?: memory
    }

    /**
     * Operit `TextSegmenter`/文档导入的分块规则（R:769-818）：
     * 按「≥2 个换行（中间允许空白）」切段 → 删除 `***`/`---`/`___` 分隔线整行 → trim → 丢空段。
     * 不按固定字数切，是为了让 chunk 与原文段落一一对应，`get_memory_by_title` 的块号才对得上。
     */
    private fun chunkDocument(text: String): List<String> {
        if (text.isBlank()) return emptyList()
        return text.split(Regex("(\\r?\\n[\\t ]*){2,}"))
            .map { block ->
                block.lines()
                    .filterNot { it.matches(Regex("^[\\*\\-=_]{3,}\\s*$")) }
                    .joinToString("\n")
                    .trim()
            }
            .filter { it.isNotEmpty() }
    }

    suspend fun chunksFor(memoryId: Long): List<DocumentChunk> = withContext(Dispatchers.IO) {
        db.query(
            "document_chunks", null, "memory_id = ?", arrayOf(memoryId.toString()),
            null, null, "chunk_index ASC",
        ).use { c ->
            buildList {
                while (c.moveToNext()) add(c.readChunk())
            }
        }
    }

    suspend fun chunkByIndex(memoryId: Long, chunkIndex: Int): DocumentChunk? = withContext(Dispatchers.IO) {
        db.query(
            "document_chunks", null, "memory_id = ? AND chunk_index = ?",
            arrayOf(memoryId.toString(), chunkIndex.toString()), null, null, null, "1",
        ).use { c ->
            if (c.moveToFirst()) c.readChunk() else null
        }
    }

    suspend fun totalChunkCount(memoryId: Long): Int = withContext(Dispatchers.IO) {
        db.rawQuery("SELECT COUNT(*) FROM document_chunks WHERE memory_id = ?", arrayOf(memoryId.toString())).use { c ->
            if (c.moveToFirst()) c.getInt(0) else 0
        }
    }

    suspend fun searchChunksInDocument(memoryId: Long, query: String, limit: Int = 20): List<DocumentChunk> =
        withContext(Dispatchers.IO) {
            val tokens = splitKeywords(query).filter { it.isNotBlank() && it != "*" }
            val all = chunksFor(memoryId)
            if (tokens.isEmpty()) return@withContext all.take(limit)
            all.map { chunk ->
                val lower = chunk.content.lowercase()
                chunk to tokens.count { lower.contains(it.lowercase()) }
            }.filter { it.second > 0 }
                .sortedByDescending { it.second }
                .take(limit)
                .map { it.first }
        }

    suspend fun updateChunk(chunkId: Long, newContent: String): Boolean = withContext(Dispatchers.IO) {
        db.update(
            "document_chunks",
            ContentValues().apply { put("content", newContent) },
            "id = ?", arrayOf(chunkId.toString()),
        ) > 0
    }

    // ---------------------------------------------------------------- Cursor 映射

    private fun Cursor.readMemories(): List<Memory> = buildList {
        while (moveToNext()) add(readMemory())
    }

    private fun Cursor.readMemory(): Memory = Memory(
        id = getLong(getColumnIndexOrThrow("id")),
        uuid = getString(getColumnIndexOrThrow("uuid")),
        title = getString(getColumnIndexOrThrow("title")),
        content = getString(getColumnIndexOrThrow("content")),
        contentType = getString(getColumnIndexOrThrow("content_type")),
        source = getString(getColumnIndexOrThrow("source")),
        credibility = getFloat(getColumnIndexOrThrow("credibility")),
        importance = getFloat(getColumnIndexOrThrow("importance")),
        documentPath = getString(getColumnIndexOrThrow("document_path")),
        isDocumentNode = getInt(getColumnIndexOrThrow("is_document_node")) != 0,
        chunkIndexFilePath = getString(getColumnIndexOrThrow("chunk_index_file_path")),
        folderPath = getString(getColumnIndexOrThrow("folder_path")),
        createdAt = getLong(getColumnIndexOrThrow("created_at")),
        updatedAt = getLong(getColumnIndexOrThrow("updated_at")),
        lastAccessedAt = getLong(getColumnIndexOrThrow("last_accessed_at")),
    )

    private fun Cursor.readLink(): MemoryLink = MemoryLink(
        id = getLong(getColumnIndexOrThrow("id")),
        sourceId = getLong(getColumnIndexOrThrow("source_id")),
        targetId = getLong(getColumnIndexOrThrow("target_id")),
        type = getString(getColumnIndexOrThrow("type")),
        weight = getFloat(getColumnIndexOrThrow("weight")),
        description = getString(getColumnIndexOrThrow("description")),
    )

    private fun Cursor.readChunk(): DocumentChunk = DocumentChunk(
        id = getLong(getColumnIndexOrThrow("id")),
        memoryId = getLong(getColumnIndexOrThrow("memory_id")),
        chunkIndex = getInt(getColumnIndexOrThrow("chunk_index")),
        content = getString(getColumnIndexOrThrow("content")),
    )

    // ---------------------------------------------------------------- SQLiteOpenHelper

    private class Helper(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {
        override fun onConfigure(db: SQLiteDatabase) {
            super.onConfigure(db)
            db.setForeignKeyConstraintsEnabled(true)
        }

        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE memories(
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    uuid TEXT NOT NULL UNIQUE,
                    title TEXT NOT NULL,
                    content TEXT NOT NULL DEFAULT '',
                    content_type TEXT NOT NULL DEFAULT 'text/plain',
                    source TEXT NOT NULL DEFAULT 'unknown',
                    credibility REAL NOT NULL DEFAULT 0.5,
                    importance REAL NOT NULL DEFAULT 0.5,
                    document_path TEXT,
                    is_document_node INTEGER NOT NULL DEFAULT 0,
                    chunk_index_file_path TEXT,
                    folder_path TEXT,
                    created_at INTEGER NOT NULL,
                    updated_at INTEGER NOT NULL,
                    last_accessed_at INTEGER NOT NULL
                )
                """.trimIndent(),
            )
            db.execSQL("CREATE INDEX idx_memories_folder ON memories(folder_path)")
            db.execSQL("CREATE TABLE tags(id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL UNIQUE)")
            db.execSQL(
                """
                CREATE TABLE memory_tags(
                    memory_id INTEGER NOT NULL,
                    tag_id INTEGER NOT NULL,
                    PRIMARY KEY(memory_id, tag_id)
                )
                """.trimIndent(),
            )
            db.execSQL(
                """
                CREATE TABLE links(
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    source_id INTEGER NOT NULL,
                    target_id INTEGER NOT NULL,
                    type TEXT NOT NULL DEFAULT 'related',
                    weight REAL NOT NULL DEFAULT 1.0,
                    description TEXT NOT NULL DEFAULT '',
                    UNIQUE(source_id, target_id, type)
                )
                """.trimIndent(),
            )
            db.execSQL(
                """
                CREATE TABLE document_chunks(
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    memory_id INTEGER NOT NULL,
                    chunk_index INTEGER NOT NULL,
                    content TEXT NOT NULL,
                    UNIQUE(memory_id, chunk_index)
                )
                """.trimIndent(),
            )
            db.execSQL("CREATE TABLE folders(path TEXT PRIMARY KEY, created_at INTEGER NOT NULL)")
            Log.i(TAG, "memory.db created")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            // 第一版没有历史库；后续版本在这里做迁移。
        }
    }
}

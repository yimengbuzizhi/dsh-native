package xin.dshaa.nativeapp.memory

import androidx.compose.ui.graphics.Color

/**
 * 记忆库数据模型（照搬 Operit `data/model/Memory.kt` 的字段，去掉 ObjectBox 注解）。
 *
 * 时间戳用 epoch millis（Operit 用 Date）；标签/属性/关联在 Operit 是 ToMany，
 * 这里由 [MemoryStore] 组装成普通 List，UI/工具拿到的就是完整快照。
 */
data class Memory(
    val id: Long = 0,
    val uuid: String = java.util.UUID.randomUUID().toString(),
    val title: String = "",
    val content: String = "",
    val contentType: String = "text/plain",
    val source: String = "unknown",
    val credibility: Float = 0.5f,
    val importance: Float = 0.5f,
    val documentPath: String? = null,
    val isDocumentNode: Boolean = false,
    val chunkIndexFilePath: String? = null,
    val folderPath: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val lastAccessedAt: Long = System.currentTimeMillis(),
    val tags: List<String> = emptyList(),
)

/** 记忆标签（Operit MemoryTag 的 name；层级 parent 未搬，鲸歌第一版不用）。 */
data class MemoryTag(val id: Long = 0, val name: String = "")

/** 记忆关联（Operit MemoryLink），source/target 用 memory.id。 */
data class MemoryLink(
    val id: Long = 0,
    val sourceId: Long = 0,
    val targetId: Long = 0,
    val type: String = "related",
    val weight: Float = 1.0f,
    val description: String = "",
)

/** 文档记忆的内容块（Operit DocumentChunk，1-based 显示、0-based 存储）。 */
data class DocumentChunk(
    val id: Long = 0,
    val memoryId: Long = 0,
    val chunkIndex: Int = 0,
    val content: String = "",
)

/** 图谱（Operit Graph）。节点 id = memory.uuid，边 id = link.id。 */
data class MemoryGraph(
    val nodes: List<MemoryNode> = emptyList(),
    val edges: List<MemoryEdge> = emptyList(),
) {
    val isEmpty: Boolean get() = nodes.isEmpty()
}

data class MemoryNode(
    val id: String,
    val label: String,
    val color: Color = Color.LightGray,
    val metadata: Map<String, String> = emptyMap(),
)

data class MemoryEdge(
    val id: Long,
    val sourceId: String,
    val targetId: String,
    val label: String? = null,
    val weight: Float = 1.0f,
    val metadata: Map<String, String> = emptyMap(),
    /** 两端不在同一个文件夹（Operit 用虚线画）。 */
    val isCrossFolderLink: Boolean = false,
)

/** 搜索评分模式（Operit MemoryScoreMode）。鲸歌无向量检索，semantic 权重不生效。 */
enum class MemoryScoreMode(val wire: String) {
    BALANCED("balanced"),
    KEYWORD_ONLY("keyword_only"),
    SEMANTIC_ONLY("semantic_only");

    companion object {
        fun fromWire(value: String?): MemoryScoreMode =
            entries.firstOrNull { it.wire == value } ?: BALANCED
    }
}

/** 记忆库显示用的「未分类」文案（Operit 是 R.string.memory_uncategorized）。 */
const val UNCATEGORIZED = "未分类"

/** 文件夹路径规范化（照搬 Operit `normalizeFolderPath`）：反斜杠转正斜杠、去空白、去空段。 */
fun normalizeFolderPath(folderPath: String?): String? {
    val raw = folderPath?.trim() ?: return null
    if (raw.isBlank()) return null
    val parts = raw.replace('\\', '/')
        .split('/')
        .map { it.trim() }
        .filter { it.isNotBlank() }
    return parts.takeIf { it.isNotEmpty() }?.joinToString("/")
}

/** 显示用：null → 未分类。 */
fun folderLabel(folderPath: String?): String = normalizeFolderPath(folderPath) ?: UNCATEGORIZED

// GraphVisualizer 是从 Operit 逐字节搬来的，里面用 Graph/Node/Edge；这里用别名接上，
// 避免为了改名去动那 1200 行绘制代码（改名容易漏、还会引入无关 diff）。
typealias Graph = MemoryGraph
typealias Node = MemoryNode
typealias Edge = MemoryEdge

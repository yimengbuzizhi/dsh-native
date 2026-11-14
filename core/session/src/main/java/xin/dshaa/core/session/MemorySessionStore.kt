package xin.dshaa.core.session

import xin.dshaa.core.model.BlockType
import xin.dshaa.core.model.MessageRole
import xin.dshaa.core.model.SessionEvent
import xin.dshaa.core.model.SessionHeader
import xin.dshaa.core.model.SessionSummary
import java.io.File
import java.util.UUID

/**
 * M84-C2：会话存储的**内存实现**（`id = "memory"`）。
 *
 * 用途：
 * - JVM 单测里换掉文件实现，跑完整的 create / list / open / rename / trash / restore / fork 流程，
 *   证明 `AppViewModel` / 引擎依赖的是 `SessionStore` 而不是写死 `SessionRepository`；
 * - debug 包真机验收（`--es dsh_session_store memory`）——切到内存后新建的会话不落文件，
 *   重启后列表清空；切回 `file` 原来的磁盘会话原样回来。
 *
 * 关键点：事件并不写文件。[MemorySession] 覆盖 `SessionSession.persist()`，`appendRaw` 的
 * seq / events 语义保持不变（ToolEngine、GoalStore 等完全无感）；`root` 只用于展示/兼容。
 */
class MemorySessionStore(override val root: File = File("memory-sessions")) : SessionStore {

    override val id: String = SessionStores.MEMORY

    /** 活着的会话：id → 会话对象（同一对象反复 load 拿到的就是它）。 */
    private val sessions = LinkedHashMap<String, SessionSession>()

    /** 回收站：dirName → (条目, 会话对象)。 */
    private val trash = LinkedHashMap<String, Pair<TrashedSession, SessionSession>>()

    @Synchronized
    override fun list(): List<SessionSummary> =
        sessions.values.map { summarize(it) }.sortedByDescending { it.updatedAt }

    @Synchronized
    override fun load(id: String): SessionSession? = sessions[id]

    @Synchronized
    override fun create(
        cwd: String,
        model: String,
        agentPreset: String,
        parentSession: String?,
        isSeeded: Boolean,
        origin: String?,
        delegationDepth: Int,
    ): SessionSession {
        val header = SessionHeader(
            version = 4,
            id = UUID.randomUUID().toString(),
            createdAt = System.currentTimeMillis(),
            cwd = cwd,
            agentPreset = agentPreset,
            parentSession = parentSession,
            isSeeded = isSeeded,
            origin = origin,
            delegationDepth = delegationDepth,
        )
        val session = MemorySession(header)
        sessions[header.id] = session
        return session
    }

    /** 内存实现没有文件；保留接口给备份/导入的调用方做「找不到」兜底。 */
    override fun findFile(id: String): File? = null

    @Synchronized
    override fun trash(id: String): Boolean {
        val session = sessions.remove(id) ?: return false
        val now = System.currentTimeMillis()
        val dirName = "$id-$now"
        trash[dirName] = TrashedSession(
            dirName = dirName,
            id = id,
            title = summarize(session).title.ifBlank { "已删除的会话" },
            deletedAt = now,
            bytes = 0,
        ) to session
        return true
    }

    @Synchronized
    override fun trashWorkspace(cwd: String): Boolean {
        val doomed = sessions.values.filter { it.header.cwd == cwd }.map { it.header.id }
        doomed.forEach { trash(it) }
        return true
    }

    @Synchronized
    override fun listTrash(): List<TrashedSession> =
        trash.values.map { it.first }.sortedByDescending { it.deletedAt }

    @Synchronized
    override fun restoreTrashed(dirName: String): Boolean {
        val (entry, session) = trash[dirName] ?: return false
        if (sessions.containsKey(entry.id)) return false
        sessions[entry.id] = session
        trash.remove(dirName)
        return true
    }

    @Synchronized
    override fun purgeTrashed(dirName: String): Boolean = trash.remove(dirName) != null

    @Synchronized
    override fun purgeAllTrash(): Int {
        val count = trash.size
        trash.clear()
        return count
    }

    @Synchronized
    override fun purge(id: String): Boolean {
        if (sessions.remove(id) != null) return true
        val entry = trash.entries.firstOrNull { it.value.first.id == id } ?: return false
        return trash.remove(entry.key) != null
    }

    @Synchronized
    override fun rename(id: String, title: String): Boolean {
        val session = sessions[id] ?: return false
        session.appendTitle(title.take(80))
        return true
    }

    @Synchronized
    override fun fork(id: String, throughSeq: Long, cleanTurnBoundary: Boolean, title: String?): SessionSession? {
        val source = sessions[id] ?: return null
        val events = source.events
        var lastIndex = events.indexOfLast { (it.seq ?: -1) <= throughSeq }
        if (lastIndex < 0) return null
        if (cleanTurnBoundary) {
            val lastTurnStart = events.subList(0, lastIndex + 1).indexOfLast { it.type == "turn/start" }
            if (lastTurnStart >= 0) {
                val turnEnd = events.subList(lastTurnStart, events.size).indexOfFirst { it.type == "turn/end" }
                lastIndex = if (turnEnd >= 0) lastTurnStart + turnEnd else lastTurnStart - 1
            }
        }
        if (lastIndex < 0) return null
        val header = source.header.copy(id = UUID.randomUUID().toString(), createdAt = System.currentTimeMillis())
        val child = MemorySession(header, events.subList(0, lastIndex + 1).toList())
        sessions[header.id] = child
        if (!title.isNullOrBlank()) child.appendTitle(title.take(80))
        return child
    }

    private fun summarize(session: SessionSession): SessionSummary {
        val events = session.events
        val messages = SessionCodec.surfaceOf(events)
        val title = events.mapNotNull { SessionCodec.titleOf(it) }.lastOrNull()
            ?: messages.firstOrNull { it.role == MessageRole.USER }?.text.orEmpty()
        val lastText = messages.lastOrNull { message ->
            message.content.any { it.type == BlockType.TEXT && it.text.isNotBlank() }
        }
        val preview = lastText?.content
            ?.filter { it.type == BlockType.TEXT }
            ?.joinToString(" ") { it.text }
            ?.replace(Regex("\\[([^\\]]+)]\\([^)]+\\)"), "$1")
            ?.replace(Regex("[`*_#>|]"), "")
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
            ?.take(80)
            .orEmpty()
        return SessionSummary(
            id = session.header.id,
            title = title.take(40).ifBlank { "新会话" },
            preview = preview,
            createdAt = session.header.createdAt,
            updatedAt = events.mapNotNull { it.time }.maxOrNull() ?: session.header.createdAt,
            messageCount = messages.size,
            cwd = session.header.cwd,
            path = "memory://${session.header.id}",
            parentSession = session.header.parentSession,
            delegationDepth = session.header.delegationDepth,
            origin = session.header.origin,
            isSeeded = session.header.isSeeded,
            lastStopReason = events.lastOrNull { it.type == "turn/end" }?.let { SessionCodec.stopReasonOf(it) },
        )
    }
}

/**
 * 事件只进内存的会话：覆盖 [SessionSession.persist] 为空实现。
 * [SessionSession.file] 只是占位（没有任何代码会读写它；`root`/备份路径走 store 自己）。
 */
private class MemorySession(
    header: SessionHeader,
    initialEvents: List<SessionEvent> = emptyList(),
) : SessionSession(File("memory-session-${header.id}"), header, initialEvents) {
    override fun persist(line: String) = Unit
    override fun flushDurable() = Unit
}

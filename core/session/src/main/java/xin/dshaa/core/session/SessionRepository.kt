package xin.dshaa.core.session

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import xin.dshaa.core.model.BlockType
import xin.dshaa.core.model.ContentBlock
import xin.dshaa.core.model.ImageOffloadTarget
import xin.dshaa.core.model.ImageRef
import xin.dshaa.core.model.MessageRole
import xin.dshaa.core.model.SessionEvent
import xin.dshaa.core.model.SessionHeader
import xin.dshaa.core.model.SessionMessage
import xin.dshaa.core.model.SessionSummary
import xin.dshaa.core.model.ToolCall
import xin.dshaa.core.model.toContentBlock
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/** 工作区目录名：与上游一致，`D:\FBD` → `--D-FBD--`。 */
object WorkspaceSlug {
    fun of(cwd: String): String {
        if (cwd.isBlank()) return "--default--"
        val body = cwd.replace(Regex("[^A-Za-z0-9]+"), "-").trim('-')
        return "--$body--"
    }
}

/**
 * 回收站里的一条（M79，用户 2026-11-12「到现在为止还不能彻底删除会话」）。
 * 目录名是 `<id>-<删除时间戳>`，[id] 从会话头里读，[title] 解不出来时退化成「已删除的会话」。
 *
 * M84-C2 从 `SessionRepository.TrashedSession` 提到顶层：它现在是
 * [SessionStore] 契约的一部分，文件实现与内存实现返回同一种条目。
 */
data class TrashedSession(
    val dirName: String,
    val id: String,
    val title: String,
    /** 删除时间（目录名后缀；解析不出来时用目录 mtime）。 */
    val deletedAt: Long,
    /** 整个会话目录的字节数，用于「彻底删除」时告诉用户释放了多少。 */
    val bytes: Long,
)

/**
 * 会话仓库：负责目录布局、读写、投影。
 *
 * 布局与上游一致：`<root>/<workspace-slug>/<session-id>/session.v4.jsonl.zstd`
 * 这样把旧版的会话目录整个拷进来就能直接用（契约 #6）。
 */
class SessionRepository(val root: File) {

    private val decoder = SessionCodec

    fun list(): List<SessionSummary> {
        if (!root.isDirectory) return emptyList()
        val out = ArrayList<SessionSummary>()
        root.listFiles()?.filter { it.isDirectory && it.name != TRASH_DIR }?.forEach { ws ->
            ws.listFiles()?.filter { it.isDirectory }?.forEach { dir ->
                val file = sessionFile(dir)
                if (file.isFile) {
                    out += summarizeCached(file, ws.name)
                }
            }
        }
        return out.sortedByDescending { it.updatedAt }
    }

    /**
     * 摘要缓存：每条消息追加都会改文件 mtime，`list()` 又是每次发完消息都要刷的，
     * 全量解压 41 条大会话会明显卡。这里按 (路径, mtime, size) 缓存结果；
     * 真正的「只读首帧」优化等 ZstdFrames 支持按帧跳读再做（见 docs/17 §五）。
     */
    private val summaryCache = object : LinkedHashMap<String, Pair<Long, Pair<Long, SessionSummary>>>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<Long, Pair<Long, SessionSummary>>>?): Boolean =
            size > 64
    }

    private fun summarizeCached(file: File, workspace: String): SessionSummary {
        val key = file.absolutePath
        val mtime = file.lastModified()
        val size = file.length()
        synchronized(summaryCache) {
            summaryCache[key]?.let { (cachedMtime, rest) ->
                val (cachedSize, summary) = rest
                if (cachedMtime == mtime && cachedSize == size) return summary
            }
            val summary = summarize(file, workspace)
            summaryCache[key] = mtime to (size to summary)
            return summary
        }
    }

    fun load(id: String): SessionSession? {
        val file = findFile(id) ?: return null
        return runCatching {
            val decoded = decoder.decode(file.readBytes())
            SessionSession(file, decoded.header, decoded.events, readOnly = isImported(file))
        }.getOrNull()
    }

    /** 旧版备份导入的会话：目录里有 `imported.json` 侧车 → 只读。 */
    fun isImported(file: File): Boolean =
        file.parentFile?.let { File(it, IMPORTED_SIDECAR).isFile } == true

    fun create(
        cwd: String,
        model: String = "deepseek-chat",
        agentPreset: String = "standard",
        /** 子代理会话：直接父会话 id（v4 header.parentSession）。 */
        parentSession: String? = null,
        /** fork 出来的子代理：继承父会话的已完成轮次（seed 边界）。 */
        isSeeded: Boolean = false,
        /** `subagent`（子代理）等产品来源；普通会话为 null。 */
        origin: String? = null,
        /** 委托深度（根 = 0，子代理 = 父 + 1；上游持久化它以便 resume 后仍受上限约束）。 */
        delegationDepth: Int = 0,
    ): SessionSession {
        val id = UUID.randomUUID().toString()
        val dir = File(File(root, WorkspaceSlug.of(cwd)), id)
        dir.mkdirs()
        val header = SessionHeader(
            version = 4,
            id = id,
            createdAt = System.currentTimeMillis(),
            cwd = cwd,
            agentPreset = agentPreset,
            parentSession = parentSession,
            isSeeded = isSeeded,
            origin = origin,
            delegationDepth = delegationDepth,
        )
        val file = sessionFile(dir)
        FileOutputStream(file, false).use { out ->
            out.write(ZstdFrames.compressFrame(decoder.encodeHeader(header) + "\n"))
        }
        return SessionSession(file, header, emptyList())
    }

    fun findFile(id: String): File? {
        root.listFiles()?.filter { it.isDirectory && it.name != TRASH_DIR }?.forEach { ws ->
            ws.listFiles()?.filter { it.isDirectory }?.forEach { dir ->
                if (dir.name == id) {
                    val f = sessionFile(dir)
                    if (f.isFile) return f
                }
            }
        }
        return null
    }

    /**
     * 删除会话：**移动到 `.trash/`，不真删**（用户对旧数据的要求是「一条都不能删」；
     * 这里做的是 App 自己创建的会话，仍然保守处理，可从 .trash 手工恢复）。
     */
    fun trash(id: String): Boolean {
        val file = findFile(id) ?: return false
        val dir = file.parentFile ?: return false
        val trashDir = File(root, ".trash").apply { mkdirs() }
        val target = File(trashDir, "${id}-${System.currentTimeMillis()}")
        return runCatching { dir.renameTo(target) }.getOrDefault(false)
    }

    /**
     * 删除一个**工作区**名下的全部会话：整个 `<root>/<workspace-slug>/` 移到 `<root>/.trash/`。
     *
     * 只用于 App 自己创建的工作区（设置页删除工作区时调用）；旧版导入的会话不在可删范围，
     * 而且依旧沿用「移到回收站、不真删」的保守语义（用户对旧数据的要求是一条都不能删）。
     */
    fun trashWorkspace(cwd: String): Boolean {
        val dir = File(root, WorkspaceSlug.of(cwd))
        if (!dir.isDirectory) return true // 没有会话就是空工作区，直接算成功
        val trashDir = File(root, TRASH_DIR).apply { mkdirs() }
        val target = File(trashDir, "${dir.name}-${System.currentTimeMillis()}")
        return runCatching { dir.renameTo(target) }.getOrDefault(false)
    }

    /**
     * 回收站目录（`.trash/`），不存在就建。
     */
    private fun trashDir(): File = File(root, TRASH_DIR).apply { mkdirs() }

    /** 列出回收站；解不开头的目录也列出来（id 用目录名），保证用户能删掉它。 */
    fun listTrash(): List<TrashedSession> {
        val dir = File(root, TRASH_DIR)
        if (!dir.isDirectory) return emptyList()
        return dir.listFiles().orEmpty()
            .filter { it.isDirectory }
            .map { entry ->
                val suffixAt = entry.name.lastIndexOf('-')
                val id = if (suffixAt > 0) entry.name.substring(0, suffixAt) else entry.name
                val stamp = if (suffixAt > 0) entry.name.substring(suffixAt + 1).toLongOrNull() else null
                val file = sessionFile(entry)
                val header = if (file.isFile) {
                    runCatching { decoder.decode(file.readBytes()).header }.getOrNull()
                } else {
                    null
                }
                val title = if (file.isFile) {
                    runCatching { summarize(file, "").title }.getOrNull().orEmpty()
                } else {
                    ""
                }
                TrashedSession(
                    dirName = entry.name,
                    id = header?.id ?: id,
                    title = title.ifBlank { "已删除的会话" },
                    deletedAt = stamp ?: entry.lastModified(),
                    bytes = entry.walkTopDown().filter { it.isFile }.sumOf { it.length() },
                )
            }
            .sortedByDescending { it.deletedAt }
    }

    /** 恢复一条回收站会话：按会话头里的 cwd 放回原工作区目录（目标已存在则失败，不覆盖）。 */
    fun restoreTrashed(dirName: String): Boolean {
        val entry = File(root, "$TRASH_DIR/$dirName")
        if (!entry.isDirectory) return false
        val file = sessionFile(entry)
        val header = if (file.isFile) {
            runCatching { decoder.decode(file.readBytes()).header }.getOrNull()
        } else {
            null
        } ?: return false
        val workspaceDir = File(root, WorkspaceSlug.of(header.cwd)).apply { mkdirs() }
        val target = File(workspaceDir, header.id)
        if (target.exists()) return false
        return runCatching { entry.renameTo(target) }.getOrDefault(false)
    }

    /** **彻底删除**一条回收站会话（真删目录，不进回收站）。 */
    fun purgeTrashed(dirName: String): Boolean {
        val entry = File(root, "$TRASH_DIR/$dirName")
        if (!entry.isDirectory) return false
        return runCatching { entry.deleteRecursively() }.getOrDefault(false)
    }

    /** 清空回收站，返回删掉的条数。 */
    fun purgeAllTrash(): Int {
        val dir = File(root, TRASH_DIR)
        val entries = dir.listFiles().orEmpty().filter { it.isDirectory }
        var removed = 0
        entries.forEach { if (runCatching { it.deleteRecursively() }.getOrDefault(false)) removed++ }
        return removed
    }

    /**
     * **彻底删除**一条还活着的会话（M79）：直接删目录，不经过回收站。
     * 与 [trash] 的区别只有这一步——用户明确要「彻底删除」时才调它。
     */
    fun purge(id: String): Boolean {
        val file = findFile(id) ?: return false
        val dir = file.parentFile ?: return false
        val trashed = dir.parentFile?.name == TRASH_DIR
        if (trashed) return purgeTrashed(dir.name)
        return runCatching { dir.deleteRecursively() }.getOrDefault(false)
    }

    /** 重命名：往会话里追加一条 session/title（与自动标题同一事件，后写生效）。 */    fun rename(id: String, title: String): Boolean {
        val file = findFile(id) ?: return false
        return runCatching {
            val decoded = decoder.decode(file.readBytes())
            val session = SessionSession(file, decoded.header, decoded.events)
            session.appendTitle(title.take(80))
            true
        }.getOrDefault(false)
    }

    /**
     * 在已有会话的**前缀**上创建分支（Operit「创建分支 / 重新生成」的 v4 实现）。
     *
     * v4 是 append-only：不能原地截断，所以分支 = 新会话 + 复制前缀事件。
     * [throughSeq] 是目标消息的 seq；[cleanTurnBoundary] 为 true 时向后扩到该 turn 的 `turn/end`，
     * 避免把「半个 turn」（有 tool_use 没有 tool_result）带进新会话。
     * 找不到目标/解码失败返回 null（调用方给用户提示）。
     */
    fun fork(
        id: String,
        throughSeq: Long,
        cleanTurnBoundary: Boolean = true,
        title: String? = null,
    ): SessionSession? {
        val file = findFile(id) ?: return null
        val decoded = runCatching { decoder.decode(file.readBytes()) }.getOrNull() ?: return null
        var lastIndex = decoded.events.indexOfLast { (it.seq ?: -1) <= throughSeq }
        if (lastIndex < 0) return null
        if (cleanTurnBoundary) {
            val lastTurnStart = decoded.events.subList(0, lastIndex + 1).indexOfLast { it.type == "turn/start" }
            if (lastTurnStart >= 0) {
                val turnEnd = decoded.events.subList(lastTurnStart, decoded.events.size)
                    .indexOfFirst { it.type == "turn/end" }
                lastIndex = if (turnEnd >= 0) lastTurnStart + turnEnd else lastTurnStart - 1
            }
        }
        if (lastIndex < 0) return null
        val prefix = decoded.events.subList(0, lastIndex + 1).toList()

        val now = System.currentTimeMillis()
        val newId = UUID.randomUUID().toString()
        val header = decoded.header.copy(id = newId, createdAt = now)
        val dir = File(File(root, WorkspaceSlug.of(header.cwd)), newId).apply { mkdirs() }
        val target = sessionFile(dir)
        FileOutputStream(target, false).use { out ->
            out.write(ZstdFrames.compressFrame(decoder.encodeHeader(header) + "\n"))
            prefix.forEach { event ->
                val line = decoder.encodeEvent(
                    event.type,
                    event.seq ?: 0,
                    event.time ?: now,
                    (event.data as? JsonObject) ?: buildJsonObject { },
                )
                out.write(ZstdFrames.compressFrame(line + "\n"))
            }
        }
        val session = SessionSession(target, header, prefix)
        if (!title.isNullOrBlank()) session.appendTitle(title.take(80))
        return session
    }

    private fun sessionFile(dir: File) = File(dir, "session.v4.jsonl.zstd")

    private companion object {
        const val TRASH_DIR = ".trash"
        const val IMPORTED_SIDECAR = "imported.json"
    }

    private fun summarize(file: File, workspace: String): SessionSummary {
        val imported = isImported(file)
        val fallback = SessionSummary(
            id = file.parentFile?.name.orEmpty(),
            createdAt = file.lastModified(),
            updatedAt = file.lastModified(),
            cwd = workspace,
            path = file.absolutePath,
            legacy = imported,
        )
        return runCatching {
            val decoded = decoder.decode(file.readBytes())
            val messages = decoder.surfaceOf(decoded.events)
            // 标题取**最后一条** session/title（上游语义：后写生效；重命名/LLM 起名都是追加）
            val title = decoded.events.mapNotNull { decoder.titleOf(it) }.lastOrNull()
                ?: messages.firstOrNull { it.role == MessageRole.USER }?.text.orEmpty()
            // 预览只取「最后一条有正文的消息」的 text 块：不要 reasoning（思考过程不是给列表看的），
            // 并去掉 markdown 记号，避免列表里出现 ** / ``` 之类。
            val lastText = messages.lastOrNull { m ->
                m.content.any { it.type == BlockType.TEXT && it.text.isNotBlank() }
            }
            val preview = lastText?.content
                ?.filter { it.type == BlockType.TEXT }
                ?.joinToString(" ") { it.text }
                ?.replace(Regex("\\[([^\\]]+)]\\([^)]+\\)"), "$1") // markdown 链接只留文字
                ?.replace(Regex("[`*_#>|]"), "")
                ?.replace(Regex("\\s+"), " ")
                ?.trim()
                ?.take(80)
                .orEmpty()
            // 只读导入的旧会话按「原始最后事件时间」排序，而不是文件拷贝时间；
            // 否则 9 月的旧对话会全部挤在列表顶部显示「今天」。
            val updatedAt = if (imported) {
                decoded.events.mapNotNull { it.time }.maxOrNull() ?: decoded.header.createdAt
            } else {
                file.lastModified()
            }
            fallback.copy(
                id = decoded.header.id,
                title = title.take(40).ifBlank { "新会话" },
                preview = preview,
                createdAt = decoded.header.createdAt,
                updatedAt = updatedAt,
                messageCount = messages.size,
                cwd = decoded.header.cwd,
                legacy = imported,
                parentSession = decoded.header.parentSession,
                delegationDepth = decoded.header.delegationDepth,
                origin = decoded.header.origin,
                isSeeded = decoded.header.isSeeded,
                // 最后一条 turn/end 的终态：子代理面板重启后也能区分「已完成 / 已停止 / 失败」
                lastStopReason = decoded.events.lastOrNull { it.type == "turn/end" }?.let { decoder.stopReasonOf(it) },
            )
        }.getOrDefault(fallback)
    }
}

/** 一个已打开的会话：可继续 append 事件。 */
open class SessionSession(
    val file: File,
    val header: SessionHeader,
    initialEvents: List<SessionEvent>,
    /** 旧版备份导入的只读副本：App 不允许继续 append。 */
    val readOnly: Boolean = false,
) {
    @Volatile
    var events: List<SessionEvent> = initialEvents
        private set

    /**
     * M84-H：每条事件落库后的同步回调（插件会话投影按事件折叠）。
     *
     * 回调在 append 的调用线程上跑，**实现方必须立刻返回**（插件事件投递是异步入队）；
     * 回调异常被吞掉，不影响落库。
     */
    private val eventListeners = java.util.concurrent.CopyOnWriteArrayList<(SessionEvent) -> Unit>()

    fun addEventListener(listener: (SessionEvent) -> Unit) {
        eventListeners.add(listener)
    }

    fun removeEventListener(listener: (SessionEvent) -> Unit) {
        eventListeners.remove(listener)
    }

    private var seq: Long = (initialEvents.mapNotNull { it.seq }.maxOrNull() ?: -1) + 1

    /**
     * 打开已有会话后续写必须接着原 turn 编号（旧实现从 0 起，重开后第一条会变成 turn 1）。
     * 取已落盘 `turn/start` 的最大 turn；新会话为 0。
     */
    private var turn: Int = initialEvents.mapNotNull { event ->
        if (event.type != "turn/start") null
        else runCatching { event.data?.jsonObject?.get("turn")?.jsonPrimitive?.intOrNull }.getOrNull()
    }.maxOrNull() ?: 0
    private var step: Int = 0

    /**
     * 追加一条事件。`@Synchronized`：同一时刻可能有两条写入路径（turn 的 IO 协程 + 用户
     * 在 /goal 面板触发的 pause/resume），不串行化会抢 seq、交错写 zstd 帧。
     *
     * [surfaceOp]：普通消息写 `"append"`，压缩替换消息写 `{"op":"replace",…}`（M43）；
     * [sourceEventSeqs]：这条事件引用的源事件（压缩替换消息引用 start/summary/被顶掉的消息）。
     */
    @Synchronized
    fun appendRaw(
        type: String,
        data: kotlinx.serialization.json.JsonObject,
        surfaceOp: kotlinx.serialization.json.JsonElement? = null,
        sourceEventSeqs: List<Long>? = null,
    ): Long {
        val mySeq = seq++
        val now = System.currentTimeMillis()
        val line = SessionCodec.encodeEvent(type, mySeq, now, data, surfaceOp, sourceEventSeqs)
        persist(line)
        val appended = SessionEvent(type, mySeq, now, data, surfaceOp)
        events = events + appended
        if (eventListeners.isNotEmpty()) {
            for (listener in eventListeners) {
                try {
                    listener(appended)
                } catch (t: Throwable) {
                    // 监听者（插件投影投递）出问题不能让事件落库失败。
                }
            }
        }
        return mySeq
    }

    /**
     * 把一行事件写进存储。默认实现追加到 [file] 的 zstd 帧流。
     *
     * M84-C2：`SessionStore` 的另一种实现（[MemorySessionStore]）覆盖它把事件留在内存里，
     * 从而证明会话**事件存储**也不是写死文件（`appendRaw` 的 seq/events 语义保持不变）。
     */
    protected open fun persist(line: String) {
        FileOutputStream(file, true).use { out ->
            out.write(ZstdFrames.compressFrame(line + "\n"))
        }
    }

    /** Persisted outbox acknowledgments must follow a durable user/message, not only a closed stream. */
    @Synchronized
    open fun flushDurable() {
        check(!readOnly && file.isFile) { "会话尚未持久化" }
        FileOutputStream(file, true).use { it.fd.sync() }
    }

    fun startTurn(): Int {
        turn += 1
        step = 0
        appendRaw("turn/start", buildJsonObject { put("turn", turn) })
        return turn
    }

    fun startStep(): Int {
        step += 1
        appendRaw("step/start", buildJsonObject { put("turn", turn); put("step", step) })
        return step
    }

    fun endStep() {
        appendRaw("step/end", buildJsonObject { put("turn", turn); put("step", step) })
    }

    fun endTurn(kind: String = "completed") {
        appendRaw(
            "turn/end",
            buildJsonObject {
                put("turn", turn)
                put("reason", buildJsonObject { put("kind", kind) })
            },
        )
    }

    /**
     * 计划模式开关（M18）：`plan/mode {active}`——上游 plan projection 的唯一真相。
     * 用户切换在 idle 点立即落库；轮次进行中在下一个 step 起点落库（docs/35）。
     */
    fun appendPlanMode(active: Boolean): Long =
        appendRaw("plan/mode", buildJsonObject { put("active", active) })

    // ---- 斜杠命令（M21）：上游 `dsh-commands` 的 command/run + command/done ----

    /**
     * 命令开始：`{commandId, name, args?, source:{kind:"user"}}`。
     * `args` 是名称之后的原始输入（**含分隔空白**）；`recordInput=false` 的命令不写。
     */
    fun appendCommandRun(
        commandId: String,
        name: String,
        rawInput: String,
        recordInput: Boolean = true,
    ): Long = appendRaw(
        "command/run",
        buildJsonObject {
            put("commandId", commandId)
            put("name", name)
            if (recordInput) put("args", rawInput)
            put("source", buildJsonObject { put("kind", "user") })
        },
    )

    /**
     * 命令结算：`{commandId, kind, text?, sourceEventSeq?}`。
     * 成功且指向更早一条权威事件时才带 `sourceEventSeq`（与上游 `settle()` 一致）。
     */
    fun appendCommandDone(
        commandId: String,
        kind: String,
        text: String? = null,
        sourceEventSeq: Long? = null,
    ): Long = appendRaw(
        "command/done",
        buildJsonObject {
            put("commandId", commandId)
            put("kind", kind)
            if (text != null) put("text", text)
            if (kind == "success" && sourceEventSeq != null) put("sourceEventSeq", sourceEventSeq)
        },
    )

    /** 记录这次请求用的模型配置；旧版靠它显示模型/provider。 */
    fun appendRequestHeader(
        provider: String,
        model: String,
        reasoningEffort: String? = null,
        maxTokens: Int? = null,
    ): Long = appendRaw(
        "request/header",
        buildJsonObject {
            put(
                "header",
                buildJsonObject {
                    put(
                        "config",
                        buildJsonObject {
                            put("provider", provider)
                            put("model", model)
                            if (reasoningEffort != null) put("reasoningEffort", reasoningEffort)
                            if (maxTokens != null) put("maxTokens", maxTokens)
                        },
                    )
                    put("reason", "initial")
                },
            )
        },
    )

    /**
     * 当前 **open turn** 里所有 user/message 的 source（对齐上游 `turnBoundaryOf` 的
     * `openTurnStartSeq`：只看最后一个 `turn/start` 之后、且 turn 还没收口的事件）。
     * goal 工具的权限判定用它，而不是看模型自述（上游 authority.js 同一做法）。
     */
    fun openTurnUserSources(): List<JsonObject> {
        val all = events
        val start = all.indexOfLast { it.type == "turn/start" }
        if (start < 0) return emptyList()
        val suffix = all.subList(start + 1, all.size)
        if (suffix.any { it.type == "turn/end" }) return emptyList()
        return suffix.mapNotNull { event ->
            if (event.type != "user/message") return@mapNotNull null
            event.data?.jsonObjectOrNull()?.get("source")?.jsonObjectOrNull()
        }
    }

    /**
     * 追加一条 user 消息。[source] 为 null 时是普通人类消息（`{kind:"user"}`）；
     * goal 轮传 `{kind:"goal", goalId, revision, round}`（上游用 source 归属轮次）。
     */
    fun appendUserMessage(text: String, source: JsonObject? = null): Long =
        appendUserMessageTexts(listOf(text), source)

    /**
     * 多 text 块的 user 消息（子代理初始任务 = prompt + 返回指导；结算通知 = 摘要 + closing message）。
     * 上游一个 user/message 可以有多个 text 块，不能先 join 成一条。
     */
    fun appendUserMessageTexts(texts: List<String>, source: JsonObject? = null): Long = appendRaw(
        "user/message",
        buildJsonObject {
            put("content", buildJsonArray { texts.forEach { add(textBlock(it)) } })
            put("source", source ?: buildJsonObject { put("kind", "user") })
            put("role", "user")
            put("id", UUID.randomUUID().toString())
        },
        surfaceOp = JsonPrimitive("append"),
    )

    /**
     * 带图片的用户消息（聊天输入条附件，docs/42）：
     * `content = [text…, image{attachment}…]`——与上游 user 消息的块顺序一致（文本在前、图片随后），
     * 空文本块不落库（只发图片时 content 里只有 image 块）。
     *
     * 附件本体不进 JSONL：image 块只存 `attachmentId` 等引用字段（对齐上游附件存储约定）。
     */
    fun appendUserMessageContent(
        texts: List<String>,
        images: List<ImageRef> = emptyList(),
        source: JsonObject? = null,
    ): Long = appendRaw(
        "user/message",
        buildJsonObject {
            put(
                "content",
                buildJsonArray {
                    texts.filter { it.isNotEmpty() }.forEach { add(textBlock(it)) }
                    images.forEach { add(it.toContentBlock().raw!!) }
                },
            )
            put("source", source ?: buildJsonObject { put("kind", "user") })
            put("role", "user")
            put("id", UUID.randomUUID().toString())
        },
        surfaceOp = JsonPrimitive("append"),
    )

    fun appendAssistantMessage(
        text: String,
        reasoning: String? = null,
        signature: String? = null,
        toolCalls: List<ToolCall> = emptyList(),
        provider: String = "deepseek",
        model: String = "deepseek-flash",
    ): Long {
        val content: JsonArray = buildJsonArray {
            if (!reasoning.isNullOrBlank()) {
                add(
                    buildJsonObject {
                        put("type", "reasoning")
                        put("text", reasoning)
                        // Anthropic thinking signature：多轮必须原样回传（契约 #1）。
                        // 上游 v4 把签名放在 source.replayState 里；我们直接把签名挂在 reasoning 块上，
                        // 读取方忽略未知字段即可，不破坏 v4 结构。
                        if (!signature.isNullOrBlank()) put("signature", signature)
                    },
                )
            }
            if (text.isNotBlank()) add(textBlock(text))
            toolCalls.forEach { call ->
                add(
                    buildJsonObject {
                        put("type", "tool-call")
                        put("id", call.id)
                        put("name", call.name)
                        put("arguments", call.arguments)
                    },
                )
            }
        }
        return appendRaw(
            "assistant/message",
            buildJsonObject {
                put("turn", turn)
                put("step", step)
                put(
                    "message",
                    buildJsonObject {
                        put("role", "assistant")
                        put("content", content)
                        put(
                            "source",
                            buildJsonObject {
                                put("kind", "model")
                                put("provider", provider)
                                put("model", model)
                            },
                        )
                    },
                )
                put(
                    "stream",
                    buildJsonArray {
                        add(
                            buildJsonObject {
                                put("type", "chunk")
                                put("time", System.currentTimeMillis())
                                put(
                                    "chunk",
                                    buildJsonObject {
                                        put("type", "finish")
                                        put("reason", buildJsonObject { put("kind", "completed") })
                                    },
                                )
                            },
                        )
                    },
                )
            },
            surfaceOp = JsonPrimitive("append"),
        )
    }

    fun appendToolCall(call: ToolCall): Long = appendRaw(
        "tool/call",
        buildJsonObject {
            put("turn", turn)
            put("step", step)
            put("callId", call.id)
            put("name", call.name)
            put("arguments", call.arguments)
        },
    )

    fun appendToolResult(callId: String, text: String, isError: Boolean, images: List<ImageRef> = emptyList()): Long = appendRaw(
        "tool/result",
        buildJsonObject {
            put("turn", turn)
            put("step", step)
            put(
                "message",
                buildJsonObject {
                    put("role", "tool")
                    put("source", buildJsonObject { put("kind", "tool"); put("callId", callId) })
                    put("toolCallId", callId)
                    put(
                        "content",
                        buildJsonArray {
                            add(textBlock(text))
                            images.forEach { add(it.toContentBlock().raw!!) }
                        },
                    )
                    put("isError", isError)
                    put("id", UUID.randomUUID().toString())
                },
            )
        },
        surfaceOp = JsonPrimitive("append"),
    )

    // ---- 上下文压缩（M43）：上游 `dsh-compaction-basic` 的 bracket 事务 ----
    //
    // 事件顺序（桌面实测 session-9922ad59）：compaction/start → compaction/summary →
    // 替换消息 user/message(surfaceOp=replace) → compaction/end → command/done。
    // 记账事件与 command 事件一样**不进模型历史**（projection 只认四条消息事件）。

    /** `compaction/start`：`{compactionId, sourceCommandId?, turn}`；手工压缩 turn=null，自动压缩是所属轮次。 */
    fun appendCompactionStart(compactionId: String, sourceCommandId: String?, turn: Int? = null): Long =
        appendRaw("compaction/start", compactionLifecycle(compactionId, sourceCommandId, turn))

    /** `compaction/end`：同 start；失败时多一个 `error` 文案（上游只在失败分支写）。 */
    fun appendCompactionEnd(
        compactionId: String,
        sourceCommandId: String?,
        error: String? = null,
        turn: Int? = null,
    ): Long {
        val data = buildJsonObject {
            put("compactionId", compactionId)
            if (sourceCommandId != null) put("sourceCommandId", sourceCommandId)
            put("turn", turn?.let { JsonPrimitive(it) } ?: JsonNull)
            if (error != null) put("error", error)
        }
        return appendRaw("compaction/end", data)
    }

    /**
     * `compaction/summary`：摘要账本。字段与桌面逐字同形（M43 抓到的样本）：
     * `{compactionId, sourceCommandId?, summary:[text…], rawOutput, llmStreamCall:true,
     *   shadowedRange:{start,end}, shadowedSeqs:[…], shadowedTokenCount, provider, model,
     *   maxTokens?, usage?}`
     */
    fun appendCompactionSummary(
        compactionId: String,
        sourceCommandId: String?,
        summary: String,
        rawOutput: JsonArray,
        shadowedRange: LongRange,
        shadowedSeqs: List<Long>,
        shadowedTokenCount: Int,
        provider: String,
        model: String,
        maxTokens: Int?,
        usage: JsonObject?,
        llmStreamCall: Boolean = true,
    ): Long {
        val data = buildJsonObject {
            put("compactionId", compactionId)
            if (sourceCommandId != null) put("sourceCommandId", sourceCommandId)
            put("summary", buildJsonArray { add(textBlock(summary)) })
            put("rawOutput", rawOutput)
            if (llmStreamCall) put("llmStreamCall", true)
            put(
                "shadowedRange",
                buildJsonObject {
                    put("start", shadowedRange.first)
                    put("end", shadowedRange.last)
                },
            )
            put("shadowedSeqs", buildJsonArray { shadowedSeqs.forEach { add(JsonPrimitive(it)) } })
            put("shadowedTokenCount", shadowedTokenCount)
            put("provider", provider)
            put("model", model)
            if (maxTokens != null) put("maxTokens", maxTokens)
            if (usage != null) put("usage", usage)
        }
        return appendRaw("compaction/summary", data)
    }

    /**
     * `compaction/prune`（M46，上游 `pruneSession()` 的 shadow-price 协议）：紧挨着被剪掉的
     * 工具结果记录之前落一条，`shadowedRange` / `shadowedSeqs` / `shadowedTokenCount` 与
     * `compaction/summary` 同形，纯读取方据此知道哪条结果被顶掉了、该减多少令牌。
     */
    fun appendCompactionPrune(shadowedSeq: Long, shadowedTokenCount: Int): Long = appendRaw(
        "compaction/prune",
        buildJsonObject {
            put(
                "shadowedRange",
                buildJsonObject {
                    put("start", shadowedSeq)
                    put("end", shadowedSeq)
                },
            )
            put("shadowedSeqs", buildJsonArray { add(JsonPrimitive(shadowedSeq)) })
            put("shadowedTokenCount", shadowedTokenCount)
        },
    )

    /**
     * 工具结果修剪的替换事件（M46，上游 `pruneSession()`）：新 `tool/result` 顶掉
     * [originalSeq] 那一条（`surfaceOp:{op:"replace",startSeq,endSeq}` 同一条），
     * 只换 `message.content`，`turn` / `step` / `source` / `toolCallId` / `isError` / `id`
     * 全部原样；`sourceEventSeqs=[originalSeq]` 让回放能找回原始输入。
     *
     * @return 新事件 seq；原始事件不存在（或不是 tool/result）时返回 null，一个字节都不写。
     */
    fun appendToolResultPruned(originalSeq: Long, content: List<ContentBlock>): Long? {
        val original = events.lastOrNull { it.seq == originalSeq && it.type == "tool/result" } ?: return null
        val data = original.data?.jsonObjectOrNull() ?: return null
        val message = data["message"]?.jsonObjectOrNull() ?: return null
        val newMessage = buildJsonObject {
            message.forEach { (key, value) -> if (key != "content") put(key, value) }
            put("content", buildJsonArray { content.forEach { add(blockJson(it)) } })
        }
        val newData = buildJsonObject {
            data.forEach { (key, value) -> if (key != "message") put(key, value) }
            put("message", newMessage)
        }
        return appendRaw(
            "tool/result",
            newData,
            surfaceOp = buildJsonObject {
                put("op", "replace")
                put("startSeq", originalSeq)
                put("endSeq", originalSeq)
            },
            sourceEventSeqs = listOf(originalSeq),
        )
    }

    /**
     * 图片卸载（M47，上游 `offloadOldestImages()` 的 `session.append("image/offload", {targets})`）：
     * 选中会在后续请求里换成占位文本的 image 出现位置。事件不改消息、不改会话面节点身份，
     * 只靠投影打 `offloaded` 标记；预算够大或换路由也不会自动恢复（与上游一致）。
     *
     * @return 事件 seq；[targets] 为空时返回 null，一个字节都不写。
     */
    fun appendImageOffload(targets: List<ImageOffloadTarget>): Long? {
        if (targets.isEmpty()) return null
        return appendRaw(
            "image/offload",
            buildJsonObject {
                put(
                    "targets",
                    buildJsonArray {
                        targets.forEach { target ->
                            add(
                                buildJsonObject {
                                    put("seq", target.seq)
                                    put("imageIndexes", buildJsonArray { target.imageIndexes.forEach { add(JsonPrimitive(it)) } })
                                },
                            )
                        }
                    },
                )
            },
        )
    }

    /** 替换消息：`source.kind:"compact-checkpoint"` 的 user/message，
     * `surfaceOp:{op:"replace",startSeq,endSeq}` 顶掉 [startSeq]..[endSeq] 的旧历史，
     * `sourceEventSeqs` 记 [compaction/start, compaction/summary, …被顶掉的消息]。
     */
    fun appendCompactionCheckpoint(
        compactionId: String,
        sourceCommandId: String?,
        texts: List<String>,
        startSeq: Long,
        endSeq: Long,
        sourceEventSeqs: List<Long>,
    ): Long = appendRaw(
        "user/message",
        buildJsonObject {
            put("content", buildJsonArray { texts.forEach { add(textBlock(it)) } })
            put(
                "source",
                buildJsonObject {
                    put("kind", "compact-checkpoint")
                    put("compactionId", compactionId)
                    if (sourceCommandId != null) put("sourceCommandId", sourceCommandId)
                },
            )
            put("role", "user")
            put("id", UUID.randomUUID().toString())
        },
        surfaceOp = buildJsonObject {
            put("op", "replace")
            put("startSeq", startSeq)
            put("endSeq", endSeq)
        },
        sourceEventSeqs = sourceEventSeqs,
    )

    private fun compactionLifecycle(compactionId: String, sourceCommandId: String?, turn: Int?) = buildJsonObject {
        put("compactionId", compactionId)
        if (sourceCommandId != null) put("sourceCommandId", sourceCommandId)
        put("turn", turn?.let { JsonPrimitive(it) } ?: JsonNull)
    }

    fun appendTitle(title: String, messageSeqs: List<Long> = emptyList()) = appendRaw(
        "session/title",
        buildJsonObject {
            put("title", title)
            put("messageSeqs", buildJsonArray { messageSeqs.forEach { add(JsonPrimitive(it)) } })
            put("source", buildJsonObject { put("kind", "fallback") })
        },
    )

    private fun textBlock(text: String) = buildJsonObject {
        put("type", "text")
        put("text", text)
    }

    /**
     * 内容块 → v4 JSON（修剪替换用）：富块带原始 JSON 就原样回写，
     * 文本/思考/工具调用按解析时的形状重建（与 `SessionCodec.parseBlock` 互逆）。
     */
    private fun blockJson(block: ContentBlock): JsonObject = block.raw ?: when (block.type) {
        BlockType.TEXT -> textBlock(block.text)
        BlockType.REASONING -> buildJsonObject {
            put("type", "reasoning")
            put("text", block.text)
            if (block.signature.isNotEmpty()) put("signature", block.signature)
        }
        BlockType.TOOL_CALL -> buildJsonObject {
            put("type", "tool-call")
            put("id", block.toolCallId.orEmpty())
            put("name", block.name.orEmpty())
            put("arguments", block.arguments)
        }
        else -> textBlock(block.text)
    }

    companion object {
        fun toRuntime(message: SessionMessage): SessionMessage = message
    }
}

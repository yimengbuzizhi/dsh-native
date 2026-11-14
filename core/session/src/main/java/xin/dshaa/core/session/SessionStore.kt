package xin.dshaa.core.session

import xin.dshaa.core.model.SessionSummary
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * M84-C2：**可替换的会话存储**（`docs/115 §C` 的最后一块）。
 *
 * 之前 `AppViewModel` 直接 `new SessionRepository(filesDir/sessions)`，列表/打开/回收站/分支
 * 全绑死在文件目录实现上。现在宿主只依赖本接口，按 id 从 [SessionStores] 取实现：
 * - `file`（默认，[FileSessionRepository]）：就是原来的 `SessionRepository`，目录布局/
 *   zstd 帧/回收站语义一个字节都不变；
 * - `memory`（[MemorySessionStore]）：事件只留在内存里，用来证明「换实现能跑」；
 * - 插件以后可以注册自己的 id（远端同步、加密库……），宿主换实现不需要改 UI。
 *
 * 契约只覆盖 App 实际用到的公开方法（`AppViewModel` 的 `repo.*` 调用点）。
 * 注意 [SessionSession] 仍是具体类：文件实现的事件编码/落盘在它里面，
 * 内存实现通过覆盖 `persist()` 把事件留在内存——所以「事件字节可换」也已经成立；
 * 后续若要把 `SessionSession` 也做成接口，属于 D/E 阶段的事。
 */
interface SessionStore {
    /** 实现 id（`file` / `memory` / 插件自定义）。 */
    val id: String

    /**
     * M85-E：只读存储（第三方 storage/v1 导出器）。只读实现只保证 [list] / [load]；
     * 宿主据此禁用新建/写入，而不是把只读实现当可写仓库用。
     */
    val readOnly: Boolean get() = false

    /** 存储根目录（备份导出、设置页展示用；内存实现给一个占位路径）。 */
    val root: File

    fun list(): List<SessionSummary>

    fun load(id: String): SessionSession?

    fun create(
        cwd: String,
        model: String = "deepseek-chat",
        agentPreset: String = "standard",
        parentSession: String? = null,
        isSeeded: Boolean = false,
        origin: String? = null,
        delegationDepth: Int = 0,
    ): SessionSession

    fun findFile(id: String): File?

    /** 删除会话（默认语义：进回收站，不真删）。 */
    fun trash(id: String): Boolean

    fun trashWorkspace(cwd: String): Boolean

    fun listTrash(): List<TrashedSession>

    fun restoreTrashed(dirName: String): Boolean

    fun purgeTrashed(dirName: String): Boolean

    fun purgeAllTrash(): Int

    fun purge(id: String): Boolean

    fun rename(id: String, title: String): Boolean

    fun fork(
        id: String,
        throughSeq: Long,
        cleanTurnBoundary: Boolean = true,
        title: String? = null,
    ): SessionSession?
}

/**
 * 默认实现：把调用原样转发给 [SessionRepository]。
 * 这一层故意不写任何逻辑——文件布局/回收站/摘要缓存继续由原实现负责，
 * 保证换到接口后行为与 M84-C1 之前逐字节一致。
 */
class FileSessionRepository(override val root: File) : SessionStore {
    private val repo = SessionRepository(root)

    override val id: String = SessionStores.FILE

    override fun list(): List<SessionSummary> = repo.list()

    override fun load(id: String): SessionSession? = repo.load(id)

    override fun create(
        cwd: String,
        model: String,
        agentPreset: String,
        parentSession: String?,
        isSeeded: Boolean,
        origin: String?,
        delegationDepth: Int,
    ): SessionSession = repo.create(
        cwd = cwd,
        model = model,
        agentPreset = agentPreset,
        parentSession = parentSession,
        isSeeded = isSeeded,
        origin = origin,
        delegationDepth = delegationDepth,
    )

    override fun findFile(id: String): File? = repo.findFile(id)

    override fun trash(id: String): Boolean = repo.trash(id)

    override fun trashWorkspace(cwd: String): Boolean = repo.trashWorkspace(cwd)

    override fun listTrash(): List<TrashedSession> = repo.listTrash()

    override fun restoreTrashed(dirName: String): Boolean = repo.restoreTrashed(dirName)

    override fun purgeTrashed(dirName: String): Boolean = repo.purgeTrashed(dirName)

    override fun purgeAllTrash(): Int = repo.purgeAllTrash()

    override fun purge(id: String): Boolean = repo.purge(id)

    override fun rename(id: String, title: String): Boolean = repo.rename(id, title)

    override fun fork(id: String, throughSeq: Long, cleanTurnBoundary: Boolean, title: String?): SessionSession? =
        repo.fork(id, throughSeq, cleanTurnBoundary, title)
}

/**
 * 会话存储注册表（与 `LlmProviders` / `PromptProviders` 同一套约定）：
 * - 内置 `file`；`create(root, requested)` 按 id 取工厂，未知 id 回落到文件实现；
 * - 同 id 注册 = 覆盖（插件可以换掉默认实现）；[activeId] 给「持久选择」留位；
 * - 工厂每次调用都拿新的 root，测试可以每个用例一份临时目录/内存实例。
 */
object SessionStores {
    const val FILE = "file"
    const val MEMORY = "memory"

    private val factories = ConcurrentHashMap<String, (File) -> SessionStore>()

    /** 当前选中的实现 id；null = 用 [FILE]。 */
    @Volatile
    var activeId: String? = null

    init {
        factories[FILE] = { FileSessionRepository(it) }
        factories[MEMORY] = { MemorySessionStore(it) }
    }

    /** 注册/覆盖一个实现；返回被覆盖的旧工厂（没有则 null）。 */
    fun register(id: String, factory: (File) -> SessionStore): ((File) -> SessionStore)? {
        require(id.isNotBlank()) { "session store id must not be blank" }
        return factories.put(id, factory)
    }

    fun unregister(id: String): ((File) -> SessionStore)? = factories.remove(id)

    fun get(id: String?): ((File) -> SessionStore)? = id?.let { factories[it] }

    fun registeredIds(): List<String> = factories.keys.sorted()

    /**
     * 建一个实现实例：显式 [requested] > [activeId] > `file`。
     * 未知 id 不报错（插件卸载/调试值残留时 App 必须还能启动）。
     */
    fun create(root: File, requested: String? = null): SessionStore {
        val factory = get(requested) ?: get(activeId) ?: get(FILE)
        return factory?.invoke(root) ?: FileSessionRepository(root)
    }

    /** 单测用；生产不调用（内置 file/memory 也一起清掉）。 */
    fun clearForTest() {
        factories.clear()
        activeId = null
    }
}

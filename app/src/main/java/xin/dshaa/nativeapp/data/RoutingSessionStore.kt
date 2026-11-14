package xin.dshaa.nativeapp.data

import android.util.Log
import xin.dshaa.core.model.SessionSummary
import xin.dshaa.core.session.SessionSession
import xin.dshaa.core.session.SessionStore
import xin.dshaa.core.session.TrashedSession
import java.io.File

/**
 * M85-E：`storage/v1` 的路由壳（宿主只依赖它，不直接依赖具体存储插件/native 实现）。
 *
 * 与 M84-C2 的 [xin.dshaa.core.session.SessionStores] 不同，这里的 delegate 可以在插件开关
 * 变化后**换绑**：默认 = 内置 `builtin.storage.jsonl`（原 FileSessionRepository，行为/磁盘布局不变），
 * 选中只读存储插件 = [PluginSessionStore]；内置存储插件被停用且没有替代 → delegate 为空，
 * 所有读返回空、写抛 [StorageUnavailableException]（聊天页同时显示安全提示，不悄悄回退）。
 *
 * 线程：delegate 用 `@Volatile`；方法调用本身沿用各实现的线程语义（文件实现自己带锁）。
 */
class RoutingSessionStore(
    private val rootDir: File,
    initial: SessionStore,
) : SessionStore {

    @Volatile
    private var delegate: SessionStore? = initial

    @Volatile
    private var unavailableReason: String? = null

    override val id: String
        get() = delegate?.id ?: "storage/unavailable"

    override val root: File
        get() = delegate?.root ?: rootDir

    override val readOnly: Boolean
        get() = delegate?.readOnly == true

    fun bind(store: SessionStore?, reason: String?) {
        delegate = store
        unavailableReason = reason?.takeIf { it.isNotBlank() }
        Log.i("DshStorage", "bind store=${store?.id ?: "-"} readOnly=${store?.readOnly == true} reason=${unavailableReason ?: "-"}")
    }

    fun unavailableReason(): String? = unavailableReason

    private fun current(): SessionStore? = delegate

    private fun requireStore(): SessionStore =
        current() ?: throw StorageUnavailableException(
            unavailableReason ?: "没有可用的会话存储（存储插件被停用）",
        )

    override fun list(): List<SessionSummary> = current()?.list() ?: emptyList()

    override fun load(id: String): SessionSession? = current()?.load(id)

    override fun create(
        cwd: String,
        model: String,
        agentPreset: String,
        parentSession: String?,
        isSeeded: Boolean,
        origin: String?,
        delegationDepth: Int,
    ): SessionSession = requireStore().create(
        cwd = cwd,
        model = model,
        agentPreset = agentPreset,
        parentSession = parentSession,
        isSeeded = isSeeded,
        origin = origin,
        delegationDepth = delegationDepth,
    )

    override fun findFile(id: String): File? = current()?.findFile(id)

    override fun trash(id: String): Boolean = current()?.trash(id) ?: false

    override fun trashWorkspace(cwd: String): Boolean = current()?.trashWorkspace(cwd) ?: false

    override fun listTrash(): List<TrashedSession> = current()?.listTrash() ?: emptyList()

    override fun restoreTrashed(dirName: String): Boolean = current()?.restoreTrashed(dirName) ?: false

    override fun purgeTrashed(dirName: String): Boolean = current()?.purgeTrashed(dirName) ?: false

    override fun purgeAllTrash(): Int = current()?.purgeAllTrash() ?: 0

    override fun purge(id: String): Boolean = current()?.purge(id) ?: false

    override fun rename(id: String, title: String): Boolean = current()?.rename(id, title) ?: false

    override fun fork(id: String, throughSeq: Long, cleanTurnBoundary: Boolean, title: String?): SessionSession? =
        current()?.fork(id, throughSeq, cleanTurnBoundary, title)
}

/** 存储插件被停用且没有回退实现时的写操作。 */
class StorageUnavailableException(message: String) : IllegalStateException(message)

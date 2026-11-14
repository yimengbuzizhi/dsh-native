package xin.dshaa.nativeapp.data

import android.util.Log
import xin.dshaa.core.js.JsOutcome
import xin.dshaa.core.js.JsStatus
import xin.dshaa.core.model.SessionSummary
import xin.dshaa.core.session.STORAGE_LIST_METHOD
import xin.dshaa.core.session.STORAGE_LOAD_METHOD
import xin.dshaa.core.session.SessionCodec
import xin.dshaa.core.session.SessionSession
import xin.dshaa.core.session.SessionStore
import xin.dshaa.core.session.TrashedSession
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.File

/**
 * M85-E：第三方 `storage/v1` 只读导出器的 [SessionStore] 适配层。
 *
 * 宿主只把 **list / load** 两条读路径交给插件（常驻 runtime dispatch
 * `storage.list` / `storage.load`）；新建/写入/回收站等仍由内置 native 实现负责，
 * v1 的第三方存储不会假装能落库。
 *
 * 设计边界：
 * - `list()` 期望插件返回 `[{id,title,updatedAt,...}]`（`SessionSummary` 的可读子集）；
 * - `load(id)` 期望插件返回原始会话文档（header 行 + 事件行的 JSONL 文本），
 *   宿主用 [SessionCodec.decodeText] 解码成只读 [SessionSession]；
 * - 常驻运行时没起来 / 超时 / 报错一律返回空列表 / null（日志可见），不炸聊天页。
 */
class PluginSessionStore(
    private val pluginId: String,
    private val storeId: String,
    private val label: String,
    private val dispatch: (pluginId: String, method: String, payloadJson: String, timeoutMs: Long) -> JsOutcome?,
    private val awaitReady: () -> Unit,
) : SessionStore {

    override val id: String = "plugin:$pluginId#$storeId"

    override val root: File = File("plugin-storage/$pluginId/$storeId")

    /** v1 第三方存储只读；宿主据此禁用新建/发送。 */
    override val readOnly: Boolean = true

    override fun list(): List<SessionSummary> {
        awaitReady()
        val payload = buildJsonObject { put("id", storeId) }.toString()
        val outcome = dispatch(pluginId, STORAGE_LIST_METHOD, payload, DISPATCH_TIMEOUT_MS)
            ?: return emptyList()
        if (outcome.status != JsStatus.COMPLETED) {
            Log.w(TAG, "storage.list failed plugin=$pluginId store=$storeId status=${outcome.status} ${outcome.error.orEmpty()}")
            return emptyList()
        }
        val obj = runCatching { Json.parseToJsonElement(outcome.valueJson ?: "null").jsonObject }.getOrNull()
            ?: return emptyList()
        val ok = obj["ok"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() == true
        if (!ok) {
            Log.w(TAG, "storage.list error plugin=$pluginId store=$storeId: ${obj["error"]?.jsonPrimitive?.contentOrNull.orEmpty()}")
            return emptyList()
        }
        val items = obj["sessions"]?.jsonArray ?: return emptyList()
        return items.mapNotNull { element ->
            val item = element as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
            summaryOf(item) ?: return@mapNotNull null
        }
    }

    override fun load(id: String): SessionSession? {
        awaitReady()
        val payload = buildJsonObject {
            put("id", storeId)
            put("sessionId", id)
        }.toString()
        val outcome = dispatch(pluginId, STORAGE_LOAD_METHOD, payload, DISPATCH_TIMEOUT_MS)
            ?: return null
        if (outcome.status != JsStatus.COMPLETED) {
            Log.w(TAG, "storage.load failed plugin=$pluginId store=$storeId status=${outcome.status} ${outcome.error.orEmpty()}")
            return null
        }
        val obj = runCatching { Json.parseToJsonElement(outcome.valueJson ?: "null").jsonObject }.getOrNull()
            ?: return null
        val ok = obj["ok"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() == true
        if (!ok) {
            Log.w(TAG, "storage.load error plugin=$pluginId store=$storeId: ${obj["error"]?.jsonPrimitive?.contentOrNull.orEmpty()}")
            return null
        }
        val doc = obj["doc"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: return null
        val decoded = runCatching { SessionCodec.decodeText(doc) }.getOrNull() ?: run {
            Log.w(TAG, "storage.load doc decode failed plugin=$pluginId store=$storeId session=$id")
            return null
        }
        Log.i(TAG, "storage.load ok plugin=$pluginId store=$storeId session=$id events=${decoded.events.size} label=$label")
        return SessionSession(
            file = File(root, "session-$id.jsonl"),
            header = decoded.header,
            initialEvents = decoded.events,
            readOnly = true,
        )
    }

    // ---- v1 只读：写路径一律拒绝/空操作（宿主在 UI 层已拦，这里兜底） ----

    override fun create(
        cwd: String,
        model: String,
        agentPreset: String,
        parentSession: String?,
        isSeeded: Boolean,
        origin: String?,
        delegationDepth: Int,
    ): SessionSession = throw StorageReadOnlyException("只读存储插件 $label 不能新建会话")

    override fun findFile(id: String): File? = null

    override fun trash(id: String): Boolean = false

    override fun trashWorkspace(cwd: String): Boolean = false

    override fun listTrash(): List<TrashedSession> = emptyList()

    override fun restoreTrashed(dirName: String): Boolean = false

    override fun purgeTrashed(dirName: String): Boolean = false

    override fun purgeAllTrash(): Int = 0

    override fun purge(id: String): Boolean = false

    override fun rename(id: String, title: String): Boolean = false

    override fun fork(id: String, throughSeq: Long, cleanTurnBoundary: Boolean, title: String?): SessionSession? = null

    private fun summaryOf(item: kotlinx.serialization.json.JsonObject): SessionSummary? {
        val sessionId = item["id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: return null
        return SessionSummary(
            id = sessionId,
            title = item["title"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            preview = item["preview"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            createdAt = item["createdAt"]?.jsonPrimitive?.longOrNull ?: 0L,
            updatedAt = item["updatedAt"]?.jsonPrimitive?.longOrNull ?: 0L,
            messageCount = item["messageCount"]?.jsonPrimitive?.intOrNull ?: 0,
            cwd = item["cwd"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            path = item["path"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            parentSession = item["parentSession"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() },
            delegationDepth = item["delegationDepth"]?.jsonPrimitive?.intOrNull ?: 0,
            origin = item["origin"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() },
            isSeeded = item["isSeeded"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false,
        )
    }

    private companion object {
        const val TAG = "DshStorage"
        const val DISPATCH_TIMEOUT_MS = 5_000L
    }
}

/** 只读存储上的写操作（宿主兜底；正常由 UI 的只读态拦住）。 */
class StorageReadOnlyException(message: String) : IllegalStateException(message)

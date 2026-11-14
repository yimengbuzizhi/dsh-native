package xin.dshaa.nativeapp.remote

import android.app.Application
import android.content.SharedPreferences
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.TimeUnit
import xin.dshaa.ui.shellapi.remote.RemoteAskOption
import xin.dshaa.ui.shellapi.remote.RemoteAskQuestion
import xin.dshaa.ui.shellapi.remote.RemoteChatMessage
import xin.dshaa.ui.shellapi.remote.RemoteChatState
import xin.dshaa.ui.shellapi.remote.RemotePhase
import xin.dshaa.ui.shellapi.remote.RemoteRole
import xin.dshaa.ui.shellapi.remote.RemoteSession
import xin.dshaa.ui.shellapi.remote.RemoteToolCard
import xin.dshaa.ui.shellapi.remote.ShellRemote
import xin.dshaa.ui.shellapi.remote.RemoteProfileUi
import xin.dshaa.core.tools.AskAnswer
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.serialization.json.Json as KJson
import kotlinx.serialization.json.JsonObject as KJsonObject
import kotlinx.serialization.json.JsonArray as KJsonArray
import kotlinx.serialization.json.JsonPrimitive as KJsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * 远程 DSH（M83 阶段 4）：手机 → 电脑上的「小助手」→ 本机 DSH。
 *
 * 手机只跟助手说话：地址 + 令牌。助手负责签 DSH 的浏览器 cookie 并把
 * `/api/dsh/…` 原样转发（普通 HTTP + WebSocket）。这里实现的是 DSH Web 客户端
 * 自己用的那套 RPC：
 *  - 一元调用：POST /api/dsh/api/<endpoint>，body {type:"client-request", rpcId, method, payload:{args}}
 *  - 会话事件流：WS /api/dsh/api/remote.mux，帧 {type:"open", streamId, endpoint, payload} /
 *    {type:"item", streamId, value} / {type:"end"} / {type:"error"}
 * 明细见 `tools/dsh-remote/helper.mjs` 与 `docs/121`。
 */

private const val TAG = "DshRemote"
private const val PREFS_KEY_URL = "remote_dsh_url"
private const val PREFS_KEY_TOKEN = "remote_dsh_token"
private const val PREFS_KEY_LAST_OK = "remote_dsh_last_ok"

private val JSON = "application/json; charset=utf-8".toMediaType()

/** 连接页保存的地址 + 令牌；令牌只存在本机 prefs，不进日志。 */
class RemoteDshSettings(private val prefs: SharedPreferences) {
    var baseUrl: String
        get() = prefs.getString(PREFS_KEY_URL, "") ?: ""
        set(value) = prefs.edit().putString(PREFS_KEY_URL, normalizeBase(value)).apply()

    var token: String
        get() = prefs.getString(PREFS_KEY_TOKEN, "") ?: ""
        set(value) = prefs.edit().putString(PREFS_KEY_TOKEN, value.trim()).apply()

    /** 最近一次连通时间（0 = 从没连过），连接页拿它显示状态。 */
    var lastConnectedAt: Long
        get() = prefs.getLong(PREFS_KEY_LAST_OK, 0L)
        set(value) = prefs.edit().putLong(PREFS_KEY_LAST_OK, value).apply()

    companion object {
        /** 容错：没写协议补 http://，去掉末尾斜杠；空串原样返回。 */
        fun normalizeBase(raw: String): String {
            val text = raw.trim().trimEnd('/')
            if (text.isEmpty()) return ""
            return RemoteProfileStore.normalizeBase(text)
        }
    }
}

/** 健康检查结果。`dsh` = DSH 是否认这个助手；`api` = /api/session/list 能不能通。 */
data class RemoteHealth(val reachable: Boolean, val detail: String)

/** DSH 浏览器客户端协议的最小实现；无状态，每次调用按 prefs 的地址/令牌现建请求。 */
class RemoteDshClient(private val baseUrl: String, private val token: String) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        // Prompt/cancel/answer must never be replayed by an HTTP transport retry.
        .retryOnConnectionFailure(false)
        .build()

    private fun url(path: String): String = baseUrl.trimEnd('/') + path

    private fun authorized(request: Request.Builder): Request.Builder =
        request.header("Authorization", "Bearer $token")

    /** GET /api/health（助手侧免令牌，但带上也无妨）。 */
    fun health(): RemoteHealth {
        return try {
            authorized(Request.Builder().url(url("/api/health")).get()).build().let { req ->
                http.newCall(req).execute().use { res ->
                    val text = res.body?.string().orEmpty()
                    if (!res.isSuccessful) return RemoteHealth(false, "HTTP ${res.code}")
                    val json = runCatching { JSONObject(text) }.getOrNull()
                        ?: return RemoteHealth(false, "健康检查返回的不是 JSON")
                    val dsh = json.optString("dsh")
                    val api = json.optString("api")
                    when {
                        dsh == "reachable" && (api.isEmpty() || api == "ok") ->
                            RemoteHealth(true, "已连上电脑上的 DSH")
                        dsh == "reachable" -> RemoteHealth(false, "DSH 首页可达，但 API 不通（$api）")
                        else -> RemoteHealth(false, "助手中转正常，但 DSH 没认 cookie（$dsh）")
                    }
                }
            }
        } catch (e: Exception) {
            RemoteHealth(false, "连不上电脑上的小助手：${e.message ?: e.javaClass.simpleName}（检查地址/端口、助手是否在跑、防火墙）")
        }
    }

    /** 一元 RPC：返回 result.value（可能是 object/bool/string）；失败抛 [RemoteRpcException]。 */
    fun callRaw(endpoint: String, args: JSONObject): Any? {
        val rpcId = "whale-" + UUID.randomUUID()
        val body = JSONObject()
            .put("type", "client-request")
            .put("rpcId", rpcId)
            .put("method", endpoint)
            .put("payload", JSONObject().put("args", args))
            .toString()
        val request = authorized(
            Request.Builder()
                .url(url("/api/dsh/api/$endpoint"))
                .post(body.toRequestBody(JSON)),
        ).build()
        http.newCall(request).execute().use { res ->
            val text = res.body?.string().orEmpty()
            if (res.code == 401) throw RemoteRpcException("auth", "令牌不对（助手拒绝）")
            if (!res.isSuccessful) throw RemoteRpcException("http", "HTTP ${res.code}")
            val json = runCatching { JSONObject(text) }.getOrNull()
                ?: throw RemoteRpcException("protocol", "返回的不是 JSON")
            if (json.optString("type") != "server-response") {
                throw RemoteRpcException("protocol", "返回的不是 DSH 响应")
            }
            val result = json.optJSONObject("result")
                ?: throw RemoteRpcException("protocol", "响应缺少 result")
            if (!result.optBoolean("ok")) {
                val error = result.optJSONObject("error")
                throw RemoteRpcException(
                    error?.optString("code").orEmpty().ifEmpty { "remote" },
                    error?.optString("message").orEmpty().ifEmpty { "DSH 返回失败" },
                )
            }
            return if (result.isNull("value")) null else result.opt("value")
        }
    }

    /** 只取对象结果的便捷包装（session/list 之类）。 */
    fun call(endpoint: String, args: JSONObject): JSONObject =
        callRaw(endpoint, args) as? JSONObject ?: JSONObject()

    /** 打开会话事件流（session/follow）；返回的句柄负责发帧/取消。 */
    fun follow(
        sessionId: String,
        fromCursor: Long,
        listener: FollowListener,
    ): RemoteFollow {
        val wsBase = when {
            baseUrl.startsWith("https://") -> "wss://" + baseUrl.removePrefix("https://")
            baseUrl.startsWith("http://") -> "ws://" + baseUrl.removePrefix("http://")
            else -> "ws://$baseUrl"
        }
        val request = authorized(Request.Builder().url("$wsBase/api/dsh/api/remote.mux")).build()
        return RemoteFollow(http, request, sessionId, fromCursor, listener)
    }

    fun close() {
        http.dispatcher.cancelAll()
        http.connectionPool.evictAll()
        http.dispatcher.executorService.shutdown()
    }
}

class RemoteRpcException(val code: String, message: String) : Exception(message)

/** JSON 里 `null` 的 optString 会返回字符串 "null"，统一当空串处理。 */
private fun JSONObject.text(key: String): String =
    if (!has(key) || isNull(key)) "" else optString(key)

/** 会话流回调：帧都是 DSH 的原始 JSON，映射在控制器里做。 */
interface FollowListener {
    fun onSnapshot(value: JSONObject)
    fun onEvent(event: JSONObject)
    fun onAssistantStream(frame: JSONObject)
    fun onClosed(reason: String?)
}

/**
 * 一条 logical stream（`remote.mux` 上的帧）。DSH 的帧格式：
 * 发 open → 收 item* → 收 end/error；本地取消发 cancel。
 */
class RemoteFollow(
    private val http: OkHttpClient,
    private val request: Request,
    private val sessionId: String,
    private val fromCursor: Long,
    private val listener: FollowListener,
) {
    private val streamId = "whale-" + UUID.randomUUID()
    @Volatile private var socket: WebSocket? = null
    @Volatile private var closed = false
    @Volatile private var opened = false
    private val terminal = AtomicBoolean(false)

    private fun finished(reason: String?) {
        if (terminal.compareAndSet(false, true)) {
            closed = true
            socket?.cancel()
            listener.onClosed(reason)
        }
    }

    fun start() {
        if (closed) return
        socket = http.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (closed) { webSocket.cancel(); return }
                val args = JSONObject().put(
                    "request",
                    JSONObject()
                        .put("address", JSONObject().put("kind", "session").put("sessionId", sessionId))
                        .put("assistantStream", true)
                        .put("maxMessages", 80),
                )
                val frame = JSONObject()
                    .put("type", "open")
                    .put("streamId", streamId)
                    .put("endpoint", "session/follow")
                    .put("payload", JSONObject().put("args", args))
                webSocket.send(frame.toString())
                opened = true
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (closed) return
                val json = runCatching { JSONObject(text) }.getOrNull() ?: return
                if (json.text("streamId") != streamId) return
                Log.d(TAG, "follow frame ${json.optString("type")}")
                when (json.optString("type")) {
                    "item" -> {
                        val value = json.optJSONObject("value") ?: return
                        Log.d(TAG, "follow item ${value.optString("type")}")
                        when (value.optString("type")) {
                            "snapshot" -> listener.onSnapshot(value)
                            "event" -> value.optJSONObject("event")?.let {
                                Log.d(TAG, "follow event ${it.optString("type")}#${it.optLong("seq")}")
                                listener.onEvent(it)
                            }
                            "assistant-stream" -> value.optJSONObject("frame")?.let(listener::onAssistantStream)
                        }
                    }
                    "error" -> {
                        val error = json.optJSONObject("error")
                        Log.w(TAG, "follow error ${error?.optString("code")} ${error?.optString("message")}")
                        finished(error?.optString("message") ?: "事件流报错")
                    }
                    "end" -> {
                        Log.d(TAG, "follow end")
                        if (!closed) finished(null)
                    }
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (closed) return
                Log.w(TAG, "follow failure: ${t.message}")
                finished(if (response?.code == 401) "令牌不对（助手拒绝）" else t.message ?: "WebSocket 断开")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (closed) return
                Log.d(TAG, "follow closed code=$code reason=$reason")
                finished(reason.ifBlank { null })
            }
        })
    }

    /** 用户主动关闭：先发 cancel（DSH 会停这条流），不再触发重连。 */
    fun close() {
        if (closed) return
        closed = true
        terminal.set(true)
        val ws = socket
        if (ws != null && opened) {
            runCatching { ws.send(JSONObject().put("type", "cancel").put("streamId", streamId).toString()) }
            runCatching { ws.close(1000, "client closed") }
        } else {
            runCatching { ws?.cancel() }
        }
        socket = null
    }
}

/**
 * 远程 DSH 的界面状态机：连接页 / 会话列表 / 远程聊天。
 * 网络一律在 IO 上跑，Compose 状态只在主线程改。
 *
 * 「按需连接」：连接页点「连接」或打开会话时才建 HTTP/WS；离开远程聊天 / 点断开即关，
 * 不在后台常驻。远程任务跑着时借 [xin.dshaa.nativeapp.chat.TurnKeepAliveService] 保活。
 */
class RemoteDshController(private val app: Application) : ShellRemote {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val store = RemoteProfileStore(app.getSharedPreferences("dsh", 0), tolerateCorruption = true)
    override var profiles by mutableStateOf(store.profiles())
        private set
    override var activeProfileId by mutableStateOf(store.activeId)
        private set
    override var generation by mutableStateOf(0L)
        private set
    override var baseUrl by mutableStateOf(profiles.firstOrNull { it.id == activeProfileId }?.baseUrl.orEmpty())
        private set
    override var token by mutableStateOf(store.token(activeProfileId).orEmpty())
        private set
    override var phase by mutableStateOf(RemotePhase.Idle)
        private set
    override var statusText by mutableStateOf("选择远程配置后连接")
        private set
    override var errorText by mutableStateOf(store.loadError)
        private set
    override var sessions by mutableStateOf<List<RemoteSession>>(emptyList())
        private set
    override var sessionsLoading by mutableStateOf(false)
        private set
    override var chat by mutableStateOf<RemoteChatState?>(null)
        private set
    override var sending by mutableStateOf(false)
        private set
    override var followConnected by mutableStateOf(false)
        private set
    override val canSend: Boolean get() = phase == RemotePhase.Connected && followConnected &&
        chat?.historyLoading == false && !sending

    private var client: RemoteDshClient? = null
    private var follow: RemoteFollow? = null
    private var reconnectJob: Job? = null
    private val operationJobs = mutableSetOf<Job>()
    private var listSerial = 0L
    private var reconnectAttempt = 0
    private var remoteLease = false
    private val gate = RemoteGenerationGate()
    private val messages = LinkedHashMap<String, RemoteChatMessage>()
    private var liveKey: String? = null
    private val cachedChats = mutableMapOf<Pair<String, String>, RemoteChatState>()
    private val lastSession = mutableMapOf<String, String>()
    private val promptDrafts = RemotePromptDrafts()

    init {
        val prefs = app.getSharedPreferences("dsh", 0)
        val debugUrl = prefs.getString("debug_remote_url", null)
        val debugToken = prefs.getString("debug_remote_token", null)
        if (store.loadError == null && (!debugUrl.isNullOrBlank() || !debugToken.isNullOrBlank())) {
            val id = store.save(activeProfileId, "远程 DSH", debugUrl ?: baseUrl, debugToken)
            store.select(id)
            prefs.edit().remove("debug_remote_url").remove("debug_remote_token").apply()
            refreshProfiles()
        }
    }

    private fun refreshProfiles() {
        profiles = store.profiles(); activeProfileId = store.activeId
        baseUrl = profiles.firstOrNull { it.id == activeProfileId }?.baseUrl.orEmpty()
        token = store.token(activeProfileId).orEmpty()
    }

    override fun saveProfile(id: String?, name: String, baseUrl: String, token: String?): String? = try {
        val old = profiles.firstOrNull { it.id == id }
        val credentialChange = old != null &&
            (old.baseUrl != RemoteProfileStore.normalizeBase(baseUrl) || (token != null && token.trim() != store.token(id)))
        val saved = store.save(id, name, baseUrl, token)
        if (credentialChange) {
            if (id == activeProfileId) invalidateTransport(clearChat = true)
            // Same profile id with another endpoint/identity must not inherit remote session ids.
            cachedChats.keys.filter { it.first == saved }.forEach { cachedChats.remove(it) }
            promptDrafts.clearProfile(saved)
            lastSession.remove(saved)
        }
        refreshProfiles(); errorText = store.loadError
        saved
    } catch (e: Exception) {
        errorText = e.message ?: "保存远程配置失败"; null
    }

    override fun removeProfile(id: String): Boolean {
        if (profiles.none { it.id == id }) return false
        return try {
            if (id == activeProfileId) invalidateTransport(clearChat = true)
            val removed = store.remove(id)
            cachedChats.keys.filter { it.first == id }.forEach { cachedChats.remove(it) }
            promptDrafts.clearProfile(id)
            lastSession.remove(id); refreshProfiles(); removed
        } catch (e: Exception) { errorText = e.message; false }
    }

    override fun selectProfile(id: String): Boolean {
        if (profiles.none { it.id == id }) return false
        return try {
            invalidateTransport(clearChat = true)
            store.select(id); refreshProfiles()
            lastSession[id]?.let { sessionId -> restoreChat(id, sessionId) }
            statusText = "已选择 ${profiles.first { it.id == id }.name}，等待连接"
            true
        } catch (e: Exception) { errorText = e.message; false }
    }

    override fun updateBaseUrl(value: String) {
        saveProfile(activeProfileId, profiles.firstOrNull { it.id == activeProfileId }?.name ?: "远程 DSH", value, null)
    }
    override fun updateToken(value: String) {
        saveProfile(activeProfileId, profiles.firstOrNull { it.id == activeProfileId }?.name ?: "远程 DSH", baseUrl, value)
    }

    private fun cacheChat() {
        chat?.let { current ->
            cachedChats[current.profileId to current.sessionId] = current.copy(messages = messages.values.toList())
            lastSession[current.profileId] = current.sessionId
        }
    }
    private fun restoreChat(profileId: String, sessionId: String) {
        val cached = cachedChats[profileId to sessionId] ?: return
        chat = cached.copy(historyLoading = true, questionsSending = emptySet())
        messages.clear(); cached.messages.forEach { messages[it.key] = it }; liveKey = null
    }
    private fun lease(busy: Boolean) {
        if (busy == remoteLease) return
        remoteLease = busy
        if (busy) xin.dshaa.nativeapp.chat.TurnKeepAliveService.startRemote(app, "远程 DSH · ${chat?.title ?: "会话"}")
        else xin.dshaa.nativeapp.chat.TurnKeepAliveService.stopRemote(app)
    }
    private fun stopFollow() {
        gate.nextFollow()
        reconnectJob?.cancel(); reconnectJob = null
        follow?.close(); follow = null; followConnected = false
        lease(false)
    }
    private fun preservePendingPrompt() {
        chat = chat?.let { current ->
            val requestId = promptDrafts.requestId(current) ?: return@let current
            messages.remove("local:$requestId")
            promptDrafts.recover(current, "发送结果未知，请先重连确认历史").copy(messages = messages.values.toList())
        }
    }
    private fun invalidateTransport(clearChat: Boolean) {
        preservePendingPrompt(); cacheChat(); stopFollow()
        gate.nextTarget(); generation = gate.target
        operationJobs.toList().forEach { it.cancel() }; operationJobs.clear()
        client?.close(); client = null
        sending = false; sessionsLoading = false; sessions = emptyList()
        reconnectAttempt = 0; phase = RemotePhase.Idle; errorText = null
        if (clearChat) { chat = null; messages.clear(); liveKey = null }
    }
    override fun disconnect() { invalidateTransport(clearChat = true); statusText = "已断开" }
    override fun deactivate() { invalidateTransport(clearChat = true); statusText = "已切回本机，远端任务不受影响" }
    /** The owning AppViewModel must retire callbacks and release transport/keepalive on destruction. */
    fun close() { invalidateTransport(clearChat = true); scope.cancel() }

    private fun launchOperation(block: suspend () -> Unit) {
        val job = scope.launch { block() }
        operationJobs += job
        job.invokeOnCompletion { scope.launch { operationJobs.remove(job) } }
    }
    private fun targetKey() = gate.key(activeProfileId, null)
    private fun sessionKey() = gate.key(activeProfileId, chat?.sessionId, sessionScoped = true)
    private fun valid(key: RemoteOperationKey): Boolean = gate.accepts(key, activeProfileId,
        if (key.sessionId == null) null else chat?.sessionId)

    override fun testConnection() { connect() }
    override fun connect() {
        if (phase == RemotePhase.Testing || phase == RemotePhase.Connected) return
        val transport = newClient() ?: return
        val key = targetKey()
        phase = RemotePhase.Testing; sessionsLoading = true; errorText = null; statusText = "正在连接…"
        launchOperation {
            try {
                val result = withContext(Dispatchers.IO) {
                    val health = transport.health()
                    if (!health.reachable) throw RemoteRpcException("health", health.detail)
                    transport.call("session/list", JSONObject().put("_request", JSONObject()))
                }
                if (!valid(key)) return@launchOperation
                sessions = RemoteProtocol.parseSessions(result.toString())
                phase = RemotePhase.Connected; statusText = "已连接 · ${sessions.size} 个会话"
                activeProfileId?.let { store.noteConnected(it, System.currentTimeMillis()) }; refreshProfiles()
                chat?.let { startFollow(it.sessionId) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (valid(key)) { phase = RemotePhase.Failed; errorText = e.message; statusText = "连接失败" }
            } finally { if (valid(key)) sessionsLoading = false }
        }
    }
    override fun refreshSessions() {
        val transport = client ?: return
        if (phase != RemotePhase.Connected) return
        val key = targetKey(); val serial = ++listSerial
        sessionsLoading = true
        launchOperation {
            try {
                val result = withContext(Dispatchers.IO) { transport.call("session/list", JSONObject().put("_request", JSONObject())) }
                if (valid(key) && serial == listSerial) { sessions = RemoteProtocol.parseSessions(result.toString()); statusText = "已连接 · ${sessions.size} 个会话" }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (valid(key) && serial == listSerial) errorText = e.message }
            finally { if (valid(key) && serial == listSerial) sessionsLoading = false }
        }
    }
    override fun openSession(session: RemoteSession) {
        if (phase != RemotePhase.Connected) return
        preservePendingPrompt(); cacheChat(); stopFollow(); gate.nextSession(); sending = false
        messages.clear(); liveKey = null
        val profileId = activeProfileId ?: return
        restoreChat(profileId, session.id)
        if (chat?.sessionId != session.id || chat?.profileId != profileId) {
            chat = RemoteChatState(session.id, session.title, running = session.running, profileId = profileId, model = session.model)
        }
        lastSession[profileId] = session.id
        reconnectAttempt = 0; startFollow(session.id)
    }
    override fun createSession() {
        val transport = client ?: return
        if (phase != RemotePhase.Connected || sending) return
        val key = sessionKey(); sending = true
        launchOperation {
            try {
                val result = withContext(Dispatchers.IO) { transport.call("session/create", JSONObject().put("request", JSONObject())) }
                if (!valid(key)) return@launchOperation
                val id = result.text("sessionId")
                if (id.isBlank()) throw RemoteRpcException("protocol", "新会话响应缺少 sessionId")
                openSession(RemoteSession(id, "新会话", "", System.currentTimeMillis(), false, "", 0L))
                refreshSessions()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (valid(key)) errorText = e.message }
            finally { if (valid(key)) sending = false }
        }
    }
    override fun closeChat() {
        preservePendingPrompt(); cacheChat(); stopFollow(); gate.nextSession(); sending = false
        chat = null; messages.clear(); liveKey = null
    }
    private fun startFollow(sessionId: String) {
        val transport = client ?: return
        if (chat?.sessionId != sessionId || phase != RemotePhase.Connected) return
        stopFollow()
        val key = sessionKey().copy(follow = gate.follow)
        val listener = object : FollowListener {
            private fun dispatch(block: () -> Unit) { scope.launch { if (valid(key)) block() } }
            override fun onSnapshot(value: JSONObject) = dispatch { followConnected = true; applySnapshot(value); lease(chat?.running == true) }
            override fun onEvent(event: JSONObject) = dispatch { applyEvent(event) }
            override fun onAssistantStream(frame: JSONObject) = dispatch { applyAssistantFrame(frame) }
            override fun onClosed(reason: String?) = dispatch { onFollowClosed(reason) }
        }
        follow = transport.follow(sessionId, 0L, listener).also { it.start() }
    }
    private fun onFollowClosed(reason: String?) {
        gate.nextFollow() // Retire even already-enqueued frames from this closed socket.
        followConnected = false; lease(false)
        val state = chat ?: return
        val key = sessionKey().copy(follow = gate.follow)
        if (phase != RemotePhase.Connected) return
        if (reason?.contains("令牌") == true) {
            phase = RemotePhase.Failed; errorText = reason; chat = state.copy(notice = reason); return
        }
        val attempt = reconnectAttempt++
        chat = state.copy(notice = if (attempt >= 6) "连接断开，自动重连失败；可重连或切回本机" else "连接断开，正在重连…")
        if (attempt >= 6) return
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(minOf(1_000L shl attempt, 8_000L))
            if (valid(key)) startFollow(state.sessionId)
        }
    }
    override fun reconnect() {
        reconnectAttempt = 0
        if (phase != RemotePhase.Connected) { connect(); return }
        chat?.let { chat = it.copy(notice = "正在重连…"); startFollow(it.sessionId) }
    }
    override fun updateDraft(text: String) {
        val current = chat ?: return
        chat = promptDrafts.edit(current, text); cacheChat()
    }
    override fun send(text: String) { trySend(text) }
    override fun trySend(text: String): Boolean {
        val current = chat ?: return false
        val transport = client ?: return false
        if (!canSend || text.isBlank()) return false
        val body = text.trim(); val requestId = UUID.randomUUID().toString(); val key = sessionKey()
        sending = true
        // Keep recoverable text until acknowledgement/durable echo, including target switches.
        chat = promptDrafts.begin(current, requestId, text)
        val optimisticKey = "local:$requestId"
        messages[optimisticKey] = RemoteChatMessage(optimisticKey, RemoteRole.User, text = body, time = System.currentTimeMillis())
        publish()
        launchOperation {
            try {
                withContext(Dispatchers.IO) { transport.call("session/prompt", JSONObject(RemoteProtocol.promptArgs(current.sessionId, requestId, body, ZoneId.systemDefault().id))) }
                if (valid(key)) {
                    // The complete turn can arrive before the HTTP acknowledgement. Do not reopen it.
                    chat = chat?.let { promptDrafts.acknowledge(it, requestId) }
                    if (messages.containsKey(optimisticKey)) { chat = chat?.copy(running = true, notice = null); lease(true) }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (valid(key) && messages.containsKey(optimisticKey)) {
                    // Network failure may still mean the server accepted it. Do not retry automatically.
                    messages.remove(optimisticKey); publish()
                    chat = chat?.let { promptDrafts.recover(it, e.message ?: "发送结果未知，请先重连确认历史") }
                }
            } finally { if (valid(key)) { sending = false; cacheChat() } }
        }
        return true
    }
    override fun stopTurn() {
        val transport = client ?: return
        val current = chat ?: return
        val key = sessionKey()
        launchOperation {
            try {
                withContext(Dispatchers.IO) { transport.call("session/cancel", JSONObject().put("request", JSONObject().put("sessionId", current.sessionId))) }
                if (valid(key)) { chat = chat?.copy(notice = "已请求停止"); lease(false) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (valid(key)) chat = chat?.copy(notice = e.message ?: "停止失败") }
        }
    }
    override fun answerQuestion(callId: String, question: RemoteAskQuestion, selected: String) {
        answerQuestions(callId, listOf(AskAnswer(question.id, listOf(selected))))
    }
    override fun answerQuestions(callId: String, answers: List<AskAnswer>) {
        val transport = client ?: return
        val current = chat ?: return
        if (!followConnected || callId in current.questionsSending) return
        val message = messages["t:$callId"] ?: return
        if (!RemoteProtocol.questionAnswerable(message)) return
        if (answers.size != message.questions.size || answers.map { it.id }.distinct().size != answers.size ||
            answers.any { answer -> message.questions.none { it.id == answer.id } }) return
        val key = sessionKey()
        chat = current.copy(questionsSending = current.questionsSending + callId)
        launchOperation {
            try {
                val result = withContext(Dispatchers.IO) { transport.callRaw("userQuestions/answer", JSONObject(RemoteProtocol.answerArgs(current.sessionId, callId, answers))) }
                if (!valid(key)) return@launchOperation
                if (result == true) {
                    updateMessageByToolCall(callId) { it.copy(answered = true) }
                    chat = chat?.copy(notice = "答案已送出")
                } else {
                    updateMessageByToolCall(callId) { it.copy(answered = true) }
                    chat = chat?.copy(notice = "问题已结束，答案没有送出")
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (valid(key)) chat = chat?.copy(notice = e.message ?: "回答失败") }
            finally { if (valid(key)) chat = chat?.let { it.copy(questionsSending = it.questionsSending - callId) } }
        }
    }
    private fun newClient(): RemoteDshClient? {
        if (activeProfileId == null || !RemoteProfileStore.validBase(baseUrl) || token.isBlank()) {
            phase = RemotePhase.Failed; errorText = "请先配置有效地址和该地址的令牌"; return null
        }
        return client ?: RemoteDshClient(baseUrl, token).also { client = it }
    }
    // ---- 会话流 → 界面消息 ----

    private fun applySnapshot(value: JSONObject) {
        if (chat == null) return
        val optimistic = messages.filterKeys { it.startsWith("local:") }
        messages.clear(); messages.putAll(optimistic)
        liveKey = null
        val records = value.optJSONArray("records") ?: JSONArray()
        for (i in 0 until records.length()) {
            val event = records.optJSONObject(i)?.optJSONObject("event") ?: continue
            applyEventInternal(event, replay = true)
        }
        // Pending optimistic entries belong after the durable snapshot until their rpcId arrives.
        val pending = messages.filterKeys { it.startsWith("local:") }
        pending.keys.forEach { messages.remove(it) }; messages.putAll(pending)
        reconnectAttempt = 0
        val values = value.optJSONObject("projections")?.optJSONObject("values")
        val title = values?.text("title").orEmpty()
        chat = chat?.copy(
            title = title.ifBlank { chat?.title.orEmpty() }.ifBlank { "远程会话" },
            historyLoading = false,
            notice = null,
            model = values?.optJSONObject("modelSelection")?.optJSONObject("lastUsed")?.text("model").orEmpty().ifBlank { chat?.model.orEmpty() },
            messages = messages.values.toList(),
        )
    }

    private fun applyEvent(event: JSONObject) {
        if (chat == null) return
        applyEventInternal(event, replay = false)
        chat = chat?.copy(messages = messages.values.toList())
    }

    /** [replay] = 正在重放 snapshot 里的历史事件：不要触发停服务/刷新列表等副作用。 */
    private fun applyEventInternal(event: JSONObject, replay: Boolean) {
        val type = event.optString("type")
        val data = event.optJSONObject("data") ?: JSONObject()
        val time = event.optLong("time")
        when (type) {
            "user/message" -> {
                val message = data.optJSONObject("message") ?: data
                val source = message.optJSONObject("source")
                val kind = source?.text("kind").orEmpty()
                if (kind == "user-question-reply") {
                    val callId = source?.text("callId").orEmpty()
                    messages["t:$callId"]?.let { messages["t:$callId"] = it.copy(answered = true) }
                    return
                }
                if (kind.isNotEmpty() && kind != "user") return
                val text = contentText(message.optJSONArray("content"))
                if (text.isBlank()) return
                val id = message.text("id").ifEmpty { "seq:${event.optLong("seq")}" }
                // 本地乐观回显按 rpcId 去重（DSH 把 prompt 的 requestId 记在 source.rpcId）。
                val rpcId = source?.text("rpcId").orEmpty()
                if (rpcId.isNotEmpty()) {
                    messages.remove("local:$rpcId")
                    // A failed HTTP response or a target switch may already have removed the optimistic row.
                    chat = chat?.let { promptDrafts.acknowledge(it, rpcId) }
                }
                messages["u:$id"] = RemoteChatMessage(
                    key = "u:$id",
                    role = RemoteRole.User,
                    text = text,
                    time = time,
                )
            }

            "assistant/message" -> {
                val message = data.optJSONObject("message") ?: return
                val id = message.text("id").ifEmpty { "seq:${event.optLong("seq")}" }
                var text = ""
                var thinking = ""
                val blocks = message.optJSONArray("content") ?: JSONArray()
                for (i in 0 until blocks.length()) {
                    val block = blocks.optJSONObject(i) ?: continue
                    when (block.optString("type")) {
                        "text" -> text += block.optString("text")
                        "reasoning" -> thinking += block.optString("text")
                    }
                }
                // 持久正文到了，丢掉同一段的临时流式气泡。
                liveKey?.let { messages.remove(it) }
                liveKey = null
                if (text.isNotBlank() || thinking.isNotBlank()) {
                    messages["a:$id"] = RemoteChatMessage(
                        key = "a:$id",
                        role = RemoteRole.Assistant,
                        text = text.trim(),
                        thinking = thinking.trim(),
                        time = time,
                    )
                }
            }

            "tool/call" -> {
                val callId = data.optString("callId")
                val name = data.optString("name")
                if (callId.isBlank() || name.isBlank()) return
                val args = data.optString("arguments")
                val questions = RemoteProtocol.parseQuestions(callId, name, args)
                messages["t:$callId"] = RemoteChatMessage(
                    key = "t:$callId",
                    role = RemoteRole.Tool,
                    tool = RemoteToolCard(callId = callId, name = name, arguments = args),
                    question = questions.firstOrNull(),
                    questions = questions,
                    seq = event.optLong("seq").takeIf { event.has("seq") },
                    questionCallId = callId,
                    time = time,
                )
            }

            "tool/result" -> {
                val message = data.optJSONObject("message") ?: return
                val callId = message.optString("toolCallId")
                if (callId.isBlank()) return
                val text = contentText(message.optJSONArray("content"))
                val isError = message.optBoolean("isError")
                val key = "t:$callId"
                messages[key] = (messages[key] ?: RemoteChatMessage(key = key, role = RemoteRole.Tool, time = time)).let {
                    val tool = it.tool?.copy(result = text, isError = isError)
                    it.copy(tool = tool, answered = it.answered || !RemoteProtocol.pendingQuestionResult(tool))
                }
            }

            "turn/start" -> {
                chat = chat?.copy(running = true)
                if (!replay) lease(true)
            }

            "turn/end" -> {
                chat = chat?.copy(running = false)
                if (!replay) {
                    lease(false)
                    refreshSessions()
                }
            }
        }
    }

    /** 流式 token：只拼正文；工具调用增量不画（等 durable tool/call）。 */
    private fun applyAssistantFrame(frame: JSONObject) {
        if (chat == null) return
        when (frame.optString("type")) {
            "start" -> {
                val key = "live:" + frame.optString("attemptId")
                liveKey = key
                messages[key] = RemoteChatMessage(
                    key = key,
                    role = RemoteRole.Assistant,
                    text = "",
                    streaming = true,
                    time = System.currentTimeMillis(),
                )
                publish()
            }

            "chunk" -> {
                val key = liveKey ?: return
                val chunk = frame.optJSONObject("chunk") ?: return
                val current = messages[key] ?: return
                when (chunk.optString("type")) {
                    "text-delta" -> messages[key] = current.copy(text = current.text + chunk.optString("text"))
                    "reasoning-delta" -> messages[key] = current.copy(thinking = current.thinking + chunk.optString("text"))
                    "finish", "block-end" -> Unit
                }
                publish()
            }

            "end" -> {
                liveKey?.let { key -> messages[key]?.let { messages[key] = it.copy(streaming = false) } }
                liveKey = null
                publish()
            }
        }
    }

    private fun publish() {
        chat = chat?.copy(messages = messages.values.toList())
    }

    private fun updateMessageByToolCall(callId: String, transform: (RemoteChatMessage) -> RemoteChatMessage) {
        val key = "t:$callId"
        messages[key]?.let { messages[key] = transform(it) }
        publish()
    }

    private fun contentText(content: JSONArray?): String {
        if (content == null) return ""
        val builder = StringBuilder()
        for (i in 0 until content.length()) {
            val block = content.optJSONObject(i) ?: continue
            when (block.optString("type")) {
                "text" -> builder.append(block.optString("text"))
                "image" -> builder.append("[图片]")
            }
        }
        return builder.toString().trim()
    }

}

/** Pure ownership gate. Session operations survive re-following; WS callbacks do not. */
internal data class RemoteOperationKey(
    val profileId: String?, val target: Long, val sessionId: String?,
    val session: Long?, val follow: Long? = null,
)
internal class RemoteGenerationGate {
    var target = 0L; private set
    var session = 0L; private set
    var follow = 0L; private set
    fun nextTarget() { target++; session++; follow++ }
    fun nextSession() { session++; follow++ }
    fun nextFollow() { follow++ }
    fun key(profileId: String?, sessionId: String?, sessionScoped: Boolean = false) =
        RemoteOperationKey(profileId, target, sessionId, if (sessionScoped) session else null)
    fun accepts(key: RemoteOperationKey, profileId: String?, sessionId: String?): Boolean =
        key.profileId == profileId && key.target == target &&
            (key.session == null || (key.session == session && key.sessionId == sessionId)) &&
            (key.follow == null || key.follow == follow)
}

/** Pure fixtures for the existing desktop wire protocol; no Android JSONObject mock required. */
internal object RemoteProtocol {
    private fun KJsonObject.text(key: String): String = (this[key] as? KJsonPrimitive)?.contentOrNull.orEmpty()
    private fun KJsonObject.number(key: String): Long = text(key).toLongOrNull() ?: 0L
    private fun KJsonObject.flag(key: String): Boolean = (this[key] as? KJsonPrimitive)?.booleanOrNull == true
    fun parseSessions(raw: String): List<RemoteSession> {
        val obj = KJson.parseToJsonElement(raw) as? KJsonObject ?: error("会话列表响应不是对象")
        val items = obj["items"] as? KJsonArray ?: error("会话列表响应缺少 items")
        return items.mapNotNull { element ->
            val row = element as? KJsonObject ?: return@mapNotNull null
            val id = row.text("sessionId")
            if (row.flag("blank") || id.isBlank()) return@mapNotNull null
            val values = ((row["projections"] as? KJsonObject)?.get("values") as? KJsonObject)
            val model = (((values?.get("modelSelection") as? KJsonObject)?.get("lastUsed") as? KJsonObject)?.text("model")).orEmpty()
            val lastPrompt = (values?.get("sessionListMetadata") as? KJsonObject)?.number("lastPromptAt") ?: 0
            RemoteSession(id, values?.text("title").orEmpty().ifBlank { id }, row.text("cwd"),
                row.number("updatedAt"), row.flag("running"), model, lastPrompt.takeIf { it > 0 } ?: row.number("updatedAt"))
        }.sortedByDescending { it.updatedAt }
    }
    fun parseQuestions(callId: String, name: String, arguments: String): List<RemoteAskQuestion> {
        if (name != "ask_user_question") return emptyList()
        val obj = runCatching { KJson.parseToJsonElement(arguments) as? KJsonObject }.getOrNull() ?: return emptyList()
        val rows = obj["questions"] as? KJsonArray ?: return emptyList()
        val seen = hashSetOf<String>()
        return rows.mapIndexedNotNull { index, element ->
            val row = element as? KJsonObject ?: return@mapIndexedNotNull null
            val id = row.text("id").ifBlank { "$callId:$index" }
            if (!seen.add(id)) return@mapIndexedNotNull null
            val options = (row["options"] as? KJsonArray).orEmpty().mapNotNull { value ->
                val option = value as? KJsonObject ?: return@mapNotNull null
                option.text("label").takeIf { it.isNotBlank() }?.let { RemoteAskOption(it, option.text("description")) }
            }
            RemoteAskQuestion(id, row.text("header"), row.text("question"), options,
                row.flag("multi_select") || row.flag("multiSelect"), row.text("detail").ifBlank { null })
        }
    }
    fun pendingQuestionResult(tool: RemoteToolCard?): Boolean {
        if (tool == null || tool.name != "ask_user_question" || tool.isError) return false
        val result = tool.result ?: return false
        val obj = runCatching { KJson.parseToJsonElement(result) as? KJsonObject }.getOrNull() ?: return false
        return obj.flag("pending") && obj.text("callId").let { it.isEmpty() || it == tool.callId }
    }
    // This unary endpoint accepts continued timed questions. Foreground/legacy waits use $events.
    fun questionAnswerable(message: RemoteChatMessage): Boolean =
        message.questions.isNotEmpty() && !message.answered && pendingQuestionResult(message.tool)

    fun promptArgs(sessionId: String, requestId: String, text: String, zone: String): String =
        kotlinx.serialization.json.buildJsonObject {
            put("request", kotlinx.serialization.json.buildJsonObject {
                put("sessionId", KJsonPrimitive(sessionId)); put("requestId", KJsonPrimitive(requestId))
                put("mode", KJsonPrimitive("queue")); put("clientTimeZone", KJsonPrimitive(zone))
                put("content", KJsonArray(listOf(kotlinx.serialization.json.buildJsonObject {
                    put("type", KJsonPrimitive("text")); put("text", KJsonPrimitive(text))
                })))
            })
        }.toString()
    fun answerArgs(sessionId: String, callId: String, answers: List<AskAnswer>): String =
        kotlinx.serialization.json.buildJsonObject {
            put("agentId", KJsonPrimitive(sessionId)); put("callId", KJsonPrimitive(callId))
            put("answer", kotlinx.serialization.json.buildJsonObject {
                put("answers", KJsonArray(answers.map { answer -> kotlinx.serialization.json.buildJsonObject {
                    put("id", KJsonPrimitive(answer.id)); put("selected", KJsonArray(answer.selected.map(::KJsonPrimitive)))
                    answer.custom.takeIf { it.isNotEmpty() }?.let { put("custom", KJsonPrimitive(it)) }
                } }))
            })
        }.toString()
}
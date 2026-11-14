package xin.dshaa.ui.shellapi.remote

import xin.dshaa.core.tools.AskAnswer

/**
 * M85-C3-2：远程 DSH 的**界面只读接口**（`RemoteDshScreen` 用到的成员）。
 *
 * `RemoteDshController`（宿主 `remote` 包）体量大、依赖 WebSocket/通知/Application，
 * 直接搬进 `ui:shell-api` 会把网络实现也拖进 API 模块。这里先抽「界面需要的状态 + 动作」：
 * - 状态在接口里是 `val`（实现类 `var ... private set` 满足 `val`，读它仍触发 Compose 重组）；
 * - 动作只转发，不解释业务；
 * - 远程消息/会话/连接阶段的纯数据类随本接口一起放在 `shellapi.remote`。
 *
 * C3-3 搬 `ui:native-shell` 后，远程页只依赖本接口；具体实现留在 app。
 */
interface ShellRemote {
    val profiles: List<RemoteProfileUi> get() = emptyList()
    val activeProfileId: String? get() = null
    /** Changes on activation/deactivation/credential edits; scopes every HTTP and WS callback. */
    val generation: Long get() = 0L
    val followConnected: Boolean get() = false
    val canSend: Boolean get() = phase == RemotePhase.Connected && chat != null && !sending

    fun selectProfile(id: String): Boolean = false
    /** null token keeps an existing token only while the normalized address stays identical. */
    fun saveProfile(id: String?, name: String, baseUrl: String, token: String?): String? = null
    fun removeProfile(id: String): Boolean = false
    /** Detach transport when switching to the local target; preserves remote drafts/snapshots. */
    fun deactivate() { disconnect() }
    fun updateDraft(text: String) {}
    /** true means this text was accepted for submission; failure never automatically re-sends it. */
    fun trySend(text: String): Boolean { send(text); return false }
    fun answerQuestions(callId: String, answers: List<AskAnswer>) {}

    // ── 连接页状态 ────────────────────────────────────────────────────────────

    val baseUrl: String

    val token: String

    val phase: RemotePhase

    val statusText: String

    val errorText: String?

    // ── 会话列表 ──────────────────────────────────────────────────────────────

    val sessions: List<RemoteSession>

    val sessionsLoading: Boolean

    /** 当前打开的远程会话（null = 还在连接页/会话列表）。 */
    val chat: RemoteChatState?

    /** 正在发送一条 prompt。 */
    val sending: Boolean

    // ── 连接页动作 ────────────────────────────────────────────────────────────

    fun updateBaseUrl(value: String)

    fun updateToken(value: String)

    fun testConnection()

    fun connect()

    fun disconnect()

    fun refreshSessions()

    fun createSession()

    // ── 远程聊天动作 ──────────────────────────────────────────────────────────

    fun openSession(session: RemoteSession)

    fun closeChat()

    fun reconnect()

    fun send(text: String)

    fun stopTurn()

    fun answerQuestion(callId: String, question: RemoteAskQuestion, selected: String)
}

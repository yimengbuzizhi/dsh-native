package xin.dshaa.nativeapp.chat

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import xin.dshaa.core.model.SessionSummary
import xin.dshaa.ui.shellapi.NativeShellHost
import xin.dshaa.ui.shellapi.chat.ChatSessionState

/**
 * 主界面 ↔ 悬浮窗的桥（docs/76 §十三.1）。
 *
 * 与 Operit 的差异：Operit 的悬浮窗在 Service 里自己 `getCore(FLOATING)`；原生没有多槽
 * ChatRuntime，所以这里借主界面的 AppViewModel —— 进程里只有一个对话引擎，不会双开。
 * 悬浮窗打开时如果 host 为空，UI 必须显示「先打开鲸歌」，不能假装能发（docs/76 W 段）。
 */
object ChatMirror {

    /**
     * 当前会话（null = 还没打开/已关闭）。AppViewModel.attach() 会同步写这里，
     * Compose 可观察：换会话时整个对象被替换。
     */
    var state by mutableStateOf<ChatSessionState?>(null)
    /** 主界面会话列表（最近会话浮层用；只读）。 */
    val sessions: List<SessionSummary>
        get() = host?.sessions.orEmpty()

    var host: NativeShellHost? = null

    val hasHost: Boolean get() = host != null

    fun send(text: String): Boolean = host?.submit(text) ?: false

    val sendWhileRunningMode: String get() = host?.settings?.sendWhileRunningMode ?: "queue"

    fun setSendWhileRunningMode(mode: String) { host?.settings?.setSendWhileRunningMode(mode) }

    fun cancelQueuedPrompt(id: String) { host?.cancelQueuedPrompt(id) }

    fun resumeQueuedPrompts() { host?.resumeQueuedPrompts() }

    fun stop() {
        host?.stop()
    }

    fun newChat() {
        host?.newChat()
    }

    fun openSession(id: String) {
        val h = host ?: return
        val summary = h.sessions.firstOrNull { it.id == id } ?: return
        h.openSession(summary)
    }

    /** 无障碍截图进待发附件（WINDOW 附件面板/全屏 chip 共用）。 */
    fun attachScreenshot(): Boolean {
        val h = host ?: return false
        h.attachScreenshot()
        return true
    }

    /**
     * 文本类附件（通知 / 位置，docs/76 W24）：文本由设备侧组好，直接进待发附件。
     * target 在发起读取时捕获，结果只进入该会话的待发草稿。
     */
    fun attachText(name: String, text: String, target: ChatSessionState? = state): Boolean =
        host?.attachTextFile(name, text, target) ?: false

    fun removePendingImage(attachmentId: String) {
        host?.removePendingImage(attachmentId)
    }

    fun removePendingFile(name: String) {
        host?.removePendingFile(name)
    }

    /** 审批：允许/拒绝当前挂起的工具调用。 */
    fun resolveApproval(allow: Boolean, always: Boolean = false) {
        host?.resolveApproval(allow, always)
    }

    /** 提问：提交答案（空答案 = 跳过）。 */
    fun resolveQuestions(answers: List<xin.dshaa.core.tools.AskAnswer>) {
        host?.resolveQuestions(answers)
    }

    /** 悬浮窗里的「打开鲸歌」：把主界面带到前台（不新建任务栈）。 */
    fun openApp(context: Context, screen: String? = null) {
        val intent = Intent().apply {
            setClassName(context.packageName, "xin.dshaa.nativeapp.MainActivity")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            if (screen != null) putExtra("dsh_open_screen", screen)
        }
        runCatching { context.startActivity(intent) }
    }
}

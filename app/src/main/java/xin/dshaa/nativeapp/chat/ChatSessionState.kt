package xin.dshaa.nativeapp.chat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.TextFieldValue
import xin.dshaa.core.model.ChatMessage
import xin.dshaa.core.model.ImageRef

/**
 * 引用「按路径引用」的附件时，跟在 `<attachment …/>` 后面的那句模型提示（M74）。
 * 定义在 chat 包里给 [AppViewModel.send] 和聊天页渲染共用，避免两处文案漂移。
 */
const val ATTACHMENT_REFERENCE_HINT =
    "（带 <attachment> 的附件已复制到工作区，可用 read / read_image 工具按路径读取。）"

/**
 * 一个聊天会话的 **Compose 状态容器**（2026-11-07 替换原来的 `ChatUiState` data class + StateFlow）。
 *
 * 为什么改成这个：旧实现用 `StateFlow<ChatUiState>` + 每次 `copy(messages = list)`，
 * 在 ChatScreen 里出现过「ViewModel 状态在涨、界面停在第一条」的假死（见 docs/14 二.1），
 * 当时只能靠 `key(messages.size, streaming, ...)` 强制重建子树绕过。
 * 现在把每个字段都做成 Compose snapshot state（列表用 [mutableStateListOf]），
 * 读它的人一定被登记为观察者，流式 token 逐字刷新不再依赖任何 key hack。
 */
class ChatSessionState(modelName: String) {
    var sessionId by mutableStateOf<String?>(null)
    var title by mutableStateOf("新会话")
    var model by mutableStateOf(modelName)
    val messages = mutableStateListOf<ChatMessage>()
    var streaming by mutableStateOf(false)
    /**
     * 正在停止（M79，用户 2026-11-12「点停止不能立刻停」）：
     * 点停止后立刻置 true，按钮切成「停止中…」；引擎收尾（写 turn/end、补中断的 tool_result）
     * 结束后由 finally 置回 false。取消本身走协程取消 + 工具自己杀进程，这个标记只管观感。
     */
    var stopping by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var usageText by mutableStateOf<String?>(null)
    var statusText by mutableStateOf<String?>(null)

    /**
     * 输入框草稿（M76）：放在会话状态里，横竖屏切布局 / 宽屏抽屉分支重建 Composables 时不丢。
     * 发送成功后清空；切换会话时随 state 一起切换（每个会话各自保留草稿）。
     */
    var draft by mutableStateOf(TextFieldValue(""))

    /** 本轮开始时间（M76 运行状态条：流式期间显示「运行中 12s」，不让人误以为卡死）。 */
    var turnStartedAt by mutableStateOf(0L)

    /** 上下文圆环（M76）：最近一次请求的 prompt tokens / 窗口大小，窗口为 0 时圆环不显示。 */
    var lastPromptTokens by mutableStateOf(0)
    var lastCompletionTokens by mutableStateOf(0)
    var contextWindow by mutableStateOf(0)

    /** 上下文三段明细的估算 token（系统提示词 / 工具 schema；对话 = prompt - 两者）。 */
    var estSystemTokens by mutableStateOf(0)
    var estToolsTokens by mutableStateOf(0)

    var pendingApproval by mutableStateOf<PendingApproval?>(null)

    /** 旧版备份导入的只读副本：聊天页禁发，只可回看。 */
    var readOnly by mutableStateOf(false)

    /** 只读原因：`import`（旧版导入副本）/ `subagent`（子代理会话回放）；可写会话为 null。 */
    var readOnlyReason by mutableStateOf<String?>(null)

    /**
     * 本轮开始前的消息数（fork 子代理的 seed 边界：只继承**已完成**的轮次，
     * 当前这轮还在跑，不在 seed 里）。普通会话也会写，零成本。
     */
    @Volatile
    var turnStartIndex: Int = 0

    /** 当前会话的目标（goal 工具写入，读时从 goal/change 折叠）。 */
    var goal by mutableStateOf<xin.dshaa.core.model.GoalView?>(null)

    /** 计划模式（M18）：已落库的开关、轮次内排队的选择、是否曾经进过。 */
    var planActive by mutableStateOf(false)
    var planPending by mutableStateOf<Boolean?>(null)
    var planEntered by mutableStateOf(false)

    /** 斜杠命令（M21）的瞬态结果（上游「分离结果 notice」）：不写进聊天正文，几秒后自动消失。 */
    var commandNotice by mutableStateOf<String?>(null)

    /**
     * 斜杠命令要求打开的输入条面板（M40/M41）：`/model` → [CommandSheet.MODEL]，
     * `/permission` → [CommandSheet.PERMISSION]，`/help` → [CommandSheet.HELP]。
     * 命令在 ViewModel 里解析，面板是 Compose 局部状态，所以用这个一次性信号交接；
     * ChatComposer 打开面板后立刻清空。
     */
    var pendingCommandSheet by mutableStateOf<CommandSheet?>(null)

    /** 模型正在等用户回答的问题（ask_user_question 工具）。 */
    var pendingQuestions by mutableStateOf<List<xin.dshaa.core.tools.AskQuestion>?>(null)

    /**
     * 待发图片（聊天输入条附件，docs/42）：相册/拍照/截图进来先落附件存储，发送时随消息一起进
     * `TurnInput.images`；发送成功立即清空（失败保留草稿）。切换会话时整个 state 被替换，草稿随之丢弃。
     */
    val pendingImages = mutableStateListOf<PendingImage>()

    /**
     * 待发文件（2026-11-08 夜间）：文本/代码类文件按 UTF-8 读成文本，发送时作为额外 text 块
     * 跟着消息一起进模型（图片仍走 [pendingImages]）；PDF/Word 这类二进制暂不支持解析。
     */
    val pendingFiles = mutableStateListOf<PendingFile>()

    /** 附件读取/归一化失败的一次性文案（输入条上方显示；下次操作清空）。 */
    var attachError by mutableStateOf<String?>(null)

    /** 工具结果按 toolCallId 建索引，给消息列表里的工具卡用。 */
    val toolResults: Map<String, ChatMessage>
        get() = messages.asSequence()
            .filter { it.role == xin.dshaa.core.model.MessageRole.TOOL && it.toolCallId != null }
            .associateBy { it.toolCallId!! }
}

/** 一张已落盘、等待随下一条消息发出的图片（[path] 供输入条缩略图直接解码）。 */
data class PendingImage(val ref: ImageRef, val path: String)

/**
 * 一个待发的文本文件（内存里拿着正文；[chars] 用于输入条上显示大小）。
 *
 * 2026-11-09：对齐 Operit「所有文件都能引用」——非文本/超大文件不再拒绝，
 * 原样复制到工作区 `.dsh/attachments/`，这里只记 [path]/[mime]/[size]，
 * 发送时拼成 `<attachment …/>` 让模型用 read / read_image 工具读。
 */
data class PendingFile(
    val name: String,
    val text: String,
    val path: String? = null,
    val mime: String? = null,
    val size: Long = 0,
) {
    val chars: Int get() = text.length
    val isReference: Boolean get() = path != null
}

/** 斜杠命令要打开的输入条面板：模型面板 / 审批方式面板 / 帮助面板（M41）。 */
enum class CommandSheet { MODEL, PERMISSION, HELP }

/**
 * 会话列表右侧的状态标（2026-11-08 夜间）：
 * - [RUNNING] 正在生成（转圈）；
 * - [WAITING] 在等用户回答（审批/提问，橙点）；
 * - [DONE] 生成完了但用户还没打开过这条会话（绿点，看过即消）。
 */
enum class SessionBadge { RUNNING, WAITING, DONE }

data class PendingApproval(
    val id: String,
    val toolName: String,
    val arguments: String,
    val summary: String,
)

/**
 * 「别的会话在等你」（M78 并行收尾）：审批卡/提问框只画在当前会话上，后台会话弹出来时
 * 前台聊天页什么都看不见，只有抽屉里一个橙点。聊天页顶部的横幅用它提示，点一下切过去。
 */
data class WaitingSession(
    val sessionId: String,
    val title: String,
    /** 等审批的工具名；等回答时是「提问」。 */
    val toolName: String,
    /** 审批摘要（命令/路径）或第一条问题正文。 */
    val summary: String,
    /** true = 模型在提问（ask_user_question），false = 工具审批。横幅文案不同。 */
    val question: Boolean = false,
)

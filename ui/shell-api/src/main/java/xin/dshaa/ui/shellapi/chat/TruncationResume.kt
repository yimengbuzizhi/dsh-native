package xin.dshaa.ui.shellapi.chat

import android.content.SharedPreferences

/**
 * H1（docs/182 §H1）：单轮输出触顶后「继续生成」的会话级状态。
 *
 * 引擎把两条截断路径（纯文本、截断在 tool_use 上）都收口成 [xin.dshaa.core.engine.TurnOutcome.MAX_TOKENS]，
 * 宿主据此在这里记一条「这条会话还有截断的回合可续写」。**不写会话 JSONL**：截断事实已经在
 * `turn/end` 的 error 里，这里只存 UI 的可恢复入口，所以按会话 id 放 SharedPreferences。
 *
 * 语义：
 * - [mark] / [pending]：一条会话最多一个待续标记（后一次截断覆盖前一次）；
 * - [consume]：读一次并清掉——「继续生成」点一次只发一轮，连点/重发都拿不到第二次；
 * - [clear]：会话删除/新一轮开始时清理。
 *
 * 不依赖 Android API 的部分单独成型，JVM 单测用假实现就能覆盖（见 `TruncationResumeTest`）。
 */
enum class TruncationKind {
    /** 截断切在 `tool_use` 上：本批工具一次都没执行。 */
    TOOL_CALL,

    /** 纯文本输出截断。 */
    TEXT,
    ;

    companion object {
        /** 读回持久化值；空/未知值一律当「没有待续标记」。 */
        fun fromStored(value: String?): TruncationKind? =
            values().firstOrNull { it.name == value }
    }
}

/** 点「继续生成」时发出去的用户消息（docs/182 §H1 第 3 条原文）。 */
const val CONTINUE_TRUNCATED_PROMPT =
    "上一条回复被截断，请从中断处继续；如果要写文件，请分几段写"

/**
 * 「继续生成」卡片的可行动文案（docs/182 §H1 第 4 条）：
 * ① 点继续生成 ② 让我分几段写 ③ 重发并说明分段 —— 不引导用户去改已隐藏的设置。
 *
 * @param outputTokenLimit 当前单轮上限（2026-11-24 起默认 256000、按窗口收窄），只用于把话说清楚；0 = 不显示具体数值。
 * @param queuedMessages 还有待发送消息时补一句「待发送消息已保留」（与插件回合路径对齐）。
 */
fun truncationResumeNotice(
    kind: TruncationKind,
    outputTokenLimit: Int,
    queuedMessages: Boolean,
): String = buildString {
    append("上一轮")
    if (outputTokenLimit > 0) append("输出达到上限（$outputTokenLimit tokens）") else append("输出被截断")
    append(
        when (kind) {
            TruncationKind.TOOL_CALL -> "，工具调用没有执行。"
            TruncationKind.TEXT -> "，回复被截断。"
        },
    )
    append("可以：① 点「继续生成」从中断处接着写；② 让我分几段写；③ 重发一次并说明分段。")
    if (queuedMessages) append("待发送消息已保留。")
}

/** 截断待续标记的持久化后端（`SharedPreferences` 或单测里的内存假实现）。 */
interface TruncationResumePreferences {
    fun getString(key: String): String?

    /** value 为 null 时删除该键。 */
    fun putString(key: String, value: String?)
}

/** 生产实现：复用 `dsh` 那份 SharedPreferences（不新增文件、不进会话 JSONL）。 */
class SharedPreferencesTruncationResumePreferences(
    private val prefs: SharedPreferences,
) : TruncationResumePreferences {
    override fun getString(key: String): String? = prefs.getString(key, null)

    override fun putString(key: String, value: String?) {
        val editor = prefs.edit()
        if (value == null) editor.remove(key) else editor.putString(key, value)
        editor.apply()
    }
}

/** 会话 id → 待续类型；键前缀独立，不与设置项同名。 */
class TruncationResumeStore(private val prefs: TruncationResumePreferences) {

    fun pending(sessionId: String): TruncationKind? {
        if (sessionId.isBlank()) return null
        return TruncationKind.fromStored(prefs.getString(key(sessionId)))
    }

    fun mark(sessionId: String, kind: TruncationKind) {
        if (sessionId.isBlank()) return
        prefs.putString(key(sessionId), kind.name)
    }

    fun clear(sessionId: String) {
        if (sessionId.isBlank()) return
        prefs.putString(key(sessionId), null)
    }

    /** 读一次并清掉：返回非 null 表示这次点击拿到了「继续生成」的资格。 */
    fun consume(sessionId: String): TruncationKind? {
        val kind = pending(sessionId) ?: return null
        clear(sessionId)
        return kind
    }

    companion object {
        fun key(sessionId: String): String = "chat_resume_truncated:$sessionId"
    }
}

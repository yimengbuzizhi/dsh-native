package xin.dshaa.core.engine

/**
 * 首条消息 → 会话标题（M33）。
 *
 * 之前直接把第一条用户消息 `take(18)`：换行、Markdown 标题、列表符号、代码围栏都会原样进抽屉，
 * 长会话列表看起来又乱又丑。这里只做「本地清洗」，不额外发 LLM 请求：
 * - 取第一行非空文本（跳过空行和代码围栏行）；
 * - 去掉行首的 `#` / `>` / 列表符号，去掉行内反引号；
 * - 连续空白压成一个空格；
 * - 最多 24 个字符（UI 负责省略号），空消息回「图片」。
 *
 * 为什么不做「LLM 起名」：那要多一次模型请求和一份提示词/费用，等用户明确要再说；
 * 本地清洗已经能解决「标题里带一堆 Markdown」的观感问题。
 */
object SessionTitle {

    const val MAX_LENGTH = 24

    fun from(text: String): String {
        val first = text.replace("\r\n", "\n").replace('\r', '\n')
            .lineSequence()
            .map { it.trim() }
            .firstOrNull { it.isNotEmpty() && !it.startsWith("```") }
            ?: return "图片"
        var title = first
            .replace(Regex("""^#{1,6}\s*"""), "")
            .replace(Regex("""^>\s*"""), "")
            .replace(Regex("""^[-*+]\s+"""), "")
            .replace(Regex("""^\d+[.)]\s+"""), "")
            .replace("`", "")
            .replace(Regex("""\s+"""), " ")
            .trim()
        if (title.isEmpty()) return "图片"
        if (title.length > MAX_LENGTH) title = title.take(MAX_LENGTH)
        return title
    }
}

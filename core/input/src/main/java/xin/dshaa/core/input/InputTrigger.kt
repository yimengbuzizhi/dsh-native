package xin.dshaa.core.input

/**
 * 聊天输入框的触发器检测（M21，对齐上游 `dsh-client-ui-input-trigger` 的 core 语义）。
 *
 * 规则：
 * - `/` 只在 token 开头（文本开头或空白之后）触发命令菜单；
 * - `@` 同样只在 token 开头触发文件引用菜单（`email@host` 不触发）；
 * - token 到下一个空白为止；`@"…"` 是带引号的路径，允许空格；
 * - 查询文本是「触发器之后、光标之前」的内容；光标不在 token 内时不触发。
 *
 * 这里只做纯文本计算，不读文件、不查技能目录——UI 拿到 [InputTriggerToken] 后自己取候选。
 */
enum class InputTriggerKind { COMMAND, FILE }

data class InputTriggerToken(
    val kind: InputTriggerKind,
    /** token 的起始下标（`/` 或 `@` 本身）。 */
    val start: Int,
    /** token 的结束下标（不含）；替换 token 时用 `text.replaceRange(start, end, …)`。 */
    val end: Int,
    /** 触发器之后、光标之前的查询文本。 */
    val query: String,
    /** `@"…"` 形式（路径里可能有空格）。 */
    val quoted: Boolean = false,
) {
    /** 菜单去重/抑制用的稳定键：同一个 token、同一段查询不重复弹。 */
    fun key(): String = "$kind:$start:$end:$quoted:$query"
}

/** 在 [text] 的 [cursor] 处检测 `/` / `@` 触发器；没有就返回 null。 */
fun inputTrigger(text: String, cursor: Int): InputTriggerToken? {
    if (cursor <= 0 || cursor > text.length) return null
    // 从光标往前找最近的、位于 token 开头的触发器。带引号的路径里有空格，
    // 所以不能只靠「往前扫到空白」——必须逐个检查候选起点。
    for (start in cursor - 1 downTo 0) {
        val trigger = text[start]
        if (trigger != '/' && trigger != '@') continue
        if (start > 0 && !text[start - 1].isWhitespace()) continue
        if (trigger == '/') {
            // 命令 token 里不允许空白（光标在空白之后就不算命令 token）
            if (text.substring(start + 1, cursor).any { it.isWhitespace() }) continue
            var end = cursor
            while (end < text.length && !text[end].isWhitespace()) end++
            return InputTriggerToken(InputTriggerKind.COMMAND, start, end, text.substring(start + 1, cursor))
        }
        // `@"…"`：引号内的空格不结束 token；闭合引号出现后菜单关闭。
        if (start + 1 < text.length && text[start + 1] == '"') {
            var close = -1
            for (i in start + 2 until text.length) {
                if (text[i] == '"') {
                    close = i
                    break
                }
            }
            if (close in start + 2 until cursor) return null
            val query = if (cursor <= start + 1) "" else text.substring(start + 2, cursor)
            return InputTriggerToken(
                kind = InputTriggerKind.FILE,
                start = start,
                end = if (close >= 0) close + 1 else text.length,
                query = query,
                quoted = true,
            )
        }
        if (text.substring(start + 1, cursor).any { it.isWhitespace() }) continue
        var end = cursor
        while (end < text.length && !text[end].isWhitespace()) end++
        return InputTriggerToken(InputTriggerKind.FILE, start, end, text.substring(start + 1, cursor))
    }
    return null
}

/**
 * 把选中的文件/目录插入成 mention（上游 `formatFileMention()`）：
 * 普通路径 = `@path`；目录带尾斜杠 = `@dir/`；含空白的路径 = `@"path with spaces"`，
 * 目录的斜杠放在闭合引号之前 = `@"dir/"`。
 *
 * 路径里含双引号/控制字符时上游的格式化器会拒绝；这里返回 null，菜单不展示该候选。
 */
fun formatFileMention(path: String, directory: Boolean): String? {
    val normalized = path.replace('\\', '/').trim('/')
    if (normalized.isEmpty()) return null
    if (normalized.any { it == '"' || it.isISOControl() }) return null
    val rendered = if (directory) "$normalized/" else normalized
    return if (rendered.any { it.isWhitespace() }) "@\"$rendered\"" else "@$rendered"
}

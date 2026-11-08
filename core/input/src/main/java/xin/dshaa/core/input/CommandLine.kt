package xin.dshaa.core.input

/**
 * 上游 `@deepseek-ai/dsh-commands` 的 `parseCommand()` 逐字移植（M21）。
 *
 * 语法（README.zh「命令语法」）：第 0 字节必须是 `/`，随后是小写名称（`a-z` 开头，
 * 可含 `a-z0-9_-`），之后是输入末尾或**一个**空白字节（`\t` / `\n` / `\r` / 空格）。
 * 名称之后的每个字节——包括分隔空白——都是命令自己的 `rawInput`。
 *
 * 不符合语法（含未知名称）的行**不会**变成模型提示词：UI 适配器会拒绝它，而不是静默降级。
 */
data class CommandInvocation(val name: String, val rawInput: String)

/**
 * 解析一整行命令；不是命令时返回 null。
 *
 * 手写扫描而不是正则：Java 的 `$` 与 JS 的 `$` 在行尾换行上的语义不同，
 * 这里要与上游 `parseCommand()` 的 `/^\/([a-z][a-z0-9_-]*)(?=$|[\t\n\r ])/u` 完全一致。
 */
fun parseCommandLine(line: String): CommandInvocation? {
    if (line.isEmpty() || line[0] != '/') return null
    if (line.length < 2) return null
    var i = 1
    if (line[i] !in 'a'..'z') return null
    i++
    while (i < line.length) {
        val c = line[i]
        val nameChar = c in 'a'..'z' || c in '0'..'9' || c == '_' || c == '-'
        if (!nameChar) break
        i++
    }
    // 名称之后必须是输入末尾或一个空白字节；`/plan!` / `/planx/y` 都不是命令。
    if (i < line.length && line[i] != ' ' && line[i] != '\t' && line[i] != '\n' && line[i] != '\r') return null
    return CommandInvocation(line.substring(1, i), line.substring(i))
}

package xin.dshaa.core.skill

/**
 * SKILL.md 的 YAML frontmatter 解析（上游 `dsh-skill-filesystem` 的 `parseFrontmatter` / `parseSkillFile`）。
 *
 * 上游用完整的 YAML 解析器；Android 不引三方 YAML，这里实现**够用的子集**并把边界写清楚：
 * - 只支持顶层标量键（`key: value`）、单双引号、行内注释、`|` / `>` 块标量；
 * - 嵌套映射（`metadata:`）会被跳过（上游只把 `metadata` 原样存进 summary，原生版不用它）；
 * - 不支持锚点/别名/流式集合/多文档——这些在技能文件里没有先例，遇到就当无效文件忽略。
 *
 * 拒收规则与上游逐条对齐（`parseSkillFile`）：
 * 1. 第一行必须是 `---`；找不到闭合 `---` → 无效；
 * 2. frontmatter 不是映射 / 缺 `name`/`description` / 名字不合 kebab-case → 无效；
 * 3. `disableModelInvocation` / `modelInvocable` / `userInvocable` 是旧字段 → 无效（上游抛错后整文件忽略）；
 * 4. `disable-model-invocation` / `user-invocable` 必须是布尔（true/false/1/0/yes/no/on/off）→ 否则无效。
 */
object SkillFrontmatter {

    /** 解析结果：标量字段 + 正文（正文按上游 `parsed.body.trim()` 去掉首尾空白）。 */
    data class Parsed(val data: Map<String, String>, val body: String)

    /** 解析失败（调用方按「忽略该技能文件」处理）。 */
    class Invalid(message: String) : Exception(message)

    fun parse(raw: String): Parsed? {
        val firstLineEnd = raw.indexOf('\n')
        if (firstLineEnd < 0) return null
        if (raw.substring(0, firstLineEnd).removeSuffix("\r") != "---") return null
        val start = firstLineEnd + 1
        val closing = findClosing(raw, start) ?: return null
        val yaml = raw.substring(start, closing.first)
        val data = parseMapping(yaml)
        return Parsed(data, raw.substring(closing.second).trim())
    }

    /** 上游 `findClosingFrontmatter`：逐行找一条 `---`；返回（闭合行起点，正文起点）。 */
    private fun findClosing(raw: String, start: Int): Pair<Int, Int>? {
        var lineStart = start
        while (lineStart <= raw.length) {
            val nextNewline = raw.indexOf('\n', lineStart)
            val lineEnd = if (nextNewline < 0) raw.length else nextNewline
            if (raw.substring(lineStart, lineEnd).removeSuffix("\r") == "---") {
                return lineStart to (if (nextNewline < 0) raw.length else nextNewline + 1)
            }
            if (nextNewline < 0) return null
            lineStart = nextNewline + 1
        }
        return null
    }

    /**
     * 顶层 `key: value` 解析。缩进行属于上一个键（嵌套映射或块标量），
     * 除了 `|` / `>` 块标量要收集正文，其余嵌套内容丢弃。
     */
    private fun parseMapping(yaml: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        val lines = yaml.split('\n')
        var index = 0
        while (index < lines.size) {
            val line = lines[index].removeSuffix("\r")
            index++
            if (line.isBlank()) continue
            if (line.trimStart().startsWith("#")) continue
            if (line.first().isWhitespace()) continue // 悬挂缩进（上面已经处理过块内容）
            val colon = line.indexOf(':')
            if (colon <= 0) throw Invalid("frontmatter line is not a mapping entry: $line")
            val key = line.substring(0, colon).trim()
            val restRaw = line.substring(colon + 1).trim()
            if (restRaw == "|" || restRaw == "|-" || restRaw == "|+" ||
                restRaw == ">" || restRaw == ">-" || restRaw == ">+"
            ) {
                val (block, next) = readBlockScalar(lines, index, folded = restRaw.startsWith(">"))
                index = next
                out[key] = block
                continue
            }
            if (restRaw.isEmpty()) {
                // 嵌套映射（metadata: / invocation: …）：跳过所有缩进行
                while (index < lines.size) {
                    val next = lines[index].removeSuffix("\r")
                    if (next.isBlank() || next.first().isWhitespace()) {
                        index++
                        continue
                    }
                    break
                }
                continue
            }
            out[key] = parseScalar(restRaw)
        }
        return out
    }

    /** 块标量：收集所有比当前行缩进更深的行（去掉公共缩进）。 */
    private fun readBlockScalar(lines: List<String>, start: Int, folded: Boolean): Pair<String, Int> {
        val body = ArrayList<String>()
        var index = start
        var indent = -1
        while (index < lines.size) {
            val line = lines[index].removeSuffix("\r")
            if (line.isBlank()) {
                body += ""
                index++
                continue
            }
            if (!line.first().isWhitespace()) break
            val width = line.indexOfFirst { !it.isWhitespace() }
            if (indent < 0) indent = width
            body += if (line.length > indent) line.substring(indent) else ""
            index++
        }
        while (body.isNotEmpty() && body.last().isEmpty()) body.removeAt(body.size - 1)
        val text = if (folded) {
            // `>`：单行换行折叠成空格，空行保留成换行
            buildString {
                body.forEachIndexed { i, l ->
                    if (i > 0) append(if (l.isEmpty() || body[i - 1].isEmpty()) "\n" else " ")
                    append(l)
                }
            }
        } else {
            body.joinToString("\n")
        }
        return text to index
    }

    /** 标量：单双引号原样取内容，否则去掉行内 ` #` 注释。 */
    private fun parseScalar(raw: String): String {
        if (raw.length >= 2 && raw.first() == '"' && raw.last() == '"') {
            return raw.substring(1, raw.length - 1).replace("\\\"", "\"").replace("\\\\", "\\")
        }
        if (raw.length >= 2 && raw.first() == '\'' && raw.last() == '\'') {
            return raw.substring(1, raw.length - 1).replace("''", "'")
        }
        val comment = raw.indexOf(" #")
        return (if (comment >= 0) raw.substring(0, comment) else raw).trim()
    }

    /** 上游 `frontmatterBoolean`：布尔、1/0 与其字符串形式、true/yes/on 与 false/no/off。 */
    fun boolean(data: Map<String, String>, key: String): Boolean? {
        val value = data[key] ?: return null
        return when (value.lowercase()) {
            "true", "yes", "on", "1" -> true
            "false", "no", "off", "0" -> false
            else -> throw Invalid("frontmatter field \"$key\" must be a boolean")
        }
    }

    /** 上游 `parseInvocationPolicy`：旧字段一律拒收；缺省 = 模型可调用 + 用户可调用。 */
    fun invocation(data: Map<String, String>): SkillInvocation {
        for ((legacy, canonical) in listOf(
            "disableModelInvocation" to "disable-model-invocation",
            "modelInvocable" to "disable-model-invocation",
            "userInvocable" to "user-invocable",
        )) {
            if (data.containsKey(legacy)) {
                throw Invalid("frontmatter field \"$legacy\" is unsupported; use \"$canonical\"")
            }
        }
        val disableModel = boolean(data, "disable-model-invocation")
        val userInvocable = boolean(data, "user-invocable")
        return SkillInvocation(
            modelInvocable = disableModel != true,
            userInvocable = userInvocable != false,
        )
    }
}

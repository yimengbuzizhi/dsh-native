package xin.dshaa.core.plugins

/**
 * DSH 组合包补丁（`dsh.bundle.patch` → `cordis.patch.yml`）的**只读解析**（M61）。
 *
 * DSH 的补丁文件是「对插件图的一串操作」：
 * ```
 * - insert:
 *     - id: timer
 *       name: '@deepseek-ai/cordis-plugin-timer'
 *     - id: hmr
 *       name: '@deepseek-ai/dsh-hmr'
 *       disabled: true
 * - id: session-title-llm      # 修改已存在的行
 *   disabled: true
 * ```
 *
 * 鲸歌没有 npm 安装环境，不能按 `name` 真的去装子插件；本解析器只把 `insert:` 行读出来，
 * 在插件详情里列出「这个组合包由哪些子插件组成」，并记录不认识的/修改已有行的条目。
 * 不执行 `config:`、`inject:`（只标记存在），也不执行 `!!js` 表达式（按 disabled 处理并记 note）。
 */
data class BundleChild(
    val id: String,
    val name: String,
    val disabled: Boolean = false,
    val hasConfig: Boolean = false,
    val inject: List<String> = emptyList(),
    /** `disabled: !!js "..."` 这类动态表达式：鲸歌静态视为停用。 */
    val dynamicDisabled: Boolean = false,
)

data class BundlePatch(
    val children: List<BundleChild> = emptyList(),
    /** 未执行但需要让用户知道的条目（修改已有行 / 不认识的键）。 */
    val notes: List<String> = emptyList(),
    /** 组合包里声明的**助手预设**（M74，`@deepseek-ai/dsh-agent-preset` + `dsh-persona`）。 */
    val presets: List<BundlePreset> = emptyList(),
)

/**
 * 组合包里的助手预设（M74）。
 *
 * 上游 DSH 没有「角色卡」，官方写法是 `preset`（`@deepseek-ai/dsh-agent-preset` 的
 * `config.id/name/description/order` + 一串 `config.plugins[]` 子插件行），人设由其中
 * `@deepseek-ai/dsh-persona` 的 `config.prefix` / `config.suffix` 两段文本提供。
 *
 * 鲸歌只把它**读出来**给「助手配置」页展示、可一键套用成一份角色卡；
 * 不执行补丁里的其它配置（和 M61 的只读立场一致）。
 */
data class BundlePreset(
    val presetId: String,
    /** `config.name`（人话名字；没有就用 id）。 */
    val name: String = "",
    val description: String = "",
    val order: Int? = null,
    /** `dsh-persona.config.prefix` → 角色卡的「人设」段。 */
    val personaPrefix: String = "",
    /** `dsh-persona.config.suffix` → 角色卡的「系统提示词」段。 */
    val personaSuffix: String = "",
    /** `config.plugins` 里的子插件行数（含分组里的），给用户一个规模感。 */
    val pluginCount: Int = 0,
)

object BundlePatchParser {

    fun parse(text: String): BundlePatch {
        val presets = runCatching { extractPresets(YamlSubsetParser(text).parseDocument()) }.getOrDefault(emptyList())
        return parseLines(text.lines()).copy(presets = presets)
    }

    fun parseFile(file: java.io.File): BundlePatch? =
        runCatching { parse(file.readText(Charsets.UTF_8)) }.getOrNull()

    private class Draft(
        var id: String = "",
        var name: String = "",
        var disabled: Boolean = false,
        var hasConfig: Boolean = false,
        var inject: List<String> = emptyList(),
        var dynamicDisabled: Boolean = false,
        val unknown: MutableList<String> = mutableListOf(),
    ) {
        fun finish(): BundleChild? {
            val resolvedName = name.ifBlank { id }
            if (resolvedName.isBlank()) return null
            return BundleChild(
                id = id.ifBlank { resolvedName },
                name = resolvedName,
                disabled = disabled,
                hasConfig = hasConfig,
                inject = inject,
                dynamicDisabled = dynamicDisabled,
            )
        }
    }

    private fun parseLines(lines: List<String>): BundlePatch {
        val children = mutableListOf<BundleChild>()
        val notes = mutableListOf<String>()
        var insertIndent = -1
        /** insert 下第一个子插件行的缩进：只有这个缩进的 `- ` 才是 insert 的子插件（config 里嵌套的行不是）。 */
        var entryIndent = -1
        /** 当前子插件自己的键缩进：config 子树里的 id/name 不能覆盖行本身（M74 修）。 */
        var keyIndent = -1
        var current: Draft? = null
        var currentIndent = -1

        fun flush() {
            current?.finish()?.let { child ->
                children.add(child)
                if (child.dynamicDisabled) {
                    notes += "子插件 ${child.name} 的 disabled 是动态表达式，静态按停用显示"
                }
                if (child.hasConfig) {
                    notes += "子插件 ${child.name} 带 config（只读：预设/persona 会读，其它不执行）"
                }
            }
            current = null
            currentIndent = -1
            keyIndent = -1
        }

        var index = 0
        while (index < lines.size) {
            val raw = lines[index]
            index++
            val stripped = raw.trimStart()
            if (stripped.isEmpty() || stripped.startsWith("#")) continue
            val indent = raw.length - stripped.length

            // 不在 insert 块里：只记录「对已有行的修改」，不执行。
            if (insertIndent < 0) {
                if (stripped.startsWith("- insert:")) {
                    insertIndent = indent
                    entryIndent = -1
                    flush()
                    continue
                }
                if (stripped.startsWith("- id:")) {
                    val id = valueOf(stripped.removePrefix("- id:"))
                    notes += "修改已有行 id=$id（鲸歌不执行补丁里的改行操作）"
                }
                continue
            }

            // insert 块内：遇到同级或更浅的新操作 → 结束 insert 块，重新按顶层处理这一行。
            if (indent <= insertIndent && stripped.startsWith("- ") && !stripped.startsWith("- insert:")) {
                flush()
                insertIndent = -1
                entryIndent = -1
                index--
                continue
            }
            if (!stripped.startsWith("- ")) {
                // 属于当前子插件的后续键（name / disabled / config / inject …）
                val draft = current ?: continue
                if (keyIndent < 0) keyIndent = indent
                // 更深的是 config 子树（preset 的 plugins[] 里也有 id/name），不覆盖当前行
                if (indent != keyIndent) continue
                val (key, value) = splitKeyValue(stripped)
                when (key) {
                    "id" -> draft.id = stripQuotes(value)
                    "name" -> draft.name = stripQuotes(value)
                    "disabled" -> {
                        if (value.startsWith("!!js")) {
                            draft.dynamicDisabled = true
                            draft.disabled = true
                        } else {
                            draft.disabled = value.equals("true", ignoreCase = true)
                        }
                    }
                    "config" -> draft.hasConfig = true
                    "inject" -> draft.inject = parseInlineList(value)
                    else -> if (key.isNotBlank()) draft.unknown += key
                }
                continue
            }

            // 新的子插件条目
            val rest = stripped.removePrefix("- ").trim()
            if (entryIndent < 0) entryIndent = indent
            // config 子树里嵌套的行（`plugins:` 下的 `- id: persona`）不算 insert 的子插件
            if (indent != entryIndent) continue
            flush()
            currentIndent = indent
            val draft = Draft()
            current = draft
            val (key, value) = splitKeyValue(rest)
            when (key) {
                "id" -> draft.id = stripQuotes(value)
                "name" -> draft.name = stripQuotes(value)
                "disabled" -> {
                    if (value.startsWith("!!js")) {
                        draft.dynamicDisabled = true
                        draft.disabled = true
                    } else {
                        draft.disabled = value.equals("true", ignoreCase = true)
                    }
                }
                "config" -> draft.hasConfig = true
                "inject" -> draft.inject = parseInlineList(value)
                else -> {
                    if (rest.isNotBlank() && !rest.startsWith("insert:")) {
                        notes += "不认识的 insert 条目：$rest"
                    }
                }
            }
        }
        flush()
        return BundlePatch(children = children, notes = notes.distinct())
    }

    private fun splitKeyValue(text: String): Pair<String, String> {
        val colon = text.indexOf(':')
        if (colon < 0) return text.trim() to ""
        return text.substring(0, colon).trim() to text.substring(colon + 1).trim()
    }

    private fun stripQuotes(value: String): String =
        value.trim().removeSurrounding("'").removeSurrounding("\"")

    private fun valueOf(raw: String): String = stripQuotes(raw.trim())

    /** `[a, b]` → list；其它形态返回空。 */
    private fun parseInlineList(value: String): List<String> {
        val text = value.trim()
        if (!text.startsWith("[") || !text.endsWith("]")) return emptyList()
        return text.removeSurrounding("[", "]")
            .split(',')
            .map { stripQuotes(it.trim()) }
            .filter { it.isNotEmpty() }
    }

    // ---- M74：读 `config:` 子树里的助手预设（@deepseek-ai/dsh-agent-preset + dsh-persona） ----

    private const val AGENT_PRESET = "@deepseek-ai/dsh-agent-preset"
    private const val PERSONA_PLUGIN = "@deepseek-ai/dsh-persona"

    private class PluginRow(val name: String, val config: Map<String, YamlNode>)

    /** 递归扫整棵补丁树，找出所有 `@deepseek-ai/dsh-agent-preset` 行。 */
    private fun extractPresets(root: YamlNode): List<BundlePreset> {
        val found = mutableListOf<BundlePreset>()
        fun walk(node: YamlNode) {
            when (node) {
                is YamlNode.ListNode -> node.items.forEach(::walk)
                is YamlNode.MapNode -> {
                    val rowName = node.scalar("name")
                    if (rowName == AGENT_PRESET) node.toPreset()?.let { found += it }
                    node.entries.values.forEach(::walk)
                }
                is YamlNode.Scalar -> Unit
            }
        }
        walk(root)
        return found
    }

    private fun YamlNode.MapNode.toPreset(): BundlePreset? {
        val config = entries["config"] as? YamlNode.MapNode ?: return null
        val presetId = config.scalar("id")?.takeIf { it.isNotBlank() }
            ?: scalar("id")?.takeIf { it.isNotBlank() }
            ?: return null
        val rows = collectPluginRows(config)
        val personas = rows.filter { it.name == PERSONA_PLUGIN }
        fun firstValue(key: String): String = personas
            .mapNotNull { (it.config[key] as? YamlNode.Scalar)?.value }
            .firstOrNull { it.isNotBlank() }
            .orEmpty()
        return BundlePreset(
            presetId = presetId,
            name = config.scalar("name")?.takeIf { it.isNotBlank() } ?: presetId,
            description = config.scalar("description").orEmpty(),
            order = config.scalar("order")?.toIntOrNull(),
            personaPrefix = firstValue("prefix").trim(),
            personaSuffix = firstValue("suffix").trim(),
            pluginCount = rows.size,
        )
    }

    /**
     * 收集 `config.plugins` 里的子插件行（`cordis:group` 的 `config:` 又是一层序列，递归进去）。
     * 只从 `plugins` 出发，**不**把 preset 的 config 映射本身当成一行（它也有 name/id）。
     */
    private fun collectPluginRows(config: YamlNode.MapNode): List<PluginRow> {
        val rows = mutableListOf<PluginRow>()
        fun walkRow(node: YamlNode) {
            when (node) {
                is YamlNode.ListNode -> node.items.forEach(::walkRow)
                is YamlNode.MapNode -> {
                    val name = node.scalar("name")
                    if (name != null) {
                        rows += PluginRow(name, (node.entries["config"] as? YamlNode.MapNode)?.entries.orEmpty())
                    }
                    // 分组行（name: cordis:group）的 config 是下一层行列表
                    (node.entries["config"] as? YamlNode.ListNode)?.let(::walkRow)
                }
                is YamlNode.Scalar -> Unit
            }
        }
        (config.entries["plugins"] as? YamlNode.ListNode)?.let(::walkRow)
        return rows
    }

    private fun YamlNode.MapNode.scalar(key: String): String? =
        (entries[key] as? YamlNode.Scalar)?.value?.takeIf { it.isNotBlank() }?.trim()
}

/**
 * `cordis.patch.yml` 的最小 YAML 子集解析（M74）：块映射 / 块序列 / 块标量（`|`、`>`）/
 * 引号标量 / `!!js` 表达式（按普通文本读）。
 *
 * 只服务于「读出助手预设」这一件事，**不是**完整 YAML：不支持锚点、多文档、流式映射嵌套。
 * 解析失败（抛异常）时调用方降级成「没有预设」，不影响 M61 已有的组合包展示。
 */
private sealed interface YamlNode {
    data class Scalar(val value: String) : YamlNode
    data class MapNode(val entries: LinkedHashMap<String, YamlNode> = LinkedHashMap()) : YamlNode
    data class ListNode(val items: MutableList<YamlNode> = mutableListOf()) : YamlNode
}

private class YamlSubsetParser(text: String) {

    private val work = text.lines().toMutableList()
    private var pos = 0

    fun parseDocument(): YamlNode {
        skipIgnorable()
        if (pos >= work.size) return YamlNode.Scalar("")
        return parseNode()
    }

    private fun indentOf(line: String): Int {
        var index = 0
        while (index < line.length && line[index] == ' ') index++
        return index
    }

    private fun skipIgnorable() {
        while (pos < work.size) {
            val stripped = work[pos].trimStart()
            if (stripped.isEmpty() || stripped.startsWith("#")) pos++ else return
        }
    }

    private fun parseNode(): YamlNode {
        skipIgnorable()
        if (pos >= work.size) return YamlNode.Scalar("")
        val raw = work[pos]
        val text = raw.trimStart()
        val indent = indentOf(raw)
        return if (text == "-" || text.startsWith("- ")) parseSequence(indent) else parseMapping(indent)
    }

    private fun parseSequence(indent: Int): YamlNode {
        val items = mutableListOf<YamlNode>()
        while (true) {
            skipIgnorable()
            if (pos >= work.size) break
            val raw = work[pos]
            val currentIndent = indentOf(raw)
            val text = raw.trimStart()
            if (currentIndent != indent || !(text == "-" || text.startsWith("- "))) break
            val rest = if (text == "-") "" else text.removePrefix("- ").trim()
            pos++
            if (rest.isEmpty()) {
                skipIgnorable()
                items += if (pos < work.size && indentOf(work[pos]) > indent) parseNode() else YamlNode.Scalar("")
                continue
            }
            if (!KEY_ENTRY.containsMatchIn(rest)) {
                items += YamlNode.Scalar(unquote(rest))
                continue
            }
            // `- id: x` 这种「序列项直接是映射」：把首个键当映射的第一行，
            // 缩进取下一行的缩进（2/4 空格两种风格都能吃）。
            val mapIndent = when {
                rest.endsWith(":") -> indent + 2
                else -> {
                    skipIgnorable()
                    if (pos < work.size && indentOf(work[pos]) > indent) indentOf(work[pos]) else indent + 2
                }
            }
            val entries = LinkedHashMap<String, YamlNode>()
            parseMappingEntry(entries, rest, mapIndent)
            while (true) {
                skipIgnorable()
                if (pos >= work.size) break
                val rawEntry = work[pos]
                val entryIndent = indentOf(rawEntry)
                val entryText = rawEntry.trimStart()
                if (entryIndent != mapIndent) break
                if (entryText == "-" || entryText.startsWith("- ")) break
                pos++
                parseMappingEntry(entries, entryText, mapIndent)
            }
            items += YamlNode.MapNode(entries)
        }
        return YamlNode.ListNode(items)
    }

    private fun parseMapping(indent: Int): YamlNode {
        val entries = LinkedHashMap<String, YamlNode>()
        while (true) {
            skipIgnorable()
            if (pos >= work.size) break
            val raw = work[pos]
            val currentIndent = indentOf(raw)
            val text = raw.trimStart()
            if (currentIndent != indent) break
            if (text == "-" || text.startsWith("- ")) break
            pos++
            parseMappingEntry(entries, text, indent)
        }
        return YamlNode.MapNode(entries)
    }

    private fun parseMappingEntry(entries: MutableMap<String, YamlNode>, text: String, keyIndent: Int) {
        val colon = text.indexOf(':')
        if (colon < 0) return
        val key = text.substring(0, colon).trim()
        if (key.isEmpty()) return
        val value = text.substring(colon + 1).trim()
        when {
            // 块标量：`|` 逐行保留换行，`>` 折叠成空格（人设正文常用 |）
            value.startsWith("|") || value.startsWith(">") ->
                entries[key] = YamlNode.Scalar(readBlockScalar(keyIndent, value))
            value.isEmpty() -> {
                skipIgnorable()
                entries[key] = if (pos < work.size && indentOf(work[pos]) > keyIndent) {
                    parseNode()
                } else {
                    YamlNode.Scalar("")
                }
            }
            else -> entries[key] = YamlNode.Scalar(unquote(value))
        }
    }

    private fun readBlockScalar(parentIndent: Int, indicator: String): String {
        val folded = indicator.startsWith(">")
        val raw = mutableListOf<String>()
        while (pos < work.size) {
            val line = work[pos]
            if (line.isBlank()) {
                raw += ""
                pos++
                continue
            }
            if (indentOf(line) <= parentIndent) break
            raw += line
            pos++
        }
        while (raw.isNotEmpty() && raw.first().isBlank()) raw.removeAt(0)
        while (raw.isNotEmpty() && raw.last().isBlank()) raw.removeAt(raw.size - 1)
        if (raw.isEmpty()) return ""
        val base = raw.filter { it.isNotBlank() }.minOf { indentOf(it) }
        val lines = raw.map { line -> if (line.isBlank()) "" else line.drop(minOf(base, line.length)) }
        if (!folded) return lines.joinToString("\n")
        val builder = StringBuilder()
        var pendingBreak = false
        lines.forEach { line ->
            if (line.isBlank()) {
                pendingBreak = true
                return@forEach
            }
            if (builder.isNotEmpty()) builder.append(if (pendingBreak) '\n' else ' ')
            pendingBreak = false
            builder.append(line)
        }
        return builder.toString()
    }

    private fun unquote(value: String): String {
        // 行尾注释：`false # 说明`（引号里的 # 不动）
        var text = value.trim()
        if (!text.startsWith("'") && !text.startsWith("\"")) {
            val comment = text.indexOf(" #")
            if (comment >= 0) text = text.substring(0, comment).trim()
        }
        if (text.length >= 2) {
            if (text.startsWith("'") && text.endsWith("'")) {
                return text.substring(1, text.length - 1).replace("''", "'")
            }
            if (text.startsWith("\"") && text.endsWith("\"")) {
                return text.substring(1, text.length - 1)
            }
        }
        return text
    }

    private companion object {
        /** `key:` 形状（`- key: value` / `- key:`）；避免把普通标量当映射。 */
        val KEY_ENTRY = Regex("^[A-Za-z_][A-Za-z0-9_.\\-]*\\s*:")
    }
}

package xin.dshaa.core.skill

/**
 * 「用户直接 `/名字` 调用技能」的手势识别（M21，逐字对齐上游 `dsh-tool-skill` 的
 * `invokedSkillNames()` 与 `SKILL_GESTURE`）。
 *
 * 规则：
 * - 只在 `source.kind:"user"` 的消息里扫描（调用方负责过滤；goal/通知/工具结果不能伪造手势）；
 * - 每个 text 块独立扫描；`/name` 必须在文本开头或空白之后，且后面是空白或文本结尾；
 * - 一个 `/` 后必须是 kebab-case 技能名（`a-z0-9` 与单个连字符）；
 * - 按首次出现顺序去重（同一名字只注入一次）。
 */
private val SKILL_GESTURE = Regex("""(^|\s)/([a-z0-9]+(?:-[a-z0-9]+)*)(?=\s|$)""")

/** 从直接用户输入的各 text 块里提取候选技能名（尚未与目录核验）。 */
fun invokedSkillNames(texts: Iterable<String>): List<String> {
    val names = ArrayList<String>()
    for (text in texts) {
        for (match in SKILL_GESTURE.findAll(text)) {
            val name = match.groupValues[2]
            if (name.isNotEmpty() && name !in names) names += name
        }
    }
    return names
}

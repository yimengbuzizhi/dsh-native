package xin.dshaa.core.skill

/**
 * 技能（skill）模型：对齐上游 `@deepseek-ai/dsh-skill` 的能力缝。
 *
 * 上游把「技能从哪来（provider）」与「技能怎么给模型（tool-skill 的目录 + loader）」分开：
 * - `dsh-skill`：名字语法、调用策略、`<skill_content>` 渲染（本文件）；
 * - `dsh-skill-filesystem`：project/user 根目录发现、YAML frontmatter 解析（[SkillFile] / [SkillDiscovery]）；
 * - `dsh-tool-skill`：`skill` 工具 + 会话内技能目录消息（`source.kind:"skill-catalog"`）。
 *
 * 原生版只做文件系统 provider（Android 没有别的 provider），但把三层的**可观察行为**逐字保留：
 * 名字正则、frontmatter 字段与拒收规则、目录消息文案、`<skill_content>` 外壳、错误文案。
 */

/** 上游 `dsh-skill` 的 `SKILL_NAME`：kebab-case，且不允许首尾/连续连字符。 */
private val SKILL_NAME = Regex("^[a-z0-9]+(?:-[a-z0-9]+)*$")

/** 一个名字是不是合法的技能名（上游 `isSkillName`）。 */
fun isSkillName(name: String): Boolean = SKILL_NAME.matches(name)

/** 调用策略（上游 `SkillInvocation`）：文件系统 provider 用 frontmatter 的两个布尔开关算出来。 */
data class SkillInvocation(
    val modelInvocable: Boolean = true,
    val userInvocable: Boolean = true,
)

/**
 * 技能资源基址（上游 `SkillResourceBase` 的三种 kind）。
 * 文件系统 provider 只产出 [directory]；另外两种留给以后的插件 provider。
 */
sealed interface SkillResourceBase {
    data class Directory(val path: String) : SkillResourceBase
    data class Url(val url: String) : SkillResourceBase
    data class Opaque(val description: String) : SkillResourceBase
}

/** 目录消息里的一条（上游 `catalogSourceEntries()` 的形状，字段顺序 name → description）。 */
data class SkillCatalogEntry(val name: String, val description: String)

/** 一条发现结果（上游 `SkillSummary` 的投影：目录消息 + 工具加载只需要这些字段）。 */
data class SkillSummary(
    val name: String,
    val description: String,
    val whenToUse: String? = null,
    val invocation: SkillInvocation = SkillInvocation(),
    /** project-dsh / project-agents / custom / user-dsh / user-agents / bundled。 */
    val source: String,
    /** provider 名（文件系统 provider = `filesystem`）。 */
    val provider: String = SkillFileSystemProvider.NAME,
    /** 根目录优先级（越小越优先；同层内先发现者胜）。 */
    val rank: Int = 0,
    /** SKILL.md 的绝对路径（诊断用）。 */
    val path: String,
    /** 资源基址：目录型技能是自己的目录，平铺 `.md` 技能是根目录。 */
    val directory: String,
    /** 调用策略是否允许模型加载（目录消息/工具都按它过滤）。 */
) {
    val modelInvocable: Boolean get() = invocation.modelInvocable
    val userInvocable: Boolean get() = invocation.userInvocable

    fun toContent(content: String): SkillContent = SkillContent(
        name = name,
        description = description,
        whenToUse = whenToUse,
        invocation = invocation,
        source = source,
        provider = provider,
        path = path,
        directory = directory,
        content = content,
    )
}

/** 加载后的完整技能（上游 `Skill`：summary + 正文）。 */
data class SkillContent(
    val name: String,
    val description: String,
    val whenToUse: String? = null,
    val invocation: SkillInvocation = SkillInvocation(),
    val source: String = "",
    val provider: String = SkillFileSystemProvider.NAME,
    val path: String = "",
    val directory: String = "",
    val content: String = "",
)

/** 一次发现的结果（上游 `snapshot()` 的 `{skills, complete}`）。 */
data class SkillSnapshot(
    val skills: List<SkillSummary> = emptyList(),
    /** false = 有根目录/条目读失败（上游 `incomplete`：目录消息要带「发现不完整」提示）。 */
    val complete: Boolean = true,
)

/** `skill` 工具加载结果（上游 `execute()` 的三条错误分支 + 成功分支）。 */
sealed interface SkillLoad {
    data class Ok(val skill: SkillContent) : SkillLoad

    /** 名字不合 kebab-case 语法：`invalid skill name "..."`。 */
    data class InvalidName(val name: String) : SkillLoad

    /** 没发现 / 刚被删：`skill "..." is unknown or no longer available`。 */
    data class Unknown(val name: String) : SkillLoad

    /** 发现但 `disable-model-invocation: true`：`skill "..." is not available for model invocation`。 */
    data class NotModelInvocable(val name: String) : SkillLoad
}

/** 技能目录/加载的后端（`skill` 工具与 `core:engine` 的 catalog 注入共用）。 */
interface SkillHost {
    /** 当前技能目录快照（按名字码元序）。 */
    fun snapshot(): SkillSnapshot

    /** 按名字加载（名字不合法/不存在/不可模型调用分别返回对应分支）。 */
    fun load(name: String): SkillLoad

    /**
     * 用户直接 `/名字` 调用（M21）：不做 modelInvocable 检查——`disable-model-invocation`
     * 的技能只能从这条路径进入；`user-invocable: false` 的技能返回 [SkillLoad.Unknown]。
     */
    fun loadForUser(name: String): SkillLoad = load(name)
}

/** 上游 `escapeText`：把 provider 提供的散文塞进伪 XML 前转义 `&`/`<`/`>`。 */
fun escapeText(value: String): String =
    value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

/** 上游 `escapeAttr`：属性值转义 `&`/`"`/`<`（技能名本来就过 [isSkillName]，这里只防御）。 */
private fun escapeAttr(value: String): String =
    value.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;")

/**
 * 上游 `renderSkillContent()`：`skill` 工具结果与「用户直接 /名字 调用」共用同一种外壳。
 * 正文逐字嵌入（技能是本地受信内容），名字走转义属性。
 */
fun renderSkillContent(skill: SkillContent): String {
    val resourceHint = renderResourceHint(skill)
    return buildList {
        add("<skill_content name=\"${escapeAttr(skill.name)}\">")
        add("<skill_resources>")
        addAll(resourceHint)
        add("</skill_resources>")
        add("")
        add("<skill_instructions>")
        add(skill.content)
        add("</skill_instructions>")
        add("</skill_content>")
    }.joinToString("\n")
}

/** 上游 `renderResourceHint()`：按资源基址的 kind 给模型一句「相对路径按哪解析」。 */
private fun renderResourceHint(skill: SkillContent): List<String> {
    val base = skill.directory.takeIf { it.isNotEmpty() }
        ?: return listOf(
            "Resources for this skill are managed by provider \"${escapeText(skill.provider)}\".",
            "Load referenced resources only as needed.",
        )
    return listOf(
        "Base directory for this skill: ${escapeText(base)}",
        "Resolve relative paths mentioned by this skill against the base directory before using them. " +
            "Load referenced resources only as needed.",
    )
}

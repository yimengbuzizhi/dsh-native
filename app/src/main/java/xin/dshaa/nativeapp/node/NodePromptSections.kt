package xin.dshaa.nativeapp.node

import xin.dshaa.core.prompt.PromptSection

/**
 * Node 插件提示词段与原生段的合并（docs/187 §九 第 11 项）。
 *
 * 两条纪律原样落在代码里，JVM 用例直接断言：
 * 1. Node 段**只认领插件自己的**：宿主侧用「加载前后差集」过滤（host.mjs 的
 *    `ownedSectionNames`），原生侧再挡一次 dsh 的 identity/persona/harness 等保留名——
 *    任何一路漏了都不会让三方插件混进 dsh 身份/人设；
 * 2. 关掉 Node 宿主后段落必须消失：`hostEnabled=false` 时一个 Node 段都不进来，
 *    不依赖「关开关时恰好清过缓存」这种时序。
 */
object NodePromptSections {

    /** dsh 自己的身份/人设/编排段名：Node 插件不许认领（大小写不敏感）。 */
    private val RESERVED = setOf(
        "identity",
        "persona",
        "harness",
        "system",
        "agent-identity",
        "dsh-identity",
        "dsh-persona",
        "dsh-harness",
    )

    /** 过滤掉空段与保留名，只留下插件自己的段。 */
    fun owned(sections: List<PromptSection>): List<PromptSection> = sections.filter { section ->
        section.name.isNotBlank() &&
            section.text.isNotBlank() &&
            section.name.lowercase() !in RESERVED
    }

    /** 原生段在前、Node 段在后（同名覆盖交给 PromptPack 的既有规则）。 */
    fun merge(
        native: List<PromptSection>,
        node: List<PromptSection>,
        hostEnabled: Boolean,
    ): List<PromptSection> = if (hostEnabled) native + owned(node) else native
}

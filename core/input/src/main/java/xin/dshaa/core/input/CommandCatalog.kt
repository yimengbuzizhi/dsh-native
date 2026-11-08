package xin.dshaa.core.input

/**
 * `/` 菜单里的一条**宿主命令**（M21 起步只有 `/plan`；M40 补 `/model`、`/permission`；
 * M41 补 `/help`）。
 *
 * 放在 `:core:input` 而不是 UI 里：`/` 菜单、`/help` 面板、命令派发三处都要用同一份表，
 * 而且纯数据可以单测（app 模块没有 JVM 单测）。
 *
 * @param name 命令名（不含 `/`，必须符合 [parseCommandLine] 的名称语法）
 * @param description 菜单里的一行说明
 * @param usage 参数用法（含 `$` 占位）；不带参数的命令为空串
 */
data class CommandChoice(
    val name: String,
    val description: String,
    val usage: String = "",
) {
    /** 菜单/帮助里显示的完整用法：`/model [<名字>]`。 */
    fun display(): String = if (usage.isBlank()) "/$name" else "/$name $usage"

    /**
     * 点帮助面板里这一行时插进输入框的文本（M41）：带参数的命令补一个空格，
     * 光标落在参数位；不带参数的只放命令名。
     */
    fun insertText(): String = if (usage.isBlank()) "/$name" else "/$name "
}

/**
 * 原生已实现的斜杠命令。**只登记真的能执行的**：菜单里出现但点了没反应，比不出现更糟。
 *
 * 其余上游命令（`/goal`…）等各自能力补齐后再挂。
 */
val DSH_COMMANDS: List<CommandChoice> = listOf(
    CommandChoice(
        name = "plan",
        description = "进入或退出计划模式：模型先出方案，批准后再执行",
        usage = "[off|消息]",
    ),
    CommandChoice(
        name = "model",
        description = "切换模型：不带参数打开模型面板，带名字直接切",
        usage = "[名字]",
    ),
    CommandChoice(
        name = "permission",
        description = "工具审批方式：不带参数打开面板，带参数直接设",
        usage = "[auto|ask]",
    ),
    CommandChoice(
        name = "compact",
        description = "把较早的历史压缩成一份摘要，腾出上下文（不带参数）",
        usage = "",
    ),
    CommandChoice(
        name = "help",
        description = "查看全部斜杠命令与用法（点一行插入输入框）",
        usage = "[命令名]",
    ),
)

/** 按名字找宿主命令（`submit()` 的派发用；找不到返回 null，再由技能目录认领）。 */
fun findCommand(name: String): CommandChoice? = DSH_COMMANDS.firstOrNull { it.name == name }

/**
 * `/permission` 的参数归一化（M40）：`auto` / `always` → `auto`，`ask` / `every` → `ask`。
 *
 * 只认英文小写参数（与 `parseCommandLine` 的名称语法一致，用户打字不用切输入法）；
 * 空串 = 不带参数（打开面板），其余 = 非法参数（返回 null）。
 */
fun parseApprovalArg(raw: String): String? = when (raw.trim().lowercase()) {
    "auto", "always", "allow" -> "auto"
    "ask", "every", "confirm" -> "ask"
    else -> null
}

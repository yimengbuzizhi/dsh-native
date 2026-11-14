package xin.dshaa.core.tools

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import xin.dshaa.core.tools.agents.InterruptAgentTool
import xin.dshaa.core.tools.agents.ListAgentsTool
import xin.dshaa.core.tools.agents.SendMessageTool
import xin.dshaa.core.tools.agents.SubagentTool
import xin.dshaa.core.tools.ask.AskUserQuestionTool
import xin.dshaa.core.tools.fs.EditTool
import xin.dshaa.core.tools.fs.GlobTool
import xin.dshaa.core.tools.fs.GrepTool
import xin.dshaa.core.tools.fs.ReadImageTool
import xin.dshaa.core.tools.fs.ReadTool
import xin.dshaa.core.tools.fs.WriteTool
import xin.dshaa.core.tools.goal.CreateGoalTool
import xin.dshaa.core.tools.goal.GetGoalTool
import xin.dshaa.core.tools.goal.UpdateGoalTool
import xin.dshaa.core.tools.jobs.JobKillTool
import xin.dshaa.core.tools.jobs.JobListTool
import xin.dshaa.core.tools.jobs.JobOutputTool
import xin.dshaa.core.tools.plan.ExitPlanModeTool
import xin.dshaa.core.tools.present.PresentTool
import xin.dshaa.core.tools.schedule.ScheduleCreateTool
import xin.dshaa.core.tools.schedule.ScheduleDeleteTool
import xin.dshaa.core.tools.schedule.ScheduleListTool
import xin.dshaa.core.tools.schedule.ScheduleUpdateTool
import xin.dshaa.core.tools.shell.BashTool
import xin.dshaa.core.tools.shell.WorkingDirectoryTool
import xin.dshaa.core.tools.skill.SkillTool
import xin.dshaa.core.tools.web.WebFetchTool
import xin.dshaa.core.tools.web.WebSearchTool
import xin.dshaa.core.tools.workspace.LoadWorkspaceDependenciesTool
import xin.dshaa.core.tools.workflow.WorkflowTool

/**
 * 内置工具的**插件目录**（M84-A2）：32 个工具按内置插件 id 分组，每组一个 [Group]。
 *
 * 为什么放 core:tools：这里全是纯 JVM 工具实现，不依赖 Android；
 * App 层（`BuiltinPlugins`）只保留开关/名称/页面，实例与分组由这里提供。
 * 关掉某组时 App 会把该组的工具从 [ToolRegistry] 注销（不是只过滤 tools[]），
 * 打开时再注册——`DefaultTools.kt` 的中心清单已删除。
 */
object BuiltinToolCatalog {

    /** 一个内置插件组：id 与 `BuiltinPlugins` 的插件 id 一致。 */
    data class Group(val id: String, val tools: List<DshTool>)

    val groups: List<Group> = listOf(
        Group(
            "builtin.fs",
            listOf(ReadTool(), ReadImageTool(), WriteTool(), EditTool(), GlobTool(), GrepTool()),
        ),
        Group("builtin.shell", listOf(BashTool(), WorkingDirectoryTool())),
        Group("builtin.jobs", listOf(JobOutputTool(), JobListTool(), JobKillTool())),
        Group("builtin.web", listOf(WebFetchTool(), WebSearchTool())),
        Group(
            "builtin.agent",
            listOf(SubagentTool(), SubagentTool(fork = true), ListAgentsTool(), SendMessageTool(), InterruptAgentTool()),
        ),
        Group(
            "builtin.schedule",
            listOf(ScheduleCreateTool(), ScheduleListTool(), ScheduleUpdateTool(), ScheduleDeleteTool()),
        ),
        Group("builtin.goal", listOf(GetGoalTool(), CreateGoalTool(), UpdateGoalTool())),
        Group("builtin.plan", listOf(ExitPlanModeTool())),
        Group("builtin.interaction", listOf(AskUserQuestionTool(), PresentTool())),
        Group("builtin.todo", listOf(TodoWriteTool())),
        Group("builtin.skill", listOf(SkillTool())),
        Group("builtin.workflow", listOf(WorkflowTool(), LoadWorkspaceDependenciesTool())),
    )

    /** 全部 32 个内置工具（不区分开关，给测试/调试看清单）。 */
    fun allTools(): List<DshTool> = groups.flatMap { it.tools }

    fun group(id: String): Group? = groups.firstOrNull { it.id == id }

    /** 同一工具名被分到多个组（应为空）。 */
    fun duplicateTools(): List<String> {
        val seen = mutableSetOf<String>()
        return allTools().map { it.name }.filter { !seen.add(it) }
    }

    /** 目录里有、但预期集合没有的工具（测试用）。 */
    fun unexpected(expected: Collection<String>): List<String> =
        allTools().map { it.name }.filter { it !in expected }
}

/** 待办清单：状态存会话内（ctx.state），UI 后续接 todo 面板。 */
class TodoWriteTool : DshTool {
    override val name = "todo_write"

    data class Todo(val content: String, val status: String)

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val array = args["todos"] as? JsonArray ?: return ToolResult.err("missing todos")
        val todos = array.mapNotNull { el ->
            val obj = runCatching { el.jsonObject }.getOrNull() ?: return@mapNotNull null
            val content = runCatching { obj["content"]?.jsonPrimitive?.contentOrNull }.getOrNull() ?: return@mapNotNull null
            val status = runCatching { obj["status"]?.jsonPrimitive?.contentOrNull }.getOrNull() ?: "pending"
            Todo(content, status)
        }
        ctx.state["todos"] = todos
        val body = todos.joinToString("\n") { t ->
            val mark = when (t.status) {
                "completed" -> "[x]"
                "in_progress" -> "[~]"
                else -> "[ ]"
            }
            "$mark ${t.content}"
        }
        return ToolResult.ok("Updated todo list (${todos.size} items):\n$body")
    }
}

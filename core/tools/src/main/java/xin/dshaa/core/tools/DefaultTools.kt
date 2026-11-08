package xin.dshaa.core.tools

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import xin.dshaa.core.tools.ask.AskUserQuestionTool
import xin.dshaa.core.tools.agents.InterruptAgentTool
import xin.dshaa.core.tools.agents.ListAgentsTool
import xin.dshaa.core.tools.agents.SendMessageTool
import xin.dshaa.core.tools.agents.SubagentTool
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

object DefaultTools {
    fun registry(): ToolRegistry = ToolRegistry(
        listOf(
            ReadTool(),
            ReadImageTool(),
            WriteTool(),
            EditTool(),
            GlobTool(),
            GrepTool(),
            BashTool(),
            WorkingDirectoryTool(),
            WebFetchTool(),
            WebSearchTool(),
            AskUserQuestionTool(),
            PresentTool(),
            TodoWriteTool(),
            GetGoalTool(),
            CreateGoalTool(),
            UpdateGoalTool(),
            ScheduleCreateTool(),
            ScheduleListTool(),
            ScheduleUpdateTool(),
            ScheduleDeleteTool(),
            JobOutputTool(),
            JobListTool(),
            JobKillTool(),
            SubagentTool(),
            SubagentTool(fork = true),
            ListAgentsTool(),
            SendMessageTool(),
            InterruptAgentTool(),
            ExitPlanModeTool(),
            LoadWorkspaceDependenciesTool(),
            SkillTool(),
            // 第 32 个工具（上游 fixtures 的 workflow；执行在 App 的 QuickJS 宿主里）
            WorkflowTool(),
        ),
    )
}

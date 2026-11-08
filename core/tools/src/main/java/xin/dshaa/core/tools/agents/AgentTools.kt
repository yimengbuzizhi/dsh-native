package xin.dshaa.core.tools.agents

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import xin.dshaa.core.tools.AgentHost
import xin.dshaa.core.tools.DshTool
import xin.dshaa.core.tools.ToolContext
import xin.dshaa.core.tools.ToolResult

/**
 * 子代理五件套（工具 24–28）：`subagent` / `subagent_fork` / `list_agents` /
 * `send_message` / `interrupt_agent`。
 *
 * schema 来自版本化 `tools.json`（契约 #2），这里只实现模型侧行为与文案：
 * - `subagent` 立刻返回 `started subagent <id>`，完成时由运行时投递结算通知；
 * - `list_agents` 空列表是 `(no subagents)`；children 行是
 *   `<id> [running|inactive] — <label>`，descendants 额外带 ` parent=<id> depth=<n>`；
 * - `send_message` 只回执 `message delivered to agent <id>`（不返回对方答案）；
 * - `interrupt_agent` 立刻回 `interrupt requested for agent <id>`。
 */
private fun host(ctx: ToolContext): AgentHost? = ctx.agents

private fun unavailable(): ToolResult =
    ToolResult.err("subagents unavailable: no agent runtime is mounted")

class SubagentTool(private val fork: Boolean = false) : DshTool {
    override val name: String = if (fork) "subagent_fork" else "subagent"

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val host = host(ctx) ?: return unavailable()
        val description = args.string("description")?.trim().orEmpty()
        if (description.isEmpty()) return ToolResult.err("description is required")
        val prompt = args.string("prompt").orEmpty()
        if (prompt.isEmpty()) return ToolResult.err("prompt is required")
        val started = runCatching {
            host.start(description, prompt, args.string("cwd"), fork)
        }.getOrElse { return ToolResult.err(it.message ?: "subagent start failed") }
        return ToolResult.ok(
            text = "started subagent ${started.id}",
            meta = mapOf("subagentId" to started.id),
        )
    }
}

class ListAgentsTool : DshTool {
    override val name = "list_agents"

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val host = host(ctx) ?: return unavailable()
        val scope = args.string("scope") ?: "children"
        if (scope != "children" && scope != "descendants") {
            return ToolResult.err("scope must be children or descendants")
        }
        val entries = runCatching { host.list(scope) }
            .getOrElse { return ToolResult.err(it.message ?: "list_agents failed") }
        if (entries.isEmpty()) return ToolResult.ok("(no subagents)")
        return ToolResult.ok(
            entries.joinToString("\n") { entry ->
                val at = if (scope == "descendants") {
                    " parent=${entry.parentId} depth=${entry.depth}"
                } else {
                    ""
                }
                if (entry.diagnostic != null) {
                    "${entry.id} [diagnostic: ${entry.diagnostic}]$at"
                } else {
                    "${entry.id} [${entry.status}]$at — ${entry.label}"
                }
            },
        )
    }
}

class SendMessageTool : DshTool {
    override val name = "send_message"

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val host = host(ctx) ?: return unavailable()
        val agentId = args.string("agent_id")?.trim().orEmpty()
        if (agentId.isEmpty()) return ToolResult.err("agent_id is required")
        val message = args.string("message").orEmpty()
        if (message.isEmpty()) return ToolResult.err("message is required")
        runCatching { host.send(agentId, message) }
            .getOrElse { return ToolResult.err(it.message ?: "send_message failed") }
        return ToolResult.ok("message delivered to agent $agentId")
    }
}

class InterruptAgentTool : DshTool {
    override val name = "interrupt_agent"

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val host = host(ctx) ?: return unavailable()
        val agentId = args.string("agent_id")?.trim().orEmpty()
        if (agentId.isEmpty()) return ToolResult.err("agent_id is required")
        runCatching { host.interrupt(agentId) }
            .getOrElse { return ToolResult.err(it.message ?: "interrupt_agent failed") }
        return ToolResult.ok("interrupt requested for agent $agentId")
    }
}

private fun JsonObject.string(name: String): String? =
    runCatching { this[name]?.jsonPrimitive?.contentOrNull }.getOrNull()

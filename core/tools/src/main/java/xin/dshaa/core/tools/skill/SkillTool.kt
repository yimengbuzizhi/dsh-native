package xin.dshaa.core.tools.skill

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import xin.dshaa.core.skill.SkillLoad
import xin.dshaa.core.skill.renderSkillContent
import xin.dshaa.core.tools.DshTool
import xin.dshaa.core.tools.ToolContext
import xin.dshaa.core.tools.ToolResult

/**
 * `skill`（工具 31/32）：加载一个技能的完整说明。
 *
 * 上游 `dsh-tool-skill` 的行为链：
 * 1. 名字必须过 kebab-case 语法（`invalid skill name "..."`）；
 * 2. 必须在**当前发现的目录**里（`skill "..." is unknown or no longer available`）；
 * 3. 必须允许模型调用（`disable-model-invocation: true` → `skill "..." is not available for model invocation`）；
 * 4. 结果正文 = `<skill_content name="...">` 外壳（与「用户直接调用」共用同一渲染，见 `renderSkillContent`）。
 *
 * 工具本身是只读的（上游 `presentCall` 的 kind = "read"）：审批策略为「每次询问」时也不弹窗，
 * 子代理也可以直接调用。
 */
class SkillTool : DshTool {
    override val name = "skill"

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val host = ctx.skills ?: return ToolResult.err("no skill service is mounted")
        val requested = runCatching { args["name"]?.jsonPrimitive?.contentOrNull }.getOrNull().orEmpty()
        return when (val load = host.load(requested)) {
            is SkillLoad.InvalidName -> ToolResult.err("invalid skill name \"${load.name}\"")
            is SkillLoad.Unknown -> ToolResult.err("skill \"${load.name}\" is unknown or no longer available")
            is SkillLoad.NotModelInvocable -> ToolResult.err("skill \"${load.name}\" is not available for model invocation")
            is SkillLoad.Ok -> ToolResult.ok(renderSkillContent(load.skill), meta = mapOf("skill" to load.skill.name))
        }
    }
}

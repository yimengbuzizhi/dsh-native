package xin.dshaa.core.tools.shell

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import xin.dshaa.core.tools.DshTool
import xin.dshaa.core.tools.ToolContext
import xin.dshaa.core.tools.ToolResult
import xin.dshaa.core.tools.resolvePath

/**
 * `working_directory`：读当前目录 / 用 `cd` 切目录。
 * 上游语义（tools.json）：只影响后续工具的相路径解析；已启动的 shell 不受影响。
 */
class WorkingDirectoryTool : DshTool {
    override val name = "working_directory"

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val cd = runCatching { args["cd"]?.jsonPrimitive?.contentOrNull }.getOrNull()
        if (cd.isNullOrBlank()) return ToolResult.ok(ctx.cwd.absolutePath)
        val dir = resolvePath(cd, ctx)
        if (!dir.isDirectory) return ToolResult.err("not a directory: ${dir.absolutePath}")
        ctx.cwd = dir
        return ToolResult.ok(dir.absolutePath)
    }
}

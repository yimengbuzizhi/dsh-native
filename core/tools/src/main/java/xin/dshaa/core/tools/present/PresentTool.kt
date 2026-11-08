package xin.dshaa.core.tools.present

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import xin.dshaa.core.tools.DshTool
import xin.dshaa.core.tools.ToolContext
import xin.dshaa.core.tools.ToolResult
import xin.dshaa.core.tools.resolvePath

/**
 * `present`：把已有的文件声明为交付物（最多 4 个）。
 * 工具本身只校验路径、返回文本；卡片由 UI 从参数渲染（见 ChatScreen.PresentedCard）。
 */
class PresentTool : DshTool {
    override val name = "present"

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val files = args["files"] as? JsonArray ?: return ToolResult.err("missing files")
        if (files.isEmpty()) return ToolResult.err("files is empty")
        if (files.size > 4) return ToolResult.err("at most 4 files per call")
        val lines = ArrayList<String>()
        files.forEach { el ->
            val obj = runCatching { el.jsonObject }.getOrNull() ?: return@forEach
            val path = runCatching { obj["path"]?.jsonPrimitive?.contentOrNull }.getOrNull() ?: return@forEach
            val description = runCatching { obj["description"]?.jsonPrimitive?.contentOrNull }.getOrNull()
            val file = resolvePath(path, ctx)
            if (!file.isFile) {
                return ToolResult.err("not a file: $path")
            }
            val size = file.length()
            lines += "- ${file.absolutePath} (${humanSize(size)})" + if (!description.isNullOrBlank()) " — $description" else ""
        }
        if (lines.isEmpty()) return ToolResult.err("no valid files")
        return ToolResult.ok("Presented ${lines.size} file(s):\n" + lines.joinToString("\n"))
    }

    private fun humanSize(bytes: Long): String = when {
        bytes >= 1024 * 1024 -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
        bytes >= 1024 -> "%.1f KB".format(bytes / 1024.0)
        else -> "$bytes B"
    }
}

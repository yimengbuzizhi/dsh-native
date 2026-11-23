package xin.dshaa.core.tools.primitives

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import xin.dshaa.core.primitives.PrimitiveArgs
import xin.dshaa.core.primitives.PrimitiveCatalog
import xin.dshaa.core.primitives.PrimitiveDefinition
import xin.dshaa.core.primitives.PrimitiveResult
import xin.dshaa.core.tools.DshTool
import xin.dshaa.core.tools.ToolContext
import xin.dshaa.core.tools.ToolResult

/**
 * P1（`docs/185` §四/§九）：**工具面是目录的投影**，不再有 6 个手写工具类。
 *
 * 一个 [PrimitiveDefinition] 生成一个工具：名字 = id 把 `.` 换 `_`，schema = 目录里的
 * `parameters`，执行只做「参数校验 → [PrimitiveHost.invoke] → 结果转写」。
 * 插件桥面/原生面走同一个 `PrimitiveHost`，所以同一动作只有一份实现。
 */
class PrimitiveTool(private val definition: PrimitiveDefinition) : DshTool {

    override val name: String = PrimitiveCatalog.toolName(definition.id)

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val host = ctx.primitives
            ?: return ToolResult.err("primitives unavailable: no primitive host is mounted")
        PrimitiveArgs.validate(definition, args)?.let { reason ->
            return ToolResult.err("invalid ${definition.id}: $reason")
        }
        val result = try {
            host.invoke(definition.id, args)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            PrimitiveResult.error(e.message?.takeIf { it.isNotBlank() } ?: "${definition.id} failed")
        }
        // meta 只放标量（工具卡/日志用）；结构化数据留在 PrimitiveResult.data 给插件/原生面。
        val meta = result.data.mapNotNull { (key, value) ->
            val primitive = value as? JsonPrimitive ?: return@mapNotNull null
            key to primitive.content
        }.toMap()
        return if (result.isError) {
            ToolResult.err(result.summary, meta)
        } else {
            ToolResult.ok(result.summary, meta)
        }
    }
}

/** 本版可执行的原语工具（顺序 = 目录顺序；App 注册进 `ToolRegistry`）。 */
object PrimitiveTools {
    val all: List<DshTool> = PrimitiveCatalog.available.map { PrimitiveTool(it) }
}

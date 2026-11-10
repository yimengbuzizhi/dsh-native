package xin.dshaa.nativeapp.device.tools

import android.app.Application
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import xin.dshaa.core.tools.ToolResult

/**
 * Operit `Tools.System.terminal.*` 的宿主实现（M68，docs/95）。
 *
 * 语义对照（Operit `Tools.System.terminal`）：
 * | Operit | 鲸歌 |
 * |---|---|
 * | `create(sessionName)` → `{sessionId, sessionName, state}` | [TerminalTools] 的 `create_terminal_session`（同名会话复用） |
 * | `exec(sessionId, command, timeoutMs)` | `execute_in_terminal_session`（真 PTY、跨调用有状态、超时 Ctrl+C） |
 * | `input(sessionId, {input, control})` | `input_in_terminal_session`（含 ctrl+c / 方向键 / enter） |
 * | `screen(sessionId)` | `get_terminal_session_screen`（ansi 屏幕快照） |
 * | `close(sessionId)` | `close_terminal_session` |
 * | `hiddenExec(command, options)` | 保持走 `shell.exec`（不进 PTY，和以前一致） |
 *
 * 返回给 JS 的是 `{ok:true, text, meta}`：`text` 是内置工具的中文/英文文本输出，
 * `meta` 里带 `session_id` 等结构化字段（[TerminalTools] 本来就给了），JS 侧据此拼出
 * Operit 的返回形状。失败 `{ok:false, error}`，由 `__dshHostOk` 抛成 Error。
 */
class TerminalPluginHost(private val app: Application) {

    private val tools: TerminalTools by lazy { TerminalTools(app) }

    suspend fun call(action: String, args: JsonObject): String {
        val result = runCatching { tools.invokeForHost(action, args) }
            .getOrElse { return fail("终端操作 $action 失败：${it.message ?: it.javaClass.simpleName}") }
        return if (result.isError) fail(result.text) else ok(result)
    }

    private fun ok(result: ToolResult): String = buildJsonObject {
        put("ok", true)
        put("text", result.text)
        put(
            "meta",
            buildJsonObject {
                result.meta.forEach { (key, value) -> put(key, JsonPrimitive(value)) }
            },
        )
    }.toString()

    private fun fail(message: String): String = buildJsonObject {
        put("ok", false)
        put("error", message)
    }.toString()
}

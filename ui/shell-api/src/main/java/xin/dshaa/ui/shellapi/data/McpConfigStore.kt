package xin.dshaa.ui.shellapi.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject

/** 一个 MCP 服务器的本地配置（2026-11-09 包管理：MCP 包管理的第一步是配置与导入）。 */
data class McpServer(
    val id: String,
    val name: String,
    /** stdio / http（由配置里有没有 url 推断）。 */
    val type: String,
    /** 展示摘要：stdio 显示 command + args，http 显示 url。 */
    val summary: String,
    val enabled: Boolean,
    /** 原始 JSON 对象（后续启动 MCP 进程时直接喂给客户端）。 */
    val raw: String,
)

/**
 * MCP 服务器配置存储（`dsh_mcp` SharedPreferences）。
 *
 * 只做配置管理 + 导入：解析标准 `{"mcpServers": {...}}` 或 `{"<name>": {...}}`，
 * 真正的 MCP 会话（stdio/HTTP JSON-RPC）留给后续里程碑。
 */
class McpConfigStore private constructor(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences("dsh_mcp", Context.MODE_PRIVATE)

    var servers by mutableStateOf(loadAll())
        private set

    private fun loadAll(): List<McpServer> {
        val text = prefs.getString(KEY_SERVERS, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(text)
            buildList {
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val id = obj.optString("id")
                    if (id.isBlank()) continue
                    add(
                        McpServer(
                            id = id,
                            name = obj.optString("name").ifBlank { id },
                            type = obj.optString("type").ifBlank { "stdio" },
                            summary = obj.optString("summary"),
                            enabled = obj.optBoolean("enabled", true),
                            raw = obj.optString("raw"),
                        ),
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun persist(list: List<McpServer>) {
        val arr = JSONArray()
        list.forEach { server ->
            arr.put(
                JSONObject()
                    .put("id", server.id)
                    .put("name", server.name)
                    .put("type", server.type)
                    .put("summary", server.summary)
                    .put("enabled", server.enabled)
                    .put("raw", server.raw),
            )
        }
        prefs.edit().putString(KEY_SERVERS, arr.toString()).apply()
        servers = list
    }

    /**
     * 导入 MCP 配置 JSON。支持：
     * - `{"mcpServers":{"<name>":{...,"command"/"url"}}}`（Claude Desktop / Operit 风格）
     * - `{"<name>":{...,"command"/"url"}}`（直接 map）
     * 同名条目覆盖旧配置；返回导入的条目数。
     */
    fun importJson(text: String): Result<Int> {
        return runCatching {
            val root = JSONObject(text)
            val map = root.optJSONObject("mcpServers") ?: root
            val names = map.keys()
            var count = 0
            val next = servers.toMutableList()
            while (names.hasNext()) {
                val name = names.next()
                val entry = map.optJSONObject(name) ?: continue
                val url = entry.optString("url")
                val command = entry.optString("command")
                val args = entry.optJSONArray("args")
                val type = if (url.isNotBlank()) "http" else "stdio"
                val summary = if (url.isNotBlank()) {
                    url
                } else {
                    buildString {
                        append(command)
                        if (args != null) {
                            for (i in 0 until args.length()) append(' ').append(args.optString(i))
                        }
                    }
                }
                if (summary.isBlank()) continue
                val id = "mcp-${name.trim().lowercase().replace(Regex("[^a-z0-9_.-]"), "-")}"
                val server = McpServer(
                    id = id,
                    name = name,
                    type = type,
                    summary = summary,
                    enabled = true,
                    raw = entry.toString(),
                )
                val existing = next.indexOfFirst { it.id == id }
                if (existing >= 0) next[existing] = server else next += server
                count++
            }
            if (count == 0) error("没有找到可导入的 MCP 服务器（需要 mcpServers 或 command/url）")
            persist(next)
            count
        }
    }

    fun toggle(id: String, enabled: Boolean) {
        persist(servers.map { if (it.id == id) it.copy(enabled = enabled) else it })
    }

    fun remove(id: String) {
        persist(servers.filterNot { it.id == id })
    }

    companion object {
        private const val KEY_SERVERS = "servers"

        @Volatile
        private var instance: McpConfigStore? = null

        fun get(context: Context): McpConfigStore =
            instance ?: synchronized(this) {
                instance ?: McpConfigStore(context).also { instance = it }
            }
    }
}

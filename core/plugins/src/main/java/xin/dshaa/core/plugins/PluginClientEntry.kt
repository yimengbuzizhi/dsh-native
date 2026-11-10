package xin.dshaa.core.plugins

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import java.io.File

/**
 * DSH 插件 `dsh.client`（浏览器半侧）的入口解析（M70）。
 *
 * 为什么要放宿主里解析、而不是安装时写死：插件目录就在 `files/plugins/<id>/`，
 * 换版本/换插件都不该重编 App。宿主按 DSH 的清单约定找入口：
 * 1. `package.json` 的 `dsh.client` 必须存在，`platform` 缺省或 `web`（其它平台不跑）；
 * 2. `exports["./client"]`（字符串，或 `{default|import: "…"}`）优先；
 * 3. 其次 `dsh.client.entry`（部分插件用这种写法）；
 * 4. 最后按约定找 `lib/client.js` / `client.js` / `dist/client.js`。
 *
 * 只做静态解析，不执行任何插件代码；解析失败给具体原因（插件页要如实显示）。
 */
object PluginClientEntry {

    sealed class Result {
        data class Resolved(val entry: String) : Result()
        data class Unsupported(val reason: String) : Result()
    }

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun resolve(pluginDir: File): Result {
        val manifest = File(pluginDir, "package.json")
        if (!manifest.isFile) return Result.Unsupported("没有 package.json")
        val root = runCatching { json.parseToJsonElement(manifest.readText(Charsets.UTF_8)).jsonObject }
            .getOrNull() ?: return Result.Unsupported("package.json 不是合法 JSON")
        val dsh = root["dsh"] as? JsonObject
            ?: return Result.Unsupported("清单没有 dsh 段")
        val client = dsh["client"] as? JsonObject
            ?: return Result.Unsupported("清单没有 dsh.client（不是 UI 插件）")
        val platform = (client["platform"] as? JsonPrimitive)?.contentOrNull?.trim()?.lowercase()
        if (!platform.isNullOrEmpty() && platform != "web") {
            return Result.Unsupported("dsh.client.platform=$platform，不是 web")
        }

        val declared = clientExport(root) ?: clientEntry(client)
        if (declared != null) {
            val file = File(pluginDir, declared)
            return if (file.isFile) {
                Result.Resolved(declared)
            } else {
                Result.Unsupported("清单声明的入口不存在：$declared")
            }
        }
        val fallback = listOf("lib/client.js", "client.js", "dist/client.js")
            .firstOrNull { File(pluginDir, it).isFile }
            ?: return Result.Unsupported("找不到 client 入口（lib/client.js 等）")
        return Result.Resolved(fallback)
    }

    /** `exports["./client"]`：字符串或 `{default|import|require: "…"}`；去掉 `./` 前缀。 */
    private fun clientExport(root: JsonObject): String? {
        val exports = root["exports"] as? JsonObject ?: return null
        val value = exports["./client"] ?: return null
        val raw = when (value) {
            is JsonPrimitive -> value.contentOrNull
            is JsonObject -> listOf("default", "import", "require", "node", "browser")
                .firstNotNullOfOrNull { key -> (value[key] as? JsonPrimitive)?.contentOrNull }
            else -> null
        }
        return raw?.trim()?.takeIf { it.isNotEmpty() }?.removePrefix("./")
    }

    private fun clientEntry(client: JsonObject): String? =
        (client["entry"] as? JsonPrimitive)?.contentOrNull
            ?.trim()?.takeIf { it.isNotEmpty() }?.removePrefix("./")
}

package xin.dshaa.nativeapp.node

import android.util.Log
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.ZipInputStream
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * Node 插件的最小导入（docs/185 P4 的「先能用」版本，不是完整导入 UI）。
 *
 * 做的事只有两件：把 zip 解到 `dsh/plugins/<名字>/`，再把这个目录写进
 * `node-host-plugins.json`（宿主启动时按它 `ctx.plugin(module, config)`）。
 * **不跑 npm install**：包里得自带 `node_modules`，或者只依赖宿主已有的
 * `@deepseek-ai` 命名空间下的包（dsh-tool-todo、cordis、zod 等）。
 */
class NodePluginInstaller(runtimeDir: File) {

    private val dshDir = File(runtimeDir, "dsh")
    private val pluginsDir = File(dshDir, "plugins")
    private val manifestFile = File(dshDir, "node-host-plugins.json")
    private val json = Json { ignoreUnknownKeys = true }

    /** 清单里登记过的模块（UI 用来显示「登记了但没加载」）。 */
    fun modules(): List<String> = readManifest().mapNotNull { element ->
        (element as? JsonObject)?.get("module")?.let { (it as? JsonPrimitive)?.content }
    }

    /** 解包 + 登记；返回写进清单的 module 说明符（形如 `./plugins/foo/index.js`）。 */
    fun installZip(bytes: ByteArray, fallbackName: String): Result<String> = runCatching {
        val entries = readZip(bytes)
        if (entries.isEmpty()) error("插件包是空的")
        val stripped = stripCommonRoot(entries)
        val dirName = sanitize(stripped.first)
        val target = File(pluginsDir, dirName)
        target.deleteRecursively()
        target.mkdirs()
        for ((name, data) in stripped.second) {
            val out = File(target, name)
            if (!out.canonicalPath.startsWith(target.canonicalPath + File.separator)) {
                error("插件包路径不合法：$name")
            }
            out.parentFile?.mkdirs()
            out.writeBytes(data)
        }
        val entry = entryPoint(target)
        val specifier = "./plugins/$dirName/$entry"
        addModule(specifier)
        Log.i(TAG, "node plugin installed: $specifier (${entries.size} entries)")
        specifier
    }

    private fun readZip(bytes: ByteArray): List<Pair<String, ByteArray>> {
        val list = mutableListOf<Pair<String, ByteArray>>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) continue
                val name = entry.name.replace('\\', '/')
                if (name.startsWith("__MACOSX/") || name.endsWith(".DS_Store")) continue
                if (name.contains("..")) error("插件包路径不合法：$name")
                list += name to zip.readBytes()
            }
        }
        return list
    }

    /**
     * zip 里常有一个统一顶层目录（`my-plugin/index.js`），装进去要把它剥掉，
     * 否则 `./plugins/my-plugin/my-plugin/index.js` 这种路径会让人困惑。
     */
    private fun stripCommonRoot(entries: List<Pair<String, ByteArray>>): Pair<String, List<Pair<String, ByteArray>>> {
        val tops = entries.map { it.first.substringBefore('/') }.distinct()
        val nested = entries.all { it.first.contains('/') }
        if (tops.size != 1 || !nested) return "" to entries
        val prefix = tops.first() + "/"
        return tops.first() to entries.map { (name, data) -> name.removePrefix(prefix) to data }
    }

    /** 入口：`package.json` 的 main 优先，其次 index.*，最后随便挑一个 js。 */
    private fun entryPoint(dir: File): String {
        val pkg = File(dir, "package.json")
        if (pkg.isFile) {
            runCatching {
                val obj = json.parseToJsonElement(pkg.readText()).jsonObject
                val main = (obj["main"] as? JsonPrimitive)?.content
                if (!main.isNullOrBlank() && File(dir, main).isFile) return main
            }
        }
        for (candidate in listOf("index.js", "index.mjs", "index.cjs", "main.js", "plugin.js")) {
            if (File(dir, candidate).isFile) return candidate
        }
        dir.walkTopDown()
            .firstOrNull { it.isFile && it.extension in setOf("js", "mjs", "cjs") }
            ?.let { return it.relativeTo(dir).invariantSeparatorsPath }
        error("插件包里找不到入口（index.js 或 package.json 的 main）")
    }

    private fun addModule(specifier: String) {
        val list = readManifest().toMutableList()
        val exists = list.any { element ->
            (element as? JsonObject)?.get("module")?.let { (it as? JsonPrimitive)?.content } == specifier
        }
        if (exists) return
        list += buildJsonObject {
            put("module", specifier)
            put("config", buildJsonObject { })
        }
        manifestFile.parentFile?.mkdirs()
        // 清单是 `{ "plugins": [ … ] }`（host.mjs 按这个形状读）；写成裸数组会让宿主一个插件都加载不到。
        manifestFile.writeText(buildJsonObject { put("plugins", JsonArray(list)) }.toString())
    }

    private fun readManifest(): List<JsonElement> = runCatching {
        if (!manifestFile.isFile) {
            emptyList()
        } else {
            when (val root = json.parseToJsonElement(manifestFile.readText())) {
                is JsonArray -> root.toList()
                is JsonObject -> (root["plugins"] as? JsonArray)?.toList().orEmpty()
                else -> emptyList()
            }
        }
    }.getOrDefault(emptyList())

    private fun sanitize(name: String): String = name
        .replace(Regex("[^A-Za-z0-9._-]"), "_")
        .trim('.', '_')
        .take(48)
        .ifBlank { "plugin" }

    private companion object {
        const val TAG = "DshNodeHost"
    }
}

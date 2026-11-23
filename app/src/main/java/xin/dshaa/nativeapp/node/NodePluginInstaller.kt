package xin.dshaa.nativeapp.node

import android.util.Log
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipInputStream
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Node 插件导入（docs/185 P4 / docs/187 §9.3）。
 *
 * 做的事只有两件：把 zip 解到 `files/node/data/plugins/<名字>/`，再把这个目录写进
 * `data/node-host-plugins.json`（宿主启动时按它 `ctx.plugin(module, config)`）。
 * **不跑 npm install**：包里得自带 `node_modules`，或者只依赖宿主已有的
 * `@deepseek-ai` 命名空间下的包（dsh-tool-todo、cordis、zod 等）。
 *
 * 导入健壮性（docs/187 §九 第 7 项 / docs/188 P4）：
 * - zip 里没有统一顶层目录时，用 **zip 文件名**做目录名（不再全部挤进 `plugin/` 互相覆盖）；
 * - 限制整包/单条目/总解压大小与条目数；**解压边写盘边计数，不把整包解压结果留在内存里**；
 * - 体积之外还有压缩比上限（防「几 KB 解出几百 MB」的 zip bomb）；
 * - 条目名禁止绝对路径与 `..`，`package.json#main` 做 canonical 校验（不许逃出插件目录）。
 *
 * 上限（用户可见，`limitsText()` 直接进导入提示）：zip 64MB / 单文件 32MB /
 * 解压后 256MB / 条目 20000 / 压缩比 200×。这样 `dsh-client-ui-pet@0.1.10` 的
 * 16.5MB `lib/client.js` 能装进来，也不会被构造的 bomb 撑爆。
 */
class NodePluginInstaller(
    dataDir: File,
    private val limits: Limits = Limits(),
) {

    private val pluginsDir = File(dataDir, "plugins")
    private val manifestFile = File(dataDir, "node-host-plugins.json")
    private val json = Json { ignoreUnknownKeys = true }

    /** 当前上限（导入提示/自测/错误文案共用）。 */
    data class Limits(
        val maxZipBytes: Long = 64L * 1024 * 1024,
        val maxEntryBytes: Long = 32L * 1024 * 1024,
        val maxTotalBytes: Long = 256L * 1024 * 1024,
        val maxEntries: Int = 20_000,
        val maxRatio: Long = 200L,
    )

    /** 清单里的一条 Node 插件记录（P4：插件页 Node 分区 + 同 id 双装判断用）。 */
    data class NodeModuleRecord(
        val id: String,
        val name: String,
        val version: String,
        val module: String,
        /** 包里有 web client（`exports["./client"]` / `dsh.client`）。 */
        val hasClient: Boolean = false,
        /** 目录里能读到 package.json 的可执行入口（只用于展示，不执行）。 */
        val entry: String = "",
    )

    /** 解包 + 登记的结果：给导入提示/迁移判断用的元数据。 */
    data class Installed(
        val id: String,
        val name: String,
        val version: String,
        val module: String,
        val entry: String,
        val hasClient: Boolean,
    ) {
        /** 兼容旧调用：清单里写的模块说明符（形如 `./plugins/foo/index.js`）。 */
        val specifier: String get() = module
    }

    /** 清单里登记过的模块（UI 用来显示「登记了但没加载」）。 */
    fun modules(): List<String> = entries().map { it.module }

    /** docs/190-B：模块说明符对应的插件目录（越界/非法返回 null；doctor 检查目录与入口用）。 */
    fun dirFor(module: String): File? = moduleDir(module)

    /** 清单条目（含从目录 package.json 补出来的 id/name/version；旧清单没有这些字段）。 */
    fun entries(): List<NodeModuleRecord> = readManifest().mapNotNull { element ->
        val obj = element as? JsonObject ?: return@mapNotNull null
        val module = (obj["module"] as? JsonPrimitive)?.content ?: return@mapNotNull null
        val fallback = metaOf(moduleDir(module))
        val id = (obj["id"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
            ?: fallback?.id
            ?: moduleDir(module)?.name
            ?: module
        NodeModuleRecord(
            id = id,
            name = (obj["name"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
                ?: fallback?.name
                ?: id,
            version = (obj["version"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
                ?: fallback?.version
                ?: "0.0.0",
            module = module,
            hasClient = (obj["hasClient"] as? JsonPrimitive)?.content?.toBooleanStrictOrNull()
                ?: fallback?.hasClient
                ?: false,
            entry = (obj["entry"] as? JsonPrimitive)?.content ?: fallback?.entry.orEmpty(),
        )
    }

    /**
     * 解包 + 登记；返回写进清单的模块元数据。
     *
     * [resetConfig] = true 时丢掉同 id 旧条目的 config（迁移/覆盖导入语义）；默认保留
     * （升级安装不因为重装丢用户配置）。
     */
    fun installZip(bytes: ByteArray, fallbackName: String, resetConfig: Boolean = false): Result<Installed> = runCatching {
        if (bytes.isEmpty()) error("插件包是空的")
        if (bytes.size > limits.maxZipBytes) {
            error("插件包太大：${mb(bytes.size.toLong())}（上限 ${mb(limits.maxZipBytes)}）")
        }
        val names = inspectZip(bytes)
        if (names.isEmpty()) error("插件包是空的（没有文件条目）")

        val root = commonRoot(names)
        val dirName = root?.let { sanitize(it) } ?: sanitize(fallbackName)
        pluginsDir.mkdirs()
        val target = File(pluginsDir, dirName)
        val targetPath = target.canonicalPath
        if (!targetPath.startsWith(pluginsDir.canonicalPath + File.separator)) {
            error("插件目录名不合法：$dirName")
        }
        target.deleteRecursively()
        target.mkdirs()
        try {
            extractZip(bytes, target, root)
            val entry = entryPoint(target)
            if (entry.split('/').any { it == ".." } || entry.startsWith("/")) {
                error("插件入口路径不合法：$entry")
            }
            val meta = metaOf(target) ?: Meta(id = dirName, name = dirName, version = "0.0.0")
            val installed = Installed(
                id = meta.id,
                name = meta.name,
                version = meta.version,
                module = "./plugins/$dirName/$entry",
                entry = entry,
                hasClient = meta.hasClient,
            )
            addModule(installed, resetConfig = resetConfig)
            Log.i(
                TAG,
                "node plugin installed: ${installed.module} (${names.size} entries, root=${root ?: "none"}, " +
                    "id=${installed.id}, hasClient=${installed.hasClient})",
            )
            installed
        } catch (failure: Throwable) {
            // 解到一半失败：清掉半成品目录，但不动旧清单（旧条目还在，宿主会报加载失败）。
            runCatching { target.deleteRecursively() }
            throw failure
        }
    }

    /** 卸载：按 id / 模块说明符 / 目录名匹配；删目录 + 清单条目。 */
    fun uninstall(idOrModule: String): Result<String> = runCatching {
        val entries = readManifest().toMutableList()
        val key = idOrModule.trim()
        val index = entries.indexOfFirst { element ->
            val obj = element as? JsonObject ?: return@indexOfFirst false
            val module = (obj["module"] as? JsonPrimitive)?.content.orEmpty()
            val dir = moduleDir(module)
            val declaredId = (obj["id"] as? JsonPrimitive)?.content
            val meta = dir?.let { metaOf(it) }
            declaredId == key || module == key || dir?.name == key || meta?.id == key
        }
        if (index < 0) error("清单里没有这个 Node 插件：$key")
        val obj = entries[index] as JsonObject
        val module = (obj["module"] as? JsonPrimitive)?.content.orEmpty()
        val dir = moduleDir(module)
        val display = (obj["name"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
            ?: dir?.name ?: key
        if (dir != null && dir.exists()) {
            val canonical = dir.canonicalPath
            if (!canonical.startsWith(pluginsDir.canonicalPath + File.separator)) {
                error("插件目录不合法：$module")
            }
            if (!dir.deleteRecursively() && dir.exists()) error("删不掉插件目录：${dir.absolutePath}")
        }
        entries.removeAt(index)
        writeManifest(entries)
        display
    }

    /**
     * 第一遍：只收条目名（不保存解压内容），用于算公共根与安全上限。
     * 目录条目跳过；`__MACOSX`/`.DS_Store` 视作垃圾但 **不跳过计数**（防止用垃圾条目绕过条目上限）。
     */
    private fun inspectZip(bytes: ByteArray): List<String> {
        val names = ArrayList<String>()
        var count = 0
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) continue
                val name = normalizeEntryName(entry.name)
                count++
                if (count > limits.maxEntries) error("插件包条目太多（上限 ${limits.maxEntries}）")
                if (name.startsWith("__MACOSX/") || name.endsWith(".DS_Store")) continue
                if (names.any { it == name }) error("插件包里有重复路径：$name")
                names += name
            }
        }
        return names
    }

    /**
     * 第二遍：边读边写盘。每个条目、累计解压量、压缩比都在写之前/之后检查；
     * 内存里最多只有一个 32KB 缓冲区（不再把整包解压结果留在堆里）。
     */
    private fun extractZip(bytes: ByteArray, target: File, root: String?) {
        val targetPath = target.canonicalPath
        var total = 0L
        var count = 0
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) continue
                count++
                if (count > limits.maxEntries) error("插件包条目太多（上限 ${limits.maxEntries}）")
                val raw = normalizeEntryName(entry.name)
                if (raw.startsWith("__MACOSX/") || raw.endsWith(".DS_Store")) continue
                val name = if (root != null) raw.removePrefix(root + "/") else raw
                if (name.isBlank()) continue
                val out = File(target, name)
                if (!out.canonicalPath.startsWith(targetPath + File.separator)) {
                    error("插件包路径不合法：$name")
                }
                out.parentFile?.mkdirs()
                var written = 0L
                FileOutputStream(out).buffered().use { fos ->
                    val buffer = ByteArray(32 * 1024)
                    while (true) {
                        val read = zip.read(buffer)
                        if (read <= 0) break
                        written += read
                        total += read
                        if (written > limits.maxEntryBytes) {
                            error("插件包里单个文件太大：$name（上限 ${mb(limits.maxEntryBytes)}）")
                        }
                        if (total > limits.maxTotalBytes) {
                            error("插件包解压后太大（上限 ${mb(limits.maxTotalBytes)}）")
                        }
                        if (looksLikeBomb(total, bytes.size.toLong())) {
                            error("压缩比异常（已解压 ${mb(total)} / 压缩包 ${mb(bytes.size.toLong())}）：疑似 zip bomb")
                        }
                        fos.write(buffer, 0, read)
                    }
                }
            }
        }
    }

    private fun looksLikeBomb(extracted: Long, archiveBytes: Long): Boolean =
        extracted > maxOf(archiveBytes, RATIO_FLOOR_BYTES) * limits.maxRatio

    private fun normalizeEntryName(raw: String): String {
        val name = raw.replace('\\', '/')
        if (name.startsWith("/")) error("插件包路径不合法（绝对路径）：$raw")
        if (Regex("^[A-Za-z]:").containsMatchIn(name)) error("插件包路径不合法（盘符）：$raw")
        val segments = name.split('/')
        if (segments.any { it == ".." }) error("插件包路径不合法（含 ..）：$raw")
        if (segments.any { it.isBlank() }) error("插件包路径不合法（空目录名）：$raw")
        return name
    }

    /**
     * zip 里常有一个统一顶层目录（`my-plugin/index.js`），装进去要把它剥掉，
     * 否则 `./plugins/my-plugin/my-plugin/index.js` 这种路径会让人困惑。
     * 没有统一顶层目录时返回 null（调用方用 zip 文件名做目录名）。
     */
    private fun commonRoot(names: List<String>): String? {
        val tops = names.map { it.substringBefore('/') }.distinct()
        if (tops.size != 1) return null
        val top = tops.first()
        if (names.any { !it.startsWith("$top/") }) return null
        if (names.any { it == top }) return null
        return top.ifBlank { null }
    }

    /** 入口：`package.json` 的 main 优先，其次 index.*，最后随便挑一个 js。 */
    private fun entryPoint(dir: File): String {
        // canonical 只用来做「不能逃出插件目录」的校验；返回的相对路径必须相对**同一个**
        // canonical 根，否则 Android 上 /data/user/0 → /data/data 的软链会让 relativeTo
        // 造出一串 `../../..`（真机踩过：manifest 里写成 ../../../../data/...）。
        val canonicalDirFile = dir.canonicalFile
        val canonicalDir = canonicalDirFile.path
        val pkg = File(dir, "package.json")
        if (pkg.isFile) {
            runCatching {
                val obj = json.parseToJsonElement(pkg.readText()).jsonObjectOrNull() ?: return@runCatching
                val main = (obj["main"] as? JsonPrimitive)?.content
                if (!main.isNullOrBlank()) {
                    val mainFile = File(dir, main).canonicalFile
                    if (!mainFile.path.startsWith(canonicalDir + File.separator)) {
                        error("package.json#main 越界：$main")
                    }
                    if (mainFile.isFile) {
                        return mainFile.relativeTo(canonicalDirFile).invariantSeparatorsPath
                    }
                }
            }.onFailure { if (it is IllegalStateException) throw it }
        }
        for (candidate in listOf("index.js", "index.mjs", "index.cjs", "main.js", "plugin.js")) {
            if (File(dir, candidate).isFile) return candidate
        }
        dir.walkTopDown()
            .firstOrNull { it.isFile && it.extension in setOf("js", "mjs", "cjs") }
            ?.let { return it.relativeTo(dir).invariantSeparatorsPath }
        error("插件包里找不到入口（index.js 或 package.json 的 main）")
    }

    private data class Meta(
        val id: String,
        val name: String,
        val version: String,
        val hasClient: Boolean = false,
        val entry: String = "",
    )

    /** 从插件目录的 package.json 读展示元数据；坏了返回 null（不阻断导入）。 */
    private fun metaOf(dir: File?): Meta? {
        if (dir == null || !dir.isDirectory) return null
        val pkg = File(dir, "package.json")
        if (!pkg.isFile) return null
        val obj = runCatching {
            json.parseToJsonElement(pkg.readText()).jsonObjectOrNull()
        }.getOrNull() ?: return null
        val name = (obj["name"] as? JsonPrimitive)?.content?.trim().orEmpty()
            .ifBlank { (obj["id"] as? JsonPrimitive)?.content?.trim().orEmpty() }
        val id = (obj["id"] as? JsonPrimitive)?.content?.trim().orEmpty().ifBlank { name }
        val exports = obj["exports"] as? JsonObject
        val hasClient = exports?.containsKey("./client") == true ||
            (obj["dsh"] as? JsonObject)?.get("client") != null
        return Meta(
            id = id.ifBlank { dir.name },
            name = name.ifBlank { id.ifBlank { dir.name } },
            version = (obj["version"] as? JsonPrimitive)?.content?.trim().orEmpty().ifBlank { "0.0.0" },
            hasClient = hasClient,
            entry = (obj["main"] as? JsonPrimitive)?.content?.trim().orEmpty(),
        )
    }

    private fun addModule(installed: Installed, resetConfig: Boolean) {
        val list = readManifest().toMutableList()
        var config: JsonElement = buildJsonObject { }
        // 同 id 换目录/覆盖安装：移除旧条目（配置按 resetConfig 决定留不留）。
        val sameId = list.filter { element ->
            val obj = element as? JsonObject ?: return@filter false
            val module = (obj["module"] as? JsonPrimitive)?.content.orEmpty()
            val declared = (obj["id"] as? JsonPrimitive)?.content
            declared == installed.id || module == installed.module ||
                moduleDir(module)?.let { metaOf(it)?.id } == installed.id
        }
        sameId.forEach { old ->
            val obj = old as JsonObject
            val module = (obj["module"] as? JsonPrimitive)?.content.orEmpty()
            val sameModule = module == installed.module
            if (!resetConfig && sameModule) {
                (obj["config"] as? JsonObject)?.let { config = it }
            }
            list.remove(old)
        }
        list += buildJsonObject {
            put("module", installed.module)
            put("config", config)
            put("id", installed.id)
            put("name", installed.name)
            put("version", installed.version)
            put("entry", installed.entry)
            if (installed.hasClient) put("hasClient", true)
        }
        writeManifest(list)
    }

    private fun manifestPathForModule(module: String): File? {
        val rel = module.removePrefix("./plugins/")
        val dirName = rel.substringBefore('/')
        if (dirName.isBlank() || dirName == "." || dirName.contains("..")) return null
        val dir = File(pluginsDir, dirName)
        val canonical = runCatching { dir.canonicalPath }.getOrNull() ?: return null
        if (!canonical.startsWith(pluginsDir.canonicalPath + File.separator)) return null
        return dir
    }

    private fun moduleDir(module: String): File? = manifestPathForModule(module)

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

    private fun writeManifest(list: List<JsonElement>) {
        manifestFile.parentFile?.mkdirs()
        // 清单是 `{ "plugins": [ … ] }`（host.mjs 按这个形状读）；写成裸数组会让宿主一个插件都加载不到。
        manifestFile.writeText(buildJsonObject { put("plugins", JsonArray(list)) }.toString())
    }

    /** 目录名：取最后一段、去 `.zip` 后缀、只留安全字符；空 = `plugin`。 */
    private fun sanitize(name: String): String {
        val base = name.trim()
            .substringAfterLast('/')
            .substringAfterLast('\\')
            .removeSuffix(".zip")
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .trim('.', '_')
            .take(48)
        return base.ifBlank { "plugin" }
    }

    private fun kotlinx.serialization.json.JsonElement.jsonObjectOrNull(): JsonObject? = this as? JsonObject

    private fun mb(bytes: Long): String = if (bytes >= 1024 * 1024) {
        "${bytes / 1024 / 1024}MB"
    } else {
        "${bytes / 1024}KB"
    }

    companion object {
        const val TAG = "DshNodeHost"
        const val RATIO_FLOOR_BYTES = 128L * 1024

        /** 用户可见的上限事实（导入页原样展示，不写成解释性文案）。 */
        fun limitsText(): String {
            val limits = Limits()
            return "zip ≤${limits.maxZipBytes / 1024 / 1024}MB · 单文件 ≤${limits.maxEntryBytes / 1024 / 1024}MB" +
                " · 解压后 ≤${limits.maxTotalBytes / 1024 / 1024}MB"
        }
    }
}

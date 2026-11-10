package xin.dshaa.core.plugins

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * 插件包读取与清单解析（纯 JVM，可单测）。
 *
 * 设计取舍（2026-11-08，第二十六棒）：
 * - **认两种清单**：DSH 的 `package.json`（桌面端 npm 包）优先，Operit/ToolPkg 的
 *   `manifest.json` 作为兜底登记；两者入口都按「JS 模块 + apply(ctx)」试运行。
 * - **zip 读取带硬上限**：条目数 2000、单文件 4MB、解压总量 32MB，路径不许绝对/`..`，
 *   防 zip-slip 与「用户误选了一个大压缩包把手机撑爆」。
 * - **单顶层目录自动剥掉**：手机上常见的压缩包是 `my-plugin/package.json`，
 *   剥掉公共根之后，清单里的 `main` 路径和包内路径才对得上。
 */
object PluginPackage {

    const val MAX_ENTRIES = 2_000
    const val MAX_ENTRY_BYTES = 4L * 1024 * 1024
    const val MAX_TOTAL_BYTES = 32L * 1024 * 1024

    private val ID_PATTERN = Regex("""^[A-Za-z0-9@][A-Za-z0-9._@/-]{0,119}$""")
    private val MANIFEST_NAMES = setOf("package.json", "manifest.json")

    /** 读 zip（含安全校验）。返回的路径已归一化，但还没剥公共顶层目录。 */
    fun read(file: File): ArchiveRead {
        if (!file.isFile) return ArchiveRead.Failed("文件不存在")
        if (file.length() > MAX_TOTAL_BYTES * 2) return ArchiveRead.Failed("压缩包太大（>64MB），不是插件包？")
        val entries = LinkedHashMap<String, ByteArray>()
        var total = 0L
        try {
            ZipInputStream(file.inputStream().buffered()).use { zip ->
                var count = 0
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.isDirectory) continue
                    val raw = entry.name
                    val path = normalizePath(raw)
                        ?: return ArchiveRead.Failed("压缩包里有不安全的路径：$raw")
                    if (isJunk(path)) continue
                    count++
                    if (count > MAX_ENTRIES) {
                        return ArchiveRead.Failed("压缩包文件数超过 $MAX_ENTRIES，不是插件包？")
                    }
                    val bytes = readLimited(zip, MAX_ENTRY_BYTES)
                        ?: return ArchiveRead.Failed("单个文件超过 4MB：$path")
                    total += bytes.size
                    if (total > MAX_TOTAL_BYTES) return ArchiveRead.Failed("解压后总大小超过 32MB")
                    if (entries.put(path, bytes) != null) return ArchiveRead.Failed("压缩包里有重复路径：$path")
                }
            }
        } catch (t: Throwable) {
            return ArchiveRead.Failed("读不了这个压缩包：${t.message ?: t.javaClass.simpleName}")
        }
        if (entries.isEmpty()) return ArchiveRead.Failed("压缩包是空的")
        return ArchiveRead.Ok(entries.map { PluginFile(it.key, it.value) })
    }

    /**
     * 从文件列表里找清单、剥公共根、校验入口。
     *
     * 清单选择规则：最短路径优先（`package.json` 优先于 `manifest.json`）；
     * 两个不同顶层目录各有一份清单时按「分不清装哪个」报错，不让用户猜。
     */
    fun parse(files: List<PluginFile>): PackageParse {
        if (files.isEmpty()) return PackageParse.Failed("空包")
        val candidates = files.filter { it.path.substringAfterLast('/') in MANIFEST_NAMES }
        if (candidates.isEmpty()) {
            return PackageParse.Failed("包里没有 package.json / manifest.json，不知道这是什么插件")
        }
        val depth = { f: PluginFile -> f.path.count { it == '/' } }
        val minDepth = candidates.minOf(depth)
        val shallowest = candidates.filter { depth(it) == minDepth }
        if (shallowest.size > 1) {
            val roots = shallowest.map { it.path.substringBeforeLast('/', "") }.distinct()
            if (roots.size > 1) {
                return PackageParse.Failed("包里有多个顶层插件目录（${roots.joinToString("、")}），请一次只装一个")
            }
        }
        // 同一目录下两个清单都有的，优先 package.json。
        val manifestFile = shallowest.firstOrNull { it.path.endsWith("package.json") } ?: shallowest.first()
        val root = manifestFile.path.substringBeforeLast('/', "")
        val prefix = if (root.isEmpty()) "" else "$root/"
        val stripped = files.filter { it.path.startsWith(prefix) }.map { PluginFile(it.path.removePrefix(prefix), it.bytes) }
        val manifestBytes = manifestFile.bytes

        val json = runCatching {
            Json.parseToJsonElement(manifestBytes.toString(Charsets.UTF_8)).jsonObject
        }.getOrNull() ?: return PackageParse.Failed("${manifestFile.path} 不是合法 JSON")

        val format = if (manifestFile.path.endsWith("package.json")) PluginFormat.DSH else PluginFormat.OPERIT
        val manifest = when (val read = manifestFrom(json, format)) {
            is ManifestRead.Ok -> read.manifest
            is ManifestRead.Failed -> return PackageParse.Failed(read.error)
            ManifestRead.Missing -> return PackageParse.Failed("清单解析失败")
        }
        if (stripped.none { it.path == manifest.main }) {
            return PackageParse.Failed("清单里的入口 main=${manifest.main} 在包里找不到")
        }
        return PackageParse.Ok(manifest, root, stripped)
    }

    /**
     * 从已安装目录读清单（M37 热重载）：`package.json` 优先，其次 `manifest.json`（与安装时一致）。
     *
     * 和 [parse] 的区别：只读目录顶层的清单文件，不枚举/校验入口文件是否存在——入口检查
     * 由调用方（PluginStore.reload）做，这样「清单改了 main 但文件还没放」能给出更直白的错误。
     */
    fun readManifest(dir: File): ManifestRead {
        if (!dir.isDirectory) return ManifestRead.Failed("插件目录不在了：${dir.absolutePath}")
        val file = listOf("package.json", "manifest.json").map { File(dir, it) }.firstOrNull { it.isFile }
            ?: return ManifestRead.Missing
        val text = runCatching { file.readText(Charsets.UTF_8) }.getOrElse {
            return ManifestRead.Failed("读不了 ${file.name}：${it.message ?: it.javaClass.simpleName}")
        }
        val format = if (file.name == "package.json") PluginFormat.DSH else PluginFormat.OPERIT
        return when (val read = parseManifestText(text, format)) {
            is ManifestRead.Ok -> read
            is ManifestRead.Failed -> ManifestRead.Failed("${file.name}：${read.error}")
            ManifestRead.Missing -> ManifestRead.Missing
        }
    }

    /** 解析一段清单 JSON 文本（安装/热重载/单测共用）。 */
    fun parseManifestText(text: String, format: PluginFormat): ManifestRead {
        val json = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull()
            ?: return ManifestRead.Failed("不是合法 JSON 对象")
        return manifestFrom(json, format)
    }

    private fun manifestFrom(json: JsonObject, format: PluginFormat): ManifestRead {
        val name = json.string("name")?.trim().orEmpty().ifEmpty { json.string("id")?.trim().orEmpty() }
        if (name.isEmpty()) return ManifestRead.Failed("清单里没有 name（插件名）")
        if (name.length > 120) return ManifestRead.Failed("插件名太长（>120 字符）")
        val id = (json.string("id")?.trim().orEmpty().ifEmpty { name })
        if (!ID_PATTERN.matches(id)) {
            return ManifestRead.Failed("插件 id 不合法：$id（只允许字母数字 @ . _ / -）")
        }
        val defaultMain = if (format == PluginFormat.DSH) "lib/index.js" else "index.js"
        val mainRaw = json.string("main")?.trim().orEmpty().ifEmpty { defaultMain }
        val main = normalizePath(mainRaw) ?: return ManifestRead.Failed("main 路径不合法：$mainRaw")
        // 权限：顶层 `permissions` 为准；DSH 的 package.json 也允许写在 `dsh.permissions`。
        val permissionRaw = json.stringList("permissions").ifEmpty {
            (json["dsh"] as? kotlinx.serialization.json.JsonObject)?.stringList("permissions").orEmpty()
        }
        val permissions = PluginPermissions.parseAll(permissionRaw).map { it.raw }
        // ToolPkg/Operit 的配置：`config: { schema, default }`（docs/04 §6.1）。
        // 也容忍把 JSON Schema 直接写在 `config` 上（没有 default）。
        val configObject = json["config"] as? JsonObject
        val configSchema = (configObject?.get("schema") as? JsonObject)
            ?: configObject?.takeIf { it["type"] != null }
        val configDefaults = configObject?.get("default") as? JsonObject
        // DSH 扩展字段：客户端半侧、组合包补丁、DSH 版本要求。手机只识别/展示，不执行 Web UI。
        val dshObject = json["dsh"] as? JsonObject
        val clientUi = dshObject?.get("client") != null
        val bundlePatch = run {
            val bundle = dshObject?.get("bundle") as? JsonObject ?: return@run null
            when (val patch = bundle["patch"]) {
                is kotlinx.serialization.json.JsonPrimitive -> patch.contentOrNull
                is kotlinx.serialization.json.JsonArray ->
                    patch.firstOrNull()?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }
                else -> null
            }
        }
        val enginesDsh = (json["engines"] as? JsonObject)?.string("dsh")
        return ManifestRead.Ok(
            PluginManifest(
                id = id,
                name = name,
                version = json.string("version")?.trim().orEmpty().ifEmpty { "0.0.0" },
                description = json.string("description")?.trim().orEmpty(),
                main = main,
                format = format,
                permissions = permissions,
                configSchema = configSchema,
                configDefaults = configDefaults,
                clientUi = clientUi,
                bundlePatch = bundlePatch,
                enginesDsh = enginesDsh,
            ),
        )
    }

    /**
     * 路径归一化：`\` → `/`、去掉开头的 `./`，拒绝绝对路径、盘符、`..`、空段。
     * 不合法返回 null（zip-slip 第一道闸）。
     */
    fun normalizePath(raw: String): String? {
        var path = raw.trim().replace('\\', '/')
        if (path.isEmpty()) return null
        if (path.startsWith("/")) return null
        if (Regex("""^[A-Za-z]:""").containsMatchIn(path)) return null
        while (path.startsWith("./")) path = path.removePrefix("./")
        if (path.isEmpty()) return null
        val parts = path.split('/')
        if (parts.any { it.isEmpty() || it == "." || it == ".." }) return null
        return parts.joinToString("/")
    }

    private fun isJunk(path: String): Boolean =
        path.startsWith("__MACOSX/") ||
            path.endsWith("/.DS_Store") ||
            path == ".DS_Store" ||
            path.substringAfterLast('/').startsWith("._")

    private fun readLimited(input: InputStream, limit: Long): ByteArray? {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > limit) return null
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    private fun kotlinx.serialization.json.JsonObject.string(key: String): String? =
        this[key]?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }

    private fun kotlinx.serialization.json.JsonObject.stringList(key: String): List<String> =
        this[key]?.let { element ->
            runCatching { element.jsonArray.mapNotNull { it.jsonPrimitive.contentOrNull } }.getOrNull()
        }.orEmpty()
}

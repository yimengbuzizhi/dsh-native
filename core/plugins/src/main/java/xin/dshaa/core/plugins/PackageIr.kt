package xin.dshaa.core.plugins

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File

/**
 * M87-G：统一包 IR（docs/181 §3.6）。
 *
 * 内部只认一种模型，外部多入口：文件夹 / `.zip` / `.dshpkg` / 单 `.md` / 单 `.json` /
 * DSH 的 `package.json` 都先解析成这里的 [PackageIr]，再交给 `PluginStore` 落盘。
 *
 * 设计取舍：
 * - **不猜**：未知 kind、未知清单字段、包内找不到的入口/素材都返回可读中文错误，
 *   安装入口据此拒绝，不产生半装状态（docs/181 §3.6.7-5）。
 * - **旧格式不丢语义**：旧鲸歌插件与 DSH 包仍走原来的 `PluginPackage` 安装/试运行链路，
 *   这里只额外给出 IR 元数据（`format` + `contributions`），加载、权限、开关、角色行为不变。
 * - **IR 可往返**：`PackageIrCodec.encode` → `decode` 字段级相等，工坊导出/再导入用它做对拍。
 */

/** 包格式（IR 里的 `format`，也是 `PluginRecord.irFormat` 的取值）。 */
enum class PackageFormat(val id: String, val label: String) {
    /** 鲸歌轻格式：`dsh.package.json` + 声明式贡献物（默认导出格式）。 */
    WHALE_LITE("whale-lite", "鲸歌轻格式"),

    /** DSH 桌面端包：`package.json` + `lib/index.js`（+ `dsh.client` / `dsh.bundle.patch`）。 */
    DSH("dsh", "DSH 兼容包"),

    /** 0.3.4 及以前的鲸歌插件：目录 + `index.js`（`package.json`/`manifest.json`）。 */
    LEGACY_JS("legacy-js", "旧版脚本插件"),

    /** Operit / ToolPkg 风格清单（`manifest.json`，兼容登记）。 */
    OPERIT("operit", "Operit/ToolPkg");

    companion object {
        fun fromId(raw: String?): PackageFormat? =
            values().firstOrNull { it.id.equals(raw?.trim(), ignoreCase = true) }
    }
}

/** 第一版贡献物类型（docs/181 §3.6.2）。 */
enum class ContributionKind(val id: String, val label: String) {
    SKILL("skill", "技能"),
    PROMPT("prompt", "提示词"),
    THEME("theme", "主题"),
    PAGE("page", "页面"),
    MACRO("macro", "命令宏"),
    MCP("mcp", "MCP 服务器"),
    TOOL_HTTP("tool.http", "HTTP 工具"),
    PRESET("preset", "助手预设"),
    SCRIPT("script", "脚本"),
    CLIENT("client", "浏览器半侧"),
    ASSET("asset", "素材");

    val needsEntry: Boolean
        get() = this == SCRIPT || this == CLIENT

    companion object {
        fun fromId(raw: String?): ContributionKind? =
            values().firstOrNull { it.id.equals(raw?.trim(), ignoreCase = true) }

        /** 给错误文案用的「本版支持哪些」。 */
        val ids: List<String> get() = values().map { it.id }
    }
}

/**
 * 一个贡献物。
 *
 * [config] 是 kind 自己的声明（主题 tokens / 页面 Surface 树 / 宏 steps / MCP 配置 …）；
 * [assets] 是包内相对路径；[entry] 只给 `script` / `client` 用。
 */
data class PluginContribution(
    val kind: ContributionKind,
    val id: String,
    val config: JsonObject = JsonObject(emptyMap()),
    val assets: List<String> = emptyList(),
    val entry: String? = null,
)

/** 统一包 IR。 */
data class PackageIr(
    val id: String,
    val name: String,
    val version: String = "0.0.0",
    val description: String = "",
    val format: PackageFormat = PackageFormat.WHALE_LITE,
    val permissions: List<String> = emptyList(),
    val contributions: List<PluginContribution> = emptyList(),
    /** 旧脚本包的主入口（声明式包为 null）。 */
    val main: String? = null,
    /** DSH `dsh.bundle.patch` 相对路径。 */
    val bundlePatch: String? = null,
    /** DSH `engines.dsh`。 */
    val enginesDsh: String? = null,
) {
    /** 这个包需要常驻/一次性 JS 运行时吗（脚本或浏览器半侧）。 */
    fun hasRuntimeScript(): Boolean =
        contributions.any { it.kind == ContributionKind.SCRIPT || it.kind == ContributionKind.CLIENT }

    fun contribution(kind: ContributionKind): List<PluginContribution> =
        contributions.filter { it.kind == kind }
}

/** 编码/解码 IR 的结果。 */
sealed interface IrRead {
    data class Ok(val ir: PackageIr) : IrRead

    data class Failed(val error: String) : IrRead
}

/**
 * IR 的规范 JSON 编解码（`dsh.package.json`，工坊导出与再导入共用）。
 *
 * 严格模式：未知顶层字段/未知贡献物字段直接报错（§3.6.1「不装、不猜」），
 * 错误文案带具体字段名，方便作者改包。
 */
object PackageIrCodec {

    private val TOP_KEYS = setOf(
        "id", "name", "version", "description", "format", "permissions",
        "contributions", "main", "bundlePatch", "enginesDsh",
    )
    private val CONTRIBUTION_KEYS = setOf("kind", "id", "config", "assets", "entry")

    fun encode(ir: PackageIr): String = buildJsonObject {
        put("id", ir.id)
        put("name", ir.name)
        put("version", ir.version)
        if (ir.description.isNotBlank()) put("description", ir.description)
        put("format", ir.format.id)
        if (ir.permissions.isNotEmpty()) {
            put("permissions", buildJsonArray { ir.permissions.forEach { add(JsonPrimitive(it)) } })
        }
        put(
            "contributions",
            buildJsonArray {
                ir.contributions.forEach { contribution ->
                    add(
                        buildJsonObject {
                            put("kind", contribution.kind.id)
                            put("id", contribution.id)
                            if (contribution.config.isNotEmpty()) put("config", contribution.config)
                            if (contribution.assets.isNotEmpty()) {
                                put("assets", buildJsonArray {
                                    contribution.assets.forEach { add(JsonPrimitive(it)) }
                                })
                            }
                            contribution.entry?.let { put("entry", it) }
                        },
                    )
                }
            },
        )
        ir.main?.let { put("main", it) }
        ir.bundlePatch?.let { put("bundlePatch", it) }
        ir.enginesDsh?.let { put("enginesDsh", it) }
    }.toString()

    fun decode(text: String, source: String = "dsh.package.json"): IrRead {
        val obj = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull()
            ?: return IrRead.Failed("$source 不是合法的 JSON 对象")
        val unknown = obj.keys - TOP_KEYS
        if (unknown.isNotEmpty()) {
            return IrRead.Failed("$source 里有不认识的字段：${unknown.sorted().joinToString("、")}（本版支持 ${TOP_KEYS.sorted().joinToString("、")}）")
        }
        val id = obj.string("id")?.trim().orEmpty()
        if (id.isEmpty()) return IrRead.Failed("$source 缺少 id（包 id）")
        PackageIrRules.validateId(id)?.let { return IrRead.Failed("$source：$it") }
        val name = obj.string("name")?.trim().orEmpty().ifEmpty { id }
        if (name.length > 120) return IrRead.Failed("$source：包名太长（>120 字符）")
        val format = PackageFormat.fromId(obj.string("format"))
            ?: return IrRead.Failed("$source 缺少或认不出 format（支持 ${PackageFormat.values().joinToString("、") { it.id }}）")
        val permissions = normalizePermissions(obj.stringList("permissions"))
            ?: return IrRead.Failed("$source 的 permissions 超过 64 条")
        val contributionsElement = obj["contributions"]
            ?: return IrRead.Failed("$source 缺少 contributions（至少一个贡献物）")
        val contributionsArray = contributionsElement as? JsonArray
            ?: return IrRead.Failed("$source 的 contributions 必须是数组")
        if (contributionsArray.isEmpty()) return IrRead.Failed("$source 的 contributions 是空的")
        if (contributionsArray.size > PackageIrRules.MAX_CONTRIBUTIONS) {
            return IrRead.Failed("$source 的贡献物超过 ${PackageIrRules.MAX_CONTRIBUTIONS} 个")
        }
        val contributions = ArrayList<PluginContribution>(contributionsArray.size)
        contributionsArray.forEachIndexed { index, element ->
            val item = element as? JsonObject
                ?: return IrRead.Failed("$source 的 contributions[$index] 不是 JSON 对象")
            val read = decodeContribution(item, index, source)
            if (read is IrContributionRead.Failed) return IrRead.Failed(read.error)
            contributions += (read as IrContributionRead.Ok).contribution
        }
        val ir = PackageIr(
            id = id,
            name = name,
            version = obj.string("version")?.trim().orEmpty().ifEmpty { "0.0.0" },
            description = obj.string("description")?.trim().orEmpty(),
            format = format,
            permissions = permissions,
            contributions = contributions,
            main = obj.string("main")?.trim()?.takeIf { it.isNotEmpty() },
            bundlePatch = obj.string("bundlePatch")?.trim()?.takeIf { it.isNotEmpty() },
            enginesDsh = obj.string("enginesDsh")?.trim()?.takeIf { it.isNotEmpty() },
        )
        PackageIrRules.validate(ir, source)?.let { return IrRead.Failed(it) }
        return IrRead.Ok(ir)
    }

    private sealed interface IrContributionRead {
        data class Ok(val contribution: PluginContribution) : IrContributionRead

        data class Failed(val error: String) : IrContributionRead
    }

    private fun decodeContribution(item: JsonObject, index: Int, source: String): IrContributionRead {
        val where = "$source 的 contributions[$index]"
        val unknown = item.keys - CONTRIBUTION_KEYS
        if (unknown.isNotEmpty()) {
            return IrContributionRead.Failed(
                "$where 有不认识的字段：${unknown.sorted().joinToString("、")}（本版支持 ${CONTRIBUTION_KEYS.sorted().joinToString("、")}）",
            )
        }
        val kind = ContributionKind.fromId(item.string("kind"))
            ?: return IrContributionRead.Failed(
                "$where 的 kind=${item.string("kind").orEmpty()} 认不出（本版支持 ${ContributionKind.ids.joinToString("、")}）",
            )
        val id = item.string("id")?.trim().orEmpty()
        if (id.isEmpty()) return IrContributionRead.Failed("$where 缺少 id")
        if (id.length > 120 || id.any { it.isISOControl() }) {
            return IrContributionRead.Failed("$where 的 id 不合法：$id")
        }
        val config = when (val element = item["config"]) {
            null -> JsonObject(emptyMap())
            is JsonObject -> element
            else -> return IrContributionRead.Failed("$where 的 config 必须是 JSON 对象")
        }
        val bytes = config.toString().toByteArray(Charsets.UTF_8).size
        if (bytes > PackageIrRules.MAX_CONFIG_BYTES) {
            return IrContributionRead.Failed("$where 的 config 太大（${bytes}B > ${PackageIrRules.MAX_CONFIG_BYTES}B）")
        }
        val assets = when (val element = item["assets"]) {
            null -> emptyList()
            is JsonArray -> element.mapNotNull { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }
                .also { if (it.size != element.size) return IrContributionRead.Failed("$where 的 assets 只能是路径字符串数组") }
            else -> return IrContributionRead.Failed("$where 的 assets 必须是数组")
        }
        assets.forEach { path ->
            if (PluginPackage.normalizePath(path) != path) {
                return IrContributionRead.Failed("$where 的素材路径不安全：$path")
            }
        }
        val entry = item.string("entry")?.trim()?.takeIf { it.isNotEmpty() }
        if (entry != null && PluginPackage.normalizePath(entry) != entry) {
            return IrContributionRead.Failed("$where 的 entry 路径不安全：$entry")
        }
        return IrContributionRead.Ok(PluginContribution(kind, id, config, assets, entry))
    }

    private fun normalizePermissions(raw: List<String>): List<String>? {
        val parsed = PluginPermissions.parseAll(raw)
        if (parsed.size > 64) return null
        return parsed.map { it.raw }
    }

    private fun kotlinx.serialization.json.JsonObject.string(key: String): String? =
        this[key]?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }

    private fun kotlinx.serialization.json.JsonObject.stringList(key: String): List<String> =
        this[key]?.let { element ->
            runCatching { element.jsonArray.mapNotNull { it.jsonPrimitive.contentOrNull } }.getOrNull()
        }.orEmpty()
}

/** IR 的校验规则/预算（纯函数，JVM 单测覆盖）。 */
object PackageIrRules {

    const val MAX_CONTRIBUTIONS = 64
    const val MAX_CONFIG_BYTES = 256 * 1024
    const val MAX_SURFACE_NODES = 500

    private val ID_PATTERN = Regex("""^[A-Za-z0-9@][A-Za-z0-9._@/-]{0,119}$""")

    fun validateId(id: String): String? =
        if (ID_PATTERN.matches(id)) null
        else "包 id 不合法：$id（只允许字母数字 @ . _ / -，长度 1–120）"

    /** 校验 IR 自身的形状（不含「文件在不在」，那由 [PackageImporter] 做）。 */
    fun validate(ir: PackageIr, source: String = "包"): String? {
        validateId(ir.id)?.let { return "$source：$it" }
        if (ir.name.isBlank()) return "$source：缺少包名"
        if (ir.name.length > 120) return "$source：包名太长（>120 字符）"
        if (ir.permissions.size > 64) return "$source：权限条目超过 64 个"
        if (ir.contributions.isEmpty()) return "$source：至少需要一个贡献物"
        if (ir.contributions.size > MAX_CONTRIBUTIONS) {
            return "$source：贡献物超过 $MAX_CONTRIBUTIONS 个"
        }
        val seen = HashSet<String>()
        ir.contributions.forEach { contribution ->
            if (!seen.add("${contribution.kind.id}:${contribution.id}")) {
                return "$source：贡献物重复（${contribution.kind.id}:${contribution.id}）"
            }
            contribution.assets.forEach { asset ->
                if (PluginPackage.normalizePath(asset) != asset) {
                    return "$source：贡献物 ${contribution.id} 的素材路径不安全：$asset"
                }
            }
            contribution.entry?.let { entry ->
                if (PluginPackage.normalizePath(entry) != entry) {
                    return "$source：贡献物 ${contribution.id} 的入口路径不安全：$entry"
                }
            }
            if (contribution.config.toString().toByteArray(Charsets.UTF_8).size > MAX_CONFIG_BYTES) {
                return "$source：贡献物 ${contribution.id} 的 config 太大（>${MAX_CONFIG_BYTES}B）"
            }
            validateContribution(contribution, source)?.let { return it }
        }
        if (ir.main != null && PluginPackage.normalizePath(ir.main) != ir.main) {
            return "$source：main 路径不安全：${ir.main}"
        }
        return null
    }

    /** 每种 kind 的最小声明要求（§3.6.2「每个 kind 一个 schema」的轻量版）。 */
    fun validateContribution(c: PluginContribution, source: String = "包"): String? {
        val where = "$source 的 ${c.kind.id} 贡献物 ${c.id}"
        return when (c.kind) {
            ContributionKind.SKILL ->
                if (c.config.string("text").isBlank() && c.assets.isEmpty()) {
                    "$where 需要正文（config.text）或 assets 里的 Markdown 文件"
                } else null

            ContributionKind.PROMPT ->
                if (c.config.string("text").isBlank() &&
                    (c.config["sections"] as? JsonArray)?.isEmpty() != false
                ) {
                    "$where 需要 text（或非空 sections 数组）"
                } else null

            ContributionKind.THEME ->
                if ((c.config["tokens"] as? JsonObject)?.isEmpty() != false) {
                    "$where 需要非空 tokens 对象（颜色/圆角/字体/间距）"
                } else null

            ContributionKind.PAGE -> {
                val surface = c.config["surface"]
                if (surface !is JsonObject) {
                    "$where 需要 surface 树（JSON 对象）"
                } else {
                    val nodes = countNodes(surface)
                    if (nodes > MAX_SURFACE_NODES) "$where 的 surface 节点太多（$nodes > $MAX_SURFACE_NODES）" else null
                }
            }

            ContributionKind.MACRO ->
                if ((c.config["steps"] as? JsonArray)?.isEmpty() != false) {
                    "$where 需要非空 steps 数组"
                } else null

            ContributionKind.MCP -> {
                val server = c.config["server"] as? JsonObject
                if (server == null) {
                    "$where 需要 server 对象"
                } else if (server.string("command").isBlank() && server.string("url").isBlank()) {
                    "$where 的 server 需要 command 或 url"
                } else null
            }

            ContributionKind.TOOL_HTTP -> {
                val request = c.config["request"] as? JsonObject
                val url = request?.string("url").orEmpty().ifBlank { c.config.string("url") }
                if (url.isBlank()) "$where 需要 request.url（或顶层 url）" else null
            }

            ContributionKind.SCRIPT ->
                if (c.entry.isNullOrBlank()) "$where 需要 entry（包内脚本路径）" else null

            // 旧 DSH/Operit 清单只知道「有 dsh.client」不知道具体入口，允许 entry 为空。
            ContributionKind.CLIENT ->
                if (c.entry.isNullOrBlank() && c.config.string("manifest").isBlank()) {
                    "$where 需要 entry（包内脚本路径）"
                } else null

            ContributionKind.ASSET ->
                if (c.assets.isEmpty()) "$where 需要 assets 路径" else null

            // 组合包预设由 cordis.patch.yml 收成一条，字段按上游形状，不强制。
            ContributionKind.PRESET -> null
        }
    }

    private fun countNodes(element: JsonElement): Int = when (element) {
        is JsonObject -> 1 + element.values.sumOf { countNodes(it) }
        is JsonArray -> 1 + element.sumOf { countNodes(it) }
        else -> 1
    }

    internal fun JsonObject.string(key: String): String =
        this[key]?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }.orEmpty()
}

/** 导入入口（供错误文案/诊断页展示）。 */
enum class ImportSource(val label: String) {
    DSH_PACKAGE("dsh.package.json（鲸歌轻格式）"),
    ZIP("压缩包"),
    MARKDOWN("Markdown 单文件"),
    JSON("JSON 单文件"),
    DSH_MANIFEST("DSH package.json"),
    LEGACY_MANIFEST("旧版清单"),
    DIRECTORY("目录"),
}

/** 导入结果：IR + 归一化后的包内文件（路径相对包根）。 */
sealed interface PackageImport {
    data class Ok(
        val ir: PackageIr,
        val files: List<PluginFile>,
        val source: ImportSource,
        /** 旧格式安装仍用原始清单（config schema/默认值等 IR 不重复保存）。 */
        val manifest: PluginManifest? = null,
        /** 自动生成 `dsh.package.json` 的单文件导入（安装时写进插件目录，便于以后重载/导出）。 */
        val generatedManifest: Boolean = false,
    ) : PackageImport

    data class Failed(val error: String) : PackageImport
}

/**
 * 多格式导入：文件夹 / `.zip` / `.dshpkg` / 单 `.md` / 单 `.json` / DSH `package.json`。
 *
 * 所有入口最终产出 [PackageImport.Ok]；旧脚本包额外带回 [PluginManifest]，
 * 让 `PluginStore` 继续走 0.3.4 的安装/试运行链路（行为不变）。
 */
object PackageImporter {

    const val MANIFEST_NAME = "dsh.package.json"
    private const val MAX_TEXT_BYTES = 4L * 1024 * 1024

    /** 从任意文件导入：zip 按压缩包读，文本按 Markdown/JSON 推断。 */
    fun importFile(file: File, sourceName: String? = null): PackageImport {
        if (!file.isFile) return PackageImport.Failed("文件不存在：${file.absolutePath}")
        if (isZip(file)) {
            return when (val read = PluginPackage.read(file)) {
                is ArchiveRead.Ok -> importFiles(read.files)
                is ArchiveRead.Failed -> PackageImport.Failed(read.error)
            }
        }
        if (file.length() > MAX_TEXT_BYTES) {
            return PackageImport.Failed("单文件太大（>${MAX_TEXT_BYTES / 1024 / 1024}MB），不是声明式插件？")
        }
        val text = runCatching { file.readText(Charsets.UTF_8) }.getOrElse {
            return PackageImport.Failed("读不了这个文件：${it.message ?: it.javaClass.simpleName}")
        }
        return importText(text, sourceName ?: file.name)
    }

    /** 从已读出的文件列表导入（zip / 目录 / 素材集合）。 */
    fun importFiles(files: List<PluginFile>): PackageImport {
        if (files.isEmpty()) return PackageImport.Failed("空包")
        // 1) 鲸歌轻格式：dsh.package.json（可能在单层顶层目录里）。
        // 支持纯声明式包，也支持混合多脚本 + 多资源/数据目录 + 客户端 UI 的复合包。
        val manifestFiles = files.filter { it.path == MANIFEST_NAME || it.path.endsWith("/$MANIFEST_NAME") }
        if (manifestFiles.isNotEmpty()) {
            val root = shallowestRoot(manifestFiles.map { it.path }, MANIFEST_NAME)
                ?: return PackageImport.Failed("包里有多个顶层 $MANIFEST_NAME，分不清装哪个")
            val stripped = stripRoot(files, root)
            val manifestFile = stripped.first { it.path == MANIFEST_NAME }
            val text = runCatching { manifestFile.bytes.toString(Charsets.UTF_8) }.getOrNull()
                ?: return PackageImport.Failed("$MANIFEST_NAME 不是 UTF-8 文本")
            val read = PackageIrCodec.decode(text, MANIFEST_NAME)
            if (read is IrRead.Failed) return PackageImport.Failed(read.error)
            val ir = (read as IrRead.Ok).ir
            val packageFiles = stripped.filter { it.path != MANIFEST_NAME }
            validateFiles(ir, packageFiles)?.let { return PackageImport.Failed(it) }
            val scriptEntries = buildList {
                ir.contributions
                    .filter { it.kind == ContributionKind.SCRIPT }
                    .mapNotNull { it.entry?.let { e -> PluginPackage.normalizePath(e) } }
                    .forEach { if (it !in this) add(it) }
                ir.main?.let { PluginPackage.normalizePath(it) }?.let { if (it !in this) add(it) }
            }
            if (scriptEntries.isNotEmpty()) {
                val finalFiles = ArrayList(stripped)
                val entryMain = if (scriptEntries.size == 1) {
                    scriptEntries.first()
                } else {
                    // 多脚本贡献物自动生成统一引导入口，按声明顺序加载各子模块并执行 apply(ctx, config)
                    val synthPath = "__dsh_multi_entry.js"
                    val synthCode = buildString {
                        append("// Auto-generated multi-script entry for dsh.package.json\n")
                        append("export function apply(ctx, config) {\n")
                        append("  const __pending = [];\n")
                        scriptEntries.forEachIndexed { idx, entry ->
                            val reqLit = kotlinx.serialization.json.JsonPrimitive("./$entry").toString()
                            append("  (function() {\n")
                            append("    const __m = require($reqLit);\n")
                            append("    const __fn = (typeof __m === 'function') ? __m : (__m && (typeof __m.apply === 'function' ? __m.apply : (__m.default && typeof __m.default.apply === 'function' ? __m.default.apply : (typeof __m.default === 'function' ? __m.default : null))));\n")
                            append("    if (typeof __fn === 'function') {\n")
                            append("      const __r = __fn(ctx, config);\n")
                            append("      if (__r && typeof __r.then === 'function') { __pending.push(__r); }\n")
                            append("    }\n")
                            append("  })();\n")
                        }
                        append("  return __pending.length ? Promise.all(__pending).then(function(){}) : undefined;\n")
                        append("}\n")
                    }
                    finalFiles.removeAll { it.path == synthPath }
                    finalFiles += PluginFile(synthPath, synthCode.toByteArray(Charsets.UTF_8))
                    synthPath
                }
                val synthesizedManifest = PluginManifest(
                    id = ir.id,
                    name = ir.name,
                    version = ir.version,
                    description = ir.description,
                    main = entryMain,
                    format = PluginFormat.DSH,
                    permissions = ir.permissions,
                    clientUi = ir.contributions.any { it.kind == ContributionKind.CLIENT },
                    bundlePatch = ir.bundlePatch,
                    enginesDsh = ir.enginesDsh,
                    ownership = PluginOwnership.NATIVE,
                )
                return PackageImport.Ok(
                    ir = ir.copy(main = entryMain),
                    files = finalFiles,
                    source = ImportSource.DSH_PACKAGE,
                    manifest = synthesizedManifest,
                )
            }
            return PackageImport.Ok(ir, stripped, ImportSource.DSH_PACKAGE)
        }

        // 2) 旧清单：DSH package.json / Operit manifest.json → 脚本（+ 客户端/预设）贡献。
        val legacy = PluginPackage.parse(files)
        when (legacy) {
            is PackageParse.Ok -> {
                val ir = legacyIr(legacy.manifest, legacy.files)
                val source = if (legacy.manifest.format == PluginFormat.DSH) {
                    ImportSource.DSH_MANIFEST
                } else {
                    ImportSource.LEGACY_MANIFEST
                }
                return PackageImport.Ok(ir, legacy.files, source, manifest = legacy.manifest)
            }

            is PackageParse.Failed -> {
                // 3) 压缩包里只有一个 md/json：按声明式单文件导入（素材就是原文件）。
                if (files.size == 1) {
                    val only = files.first()
                    val name = only.path.substringAfterLast('/')
                    val text = runCatching { only.bytes.toString(Charsets.UTF_8) }.getOrNull()
                    if (text != null && isTextPackageName(name)) {
                        return importText(text, name)
                    }
                }
                return PackageImport.Failed(legacy.error)
            }
        }
    }

    /** 单文件导入：`.js`/`.mjs`/`.cjs`（自动识别 ToolPkg 或 DSH 脚本）、`.yaml`/`.yml`、`.md`、`.json`。 */
    fun importText(text: String, sourceName: String? = null): PackageImport {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return PackageImport.Failed("文件是空的")
        val lowerName = sourceName?.lowercase().orEmpty()
        if (lowerName.endsWith(".js") || lowerName.endsWith(".mjs") || lowerName.endsWith(".cjs") || ToolPkg.isToolPkg(text)) {
            return importSingleJs(text, sourceName)
        }
        if (lowerName.endsWith(".yaml") || lowerName.endsWith(".yml")) {
            return importYaml(trimmed, sourceName)
        }
        if (trimmed.startsWith("{")) return importJsonText(trimmed, sourceName)
        return importMarkdown(trimmed, sourceName)
    }

    /** 单文件 JS 插件导入：支持 Operit ToolPkg 与单文件 DSH apply(ctx) 脚本。 */
    private fun importSingleJs(text: String, sourceName: String?): PackageImport {
        val rawStem = sourceName?.substringAfterLast('/')?.substringBeforeLast('.')?.trim().orEmpty()
        val isToolPkg = ToolPkg.isToolPkg(text)
        val parsedMeta = if (isToolPkg) ToolPkg.metadata(text) else null
        val metaName = (parsedMeta?.get("name") as? JsonPrimitive)?.content?.trim()
        val name = metaName?.takeIf { it.isNotBlank() }
            ?: rawStem.takeIf { it.isNotBlank() }
            ?: "js-plugin"
        val id = derivedId(ContributionKind.SCRIPT, name)
        validateId(id)?.let { return PackageImport.Failed(it) }
        val description = when (val desc = parsedMeta?.get("description")) {
            is JsonPrimitive -> desc.content.trim()
            is JsonObject -> (desc["zh"] as? JsonPrimitive)?.content?.trim()
                ?: (desc["en"] as? JsonPrimitive)?.content?.trim()
                ?: ""
            else -> ""
        }
        val format = if (isToolPkg) PluginFormat.OPERIT else PluginFormat.DSH
        val entryName = sourceName?.substringAfterLast('/')?.takeIf {
            it.endsWith(".js", true) || it.endsWith(".mjs", true) || it.endsWith(".cjs", true)
        } ?: "index.js"
        val manifest = PluginManifest(
            id = id,
            name = name,
            version = "0.1.0",
            description = description,
            main = entryName,
            format = format,
            permissions = emptyList(),
            ownership = PluginOwnership.NATIVE,
        )
        val pkgJson = buildJsonObject {
            put("id", id)
            put("name", name)
            put("version", "0.1.0")
            put("description", description)
            put("main", entryName)
        }.toString()
        val manifestFileName = if (format == PluginFormat.DSH) "package.json" else "manifest.json"
        val files = listOf(
            PluginFile(manifestFileName, pkgJson.toByteArray(Charsets.UTF_8)),
            PluginFile(entryName, text.toByteArray(Charsets.UTF_8)),
        )
        val ir = legacyIr(manifest, files)
        return PackageImport.Ok(
            ir = ir,
            files = files,
            source = if (isToolPkg) ImportSource.LEGACY_MANIFEST else ImportSource.DSH_MANIFEST,
            manifest = manifest,
            generatedManifest = true,
        )
    }

    /** 轻量 YAML 声明式单文件（键值格式，支持 kind / name / id / description / permissions / content）。 */
    private fun importYaml(text: String, sourceName: String?): PackageImport {
        val wrapped = if (text.startsWith("---")) text else "---\n$text\n---\n"
        val (meta, body) = splitFrontmatter(wrapped)
        val content = meta["content"]?.takeIf { it.isNotBlank() } ?: body.ifBlank { text }
        val kindRaw = meta["kind"] ?: "prompt"
        val kind = ContributionKind.fromId(kindRaw)
            ?: return PackageImport.Failed("认不出 YAML 里的 kind=$kindRaw")
        val title = meta["name"]?.takeIf { it.isNotBlank() }
            ?: sourceName?.substringBeforeLast('.')?.takeIf { it.isNotBlank() }
            ?: return PackageImport.Failed("YAML 里没有 name，无法生成插件名")
        val id = meta["id"]?.takeIf { it.isNotBlank() } ?: derivedId(kind, title)
        validateId(id)?.let { return PackageImport.Failed(it) }
        val permissions = metaPermissions(meta)
        val fileName = sourceName?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: "plugin.yaml"
        val contribution = PluginContribution(
            kind = kind,
            id = id,
            config = buildJsonObject {
                put("name", title)
                meta["description"]?.let { put("description", it) }
                put("content", content)
            },
            entry = fileName,
        )
        val ir = PackageIr(
            id = id,
            name = title,
            version = meta["version"]?.takeIf { it.isNotBlank() } ?: "0.1.0",
            description = meta["description"].orEmpty(),
            format = PackageFormat.WHALE_LITE,
            permissions = permissions,
            contributions = listOf(contribution),
        )
        val manifestBytes = PackageIrCodec.encode(ir).toByteArray(Charsets.UTF_8)
        return PackageImport.Ok(
            ir = ir,
            files = listOf(
                PluginFile(MANIFEST_NAME, manifestBytes),
                PluginFile(fileName, text.toByteArray(Charsets.UTF_8)),
            ),
            source = ImportSource.JSON,
            generatedManifest = true,
        )
    }

    /** 旧清单 → IR：id/权限不变，脚本 + 客户端 + cordis 预设收成贡献物。 */
    fun legacyIr(manifest: PluginManifest, files: List<PluginFile>): PackageIr {
        val format = when (manifest.format) {
            PluginFormat.DSH -> PackageFormat.DSH
            PluginFormat.OPERIT -> PackageFormat.LEGACY_JS
        }
        val contributions = ArrayList<PluginContribution>()
        contributions += PluginContribution(
            kind = ContributionKind.SCRIPT,
            id = manifest.id,
            config = buildJsonObject { put("manifest", manifest.format.label) },
            entry = manifest.main,
        )
        if (manifest.clientUi) {
            contributions += PluginContribution(
                kind = ContributionKind.CLIENT,
                id = "${manifest.id}.client",
                config = buildJsonObject { put("manifest", manifest.format.label) },
            )
        }
        val bundleFile = manifest.bundlePatch?.let { relative ->
            PluginPackage.normalizePath(relative)?.let { safe -> files.firstOrNull { it.path == safe } }
        }
        if (bundleFile != null) {
            val patch = runCatching { BundlePatchParser.parse(bundleFile.bytes.toString(Charsets.UTF_8)) }
                .getOrNull()
            patch?.presets.orEmpty().forEach { preset ->
                contributions += PluginContribution(
                    kind = ContributionKind.PRESET,
                    id = preset.presetId,
                    config = buildJsonObject {
                        if (preset.name.isNotBlank()) put("name", preset.name)
                        if (preset.description.isNotBlank()) put("description", preset.description)
                        preset.order?.let { put("order", it) }
                        if (preset.personaPrefix.isNotBlank()) put("personaPrefix", preset.personaPrefix)
                        if (preset.personaSuffix.isNotBlank()) put("personaSuffix", preset.personaSuffix)
                        put("pluginCount", preset.pluginCount)
                    },
                )
            }
        }
        return PackageIr(
            id = manifest.id,
            name = manifest.name,
            version = manifest.version,
            description = manifest.description,
            format = format,
            permissions = manifest.permissions,
            contributions = contributions,
            main = manifest.main,
            bundlePatch = manifest.bundlePatch,
            enginesDsh = manifest.enginesDsh,
        )
    }

    /** 是否 zip（按魔数，不信扩展名：SAF 复制出来的临时文件永远叫 `.zip`）。 */
    fun isZip(file: File): Boolean = runCatching {
        file.inputStream().use { input ->
            val head = ByteArray(4)
            val read = input.read(head)
            read >= 4 && head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte()
        }
    }.getOrDefault(false)

    // ---- Markdown / JSON ----

    private fun importMarkdown(text: String, sourceName: String?): PackageImport {
        val (meta, body) = splitFrontmatter(text)
        val kindRaw = meta["kind"]
        val kind = if (kindRaw != null) {
            ContributionKind.fromId(kindRaw)
                ?: return PackageImport.Failed("认不出 kind=$kindRaw（Markdown 单文件只支持 skill / prompt）")
        } else {
            ContributionKind.SKILL
        }
        if (kind != ContributionKind.SKILL && kind != ContributionKind.PROMPT) {
            return PackageImport.Failed("Markdown 单文件只能导入 skill / prompt（当前 kind=${kind.id}）")
        }
        val title = meta["name"]?.takeIf { it.isNotBlank() }
            ?: firstHeading(body)
            ?: sourceName?.substringBeforeLast('.')?.takeIf { it.isNotBlank() }
            ?: return PackageImport.Failed("Markdown 里没有 name（或一级标题），无法生成插件名")
        val id = meta["id"]?.takeIf { it.isNotBlank() } ?: derivedId(kind, title)
        validateId(id)?.let { return PackageImport.Failed(it) }
        val permissions = metaPermissions(meta)
        val bodyText = body.trim()
        if (bodyText.isEmpty()) return PackageImport.Failed("Markdown 正文是空的")
        val config = buildJsonObject {
            put("title", title)
            put("text", bodyText)
        }
        val assets = emptyList<String>()
        val ir = PackageIr(
            id = id,
            name = title,
            version = meta["version"]?.takeIf { it.isNotBlank() } ?: "0.0.0",
            description = meta["description"]?.trim().orEmpty(),
            format = PackageFormat.WHALE_LITE,
            permissions = permissions,
            contributions = listOf(PluginContribution(kind, id, config, assets)),
        )
        return generated(ir, ImportSource.MARKDOWN)
    }

    private fun importJsonText(text: String, sourceName: String?): PackageImport {
        val obj = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull()
            ?: return PackageImport.Failed("不是合法的 JSON 对象")
        // 顶层有 contributions 且没有 kind = 直接给一份 IR 清单。
        if (obj["contributions"] is JsonArray && obj["kind"] == null) {
            val read = PackageIrCodec.decode(text, sourceName ?: MANIFEST_NAME)
            if (read is IrRead.Failed) return PackageImport.Failed(read.error)
            val ir = (read as IrRead.Ok).ir
            // 单文件带不了素材/脚本文件：引用了包内文件的 IR 必须走 zip/目录。
            validateFiles(ir, emptyList())?.let { return PackageImport.Failed(it) }
            val files = listOf(PluginFile(MANIFEST_NAME, PackageIrCodec.encode(ir).toByteArray(Charsets.UTF_8)))
            return PackageImport.Ok(ir, files, ImportSource.JSON)
        }
        val kindRaw = obj.string("kind")
            ?: return PackageImport.Failed(
                "JSON 里没有 kind（本版支持：${ContributionKind.ids.joinToString("、")}；" +
                    "完整包请把清单命名为 $MANIFEST_NAME 并带 contributions 数组）",
            )
        val kind = ContributionKind.fromId(kindRaw)
            ?: return PackageImport.Failed("认不出 kind=$kindRaw（本版支持：${ContributionKind.ids.joinToString("、")}）")
        val id = obj.string("id")?.takeIf { it.isNotBlank() }
            ?: obj.string("name")?.takeIf { it.isNotBlank() }?.let { derivedId(kind, it) }
            ?: return PackageImport.Failed("JSON 里没有 id/name，无法生成插件 id")
        validateId(id)?.let { return PackageImport.Failed(it) }
        val name = obj.string("name")?.takeIf { it.isNotBlank() } ?: id
        val config = (obj["config"] as? JsonObject) ?: JsonObject(
            obj.filterKeys { it !in JSON_RESERVED },
        )
        val assets = when (val element = obj["assets"]) {
            null -> emptyList()
            is JsonArray -> element.mapNotNull { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }
            else -> return PackageImport.Failed("assets 必须是路径数组")
        }
        val entry = obj.string("entry")?.takeIf { it.isNotBlank() }
        val permissions = obj.stringList("permissions")
            .let { PluginPermissions.parseAll(it).map { permission -> permission.raw } }
        val ir = PackageIr(
            id = id,
            name = name,
            version = obj.string("version")?.takeIf { it.isNotBlank() } ?: "0.0.0",
            description = obj.string("description")?.trim().orEmpty(),
            format = PackageFormat.WHALE_LITE,
            permissions = permissions,
            contributions = listOf(
                PluginContribution(
                    kind = kind,
                    id = obj.string("contributionId")?.takeIf { it.isNotBlank() } ?: id,
                    config = config,
                    assets = assets,
                    entry = entry,
                ),
            ),
        )
        PackageIrRules.validate(ir, sourceName ?: "JSON")?.let { return PackageImport.Failed(it) }
        return generated(ir, ImportSource.JSON)
    }

    private fun generated(ir: PackageIr, source: ImportSource): PackageImport {
        PackageIrRules.validate(ir, "导入")?.let { return PackageImport.Failed(it) }
        val files = listOf(PluginFile(MANIFEST_NAME, PackageIrCodec.encode(ir).toByteArray(Charsets.UTF_8)))
        return PackageImport.Ok(ir, files, source, generatedManifest = true)
    }

    /** 包内入口/素材是否真的存在（安装前最后一道闸）。 */
    fun validateFiles(ir: PackageIr, files: List<PluginFile>): String? {
        val paths = files.mapTo(HashSet()) { it.path }
        ir.contributions.forEach { contribution ->
            contribution.entry?.let { entry ->
                if (entry !in paths) {
                    return "${contribution.kind.id} 贡献物 ${contribution.id} 的入口在包里找不到：$entry"
                }
            }
            contribution.assets.forEach { asset ->
                if (asset !in paths) {
                    return "${contribution.kind.id} 贡献物 ${contribution.id} 的素材在包里找不到：$asset"
                }
            }
        }
        if (ir.main != null && ir.main !in paths) {
            return "main=${ir.main} 在包里找不到"
        }
        return null
    }

    // ---- 内部 ----

    private val JSON_RESERVED = setOf(
        "kind", "id", "name", "version", "description", "permissions", "assets", "entry", "config",
        "contributionId",
    )

    private fun validateId(id: String): String? = PackageIrRules.validateId(id)

    private fun derivedId(kind: ContributionKind, name: String): String {
        val slug = name.lowercase().map { ch ->
            if (ch in 'a'..'z' || ch in '0'..'9' || ch == '.' || ch == '_' || ch == '-') ch else '-'
        }.joinToString("").trim('-', '.').take(60).ifBlank {
            "p" + Integer.toHexString(name.hashCode())
        }
        return "local.${kind.id}.$slug"
    }

    private fun firstHeading(body: String): String? =
        body.lineSequence().firstOrNull { it.trimStart().startsWith("# ") }
            ?.trimStart()?.removePrefix("# ")?.trim()?.takeIf { it.isNotBlank() }

    /**
     * 极简 frontmatter：只认 `key: value` 与 `key:` 下的 `- item` 列表，不引 YAML 依赖。
     * 认不出的行（缩进嵌套、注释）忽略——skill/prompt 的正文才是内容，不让格式细节挡住导入。
     */
    private fun splitFrontmatter(text: String): Pair<Map<String, String>, String> {
        val lines = text.lines()
        if (lines.isEmpty() || lines.first().trim() != "---") {
            return emptyMap<String, String>() to text
        }
        val meta = LinkedHashMap<String, String>()
        var index = 1
        var currentKey: String? = null
        val listValues = LinkedHashMap<String, MutableList<String>>()
        while (index < lines.size && lines[index].trim() != "---") {
            val raw = lines[index]
            index++
            val stripped = raw.trim()
            if (stripped.isEmpty() || stripped.startsWith("#")) continue
            if (stripped.startsWith("- ") && currentKey != null) {
                listValues.getOrPut(currentKey!!) { mutableListOf() }.add(stripped.removePrefix("- ").trim())
                continue
            }
            val colon = stripped.indexOf(':')
            if (colon <= 0) continue
            val key = stripped.take(colon).trim().lowercase()
            val value = stripped.substring(colon + 1).trim()
            currentKey = key
            if (value.isEmpty()) continue
            meta[key] = value.removeSurrounding("\"").removeSurrounding("'")
        }
        if (index >= lines.size) {
            // 只有开头没有结尾的 ---：按没有 frontmatter 处理（正文原样保留）。
            return emptyMap<String, String>() to text
        }
        val body = lines.drop(index + 1).joinToString("\n")
        listValues.forEach { (key, values) ->
            meta[key] = values.joinToString(",")
        }
        return meta to body
    }

    private fun metaPermissions(meta: Map<String, String>): List<String> {
        val raw = meta["permissions"]?.trim().orEmpty()
        if (raw.isEmpty()) return emptyList()
        val items = raw.removePrefix("[").removeSuffix("]")
            .split(',').map { it.trim().removeSurrounding("\"").removeSurrounding("'") }
            .filter { it.isNotEmpty() }
        return PluginPermissions.parseAll(items).map { it.raw }
    }

    private fun isTextPackageName(name: String): Boolean {
        val lower = name.lowercase()
        return lower.endsWith(".md") ||
            lower.endsWith(".markdown") ||
            lower.endsWith(".json") ||
            lower.endsWith(".yaml") ||
            lower.endsWith(".yml") ||
            lower.endsWith(".js") ||
            lower.endsWith(".mjs") ||
            lower.endsWith(".cjs")
    }

    private fun shallowestRoot(paths: List<String>, marker: String): String? {
        val roots = paths.map { it.removeSuffix(marker).removeSuffix("/") }.distinct()
        val minSlash = roots.minOfOrNull { it.count { ch -> ch == '/' } } ?: 0
        val shallow = roots.filter { it.count { ch -> ch == '/' } == minSlash }
        return if (shallow.size == 1) shallow.first() else null
    }

    private fun stripRoot(files: List<PluginFile>, root: String): List<PluginFile> {
        if (root.isEmpty()) return files
        val prefix = "$root/"
        return files.filter { it.path.startsWith(prefix) }
            .map { PluginFile(it.path.removePrefix(prefix), it.bytes) }
    }

    private fun JsonObject.string(key: String): String? =
        this[key]?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }

    private fun JsonObject.stringList(key: String): List<String> =
        this[key]?.let { element ->
            runCatching { element.jsonArray.mapNotNull { it.jsonPrimitive.contentOrNull } }.getOrNull()
        }.orEmpty()
}

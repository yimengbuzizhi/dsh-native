package xin.dshaa.core.plugins

import kotlinx.serialization.json.Json
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

/** 插件包格式。目前只认两种清单，运行形态都按「JS 模块 + apply(ctx)」处理。 */
enum class PluginFormat(val label: String) {
    /** DSH 桌面端 npm 包：`package.json`（name/version/main）+ `lib/index.js`。 */
    DSH("DSH"),

    /** Operit / ToolPkg 风格：`manifest.json` + 入口脚本（兼容登记的兜底格式）。 */
    OPERIT("Operit"),
}

/** `package.json` / `manifest.json` 解析出来的最小清单。 */
data class PluginManifest(
    val id: String,
    val name: String,
    val version: String,
    val description: String,
    /** 包内相对入口路径，如 `lib/index.js`。 */
    val main: String,
    val format: PluginFormat,
    /** 清单里申请的权限（已规范化，M28）。 */
    val permissions: List<String> = emptyList(),
    /**
     * 清单里的配置 schema（ToolPkg/Operit 风格：`manifest.json` 的 `config.schema`；M32）。
     * DSH 包用 `export const Config`，这里为 null。
     */
    val configSchema: JsonObject? = null,
    /** 清单里的配置默认值（`config.default`；M32）。 */
    val configDefaults: JsonObject? = null,
)

/** 包里的一个文件（路径已归一化成 `/` 分隔、已去掉公共顶层目录）。 */
class PluginFile(val path: String, val bytes: ByteArray)

sealed interface PackageParse {
    data class Ok(val manifest: PluginManifest, val root: String, val files: List<PluginFile>) : PackageParse

    data class Failed(val error: String) : PackageParse
}

sealed interface ArchiveRead {
    data class Ok(val files: List<PluginFile>) : ArchiveRead

    data class Failed(val error: String) : ArchiveRead
}

/**
 * 读清单的结果（M37）。
 *
 * [Missing] 只出现在「读已安装目录」时：目录里没有 package.json/manifest.json
 * （旧安装或被手删），此时重载只能重跑入口，不能改清单字段。
 */
sealed interface ManifestRead {
    data class Ok(val manifest: PluginManifest) : ManifestRead

    object Missing : ManifestRead

    data class Failed(val error: String) : ManifestRead
}

/** 试运行里「插件注册了哪些工具」。 */
data class PluginTool(
    val name: String,
    val description: String,
    /** 归一化后的 JSON Schema 文本（null = 认不出参数形状，暂不注入对话）。 */
    val parameters: String? = null,
)

/** 插件试运行（干跑）结果：只读，不改宿主持久状态。 */
data class PluginDryRun(
    val name: String? = null,
    val version: String? = null,
    val inject: List<String> = emptyList(),
    val tools: List<PluginTool> = emptyList(),
    val imports: List<String> = emptyList(),
    val relativeImports: List<String> = emptyList(),
    val applied: Boolean = false,
    val error: String? = null,
    /** 插件 `export const Config = …` 归一化后的 JSON Schema 文本（M30；null = 没有配置）。 */
    val configSchema: String? = null,
    /** Config 序列化失败的原因（插件页/日志可见，不影响工具注册）。 */
    val configError: String? = null,
)

/**
 * 插件工具的一次真实执行结果（M27）。
 * [text] 是给模型的工具结果正文；[error] 非空 = 执行失败（原文给模型和用户看）。
 */
data class PluginInvokeResult(
    val ok: Boolean,
    val text: String = "",
    val error: String? = null,
)

/**
 * 已安装插件清单（`files/plugins.json`）。
 *
 * 一个插件 = 一个目录 + 一条记录。记录里的 [error] 是最近一次试运行的失败文本（没有就 null），
 * 插件页原样展示给用户和 AI 看（docs/05：加载一次就是干跑，报错直接可见）。
 */
data class PluginRecord(
    val id: String,
    val name: String,
    val version: String,
    val description: String,
    val path: String,
    val main: String,
    /** [PluginFormat] 的名字（写 JSON 用字符串，兼容旧文件）。 */
    val format: String,
    val enabled: Boolean,
    val tools: List<String>,
    /** 工具名 → 给模型看的说明（M27 起持久化；旧清单没有就是空，注入时用插件名兜底）。 */
    val toolDescriptions: Map<String, String> = emptyMap(),
    /** 工具名 → 归一化后的 JSON Schema 文本（M27 起持久化；没有 = 暂不注入）。 */
    val toolSchemas: Map<String, String> = emptyMap(),
    val imports: List<String>,
    val error: String?,
    val installedAt: Long,
    /** 清单里申请的权限（规范化后的原文，M28）。 */
    val permissions: List<String> = emptyList(),
    /** 用户已经确认/自动允许的权限；执行插件时才按这张表放行。 */
    val grantedPermissions: List<String> = emptyList(),
    /** `Config` 导出归一化后的 JSON Schema 文本（M30；null = 这个插件没有配置）。 */
    val configSchema: String? = null,
    /**
     * 用户改过的配置（稀疏 JSON 对象文本，只存用户显式设的项；M30）。
     * 空串/`"{}"` 表示全用默认值；执行/展示时由 [PluginSchema.withDefaults] 合并默认值。
     */
    val config: String = "{}",
    /** Config schema 算出来的默认配置（JSON 对象文本，M30；给「恢复默认」和详情展示用）。 */
    val configDefaults: String = "{}",
    /** `Config` 序列化/换算失败的原因（M30；不影响工具注册，但插件页要能看到）。 */
    val configError: String? = null,
)

/**
 * 插件清单文件的读写。格式刻意简单（和 WorkspaceStore 一样手写 JSON）：
 * 坏文件按「没有装过插件」降级，不阻塞插件页。
 */
class PluginRegistryFile(private val file: File) {

    @Synchronized
    fun list(): List<PluginRecord> = load()

    fun find(id: String): PluginRecord? = list().firstOrNull { it.id == id }

    @Synchronized
    fun upsert(record: PluginRecord) {
        val records = load().toMutableList()
        val index = records.indexOfFirst { it.id == record.id }
        if (index >= 0) records[index] = record else records.add(record)
        save(records)
    }

    @Synchronized
    fun remove(id: String): PluginRecord? {
        val records = load().toMutableList()
        val index = records.indexOfFirst { it.id == id }
        if (index < 0) return null
        val removed = records.removeAt(index)
        save(records)
        return removed
    }

    @Synchronized
    fun setEnabled(id: String, enabled: Boolean): PluginRecord? {
        val records = load().toMutableList()
        val index = records.indexOfFirst { it.id == id }
        if (index < 0) return null
        records[index] = records[index].copy(enabled = enabled)
        save(records)
        return records[index]
    }

    /**
     * 从备份里的 `plugins.json` 合并记录（M35）：只补当前清单里没有的 id，不覆盖已装插件。
     * [pathFor] 把备份里的相对路径（其实只写了插件 id）重写成当前设备上的绝对路径。
     * 返回真正新增的插件 id。
     */
    @Synchronized
    fun mergeFromRegistryJson(text: String, pathFor: (String) -> String): List<String> {
        val incoming = runCatching { parse(text) }.getOrDefault(emptyList())
        if (incoming.isEmpty()) return emptyList()
        val records = load().toMutableList()
        val existing = records.mapTo(HashSet()) { it.id }
        val added = ArrayList<String>()
        incoming.forEach { record ->
            if (existing.add(record.id)) {
                records += record.copy(path = pathFor(record.id))
                added += record.id
            }
        }
        if (added.isNotEmpty()) save(records)
        return added
    }

    private fun load(): List<PluginRecord> {
        if (!file.isFile) return emptyList()
        return runCatching { parse(file.readText(Charsets.UTF_8)) }.getOrDefault(emptyList())
    }

    private fun parse(text: String): List<PluginRecord> {
        return runCatching {
            val root = Json.parseToJsonElement(text).jsonObject
            root["plugins"]?.jsonArray?.mapNotNull { element ->
                val obj = element.jsonObject
                val id = obj["id"]?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() } ?: return@mapNotNull null
                val path = obj["path"]?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() } ?: return@mapNotNull null
                val permissions = obj.stringList("permissions")
                PluginRecord(
                    id = id,
                    name = obj.string("name") ?: id,
                    version = obj.string("version") ?: "",
                    description = obj.string("description") ?: "",
                    path = path,
                    main = obj.string("main") ?: "",
                    format = obj.string("format") ?: PluginFormat.DSH.label,
                    enabled = obj.boolean("enabled") ?: true,
                    tools = obj.stringList("tools"),
                    toolDescriptions = obj.stringMap("toolDescriptions"),
                    toolSchemas = obj.stringMap("toolSchemas"),
                    imports = obj.stringList("imports"),
                    error = obj.string("error")?.takeIf { it.isNotBlank() },
                    installedAt = obj.long("installedAt") ?: 0L,
                    permissions = permissions,
                    // 手改过的授权表也要重新过一遍白名单：清单里没申请的权限不生效。
                    grantedPermissions = PluginPermissions
                        .sanitizeGrants(PluginPermissions.parseAll(permissions), obj.stringList("grantedPermissions"))
                        .toList(),
                    configSchema = obj.string("configSchema")?.takeIf { it.isNotBlank() },
                    config = obj.string("config")?.takeIf { it.isNotBlank() } ?: "{}",
                    configDefaults = obj.string("configDefaults")?.takeIf { it.isNotBlank() } ?: "{}",
                    configError = obj.string("configError")?.takeIf { it.isNotBlank() },
                )
            }.orEmpty()
        }.getOrDefault(emptyList())
    }

    private fun save(records: List<PluginRecord>) {
        runCatching {
            file.parentFile?.mkdirs()
            val root = buildJsonObject {
                put("version", 1)
                put(
                    "plugins",
                    buildJsonArray {
                        records.forEach { record ->
                            add(
                                buildJsonObject {
                                    put("id", record.id)
                                    put("name", record.name)
                                    put("version", record.version)
                                    put("description", record.description)
                                    put("path", record.path)
                                    put("main", record.main)
                                    put("format", record.format)
                                    put("enabled", record.enabled)
                                    put("tools", buildJsonArray { record.tools.forEach { add(JsonPrimitive(it)) } })
                                    if (record.toolDescriptions.isNotEmpty()) {
                                        put(
                                            "toolDescriptions",
                                            buildJsonObject {
                                                record.toolDescriptions.forEach { (name, description) ->
                                                    put(name, description)
                                                }
                                            },
                                        )
                                    }
                                    if (record.toolSchemas.isNotEmpty()) {
                                        put(
                                            "toolSchemas",
                                            buildJsonObject {
                                                record.toolSchemas.forEach { (name, schema) -> put(name, schema) }
                                            },
                                        )
                                    }
                                    put("imports", buildJsonArray { record.imports.forEach { add(JsonPrimitive(it)) } })
                                    put("permissions", buildJsonArray { record.permissions.forEach { add(JsonPrimitive(it)) } })
                                    put(
                                        "grantedPermissions",
                                        buildJsonArray { record.grantedPermissions.forEach { add(JsonPrimitive(it)) } },
                                    )
                                    record.configSchema?.let { put("configSchema", it) }
                                    if (record.config != "{}") put("config", record.config)
                                    if (record.configDefaults != "{}") put("configDefaults", record.configDefaults)
                                    record.configError?.let { put("configError", it) }
                                    record.error?.let { put("error", it) }
                                    put("installedAt", record.installedAt)
                                },
                            )
                        }
                    },
                )
            }
            val text = root.toString()
            val tmp = File(file.parentFile, "${file.name}.tmp")
            tmp.writeText(text, Charsets.UTF_8)
            if (!tmp.renameTo(file)) {
                file.writeText(text, Charsets.UTF_8)
                tmp.delete()
            }
        }
    }

    private fun kotlinx.serialization.json.JsonObject.string(key: String): String? =
        this[key]?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }

    private fun kotlinx.serialization.json.JsonObject.boolean(key: String): Boolean? =
        this[key]?.let { runCatching { it.jsonPrimitive.content.toBooleanStrictOrNull() }.getOrNull() }

    private fun kotlinx.serialization.json.JsonObject.long(key: String): Long? =
        this[key]?.let { runCatching { it.jsonPrimitive.content.toLongOrNull() }.getOrNull() }

    private fun kotlinx.serialization.json.JsonObject.stringList(key: String): List<String> =
        this[key]?.let { element ->
            runCatching { element.jsonArray.mapNotNull { it.jsonPrimitive.contentOrNull } }.getOrNull()
        }.orEmpty()

    /** 字符串 map（工具说明 / 工具 schema）；值不是字符串就跳过，坏一条不影响整份清单。 */
    private fun kotlinx.serialization.json.JsonObject.stringMap(key: String): Map<String, String> =
        this[key]?.let { element ->
            runCatching {
                element.jsonObject.mapNotNull { (name, value) ->
                    value.jsonPrimitive.contentOrNull?.let { name to it }
                }.toMap()
            }.getOrNull()
        }.orEmpty()
}

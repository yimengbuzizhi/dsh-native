package xin.dshaa.core.backup

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.BufferedOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** 备份包不是 DSH 原生格式（或版本太新）时抛出；调用方直接把 message 给用户看。 */
class BackupFormatException(message: String) : Exception(message)

private fun JsonObject.jsonString(key: String): String? =
    this[key]?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }

/** 备份里的一条会话（manifest 用；不存消息正文，正文在 sessions/ 下的原文件里）。 */
data class BackupSessionEntry(
    val id: String,
    val workspace: String,
    /** 旧版只读导入的会话：目录里有 imported.json 侧车，恢复后仍只读。 */
    val legacy: Boolean,
    val bytes: Long,
    val sha256: String,
    /**
     * 会话 header 里的原工作区路径（M36，docs/60）。
     * 旧备份没有这个字段 → 空串；恢复时只在原路径不可用时才映射到当前工作区。
     */
    val cwd: String = "",
)

/** 备份里的一个插件（名字/版本来自 plugins.json；插件文件在 plugins/<id>/ 下）。 */
data class BackupPluginEntry(
    val id: String,
    val name: String,
    val version: String,
    val enabled: Boolean,
    val bytes: Long,
)

/**
 * `manifest.json` 的内存形态。
 *
 * 格式 v1（**不含任何密钥**）：
 * ```
 * manifest.json
 * settings.json                  # 按类型分组，见 App 侧 BackupManager
 * sessions/<workspace>/<id>/session.v4.jsonl.zstd
 * sessions/<workspace>/<id>/imported.json        # 仅旧版只读会话
 * plugins/plugins.json           # 已去掉绝对路径
 * plugins/<id>/...               # 插件包原文件
 * attachments/objects/xx/<sha256># 内容寻址附件（可选）
 * ```
 */
data class BackupManifest(
    val createdAt: Long,
    val appVersion: String,
    val sessions: List<BackupSessionEntry>,
    val plugins: List<BackupPluginEntry>,
    val settingsKeys: Int,
    val attachmentsFiles: Int,
    val attachmentsBytes: Long,
    /** 备份包内所有原文件字节数之和（不是 zip 压缩后的大小）。 */
    val totalBytes: Long,
    val includesSettings: Boolean,
    val includesPlugins: Boolean,
    val includesAttachments: Boolean,
    val warnings: List<String> = emptyList(),
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("type", NativeBackup.TYPE)
        put("version", NativeBackup.VERSION)
        put("createdAt", createdAt)
        put("appVersion", appVersion)
        put(
            "includes",
            buildJsonObject {
                put("settings", includesSettings)
                put("plugins", includesPlugins)
                put("attachments", includesAttachments)
                // 密钥/账号令牌永远不进备份包（docs/59）。
                put("credentials", false)
            },
        )
        put(
            "sessions",
            buildJsonArray {
                sessions.forEach { session ->
                    add(
                        buildJsonObject {
                            put("id", session.id)
                            put("workspace", session.workspace)
                            put("legacy", session.legacy)
                            put("bytes", session.bytes)
                            put("sha256", session.sha256)
                            if (session.cwd.isNotBlank()) put("cwd", session.cwd)
                        },
                    )
                }
            },
        )
        put(
            "plugins",
            buildJsonArray {
                plugins.forEach { plugin ->
                    add(
                        buildJsonObject {
                            put("id", plugin.id)
                            put("name", plugin.name)
                            put("version", plugin.version)
                            put("enabled", plugin.enabled)
                            put("bytes", plugin.bytes)
                        },
                    )
                }
            },
        )
        put(
            "counts",
            buildJsonObject {
                put("sessions", sessions.size)
                put("plugins", plugins.size)
                put("settingsKeys", settingsKeys)
                put("attachmentsFiles", attachmentsFiles)
                put("attachmentsBytes", attachmentsBytes)
                put("totalBytes", totalBytes)
            },
        )
        put(
            "warnings",
            buildJsonArray { warnings.forEach { add(JsonPrimitive(it)) } },
        )
    }

    companion object {
        fun parse(text: String): BackupManifest {
            val root = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrElse {
                throw BackupFormatException("manifest.json 不是合法的 JSON 对象")
            }
            val type = root.jsonString("type")
            if (type != NativeBackup.TYPE) {
                throw BackupFormatException("不是 DSH 原生备份（type=${type ?: "空"}）")
            }
            val version = root["version"]?.jsonPrimitive?.intOrNull ?: 0
            if (version > NativeBackup.VERSION) {
                throw BackupFormatException("备份版本 $version 比当前 App 支持的 ${NativeBackup.VERSION} 新")
            }
            val includes = root["includes"]?.jsonObject
            val counts = root["counts"]?.jsonObject
            return BackupManifest(
                createdAt = root["createdAt"]?.jsonPrimitive?.longOrNull ?: 0L,
                appVersion = root.jsonString("appVersion").orEmpty(),
                sessions = root["sessions"]?.jsonArray?.mapNotNull { element ->
                    val obj = element.jsonObject
                    val id = obj.jsonString("id") ?: return@mapNotNull null
                    BackupSessionEntry(
                        id = id,
                        workspace = obj.jsonString("workspace").orEmpty(),
                        legacy = obj["legacy"]?.jsonPrimitive?.booleanOrNull ?: false,
                        bytes = obj["bytes"]?.jsonPrimitive?.longOrNull ?: 0L,
                        sha256 = obj.jsonString("sha256").orEmpty(),
                        cwd = obj.jsonString("cwd").orEmpty(),
                    )
                }.orEmpty(),
                plugins = root["plugins"]?.jsonArray?.mapNotNull { element ->
                    val obj = element.jsonObject
                    val id = obj.jsonString("id") ?: return@mapNotNull null
                    BackupPluginEntry(
                        id = id,
                        name = obj.jsonString("name") ?: id,
                        version = obj.jsonString("version").orEmpty(),
                        enabled = obj["enabled"]?.jsonPrimitive?.booleanOrNull ?: true,
                        bytes = obj["bytes"]?.jsonPrimitive?.longOrNull ?: 0L,
                    )
                }.orEmpty(),
                settingsKeys = counts?.get("settingsKeys")?.jsonPrimitive?.intOrNull ?: 0,
                attachmentsFiles = counts?.get("attachmentsFiles")?.jsonPrimitive?.intOrNull ?: 0,
                attachmentsBytes = counts?.get("attachmentsBytes")?.jsonPrimitive?.longOrNull ?: 0L,
                totalBytes = counts?.get("totalBytes")?.jsonPrimitive?.longOrNull ?: 0L,
                includesSettings = includes?.get("settings")?.jsonPrimitive?.booleanOrNull ?: true,
                includesPlugins = includes?.get("plugins")?.jsonPrimitive?.booleanOrNull ?: false,
                includesAttachments = includes?.get("attachments")?.jsonPrimitive?.booleanOrNull ?: false,
                warnings = root["warnings"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty(),
            )
        }
    }
}

/** 恢复结果（会话 / 附件共用；[ids] 是真正落盘的会话 id 或插件 id）。 */
data class RestoreReport(
    val restored: Int = 0,
    val skipped: Int = 0,
    val failed: Int = 0,
    val bytes: Long = 0,
    val ids: List<String> = emptyList(),
    val errors: List<String> = emptyList(),
    /**
     * 恢复确认里没勾选的工作区/会话数（M39）。
     * 只统计 manifest 里列出的会话；插件/附件/设置不受勾选影响。
     */
    val deselected: Int = 0,
) {
    val ok: Boolean get() = failed == 0
    fun summary(): String = "恢复 $restored，跳过 $skipped，失败 $failed"
}

/** 插件恢复：除了文件，还要把备份里的 `plugins.json` 交给 App 合并（路径会重写）。 */
data class PluginRestoreReport(
    val restoredIds: List<String> = emptyList(),
    val restoredFiles: Int = 0,
    val skipped: Int = 0,
    val failed: Int = 0,
    val errors: List<String> = emptyList(),
    /** 备份里的 plugins.json 原文（`path` 已替换成插件 id，合并时重写绝对路径）。 */
    val registryJson: String? = null,
)

/**
 * 原生备份 v1：导出 / 检查 / 恢复。
 *
 * 设计原则（docs/59）：
 * 1. **原文件直搬**：会话是 v4 JSONL 的原始 zstd 字节，恢复后不是「只读副本」，可以直接续聊；
 * 2. **只认自己的格式**：manifest.type 必须对，避免把旧版备份/随便一个 zip 当自家备份；
 * 3. **不碰密钥**：settings 由 App 过滤后传入，API Key / 账号令牌永远不写进 zip；
 * 4. **恢复保守**：同 id 会话已存在就跳过（不覆盖），附件按内容寻址已存在就跳过；
 * 5. **路径守卫**：zip entry 只接受 `sessions/<ws>/<id>/<file>` 这类规范路径，`..`/绝对路径一律拒绝。
 */
object NativeBackup {

    const val TYPE = "dsh.native.backup"
    const val VERSION = 1

    const val MANIFEST_ENTRY = "manifest.json"
    const val SETTINGS_ENTRY = "settings.json"
    const val PLUGIN_REGISTRY_ENTRY = "plugins/plugins.json"

    private const val SESSIONS_PREFIX = "sessions/"
    private const val PLUGINS_PREFIX = "plugins/"
    private const val ATTACHMENTS_PREFIX = "attachments/"

    const val SESSION_FILE = "session.v4.jsonl.zstd"
    const val IMPORTED_SIDECAR = "imported.json"

    private const val MAX_SESSION_ENTRY = 512L * 1024 * 1024
    private const val MAX_PLUGIN_ENTRY = 64L * 1024 * 1024
    private const val MAX_ATTACHMENT_ENTRY = 256L * 1024 * 1024

    private val SHA256_NAME = Regex("[0-9a-f]{64}")

    // ------------------------------------------------------------------ 导出

    /**
     * 写一个备份 zip。调用方负责关闭 [out]；本方法会把 zip 收尾（central directory）写完。
     *
     * @param settings 已由 App 过滤过的设置快照（不得包含任何密钥）。
     * @param sessionCwds 会话 id → header.cwd（M36）：只写进 manifest 供恢复前提示/诊断，
     *   不参与导出内容；缺失的会话该字段为空。
     */
    fun export(
        out: OutputStream,
        sessionsRoot: File,
        settings: JsonObject,
        pluginsRoot: File? = null,
        pluginRegistry: File? = null,
        attachmentsRoot: File? = null,
        appVersion: String = "",
        now: Long = System.currentTimeMillis(),
        sessionCwds: Map<String, String> = emptyMap(),
    ): BackupManifest {
        val warnings = ArrayList<String>()
        val sessions = scanSessions(sessionsRoot, warnings)
        val settingsText = settings.toString()
        val settingsBytes = settingsText.toByteArray(Charsets.UTF_8)
        val registryText = pluginRegistry?.takeIf { it.isFile }?.let { sanitizeRegistry(it.readText(Charsets.UTF_8)) }
        val registryBytes = registryText?.toByteArray(Charsets.UTF_8)
        val registryInfo = parseRegistryInfo(registryText)
        val pluginDirs = scanPlugins(pluginsRoot, warnings)
        val plugins = pluginDirs.map { p ->
            BackupPluginEntry(
                id = p.id,
                name = registryInfo[p.id]?.first ?: p.id,
                version = registryInfo[p.id]?.second ?: "",
                enabled = registryInfo[p.id]?.third ?: true,
                bytes = p.files.sumOf { it.length() },
            )
        }
        val attachments = scanFiles(attachmentsRoot)

        val totalBytes =
            sessions.sumOf { it.bytes + (it.sidecar?.length() ?: 0L) } +
                settingsBytes.size +
                (registryBytes?.size ?: 0) +
                plugins.sumOf { it.bytes } +
                attachments.sumOf { it.length() }

        val manifest = BackupManifest(
            createdAt = now,
            appVersion = appVersion,
            sessions = sessions.map {
                BackupSessionEntry(
                    it.id,
                    it.workspace,
                    it.sidecar != null,
                    it.bytes,
                    it.sha256,
                    sessionCwds[it.id].orEmpty(),
                )
            },
            plugins = plugins,
            settingsKeys = countSettings(settings),
            attachmentsFiles = attachments.size,
            attachmentsBytes = attachments.sumOf { it.length() },
            totalBytes = totalBytes,
            includesSettings = true,
            includesPlugins = plugins.isNotEmpty() || registryText != null,
            includesAttachments = attachments.isNotEmpty(),
            warnings = warnings,
        )

        ZipOutputStream(BufferedOutputStream(out)).use { zos ->
            putText(zos, MANIFEST_ENTRY, manifest.toJson().toString(), manifest.createdAt)
            putText(zos, SETTINGS_ENTRY, settingsText, manifest.createdAt)
            sessions.forEach { session ->
                putFile(zos, "$SESSIONS_PREFIX${session.workspace}/${session.id}/$SESSION_FILE", session.file)
                session.sidecar?.let { sidecar ->
                    putFile(zos, "$SESSIONS_PREFIX${session.workspace}/${session.id}/$IMPORTED_SIDECAR", sidecar)
                }
            }
            registryText?.let { putText(zos, PLUGIN_REGISTRY_ENTRY, it, manifest.createdAt) }
            pluginDirs.forEach { plugin ->
                plugin.files.forEach { file ->
                    putFile(zos, "$PLUGINS_PREFIX${plugin.id}/${relative(file, plugin.dir)}", file)
                }
            }
            val attachmentsSuper = attachmentsRoot
            if (attachmentsSuper != null) {
                attachments.forEach { file ->
                    putFile(zos, "$ATTACHMENTS_PREFIX${relative(file, attachmentsSuper)}", file)
                }
            }
            zos.finish()
        }
        return manifest
    }

    private data class ScannedSession(
        val id: String,
        val workspace: String,
        val file: File,
        val sidecar: File?,
        val bytes: Long,
        val sha256: String,
    )

    private fun scanSessions(root: File, warnings: MutableList<String>): List<ScannedSession> {
        if (!root.isDirectory) return emptyList()
        val seen = HashSet<String>()
        val out = ArrayList<ScannedSession>()
        root.listFiles()
            ?.filter { it.isDirectory && it.name != ".trash" && !it.name.startsWith(".") }
            ?.sortedBy { it.name }
            ?.forEach { workspace ->
                workspace.listFiles()
                    ?.filter { it.isDirectory && !it.name.startsWith(".") }
                    ?.sortedBy { it.name }
                    ?.forEach { dir ->
                        val file = File(dir, SESSION_FILE)
                        if (!file.isFile) return@forEach
                        if (!seen.add(dir.name)) {
                            warnings += "会话 id 重复，只备份第一份：${dir.name}"
                            return@forEach
                        }
                        if (file.length() > MAX_SESSION_ENTRY) {
                            warnings += "会话 ${dir.name} 超过 512MB，跳过"
                            return@forEach
                        }
                        runCatching {
                            ScannedSession(
                                id = dir.name,
                                workspace = workspace.name,
                                file = file,
                                sidecar = File(dir, IMPORTED_SIDECAR).takeIf { it.isFile },
                                bytes = file.length(),
                                sha256 = sha256(file),
                            )
                        }.onSuccess { out += it }
                            .onFailure { warnings += "会话 ${dir.name} 读取失败：${it.message ?: "未知错误"}" }
                    }
            }
        return out
    }

    private data class ScannedPlugin(val id: String, val dir: File, val files: List<File>)

    private fun scanPlugins(root: File?, warnings: MutableList<String>): List<ScannedPlugin> {
        if (root == null || !root.isDirectory) return emptyList()
        return root.listFiles()
            ?.filter { it.isDirectory && !it.name.startsWith(".") }
            ?.sortedBy { it.name }
            ?.mapNotNull { dir ->
                val files = dir.walkTopDown().filter { it.isFile }.toList()
                if (files.isEmpty()) {
                    warnings += "插件 ${dir.name} 没有文件，跳过"
                    null
                } else {
                    ScannedPlugin(dir.name, dir, files)
                }
            }
            .orEmpty()
    }

    private fun scanFiles(root: File?): List<File> {
        if (root == null || !root.isDirectory) return emptyList()
        return root.walkTopDown().filter { it.isFile }.sortedBy { it.absolutePath }.toList()
    }

    private fun countSettings(settings: JsonObject): Int =
        settings.values.sumOf { (it as? JsonObject)?.size ?: 0 }

    /**
     * 备份里的 plugins.json 去掉本机绝对路径：`path` 改成插件 id（恢复时由 App 重写成新路径）。
     * 解析失败就原样保留——插件清单坏了不影响会话备份。
     */
    private fun sanitizeRegistry(text: String): String = runCatching {
        val root = Json.parseToJsonElement(text).jsonObject
        val records = root["plugins"]?.jsonArray ?: return@runCatching text
        val sanitized = buildJsonArray {
            records.forEach { element ->
                val obj = element.jsonObject
                add(
                    buildJsonObject {
                        obj.forEach { (key, value) -> if (key != "path") put(key, value) }
                        put("path", obj.jsonString("id").orEmpty())
                    },
                )
            }
        }
        buildJsonObject {
            root.forEach { (key, value) -> if (key != "plugins") put(key, value) }
            put("plugins", sanitized)
        }.toString()
    }.getOrDefault(text)

    private fun parseRegistryInfo(text: String?): Map<String, Triple<String, String, Boolean>> {
        if (text.isNullOrBlank()) return emptyMap()
        return runCatching {
            val root = Json.parseToJsonElement(text).jsonObject
            root["plugins"]?.jsonArray?.mapNotNull { element ->
                val obj = element.jsonObject
                val id = obj.jsonString("id") ?: return@mapNotNull null
                id to Triple(
                    obj.jsonString("name") ?: id,
                    obj.jsonString("version").orEmpty(),
                    obj["enabled"]?.jsonPrimitive?.booleanOrNull ?: true,
                )
            }?.toMap().orEmpty()
        }.getOrDefault(emptyMap())
    }

    // ------------------------------------------------------------------ 检查

    /** 只读 manifest；不是 DSH 原生备份就抛 [BackupFormatException]。 */
    fun inspect(zip: File): BackupManifest {
        if (!zip.isFile) throw BackupFormatException("备份文件不存在：${zip.name}")
        return ZipFile(zip).use { zf ->
            val entry = zf.getEntry(MANIFEST_ENTRY)
                ?: throw BackupFormatException("zip 里没有 manifest.json，不是 DSH 原生备份")
            val text = zf.getInputStream(entry).use { it.readBytes().toString(Charsets.UTF_8) }
            BackupManifest.parse(text)
        }
    }

    // ------------------------------------------------------------------ 恢复：会话

    /**
     * 恢复会话：同 id 已存在就跳过（不覆盖现有会话）。
     * 会话文件按 manifest 里的 sha256 校验；恢复出来**没有 imported.json 的新会话可直接续聊**，
     * 旧版只读会话的侧车会一起恢复，保持只读。
     *
     * [onlySessionIds] 非空时（M39）：只恢复这些 id，manifest 里其余的会话计入
     * [RestoreReport.deselected]，不算「跳过」也不算失败；null = 全恢复（旧行为）。
     */
    fun restoreSessions(
        zip: File,
        sessionsRoot: File,
        onlySessionIds: Set<String>? = null,
    ): RestoreReport {
        val manifest = inspect(zip)
        val expected = manifest.sessions.associateBy { it.id }
        val existing = existingSessionIds(sessionsRoot)
        val knownIds = expected.keys
        val selected: Set<String>? = onlySessionIds?.let { wanted ->
            manifest.sessions.filter { it.id in wanted }.map { it.id }.toSet()
        }
        val deselected = onlySessionIds?.let { wanted -> manifest.sessions.count { it.id !in wanted } } ?: 0
        var restored = 0
        var skipped = 0
        var failed = 0
        var bytes = 0L
        val ids = ArrayList<String>()
        val errors = ArrayList<String>()

        ZipFile(zip).use { zf ->
            val groups = LinkedHashMap<String, MutableList<ZipEntry>>()
            zf.entries().asSequence().forEach { entry ->
                if (entry.isDirectory || !entry.name.startsWith(SESSIONS_PREFIX)) return@forEach
                val parts = safeParts(entry.name)
                if (parts == null || parts.size != 4 || parts[0] != "sessions") {
                    failed++
                    if (errors.size < 12) errors += "非法会话路径：${entry.name}"
                    return@forEach
                }
                val name = parts[3]
                if (name != SESSION_FILE && name != IMPORTED_SIDECAR) {
                    failed++
                    if (errors.size < 12) errors += "不认识的会话文件：${entry.name}"
                    return@forEach
                }
                groups.getOrPut(parts[2]) { mutableListOf() } += entry
            }

            groups.forEach { (id, entries) ->
                // 没勾选的会话直接不碰；manifest 里没有的 id 不参与勾选，仍按原逻辑校验。
                if (selected != null && id in knownIds && id !in selected) return@forEach
                val workspaces = entries.map { it.name.split('/')[1] }.distinct()
                if (workspaces.size != 1) {
                    failed++
                    errors += "会话 $id 的工作区不一致"
                    return@forEach
                }
                if (id in existing) {
                    skipped++
                    return@forEach
                }
                val hasSession = entries.any { it.name.endsWith("/$SESSION_FILE") }
                if (!hasSession) {
                    skipped++
                    errors += "会话 $id 没有主文件，跳过"
                    return@forEach
                }
                val staging = File(sessionsRoot, ".restore-$id-${System.nanoTime()}")
                try {
                    staging.mkdirs()
                    entries.forEach { entry ->
                        val target = File(staging, entry.name.substringAfterLast('/'))
                        zf.getInputStream(entry).use { input ->
                            target.outputStream().use { output -> copyLimited(input, output, MAX_SESSION_ENTRY) }
                        }
                    }
                    val main = File(staging, SESSION_FILE)
                    val want = expected[id]?.sha256
                    if (!want.isNullOrBlank() && sha256(main) != want) {
                        throw BackupFormatException("会话 $id 的 sha256 对不上（文件损坏）")
                    }
                    val workspace = workspaces.first()
                    val targetDir = File(File(sessionsRoot, workspace), id)
                    if (targetDir.exists()) {
                        skipped++
                        staging.deleteRecursively()
                        return@forEach
                    }
                    targetDir.parentFile?.mkdirs()
                    if (!staging.renameTo(targetDir)) {
                        targetDir.mkdirs()
                        staging.listFiles()?.forEach { file ->
                            file.copyTo(File(targetDir, file.name), overwrite = true)
                        }
                        staging.deleteRecursively()
                    }
                    restored++
                    bytes += main.length()
                    ids += id
                } catch (e: Exception) {
                    failed++
                    if (errors.size < 12) errors += "会话 $id：${e.message ?: e.javaClass.simpleName}"
                    staging.deleteRecursively()
                }
            }
        }
        return RestoreReport(restored, skipped, failed, bytes, ids, errors, deselected)
    }

    // ------------------------------------------------------------------ 恢复：插件

    /**
     * 恢复插件文件到 `plugins/<id>/`（目录已存在就跳过），并返回备份里的 plugins.json 原文。
     * 记录合并由 App 的 PluginRegistryFile 做（它知道现有清单与路径重写规则）。
     */
    fun restorePlugins(zip: File, pluginsRoot: File): PluginRestoreReport {
        inspect(zip)
        val errors = ArrayList<String>()
        val ids = ArrayList<String>()
        var restoredFiles = 0
        var skipped = 0
        var failed = 0
        var registryJson: String? = null

        ZipFile(zip).use { zf ->
            val groups = LinkedHashMap<String, MutableList<ZipEntry>>()
            zf.entries().asSequence().forEach { entry ->
                if (entry.isDirectory || !entry.name.startsWith(PLUGINS_PREFIX)) return@forEach
                if (entry.name == PLUGIN_REGISTRY_ENTRY) {
                    registryJson = zf.getInputStream(entry).use { it.readBytes().toString(Charsets.UTF_8) }
                    return@forEach
                }
                val parts = safeParts(entry.name)
                if (parts == null || parts.size < 3 || parts[0] != "plugins") {
                    failed++
                    if (errors.size < 12) errors += "非法插件路径：${entry.name}"
                    return@forEach
                }
                groups.getOrPut(parts[1]) { mutableListOf() } += entry
            }

            groups.forEach { (id, entries) ->
                val targetDir = File(pluginsRoot, id)
                if (targetDir.exists()) {
                    skipped++
                    return@forEach
                }
                val staging = File(pluginsRoot, ".restore-$id-${System.nanoTime()}")
                try {
                    entries.forEach { entry ->
                        val relative = entry.name.removePrefix("$PLUGINS_PREFIX$id/")
                        val parts = safeParts(relative) ?: throw BackupFormatException("插件文件路径非法")
                        val target = File(staging, parts.joinToString(File.separator))
                        target.parentFile?.mkdirs()
                        zf.getInputStream(entry).use { input ->
                            target.outputStream().use { output -> copyLimited(input, output, MAX_PLUGIN_ENTRY) }
                        }
                        restoredFiles++
                    }
                    pluginsRoot.mkdirs()
                    if (!staging.renameTo(targetDir)) {
                        targetDir.mkdirs()
                        staging.listFiles()?.forEach { file ->
                            file.copyTo(File(targetDir, file.name), overwrite = true)
                        }
                        staging.deleteRecursively()
                    }
                    ids += id
                } catch (e: Exception) {
                    failed++
                    if (errors.size < 12) errors += "插件 $id：${e.message ?: e.javaClass.simpleName}"
                    staging.deleteRecursively()
                }
            }
        }
        return PluginRestoreReport(ids, restoredFiles, skipped, failed, errors, registryJson)
    }

    // ------------------------------------------------------------------ 恢复：附件 / 设置

    /** 恢复内容寻址附件；目标已存在就跳过，文件名是 64 位 hex 时校验内容 sha256。 */
    fun restoreAttachments(zip: File, attachmentsRoot: File): RestoreReport {
        inspect(zip)
        var restored = 0
        var skipped = 0
        var failed = 0
        var bytes = 0L
        val errors = ArrayList<String>()
        ZipFile(zip).use { zf ->
            zf.entries().asSequence().forEach { entry ->
                if (entry.isDirectory || !entry.name.startsWith(ATTACHMENTS_PREFIX)) return@forEach
                val parts = safeParts(entry.name)
                if (parts == null || parts.size < 2) {
                    failed++
                    if (errors.size < 12) errors += "非法附件路径：${entry.name}"
                    return@forEach
                }
                val relative = parts.drop(1).joinToString(File.separator)
                val target = File(attachmentsRoot, relative)
                if (target.exists()) {
                    skipped++
                    return@forEach
                }
                target.parentFile?.mkdirs()
                val tmp = File(target.parentFile, ".${target.name}.restoring")
                try {
                    zf.getInputStream(entry).use { input ->
                        tmp.outputStream().use { output -> copyLimited(input, output, MAX_ATTACHMENT_ENTRY) }
                    }
                    if (SHA256_NAME.matches(target.name) && sha256(tmp) != target.name) {
                        throw BackupFormatException("附件 ${target.name} 的内容与文件名不符")
                    }
                    if (!tmp.renameTo(target)) {
                        tmp.copyTo(target, overwrite = true)
                        tmp.delete()
                    }
                    restored++
                    bytes += target.length()
                } catch (e: Exception) {
                    failed++
                    if (errors.size < 12) errors += "附件 ${target.name}：${e.message ?: e.javaClass.simpleName}"
                    tmp.delete()
                }
            }
        }
        return RestoreReport(restored, skipped, failed, bytes, emptyList(), errors)
    }

    /** 读备份里的 settings.json（按类型的对象）；没有/坏了返回 null。 */
    fun readSettings(zip: File): JsonObject? {
        if (!zip.isFile) return null
        return runCatching {
            ZipFile(zip).use { zf ->
                val entry = zf.getEntry(SETTINGS_ENTRY) ?: return@runCatching null
                val text = zf.getInputStream(entry).use { it.readBytes().toString(Charsets.UTF_8) }
                Json.parseToJsonElement(text).jsonObject
            }
        }.getOrNull()
    }

    // ------------------------------------------------------------------ 工具

    private fun existingSessionIds(sessionsRoot: File): Set<String> {
        if (!sessionsRoot.isDirectory) return emptySet()
        val out = HashSet<String>()
        sessionsRoot.listFiles()
            ?.filter { it.isDirectory && it.name != ".trash" && !it.name.startsWith(".") }
            ?.forEach { workspace ->
                workspace.listFiles()
                    ?.filter { it.isDirectory && File(it, SESSION_FILE).isFile }
                    ?.forEach { out += it.name }
            }
        return out
    }

    private fun putText(zos: ZipOutputStream, name: String, text: String, time: Long) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        val entry = ZipEntry(name).apply { this.time = time }
        zos.putNextEntry(entry)
        zos.write(bytes)
        zos.closeEntry()
    }

    private fun putFile(zos: ZipOutputStream, name: String, file: File) {
        val entry = ZipEntry(name).apply { time = file.lastModified() }
        zos.putNextEntry(entry)
        file.inputStream().use { it.copyTo(zos) }
        zos.closeEntry()
    }

    /** zip entry 只接受规范相对路径：不允许 `..`、空段、反斜杠、绝对路径。 */
    private fun safeParts(name: String): List<String>? {
        if (name.isBlank() || name.startsWith("/") || name.contains('\\')) return null
        val parts = name.split('/')
        if (parts.any { it.isBlank() || it == "." || it == ".." }) return null
        return parts
    }

    private fun copyLimited(input: InputStream, output: OutputStream, limit: Long) {
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > limit) throw BackupFormatException("条目超过 ${limit / 1024 / 1024}MB 上限")
            output.write(buffer, 0, read)
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun relative(file: File, root: File): String =
        file.absoluteFile.toRelativeString(root.absoluteFile).replace('\\', '/')
}

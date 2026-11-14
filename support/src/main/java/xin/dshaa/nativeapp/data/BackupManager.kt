package xin.dshaa.nativeapp.data

import android.content.Context
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import xin.dshaa.core.backup.BackupManifest
import xin.dshaa.core.backup.NativeBackup
import xin.dshaa.core.backup.PluginRestoreReport
import xin.dshaa.core.backup.RestoreReport
import xin.dshaa.core.plugins.PluginRegistryFile
import xin.dshaa.core.session.SessionCwd
import xin.dshaa.core.session.SessionRepository
import xin.dshaa.core.session.WorkspaceSlug
import java.io.File
import java.io.OutputStream

/**
 * 原生备份 v1 的 App 侧（M35，docs/59）：把「哪些目录/哪些设置」翻译成 [NativeBackup] 的输入。
 *
 * - 导出：会话原文件 + 插件目录 + 附件 + 设置快照（**过滤 api_key / account_token**）；
 * - 恢复：会话（可续聊）/ 插件文件 + 清单记录 / 附件 / 设置；
 * - 语义：**只补不删**——同 id 会话跳过，已装插件跳过，已存在附件跳过，设置按备份值覆盖。
 */
class BackupManager(
    private val context: Context,
    /** 导出清单里记录的 app 版本；由宿主传入（本模块不依赖 app 的 BuildConfig）。 */
    private val appVersion: String = "",
) {

    private val prefs = context.getSharedPreferences("dsh", 0)

    /** 「上次备份」记录单独放一个 prefs：不参与设置快照（否则会把本机备份时间导进备份包）。 */
    private val backupPrefs = context.getSharedPreferences("backup_state", 0)

    val sessionsRoot = File(context.filesDir, "sessions")
    val pluginsRoot = File(context.filesDir, "plugins")
    val pluginsJson = File(context.filesDir, "plugins.json")
    val attachmentsRoot = File(context.filesDir, "attachments/v1")

    data class Stats(
        val sessions: Int = 0,
        val messages: Int = 0,
        val sessionBytes: Long = 0,
        val plugins: Int = 0,
        val attachmentBytes: Long = 0,
    )

    data class RestoreResult(
        val sessions: RestoreReport,
        val plugins: PluginRestoreReport,
        val attachments: RestoreReport,
        val pluginsAdded: List<String>,
        val settingsApplied: Int,
        /** 跨设备恢复：有多少条会话的 `header.cwd` 被映射到了当前工作区（M36，docs/60）。 */
        val cwdRemapped: Int = 0,
        val cwdErrors: List<String> = emptyList(),
    ) {
        val errors: List<String>
            get() = sessions.errors + plugins.errors + attachments.errors + cwdErrors
    }

    /** 一次导出的结果：manifest + 实际写出的 zip 字节数。 */
    data class ExportRecord(val manifest: BackupManifest, val zipBytes: Long)

    /** 「上次备份」摘要（备份页展示；只存本机，不进备份包）。 */
    data class LastExport(
        val at: Long,
        val zipBytes: Long,
        val sessions: Int,
        val plugins: Int,
        val attachments: Int,
    )

    fun stats(): Stats {
        val summaries = SessionRepository(sessionsRoot).list()
        var sessionBytes = 0L
        summaries.forEach { summary ->
            sessionBytes += runCatching { File(summary.path).length() }.getOrDefault(0L)
        }
        val attachmentsBytes = if (attachmentsRoot.isDirectory) {
            attachmentsRoot.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        } else {
            0L
        }
        return Stats(
            sessions = summaries.size,
            messages = summaries.sumOf { it.messageCount },
            sessionBytes = sessionBytes,
            plugins = PluginRegistryFile(pluginsJson).list().size,
            attachmentBytes = attachmentsBytes,
        )
    }

    fun exportTo(out: OutputStream, includeAttachments: Boolean = true): ExportRecord {
        val counting = CountingOutputStream(out)
        val manifest = NativeBackup.export(
            out = counting,
            sessionsRoot = sessionsRoot,
            settings = settingsSnapshot(),
            pluginsRoot = pluginsRoot,
            pluginRegistry = pluginsJson,
            attachmentsRoot = attachmentsRoot.takeIf { includeAttachments },
            appVersion = appVersion,
            sessionCwds = sessionCwds(),
        )
        val record = ExportRecord(manifest, counting.count)
        rememberExport(record)
        return record
    }

    fun exportTo(file: File, includeAttachments: Boolean = true): ExportRecord {
        file.parentFile?.mkdirs()
        return file.outputStream().use { exportTo(it, includeAttachments) }
    }

    /** 会话 id → header.cwd：写进 manifest 供恢复前提示「哪些会话要映射工作区」。 */
    private fun sessionCwds(): Map<String, String> =
        SessionRepository(sessionsRoot).list().associate { it.id to it.cwd }

    fun lastExport(): LastExport? {
        val at = backupPrefs.getLong(KEY_LAST_EXPORT_AT, 0L)
        if (at <= 0L) return null
        return LastExport(
            at = at,
            zipBytes = backupPrefs.getLong(KEY_LAST_EXPORT_BYTES, 0L),
            sessions = backupPrefs.getInt(KEY_LAST_EXPORT_SESSIONS, 0),
            plugins = backupPrefs.getInt(KEY_LAST_EXPORT_PLUGINS, 0),
            attachments = backupPrefs.getInt(KEY_LAST_EXPORT_ATTACHMENTS, 0),
        )
    }

    private fun rememberExport(record: ExportRecord) {
        backupPrefs.edit()
            .putLong(KEY_LAST_EXPORT_AT, record.manifest.createdAt)
            .putLong(KEY_LAST_EXPORT_BYTES, record.zipBytes)
            .putInt(KEY_LAST_EXPORT_SESSIONS, record.manifest.sessions.size)
            .putInt(KEY_LAST_EXPORT_PLUGINS, record.manifest.plugins.size)
            .putInt(KEY_LAST_EXPORT_ATTACHMENTS, record.manifest.attachmentsFiles)
            .apply()
    }

    fun inspect(file: File): BackupManifest = NativeBackup.inspect(file)

    fun readSettings(file: File): JsonObject? = NativeBackup.readSettings(file)

    /**
     * 恢复：会话 → 插件 → 附件 → 设置。
     * 设置放最后：如果中途会话恢复失败，也不该先把界面设置改掉。
     *
     * [mapMissingCwdTo] 非空时（M36，docs/60）：恢复出来的可写会话如果原 `header.cwd`
     * 在本机不存在/不可写，就把 header 改成这个当前工作区（并把目录挪到对应 workspace slug）。
     * 只读的旧版导入会话不映射，保留历史工作区标签。
     *
     * [onlySessionIds] 非空时（M39）：只恢复这些会话；插件/附件/设置照常恢复。
     */
    fun restore(
        file: File,
        restoreSessions: Boolean = true,
        restorePlugins: Boolean = true,
        restoreAttachments: Boolean = true,
        restoreSettings: Boolean = true,
        mapMissingCwdTo: String? = null,
        onlySessionIds: Set<String>? = null,
    ): RestoreResult {
        val sessionReport = if (restoreSessions) {
            NativeBackup.restoreSessions(file, sessionsRoot, onlySessionIds)
        } else {
            RestoreReport()
        }

        val cwdRemap = if (restoreSessions && !mapMissingCwdTo.isNullOrBlank() && sessionReport.restored > 0) {
            remapRestoredCwds(sessionReport, mapMissingCwdTo)
        } else {
            RemapSummary()
        }

        val pluginReport = if (restorePlugins) {
            NativeBackup.restorePlugins(file, pluginsRoot)
        } else {
            PluginRestoreReport()
        }
        val added = pluginReport.registryJson?.let { json ->
            PluginRegistryFile(pluginsJson).mergeFromRegistryJson(json) { id ->
                File(pluginsRoot, id).absolutePath
            }
        }.orEmpty()

        val attachmentReport = if (restoreAttachments) {
            NativeBackup.restoreAttachments(file, attachmentsRoot)
        } else {
            RestoreReport()
        }

        val settingsApplied = if (restoreSettings) applySettings(file) else 0

        return RestoreResult(
            sessions = sessionReport,
            plugins = pluginReport,
            attachments = attachmentReport,
            pluginsAdded = added,
            settingsApplied = settingsApplied,
            cwdRemapped = cwdRemap.count,
            cwdErrors = cwdRemap.errors,
        )
    }

    private data class RemapSummary(val count: Int = 0, val errors: List<String> = emptyList())

    /**
     * 把刚恢复出来的会话映射到当前工作区。只处理本次真正落盘的 id；旧的同 id 会话已跳过，不碰。
     * 映射是逐条保守的：读不了/写不了就记录错误并保持原文件，不阻断其它会话。
     */
    private fun remapRestoredCwds(report: RestoreReport, targetCwd: String): RemapSummary {
        val target = File(targetCwd)
        if (!target.isDirectory && !target.mkdirs()) {
            return RemapSummary(errors = listOf("当前工作区不存在也建不了，未映射会话工作区：$targetCwd"))
        }
        if (!target.canWrite()) {
            return RemapSummary(errors = listOf("当前工作区不可写，未映射会话工作区：$targetCwd"))
        }

        val repo = SessionRepository(sessionsRoot)
        val errors = ArrayList<String>()
        var count = 0
        val usable = { path: String ->
            val dir = File(path)
            dir.isDirectory && dir.canWrite()
        }
        report.ids.forEach { id ->
            val sessionFile = repo.findFile(id) ?: return@forEach
            val dir = sessionFile.parentFile ?: return@forEach
            // 旧版只读导入会话：保留原来的历史工作区，不参与映射（docs/60）
            if (File(dir, "imported.json").isFile) return@forEach

            val result = SessionCwd.remapIfNeeded(sessionFile, targetCwd, usable)
            if (result.error != null) {
                errors += "会话 $id 工作区：${result.error}"
                return@forEach
            }
            if (!result.changed) return@forEach
            count++
            moveSessionDir(dir, targetCwd)?.let { errors += it }
        }
        return RemapSummary(count, errors)
    }

    /** 会话目录跟着 header.cwd 走：`sessions/<slug>/<id>`；移动失败不影响打开（只是目录名旧）。 */
    private fun moveSessionDir(dir: File, targetCwd: String): String? {
        val newParent = File(sessionsRoot, WorkspaceSlug.of(targetCwd))
        if (dir.parentFile?.name == newParent.name) return null
        if (!newParent.isDirectory && !newParent.mkdirs()) {
            return "会话 ${dir.name} 的目录没能移动：建不了 ${newParent.name}"
        }
        val dest = File(newParent, dir.name)
        if (dest.exists()) {
            return "会话 ${dir.name} 的目录没能移动：目标已存在"
        }
        if (dir.renameTo(dest)) return null
        return try {
            dest.mkdirs()
            dir.listFiles()?.forEach { file -> file.copyTo(File(dest, file.name), overwrite = true) }
            if (dir.deleteRecursively()) {
                null
            } else {
                dest.deleteRecursively()
                "会话 ${dir.name} 的目录没能移动"
            }
        } catch (e: Exception) {
            dest.deleteRecursively()
            "会话 ${dir.name} 的目录没能移动：${e.message ?: "IO 错误"}"
        }
    }

    // ------------------------------------------------------------------ 设置快照

    /**
     * 导出时按 SharedPreferences 的类型分组，恢复时才能按原类型写回（Int 不能写成 Long）。
     * 密钥/令牌与调试键永不导出。
     */
    private fun settingsSnapshot(): JsonObject {
        val strings = LinkedHashMap<String, String>()
        val booleans = LinkedHashMap<String, Boolean>()
        val ints = LinkedHashMap<String, Int>()
        val longs = LinkedHashMap<String, Long>()
        val floats = LinkedHashMap<String, Float>()
        val sets = LinkedHashMap<String, Set<String>>()

        prefs.all.forEach { (key, value) ->
            if (!exportable(key)) return@forEach
            when (value) {
                is String -> strings[key] = value
                is Boolean -> booleans[key] = value
                is Int -> ints[key] = value
                is Long -> longs[key] = value
                is Float -> floats[key] = value
                is Set<*> -> sets[key] = value.mapNotNull { it as? String }.toSet()
            }
        }

        return buildJsonObject {
            put("strings", buildJsonObject { strings.forEach { (k, v) -> put(k, v) } })
            put("booleans", buildJsonObject { booleans.forEach { (k, v) -> put(k, v) } })
            put("ints", buildJsonObject { ints.forEach { (k, v) -> put(k, v) } })
            put("longs", buildJsonObject { longs.forEach { (k, v) -> put(k, v) } })
            put("floats", buildJsonObject { floats.forEach { (k, v) -> put(k, v.toDouble()) } })
            put(
                "stringSets",
                buildJsonObject {
                    sets.forEach { (k, values) ->
                        put(k, buildJsonArray { values.forEach { add(JsonPrimitive(it)) } })
                    }
                },
            )
        }
    }

    private fun applySettings(file: File): Int {
        val sections = readSettings(file) ?: return 0
        val edit = prefs.edit()
        var applied = 0

        sections["strings"]?.let { obj ->
            obj.asObject()?.forEach { (key, value) ->
                if (exportable(key) && value is JsonPrimitive && value.isString) {
                    edit.putString(key, value.content)
                    applied++
                }
            }
        }
        sections["booleans"]?.let { obj ->
            obj.asObject()?.forEach { (key, value) ->
                val boolean = (value as? JsonPrimitive)?.booleanOrNull
                if (exportable(key) && boolean != null) {
                    edit.putBoolean(key, boolean)
                    applied++
                }
            }
        }
        sections["ints"]?.let { obj ->
            obj.asObject()?.forEach { (key, value) ->
                val int = (value as? JsonPrimitive)?.intOrNull
                if (exportable(key) && int != null) {
                    edit.putInt(key, int)
                    applied++
                }
            }
        }
        sections["longs"]?.let { obj ->
            obj.asObject()?.forEach { (key, value) ->
                val long = (value as? JsonPrimitive)?.longOrNull
                if (exportable(key) && long != null) {
                    edit.putLong(key, long)
                    applied++
                }
            }
        }
        sections["floats"]?.let { obj ->
            obj.asObject()?.forEach { (key, value) ->
                val float = (value as? JsonPrimitive)?.floatOrNull
                    ?: (value as? JsonPrimitive)?.doubleOrNull?.toFloat()
                if (exportable(key) && float != null) {
                    edit.putFloat(key, float)
                    applied++
                }
            }
        }
        sections["stringSets"]?.let { obj ->
            obj.asObject()?.forEach { (key, value) ->
                val array = value as? JsonArray ?: return@forEach
                val values = array.mapNotNull { it.jsonPrimitive.contentOrNull }.toSet()
                if (exportable(key)) {
                    edit.putStringSet(key, values)
                    applied++
                }
            }
        }
        if (applied > 0) edit.apply()
        return applied
    }

    private fun kotlinx.serialization.json.JsonElement.asObject(): JsonObject? =
        runCatching { this as JsonObject }.getOrNull()

    private fun exportable(key: String): Boolean =
        key !in SECRET_KEYS && !key.startsWith("debug_")

    private companion object {
        /** 隐私红线：密钥/账号令牌永远不落备份包，恢复后让用户重新登录/填 key。 */
        val SECRET_KEYS = setOf("api_key", "account_token")

        const val KEY_LAST_EXPORT_AT = "last_export_at"
        const val KEY_LAST_EXPORT_BYTES = "last_export_zip_bytes"
        const val KEY_LAST_EXPORT_SESSIONS = "last_export_sessions"
        const val KEY_LAST_EXPORT_PLUGINS = "last_export_plugins"
        const val KEY_LAST_EXPORT_ATTACHMENTS = "last_export_attachments"
    }
}

/** 数 zip 实际写出多少字节（SAF 的 OutputStream 没有 length）。 */
private class CountingOutputStream(private val delegate: OutputStream) : OutputStream() {
    var count: Long = 0L
        private set

    override fun write(b: Int) {
        delegate.write(b)
        count++
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        delegate.write(b, off, len)
        count += len
    }

    override fun flush() = delegate.flush()

    override fun close() = delegate.close()
}

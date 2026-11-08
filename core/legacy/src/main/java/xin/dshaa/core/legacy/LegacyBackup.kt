package xin.dshaa.core.legacy

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import xin.dshaa.core.model.MessageRole
import xin.dshaa.core.session.WorkspaceSlug
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipInputStream

/** 预览行（导入前台展示「旧会话里有什么」）。 */
data class LegacyPreviewLine(val role: String, val text: String)

/** 备份包里的一条旧会话。 */
data class LegacyBackupEntry(
    val entryName: String,
    val formatVersion: Int,
    val header: LegacyHeader,
    val title: String,
    val messageCount: Int,
    val preview: List<LegacyPreviewLine>,
    val provider: String?,
    val model: String?,
    val plainBytes: Int,
    val zipBytes: Long,
    val sha256: String,
    val alreadyImported: Boolean = false,
    val warnings: List<String> = emptyList(),
)

data class LegacyScanResult(
    val sourceName: String,
    val entries: List<LegacyBackupEntry>,
    val warnings: List<String> = emptyList(),
)

sealed class LegacyImportOutcome {
    data class Imported(val id: String, val path: String, val messageCount: Int) : LegacyImportOutcome()
    data class Skipped(val reason: String) : LegacyImportOutcome()
    data class Failed(val error: String) : LegacyImportOutcome()
}

/**
 * 旧版备份包（`DSH备份-*.zip`）的扫描与只读导入。
 *
 * 备份包结构（实测 2026-09-24 包，176 entries）：
 * ```
 * manifest.json
 * dshhome/sessions/<projectKey>/session-<uuid>/session.jsonl.zstd       # v0
 * dshhome/sessions/<projectKey>/session-<uuid>/session.v3.jsonl.zstd   # v3
 * dshhome/settings.yaml / storages/…                                    # 其他数据（本轮不导入）
 * ```
 * 只读含义：**绝不写回备份包、绝不删除任何旧文件**；导入只是在 App 自己的 sessions 根下生成副本。
 */
object LegacyBackup {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true; explicitNulls = false }
    private val sessionName = Regex("""session(\.[a-z0-9]+)*\.jsonl(\.zstd)?""")

    const val MAX_ENTRY_BYTES = 64L * 1024 * 1024

    fun isSessionLogName(name: String): Boolean {
        val base = name.substringAfterLast('/')
        return sessionName.matches(base)
    }

    // ---------------- 扫描 ----------------

    fun scan(
        input: InputStream,
        sourceName: String,
        importedKeys: Set<String> = emptySet(),
    ): LegacyScanResult {
        val warnings = ArrayList<String>()
        val groups = LinkedHashMap<String, MutableList<LegacyBackupEntry>>()
        var entryCount = 0

        ZipInputStream(BufferedInputStream(input)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val name = entry.name
                if (!entry.isDirectory && isSessionLogName(name)) {
                    entryCount++
                    if (entry.size > MAX_ENTRY_BYTES) {
                        warnings += "跳过 ${name.substringAfterLast('/')}：条目超过 64MB"
                    } else {
                        val bytes = readAtMost(zis, MAX_ENTRY_BYTES)
                        if (bytes == null) {
                            warnings += "跳过 ${name.substringAfterLast('/')}：条目过大"
                        } else {
                            val parsed = parseEntry(name, bytes, entry.size, importedKeys)
                            if (parsed != null) {
                                groups.getOrPut(name.substringBeforeLast('/')) { mutableListOf() } += parsed
                            } else {
                                warnings += "跳过 ${name.substringAfterLast('/')}：不是可识别的会话日志"
                            }
                        }
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }

        val entries = ArrayList<LegacyBackupEntry>()
        groups.forEach { (dir, candidates) ->
            val best = candidates.maxByOrNull { it.formatVersion } ?: return@forEach
            if (candidates.size > 1) {
                val versions = candidates.map { "v${it.formatVersion}" }.distinct().sorted()
                warnings += "${dir.substringAfterLast('/')} 有 ${candidates.size} 份日志（${versions.joinToString("/")}），取 v${best.formatVersion}"
            }
            entries += best
        }
        entries.sortWith(compareByDescending<LegacyBackupEntry> { it.header.createdAt })
        if (entryCount == 0) warnings += "这个 zip 里没有找到会话日志（应包含 dshhome/sessions/**/session*.jsonl.zstd）"
        return LegacyScanResult(sourceName, entries, warnings)
    }

    private fun parseEntry(
        name: String,
        bytes: ByteArray,
        zipBytes: Long,
        importedKeys: Set<String>,
    ): LegacyBackupEntry? = runCatching {
        val log = LegacyLogReader.decode(bytes)
        val conversation = LegacyProjection.conversation(log)
        val preview = conversation.asSequence()
            .filter { it.role == MessageRole.USER || it.role == MessageRole.ASSISTANT }
            .mapNotNull { m ->
                val text = LegacyProjection.previewText(m, 200)
                if (text.isBlank()) null else LegacyPreviewLine(m.role.name.lowercase(), text)
            }
            .take(4)
            .toList()
        val model = LegacyProjection.modelInfo(log)
        val sha = sha256(bytes)
        LegacyBackupEntry(
            entryName = name,
            formatVersion = log.header.version,
            header = log.header,
            title = LegacyProjection.title(log)
                ?: conversation.firstOrNull { it.role == MessageRole.USER && !it.text.isBlank() }?.text?.take(40)?.takeIf { it.isNotBlank() }
                ?: "未命名会话",
            messageCount = conversation.size,
            preview = preview,
            provider = model.provider,
            model = model.model,
            plainBytes = log.plainBytes,
            zipBytes = if (zipBytes > 0) zipBytes else bytes.size.toLong(),
            sha256 = sha,
            alreadyImported = sha in importedKeys,
        )
    }.getOrNull()

    // ---------------- 导入 ----------------

    /**
     * 把一条旧会话导入 `<sessionsRoot>`。
     *
     * - v4 源：原字节复制（保留原 id；同 id 已存在则跳过）
     * - v0–v3 源：投影成 v4 只读副本（新 id）
     * - 任何情况都写 `imported.json` 侧车（来源、sha256、只读标记）
     */
    fun import(
        sessionsRoot: File,
        openSource: () -> InputStream,
        entry: LegacyBackupEntry,
        sourceName: String,
    ): LegacyImportOutcome {
        val bytes = readEntry(openSource(), entry.entryName)
            ?: return LegacyImportOutcome.Failed("备份包里找不到条目：${entry.entryName}")
        if (sha256(bytes) != entry.sha256) {
            return LegacyImportOutcome.Failed("备份在预览之后发生变化，请重新选择备份包")
        }
        val workspace = sessionsRoot.resolve(WorkspaceSlug.of(entry.header.cwd))

        return runCatching {
            if (entry.formatVersion >= 4) {
                val id = entry.header.id
                val dir = File(workspace, id)
                val file = File(dir, "session.v4.jsonl.zstd")
                if (file.isFile) {
                    return@runCatching LegacyImportOutcome.Skipped("已导入过")
                }
                dir.mkdirs()
                file.writeBytes(bytes)
                writeSidecar(dir, entry, sourceName, entry.messageCount)
                LegacyImportOutcome.Imported(id, dir.absolutePath, entry.messageCount)
            } else {
                val log = LegacyLogReader.decode(bytes)
                val id = UUID.randomUUID().toString()
                val dir = File(workspace, id)
                dir.mkdirs()
                val built = LegacyV4.build(log, id, entry.title)
                LegacyV4.write(File(dir, "session.v4.jsonl.zstd"), built)
                writeSidecar(dir, entry, sourceName, entry.messageCount)
                LegacyImportOutcome.Imported(id, dir.absolutePath, entry.messageCount)
            }
        }.getOrElse { err ->
            LegacyImportOutcome.Failed(err.message ?: err.javaClass.simpleName)
        }
    }

    private fun writeSidecar(dir: File, entry: LegacyBackupEntry, sourceName: String, messageCount: Int) {
        val sidecar = buildJsonObject {
            put("type", "import.legacy")
            put("version", 1)
            put("sourceName", sourceName)
            put("entryName", entry.entryName)
            put("formatVersion", entry.formatVersion)
            put("importedAt", System.currentTimeMillis())
            put("readOnly", true)
            put("sourceSha256", entry.sha256)
            put("messageCount", messageCount)
            put("originalBytes", entry.zipBytes)
        }
        File(dir, "imported.json").writeText(sidecar.toString(), Charsets.UTF_8)
    }

    /** 已导入的来源 sha 集合（扫描时标「已导入」用）。 */
    fun importedKeys(sessionsRoot: File): Set<String> {
        val out = HashSet<String>()
        sessionsRoot.listFiles()?.filter { it.isDirectory }?.forEach { ws ->
            ws.listFiles()?.filter { it.isDirectory }?.forEach { dir ->
                val sidecar = File(dir, "imported.json")
                if (sidecar.isFile) {
                    runCatching {
                        val obj = json.parseToJsonElement(sidecar.readText()).jsonObject
                        obj["sourceSha256"]?.jsonPrimitive?.contentOrNull?.let { out += it }
                    }
                }
            }
        }
        return out
    }

    // ---------------- 工具 ----------------

    fun sha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun readEntry(input: InputStream, name: String): ByteArray? {
        ZipInputStream(BufferedInputStream(input)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (entry.name == name && !entry.isDirectory) return readAtMost(zis, MAX_ENTRY_BYTES)
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
        return null
    }

    private fun readAtMost(input: InputStream, limit: Long): ByteArray? {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            total += n
            if (total > limit) return null
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }
}

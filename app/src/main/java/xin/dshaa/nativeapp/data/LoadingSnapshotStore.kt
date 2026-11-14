package xin.dshaa.nativeapp.data

import kotlinx.serialization.json.*
import xin.dshaa.core.plugins.LoadingSpec
import xin.dshaa.core.plugins.PluginLoading
import xin.dshaa.core.plugins.PluginPermissions
import xin.dshaa.core.plugins.PluginRecord
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID

/** Built-in implementation of the same data contract; the renderer contains no product loading UI. */
object BuiltinLoadingPlugin {
    const val ID = "builtin.loading.native"
    val declaration: PluginLoading = LoadingSpec.parse(Json.parseToJsonElement("""
        {"id":"native","label":"鲸歌加载页","tree":{"type":"column","padding":32,"gap":16,"align":"center","children":[
          {"type":"icon","name":"whale","size":48,"color":"brand"},
          {"type":"text","text":"鲸歌","size":28,"weight":"bold"},
          {"type":"progress"},
          {"type":"text","bind":"boot.message","color":"sub"},
          {"type":"text","bind":"boot.elapsed","size":12,"color":"faint"}
        ]}}
    """.trimIndent())).loading ?: error("内置加载页声明不合法")
}

data class LoadingSnapshot(
    val selection: String,
    val ownerPlugin: String,
    val ownerVersion: String,
    val ownerInstalledAt: Long,
    val revision: String,
    val loading: PluginLoading,
)

data class LoadingSnapshotRefresh(val snapshot: LoadingSnapshot, val unavailableReason: String? = null)

/**
 * One bounded, atomic, private file. read() does not read plugin registry/files or execute JS.
 * Host calls refresh after selection and EVERY record/permission/version change, before the next boot.
 * A persisted snapshot is last validated state, not proof about edits made outside host mutation APIs.
 */
class LoadingSnapshotStore(private val file: File) {
    companion object {
        const val MAX_SNAPSHOT_BYTES = LoadingSpec.MAX_BYTES + 8 * 1024
        fun defaultSnapshot(): LoadingSnapshot = snapshotOf(
            BuiltinLoadingPlugin.ID, BuiltinLoadingPlugin.ID, "1", 0L, "builtin", BuiltinLoadingPlugin.declaration,
        )

        private fun snapshotOf(selection: String, owner: String, version: String, installedAt: Long, grants: String, loading: PluginLoading): LoadingSnapshot {
            val fingerprint = listOf(selection, owner, version, installedAt.toString(), grants, loading.id, loading.label, loading.treeJson).joinToString("\u0000")
            val revision = MessageDigest.getInstance("SHA-256").digest(fingerprint.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
            return LoadingSnapshot(selection, owner, version, installedAt, revision, loading)
        }
    }

    /** null means missing/corrupt/oversized/wrong selection; callers immediately use defaultSnapshot(). */
    @Synchronized
    fun read(selection: String? = null): LoadingSnapshot? = runCatching {
        if (!file.isFile || file.length() > MAX_SNAPSHOT_BYTES || Files.isSymbolicLink(file.toPath())) return null
        val obj = Json.parseToJsonElement(file.readText(Charsets.UTF_8)).jsonObject
        require(obj["protocol"]?.jsonPrimitive?.content == LoadingSpec.VERSION)
        val selected = obj.getValue("selection").jsonPrimitive.content
        if (selection != null && normalizeSelection(selection) != selected) return null
        val loading = LoadingSpec.parse(obj.getValue("loading")).loading ?: return null
        val owner = obj.getValue("ownerPlugin").jsonPrimitive.content
        val version = obj.getValue("ownerVersion").jsonPrimitive.content
        val installedAt = obj.getValue("ownerInstalledAt").jsonPrimitive.long
        val revision = obj.getValue("revision").jsonPrimitive.content
        val grantStamp = obj.getValue("grantStamp").jsonPrimitive.content
        val expected = snapshotOf(selected, owner, version, installedAt, grantStamp, loading)
        require(revision == expected.revision)
        expected
    }.getOrNull()

    /** Selection is builtin.loading.native, pluginId, or pluginId#loadingId; no implicit shell selection. */
    @Synchronized
    fun refresh(records: List<PluginRecord>, selection: String?): LoadingSnapshotRefresh {
        val selected = normalizeSelection(selection)
        if (selected == BuiltinLoadingPlugin.ID) return persist(defaultSnapshot(), "builtin")
        val owner = selected.substringBefore('#')
        val id = selected.substringAfter('#', "")
        val record = records.firstOrNull { it.id == owner }
        val reason = when {
            record == null -> "加载页插件未安装：$owner"
            !record.enabled -> "加载页插件已停用：$owner"
            PluginPermissions.UI !in record.permissions || PluginPermissions.UI !in record.grantedPermissions -> "加载页插件缺少 ui 授权：$owner"
            record.error != null || record.configError != null -> "加载页插件登记失败：$owner"
            else -> null
        }
        if (reason != null) return persist(defaultSnapshot(), "builtin", reason)
        val active = requireNotNull(record)
        val available = active.loadings.mapNotNull { value ->
            runCatching { LoadingSpec.parse(LoadingSpec.declaration(value)).loading }.getOrNull()
        }
        val loading = if (id.isEmpty()) available.firstOrNull() else available.firstOrNull { it.id == id }
        if (loading == null) return persist(defaultSnapshot(), "builtin", "加载页声明不存在：$selected")
        val grantStamp = active.grantedPermissions.sorted().joinToString(",")
        val snapshot = snapshotOf(selected, active.id, active.version, active.installedAt, grantStamp, loading)
        return persist(snapshot, grantStamp)
    }

    private fun normalizeSelection(value: String?): String = value?.trim().orEmpty().ifBlank { BuiltinLoadingPlugin.ID }

    private fun persist(snapshot: LoadingSnapshot, grantStamp: String, reason: String? = null): LoadingSnapshotRefresh {
        if (read(snapshot.selection) == snapshot) return LoadingSnapshotRefresh(snapshot, reason)
        val obj = buildJsonObject {
            put("protocol", LoadingSpec.VERSION)
            put("selection", snapshot.selection)
            put("ownerPlugin", snapshot.ownerPlugin)
            put("ownerVersion", snapshot.ownerVersion)
            put("ownerInstalledAt", snapshot.ownerInstalledAt)
            put("revision", snapshot.revision)
            put("grantStamp", grantStamp)
            put("loading", LoadingSpec.declaration(snapshot.loading))
        }
        val bytes = obj.toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_SNAPSHOT_BYTES) { "加载页快照过大" }
        val target = file.absoluteFile
        val parent = requireNotNull(target.parentFile)
        check(parent.isDirectory || parent.mkdirs()) { "建不了加载页快照目录" }
        require(!Files.isSymbolicLink(target.toPath()) && target.canonicalFile.parentFile == parent.canonicalFile) { "加载页快照路径异常" }
        val staging = File(parent, ".${target.name}.${UUID.randomUUID()}.tmp")
        try {
            staging.outputStream().use { out -> out.write(bytes); out.fd.sync() }
            // API 26+, same-directory move. Failure leaves old complete snapshot intact.
            Files.move(staging.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            if (staging.exists()) check(staging.delete()) { "加载页暂存文件清理失败" }
        }
        return LoadingSnapshotRefresh(snapshot, reason)
    }
}

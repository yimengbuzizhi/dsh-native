package xin.dshaa.nativeapp.data

import android.content.SharedPreferences
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.util.UUID

/**
 * 一个「工作区」= 会话 header.cwd = 工具的可写根目录。
 *
 * 上游 DSH 的工作区就是 cwd（`<sessions>/<workspace-slug>/<id>/…`），手机版照这个语义做：
 * - [managed] 本地创建的目录（`files/workspace` 或 `files/workspaces/ws-*`），可重命名/删除；
 * - 非 managed = 从已有会话的 cwd 推导出来的「历史位置」（旧版导入的会话、别的工作区留下的会话），
 *   只用于分组浏览，不能在设置里改名/删除。
 */
data class WorkspaceEntry(
    val id: String,
    val name: String,
    val path: String,
    val managed: Boolean = true,
)

/**
 * 工作区清单的持久化：`files/workspaces.json`（清单）+ `dsh` SharedPreferences（当前选择）。
 *
 * 为什么不用 Room/DataStore：这是一张最多十几行的表，读写都在主线程外的冷路径；
 * 和 SessionRepository 的 JSON 风格保持一致，坏文件按「只有默认工作区」降级，不阻塞启动。
 */
class WorkspaceStore(
    private val file: File,
    private val prefs: SharedPreferences,
    private val home: File,
) {
    val defaultPath: String = File(home, "workspace").absolutePath

    private val entries: MutableList<WorkspaceEntry> = load().toMutableList()

    init {
        File(defaultPath).mkdirs()
        // 默认工作区永远在清单里（名字可改，路径不可改）
        if (entries.none { it.id == DEFAULT_ID }) {
            entries.add(0, WorkspaceEntry(DEFAULT_ID, "默认工作区", defaultPath))
            save()
        }
    }

    fun managed(): List<WorkspaceEntry> = entries.toList()

    fun find(id: String): WorkspaceEntry? = entries.firstOrNull { it.id == id }

    /** 当前工作区路径：优先上次选择；指向已不存在的本地目录时回落到默认（并纠正偏好）。 */
    fun currentPath(): String {
        val saved = prefs.getString(KEY_CURRENT_PATH, null)
        if (saved.isNullOrBlank()) return defaultPath
        if (saved == defaultPath) return saved
        val dir = File(saved)
        // 历史路径（别的 App 的私有目录）本来就可能不存在：仍然允许停留在那里回看会话。
        val known = entries.any { it.path == saved }
        if (!known && !dir.exists() && saved.startsWith(home.absolutePath)) {
            prefs.edit().putString(KEY_CURRENT_PATH, defaultPath).apply()
            return defaultPath
        }
        return saved
    }

    fun setCurrentPath(path: String) {
        prefs.edit().putString(KEY_CURRENT_PATH, path).apply()
    }

    /**
     * 新建工作区。
     *
     * [path] 为空 → 建在 App 私有目录 `files/workspaces/ws-<short>`（用不透明 id 而不是名字，
     * 改名不用搬目录，中文/空格/emoji 都不会影响路径）。
     * [path] 非空 → 用用户选定的**任意目录**（可以是 /sdcard 下的已有文件夹，也可以是不存在的路径，
     * 这里负责 mkdirs）；已存在且可写的目录直接复用，不搬不删。
     */
    fun create(name: String, path: String? = null): Result<WorkspaceEntry> {
        val id = "ws-" + UUID.randomUUID().toString().replace("-", "").take(8)
        val dir = if (path.isNullOrBlank()) File(File(home, "workspaces"), id) else File(path.trim())
        if (dir.exists() && !dir.isDirectory) return Result.failure(IllegalStateException("这个路径是个文件，不是目录"))
        if (!dir.isDirectory && !dir.mkdirs() && !dir.isDirectory) {
            return Result.failure(IllegalStateException("建不了目录（没有写权限？）：${dir.absolutePath}"))
        }
        if (!dir.canWrite()) {
            return Result.failure(IllegalStateException("目录不可写：${dir.absolutePath}"))
        }
        entries.firstOrNull { it.path == dir.absolutePath }?.let { return Result.success(it) }
        val entry = WorkspaceEntry(
            id = id,
            name = name.trim().ifBlank { dir.name.ifBlank { "未命名工作区" } }.take(40),
            path = dir.absolutePath,
        )
        entries.add(entry)
        save()
        return Result.success(entry)
    }

    fun rename(id: String, name: String): Boolean {
        val index = entries.indexOfFirst { it.id == id }
        if (index < 0) return false
        entries[index] = entries[index].copy(name = name.trim().ifBlank { entries[index].name }.take(40))
        save()
        return true
    }

    /** 从清单移除（文件由调用方决定怎么处理：App 层会把目录和会话一起移到回收站）。 */
    fun remove(id: String): WorkspaceEntry? {
        if (id == DEFAULT_ID) return null
        val index = entries.indexOfFirst { it.id == id }
        if (index < 0) return null
        val removed = entries.removeAt(index)
        save()
        return removed
    }

    private fun load(): List<WorkspaceEntry> {
        if (!file.isFile) return emptyList()
        return runCatching {
            val root = Json.parseToJsonElement(file.readText(Charsets.UTF_8)).jsonObject
            root["workspaces"]?.jsonArray?.mapNotNull { element ->
                val obj = element.jsonObject
                val id = obj["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val path = obj["path"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val stored = obj["name"]?.jsonPrimitive?.contentOrNull
                // 旧版本把 /storage/emulated/0 自动命名成 "0"：读的时候顺手纠正（写回在 save 时）
                val name = when {
                    stored.isNullOrBlank() -> File(path).name.ifBlank { path }
                    path.trimEnd('/') == "/storage/emulated/0" && stored == "0" -> "手机存储"
                    else -> stored
                }
                WorkspaceEntry(id, name, path)
            }.orEmpty()
        }.getOrDefault(emptyList())
    }

    private fun save() {
        runCatching {
            file.parentFile?.mkdirs()
            val root = buildJsonObject {
                put(
                    "workspaces",
                    buildJsonArray {
                        entries.forEach { entry ->
                            add(
                                buildJsonObject {
                                    put("id", entry.id)
                                    put("name", entry.name)
                                    put("path", entry.path)
                                },
                            )
                        }
                    },
                )
            }
            val tmp = File(file.parentFile, "${file.name}.tmp")
            tmp.writeText(root.toString(), Charsets.UTF_8)
            if (!tmp.renameTo(file)) {
                file.writeText(root.toString(), Charsets.UTF_8)
                tmp.delete()
            }
        }
    }

    companion object {
        const val DEFAULT_ID = "default"
        private const val KEY_CURRENT_PATH = "workspace_path"

        /** 旧版/其他 App 的 cwd → 列表里的显示名（默认工作区叫「默认工作区」，其余取末级目录）。 */
        fun displayName(path: String, defaultPath: String): String = when {
            path == defaultPath -> "默认工作区"
            path.isBlank() -> "默认工作区"
            // /storage/emulated/0 的 File.name 是 "0"（内部分区号），对用户没意义
            path.trimEnd('/') == "/storage/emulated/0" -> "手机存储"
            else -> File(path).name.ifBlank { path }
        }
    }
}

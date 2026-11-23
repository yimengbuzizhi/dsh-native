package xin.dshaa.nativeapp.chat

import xin.dshaa.core.plugins.PluginPreset
import xin.dshaa.core.plugins.PresetCodec
import java.io.File

/**
 * P3-d（`docs/187` §9.2）：预设的落盘（`files/node/data/presets.json`）。
 *
 * **为什么放这里**（而不是 prefs / files 根）：
 * 1. 升级只换 `files/node/runtime/<version>/`，`files/node/data/` 全程保留 —— 预设不会因为
 *    运行时升级丢（和 P2「data 与 runtime 分离」同一条纪律）；
 * 2. 预设是纯数据（模型 + 提示词段 + 工具 + 权限的组合），跟 Node 插件数据同目录便于一起备份/排查；
 * 3. **不进 session.v4**：会话格式一个字节都不动，删掉 `presets.json` 只丢「套用选择」。
 *
 * 写入用「临时文件 + rename」：中途被杀不会留半截 JSON（宁可丢最后一次保存，也不留坏文件）。
 * 解码容错由 [PresetCodec] 负责（坏条目跳过并带 errors，不静默）。
 */
class PresetStore(filesDir: File) {

    val dir: File = File(File(filesDir, "node"), "data")
    val file: File = File(dir, PresetCodec.FILE_NAME)

    private var cached: PresetCodec.Decoded = PresetCodec.Decoded()

    init {
        reload()
    }

    @Synchronized
    fun reload(): PresetCodec.Decoded {
        val text = runCatching { file.takeIf { it.isFile }?.readText(Charsets.UTF_8) }.getOrNull()
        cached = PresetCodec.decode(text)
        return cached
    }

    @Synchronized
    fun presets(): List<PluginPreset> = cached.presets

    @Synchronized
    fun activeId(): String? = cached.activeId

    @Synchronized
    fun errors(): List<String> = cached.errors

    /** 新建/覆盖（同名 = 同 slug = 覆盖，保留 createdAt）。返回落盘后的预设。 */
    @Synchronized
    fun save(preset: PluginPreset): Result<PluginPreset> {
        val id = preset.id.ifBlank { PresetCodec.slug(preset.name).orEmpty() }
        if (id.isEmpty()) return Result.failure(IllegalArgumentException("预设名不能为空（要能派生出文件 id）"))
        val existing = cached.presets.firstOrNull { it.id == id }
        val now = System.currentTimeMillis()
        val merged = preset.copy(
            id = id,
            name = preset.name.ifBlank { id },
            createdAt = existing?.createdAt?.takeIf { it > 0 } ?: preset.createdAt.takeIf { it > 0 } ?: now,
            updatedAt = now,
            permissions = PresetCodec.normalizePermissions(preset.permissions),
        )
        val next = (cached.presets.filterNot { it.id == id } + merged).sortedBy { it.name.lowercase() }
        write(next, cached.activeId)
        return Result.success(merged)
    }

    @Synchronized
    fun delete(id: String): Boolean {
        val next = cached.presets.filterNot { it.id == id }
        if (next.size == cached.presets.size) return false
        write(next, cached.activeId?.takeIf { it != id })
        return true
    }

    /** 记录「当前套用的是哪个预设」；null = 清除套用。 */
    @Synchronized
    fun setActive(id: String?) {
        if (cached.activeId == id) return
        write(cached.presets, id)
    }

    private fun write(presets: List<PluginPreset>, active: String?) {
        runCatching {
            dir.mkdirs()
            val temp = File(dir, PresetCodec.FILE_NAME + ".tmp")
            temp.writeText(PresetCodec.encode(presets, active), Charsets.UTF_8)
            if (!temp.renameTo(file)) {
                // rename 失败（跨文件系统/被占用）就退回直写；至少保证内容是最新的。
                file.writeText(PresetCodec.encode(presets, active), Charsets.UTF_8)
                temp.delete()
            }
        }
        cached = PresetCodec.Decoded(presets = presets, activeId = active, errors = cached.errors)
    }
}

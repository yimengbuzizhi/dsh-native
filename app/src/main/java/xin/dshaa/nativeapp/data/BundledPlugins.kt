package xin.dshaa.nativeapp.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.runBlocking
import java.io.BufferedOutputStream
import java.io.File

/**
 * M85-B：随 APK 打包的内置插件（`assets/builtin-plugins/<id>/`）。
 *
 * 和用户导入的插件**同一套格式/同一套开关**：首次启动时打进 `files/plugins/<id>/` 并走一次真实
 * 试运行；之后就是普通插件记录（可停用，可被同 id 的导入插件覆盖）。宿主不享受任何特权。
 */
object BundledPlugins {

    const val WHALE_SHELL = "builtin.shell.whale"

    private const val ASSET_ROOT = "builtin-plugins"
    private const val TAG = "DshBundled"

    /** 内置默认壳：缺记录才安装；用户删掉后下次启动会补回（M85-B 先这样，M85-C 前再定「删除」语义）。 */
    fun ensureWhaleShell(context: Context, store: PluginStore) {
        ensure(context, store, WHALE_SHELL)
    }

    private fun ensure(context: Context, store: PluginStore, id: String) {
        val assetDir = "$ASSET_ROOT/$id"
        val names = runCatching { context.assets.list(assetDir)?.filter { it.endsWith(".json") || it.endsWith(".js") }.orEmpty() }
            .getOrDefault(emptyList())
        if (names.isEmpty()) {
            Log.w(TAG, "内置插件 assets 缺失：$assetDir")
            return
        }
        // 版本升级：记录还在但版本不同 → 用 APK 里的新文件覆盖（配置/授权按重装规则保留）。
        val assetVersion = runCatching {
            context.assets.open("$assetDir/package.json").use { input ->
                org.json.JSONObject(input.readBytes().toString(Charsets.UTF_8)).optString("version")
            }
        }.getOrDefault("")
        val existing = store.list().firstOrNull { it.id == id }
        if (existing != null && (assetVersion.isBlank() || existing.version == assetVersion)) return
        val zip = File(context.cacheDir, "bundled-$id.zip")
        val created = runCatching {
            BufferedOutputStream(zip.outputStream()).use { out ->
                java.util.zip.ZipOutputStream(out).use { zos ->
                    names.forEach { name ->
                        zos.putNextEntry(java.util.zip.ZipEntry(name))
                        context.assets.open("$assetDir/$name").use { input -> input.copyTo(zos) }
                        zos.closeEntry()
                    }
                }
            }
            true
        }.getOrElse {
            Log.w(TAG, "打包内置插件失败：$id ${it.message}")
            false
        }
        if (!created) return
        val result = runCatching { runBlocking { store.installFile(zip) } }
            .getOrElse { PluginStore.Result(false, it.message ?: it.javaClass.simpleName) }
        zip.delete()
        Log.i(TAG, "内置插件 $id install ok=${result.ok} ${result.message}")
    }
}

package xin.dshaa.nativeapp.data

import android.content.Context
import android.util.Log
import java.io.File

/**
 * 已删除的内置插件清理（`docs/187` §五）。
 *
 * 历史：`builtin.shell.whale`（鲸歌可选壳）与 `builtin.floating.demo`（悬浮窗插口示例）曾随包
 * 打进 `assets/builtin-plugins/` 并装进 `files/plugins/`。用户 2026-11-23 明确说它们没用，
 * 已从 APK 里删掉；**老设备上残留的记录与目录要在启动时一起清掉**，否则插件页/角色页会留下
 * 一条指向不存在目录的坏记录（`whale` 还会继续出现在壳候选里）。
 *
 * 这里只做「删」，不再安装任何内置插件（以后要内置插件另开入口）。
 */
object BundledPlugins {

    /** 需要清理的历史内置插件 id。 */
    val obsoleteIds: List<String> = listOf(
        "builtin.shell.whale",
        "builtin.floating.demo",
    )

    private const val TAG = "DshBundled"

    /**
     * 幂等清理：删 `files/plugins/<id>/` 与 `plugins.json` 里的记录（[PluginStore.uninstall] 两者都管）。
     * 不存在就什么都不做；任何一步失败只记日志，不挡启动。
     */
    fun purgeObsolete(context: Context, store: PluginStore) {
        val installed = runCatching { store.list().map { it.id }.toSet() }.getOrDefault(emptySet())
        for (id in obsoleteIds) {
            val dir = File(File(context.filesDir, "plugins"), id)
            val recorded = id in installed
            if (!recorded && !dir.exists()) continue
            // 记录先删（uninstall 会删目录 + 更新 plugins.json）；记录不在但目录还在时补删目录。
            val removed = runCatching { store.uninstall(id) }
                .getOrElse { PluginStore.Result(false, it.message ?: it.javaClass.simpleName) }
            if (!removed.ok) {
                Log.w(TAG, "清理旧内置插件失败：$id ${removed.message}")
            }
            if (dir.exists()) {
                val gone = runCatching { dir.deleteRecursively() }.getOrDefault(false)
                Log.i(TAG, "残留目录 $id 删除=$gone")
            }
            Log.i(TAG, "旧内置插件已清理：$id（记录=$recorded, uninstall=${removed.ok}）")
        }
    }
}

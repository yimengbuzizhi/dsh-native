package xin.dshaa.nativeapp.pluginui

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import xin.dshaa.core.plugins.ContainerKind
import xin.dshaa.core.plugins.ContainerSpec
import xin.dshaa.core.plugins.PluginClientEntry
import xin.dshaa.core.plugins.PluginRecord
import java.io.File

/**
 * 「插件容器」的入口（P3-e，`docs/187` §9.2）：解析 `exports["./client"]` → 启动通用宿主服务。
 *
 * 这里只做四件事：解析入口、检查悬浮窗权限、算容器种类、发 Intent；窗口本身在
 * [ClientPluginWindowService] 里（三种容器共用同一套 WebView / 文件拦截 / 手势，
 * 不重写一套基础设施）。失败返回中文原因，调用方（插件页/原语/调试通道）原样展示，不假装成功。
 *
 * 容器只有三种（[ContainerKind]），不随上游界面贡献增长；没有 slot 协议、没有会话流内嵌。
 */
object ClientPluginWindows {

    /** 解析任意插件目录（原生或 Node 插件均可）能不能跑浏览器半侧。 */
    fun resolveDir(pluginDir: File): PluginClientEntry.Result =
        PluginClientEntry.resolve(pluginDir)

    /** 解析这个插件能不能跑浏览器半侧；不能时给出具体原因。 */
    fun resolve(record: PluginRecord): PluginClientEntry.Result =
        resolveDir(File(record.path))

    /** 打开插件自己的界面；返回给用户看的结果（成功/失败原因）。老调用方 = 悬浮窗。 */
    fun open(context: Context, record: PluginRecord): String =
        open(context, record, ContainerKind.FLOATING, title = null)

    /** P3-e：按容器种类打开；[title] 为空用 [ContainerSpec.defaultTitle]。 */
    fun open(
        context: Context,
        record: PluginRecord,
        kind: ContainerKind,
        title: String? = null,
    ): String = openDir(context, record.id, File(record.path), record.name, kind, title)

    /** 打开任意插件目录（支持 Node 混合插件直接打开自带 web client 容器）。 */
    fun openDir(
        context: Context,
        pluginId: String,
        pluginDir: File,
        pluginName: String,
        kind: ContainerKind = ContainerKind.FLOATING,
        title: String? = null,
    ): String {
        if (!Settings.canDrawOverlays(context)) return "没有悬浮窗权限：请到系统设置里允许「显示在其他应用上层」"
        val resolved = resolveDir(pluginDir)
        if (resolved !is PluginClientEntry.Result.Resolved) {
            return "这个插件没有可运行的浏览器半侧：${(resolved as PluginClientEntry.Result.Unsupported).reason}"
        }
        val intent = Intent(context, ClientPluginWindowService::class.java).apply {
            putExtra(ClientPluginWindowService.EXTRA_PLUGIN_ID, pluginId)
            putExtra(ClientPluginWindowService.EXTRA_PLUGIN_DIR, pluginDir.absolutePath)
            putExtra(ClientPluginWindowService.EXTRA_PLUGIN_NAME, pluginName)
            putExtra(ClientPluginWindowService.EXTRA_ENTRY, resolved.entry)
            putExtra(ClientPluginWindowService.EXTRA_KIND, kind.wire)
            title?.takeIf { it.isNotBlank() }?.let {
                putExtra(ClientPluginWindowService.EXTRA_TITLE, it)
            }
        }
        return runCatching {
            context.startForegroundService(intent)
            ClientPluginWindowService.markRunning(true)
            Log.i(TAG, "open container kind=${kind.wire} id=$pluginId entry=${resolved.entry} title=$title")
            "正在打开「$pluginName」的插件界面（${kindLabel(kind)}）"
        }.getOrElse {
            Log.w(TAG, "open plugin container failed: ${it.message}")
            "打开插件容器失败：${it.message}"
        }
    }

    fun close(context: Context) {
        runCatching { context.stopService(Intent(context, ClientPluginWindowService::class.java)) }
        ClientPluginWindowService.markRunning(false)
    }

    /** 调试/截图用：没有 UI 时也能知道当前有没有容器。 */
    fun isRunning(): Boolean = ClientPluginWindowService.isRunning()

    fun kindLabel(kind: ContainerKind): String = when (kind) {
        ContainerKind.FULLSCREEN -> "整页"
        ContainerKind.FLOATING -> "悬浮窗"
        ContainerKind.SHEET -> "抽屉"
    }

    fun toast(context: Context, message: String) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }

    private const val TAG = "DshClientHost"
}

package xin.dshaa.nativeapp.pluginui

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import xin.dshaa.core.plugins.PluginClientEntry
import xin.dshaa.core.plugins.PluginRecord
import java.io.File

/**
 * 「插件窗口」的入口（M70）：解析 `dsh.client` → 启动通用宿主服务。
 *
 * 这里只做三件事：解析、检查悬浮窗权限、发 Intent；窗口本身在
 * [ClientPluginWindowService] 里。失败返回中文原因，调用方（插件页/调试通道）
 * 原样展示，不假装成功。
 */
object ClientPluginWindows {

    /** 解析这个插件能不能跑浏览器半侧；不能时给出具体原因。 */
    fun resolve(record: PluginRecord): PluginClientEntry.Result =
        PluginClientEntry.resolve(File(record.path))

    /** 打开插件自己的界面；返回给用户看的结果（成功/失败原因）。 */
    fun open(context: Context, record: PluginRecord): String {
        if (!Settings.canDrawOverlays(context)) return "没有悬浮窗权限：请到系统设置里允许「显示在其他应用上层」"
        val resolved = resolve(record)
        if (resolved !is PluginClientEntry.Result.Resolved) {
            return "这个插件没有可运行的浏览器半侧：${(resolved as PluginClientEntry.Result.Unsupported).reason}"
        }
        val intent = Intent(context, ClientPluginWindowService::class.java).apply {
            putExtra(ClientPluginWindowService.EXTRA_PLUGIN_ID, record.id)
            putExtra(ClientPluginWindowService.EXTRA_PLUGIN_DIR, record.path)
            putExtra(ClientPluginWindowService.EXTRA_PLUGIN_NAME, record.name)
            putExtra(ClientPluginWindowService.EXTRA_ENTRY, resolved.entry)
        }
        return runCatching {
            context.startForegroundService(intent)
            ClientPluginWindowService.markRunning(true)
            Log.i(TAG, "open plugin window id=${record.id} entry=${resolved.entry}")
            "正在打开「${record.name}」的插件界面"
        }.getOrElse {
            Log.w(TAG, "open plugin window failed: ${it.message}")
            "打开插件窗口失败：${it.message}"
        }
    }

    fun close(context: Context) {
        runCatching { context.stopService(Intent(context, ClientPluginWindowService::class.java)) }
        ClientPluginWindowService.markRunning(false)
    }

    /** 调试/截图用：没有 UI 时也能知道当前有没有窗口。 */
    fun isRunning(): Boolean = ClientPluginWindowService.isRunning()

    fun toast(context: Context, message: String) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }

    private const val TAG = "DshClientHost"
}

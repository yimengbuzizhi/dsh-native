package xin.dshaa.nativeapp

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.ai.assistance.showerclient.ShowerEnvironment
import xin.dshaa.nativeapp.device.DeviceShell
import xin.dshaa.nativeapp.device.a11y.A11y
import xin.dshaa.nativeapp.device.floating.FloatingBallService

/**
 * 进程级初始化：
 * - Shower（虚拟屏）需要宿主注入 [DeviceShell]（Shizuku / root）；
 * - Shizuku binder 监听尽早挂上，虚拟屏/终端第一次用就不必等；
 * - 无障碍服务是否已启用由系统绑定决定，这里只做状态探测。
 *
 * 2026-11-09（用户要求）：悬浮球跟随后台——应用不在前台时自动出现，回到前台自动收起。
 * 用 [ProcessLifecycleOwner] 而不是 MainActivity 的生命周期，避免位置附件那种透明 Activity
 * 把「应用退后台」误判成一次前后台切换。
 *
 * 2026-11-10（M71，用户要求）：大肥鱼 / 3D 鲸鱼娘 / 鲸鱼娘挂件三组原生替身全部删除，
 * 悬浮窗只剩悬浮球；这些插件的界面改由通用客户端插件宿主跑插件自己的 `dsh.client`。
 */
class DshApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // 2026-11-24：崩溃循环自救。必须在任何插件加载之前判定（本方法先于 Activity/AppViewModel），
        // 60 秒内第 5 次启动 → 停用全部第三方插件，App 用内置实现起来，顶部横幅可一键恢复。
        runCatching { enterSafeModeIfCrashLooping() }
            .onFailure { android.util.Log.w("DshSafeMode", "safe mode check failed: ${it.message}") }
        ShowerEnvironment.shellRunner = DeviceShell
        // jar 暂存到 App 专属外部目录（shell 用户可读），不再写 /sdcard/Download（需要 MANAGE_EXTERNAL_STORAGE）
        ShowerEnvironment.stagingDir = DeviceShell.showerJarDir(this)
        DeviceShell.initShizuku(this)
        // root-only 虚拟屏：把 aarch64 shell 身份 launcher 装到 filesDir/bin（不可用会记原因）
        DeviceShell.initIdentityLauncher(this)
        // H7：root 探测放后台（su 授权弹窗可能等十几秒），能力页/终端读缓存。
        DeviceShell.primeRootProbe()
        A11y.isReady()
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                FloatingBallService.hideWhenForeground(this@DshApplication)
            }

            override fun onStop(owner: LifecycleOwner) {
                FloatingBallService.showWhenBackground(this@DshApplication)
            }
        })
    }

    /**
     * 用户 2026-11-24：「一分钟之内重启应用 5 次进入安全模式；已经有用户写插件写坏，只能重装」。
     *
     * 只做两件事：记一次启动；达到阈值就**在插件加载之前**把第三方插件全停用并把名单留好，
     * 供聊天页横幅「恢复第三方插件」使用。停用是写 `plugins.json` 的 enabled，等同用户在插件页
     * 手动关掉——恢复时按名单开回去，不丢插件、不丢配置、不丢会话。
     *
     * 为什么同步做：Async 化会让首个 Activity 抢在停用前把坏插件加载起来（那正是要避免的崩溃）。
     * 注册表只是一个 JSON，读写在主线程毫秒级。
     */
    private fun enterSafeModeIfCrashLooping() {
        val safeMode = xin.dshaa.nativeapp.data.SafeMode(this)
        if (safeMode.active) return
        if (!safeMode.recordLaunch()) return
        val store = xin.dshaa.nativeapp.data.PluginStore(this)
        val enabled = store.list().filter { it.enabled }.map { it.id }.toSet()
        safeMode.activate(enabled)
        val disabled = if (enabled.isEmpty()) 0 else store.setAllEnabled(false)
        android.util.Log.w("DshSafeMode", "entered safe mode: plugins disabled=$disabled (${enabled.size} known)")
    }
}

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
}

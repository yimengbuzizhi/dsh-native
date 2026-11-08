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
 */
class DshApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        ShowerEnvironment.shellRunner = DeviceShell
        DeviceShell.initShizuku(this)
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

package xin.dshaa.nativeapp.device

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.ai.assistance.shower.IShowerService
import com.ai.assistance.shower.ShowerBinderContainer
import com.ai.assistance.showerclient.ShowerBinderRegistry

/**
 * Shower 服务端（app_process 拉起的 jar）通过广播把 Binder 交回来。
 *
 * action 是预编译 jar 里写死的 `com.ai.assistance.operit.action.SHOWER_BINDER_READY`
 * （不能改成我们自己的包名，否则收不到）；extra key 是 `binder_container`。
 */
class ShowerBinderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_SHOWER_BINDER_READY) return
        @Suppress("DEPRECATION")
        val container = intent.getParcelableExtra<ShowerBinderContainer>(EXTRA_BINDER_CONTAINER) ?: return
        val binder = container.binder ?: return
        ShowerBinderRegistry.setService(IShowerService.Stub.asInterface(binder))
        Log.i("ShowerBinder", "received shower binder for ${context.packageName}")
    }

    private companion object {
        const val ACTION_SHOWER_BINDER_READY = "com.ai.assistance.operit.action.SHOWER_BINDER_READY"
        const val EXTRA_BINDER_CONTAINER = "binder_container"
    }
}

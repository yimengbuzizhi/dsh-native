package xin.dshaa.nativeapp.device.attach

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * 「位置」附件（docs/76 W24）：运行时权限 + 取一次位置。
 *
 * 权限是 `ACCESS_FINE_LOCATION` / `ACCESS_COARSE_LOCATION`，Service/悬浮窗不能自己弹
 * 授权框，所以统一走 [LocationAttachActivity]（透明 Activity）。
 *
 * 取点顺序：`getCurrentLocation`（Android 11+，带超时）→ 失败退回各 provider 的
 * `getLastKnownLocation`（取时间最新的一条）。两者都没有就报错，不编造坐标。
 */
object LocationCapture {

    private const val TAG = "DshAttach"

    val PERMISSIONS = arrayOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
    )

    fun hasPermission(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /** 悬浮窗/Service 入口：起透明 Activity 走授权 + 抓取。返回 false 表示没起来。 */
    fun start(context: Context): Boolean {
        val intent = Intent(context, LocationAttachActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
        }
        return runCatching { context.startActivity(intent) }
            .onFailure { Log.w(TAG, "start LocationAttachActivity failed: ${it.message}") }
            .isSuccess
    }

    /**
     * 取一次位置。回调保证在主线程、且只回调一次；拿不到给 null（调用方提示用户）。
     * 总耗时上限约 [timeoutMs]，不会把附件入口卡死。
     */
    fun locate(context: Context, timeoutMs: Long = 8_000, onResult: (Location?) -> Unit) {
        val lm = runCatching { context.getSystemService(LocationManager::class.java) }.getOrNull()
        if (lm == null) {
            onResult(null)
            return
        }
        val fallback = bestLastKnown(lm)
        val provider = currentProvider(lm)
        if (provider == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            onResult(fallback)
            return
        }
        val handler = Handler(Looper.getMainLooper())
        val signal = CancellationSignal()
        var done = false

        fun finish(location: Location?) {
            if (done) return
            done = true
            handler.removeCallbacksAndMessages(null)
            onResult(location ?: fallback)
        }

        val started = runCatching {
            lm.getCurrentLocation(provider, signal, context.mainExecutor) { location ->
                finish(location)
            }
        }.onFailure {
            Log.w(TAG, "getCurrentLocation($provider) failed: ${it.message}")
        }.isSuccess
        if (!started) {
            finish(null)
            return
        }
        handler.postDelayed({
            if (!done) {
                runCatching { signal.cancel() }
                Log.i(TAG, "getCurrentLocation($provider) timeout ${timeoutMs}ms，退回 lastKnown")
                finish(null)
            }
        }, timeoutMs)
    }

    /** 拿当前最佳的已启用 provider（fused 优先，其次 network/gps/passive）。 */
    private fun currentProvider(lm: LocationManager): String? {
        val candidates = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(LocationManager.FUSED_PROVIDER)
            add(LocationManager.NETWORK_PROVIDER)
            add(LocationManager.GPS_PROVIDER)
            add(LocationManager.PASSIVE_PROVIDER)
        }
        return candidates.firstOrNull { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }
    }

    private fun bestLastKnown(lm: LocationManager): Location? {
        val providers = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(LocationManager.FUSED_PROVIDER)
            add(LocationManager.GPS_PROVIDER)
            add(LocationManager.NETWORK_PROVIDER)
            add(LocationManager.PASSIVE_PROVIDER)
        }
        return providers
            .mapNotNull { provider -> runCatching { lm.getLastKnownLocation(provider) }.getOrNull() }
            .maxByOrNull { it.time }
    }
}

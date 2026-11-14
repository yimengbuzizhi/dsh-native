package xin.dshaa.nativeapp.device.attach

import android.location.Geocoder
import android.location.Location
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale

/**
 * 「位置」附件的授权 + 抓取入口（透明 Activity，docs/76 W24）。
 *
 * 为什么必须是 Activity：`ACCESS_*_LOCATION` 是运行时权限，Service/悬浮窗不能自己弹
 * 授权框。悬浮窗点「位置」时用 NEW_TASK 起本页；授权通过后抓一次位置、写进待发附件，
 * 用户只看到系统授权框 + 一条 toast，没有额外界面。
 *
 * 调试入口：`am start -n xin.dshaa.nativeapp/.MainActivity --ez dsh_attach_location true`
 * （MainActivity 里转起本页，见 MainActivity 的 debug extras）。
 */
class LocationAttachActivity : ComponentActivity() {

    private var captured = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        val granted = result.values.any { it } || LocationCapture.hasPermission(this)
        Log.i(
            TAG,
            "permission result=${result.entries.joinToString { "${it.key.substringAfterLast('.')}=${it.value}" }}",
        )
        if (granted) {
            capture()
        } else {
            toast("没有定位权限，已取消位置附件（可在系统设置里给「鲸歌」打开位置权限）")
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (LocationCapture.target(intent.getStringExtra(LocationCapture.TARGET_EXTRA)) == null) {
            toast("原会话已关闭，请重新添加位置附件")
            finish()
            return
        }
        if (LocationCapture.hasPermission(this)) {
            capture()
        } else {
            toast("位置附件需要定位权限")
            permissionLauncher.launch(LocationCapture.PERMISSIONS)
        }
    }

    private fun capture() {
        if (captured) return
        captured = true
        toast("正在获取位置…")
        LocationCapture.locate(this, 8_000) { location ->
            runOnUiThread { finishWithLocation(location) }
        }
    }

    private fun finishWithLocation(location: Location?) {
        if (location == null) {
            toast("没拿到位置：确认系统定位开关已开，或到窗边/室外再试")
            finish()
            return
        }
        lifecycleScope.launch {
            val address = withContext(Dispatchers.IO) {
                withTimeoutOrNull(2_500) { geocode(location) }
            }
            val target = LocationCapture.target(intent.getStringExtra(LocationCapture.TARGET_EXTRA))
            val outcome = AttachmentCapture.attachLocation(this@LocationAttachActivity, location, address, target)
            toast(outcome.message)
            finish()
        }
    }

    override fun onDestroy() {
        if (isFinishing) LocationCapture.releaseTarget(intent.getStringExtra(LocationCapture.TARGET_EXTRA))
        super.onDestroy()
    }

    /** 逆地理编码是 best-effort：没有 GeocodeProvider / 没网就返回 null，不阻塞附件。 */
    private fun geocode(location: Location): String? = runCatching {
        Geocoder(this, Locale.getDefault()).getFromLocation(location.latitude, location.longitude, 1)
            ?.firstOrNull()
            ?.getAddressLine(0)
    }.getOrNull()?.takeIf { it.isNotBlank() }

    private fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }

    private companion object {
        const val TAG = "DshAttach"
    }
}

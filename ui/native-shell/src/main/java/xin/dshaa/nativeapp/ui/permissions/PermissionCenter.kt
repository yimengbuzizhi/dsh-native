package xin.dshaa.nativeapp.ui.permissions

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Accessibility
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import xin.dshaa.nativeapp.device.DevicePermissions
import xin.dshaa.nativeapp.device.DevicePermissions.ShizukuState
import xin.dshaa.ui.design.theme.DshTheme2

/** 设备能力项的 id：每项都能「查 + 去授权」，界面只认这个枚举。 */
enum class PermissionId { Shizuku, Accessibility, Overlay, Notifications, AllFiles, Battery }

/**
 * 一项设备能力在界面上的投影。
 *
 * [ready] 是给进度条用的（Shizuku 只有 GRANTED 才算 ready，装了没授权不算）；
 * [status] 是右上的小字（二态能力就是「已开/未开」，Shizuku 四态要区分）。
 */
data class PermissionSpec(
    val id: PermissionId,
    val title: String,
    val why: String,
    val icon: ImageVector,
    val ready: Boolean,
    val status: String,
    val action: String?,
)

fun permissionSpecs(context: Context): List<PermissionSpec> {
    val shizuku = DevicePermissions.shizukuState(context)
    val a11y = DevicePermissions.accessibilityEnabled(context)
    val overlay = DevicePermissions.overlayGranted(context)
    val notif = DevicePermissions.notificationsEnabled(context)
    val files = DevicePermissions.allFilesGranted(context)
    val battery = DevicePermissions.batteryOptimizationIgnored(context)
    return listOf(
        PermissionSpec(
            id = PermissionId.Shizuku,
            title = "Shizuku",
            why = "ADB 级 shell：虚拟屏、终端、设备工具都走它",
            icon = Icons.Rounded.Terminal,
            ready = shizuku == ShizukuState.GRANTED,
            status = when (shizuku) {
                ShizukuState.GRANTED -> "已授权"
                ShizukuState.NOT_GRANTED -> "未授权"
                ShizukuState.NOT_RUNNING -> "未运行"
                ShizukuState.NOT_INSTALLED -> "未安装"
            },
            action = when (shizuku) {
                ShizukuState.GRANTED -> null
                ShizukuState.NOT_GRANTED -> "授权"
                ShizukuState.NOT_RUNNING -> "启动"
                ShizukuState.NOT_INSTALLED -> "安装"
            },
        ),
        PermissionSpec(
            id = PermissionId.Accessibility,
            title = "无障碍服务",
            why = "读屏、点击、手势、截图（模型操作手机的手）",
            icon = Icons.Rounded.Accessibility,
            ready = a11y,
            status = if (a11y) "已启用" else "未启用",
            action = if (a11y) null else "去开启",
        ),
        PermissionSpec(
            id = PermissionId.Overlay,
            title = "悬浮窗",
            why = "悬浮球：聊天/终端/虚拟屏的全局入口",
            icon = Icons.Rounded.Layers,
            ready = overlay,
            status = if (overlay) "已授予" else "未授予",
            action = if (overlay) null else "去授权",
        ),
        PermissionSpec(
            id = PermissionId.Notifications,
            title = "通知",
            why = "后台任务与前台服务的进度提醒",
            icon = Icons.Rounded.Notifications,
            ready = notif,
            status = if (notif) "已开启" else "已关闭",
            action = if (notif) null else "去开启",
        ),
        PermissionSpec(
            id = PermissionId.AllFiles,
            title = "所有文件访问",
            why = "工作区放到手机存储（/sdcard）时要靠它",
            icon = Icons.Rounded.FolderOpen,
            ready = files,
            status = if (files) "已授予" else "未授予",
            action = if (files) null else "去授权",
        ),
        PermissionSpec(
            id = PermissionId.Battery,
            title = "电池优化",
            why = "长时间任务/虚拟屏不被系统掐掉",
            icon = Icons.Rounded.BatteryChargingFull,
            ready = battery,
            status = if (battery) "已忽略" else "受限制",
            action = if (battery) null else "去设置",
        ),
    )
}

/**
 * 设备能力清单（引导页与「权限与能力」页共用）。
 *
 * 交互：点整行/右侧按钮 = 去授权；点完回来靠 ON_RESUME + 未全就绪时 900ms 轮询刷新
 * （Shizuku 的 binder 可能比界面晚到，没有轮询会一直显示「未运行」）。
 */
@Composable
fun PermissionChecklist(
    modifier: Modifier = Modifier,
    onAllReady: (Boolean) -> Unit = {},
    bottomSlot: @Composable () -> Unit = {},
) {
    val c = DshTheme2.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var tick by remember { mutableIntStateOf(0) }
    val specs = remember(tick) { permissionSpecs(context) }
    val allReady = specs.isNotEmpty() && specs.all { it.ready }

    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) tick++
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(tick, allReady) {
        if (!allReady) {
            delay(900)
            tick++
        }
    }
    LaunchedEffect(allReady, specs.size) { onAllReady(allReady) }

    fun actionFor(spec: PermissionSpec) {
        when (spec.id) {
            PermissionId.Shizuku -> when (DevicePermissions.shizukuState(context)) {
                ShizukuState.NOT_INSTALLED -> DevicePermissions.openShizukuStore(context)
                ShizukuState.NOT_RUNNING -> DevicePermissions.openShizukuManager(context)
                else -> DevicePermissions.requestShizuku { ok -> scope.launch { if (ok) tick++ } }
            }
            PermissionId.Accessibility -> DevicePermissions.openAccessibilitySettings(context)
            PermissionId.Overlay -> DevicePermissions.openOverlaySettings(context)
            PermissionId.Notifications -> DevicePermissions.openNotificationSettings(context)
            PermissionId.AllFiles -> DevicePermissions.openAllFilesSettings(context)
            PermissionId.Battery -> DevicePermissions.requestBatteryExemption(context)
        }
    }

    LazyColumn(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(c.surface, RoundedCornerShape(18.dp))
                    .padding(16.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (allReady) "全部就绪" else "已就绪 ${specs.count { it.ready }}/${specs.size}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = c.text,
                    )
                    Spacer(Modifier.weight(1f))
                    Box(
                        Modifier.size(30.dp).background(c.surfaceAlt, CircleShape).clickable { tick++ },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Rounded.Refresh, "刷新", tint = c.sub, modifier = Modifier.size(17.dp))
                    }
                }
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { if (specs.isEmpty()) 0f else specs.count { it.ready }.toFloat() / specs.size },
                    modifier = Modifier.fillMaxWidth().height(6.dp),
                    color = if (allReady) c.success else c.brand,
                    trackColor = c.surfaceAlt,
                    strokeCap = StrokeCap.Round,
                    drawStopIndicator = {},
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    if (allReady) "虚拟屏 / 终端 / 读屏都能用了。" else "缺哪项点哪行去开，回来会自动刷新。",
                    style = MaterialTheme.typography.bodySmall,
                    color = c.sub,
                )
            }
        }
        items(specs, key = { it.id.name }) { spec ->
            PermissionRow(spec) { actionFor(spec) }
        }
        item { bottomSlot() }
    }
}

@Composable
private fun PermissionRow(spec: PermissionSpec, onClick: () -> Unit) {
    val c = DshTheme2.colors
    Row(
        Modifier
            .fillMaxWidth()
            .background(c.surface, RoundedCornerShape(16.dp))
            .clickable { if (spec.action != null) onClick() }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(38.dp)
                .background(if (spec.ready) c.successSoft else c.brandSoft, RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(spec.icon, null, tint = if (spec.ready) c.success else c.brand, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(spec.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = c.text)
            Text(spec.why, style = MaterialTheme.typography.labelSmall, color = c.faint, maxLines = 2)
        }
        Spacer(Modifier.width(8.dp))
        if (spec.ready) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Check, null, tint = c.success, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(3.dp))
                Text(spec.status, style = MaterialTheme.typography.labelSmall, color = c.success)
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(spec.status, style = MaterialTheme.typography.labelSmall, color = c.warn)
                Spacer(Modifier.width(6.dp))
                Box(
                    Modifier
                        .background(c.brand, RoundedCornerShape(50))
                        .clickable(onClick = onClick)
                        .padding(horizontal = 11.dp, vertical = 5.dp),
                ) {
                    Text(spec.action ?: "去授权", style = MaterialTheme.typography.labelSmall, color = c.onHero)
                }
            }
        }
    }
}

/** 「权限与能力」独立页（设置 → 数据和权限 → 权限与能力）。 */
@Composable
fun PermissionsScreen(onBack: () -> Unit) {
    val c = DshTheme2.colors
    Column(Modifier.fillMaxSize().background(c.background).statusBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(40.dp).clickable(onClick = onBack), contentAlignment = Alignment.Center) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回", tint = c.text)
            }
            Column(Modifier.weight(1f)) {
                Text("权限与能力", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = c.text)
                Text("虚拟屏 / 终端 / 读屏的前置开关", style = MaterialTheme.typography.labelSmall, color = c.faint)
            }
        }
        PermissionChecklist(
            modifier = Modifier.padding(horizontal = 14.dp),
            bottomSlot = {
                Text(
                    "Shizuku 需要先装官方 App 并启动服务（无线调试/USB 启动过一次即可）；" +
                        "无障碍在系统设置里属于「已下载的服务」。这两项不开，虚拟屏和读屏就用不了。",
                    style = MaterialTheme.typography.labelSmall,
                    color = c.faint,
                    modifier = Modifier.padding(vertical = 14.dp),
                )
            },
        )
    }
}

package xin.dshaa.nativeapp.ui.screen

import android.content.Intent
import android.graphics.BitmapFactory
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.ai.assistance.showerclient.ui.ShowerSurfaceView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import xin.dshaa.nativeapp.device.DeviceShell
import xin.dshaa.nativeapp.device.shower.VirtualDisplaySpec
import xin.dshaa.nativeapp.device.shower.VirtualScreenManager
import xin.dshaa.nativeapp.device.shower.VirtualScreenSession

/**
 * 虚拟屏（Operit showerclient 的原生接入）：
 * 用 Shizuku/root 拉起 `shower-server.jar`（app_process），创建虚拟显示，
 * 画面经 ShowerSurfaceView（MediaCodec）渲染；触摸映射回虚拟屏坐标。
 *
 * 没有 Shizuku/root 时如实提示（Shower 必须用 shell uid 建虚拟屏）。
 *
 * 2026-11-07：Home 改成启动虚拟桌面 Activity（系统 HOME 键在 Android 15 只会切主屏）；
 * 启动后默认进虚拟桌面而不是黑屏；回前台重建 sink；尺寸可选。
 */
@Composable
fun VirtualScreenPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val controller = VirtualScreenSession.controller
    var status by remember {
        mutableStateOf(
            if (VirtualScreenSession.running) {
                "运行中 · ${VirtualScreenSession.appLabel ?: ""}".trim(' ', '·')
            } else {
                "未启动"
            },
        )
    }
    var running by remember { mutableStateOf(VirtualScreenSession.running) }
    var busy by remember { mutableStateOf(false) }
    var videoSize by remember { mutableStateOf(VirtualScreenSession.videoSize) }
    var spec by remember { mutableStateOf(VirtualScreenManager.specFromPrefs(context)) }
    var screenshot by remember { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    var appPicker by remember { mutableStateOf(false) }
    var apps by remember { mutableStateOf<List<AppEntry>>(emptyList()) }
    var displayDialog by remember { mutableStateOf(false) }

    suspend fun reattachSurface() {
        val currentSpec = VirtualScreenSession.spec ?: spec
        runCatching {
            controller.ensureDisplay(context, currentSpec.width, currentSpec.height, currentSpec.dpi)
        }
        videoSize = controller.getVideoSize()
    }

    // 第一次进页面：显示还活着但 Surface 换了，重新把编码器的 sink 接过来。
    // 预览窗先收起：页面自己的 Surface 才是主画面（两个 Surface 会抢同一条视频流）。
    LaunchedEffect(Unit) {
        xin.dshaa.nativeapp.device.shower.VirtualScreenOverlay.hide()
        if (running) reattachSurface()
    }

    // 从桌面/别的 App 回到本页：Activity 只是 pause/resume，不会重建 Compose，
    // 所以必须在 ON_RESUME 再 ensureDisplay 一次，否则一直是黑屏（旧 surface 已销毁）。
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner, running) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && running) {
                scope.launch {
                    delay(120)
                    reattachSurface()
                }
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }

    fun loadApps() {
        if (apps.isNotEmpty()) return
        scope.launch(Dispatchers.IO) {
            val pm = context.packageManager
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val resolved = pm.queryIntentActivities(intent, 0)
            val list = resolved
                .map { AppEntry(it.activityInfo.packageName, it.loadLabel(pm).toString(), it.activityInfo.name) }
                .distinctBy { it.packageName }
                .sortedBy { it.label.lowercase() }
            withContext(Dispatchers.Main) { apps = list }
        }
    }

    fun launchInScreen(app: AppEntry) {
        appPicker = false
        scope.launch {
            val ok = runCatching { controller.launchApp(app.packageName) }.getOrDefault(false)
            if (ok) {
                context.getSharedPreferences("dsh", 0).edit().putString("vs_last_app", app.packageName).apply()
                status = "运行中 · ${app.label}"
                VirtualScreenSession.markStarted(
                    controller.getVideoSize(),
                    app.label,
                    spec,
                    app.packageName,
                )
                videoSize = controller.getVideoSize()
            } else {
                Toast.makeText(context, "启动 ${app.label} 失败", Toast.LENGTH_SHORT).show()
            }
        }
    }

    suspend fun doStart() {
        status = "检查 shell 通道…"
        val prefs = context.getSharedPreferences("dsh", 0)
        val lastPkg = prefs.getString("vs_last_app", null)?.takeIf { pkg ->
            runCatching { context.packageManager.getLaunchIntentForPackage(pkg) != null }.getOrDefault(false)
        }
        status = "拉起 Shower 服务…"
        val result = VirtualScreenManager.start(context, spec, lastPkg)
        result.onSuccess { started ->
            videoSize = started.size
            running = true
            status = if (started.appLabel != null) {
                "运行中 · ${started.appLabel}"
            } else {
                "运行中 · 桌面"
            }
        }.onFailure {
            running = false
            status = it.message ?: "启动虚拟屏失败"
        }
    }

    fun startScreen() {
        if (busy) return
        busy = true
        scope.launch {
            doStart()
            busy = false
        }
    }

    fun restartWith(newSpec: VirtualDisplaySpec) {
        if (busy) return
        busy = true
        displayDialog = false
        scope.launch {
            status = "按 ${newSpec.label} 重建虚拟屏…"
            if (running) {
                VirtualScreenManager.stop(context)
                running = false
                delay(350)
            }
            spec = newSpec
            VirtualScreenManager.saveSpec(context, newSpec)
            doStart()
            busy = false
        }
    }

    fun stopScreen() {
        if (busy) return
        busy = true
        scope.launch {
            VirtualScreenManager.stop(context)
            running = false
            status = "已停止"
            busy = false
        }
    }

    fun openVirtualHome() {
        if (!running) return
        scope.launch {
            val ok = VirtualScreenManager.openHome(context)
            if (ok) {
                status = "运行中 · 桌面"
                VirtualScreenSession.markStarted(controller.getVideoSize(), "桌面", spec)
            } else {
                Toast.makeText(context, "打开虚拟桌面失败", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun shot() {
        scope.launch {
            val bytes = runCatching { controller.requestScreenshot() }.getOrNull()
            val bitmap = bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
            if (bitmap == null) {
                Toast.makeText(context, "截图失败", Toast.LENGTH_SHORT).show()
            } else {
                screenshot = bitmap.asImageBitmap()
            }
        }
    }

    Column(Modifier.fillMaxSize().background(Color(0xFF0B0F14))) {
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(40.dp).pointerInput(Unit) { detectTapGestures { onBack() } }, contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.ArrowBack, "返回", tint = Color.White, modifier = Modifier.size(22.dp))
            }
            Column(Modifier.weight(1f)) {
                Text("虚拟屏", color = Color.White, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    status,
                    color = Color.White.copy(alpha = 0.6f),
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
            }
            if (running) {
                IconButtonBox(Icons.Rounded.Apps) {
                    loadApps()
                    appPicker = true
                }
                IconButtonBox(Icons.Rounded.ArrowBack) { scope.launch { controller.key(4) } }
                IconButtonBox(Icons.Rounded.Home) { openVirtualHome() }
                IconButtonBox(Icons.Rounded.Menu) { displayDialog = true }
            }
        }

        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(Color.Black),
        ) {
            if (running) {
                AndroidView(
                    factory = { ctx ->
                        var lastX = 0
                        var lastY = 0
                        ShowerSurfaceView(ctx).apply {
                            bindController(controller)
                            setOnTouchListener { v, event ->
                                val size = controller.getVideoSize() ?: return@setOnTouchListener false
                                val x = (event.x / v.width.coerceAtLeast(1) * size.first).toInt()
                                val y = (event.y / v.height.coerceAtLeast(1) * size.second).toInt()
                                when (event.actionMasked) {
                                    android.view.MotionEvent.ACTION_DOWN -> {
                                        lastX = x
                                        lastY = y
                                        scope.launch { controller.touchDown(x, y) }
                                        true
                                    }
                                    android.view.MotionEvent.ACTION_MOVE -> {
                                        if (Math.abs(x - lastX) > 8 || Math.abs(y - lastY) > 8) {
                                            lastX = x
                                            lastY = y
                                            scope.launch { controller.touchMove(x, y) }
                                        }
                                        true
                                    }
                                    android.view.MotionEvent.ACTION_UP -> {
                                        scope.launch { controller.touchUp(x, y) }
                                        true
                                    }
                                    else -> false
                                }
                            }
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Column(
                    Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(Icons.Rounded.PlayArrow, null, tint = Color.White.copy(alpha = 0.5f), modifier = Modifier.size(56.dp))
                    Spacer(Modifier.height(8.dp))
                    Text("Shower 虚拟显示", color = Color.White.copy(alpha = 0.75f))
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "需要 Shizuku（或 root）提供 shell 身份",
                        color = Color.White.copy(alpha = 0.45f),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }

        Surface(color = Color(0xFF111820)) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (running) {
                    PrimaryButton("停止", accent = Color(0xFFE05A5A)) { stopScreen() }
                    PrimaryButton("截图") { shot() }
                } else {
                    PrimaryButton(if (busy) "启动中…" else "启动虚拟屏") { startScreen() }
                }
                Spacer(Modifier.weight(1f))
                Text(
                    spec.label,
                    color = Color.White.copy(alpha = 0.45f),
                    style = MaterialTheme.typography.labelSmall,
                )
                Text(
                    if (DeviceShell.hasShizuku()) "Shizuku ✓" else if (DeviceShell.hasRoot()) "root ✓" else "无 shell 通道",
                    color = Color.White.copy(alpha = 0.5f),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }

    screenshot?.let { bitmap ->
        Dialog(onDismissRequest = { screenshot = null }) {
            Column(
                Modifier.background(Color(0xFF111820), RoundedCornerShape(14.dp)).padding(10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Image(bitmap, contentDescription = "截图", modifier = Modifier.fillMaxWidth())
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
                    PrimaryButton("关闭") { screenshot = null }
                }
            }
        }
    }

    if (displayDialog) {
        DisplaySpecDialog(
            current = spec,
            running = running,
            onDismiss = { displayDialog = false },
            onApply = { restartWith(it) },
        )
    }

    // 选一个 App 启动到虚拟屏（Operit 的做法：空显示只有黑屏，必须塞 Activity 进去）
    if (appPicker) {
        Dialog(onDismissRequest = { appPicker = false }) {
            Column(
                Modifier
                    .background(Color(0xFF111820), RoundedCornerShape(16.dp))
                    .padding(vertical = 12.dp),
            ) {
                Text(
                    "在虚拟屏里启动",
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                Text(
                    "虚拟显示是空白的，选一个 App 才有画面。",
                    color = Color.White.copy(alpha = 0.55f),
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                )
                Spacer(Modifier.height(8.dp))
                androidx.compose.foundation.lazy.LazyColumn(Modifier.height(420.dp)) {
                    items(apps, key = { it.packageName }) { app ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { launchInScreen(app) }
                                .padding(horizontal = 16.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            AppIcon(app)
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text(app.label, color = Color.White, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                                Text(
                                    app.packageName,
                                    color = Color.White.copy(alpha = 0.4f),
                                    style = MaterialTheme.typography.labelSmall,
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.End) {
                    PrimaryButton("取消") { appPicker = false }
                }
            }
        }
    }
}

/** 应用列表项（图标在 IO 线程转 bitmap，列表最多几十个，不做缓存池）。 */
private data class AppEntry(val packageName: String, val label: String, val activity: String)

@Composable
private fun AppIcon(app: AppEntry) {
    val context = LocalContext.current
    val bitmap = remember(app.packageName) {
        runCatching {
            val pm = context.packageManager
            pm.getApplicationIcon(app.packageName)
                .toBitmap(96, 96)
                .asImageBitmap()
        }.getOrNull()
    }
    if (bitmap != null) {
        Image(bitmap, null, modifier = Modifier.size(34.dp))
    } else {
        Box(Modifier.size(34.dp).background(Color(0xFF2A3342), RoundedCornerShape(9.dp)))
    }
}

@Composable
private fun DisplaySpecDialog(
    current: VirtualDisplaySpec,
    running: Boolean,
    onDismiss: () -> Unit,
    onApply: (VirtualDisplaySpec) -> Unit,
) {
    var selected by remember { mutableStateOf(current) }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .background(Color(0xFF111820), RoundedCornerShape(18.dp))
                .padding(vertical = 14.dp),
        ) {
            Text(
                "虚拟屏尺寸",
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 18.dp),
            )
            Text(
                if (running) "修改会立刻重建虚拟屏，当前 App 会被关掉。" else "重启虚拟屏后生效。",
                color = Color.White.copy(alpha = 0.55f),
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 3.dp),
            )
            Spacer(Modifier.height(6.dp))
            VirtualDisplaySpec.presets.forEach { (preset, label) ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { selected = preset }
                        .padding(horizontal = 12.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = selected == preset, onClick = { selected = preset })
                    Text(label, color = Color.White.copy(alpha = 0.9f), style = MaterialTheme.typography.bodyMedium)
                }
            }
            Spacer(Modifier.height(6.dp))
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                PrimaryButton("取消", accent = Color(0xFF26303C)) { onDismiss() }
                Spacer(Modifier.width(8.dp))
                PrimaryButton(if (running) "重建" else "保存") { onApply(selected) }
            }
        }
    }
}

@Composable
private fun IconButtonBox(icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    Box(Modifier.size(38.dp).pointerInput(Unit) { detectTapGestures { onClick() } }, contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = Color.White.copy(alpha = 0.85f), modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun PrimaryButton(text: String, accent: Color = Color(0xFF2F6FE0), onClick: () -> Unit) {
    Surface(color = accent, shape = RoundedCornerShape(10.dp)) {
        Box(
            Modifier
                .pointerInput(Unit) { detectTapGestures { onClick() } }
                .padding(horizontal = 18.dp, vertical = 9.dp),
        ) {
            Text(text, color = Color.White, style = MaterialTheme.typography.labelLarge)
        }
    }
}

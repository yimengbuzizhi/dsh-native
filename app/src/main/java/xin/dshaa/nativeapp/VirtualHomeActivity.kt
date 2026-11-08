package xin.dshaa.nativeapp

import android.graphics.Bitmap
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import xin.dshaa.nativeapp.device.shower.VirtualScreenManager
import xin.dshaa.nativeapp.device.shower.VirtualScreenSession
import xin.dshaa.ui.design.theme.DshTheme

/**
 * 虚拟屏自己的「桌面」（2026-11-07）。
 *
 * 为什么不直接发 KEYCODE_HOME：Android 15 的 PhoneWindowManager 把 HOME 当全局键处理，
 * 即使 KeyEvent.setDisplayId(13) + InputManager.injectInputEvent 也只会把**主屏**切到桌面。
 * 所以 Home 语义改成在虚拟 display 上启动这个 Activity：只动虚拟屏，主屏不受影响。
 *
 * 它同时补上了「虚拟屏启动后是黑屏」：AI/用户看到的第一屏就是可点的应用列表。
 */
class VirtualHomeActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            DshTheme(darkTheme = true) {
                VirtualHomeScreen(onClose = { finish() })
            }
        }
    }
}

@Composable
private fun VirtualHomeScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val view = LocalView.current
    val displayId = remember { view.display?.displayId ?: 0 }
    var apps by remember { mutableStateOf<List<VirtualScreenManager.LaunchableApp>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var lastError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.IO) {
            runCatching { VirtualScreenManager.launchableApps(context) }.getOrDefault(emptyList())
        }
        loading = false
    }

    BackHandler {
        // 桌面不吃返回键：返回会让虚拟 display 变成黑屏。
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF0B0F14))
            .statusBarsPadding(),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    "虚拟桌面",
                    color = Color.White,
                    fontSize = 26.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    "display $displayId · 点应用直接在这个屏幕里打开",
                    color = Color.White.copy(alpha = 0.5f),
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            Text(
                "关闭",
                color = Color.White.copy(alpha = 0.65f),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .clickable { onClose() }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }

        lastError?.let { error ->
            Text(
                error,
                color = Color(0xFFE05A5A),
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(horizontal = 22.dp, vertical = 4.dp),
            )
        }

        if (loading) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text("正在读取应用…", color = Color.White.copy(alpha = 0.5f))
            }
        } else if (apps.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text("没有可启动的应用", color = Color.White.copy(alpha = 0.5f))
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(4),
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                items(apps, key = { it.packageName }) { app ->
                    AppTile(app) {
                        scope.launch {
                            lastError = null
                            val ok = runCatching { VirtualScreenSession.controller.launchApp(app.packageName) }
                                .getOrDefault(false)
                            if (ok) {
                                context.getSharedPreferences("dsh", 0).edit()
                                    .putString("vs_last_app", app.packageName)
                                    .apply()
                                VirtualScreenSession.markStarted(
                                    VirtualScreenSession.controller.getVideoSize(),
                                    app.label,
                                    VirtualScreenSession.spec,
                                )
                                onClose()
                            } else {
                                lastError = "启动 ${app.label} 失败"
                                Toast.makeText(context, "启动 ${app.label} 失败", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AppTile(app: VirtualScreenManager.LaunchableApp, onClick: () -> Unit) {
    val context = LocalContext.current
    var bitmap by remember(app.packageName) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(app.packageName) {
        bitmap = withContext(Dispatchers.IO) {
            runCatching {
                context.packageManager.getApplicationIcon(app.packageName)
                    .toBitmap(128, 128, Bitmap.Config.ARGB_8888)
                    .asImageBitmap()
            }.getOrNull()
        }
    }
    Column(
        Modifier
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp, horizontal = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(58.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Color.White.copy(alpha = 0.06f)),
            contentAlignment = Alignment.Center,
        ) {
            val icon = bitmap
            if (icon != null) {
                Image(
                    icon,
                    contentDescription = app.label,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.size(54.dp).clip(RoundedCornerShape(14.dp)),
                )
            }
        }
        Spacer(Modifier.height(7.dp))
        Text(
            app.label,
            color = Color.White.copy(alpha = 0.86f),
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

package xin.dshaa.nativeapp.ui.device

import android.content.Intent
import android.provider.Settings
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import xin.dshaa.nativeapp.device.a11y.A11y
import xin.dshaa.ui.design.theme.DshTheme2

/**
 * 无障碍页：服务状态、去系统设置启用、当前窗口节点树预览。
 * 这是后续 `android_*` 设备工具的数据源（读屏/点击/手势/截图）。
 */
@Composable
fun AccessibilityScreen(onBack: () -> Unit) {
    val c = DshTheme2.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var ready by remember { mutableStateOf(A11y.isReady()) }
    var dump by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().background(c.background)) {
        Row(
            // statusBarsPadding：之前顶栏被内容顶进状态栏（用户 23:00：无障碍页内容过于靠上、遮挡状态栏）
            Modifier
                .fillMaxWidth()
                .background(c.surface)
                .statusBarsPadding()
                .padding(horizontal = 8.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(40.dp).clickable { onBack() }, contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.ArrowBack, "返回", tint = c.text)
            }
            Column(Modifier.weight(1f)) {
                Text("无障碍", style = MaterialTheme.typography.titleLarge, color = c.text, fontWeight = FontWeight.SemiBold)
                Text("读屏 / 点击 / 手势 / 截图", style = MaterialTheme.typography.labelSmall, color = c.faint)
            }
            Icon(
                if (ready) Icons.Rounded.CheckCircle else Icons.Rounded.Warning,
                null,
                tint = if (ready) c.success else c.warn,
            )
        }

        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            Column(Modifier.fillMaxWidth().background(c.surface, RoundedCornerShape(16.dp)).padding(14.dp)) {
                Text(
                    if (ready) "服务已连接，能力对模型可用" else "服务未启用：需要手动开启（系统限制，不能代开）",
                    style = MaterialTheme.typography.bodyMedium,
                    color = c.text,
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Action("打开系统设置") {
                        context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                    Action("刷新状态") { ready = A11y.isReady() }
                    Action(if (loading) "读取中…" else "读取当前界面") {
                        if (loading) return@Action
                        loading = true
                        scope.launch {
                            dump = withContext(Dispatchers.Default) { A11y.dump() }
                            loading = false
                            ready = A11y.isReady()
                        }
                    }
                }
            }

            Spacer(Modifier.height(14.dp))
            dump?.let { text ->
                Column(Modifier.fillMaxWidth().background(c.surface, RoundedCornerShape(16.dp)).padding(12.dp)) {
                    Text("当前窗口节点树", style = MaterialTheme.typography.labelMedium, color = c.faint)
                    Spacer(Modifier.height(8.dp))
                    Text(text, style = MaterialTheme.typography.labelSmall, color = c.sub, fontFamily = FontFamily.Monospace)
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun Action(text: String, onClick: () -> Unit) {
    val c = DshTheme2.colors
    Box(
        Modifier.background(c.brandSoft, RoundedCornerShape(10.dp)).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(text, style = MaterialTheme.typography.labelMedium, color = c.brand)
    }
}

package xin.dshaa.nativeapp.ui.settings

import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebViewDatabase
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import xin.dshaa.ui.shellapi.data.SettingsStore
import xin.dshaa.ui.design.component.AvatarLoader
import xin.dshaa.ui.design.component.SettingsCard
import xin.dshaa.ui.design.component.SettingsInfoBanner
import xin.dshaa.ui.design.component.SettingsSectionHeader
import xin.dshaa.ui.design.theme.DshTheme2
import java.io.File

/**
 * 隐私与数据清理（M73）。
 *
 * 原则：**只清「可再生」的东西**，绝不碰用户的资产——
 * - 会清：WebView 缓存 / Cookie / 本地存储（插件窗口页面的 localStorage 也在这里）、
 *   cacheDir 临时文件（导入插件时的临时 zip、截图缓存等）、头像缓存（内存里的 Bitmap 缓存）。
 * - 不清：API Key / 账号令牌（`shared_prefs/dsh.xml`）、插件本体（`files/plugins/`）、
 *   会话历史（`files/sessions` 等）、记忆库（`databases/memory.db`）、助手配置。
 *   要删会话去「对话历史管理」，要删插件去「插件」页。
 *
 * 每个动作都有二次确认 + 结果提示（清了多少字节），不做「一键清空全部」这种危险按钮。
 */
@Composable
fun PrivacyScreen(
    settings: SettingsStore,
    onBack: () -> Unit,
    onOpenSessions: () -> Unit,
) {
    val c = DshTheme2.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var usage by remember { mutableStateOf<Usage>(Usage()) }
    var notice by remember { mutableStateOf("") }
    var pending by remember { mutableStateOf<Action?>(null) }
    var busy by remember { mutableStateOf(false) }

    fun refresh() {
        scope.launch {
            usage = withContext(Dispatchers.IO) { measure(context) }
        }
    }

    LaunchedEffect(Unit) { refresh() }

    Column(Modifier.fillMaxSize().background(c.background)) {
        Row(
            Modifier.fillMaxWidth().background(c.surface).statusBarsPadding().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(40.dp).clip(CircleShape).clickable { onBack() }, contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.ArrowBack, "返回", tint = c.text, modifier = Modifier.size(23.dp))
            }
            Spacer(Modifier.width(4.dp))
            Text("隐私与数据清理", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, color = c.text)
        }

        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp)) {

            SettingsSectionHeader(icon = Icons.Rounded.CleaningServices, title = "占用情况")
            SettingsCard {
                UsageRow("网页缓存与本地存储", usage.webView, "插件窗口页面、登录页、模型页的 WebView 数据")
                UsageRow("临时文件（cache）", usage.cache, "导入插件时解出来的临时 zip、截图临时文件")
                UsageRow("插件本体", usage.plugins, "files/plugins · 删插件请到「插件」页", keep = true)
                UsageRow("会话历史", usage.sessions, "对话记录 · 删除请用「对话历史管理」", keep = true)
                UsageRow("记忆库", usage.memory, "databases/memory.db · 记忆库页面管理", keep = true)
            }

            Spacer(Modifier.height(16.dp))
            SettingsSectionHeader(icon = Icons.Rounded.DeleteSweep, title = "可以清理")
            SettingsCard {
                ActionRow(
                    title = "清理网页缓存与 Cookie",
                    subtitle = "清 WebView 缓存 / Cookie / localStorage（插件窗口会重新登录/重置偏好）",
                    onClick = { pending = Action.WebView },
                )
                ActionRow(
                    title = "清理临时文件",
                    subtitle = "清空 cacheDir（不动插件、会话、记忆、设置）",
                    onClick = { pending = Action.Cache },
                )
                ActionRow(
                    title = "清空头像图片缓存",
                    subtitle = "只清内存里的 Bitmap 缓存，磁盘上的头像还在",
                    onClick = {
                        AvatarLoader.clear()
                        notice = "已清空头像内存缓存"
                        refresh()
                    },
                )
                ActionRow(
                    title = "清理全部临时数据（上面两项一起）",
                    subtitle = "网页缓存 + 临时文件，最省事的一档",
                    onClick = { pending = Action.Both },
                )
            }

            Spacer(Modifier.height(16.dp))
            SettingsSectionHeader(icon = Icons.Rounded.Lock, title = "不会动的东西")
            SettingsCard {
                Text(
                    "API Key / 账号令牌（shared_prefs/dsh.xml）\n" +
                        "插件本体（files/plugins/）与插件配置\n" +
                        "会话历史、记忆库、工作区文件\n" +
                        "助手配置（角色卡）与技能",
                    style = MaterialTheme.typography.bodySmall,
                    color = c.sub,
                    lineHeight = MaterialTheme.typography.bodySmall.fontSize * 1.7f,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "要删会话 → 对话历史管理；要删插件 → 插件页卸载；要清记忆 → 记忆库。",
                    style = MaterialTheme.typography.labelSmall,
                    color = c.faint,
                )
                Spacer(Modifier.height(10.dp))
                Surface(
                    onClick = onOpenSessions,
                    shape = RoundedCornerShape(12.dp),
                    color = c.surfaceAlt,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(Modifier.padding(horizontal = 12.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.History, null, tint = c.sub, modifier = Modifier.size(19.dp))
                        Spacer(Modifier.width(10.dp))
                        Text("打开对话历史管理", style = MaterialTheme.typography.titleSmall, color = c.text)
                    }
                }
            }

            if (notice.isNotBlank()) {
                Spacer(Modifier.height(12.dp))
                SettingsInfoBanner(notice)
            }
            SettingsInfoBanner(
                "这些清理都是可再生的：清完插件窗口要重新加载一次，网页缓存会自己重新长回来；" +
                    "会话、插件、密钥、角色卡一个都不动。",
            )
            Spacer(Modifier.height(24.dp))
        }
    }

    pending?.let { action ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { if (!busy) pending = null },
            containerColor = c.surface,
            title = { Text(action.title) },
            text = { Text(action.detail) },
            confirmButton = {
                androidx.compose.material3.TextButton(
                    onClick = {
                        if (busy) return@TextButton
                        busy = true
                        scope.launch {
                            val freed = withContext(Dispatchers.IO) { runAction(context, action, settings) }
                            busy = false
                            pending = null
                            notice = "已清理 " + formatBytes(freed)
                            refresh()
                        }
                    },
                ) { Text(if (busy) "清理中…" else "确定清理") }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { if (!busy) pending = null }) { Text("取消") }
            },
        )
    }
}

private enum class Action(val title: String, val detail: String) {
    WebView("清理网页缓存与 Cookie", "会清空 WebView 缓存、Cookie 和 localStorage。插件窗口的偏好（例如宠物位置）会重置，重新打开即可。"),
    Cache("清理临时文件", "会清空 cacheDir。导入插件的临时 zip、截图临时文件会没，插件本身和会话不受影响。"),
    Both("清理全部临时数据", "网页缓存 + Cookie + localStorage + cacheDir 全部清掉。插件、会话、密钥、角色卡都不动。"),
}

private data class Usage(
    val webView: Long = -1,
    val cache: Long = 0,
    val plugins: Long = 0,
    val sessions: Long = 0,
    val memory: Long = 0,
)

private fun measure(context: android.content.Context): Usage {
    fun size(dir: File?): Long {
        if (dir == null || !dir.exists()) return 0
        return runCatching {
            dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        }.getOrDefault(0)
    }
    val files = context.filesDir
    return Usage(
        // WebView 的数据在 app_webview 下，没权限直接读时给 -1（界面上显示「—」）
        webView = size(File(context.dataDir, "app_webview")).takeIf { it > 0 } ?: -1,
        cache = size(context.cacheDir),
        plugins = size(File(files, "plugins")),
        sessions = size(File(files, "sessions")),
        memory = size(File(context.dataDir, "databases")),
    )
}

private fun runAction(context: android.content.Context, action: Action, settings: SettingsStore): Long {
    var freed = 0L
    val clearWeb = action == Action.WebView || action == Action.Both
    val clearCache = action == Action.Cache || action == Action.Both
    if (clearWeb) {
        freed += measure(context).webView.coerceAtLeast(0)
        runCatching { WebStorage.getInstance().deleteAllData() }
        runCatching { CookieManager.getInstance().removeAllCookies(null) }
        runCatching { CookieManager.getInstance().flush() }
        runCatching { WebViewDatabase.getInstance(context).clearFormData() }
        runCatching { WebViewDatabase.getInstance(context).clearHttpAuthUsernamePassword() }
    }
    if (clearCache) {
        freed += measure(context).cache
        runCatching { context.cacheDir.listFiles()?.forEach { it.deleteRecursively() } }
    }
    AvatarLoader.clear()
    return freed
}

private fun formatBytes(bytes: Long): String = when {
    bytes <= 0 -> "0 B"
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> String.format("%.1f KB", bytes / 1024.0)
    else -> String.format("%.1f MB", bytes / 1024.0 / 1024.0)
}

@Composable
private fun UsageRow(title: String, bytes: Long, subtitle: String, keep: Boolean = false) {
    val c = DshTheme2.colors
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = c.text)
            Text(subtitle, style = MaterialTheme.typography.labelSmall, color = c.faint, maxLines = 2)
        }
        Text(
            if (bytes < 0) "—" else formatBytes(bytes),
            style = MaterialTheme.typography.titleSmall,
            color = if (keep) c.faint else c.sub,
        )
    }
}

@Composable
private fun ActionRow(title: String, subtitle: String, onClick: () -> Unit) {
    val c = DshTheme2.colors
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable { onClick() }
            .padding(vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.PhotoLibrary, null, tint = c.sub, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = c.text)
            Text(subtitle, style = MaterialTheme.typography.labelSmall, color = c.faint)
        }
    }
}

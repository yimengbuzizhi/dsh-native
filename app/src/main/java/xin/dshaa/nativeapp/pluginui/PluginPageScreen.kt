package xin.dshaa.nativeapp.pluginui

import android.annotation.SuppressLint
import android.graphics.Color
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import xin.dshaa.core.plugins.PluginClientEntry
import xin.dshaa.core.plugins.PluginRecord
import xin.dshaa.nativeapp.data.PluginStore
import xin.dshaa.ui.design.theme.DshTheme
import xin.dshaa.ui.design.theme.DshTheme2
import java.io.ByteArrayInputStream
import java.io.File

/**
 * M84-G：**插件整页**——把插件自己的 `dsh.client` 跑成全屏页面（不是悬浮窗）。
 *
 * - 页面由插件自己的 client.js 提供；宿主不按插件 id 画原生 UI；
 * - `ctx.ui.registerPage({path})` 或 `registerRoute({..., page:true})` 登记；
 * - 路由 id 与宿主内置页同名时，[App] 用这个 Composable 替换内置页（例如 About）。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun PluginPageScreen(
    record: PluginRecord,
    title: String,
    onBack: () -> Unit,
) {
    val c = DshTheme2.colors
    val entry = remember(record.path) { PluginClientEntry.resolve(File(record.path)) }
    BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize().background(c.background)) {
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().background(c.surface).padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Rounded.ArrowBack, contentDescription = "返回", tint = c.text)
            }
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = c.text)
                Text(
                    "插件页面 · ${record.name}",
                    style = MaterialTheme.typography.labelSmall,
                    color = c.faint,
                )
            }
        }
        if (entry !is PluginClientEntry.Result.Resolved) {
            Text(
                "这个插件没有可运行的整页：${(entry as PluginClientEntry.Result.Unsupported).reason}",
                Modifier.padding(20.dp),
                color = c.warn,
            )
            return@Column
        }
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                WebView(context).apply {
                    setBackgroundColor(Color.TRANSPARENT)
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.allowFileAccess = false
                    webViewClient = PluginPageClient(record, context.applicationContext.assets)
                    loadUrl(hostUrl(record, entry.entry))
                }
            },
        )
    }
}

/** 插件整页的 Activity 入口（插件详情里「以整页打开」用）。 */
class PluginPageActivity : androidx.activity.ComponentActivity() {
    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        val pluginId = intent.getStringExtra(EXTRA_PLUGIN_ID).orEmpty()
        val routeId = intent.getStringExtra(EXTRA_ROUTE_ID).orEmpty()
        val record = PluginStore(applicationContext).list().firstOrNull { it.id == pluginId }
        if (record == null) {
            finish()
            return
        }
        val route = record.routes.firstOrNull { it.id == routeId }
        setContent {
            DshTheme {
                PluginPageScreen(
                    record = record,
                    title = route?.title ?: record.name,
                    onBack = { finish() },
                )
            }
        }
    }

    companion object {
        private const val EXTRA_PLUGIN_ID = "plugin_id"
        private const val EXTRA_ROUTE_ID = "route_id"

        /** 打开插件整页；record/route 不存在时返回 false。 */
        fun open(context: android.content.Context, record: PluginRecord, routeId: String): Boolean {
            if (record.routes.none { it.id == routeId }) return false
            val intent = android.content.Intent(context, PluginPageActivity::class.java)
                .putExtra(EXTRA_PLUGIN_ID, record.id)
                .putExtra(EXTRA_ROUTE_ID, routeId)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            return true
        }
    }
}

private const val HOST = "dsh-plugin.local"

private fun hostUrl(record: PluginRecord, entry: String): String =
    "https://$HOST/host/index.html?plugin=${Uri.encode(record.id)}" +
        "&entry=${Uri.encode(entry)}&name=${Uri.encode(record.name)}"

private class PluginPageClient(
    private val record: PluginRecord,
    private val assets: android.content.res.AssetManager,
) : WebViewClient() {
    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
        val url = request?.url ?: return null
        if (url.host != HOST) return null
        val path = url.path.orEmpty()
        return when {
            path.startsWith("/host/") -> serveAsset(path.removePrefix("/host/"))
            path.startsWith("/plugin/") -> servePlugin(path.removePrefix("/plugin/"))
            else -> null
        }
    }

    private fun serveAsset(relative: String): WebResourceResponse? {
        val clean = relative.substringBefore('?').substringBefore('#')
        if (clean.contains("..")) return notFound()
        return runCatching {
            response(assets.open("client-host/$clean"), mimeOf(clean))
        }.getOrElse { notFound() }
    }

    private fun servePlugin(rest: String): WebResourceResponse {
        val clean = rest.substringBefore('?').substringBefore('#')
        if (clean.isBlank() || clean.contains("..")) return notFound()
        val root = File(record.path).canonicalFile
        var file = File(root, clean).canonicalFile
        if (file.path != root.path && !file.path.startsWith(root.path + File.separator)) return notFound()
        if (!file.isFile && clean.startsWith("media/")) {
            file = File(root, clean.removePrefix("media/")).canonicalFile
        }
        if (!file.isFile) return notFound()
        return response(file.inputStream(), mimeOf(file.name))
    }

    private fun response(stream: java.io.InputStream, mime: String) = WebResourceResponse(
        mime,
        if (mime.startsWith("text/") || mime.contains("javascript") || mime.contains("json")) "UTF-8" else null,
        200,
        "OK",
        mapOf("Access-Control-Allow-Origin" to "*", "Cache-Control" to "no-cache"),
        stream,
    )

    private fun notFound() = WebResourceResponse(
        "text/plain",
        "UTF-8",
        404,
        "Not Found",
        mapOf("Access-Control-Allow-Origin" to "*"),
        ByteArrayInputStream("not found".toByteArray()),
    )

    private fun mimeOf(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "html", "htm" -> "text/html"
        "js", "mjs" -> "application/javascript"
        "css" -> "text/css"
        "json", "map" -> "application/json"
        "png" -> "image/png"
        "webp" -> "image/webp"
        "jpg", "jpeg" -> "image/jpeg"
        "gif" -> "image/gif"
        "svg" -> "image/svg+xml"
        "woff" -> "font/woff"
        "woff2" -> "font/woff2"
        "ttf" -> "font/ttf"
        "mp3" -> "audio/mpeg"
        "wav" -> "audio/wav"
        "mp4" -> "video/mp4"
        else -> "application/octet-stream"
    }
}

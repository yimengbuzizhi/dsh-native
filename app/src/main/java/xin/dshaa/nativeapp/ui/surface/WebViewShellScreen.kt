package xin.dshaa.nativeapp.ui.surface

import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import org.json.JSONObject
import xin.dshaa.nativeapp.chat.AppViewModel
import xin.dshaa.nativeapp.data.PluginShellHost
import xin.dshaa.ui.design.theme.DshTheme2

/**
 * M85-F：`shell/v1` 的第三种后端——**整屏 WebView**。
 *
 * 与 Surface 后端共用同一套桥：
 * - 宿主 → 壳：`shell.mount` / `shell.state` / `shell.action` / `shell.event`（协议在
 *   [PluginShellHost]，返回值是 `{html}` 而不是 `{tree}`）；
 * - 壳 → 宿主：WebView 里的 `DshWebShell` 转发到同一份 `surface.command` 白名单
 *   （[AppViewModel.shellCommand]），以及 `shell.action` 的通用 payload。
 *
 * 性能纪律：Surface 的 `bind` 在 WebView 里没有等价物，所以流式期间宿主每 500ms
 * 推一次 `shell.state`（重绘 HTML 片段），并在 `onPageFinished` / 结构变化时推；
 * 这是 v1 的已知限制（见 `docs/168`）。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebViewShellScreen(vm: AppViewModel, target: PluginShellHost.Target) {
    val c = DshTheme2.colors
    val host = vm.shellHost
    val bridge = remember(vm, host, target) { WebShellBridge(vm, host, target) }
    var pageReady by remember(target) { mutableStateOf(false) }
    DisposableEffect(bridge) {
        host.webContentReady = false
        onDispose { bridge.detach() }
    }
    val signature = shellSignature(vm)
    ShellAttachmentPickers(vm)

    LaunchedEffect(target) {
        if (vm.chat == null) vm.newChat()
        host.start(target)
    }
    // 结构变化：与 Surface 壳相同的签名，只在结构变化时 dispatch shell.state。
    LaunchedEffect(target) {
        snapshotFlow { shellSignature(vm) }
            .distinctUntilChanged()
            .collect { host.requestState() }
    }
    // 流式文本：WebView 没有 bind，只能整段重绘（v1 限制）。只有流式中才推。
    LaunchedEffect(target) {
        while (true) {
            if (vm.chat?.streaming == true) host.requestState()
            delay(500)
        }
    }
    // 最新 HTML 到达 / 页面就绪后推给 WebView（同一份 state 一起给，壳可只读不解析）。
    LaunchedEffect(host.html, pageReady, signature) {
        if (!pageReady) return@LaunchedEffect
        val html = host.html ?: return@LaunchedEffect
        bridge.push(html, vm.shellStateJson())
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(c.background)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        key(bridge) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                WebView(ctx).apply {
                    setBackgroundColor(Color.TRANSPARENT)
                    settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        allowFileAccess = false
                        allowContentAccess = false
                        javaScriptCanOpenWindowsAutomatically = false
                        setSupportMultipleWindows(false)
                        setSupportZoom(false)
                        builtInZoomControls = false
                        displayZoomControls = false
                        // 插件 HTML 不能绕过 net 权限直接加载远程资源（M85-F 安全边界）。
                        blockNetworkLoads = true
                        blockNetworkImage = true
                        mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                        cacheMode = WebSettings.LOAD_NO_CACHE
                    }
                    addJavascriptInterface(bridge, "dshNative")
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView, url: String?) {
                            pageReady = true
                            bridge.attach(view)
                        }

                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                            // 壳是整屏 UI，不允许导航离开（链接/重定向一律拦下）。
                            android.util.Log.w("DshWebShell", "blocked navigation ${request.url}")
                            return true
                        }
                    }
                    loadDataWithBaseURL(
                        "https://dsh.local/",
                        WEB_SHELL_DOC,
                        "text/html",
                        "utf-8",
                        null,
                    )
                    bridge.attach(this)
                }
            },
            update = { },
        )
        }

        val html = host.html
        if (html == null) {
            Column(
                Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
            ) {
                CircularProgressIndicator(color = c.brand)
                Spacer(Modifier.height(14.dp))
                Text(target.label, color = c.text, fontWeight = FontWeight.SemiBold)
                Text("WebView 界面由 ${target.pluginName} 提供", color = c.faint)
                host.error?.let { message ->
                    Spacer(Modifier.height(10.dp))
                    Text(message, color = c.danger, modifier = Modifier.padding(horizontal = 24.dp))
                    TextButton(onClick = { vm.setActiveShell(null) }) {
                        Text("回到原生界面", color = c.brand)
                    }
                }
            }
        }
    }
}

/**
 * WebView → 宿主的桥。
 *
 * - `command(name,args,id)`：与 Surface 壳同一个 `surface.command` 白名单（同步返回结果通过
 *   `window.__dshShell.commandResult(id,result)` 回给页面）；
 * - `action(action,value)`：页面里的按钮/输入把动作交给宿主，宿主 dispatch `shell.action`，
 *   插件返回的新 HTML 由 [PluginShellHost.html] 驱动重绘。
 */
private class WebShellBridge(
    private val vm: AppViewModel,
    private val host: PluginShellHost,
    private val target: PluginShellHost.Target,
) {
    private val main = Handler(Looper.getMainLooper())

    @Volatile
    private var webView: WebView? = null
    private var detached = false
    private var htmlRevision = 0L
    private var pushedHtml: String? = null
    private var confirmedRevision = -1L
    private var checkingRevision = -1L

    fun attach(view: WebView) {
        if (detached) return
        webView = view
        android.util.Log.i("DshWebShell", "attached target=${target.shellId}")
    }

    fun detach() {
        detached = true
        htmlRevision++
        val view = webView
        webView = null
        if (host.target == target) host.webContentReady = false
        view?.removeJavascriptInterface("dshNative")
        view?.stopLoading()
        view?.destroy()
    }

    /** 整段 HTML 片段 + state 推给页面（`#dsh-root` 的 innerHTML）。 */
    fun push(html: String, stateJson: String) {
        val view = webView ?: return
        if (detached || host.target != target || host.html != html) return
        if (pushedHtml != html) {
            pushedHtml = html
            htmlRevision++
            confirmedRevision = -1L
            checkingRevision = -1L
            host.webContentReady = false
        }
        view.evaluateJavascript(
            "window.__dshShell.render(${JSONObject.quote(html)}, $stateJson, $htmlRevision);",
            null,
        )
    }

    /** DOM 注入并跨过两帧后，再等 WebView renderer 同步；不把空外壳加载计作内容首帧。 */
    @JavascriptInterface
    fun rendered(revision: String?) {
        val renderedRevision = revision?.toLongOrNull() ?: return
        main.post {
            val view = webView ?: return@post
            if (detached || host.target != target || host.html != pushedHtml ||
                renderedRevision != htmlRevision || (confirmedRevision == renderedRevision && host.webContentReady) ||
                checkingRevision == renderedRevision) return@post
            checkingRevision = renderedRevision
            view.postVisualStateCallback(renderedRevision, object : WebView.VisualStateCallback() {
                override fun onComplete(requestId: Long) {
                    if (detached || webView !== view || host.target != target || host.html != pushedHtml ||
                        requestId != htmlRevision) return
                    confirmedRevision = requestId
                    checkingRevision = -1L
                    host.webContentReady = true
                    // 这是 renderer 同步证据，仍不是屏幕像素呈现时间。
                    android.util.Log.i("DshWebShell", "contentVisualReady revision=$requestId presentationConfirmed=false")
                }
            })
        }
    }

    @JavascriptInterface
    fun command(name: String, argsJson: String?, requestId: String?) {
        val id = requestId.orEmpty()
        main.post {
            if (detached || host.target != target) return@post
            val result = runCatching { vm.shellCommand(name, argsJson) }
                .getOrElse { JSONObject().put("ok", false).put("error", it.message ?: "宿主命令失败").toString() }
            // 命令可能只改草稿/队列/设置，HTML 相同也要更新页面里的只读 state。
            host.html?.let { push(it, vm.shellStateJson()) }
            val view = webView
            android.util.Log.i("DshWebShell", "command=$name id=$id -> ${result.take(160)}")
            view?.evaluateJavascript(
                "window.__dshShell.commandResult(${JSONObject.quote(id)}, $result);",
                null,
            )
        }
    }

    @JavascriptInterface
    fun action(action: String?, valueJson: String?) {
        val name = action.orEmpty()
        val payload = valueJson
        main.post {
            if (detached || host.target != target) return@post
            android.util.Log.i("DshWebShell", "action=$name payload=${payload?.length ?: 0}b")
            host.actionRaw(name, payload)
        }
    }
}

/** 固定外壳文档：只提供根节点、基础样式和桥；壳返回的 HTML 片段塞进 `#dsh-root`。 */
private const val WEB_SHELL_DOC = """
<!doctype html>
<html>
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover">
<style>
  :root { color-scheme: light dark; }
  html, body { margin: 0; padding: 0; background: transparent; color: #1b1b1f; }
  body { font-family: system-ui, -apple-system, "Noto Sans CJK SC", sans-serif; -webkit-text-size-adjust: 100%; }
  * { box-sizing: border-box; }
  button, input, textarea { font: inherit; }
  #dsh-root { min-height: 100vh; }
</style>
</head>
<body>
<div id="dsh-root"></div>
<script>
  window.__dshState = null;
  window.__dshCommandCallbacks = {};
  window.__dshShell = {
    render: function (html, state, revision) {
      window.__dshState = state;
      window.__dshHtmlRevision = revision;
      var root = document.getElementById('dsh-root');
      if (root && root.innerHTML !== html) { root.innerHTML = html; }
      window.dispatchEvent(new CustomEvent('dsh:state', { detail: state }));
      window.scrollTo(0, document.body.scrollHeight);
      requestAnimationFrame(function () {
        requestAnimationFrame(function () {
          if (window.__dshHtmlRevision === revision) {
            try { dshNative.rendered(String(revision)); } catch (e) {}
          }
        });
      });
    },
    commandResult: function (id, result) {
      var cb = window.__dshCommandCallbacks[id];
      if (typeof cb === 'function') {
        try { cb(result); } catch (e) {}
        delete window.__dshCommandCallbacks[id];
      }
    }
  };
  window.DshWebShell = {
    action: function (action, value) {
      try { dshNative.action(String(action || ''), JSON.stringify(value || {})); }
      catch (e) { console.error('action bridge failed', e); }
    },
    command: function (name, args, onResult) {
      var id = 'c' + (window.__dshCmdSeq = (window.__dshCmdSeq || 0) + 1);
      if (typeof onResult === 'function') { window.__dshCommandCallbacks[id] = onResult; }
      try { dshNative.command(String(name || ''), JSON.stringify(args || {}), id); }
      catch (e) { console.error('command bridge failed', e); }
    },
    state: function () { return window.__dshState; }
  };
  (function () {
    var last = -1;
    function tick() {
      var h = document.body ? document.body.scrollHeight : 0;
      if (h !== last) { last = h; window.scrollTo(0, h); }
      requestAnimationFrame(tick);
    }
    requestAnimationFrame(tick);
  })();
</script>
</body>
</html>
"""

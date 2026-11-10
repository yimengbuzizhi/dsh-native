package xin.dshaa.nativeapp.pluginui

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Region
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import xin.dshaa.nativeapp.MainActivity
import xin.dshaa.nativeapp.fish.BigFish
import xin.dshaa.nativeapp.fish.FishSnapshot
import xin.dshaa.nativeapp.fish.FishState
import java.io.ByteArrayInputStream
import java.io.File
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * DSH 客户端插件宿主（M70）：把插件**自己的** `dsh.client` 浏览器半侧跑起来。
 *
 * 和 M64–M66 的区别：以前是「按插件 id 写死一个页面」，这里是**通用**的——
 * 从 `files/plugins/<id>/` 读 `package.json` 解析 `dsh.client` 入口，载入插件自己的
 * client bundle，宿主只提供 DSH client runtime 的最小面：
 *
 * - `window.__ModuleLoader__`：收 entry → `factory(require)` → `apply(ctx)`；
 * - `require` 白名单：react / react-dom / react-dom/client / react/jsx-runtime；
 * - `ctx.get('sessions')`：BigFish 快照伪装成 DSH 会话面（subscribe 可监听）；
 * - `ctx.effect` / `ctx.on` / `ctx.slots`（最小实现，shell.overlay 渲染成整页层）；
 * - 本地请求拦截：`/host/…` → App assets；`/plugin/…` → 当前插件安装目录（只读，
 *   `.png` 找不到时回退 `.webp`，`media/` 前缀会被剥掉再找一次）。
 *
 * 窗口层只做窗口该做的事：透明悬浮窗、双指拖窗口（位置按插件落盘）、
 * 双指轻点收起、常驻通知可回到 App。
 */
class ClientPluginWindowService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var prefs: android.content.SharedPreferences
    private var root: OverlayRoot? = null
    private var webView: WebView? = null
    private var params: WindowManager.LayoutParams? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var collectJob: Job? = null
    private val main = Handler(Looper.getMainLooper())

    private var pluginId: String = ""
    private var pluginDir: String = ""
    private var pluginName: String = ""
    private var entryPath: String = ""

    /** 当前 turn 的工具调用时间（喂给 sessions 面的 nodes.time）。 */
    private val turnToolTimes = mutableListOf<Long>()
    private var turnError = false
    private var lastState: FishState? = null
    private var lastWorkingAt = 0L

    /**
     * 触摸命中区（M70）：插件 UI 真正占用的矩形并集，页面通过 `DSHNative.setTouchRegion` 上报。
     *
     * 透明悬浮窗默认整块吃触摸（用户反馈「鲸鱼娘旁边点击滑动没反应」）；用
     * [android.view.ViewTreeObserver.InternalInsetsInfo.TOUCHABLE_INSETS_REGION] 把窗口输入区域
     * 收窄到这几块，其余区域穿透到下层 App。页面还没上报时保持整窗可点（否则宠物也点不到）。
     */
    private val touchLock = Any()
    /** 插件 UI 的并集（相对渲染窗口的像素矩形）；null = 页面还没上报。 */
    private var touchUnion: Rect? = null
    /** 只负责接收触摸的小窗口：渲染窗口 FLAG_NOT_TOUCHABLE，触摸落在这一块后转发给 WebView。 */
    private var proxyView: View? = null
    private var proxyParams: WindowManager.LayoutParams? = null

    /**
     * 手势穿透开关（M72）。
     *
     * `true`：渲染窗口 `FLAG_NOT_TOUCHABLE` + proxy 小窗只接插件 UI 矩形，UI 以外的触摸落给下层
     * App（M70 的形状）。**代价**：Android 12+ 把手势穿透的悬浮窗当成「不可信遮挡」，直接把窗口
     * alpha 压到 0.8 —— 界面整体发灰、能透出下层内容（`dumpsys window` 里 `alpha=0.8` 就是它）。
     * `false`：窗口可触摸、alpha 正常，但**整个窗口**都吃触摸；适配层用 `setContentSize` 把窗口
     * 收到插件 UI 的实际包围盒，用几何实现穿透。有适配层的插件（`assets/client-host/adapters/`）
     * 默认走这一条。
     */
    private var passThrough = true

    @Volatile
    private var sessionCache: String = """{"running":false,"nodes":[],"pending":[],"sessionId":"dsh-native"}"""

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val id = intent?.getStringExtra(EXTRA_PLUGIN_ID)?.trim().orEmpty()
        val dir = intent?.getStringExtra(EXTRA_PLUGIN_DIR)?.trim().orEmpty()
        val entry = intent?.getStringExtra(EXTRA_ENTRY)?.trim().orEmpty()
        if (id.isBlank() || dir.isBlank() || entry.isBlank()) {
            Log.w(TAG, "missing extras, stop")
            stopSelf()
            return START_NOT_STICKY
        }
        if (root != null && (id != pluginId || dir != pluginDir || entry != entryPath)) {
            // 同一时间只留一个插件窗口：换插件先拆旧的
            removeOverlay()
        }
        pluginId = id
        pluginDir = dir
        entryPath = entry
        pluginName = intent?.getStringExtra(EXTRA_PLUGIN_NAME)?.trim().orEmpty().ifBlank { id }
        if (root == null && !showView()) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, buildNotification())
        if (!Settings.canDrawOverlays(this)) {
            Log.w(TAG, "no overlay permission, stop")
            stopSelf()
            return
        }
        prefs = getSharedPreferences("dsh", Context.MODE_PRIVATE)
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
    }

    override fun onDestroy() {
        collectJob?.cancel()
        scope.cancel()
        removeOverlay()
        super.onDestroy()
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun removeOverlay() {
        proxyView?.let { runCatching { windowManager.removeView(it) } }
        proxyView = null
        proxyParams = null
        root?.let { runCatching { windowManager.removeView(it) } }
        webView?.let { web ->
            runCatching { web.loadUrl("about:blank") }
            runCatching { web.removeJavascriptInterface(BRIDGE_NAME) }
            runCatching { web.destroy() }
        }
        webView = null
        root = null
        params = null
        synchronized(touchLock) { touchUnion = null }
    }

    private fun showView(): Boolean = try {
        // 有宿主适配层（assets/client-host/adapters/<id>.js）的插件默认走「窗口可触摸 + 适配层收窗口」
        // 这一条，界面才不会被 Android 12+ 压成 0.8 透明度。
        passThrough = !hasHostAdapter(pluginId)
        val widthPx = dp(WIDTH_DP)
        val heightPx = dp(HEIGHT_DP)
        val container = OverlayRoot(this)
        val web = buildWebView()
        container.addView(
            web,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        val lp = WindowManager.LayoutParams(
            widthPx,
            heightPx,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                // 手势穿透模式（没有适配层的插件）才加：插件 UI 以外的触摸落到下层 App。
                // 代价见 [applyPassThrough]：Android 12+ 会强制这种窗口 alpha=0.8。
                (if (passThrough) WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE else 0) or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = prefs.getInt(positionKey("x"), screenWidth() - widthPx - dp(6))
            // 默认把窗口底边抬到输入框上方：插件桌宠通常贴在窗口右下角，不然会压住输入框。
            y = prefs.getInt(positionKey("y"), screenHeight() - heightPx - dp(80))
            x = x.coerceIn(-widthPx + dp(60), screenWidth() - dp(60))
            y = y.coerceIn(0, screenHeight() - dp(80))
        }
        windowManager.addView(container, lp)
        root = container
        webView = web
        params = lp
        web.loadUrl(hostUrl())
        Log.i(TAG, "overlay plugin=$pluginId entry=$entryPath ${widthPx}x$heightPx at ${lp.x},${lp.y} passThrough=$passThrough")
        collectJob = scope.launch {
            BigFish.state.collectLatest { snapshot ->
                track(snapshot)
                pushSession()
            }
        }
        true
    } catch (error: Throwable) {
        Log.e(TAG, "add overlay failed: ${error.message}", error)
        false
    }

    // ---- WebView / 本地文件服务 ----

    private fun buildWebView(): WebView = WebView(this).apply {
        setBackgroundColor(Color.TRANSPARENT)
        setLayerType(View.LAYER_TYPE_HARDWARE, null)
        isVerticalScrollBarEnabled = false
        isHorizontalScrollBarEnabled = false
        overScrollMode = View.OVER_SCROLL_NEVER
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.mediaPlaybackRequiresUserGesture = false
        settings.cacheMode = WebSettings.LOAD_DEFAULT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false)
        }
        addJavascriptInterface(Bridge(), BRIDGE_NAME)
        webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                Log.i(TAG, "page finished: $url")
                main.postDelayed({ pushSession() }, 150)
                main.postDelayed({ pushSession() }, 900)
            }

            override fun onReceivedError(
                view: WebView?,
                errorCode: Int,
                description: String?,
                failingUrl: String?,
            ) {
                Log.e(TAG, "web error $errorCode $description $failingUrl")
            }

            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?,
            ): WebResourceResponse? {
                val url = request?.url ?: return null
                if (url.host != HOST) return null
                val path = url.path.orEmpty()
                return when {
                    path.startsWith("/host/") -> serveAsset(path.removePrefix("/host/"))
                    path.startsWith("/plugin/") -> servePluginFile(path.removePrefix("/plugin/"))
                    else -> null
                }
            }
        }
        if (isDebuggable()) WebView.setWebContentsDebuggingEnabled(true)
    }

    private fun hostUrl(): String {
        val encodedEntry = android.net.Uri.encode(entryPath)
        return "https://$HOST/host/index.html?plugin=${android.net.Uri.encode(pluginId)}" +
            "&entry=$encodedEntry&name=${android.net.Uri.encode(pluginName)}"
    }

    private fun serveAsset(relative: String): WebResourceResponse? {
        val clean = relative.substringBefore('?').substringBefore('#')
        if (clean.contains("..")) return notFound()
        return runCatching {
            val stream = assets.open("client-host/$clean")
            response(stream, mimeOf(clean))
        }.getOrElse { notFound() }
    }

    /**
     * `/plugin/<相对路径>`：只服务当前插件的安装目录（插件 id 可能有 `/`，所以路径里不带 id），
     * 防目录穿越。找不到时按插件常见布局回退：剥 `media/`、加 `assets/`/`public/`、`.png` → `.webp`。
     */
    private fun servePluginFile(rest: String): WebResourceResponse {
        val clean = rest.substringBefore('?').substringBefore('#')
        val rel = clean
        if (rel.isBlank() || rel.contains("..")) return notFound()
        val root = File(pluginDir).canonicalFile
        val candidates = LinkedHashSet<String>()
        candidates += rel
        if (rel.startsWith("media/")) candidates += rel.removePrefix("media/")
        candidates += "assets/$rel"
        candidates += "public/$rel"
        if (rel.endsWith(".png")) {
            candidates.toList().forEach { candidates += it.removeSuffix(".png") + ".webp" }
        }
        for (candidate in candidates) {
            val file = runCatching { File(root, candidate).canonicalFile }.getOrNull() ?: continue
            if (file.path != root.path && !file.path.startsWith(root.path + File.separator)) continue
            if (!file.isFile) continue
            return runCatching { response(file.inputStream(), mimeOf(file.name)) }.getOrElse { notFound() }
        }
        Log.w(TAG, "plugin file miss: $pluginId/$rel")
        return notFound()
    }

    private fun response(stream: java.io.InputStream, mime: String): WebResourceResponse =
        WebResourceResponse(
            mime,
            if (mime.startsWith("text/") || mime.contains("javascript") || mime.contains("json")) "UTF-8" else null,
            200,
            "OK",
            mapOf("Access-Control-Allow-Origin" to "*", "Cache-Control" to "no-cache"),
            stream,
        )

    private fun notFound(): WebResourceResponse = WebResourceResponse(
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
        "ogg" -> "audio/ogg"
        "mp4" -> "video/mp4"
        "wasm" -> "application/wasm"
        else -> "application/octet-stream"
    }

    // ---- 会话快照（BigFish → DSH sessions 面） ----

    private fun track(snapshot: FishSnapshot) {
        val prev = lastState
        val running = snapshot.state == FishState.THINKING ||
            snapshot.state == FishState.WORKING ||
            snapshot.state == FishState.WAITING
        val wasRunning = prev == FishState.THINKING ||
            prev == FishState.WORKING ||
            prev == FishState.WAITING
        if (running && !wasRunning) {
            turnToolTimes.clear()
            turnError = false
        }
        if (snapshot.state == FishState.WORKING && snapshot.updatedAt != lastWorkingAt) {
            lastWorkingAt = snapshot.updatedAt
            if (wasRunning) turnToolTimes.add(System.currentTimeMillis())
        }
        if (snapshot.state == FishState.ERROR) turnError = true
        lastState = snapshot.state
        sessionCache = sessionJson()
    }

    private fun sessionJson(): String {
        val snapshot = BigFish.state.value
        val running = snapshot.state == FishState.THINKING ||
            snapshot.state == FishState.WORKING ||
            snapshot.state == FishState.WAITING
        val nodes = JSONArray()
        turnToolTimes.takeLast(24).forEach { time ->
            nodes.put(JSONObject().put("kind", "tool-result").put("time", time).put("isError", false))
        }
        if (turnError) {
            nodes.put(
                JSONObject()
                    .put("kind", "turn-error")
                    .put("time", System.currentTimeMillis())
                    .put("isError", true),
            )
        }
        val pending = JSONArray()
        if (snapshot.state == FishState.WAITING) {
            pending.put(JSONObject().put("kind", "approval"))
        }
        return JSONObject()
            .put("running", running)
            .put("nodes", nodes)
            .put("pending", pending)
            .put("sessionId", "dsh-native")
            .put("state", snapshot.state.name)
            .put("caption", snapshot.message.take(120))
            // M66/M72：本轮最终回复与 token 用量，客户端插件（鲸鱼娘挂件）据此播报/折算功德。
            .put("reply", snapshot.reply ?: JSONObject.NULL)
            .put("replySeq", snapshot.replySeq)
            .put("tokens", snapshot.tokens)
            .put("tokensSeq", snapshot.tokensSeq)
            .toString()
    }

    private fun pushSession() {
        val web = webView ?: return
        if (root == null) return
        val quoted = JSONObject.quote(sessionCache)
        web.evaluateJavascript("window.__clientSession && window.__clientSession($quoted);", null)
    }

    // ---- 窗口位置 / 手势 ----

    private fun moveWindow(dx: Int, dy: Int) {
        val p = params ?: return
        val w = root?.width ?: 0
        val h = root?.height ?: 0
        p.x = (p.x + dx).coerceIn(-w + dp(60), screenWidth() - dp(60))
        p.y = (p.y + dy).coerceIn(0, screenHeight() - dp(80))
        runCatching { root?.let { windowManager.updateViewLayout(it, p) } }
        updateTouchProxy()
    }

    /**
     * 改窗口尺寸（M72）：插件适配层按自己的面板需要调 `DSHNative.setContentSize(w,h)`。
     *
     * **右下角固定**：窗口变大时往左上长，插件里 `position: fixed; right/bottom` 的挂件
     * 屏幕位置不动（否则一开设置面板鲸鱼娘就跳走）。尺寸夹在屏幕内。
     */
    private fun resizeWindow(newWidth: Int, newHeight: Int) {
        val p = params ?: return
        val view = root ?: return
        val width = newWidth.coerceIn(dp(120), screenWidth())
        val height = newHeight.coerceIn(dp(100), screenHeight())
        if (p.width == width && p.height == height) return
        p.x += p.width - width
        p.y += p.height - height
        p.width = width
        p.height = height
        p.x = p.x.coerceIn(-width + dp(60), screenWidth() - dp(60))
        p.y = p.y.coerceIn(0, screenHeight() - dp(80))
        runCatching { windowManager.updateViewLayout(view, p) }
            .onFailure { Log.w(TAG, "resize window failed: ${it.message}") }
        updateTouchProxy()
        Log.i(TAG, "resize plugin window ${width}x$height at ${p.x},${p.y}")
    }

    /**
     * 把页面报上来的插件 UI 并集做成一个**只接触摸的小窗口**，盖在渲染窗口的同一位置。
     *
     * 为什么不用 `TOUCHABLE_INSETS_REGION`：`ViewTreeObserver.InternalInsetsInfo` 是 @hide，
     * 编译期就拿不到（试过，直接 Unresolved reference）。所以用「渲染窗口 FLAG_NOT_TOUCHABLE +
     * proxy 窗口转发 MotionEvent」这一套公开 API：宠物/面板以外的区域完全穿透。
     */
    private fun updateTouchProxy() {
        if (root == null) return
        // 可触摸模式（适配层收窄窗口）：窗口自己就是命中区，不需要 proxy 小窗。
        if (!passThrough) {
            proxyView?.let { runCatching { windowManager.removeView(it) } }
            proxyView = null
            proxyParams = null
            return
        }
        val render = params ?: return
        val union = synchronized(touchLock) { touchUnion?.let { Rect(it) } }
        if (union == null || union.isEmpty) {
            proxyView?.let { runCatching { windowManager.removeView(it) } }
            proxyView = null
            proxyParams = null
            return
        }
        val pad = dp(2)
        val width = (union.width() + pad * 2).coerceAtLeast(dp(24))
        val height = (union.height() + pad * 2).coerceAtLeast(dp(24))
        val x = render.x + union.left - pad
        val y = render.y + union.top - pad
        val lp = proxyParams ?: WindowManager.LayoutParams(
            width,
            height,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }.also { proxyParams = it }
        lp.x = x
        lp.y = y
        lp.width = width
        lp.height = height
        val proxy = proxyView
        if (proxy == null) {
            val view = TouchProxyView(this)
            proxyView = view
            runCatching { windowManager.addView(view, lp) }
                .onFailure { Log.w(TAG, "add touch proxy failed: ${it.message}") }
        } else {
            runCatching { windowManager.updateViewLayout(proxy, lp) }
        }
    }

    /** 把 proxy 收到的触摸换算到渲染窗口坐标后直接发给 WebView。 */
    private fun forwardTouch(event: MotionEvent, forceCancel: Boolean = false) {
        val web = webView ?: return
        val render = params ?: return
        val proxy = proxyParams ?: return
        val copy = MotionEvent.obtain(event)
        copy.offsetLocation((proxy.x - render.x).toFloat(), (proxy.y - render.y).toFloat())
        if (forceCancel) copy.action = MotionEvent.ACTION_CANCEL
        runCatching { web.dispatchTouchEvent(copy) }
        copy.recycle()
    }

    /** 只承接「插件 UI 所在矩形」的触摸：单指转发给 WebView，双指 native 拖窗口/轻点收起。 */
    private inner class TouchProxyView(context: Context) : View(context) {

        private var twoFinger = false
        private var moved = false
        private var startAt = 0L
        private var downRawX = 0f
        private var downRawY = 0f
        private var lastRawX = 0f
        private var lastRawY = 0f

        init {
            setBackgroundColor(Color.TRANSPARENT)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    twoFinger = false
                    moved = false
                    forwardTouch(event)
                }
                MotionEvent.ACTION_POINTER_DOWN -> {
                    if (event.pointerCount >= 2 && !twoFinger) {
                        twoFinger = true
                        moved = false
                        startAt = SystemClock.uptimeMillis()
                        downRawX = event.rawX
                        downRawY = event.rawY
                        lastRawX = event.rawX
                        lastRawY = event.rawY
                        forwardTouch(event, forceCancel = true)
                    }
                }
                MotionEvent.ACTION_MOVE -> {
                    if (twoFinger && event.pointerCount >= 2) {
                        if (!moved && hypot(event.rawX - downRawX, event.rawY - downRawY) > dp(8f)) {
                            moved = true
                        }
                        if (moved) {
                            val dx = (event.rawX - lastRawX).roundToInt()
                            val dy = (event.rawY - lastRawY).roundToInt()
                            if (dx != 0 || dy != 0) moveWindow(dx, dy)
                        }
                        lastRawX = event.rawX
                        lastRawY = event.rawY
                    } else {
                        forwardTouch(event)
                    }
                }
                MotionEvent.ACTION_POINTER_UP,
                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL,
                -> {
                    if (twoFinger) {
                        val quick = SystemClock.uptimeMillis() - startAt < 350L
                        twoFinger = false
                        if (moved) {
                            savePosition()
                        } else if (quick) {
                            collapse()
                        }
                    } else {
                        forwardTouch(event)
                    }
                }
                else -> forwardTouch(event)
            }
            return true
        }
    }

    private fun savePosition() {
        val p = params ?: return
        prefs.edit().putInt(positionKey("x"), p.x).putInt(positionKey("y"), p.y).apply()
    }

    private fun positionKey(axis: String): String =
        "pluginwin_" + pluginId.replace(Regex("[^A-Za-z0-9_-]"), "_") + "_" + axis

    /** 适配层 KV 的 prefs 键；键名只允许字母数字与 `_ - .`，避免撑出奇怪的键。 */
    private fun kvKey(key: String): String? {
        val clean = key.trim()
        if (clean.isEmpty() || clean.length > 64) return null
        if (!clean.all { it.isLetterOrDigit() || it == '_' || it == '-' || it == '.' }) return null
        return "pluginhost_" + pluginId.replace(Regex("[^A-Za-z0-9_-]"), "_") + "_" + clean
    }

    private fun collapse() {
        Toast.makeText(this, "插件窗口已收起（插件页可再打开）", Toast.LENGTH_SHORT).show()
        stopSelf()
    }

    /**
     * 切换手势穿透（M72）：见 [passThrough] 的说明。适配层可以随时调，窗口立刻换模式。
     * 注意：Android 12+ 的 alpha 上限是在加窗口时按标志算的，从穿透切回可触摸不一定马上恢复
     * 1.0，所以优先级是「加窗口时就选对」（[hasHostAdapter]），这个接口只作兜底/调试。
     */
    private fun applyPassThrough(enabled: Boolean) {
        if (passThrough == enabled) return
        passThrough = enabled
        val p = params ?: return
        val view = root ?: return
        p.flags = if (enabled) {
            p.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        } else {
            p.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        }
        runCatching { windowManager.updateViewLayout(view, p) }
            .onFailure { Log.w(TAG, "switch passThrough failed: ${it.message}") }
        if (enabled) updateTouchProxy() else updateTouchProxy()
        Log.i(TAG, "passThrough=$enabled flags=${p.flags}")
    }

    /** 有没有这个插件的宿主适配层（assets/client-host/adapters/<归一化 id>.js）。 */
    private fun hasHostAdapter(id: String): Boolean {
        val safe = id.replace(Regex("[^A-Za-z0-9_-]"), "_")
        return runCatching {
            assets.open("client-host/adapters/$safe.js").use { true }
        }.getOrDefault(false)
    }

    private fun dp(value: Float): Int = (value * resources.displayMetrics.density).toInt()
    private fun dp(value: Int): Int = dp(value.toFloat())
    private fun screenWidth(): Int = resources.displayMetrics.widthPixels
    private fun screenHeight(): Int = resources.displayMetrics.heightPixels

    private fun isDebuggable(): Boolean =
        (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0

    /** 页面 ↔ 原生桥（JS 里 `window.DSHNative`）。 */
    private inner class Bridge {

        @JavascriptInterface
        fun state(): String = JSONObject()
            .put("session", JSONObject(sessionCache))
            .put("host", "dsh-native")
            .put("plugin", pluginId)
            .toString()

        @JavascriptInterface
        fun info(): String = JSONObject()
            .put("id", pluginId)
            .put("name", pluginName)
            .put("entry", entryPath)
            .toString()

        /**
         * 页面上报「插件 UI 真正占用的矩形」：`[[x,y,w,h], …]`（CSS px）。
         * native 换算成像素并集后作为窗口的触摸区域；空数组 = 回到整窗可点（页面坏了时还能收起）。
         */
        @JavascriptInterface
        fun setTouchRegion(json: String) {
            val density = resources.displayMetrics.density
            var union: Rect? = null
            runCatching {
                val array = JSONArray(json)
                for (index in 0 until array.length()) {
                    val item = array.optJSONArray(index) ?: continue
                    val left = (item.optDouble(0) * density).roundToInt()
                    val top = (item.optDouble(1) * density).roundToInt()
                    val right = ((item.optDouble(0) + item.optDouble(2)) * density).roundToInt()
                    val bottom = ((item.optDouble(1) + item.optDouble(3)) * density).roundToInt()
                    if (right <= left || bottom <= top) continue
                    val rect = Rect(left, top, right, bottom)
                    val current = union
                    if (current == null) union = rect else current.union(rect)
                }
            }
            synchronized(touchLock) { touchUnion = union }
            main.post { updateTouchProxy() }
        }

        @JavascriptInterface
        fun log(message: String) {
            Log.i(WEB_TAG, "[$pluginId] ${message.take(2000)}")
        }

        @JavascriptInterface
        fun ready() {
            Log.i(TAG, "plugin page ready: $pluginId")
            main.post { pushSession() }
        }

        @JavascriptInterface
        fun moveWindowBy(dx: Double, dy: Double) {
            val px = (dx * resources.displayMetrics.density).roundToInt()
            val py = (dy * resources.displayMetrics.density).roundToInt()
            if (px == 0 && py == 0) return
            main.post { moveWindow(px, py) }
        }

        @JavascriptInterface
        fun endDrag() {
            main.post { savePosition() }
        }

        /**
         * 插件宿主 KV（M72）：落 `dsh` prefs，键前缀带插件 id 的归一化写法——
         * 适配层（例如鲸鱼娘的功德账本）杀进程不丢，但**碰不到** App 自己的键（API key 等在同一个文件里）。
         */
        @JavascriptInterface
        fun kvGet(key: String): String {
            val clean = kvKey(key) ?: return ""
            return runCatching { prefs.getString(clean, "") ?: "" }.getOrDefault("")
        }

        @JavascriptInterface
        fun kvSet(key: String, value: String) {
            val clean = kvKey(key) ?: return
            runCatching {
                prefs.edit().putString(clean, value.take(KV_MAX_CHARS)).apply()
            }.onFailure { Log.w(TAG, "kvSet failed: ${it.message}") }
        }

        /** 适配层按自己的面板需要改窗口尺寸（CSS px ≈ dp）。 */
        @JavascriptInterface
        fun setContentSize(widthDp: Double, heightDp: Double) {
            val width = widthDp.roundToInt()
            val height = heightDp.roundToInt()
            if (width <= 0 || height <= 0) return
            main.post { resizeWindow(dp(width), dp(height)) }
        }

        /** 手势穿透开关（M72，见 [passThrough]）；有适配层的插件一般不用碰，默认就是可触摸。 */
        @JavascriptInterface
        fun setPassThrough(enabled: Boolean) {
            main.post { applyPassThrough(enabled) }
        }

        @JavascriptInterface
        fun collapse() {
            main.post { this@ClientPluginWindowService.collapse() }
        }

        @JavascriptInterface
        fun openApp() {
            main.post {
                startActivity(
                    Intent(this@ClientPluginWindowService, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        }
    }

    /** 单指全部交给 WebView（页面里再合成鼠标事件）；双指 native 拖窗，轻点收起。 */
    private inner class OverlayRoot(context: Context) : FrameLayout(context) {

        private var twoFinger = false
        private var moved = false
        private var startAt = 0L
        private var downRawX = 0f
        private var downRawY = 0f
        private var lastRawX = 0f
        private var lastRawY = 0f

        override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
            if (event.pointerCount >= 2) {
                if (!twoFinger) {
                    twoFinger = true
                    moved = false
                    startAt = SystemClock.uptimeMillis()
                    downRawX = event.rawX
                    downRawY = event.rawY
                    lastRawX = event.rawX
                    lastRawY = event.rawY
                }
                return true
            }
            return twoFinger
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_POINTER_DOWN,
                MotionEvent.ACTION_MOVE,
                -> {
                    if (twoFinger && event.pointerCount >= 2) {
                        if (!moved && hypot(event.rawX - downRawX, event.rawY - downRawY) > dp(8f)) {
                            moved = true
                        }
                        if (moved) {
                            val dx = (event.rawX - lastRawX).roundToInt()
                            val dy = (event.rawY - lastRawY).roundToInt()
                            if (dx != 0 || dy != 0) moveWindow(dx, dy)
                        }
                        lastRawX = event.rawX
                        lastRawY = event.rawY
                    }
                }
                MotionEvent.ACTION_POINTER_UP,
                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL,
                -> {
                    if (twoFinger) {
                        val quick = SystemClock.uptimeMillis() - startAt < 350L
                        twoFinger = false
                        if (moved) savePosition() else if (quick) collapse()
                    }
                }
                else -> Unit
            }
            return true
        }
    }

    private fun buildNotification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "插件窗口", NotificationManager.IMPORTANCE_MIN)
                    .apply { setShowBadge(false) },
            )
        }
        val pending = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle("插件窗口")
            .setContentText("正在运行插件自己的界面（双指拖动 / 双指轻点收起）")
            .setSmallIcon(android.R.drawable.sym_def_app_icon)
            .setContentIntent(pending)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
    }

    companion object {
        const val EXTRA_PLUGIN_ID = "plugin_id"
        const val EXTRA_PLUGIN_DIR = "plugin_dir"
        const val EXTRA_PLUGIN_NAME = "plugin_name"
        const val EXTRA_ENTRY = "plugin_entry"

        private const val TAG = "DshClientHost"
        private const val WEB_TAG = "DshClientHostWeb"
        private const val BRIDGE_NAME = "DSHNative"
        private const val HOST = "dsh-plugin.local"
        private const val CHANNEL_ID = "dsh-client-host"
        private const val NOTIFICATION_ID = 4010

        private const val WIDTH_DP = 300f
        private const val HEIGHT_DP = 380f
        private const val KV_MAX_CHARS = 262_144

        @Volatile
        private var running = false

        fun markRunning(value: Boolean) {
            running = value
        }

        fun isRunning(): Boolean = running
    }
}

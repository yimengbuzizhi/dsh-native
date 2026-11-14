package xin.dshaa.nativeapp.ui.boot

import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.Choreographer
import android.view.ViewTreeObserver
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import xin.dshaa.nativeapp.chat.AppViewModel
import xin.dshaa.nativeapp.data.LoadingSnapshotStore
import xin.dshaa.nativeapp.ui.DshApp
import xin.dshaa.ui.design.theme.DshTheme
import java.io.File

/** First content does not create AppViewModel. Actual draw releases initialization, never a timeout. */
@Composable
fun BootHost(
    activity: ComponentActivity,
    activityStartedAt: Long,
    createViewModel: () -> AppViewModel,
    onReady: (AppViewModel) -> Unit,
) {
    var state by remember { mutableStateOf(BootState()) }
    var snapshot by remember { mutableStateOf(LoadingSnapshotStore.defaultSnapshot()) }
    var snapshotReady by remember { mutableStateOf(false) }
    var loadingDrawn by remember { mutableStateOf(false) }
    var recoveryOpen by remember { mutableStateOf(false) }
    var recoveryDrawn by remember { mutableStateOf(false) }
    var bootTheme by remember { mutableStateOf("system") }
    var bootAccent by remember { mutableStateOf("blue") }
    var viewModel by remember { mutableStateOf<AppViewModel?>(null) }
    var acceptedReady by remember { mutableStateOf(false) }
    var retry by remember { mutableIntStateOf(0) }
    var useDefault by remember { mutableStateOf(false) }
    var shellDrawn by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val prepared = withContext(Dispatchers.IO) {
            runCatching {
                val prefs = activity.getSharedPreferences("dsh", 0)
                val selected = prefs.getString("dsh_loading", null)
                Triple(
                    LoadingSnapshotStore(File(activity.filesDir, "loading-snapshot.json")).read(selected ?: xin.dshaa.nativeapp.data.BuiltinLoadingPlugin.ID),
                    prefs.getString("theme_mode", "system") ?: "system",
                    prefs.getString("accent_id", "blue") ?: "blue",
                )
            }.getOrElse {
                Log.w("DshBoot", "snapshot read failed; using builtin loading")
                Triple(null, "system", "blue")
            }
        }
        if (!useDefault && prepared.first != null) snapshot = requireNotNull(prepared.first)
        bootTheme = prepared.second
        bootAccent = prepared.third
        snapshotReady = true
        Log.i("DshBoot", "loadingSnapshot owner=${snapshot.ownerPlugin} selection=${snapshot.selection} revision=${snapshot.revision.take(12)}")
    }
    LaunchedEffect(acceptedReady) {
        while (!acceptedReady) {
            state = state.copy(elapsedMs = SystemClock.elapsedRealtime() - activityStartedAt)
            delay(100)
        }
    }
    LaunchedEffect(snapshotReady, loadingDrawn, retry) {
        if (!snapshotReady || !loadingDrawn || acceptedReady || recoveryOpen) return@LaunchedEffect
        state = state.copy(status = BootStatus.LOADING, failure = null)
        try {
            // LaunchedEffect runs on Main. ViewModelProvider and its constructor stay on Main.
            val vm = viewModel ?: createViewModel().also { viewModel = it }
            vm.initializeRuntime(onPhase = { phase, message -> state = state.copy(phase = phase, message = message) })
            if (!vm.runtimeReady) error(vm.runtimeInitError ?: "初始化未完成")
            Log.i("DshBoot", "coreReady elapsedMs=${SystemClock.elapsedRealtime() - activityStartedAt}")
            onReady(vm)
            acceptedReady = true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            state = state.copy(status = BootStatus.FAILED, failure = error.message ?: error.javaClass.simpleName)
            Log.e("DshBoot", "coreFailed elapsedMs=${SystemClock.elapsedRealtime() - activityStartedAt} reason=${error.message}")
        }
    }

    if (acceptedReady && viewModel != null) {
        val vm = requireNotNull(viewModel)
        val shellContentReady = !vm.onboardingDone || vm.shellActive == null ||
            (if (vm.shellActive?.backend == "webview") vm.shellHost.webContentReady else vm.shellHost.tree != null)
        Box(Modifier.fillMaxSize()) {
            DshApp(vm)
            if (shellContentReady) FirstDrawSignal("shell", activityStartedAt) {
                if (!shellDrawn) {
                    shellDrawn = true
                    Log.i("DshBoot", "shellFirstDraw elapsedMs=${SystemClock.elapsedRealtime() - activityStartedAt}")
                    Log.i("DshBoot", "firstInteractive mode=shell elapsedMs=${SystemClock.elapsedRealtime() - activityStartedAt} presentationConfirmed=false")
                    activity.reportFullyDrawn()
                    Log.i("DshBoot", "reportFullyDrawn elapsedMs=${SystemClock.elapsedRealtime() - activityStartedAt}")
                }
            }
        }
    } else {
        val dark = when (bootTheme) { "dark" -> true; "light" -> false; else -> androidx.compose.foundation.isSystemInDarkTheme() }
        DshTheme(darkTheme = dark, accentId = bootAccent) {
            val vm = viewModel
            if (recoveryOpen && vm != null && vm.runtimeCoreReady) {
                BootRecoveryPanel(vm) { recoveryOpen = false; retry++ }
                FirstDrawSignal("recovery", activityStartedAt) {
                    if (!recoveryDrawn) {
                        recoveryDrawn = true
                        Log.i("DshBoot", "firstInteractive mode=recovery elapsedMs=${SystemClock.elapsedRealtime() - activityStartedAt}")
                    }
                }
            } else BootLoadingScreen(
                snapshot = snapshot,
                state = state,
                onRetry = { retry++ },
                onUseDefault = {
                    useDefault = true
                    snapshot = LoadingSnapshotStore.defaultSnapshot()
                    activity.getSharedPreferences("dsh", 0).edit().remove("dsh_loading").apply()
                    loadingDrawn = false
                    state = state.copy(status = BootStatus.LOADING, failure = null)
                    retry++
                },
                onOpenRecovery = if (state.status == BootStatus.FAILED && vm?.runtimeCoreReady == true) {
                    { recoveryOpen = true }
                } else null,
            )
        }
        // Install only after snapshot IO resolves. This signal observes the selected tree's frame.
        if (snapshotReady && !recoveryOpen) FirstDrawSignal("loading:${snapshot.revision}:$retry", activityStartedAt) {
            if (!loadingDrawn) {
                loadingDrawn = true
                Log.i("DshBoot", "loadingFirstDraw elapsedMs=${SystemClock.elapsedRealtime() - activityStartedAt}")
            }
        }
    }
}

/** API 29 frame commit is a renderer submission; API 26 uses draw+next frame, not screen presentation. */
@Composable
private fun FirstDrawSignal(kind: String, startedAt: Long, onDrawn: () -> Unit) {
    val view = LocalView.current
    val callback by rememberUpdatedState(onDrawn)
    DisposableEffect(view, kind) {
        var disposed = false
        var fired = false
        var completion: Runnable? = null
        var frame: Choreographer.FrameCallback? = null
        val observer = view.viewTreeObserver
        val listener = object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                if (fired || disposed) return true
                fired = true
                view.post { if (observer.isAlive) observer.removeOnPreDrawListener(this) }
                val done = Runnable {
                    view.post {
                        if (!disposed) {
                            Log.i("DshBoot", "$kind drawCallback=${if (Build.VERSION.SDK_INT >= 29 && view.isHardwareAccelerated) "frameCommit" else "draw-next-frame"} elapsedMs=${SystemClock.elapsedRealtime() - startedAt} presentationConfirmed=false")
                            callback()
                        }
                    }
                }
                completion = done
                if (Build.VERSION.SDK_INT >= 29 && view.isHardwareAccelerated) {
                    observer.registerFrameCommitCallback(done)
                } else {
                    frame = Choreographer.FrameCallback { done.run() }.also { Choreographer.getInstance().postFrameCallback(it) }
                }
                return true
            }
        }
        observer.addOnPreDrawListener(listener)
        view.invalidate()
        onDispose {
            disposed = true
            if (observer.isAlive) {
                observer.removeOnPreDrawListener(listener)
                if (Build.VERSION.SDK_INT >= 29) completion?.let { observer.unregisterFrameCommitCallback(it) }
            }
            frame?.let { Choreographer.getInstance().removeFrameCallback(it) }
        }
    }
}

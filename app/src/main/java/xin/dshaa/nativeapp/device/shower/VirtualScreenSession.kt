package xin.dshaa.nativeapp.device.shower

import com.ai.assistance.showerclient.ShowerController

/** 虚拟显示的请求尺寸（真实宽度会按编码器 16 对齐，例如 1080 -> 1088）。 */
data class VirtualDisplaySpec(val width: Int, val height: Int, val dpi: Int) {
    val label: String get() = "${width}×${height} · ${dpi}dpi"

    companion object {
        val default = VirtualDisplaySpec(1080, 1920, 320)
        val presets = listOf(
            default to "1080×1920 · 320dpi（推荐）",
            VirtualDisplaySpec(1080, 2400, 420) to "1080×2400 · 420dpi（全面屏）",
            VirtualDisplaySpec(720, 1280, 320) to "720×1280 · 320dpi（轻量）",
        )
    }
}

/**
 * 虚拟屏的进程级状态（2026-11-07）。
 *
 * 为什么要有：虚拟显示是 Shell 侧的，App 里进出页面会重建 Composable，但显示还在。
 * 如果控制器和「运行中」标志跟着 Composable 走，返回再进就会显示「未启动」，
 * 而实际 display 还活着（点启动会重复建屏，甚至和旧屏抢输入）。
 */
object VirtualScreenSession {
    /** 一个 App 进程只用一个控制器：它内部缓存 shower binder 与 displayId。 */
    val controller: ShowerController by lazy { ShowerController() }

    @Volatile
    var running: Boolean = false

    @Volatile
    var videoSize: Pair<Int, Int>? = null

    /** 正在虚拟屏里跑的应用名（标题栏第二行）；虚拟桌面时为「桌面」。 */
    @Volatile
    var appLabel: String? = null

    /** 正在虚拟屏里跑的包名（浮层贴边把手取图标用；桌面时为 null）。 */
    @Volatile
    var appPackage: String? = null

    /** 当前虚拟显示的请求尺寸（回前台重连 sink 要用）。 */
    @Volatile
    var spec: VirtualDisplaySpec? = null

    fun markStarted(
        size: Pair<Int, Int>?,
        label: String?,
        spec: VirtualDisplaySpec? = this.spec,
        packageName: String? = null,
    ) {
        running = true
        videoSize = size
        appLabel = label
        appPackage = packageName
        if (spec != null) this.spec = spec
    }

    fun markStopped() {
        running = false
        videoSize = null
        appLabel = null
        appPackage = null
        spec = null
    }
}

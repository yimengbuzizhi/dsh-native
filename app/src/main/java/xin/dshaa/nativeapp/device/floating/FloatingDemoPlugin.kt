package xin.dshaa.nativeapp.device.floating

import android.util.Log
import androidx.compose.ui.graphics.Color
import xin.dshaa.core.plugins.SurfaceSpec
import xin.dshaa.core.plugins.SurfaceTree

/**
 * M84-D 验收用的内置示例悬浮窗插件（`builtin.floating.demo`）：
 *
 * - 球：右上角红色角标「1」；
 * - 气泡：粉底深红字、18dp 圆角、正文前缀 `【demo.floating】`；
 * - 面板 / 全屏页：顶部横幅；
 * - `onClick` / `onShow`：写 logcat `DshFloatingSlot`，证明点击与显示时机的钩子真的被调用。
 *
 * 它走的是和以后 JS 插件相同的 [FloatingSlotPlugin] 接口；现在由内置插件开关控制注册/注销，
 * 关掉后 [FloatingSlots] 里没有它，四个插口立即恢复默认（截图 126-06）。
 */
class FloatingDemoPlugin : FloatingSlotPlugin {
    override val id: String = ID

    override fun ballBadge(): FloatingBadge = FloatingBadge(text = "1")

    override fun bubbleStyle(): BubbleStyle = BubbleStyle(
        container = Color(0xFFFFE4EC),
        content = Color(0xFF7A1734),
        cornerDp = 18,
    )

    override fun bubbleText(defaultText: String): String = "【demo.floating】$defaultText"

    override fun banner(slot: FloatingSlot): String? = when (slot) {
        FloatingSlot.PANEL -> "悬浮窗插件 demo：面板插口（内容来自 FloatingSlots）"
        FloatingSlot.FULLSCREEN -> "悬浮窗插件 demo：全屏页插口"
        else -> null
    }

    override fun onClick(slot: FloatingSlot): Boolean {
        Log.i(TAG, "click slot=$slot plugin=$id (default action kept)")
        return false
    }

    override fun onShow(slot: FloatingSlot) {
        Log.i(TAG, "show slot=$slot plugin=$id")
    }

    /**
     * M85-F：面板整块内容由 Surface 原语提供（演示插口）。
     * 全屏页不在这里替换（会把聊天整页换掉），但同一个渲染入口也支持 FULLSCREEN。
     */
    override fun surfaceTree(slot: FloatingSlot): SurfaceTree? =
        if (slot == FloatingSlot.PANEL) DEMO_PANEL_TREE else null

    override fun onSurfaceAction(slot: FloatingSlot, action: String): Boolean {
        Log.i(TAG, "surface action slot=$slot action=$action plugin=$id")
        return true
    }

    companion object {
        const val ID = "demo.floating"
        const val PLUGIN_ID = "builtin.floating.demo"
        private const val TAG = "DshFloatingSlot"

        /** 面板 Surface 树：静态内容 + 一个动作按钮（证明点击回到插件）。 */
        private val DEMO_PANEL_TREE: SurfaceTree? = SurfaceSpec.parse(
            """
            {"title":"悬浮窗面板 · Surface 插口","tree":{"type":"column","padding":14,"gap":10,"children":[
              {"type":"text","text":"悬浮窗面板 · Surface 插口","size":18,"weight":"bold"},
              {"type":"text","text":"这块面板由插件用 Surface 原语提供（不是原生聊天页）；关掉插件立即恢复原生面板。","size":13,"color":"sub"},
              {"type":"divider"},
              {"type":"button","label":"测试动作","style":"tonal","onTap":"floating.demo.action"},
              {"type":"text","text":"M85-F · floating surface slot","size":11,"color":"faint"}
            ]}}
            """.trimIndent(),
        ).tree
    }
}

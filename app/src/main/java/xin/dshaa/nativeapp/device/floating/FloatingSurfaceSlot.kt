package xin.dshaa.nativeapp.device.floating

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import xin.dshaa.core.plugins.SurfaceTree
import xin.dshaa.nativeapp.ui.surface.SurfaceDataSource
import xin.dshaa.nativeapp.ui.surface.SurfaceRow
import xin.dshaa.nativeapp.ui.surface.SurfaceTreeView
import xin.dshaa.ui.design.theme.DshTheme2

/**
 * M85-F：悬浮窗面板 / 全屏页的 **Surface 内容插口**。
 *
 * 插件在 [FloatingSlotPlugin.surfaceTree] 里返回一棵 Surface 树时，宿主用与壳相同的
 * `SurfaceTreeView` 渲染它；动作经 [FloatingSlots.handleSurfaceAction] 回到插件。
 * 插件不返回树 = 继续用原生悬浮窗界面（聊天页不删、行为不变）。
 *
 * v1 的 Surface 内容只支持静态树（text/button/divider/…）：悬浮窗没有壳宿主那份
 * chat/state 绑定数据源，`bind` 在这里读不到东西；需要绑定的内容仍由原生界面提供。
 */
@Composable
fun FloatingSurfaceSlot(
    tree: SurfaceTree,
    slot: FloatingSlot,
    modifier: Modifier = Modifier,
) {
    val c = DshTheme2.colors
    val emptyData = remember {
        object : SurfaceDataSource {
            @Composable
            override fun rows(bind: String, parent: SurfaceRow?): List<SurfaceRow>? = null

            @Composable
            override fun text(bind: String, row: SurfaceRow?): String = ""

            @Composable
            override fun flag(bind: String, row: SurfaceRow?): Boolean = false
        }
    }
    SurfaceTreeView(
        tree = tree,
        data = emptyData,
        onAction = { action, _, _ -> FloatingSlots.handleSurfaceAction(slot, action) },
        onDraft = { },
        modifier = modifier.background(c.background).fillMaxSize(),
    )
}

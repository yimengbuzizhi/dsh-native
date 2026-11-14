package xin.dshaa.nativeapp.device.floating

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import java.util.concurrent.CopyOnWriteArrayList
import xin.dshaa.core.plugins.SurfaceTree

/**
 * M84-D：**悬浮窗四分口**——球 / 气泡 / 面板 / 全屏页各是一个插口。
 *
 * `docs/115 §D` 要求「显示时机、点击、内容都是钩子」。这一层先把四个插口的边界抽出来：
 * - 显示时机：[FloatingSlotPlugin.visible]（当前作用于流式结束后的结果气泡；关掉就不弹）；
 * - 点击：[FloatingSlotPlugin.onClick]（true = 消费，不再走默认动作；当前接球与气泡）；
 * - 内容：[FloatingSlotPlugin.ballBadge] / [bubbleStyle] / [bubbleText] / [banner]。
 *
 * 现在的实现仍是**宿主内 Kotlin 插件**（内置 demo 插件 + 插件页开关）；JS 插件注册入口
 * （`ctx.floating.*`）归 `docs/115 §E`，到时只是往 [register] 里塞同一个接口。
 *
 * 约束：
 * - 注册表是进程内单例，悬浮窗服务与 App 共用；[revision] 是 Compose 快照，开关后界面立即重组；
 * - 查询全部按「后注册覆盖先注册」取最后一个非空值；可见性是所有插件 AND；
 * - 插件抛异常不能弄断悬浮窗：所有查询都 runCatching 包住，失败当 null/默认。
 */
enum class FloatingSlot { BALL, BUBBLE, PANEL, FULLSCREEN }

/** 球右上角的角标（红点可以带一个短文本，如未读数）。 */
data class FloatingBadge(val text: String = "", val color: Color = Color(0xFFFF3B30))

/** 结果气泡的样式（内容默认取最后一条助手正文；插件可用 [FloatingSlotPlugin.bubbleText] 覆盖）。 */
data class BubbleStyle(
    val container: Color = Color.White,
    val content: Color = Color.Black,
    val cornerDp: Int = 12,
)

/** 一个悬浮窗插口插件。全部方法都有默认实现——只关心一个插口时不用写全。 */
interface FloatingSlotPlugin {
    val id: String
    fun ballBadge(): FloatingBadge? = null
    fun bubbleStyle(): BubbleStyle? = null
    /** 覆盖气泡正文；null = 用默认的最后一条助手消息。 */
    fun bubbleText(defaultText: String): String? = null
    /** 面板/全屏页顶部横幅（内容插口）；null = 不显示。 */
    fun banner(slot: FloatingSlot): String? = null
    /** 该插口是否允许自动出现（当前用于气泡；不实现 = true）。 */
    fun visible(slot: FloatingSlot): Boolean = true
    /** 点击拦截：true = 已消费，不再走默认动作。 */
    fun onClick(slot: FloatingSlot): Boolean = false
    /** 插口被显示（模式切换 / 气泡弹出）。 */
    fun onShow(slot: FloatingSlot) = Unit

    /**
     * M85-F：把面板 / 全屏页的**整块内容**交给插件用 Surface 原语提供。
     *
     * 返回 null = 继续用原生悬浮窗界面（聊天页原样，不删）；非 null = 宿主改用
     * `SurfaceTreeView` 渲染这棵树，动作经 [onSurfaceAction] 回到插件。
     * 目前只对 [FloatingSlot.PANEL] / [FloatingSlot.FULLSCREEN] 生效（球/气泡不是整块内容）。
     */
    fun surfaceTree(slot: FloatingSlot): SurfaceTree? = null

    /** Surface 插口里的点击动作；true = 已消费（宿主不再走默认）。 */
    fun onSurfaceAction(slot: FloatingSlot, action: String): Boolean = false
}

object FloatingSlots {
    private val plugins = CopyOnWriteArrayList<FloatingSlotPlugin>()

    /** Compose 快照：注册/注销后界面要重组（开关插件立即生效）。 */
    var revision by mutableStateOf(0)
        private set

    fun register(plugin: FloatingSlotPlugin): FloatingSlotPlugin? {
        val old = plugins.firstOrNull { it.id == plugin.id }
        if (old != null) plugins.remove(old)
        plugins.add(plugin)
        revision++
        return old
    }

    fun unregister(id: String): FloatingSlotPlugin? {
        val old = plugins.firstOrNull { it.id == id } ?: return null
        plugins.remove(old)
        revision++
        return old
    }

    fun get(id: String): FloatingSlotPlugin? = plugins.firstOrNull { it.id == id }

    fun ids(): List<String> = plugins.map { it.id }.sorted()

    fun clearForTest() {
        plugins.clear()
        revision++
    }

    private inline fun <T> query(block: (FloatingSlotPlugin) -> T?): T? {
        var result: T? = null
        plugins.forEach { plugin ->
            runCatching { block(plugin) }.getOrNull()?.let { result = it }
        }
        return result
    }

    private inline fun <T> queryAll(block: (FloatingSlotPlugin) -> T?): List<T> =
        plugins.mapNotNull { plugin -> runCatching { block(plugin) }.getOrNull() }

    fun ballBadge(): FloatingBadge? = query { it.ballBadge() }

    fun bubbleStyle(): BubbleStyle? = query { it.bubbleStyle() }

    fun bubbleText(defaultText: String): String? = query { it.bubbleText(defaultText) }

    fun banner(slot: FloatingSlot): String? = query { it.banner(slot) }

    /** 所有插件都说可见才可见（不实现 visible 的插件恒为 true）。 */
    fun visible(slot: FloatingSlot): Boolean =
        queryAll { it.visible(slot) }.all { it }

    fun handleClick(slot: FloatingSlot): Boolean =
        queryAll { it.onClick(slot) }.any { it }

    fun dispatchShow(slot: FloatingSlot) {
        plugins.forEach { plugin -> runCatching { plugin.onShow(slot) } }
    }

    /** M85-F：面板/全屏页的 Surface 内容（后注册覆盖先注册；null = 原生界面）。 */
    fun surfaceTree(slot: FloatingSlot): SurfaceTree? = query { it.surfaceTree(slot) }

    fun handleSurfaceAction(slot: FloatingSlot, action: String): Boolean =
        queryAll { it.onSurfaceAction(slot, action) }.any { it }
}

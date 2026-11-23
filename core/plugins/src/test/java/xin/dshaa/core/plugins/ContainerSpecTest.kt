package xin.dshaa.core.plugins

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * P3-e：三种容器只有三种（`docs/187` §9.2 验收：容器不随上游界面贡献增长）。
 *
 * 用例锁死三件事：
 * 1. wire 值与别名解析；未知值返回 null（调用方必须报错，不能悄悄退化成悬浮窗）；
 * 2. 几何：sheet 贴底、floating 可拖且保持 M70 尺寸、fullscreen 占满；
 * 3. 能力只有 5 个，且**没有** slot / 会话流内嵌入口（红线）。
 */
class ContainerSpecTest {

    @Test
    fun onlyThreeKinds() {
        assertEquals(3, ContainerSpec.kinds.size)
        assertEquals(
            listOf("fullscreen", "floating", "sheet"),
            ContainerSpec.kinds.map { it.wire },
        )
    }

    @Test
    fun parseWireAndAliases_unknownIsNull() {
        assertEquals(ContainerKind.FULLSCREEN, ContainerKind.parse("fullscreen"))
        assertEquals(ContainerKind.FLOATING, ContainerKind.parse("FLOATING"))
        assertEquals(ContainerKind.SHEET, ContainerKind.parse(" sheet "))
        assertEquals(ContainerKind.FULLSCREEN, ContainerKind.parse("page"))
        assertEquals(ContainerKind.FLOATING, ContainerKind.parse("overlay"))
        assertEquals(ContainerKind.SHEET, ContainerKind.parse("bottom-sheet"))
        assertNull(ContainerKind.parse("dialog"))
        assertNull(ContainerKind.parse("slot"))
        assertNull(ContainerKind.parse(null))
        assertNull(ContainerKind.parse(""))
    }

    @Test
    fun geometryMatchesEachKindsPromise() {
        val full = ContainerSpec.geometry(ContainerKind.FULLSCREEN)
        assertNull(full.widthDp, "fullscreen 占满可用空间")
        assertNull(full.heightDp)
        assertFalse(full.draggable)
        assertTrue(full.chrome)

        val floating = ContainerSpec.geometry(ContainerKind.FLOATING)
        assertEquals(ContainerSpec.FLOATING_WIDTH_DP, floating.widthDp)
        assertEquals(ContainerSpec.FLOATING_HEIGHT_DP, floating.heightDp)
        assertTrue(floating.draggable, "悬浮窗必须可拖")
        assertFalse(floating.chrome, "悬浮窗不加原生标题栏（会盖住宠物）")
        assertEquals(0f, floating.dimBehind)

        val sheet = ContainerSpec.geometry(ContainerKind.SHEET)
        assertTrue(sheet.anchoredBottom, "sheet 贴底")
        assertNull(sheet.widthDp)
        assertFalse(sheet.draggable)
        assertTrue(sheet.chrome)
        assertTrue(sheet.dimBehind > 0f, "sheet 背后要压暗一点")
    }

    @Test
    fun sheetHeightIsAboutTwoThirds_andClamped() {
        val screen = 2000
        val height = ContainerSpec.sheetHeightPx(screen, 400)
        assertEquals((screen * ContainerSpec.SHEET_HEIGHT_RATIO).toInt(), height)
        assertTrue(height < screen)
        assertTrue(ContainerSpec.sheetHeightPx(300, 400) <= 300, "小屏也不能超过屏高")
        assertTrue(ContainerSpec.sheetHeightPx(2000, 400) >= 400, "不能小于最小高度")
    }

    @Test
    fun capabilitiesAreExactlyTheFiveAllowedOnes() {
        assertEquals(
            listOf("closeSelf", "setTitle", "postMessage", "theme", "device"),
            ContainerSpec.capabilities,
        )
        ContainerSpec.capabilities.forEach { assertTrue(ContainerSpec.hasCapability(it)) }
        assertTrue(ContainerSpec.hasCapability("SETTITLE"))
        // 红线：不做 slot 协议、不做会话流内嵌 —— 这两个名字永远不该出现在能力表里。
        assertFalse(ContainerSpec.hasCapability("slots.register"))
        assertFalse(ContainerSpec.hasCapability("session.append"))
        assertFalse(ContainerSpec.hasCapability("openSlot"))
    }

    @Test
    fun defaultTitleIsPerKind_butNeverPluginSpecific() {
        assertEquals("桌宠", ContainerSpec.defaultTitle(ContainerKind.FLOATING, "桌宠"))
        assertEquals("桌宠 · 抽屉", ContainerSpec.defaultTitle(ContainerKind.SHEET, "桌宠"))
        assertEquals("插件", ContainerSpec.defaultTitle(ContainerKind.FULLSCREEN, ""))
    }
}

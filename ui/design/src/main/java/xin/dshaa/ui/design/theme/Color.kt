package xin.dshaa.ui.design.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * 设计 token（07 §三 / design/html/01-home.html）。
 *
 * 主色按 2026-11-06 定稿：#2F6FE0。旧文档里的 #4D6BFE 已废弃，不要再引用。
 * 亮色是默认（Rikka 式暖白、少颜色、单一强调色），暗色是「深海」。
 */
@Immutable
data class DshColors(
    val brand: Color,
    val brandBright: Color,
    val brandSoft: Color,
    val tint: Color,
    val background: Color,
    val surface: Color,
    val surfaceAlt: Color,
    val line: Color,
    val line2: Color,
    val text: Color,
    val sub: Color,
    val faint: Color,
    val success: Color,
    val successSoft: Color,
    val purple: Color,
    val purpleSoft: Color,
    val warn: Color,
    val warnSoft: Color,
    val danger: Color,
    val dangerSoft: Color,
    val heroStart: Color,
    val heroMid: Color,
    val heroEnd: Color,
    val onHero: Color,
    val isDark: Boolean,
)

val LightDshColors = DshColors(
    brand = Color(0xFF2F6FE0),
    brandBright = Color(0xFF4F8BFF),
    brandSoft = Color(0xFFE6F0FF),
    tint = Color(0xFFF4F8FF),
    background = Color(0xFFF0F2F6),
    surface = Color(0xFFFFFFFF),
    surfaceAlt = Color(0xFFF7F9FC),
    line = Color(0xFFDCE6F6),
    line2 = Color(0xFFEDF1F7),
    text = Color(0xFF17181A),
    sub = Color(0xFF6B7280),
    faint = Color(0xFF9AA3B2),
    success = Color(0xFF16A34A),
    successSoft = Color(0xFFE6F7EF),
    purple = Color(0xFF7C3AED),
    purpleSoft = Color(0xFFF3E8FF),
    warn = Color(0xFFB26B00),
    warnSoft = Color(0xFFFEF3C7),
    danger = Color(0xFFD9503F),
    dangerSoft = Color(0xFFFFEDEA),
    heroStart = Color(0xFF2F6FE0),
    heroMid = Color(0xFF3B82F6),
    heroEnd = Color(0xFF60A5FA),
    onHero = Color(0xFFFFFFFF),
    isDark = false,
)

val DarkDshColors = DshColors(
    brand = Color(0xFF5B8DFF),
    brandBright = Color(0xFF7FA8FF),
    brandSoft = Color(0xFF1B2A47),
    tint = Color(0xFF121B2B),
    background = Color(0xFF0B0F17),
    surface = Color(0xFF131A26),
    surfaceAlt = Color(0xFF182132),
    line = Color(0xFF243149),
    line2 = Color(0xFF1D2738),
    text = Color(0xFFE9EEFB),
    sub = Color(0xFF93A0B8),
    faint = Color(0xFF6C7A93),
    success = Color(0xFF31C48D),
    successSoft = Color(0xFF10321F),
    purple = Color(0xFFA78BFA),
    purpleSoft = Color(0xFF241A3D),
    warn = Color(0xFFF5A524),
    warnSoft = Color(0xFF33260A),
    danger = Color(0xFFF05252),
    dangerSoft = Color(0xFF3A1717),
    heroStart = Color(0xFF1E3A70),
    heroMid = Color(0xFF2F5FA8),
    heroEnd = Color(0xFF3E7FD0),
    onHero = Color(0xFFF2F6FF),
    isDark = true,
)

val LocalDshColors = staticCompositionLocalOf { LightDshColors }

/**
 * 强调色预设（decision #10：6 套主题预设，先做强调色；surface/文字仍按亮暗两套）。
 * 品牌色之外派生 brandBright / hero 渐变，避免每套都手写一份完整 palette。
 */
data class AccentOption(val id: String, val name: String, val color: Color)

val DshAccents = listOf(
    AccentOption("blue", "经典蓝", Color(0xFF2F6FE0)),
    AccentOption("indigo", "深靛", Color(0xFF4F46E5)),
    AccentOption("teal", "青碧", Color(0xFF0E9F8A)),
    AccentOption("violet", "紫罗兰", Color(0xFF7C3AED)),
    AccentOption("amber", "琥珀", Color(0xFFB26B00)),
    AccentOption("rose", "玫瑰", Color(0xFFD64570)),
)

fun accentById(id: String): AccentOption = DshAccents.firstOrNull { it.id == id } ?: DshAccents[0]

fun DshColors.withAccent(accent: Color): DshColors = copy(
    brand = accent,
    brandBright = androidx.compose.ui.graphics.lerp(accent, Color.White, if (isDark) 0.28f else 0.12f),
    heroStart = accent,
    heroMid = androidx.compose.ui.graphics.lerp(accent, Color.White, 0.14f),
    heroEnd = androidx.compose.ui.graphics.lerp(accent, Color.White, 0.34f),
)

/** 间距基数 4 */
object DshSpacing {
    val xs = 4
    val s = 8
    val m = 12
    val l = 16
    val xl = 20
    val xxl = 24
    val xxxl = 32
}

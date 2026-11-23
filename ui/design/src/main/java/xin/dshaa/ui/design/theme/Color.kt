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
    /** 二级窗口（弹窗/弹层）的实底：几乎不透明，避免透出下一级 */
    val panel: Color,
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

/**
 * 液态玻璃改造：surface / surfaceAlt / tint / line / line2 全部改成**半透明白**。
 * 背后是 `LiquidBackdrop` 的水族箱墙面，玻璃靠透出墙面来获得「厚度」；
 * `background` 仍保持不透明（没有背景板的场合——浮窗、独立 Activity——要它能兜底）。
 */
val LightDshColors = DshColors(
    brand = Color(0xFF2E5FE6),
    brandBright = Color(0xFF4C7BF0),
    brandSoft = Color(0x1F2E5FE6),
    tint = Color(0x4DFFFFFF),
    background = Color(0xFFE8F0FA),
    surface = Color(0x4DFFFFFF), // 卡面玻璃：半透，墙色透上来
    surfaceAlt = Color(0x10142A54),
    panel = Color(0xFFF6FAFF),
    // 浅色墙很亮：白描边看不见，改用冷灰描边 + 白色内高光（见 LiquidTokens.hairline）。
    line = Color(0x1A162C58),
    line2 = Color(0x0A142A54),
    text = Color(0xFF121A28),
    sub = Color(0xFF4A5B74),
    faint = Color(0xFF7889A2),
    success = Color(0xFF0F9C8D),
    successSoft = Color(0xFFE6F7EF),
    purple = Color(0xFF7C3AED),
    purpleSoft = Color(0xFFF3E8FF),
    warn = Color(0xFFB26B00),
    warnSoft = Color(0xFFFEF3C7),
    danger = Color(0xFFD9503F),
    dangerSoft = Color(0xFFFFEDEA),
    heroStart = Color(0xFF2E5FE6),
    heroMid = Color(0xFF4C7BF0),
    heroEnd = Color(0xFF6FA0FF),
    onHero = Color(0xFFFFFFFF),
    isDark = false,
)

val DarkDshColors = DshColors(
    brand = Color(0xFF5B8EF8),
    brandBright = Color(0xFF7FA6FF),
    brandSoft = Color(0x265B8EF8),
    tint = Color(0x1FFFFFFF),
    background = Color(0xFF090F1C),
    surface = Color(0x420E1728), // 卡面玻璃：半透
    surfaceAlt = Color(0x0FFFFFFF),
    panel = Color(0xFF0D1422),
    line = Color(0x29FFFFFF),
    line2 = Color(0x0AFFFFFF),
    text = Color(0xFFEEF4FC),
    sub = Color(0xFF9CB0C9),
    faint = Color(0xFF6C809A),
    success = Color(0xFF20CAB8),
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
 * **原生界面（默认壳）的色板**：逐字保留 0.3.5 / `89b1455` 的值（`docs/187` §4.2）。
 *
 * 液态玻璃（[LightDshColors] / [DarkDshColors]）把 `surface/surfaceAlt/tint/line/line2`
 * 改成了半透明，原生壳不能跟着变——用户选的「原生」必须和 0.3.5 截图逐页一致，
 * 所以两套色板分开存，由 `DshTheme(liquid = …)` 选（见 `LocalLiquidUi`）。
 */
val LegacyLightDshColors = DshColors(
    brand = Color(0xFF2F6FE0),
    brandBright = Color(0xFF4F8BFF),
    brandSoft = Color(0xFFE6F0FF),
    tint = Color(0xFFF4F8FF),
    background = Color(0xFFF0F2F6),
    surface = Color(0xFFFFFFFF),
    surfaceAlt = Color(0xFFF7F9FC),
    panel = Color(0xFFFFFFFF),
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

val LegacyDarkDshColors = DshColors(
    brand = Color(0xFF5B8DFF),
    brandBright = Color(0xFF7FA8FF),
    brandSoft = Color(0xFF1B2A47),
    tint = Color(0xFF121B2B),
    background = Color(0xFF0B0F17),
    surface = Color(0xFF131A26),
    surfaceAlt = Color(0xFF182132),
    panel = Color(0xFF131A26),
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

/**
 * 液体玻璃化之后 `surface / surfaceAlt / line / line2 / tint / brandSoft` 都不再是实色，
 * 而是「自带不透明度的一层薄涂」（浅色=深墨 N%、深色=白 N%）。
 *
 * 所以想「再淡一点」时**不能**写 `c.surfaceAlt.copy(alpha = .7f)`——那会把 6% 的淡淡一层
 * 直接变成 70% 的深色板子（浅色主题下就是截图里那种深灰蓝大块，深色主题下是一片白，
 * 按钮/开关跟背景糊在一起分不出来）。正确写法是 [wash]：按比例稀释它原本的不透明度。
 */
fun Color.wash(scale: Float = 1f): Color =
    copy(alpha = (alpha * scale).coerceIn(0.012f, 1f))

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
package xin.dshaa.ui.design.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 液态玻璃 token（厚透镜 / Liquid Glass）。
 *
 * **这里的每一个值都是从设计稿 `鲸歌 · 厚透镜液态玻璃.html` 的 `:root` /
 * `[data-theme="light"]` 变量里一对一搬过来的**，字段名后面标了对应的 CSS 变量名。
 * 改颜色必须回去改设计稿再同步过来，不要在 App 里凭手感调 —— 之前几版「和 HTML 不一样」
 * 就是这么来的。
 *
 * 一块玻璃在 CSS 里只有五样东西（见设计稿 `.glass` / `::before` / `::after`）：
 * ① `background-color: var(--glass-tint)` ② `background-image` 两道渐变
 * ③ `inset 0 1px spec-top / inset 0 -1px spec-bottom / inset 0 0 0 .5px edge-line`
 * ④ `::after` 3px 侧壁环（--rim-width）⑤ `::before` 1px 镜面环
 * `Modifier.liquidGlass` 就按这五步顺序画。
 */
@Immutable
data class LiquidTokens(
    /** 墙面底色 `--bg-l0` */
    val wallTop: Color,
    /** 保留字段（设计稿里墙面是纯色 + 光斑，没有垂直渐变） */
    val wallMid: Color,
    val wallBottom: Color,
    /** 背景三团流体色斑（`.scenery__shape--s1/s2` 与 `.scenery__flow` 的首停色） */
    val blobA: Color,
    val blobB: Color,
    val blobC: Color,
    /** `--glass-white-10`：顶面受光带最亮处 */
    val glassTop: Color,
    val glassMid: Color,
    /** `--glass-white-low`：受光带两端的弱光 */
    val glassBottom: Color,
    /** 顶栏/输入条/抽屉一档（设计稿只有一套玻璃配方，这里与 Card 同值，保留字段以兼容调用点） */
    val glassStrongTop: Color,
    val glassStrongBottom: Color,
    /** `--spec-top`：顶部镜面硬边 */
    val rimTop: Color,
    /** `--spec-bottom`：底部弱光 */
    val rimBottom: Color,
    /** `--edge-line`：半像素分界线（浅色背景上没有这道极淡暗线，边界立不住） */
    val hairline: Color,
    /** `--wall-a`：内壁侧壁浓边 / 内壁聚光 */
    val wallSide: Color,
    /** 保留字段（设计稿没有左右色散，恒为透明） */
    val dispWarm: Color,
    val dispCool: Color,
    /** `--shadow-ambient` 的主色 */
    val ambient: Color,
    /** 噪点强度（设计稿 0.055 深 / 0.038 浅） */
    val grainAlpha: Float,
    /** 跟手光源 */
    val sheen: Color,
    /** 玻璃上文字的高光描边 */
    val textScrim: Color,
    /** 保留字段（墙面重写后不再单独画窗光） */
    val windowLight: Color,
    /** 保留字段（暗角由光斑自然形成） */
    val vignette: Color,
    /** 保留字段（内壁阴影由 `--glass-white-low` 承担） */
    val innerShadow: Color,
    /** `--glass-solid`：弹窗 / 二级面板 / 抽屉的实底，避免透出下一级窗口 */
    val panelBase: Color,
    /** `--glass-tint`：玻璃底色 */
    val glassTint: Color,
    /** `--pill-fill`：小胶囊的实面（小件不上背景采样） */
    val pillFill: Color,
    val isDark: Boolean,
)

val LightLiquidTokens = LiquidTokens(
    wallTop = Color(0xFFE8F0FA), // --bg-l0
    wallMid = Color(0xFFE8F0FA),
    wallBottom = Color(0xFFE8F0FA),
    // .scenery__layer--light 的 s1 / s2 / flow
    blobA = Color(0xA670A2FA), // rgba(112,162,250,.56)
    blobB = Color(0x8540CEC0), // rgba(64,206,192,.44)
    blobC = Color(0x6B7EACFC), // rgba(126,172,252,.42)
    glassTop = Color(0x7AFFFFFF), // --glass-white-10: .48
    glassMid = Color(0x1FFFFFFF), // 40% 处回落到 --glass-white-low: .12（原来给 .48，玻璃中段发白不透）
    glassBottom = Color(0x1FFFFFFF), // --glass-white-low: .12
    glassStrongTop = Color(0x7AFFFFFF),
    glassStrongBottom = Color(0x1FFFFFFF),
    rimTop = Color(0xFAFFFFFF), // --spec-top: .98
    rimBottom = Color(0x80FFFFFF), // --spec-bottom: .50
    hairline = Color(0x1A162C58), // --edge-line: rgba(22,44,88,.10)
    wallSide = Color(0x8CFFFFFF), // --wall-a: .55
    dispWarm = Color(0x00FFFFFF),
    dispCool = Color(0x00FFFFFF),
    ambient = Color(0x26163060), // --shadow-ambient: rgba(22,48,96,.15)
    grainAlpha = 0.038f,
    sheen = Color(0x66FFFFFF),
    textScrim = Color(0x0A1B2B4B),
    windowLight = Color(0x00FFFFFF),
    vignette = Color(0x0016233C),
    innerShadow = Color(0x00163060),
    panelBase = Color(0xFFF6FAFF), // --glass-solid: rgba(246,250,255,.95)
    glassTint = Color(0x4DFFFFFF), // --glass-tint: rgba(255,255,255,.30)
    pillFill = Color(0x9EFFFFFF), // --pill-fill: rgba(255,255,255,.62)
    isDark = false,
)

val DarkLiquidTokens = LiquidTokens(
    wallTop = Color(0xFF090F1C), // --bg-l0
    wallMid = Color(0xFF090F1C),
    wallBottom = Color(0xFF090F1C),
    // .scenery__layer--dark 的 s1 / s2 / flow
    blobA = Color(0x9E345CB8), // rgba(52,92,184,.62)
    blobB = Color(0x6B1CBCAC), // rgba(28,188,172,.42)
    blobC = Color(0x61588AEE), // rgba(88,138,238,.38)
    glassTop = Color(0x16FFFFFF), // --glass-white-10: .085
    glassMid = Color(0x04FFFFFF), // 40% 处回落到 --glass-white-low: .015
    glassBottom = Color(0x04FFFFFF), // --glass-white-low: .015
    glassStrongTop = Color(0x16FFFFFF),
    glassStrongBottom = Color(0x04FFFFFF),
    rimTop = Color(0x9EFFFFFF), // --spec-top: .62
    rimBottom = Color(0x1FFFFFFF), // --spec-bottom: .12
    hairline = Color(0x29FFFFFF), // --edge-line: .16
    wallSide = Color(0x1AFFFFFF), // --wall-a: .10
    dispWarm = Color(0x00FFFFFF),
    dispCool = Color(0x00FFFFFF),
    ambient = Color(0x94020610), // --shadow-ambient: rgba(2,6,16,.58)
    grainAlpha = 0.055f,
    sheen = Color(0x1FFFFFFF),
    textScrim = Color(0x14000000),
    windowLight = Color(0x00FFFFFF),
    vignette = Color(0x00000000),
    innerShadow = Color(0x00000000),
    panelBase = Color(0xFF0D1422), // --glass-solid: rgba(13,20,34,.94)
    glassTint = Color(0x420E1728), // --glass-tint: rgba(14,23,40,.26)
    pillFill = Color(0x12FFFFFF), // --pill-fill: rgba(255,255,255,.07)
    isDark = true,
)

val LocalLiquidTokens = staticCompositionLocalOf { LightLiquidTokens }

/**
 * 圆角尺度，对应设计稿 `--r-*`：
 * panel 26 / bar 22 / card 16 / row 12 / pill 999。
 * 内层元素用它，外层按光学同心圆 R_外 = R_内 + Padding 推。
 */
object LiquidRadius {
    val xs: Dp = 12.dp // --r-row
    val s: Dp = 12.dp // --r-row
    val m: Dp = 16.dp // --r-card
    val l: Dp = 22.dp // --r-bar
    val xl: Dp = 26.dp // --r-panel
    val xxl: Dp = 30.dp
    val pill: Dp = 999.dp
}

/**
 * 光学同心圆角：外圆角 = 内圆角 + 内边距（设计稿 / b-end 规范的硬门禁）。
 * 内外两层圆弧共心，视觉上厚度才均匀，否则会出现「内角比外角尖」的廉价感。
 */
fun opticalOuterRadius(innerRadius: Dp, padding: Dp): Dp = innerRadius + padding

/** 动效常量：跟手、无过冲、短。 */
object LiquidMotion {
    /** 按压回弹（0.976 缩放），跟手优先 */
    val press = spring<Float>(dampingRatio = 0.72f, stiffness = 900f)
    /** 面板展开 / 收起 */
    val panel = spring<Float>(dampingRatio = 0.82f, stiffness = 420f)
    /** 布局尺寸变化（输入框长高、顶栏压缩） */
    val size = spring<Float>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 500f)
    /** 入场淡入 + 上浮（design 稿 cubic-bezier(0.22,1,0.36,1)） */
    val enterEasing = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)
    const val enterMs = 340
    /** 错峰步长（设计稿 45ms） */
    const val staggerMs = 45
    /** 跟手光源的指数逼近（同一帧内的收敛速度） */
    const val sheenMs = 90
}

/**
 * 玻璃厚度档位。设计稿只有一套玻璃配方，档位只影响阴影与是否上阴影。
 */
enum class LiquidLevel {
    /** 小胶囊 / chip：不上阴影，用 --pill-fill 实面 */
    Pill,

    /** 常规卡片 / 列表 / 气泡 */
    Card,

    /** 顶栏 / 输入条 / 抽屉 / 弹层 */
    Panel,

    /** 主视觉（水球底座、空状态） */
    Hero,
}

/** 一圈各档位的阴影高度（dp），对应设计稿 --shadow-ambient。 */
internal fun LiquidLevel.shadowDp(isDark: Boolean): Dp = when (this) {
    LiquidLevel.Pill -> 0.dp
    LiquidLevel.Card -> if (isDark) 8.dp else 6.dp
    LiquidLevel.Panel -> if (isDark) 10.dp else 10.dp
    LiquidLevel.Hero -> if (isDark) 12.dp else 14.dp
}

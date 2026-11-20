package xin.dshaa.ui.design.theme

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * 设计稿 `body { transition: background-color 280ms, color 280ms }` 的等价物。
 *
 * 设计稿切换深浅时不是「啪」一下换掉：墙面、玻璃、文字各自做 280ms 的颜色过渡，
 * 所以看起来像整块墙面在变色。Compose 里没有这样的全局 CSS transition，
 * 这里把两套令牌包成「可动画版本」——切换主题时逐字段插值，其余时间它们就是常量。
 *
 * 成本：只有切换主题的那 280ms 里会有逐帧重组（约 50 个颜色动画），
 * 稳态下每个字段都是同一个值，不额外吃帧。
 */
private val ThemeEase = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)
private const val THEME_MS = 280

@Composable
internal fun animatedDshColors(target: DshColors): DshColors {
    @Composable
    fun c(v: Color): Color =
        animateColorAsState(v, tween(THEME_MS, easing = ThemeEase), label = "themeColor").value

    return target.copy(
        brand = c(target.brand),
        brandBright = c(target.brandBright),
        brandSoft = c(target.brandSoft),
        tint = c(target.tint),
        background = c(target.background),
        surface = c(target.surface),
        surfaceAlt = c(target.surfaceAlt),
        panel = c(target.panel),
        line = c(target.line),
        line2 = c(target.line2),
        text = c(target.text),
        sub = c(target.sub),
        faint = c(target.faint),
        success = c(target.success),
        successSoft = c(target.successSoft),
        purple = c(target.purple),
        purpleSoft = c(target.purpleSoft),
        warn = c(target.warn),
        warnSoft = c(target.warnSoft),
        danger = c(target.danger),
        dangerSoft = c(target.dangerSoft),
        heroStart = c(target.heroStart),
        heroMid = c(target.heroMid),
        heroEnd = c(target.heroEnd),
        onHero = c(target.onHero),
    )
}

@Composable
internal fun animatedLiquidTokens(target: LiquidTokens): LiquidTokens {
    @Composable
    fun c(v: Color): Color =
        animateColorAsState(v, tween(THEME_MS, easing = ThemeEase), label = "liquidColor").value

    return target.copy(
        wallTop = c(target.wallTop),
        wallMid = c(target.wallMid),
        wallBottom = c(target.wallBottom),
        blobA = c(target.blobA),
        blobB = c(target.blobB),
        blobC = c(target.blobC),
        glassTop = c(target.glassTop),
        glassMid = c(target.glassMid),
        glassBottom = c(target.glassBottom),
        glassStrongTop = c(target.glassStrongTop),
        glassStrongBottom = c(target.glassStrongBottom),
        rimTop = c(target.rimTop),
        rimBottom = c(target.rimBottom),
        hairline = c(target.hairline),
        wallSide = c(target.wallSide),
        dispWarm = c(target.dispWarm),
        dispCool = c(target.dispCool),
        ambient = c(target.ambient),
        sheen = c(target.sheen),
        textScrim = c(target.textScrim),
        windowLight = c(target.windowLight),
        vignette = c(target.vignette),
        innerShadow = c(target.innerShadow),
        panelBase = c(target.panelBase),
        glassTint = c(target.glassTint),
        pillFill = c(target.pillFill),
    )
}

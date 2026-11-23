package xin.dshaa.ui.design.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.dp

/**
 * 当前是否走「液态玻璃」界面壳（`docs/187` §4）。
 *
 * - `false`（默认）= 原生界面，色板用 [LegacyLightDshColors] / [LegacyDarkDshColors]，
 *   页面用实底背景、卡片用原 Surface，**看不到任何玻璃/墙面/光斑**；
 * - `true` = 液态玻璃，色板用 [LightDshColors] / [DarkDshColors]，页面透明化由
 *   `LiquidBackdrop` 铺底，组件走 `liquidGlass` 配方。
 *
 * 由 `AppViewModel.liquidUiEnabled`（角色页「界面：液态玻璃（实验）」）提供；
 * 组件只读这个 local，不各自去读设置。
 */
val LocalLiquidUi = staticCompositionLocalOf { false }

@Composable
fun DshTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    accentId: String = "blue",
    liquid: Boolean = LocalLiquidUi.current,
    content: @Composable () -> Unit,
) {
    val base = if (liquid) {
        if (darkTheme) DarkDshColors else LightDshColors
    } else {
        if (darkTheme) LegacyDarkDshColors else LegacyLightDshColors
    }
    val palette = if (accentId == "blue") base else base.withAccent(accentById(accentId).color)
    val scheme = if (darkTheme) {
        darkColorScheme(
            primary = palette.brand,
            onPrimary = palette.onHero,
            primaryContainer = palette.brandSoft,
            onPrimaryContainer = palette.text,
            secondary = palette.purple,
            background = palette.background,
            onBackground = palette.text,
            surface = palette.surface,
            onSurface = palette.text,
            surfaceVariant = palette.surfaceAlt,
            onSurfaceVariant = palette.sub,
            outline = palette.line,
            outlineVariant = palette.line2,
            error = palette.danger,
        )
    } else {
        lightColorScheme(
            primary = palette.brand,
            onPrimary = palette.onHero,
            primaryContainer = palette.brandSoft,
            onPrimaryContainer = palette.text,
            secondary = palette.purple,
            background = palette.background,
            onBackground = palette.text,
            surface = palette.surface,
            onSurface = palette.text,
            surfaceVariant = palette.surfaceAlt,
            onSurfaceVariant = palette.sub,
            outline = palette.line,
            outlineVariant = palette.line2,
            error = palette.danger,
        )
    }
    val shapes = Shapes(
        extraSmall = RoundedCornerShape(8.dp),
        small = RoundedCornerShape(12.dp),
        medium = RoundedCornerShape(16.dp),
        large = RoundedCornerShape(20.dp),
        extraLarge = RoundedCornerShape(24.dp),
    )
    // 设计稿切主题是 280ms 的颜色过渡（墙面 + 玻璃 + 文字一起变），这里把两套令牌包成可动画版本。
    // 原生壳不做墙面/玻璃过渡（它根本没有这些东西），直接给静态令牌，省一次动画开销。
    val liquidTokens = if (liquid) {
        animatedLiquidTokens(if (darkTheme) DarkLiquidTokens else LightLiquidTokens)
    } else {
        if (darkTheme) DarkLiquidTokens else LightLiquidTokens
    }
    val livePalette = animatedDshColors(palette)
    CompositionLocalProvider(
        LocalDshColors provides livePalette,
        LocalLiquidTokens provides liquidTokens,
        LocalDarkTheme provides darkTheme,
        LocalLiquidUi provides liquid,
    ) {
        MaterialTheme(colorScheme = scheme, typography = DshTypography, shapes = shapes, content = content)
    }
}

object DshTheme2 {
    val colors: DshColors
        @Composable @ReadOnlyComposable get() = LocalDshColors.current
    val typography
        @Composable @ReadOnlyComposable get() = LocalDshTypography.current

    /** 液态玻璃 token（墙面 / 玻璃档位 / 色散 / 噪点强度），只在需要手绘玻璃时用。 */
    val liquid: LiquidTokens
        @Composable @ReadOnlyComposable get() = LocalLiquidTokens.current

    /** 当前是不是液态玻璃壳（原生壳下所有玻璃构图都退化成实底）。 */
    val liquidEnabled: Boolean
        @Composable @ReadOnlyComposable get() = LocalLiquidUi.current

    val spacing = DshSpacing
}

val LocalDarkTheme = staticCompositionLocalOf { false }

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

@Composable
fun DshTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    accentId: String = "blue",
    content: @Composable () -> Unit,
) {
    val base = if (darkTheme) DarkDshColors else LightDshColors
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
    val liquid = animatedLiquidTokens(if (darkTheme) DarkLiquidTokens else LightLiquidTokens)
    val livePalette = animatedDshColors(palette)
    CompositionLocalProvider(
        LocalDshColors provides livePalette,
        LocalLiquidTokens provides liquid,
        LocalDarkTheme provides darkTheme,
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
    val spacing = DshSpacing
}

val LocalDarkTheme = staticCompositionLocalOf { false }

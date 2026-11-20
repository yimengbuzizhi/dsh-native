package xin.dshaa.nativeapp.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.SettingsBrightness
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import xin.dshaa.ui.design.component.SettingsGroup
import xin.dshaa.ui.design.component.WaterOrb
import xin.dshaa.ui.design.component.liquidClickable
import xin.dshaa.ui.design.component.liquidEnter
import xin.dshaa.ui.design.component.liquidGlass
import xin.dshaa.ui.design.theme.DshAccents
import xin.dshaa.ui.design.theme.DshTheme2
import xin.dshaa.ui.design.theme.LiquidLevel
import xin.dshaa.ui.design.theme.LiquidRadius
import xin.dshaa.ui.design.theme.LocalLiquidTokens
import xin.dshaa.ui.shellapi.data.SettingsStore

/**
 * 主题和外观（新页面）。
 *
 * 之前设置首页的「主题和外观」这一行错误地指向了 [BehaviorScreen]（`onClick = onOpenBehavior`），
 * 所以点进去看到的是「显示与行为」，深浅色根本没地方切 —— 这是「明暗切换渲染也没做」的直接原因。
 *
 * 本页做三件事：深浅模式（对应设计稿 `data-theme` 的两套令牌）、强调色（`DshAccents` 六套）、
 * 以及一块预览卡（玻璃 + 水球 + 文字），让切换能当场看到效果。
 */
@Composable
fun AppearanceScreen(
    settings: SettingsStore,
    onBack: () -> Unit,
) {
    val c = DshTheme2.colors
    val liquid = LocalLiquidTokens.current

    Column(Modifier.fillMaxSize().background(Color.Transparent)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)
                .liquidGlass(shape = RoundedCornerShape(LiquidRadius.xl), level = LiquidLevel.Panel)
                .statusBarsPadding().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(40.dp).clip(CircleShape).clickable { onBack() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.ArrowBack, "返回", tint = c.text, modifier = Modifier.size(23.dp))
            }
            Spacer(Modifier.width(4.dp))
            Text(
                "主题和外观",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = c.text,
            )
        }

        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            SettingsGroup(icon = Icons.Rounded.Palette, title = "外观模式") {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    ThemeModeRow(
                        title = "跟随系统",
                        subtitle = "系统切深色时界面跟着切",
                        icon = Icons.Rounded.SettingsBrightness,
                        selected = settings.themeMode == "system",
                        onClick = { settings.setThemeMode("system") },
                    )
                    ThemeModeRow(
                        title = "浅色",
                        subtitle = "设计稿默认：亮蓝白墙面 + 白玻璃",
                        icon = Icons.Rounded.LightMode,
                        selected = settings.themeMode == "light",
                        onClick = { settings.setThemeMode("light") },
                    )
                    ThemeModeRow(
                        title = "深色",
                        subtitle = "深蓝夜海墙面 + 半透明玻璃",
                        icon = Icons.Rounded.DarkMode,
                        selected = settings.themeMode == "dark",
                        onClick = { settings.setThemeMode("dark") },
                    )
                }
            }

            SettingsGroup(icon = Icons.Rounded.Palette, title = "强调色") {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    DshAccents.forEach { accent ->
                        val on = settings.accentId == accent.id
                        Box(
                            Modifier
                                .size(44.dp)
                                .liquidGlass(
                                    shape = CircleShape,
                                    level = LiquidLevel.Pill,
                                    tint = accent.color,
                                    tintGradient = if (on) 1f else 0.55f,
                                    elevated = false,
                                )
                                .liquidClickable { settings.setAccentId(accent.id) },
                            contentAlignment = Alignment.Center,
                        ) {
                            if (on) {
                                Icon(Icons.Rounded.Check, accent.name, tint = Color.White, modifier = Modifier.size(20.dp))
                            }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    DshAccents.firstOrNull { it.id == settings.accentId }?.name ?: "经典蓝",
                    style = MaterialTheme.typography.bodySmall,
                    color = c.sub,
                )
            }

            SettingsGroup(icon = Icons.Rounded.Palette, title = "预览") {
                // 一块玻璃卡 + 一颗水球：切换深浅/强调色后立刻能看出差别
                Row(
                    Modifier.fillMaxWidth().liquidEnter()
                        .liquidGlass(
                            shape = RoundedCornerShape(LiquidRadius.m),
                            level = LiquidLevel.Card,
                        )
                        .padding(horizontal = 14.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    WaterOrb(size = 64.dp, poking = false)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            "厚透镜液态玻璃",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = c.text,
                        )
                        Spacer(Modifier.height(3.dp))
                        Text(
                            "墙面 ${if (liquid.isDark) "深蓝夜海" else "亮蓝白"} · 玻璃 ${if (liquid.isDark) "半透明" else "白玻璃"}",
                            style = MaterialTheme.typography.bodySmall,
                            color = c.sub,
                            letterSpacing = 0.2.sp,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ThemeModeRow(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val c = DshTheme2.colors
    val liquid = LocalLiquidTokens.current
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .liquidGlass(
                shape = RoundedCornerShape(LiquidRadius.xs),
                level = LiquidLevel.Pill,
                tint = if (selected) c.brand else c.surfaceAlt,
                tintGradient = if (selected) 0.9f else 0.35f,
                elevated = false,
            )
            .liquidClickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            null,
            tint = if (selected) Color.White else c.sub,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = if (selected) Color.White else c.text,
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = if (selected) Color.White.copy(alpha = 0.78f) else c.sub,
            )
        }
        if (selected) {
            Icon(Icons.Rounded.Check, null, tint = Color.White, modifier = Modifier.size(18.dp))
        }
    }
}

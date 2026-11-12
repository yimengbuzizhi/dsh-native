package xin.dshaa.ui.design.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import xin.dshaa.ui.design.theme.DshTheme2

/**
 * Operit 设置页组件词汇表（源码：ui/features/settings/sections/ModelApiSettingsSection.kt L1519-1788）。
 *
 * 几何/字号按 Operit 原样：卡片圆角 12、内层输入框圆角 6、选择行圆角 10、padding 12/16；
 * 只把颜色换成本项目 token。
 */

/** 分组标题：24dp 主色图标 + 标题（Operit `SettingsSectionHeader`）。 */
@Composable
fun SettingsSectionHeader(
    icon: ImageVector,
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
) {
    val c = DshTheme2.colors
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = c.brand, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = c.text)
            if (subtitle != null) {
                Spacer(Modifier.height(2.dp))
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = c.sub)
            }
        }
    }
}

/** 一个「设置卡」：Operit 的统一外壳（Card 圆角 12、padding 16、内部 12 间距）。 */
@Composable
fun SettingsCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = DshTheme2.colors
    Surface(
        modifier = modifier.fillMaxWidth().padding(top = 4.dp),
        shape = RoundedCornerShape(14.dp),
        color = c.surface,
        border = BorderStroke(1.dp, c.line2),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

/** 带标题的整块设置区：分组标题 + 卡片。 */
@Composable
fun SettingsGroup(
    icon: ImageVector,
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.fillMaxWidth()) {
        SettingsSectionHeader(icon, title, subtitle = subtitle)
        SettingsCard(content = content)
        Spacer(Modifier.height(16.dp))
    }
}

/** Operit `SettingsTextField`：标题 + 副标题 + 内嵌输入框。 */
@Composable
fun SettingsTextField(
    title: String,
    subtitle: String? = null,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    singleLine: Boolean = true,
    enabled: Boolean = true,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    trailingContent: (@Composable () -> Unit)? = null,
    trailingText: String? = null,
    onClick: (() -> Unit)? = null,
    /** 焦点变化（用于「编辑中不校验、失焦再规范化」的输入框）。 */
    onFocusChanged: ((Boolean) -> Unit)? = null,
    /**
     * 长文本折叠（M79，用户 2026-11-12）：内联只显示前几行，超出的靠「全屏」看/改。
     * 非 null 且 [singleLine] = false 时给输入框加高度上限（内部可滚动）。
     */
    collapsedMaxHeight: androidx.compose.ui.unit.Dp? = null,
    /** 输入框下方的一排小动作（「全屏」「导入文件」…），由调用方给。 */
    footerActions: (@Composable () -> Unit)? = null,
) {
    val c = DshTheme2.colors
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier),
        shape = RoundedCornerShape(10.dp),
        color = c.surfaceAlt.copy(alpha = if (c.isDark) 0.55f else 0.75f),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = c.text)
            if (subtitle != null) {
                Spacer(Modifier.height(2.dp))
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = c.sub)
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Surface(
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(6.dp),
                    color = c.background.copy(alpha = if (c.isDark) 0.75f else 0.6f),
                    border = BorderStroke(1.dp, c.line.copy(alpha = 0.6f)),
                ) {
                    BasicTextField(
                        value = value,
                        onValueChange = onValueChange,
                        singleLine = singleLine,
                        enabled = enabled,
                        keyboardOptions = keyboardOptions,
                        keyboardActions = keyboardActions,
                        visualTransformation = visualTransformation,
                        cursorBrush = androidx.compose.ui.graphics.SolidColor(c.brand),
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(
                                if (collapsedMaxHeight != null && !singleLine) {
                                    Modifier.heightIn(max = collapsedMaxHeight)
                                } else {
                                    Modifier
                                },
                            )
                            .then(
                                if (onFocusChanged != null) {
                                    Modifier.onFocusChanged { onFocusChanged(it.isFocused) }
                                } else {
                                    Modifier
                                },
                            ),
                        textStyle = TextStyle(
                            color = if (enabled) c.brandBright else c.sub,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                        ),
                        decorationBox = { inner ->
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                androidx.compose.foundation.layout.Box(Modifier.weight(1f)) {
                                    if (value.isEmpty() && placeholder.isNotEmpty()) {
                                        Text(placeholder, style = MaterialTheme.typography.bodySmall, color = c.faint)
                                    }
                                    inner()
                                }
                                if (trailingText != null) {
                                    Spacer(Modifier.width(4.dp))
                                    Text(trailingText, style = MaterialTheme.typography.bodySmall, color = c.faint)
                                }
                            }
                        },
                    )
                }
                trailingContent?.invoke()
            }
            if (footerActions != null) {
                Spacer(Modifier.height(8.dp))
                footerActions()
            }
        }
    }
}

/** Operit `SettingsSelectorRow`：标题/副标题 + 右侧当前值 + 下拉箭头。 */
@Composable
fun SettingsSelectorRow(
    title: String,
    subtitle: String,
    value: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val c = DshTheme2.colors
    Surface(
        modifier = modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(enabled = enabled) { onClick() },
        shape = RoundedCornerShape(10.dp),
        color = c.surfaceAlt.copy(alpha = if (c.isDark) 0.55f else 0.75f),
    ) {
        Row(
            Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = c.text)
                Spacer(Modifier.height(2.dp))
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = c.sub)
            }
            Spacer(Modifier.width(12.dp))
            Text(
                value,
                style = MaterialTheme.typography.bodyMedium,
                color = c.brandBright,
                modifier = Modifier.weight(0.6f, fill = false),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = null, tint = c.brand)
        }
    }
}

/** Operit `SettingsSwitchRow`：标题/副标题 + 右侧开关。 */
@Composable
fun SettingsSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val c = DshTheme2.colors
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = c.surfaceAlt.copy(alpha = if (c.isDark) 0.55f else 0.75f),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = if (enabled) c.text else c.sub,
                )
                Spacer(Modifier.height(2.dp))
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = c.sub)
            }
            Spacer(Modifier.width(8.dp))
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                enabled = enabled,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = c.onHero,
                    checkedTrackColor = c.brand,
                    uncheckedThumbColor = c.faint,
                    uncheckedTrackColor = c.line,
                    uncheckedBorderColor = c.line,
                ),
            )
        }
    }
}

/** Operit `SettingsInfoBanner`：提示条。 */
@Composable
fun SettingsInfoBanner(text: String, modifier: Modifier = Modifier, tint: Color? = null) {
    val c = DshTheme2.colors
    val color = tint ?: c.brand
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = color.copy(alpha = if (c.isDark) 0.16f else 0.10f),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Info, contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(text, style = MaterialTheme.typography.bodySmall, color = c.text.copy(alpha = 0.9f))
        }
    }
}

/** Operit `CompactSettingsItem`：图标 + 标题/副标题 + 右箭头（设置首页的行）。 */
@Composable
fun SettingsNavRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
) {
    val c = DshTheme2.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable { onClick() }
            .padding(horizontal = 4.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = c.brand, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(1.dp))
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = c.sub, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        trailing?.invoke() ?: Icon(
            Icons.Rounded.KeyboardArrowRight,
            contentDescription = null,
            tint = c.faint,
            modifier = Modifier.size(18.dp),
        )
    }
}

/** 可折叠卡片（Operit 的「上下文设置 / 思考配置 / 模型参数设置」都是这个）。 */
@Composable
fun CollapsibleSettingsCard(
    icon: ImageVector,
    title: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = DshTheme2.colors
    Surface(
        modifier = modifier.fillMaxWidth().padding(top = 4.dp),
        shape = RoundedCornerShape(14.dp),
        color = c.surface,
        border = BorderStroke(1.dp, c.line2),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { onToggle() },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(icon, contentDescription = null, tint = c.brand, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = c.text)
                    if (subtitle != null) {
                        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = c.sub, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
                Icon(
                    if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                    contentDescription = if (expanded) "收起" else "展开",
                    tint = c.sub,
                )
            }
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(tween(180)) + fadeIn(tween(140)),
                exit = shrinkVertically(tween(160)) + fadeOut(tween(100)),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { content() }
            }
        }
    }
}

/** 圆角小胶囊标签（Operit 到处用的小 chip）。 */
@Composable
fun MiniChip(text: String, modifier: Modifier = Modifier, color: Color? = null) {
    val c = DshTheme2.colors
    val tint = color ?: c.sub
    Surface(modifier = modifier, shape = RoundedCornerShape(999.dp), color = tint.copy(alpha = 0.14f)) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            color = tint,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

/** 高度受限的可滚动文本（工具输出/思考内容）。 */
@Composable
fun ScrollableText(
    text: String,
    maxHeight: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodySmall,
    color: Color = DshTheme2.colors.sub,
) {
    Text(
        text,
        style = style,
        color = color,
        modifier = modifier
            .heightIn(max = maxHeight)
            .verticalScroll(rememberScrollState()),
    )
}

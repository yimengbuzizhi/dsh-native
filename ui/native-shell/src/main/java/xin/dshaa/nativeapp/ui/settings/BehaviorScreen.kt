package xin.dshaa.nativeapp.ui.settings

import xin.dshaa.ui.design.component.liquidGlass
import xin.dshaa.ui.design.theme.LiquidLevel
import xin.dshaa.ui.design.theme.LiquidRadius
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Color
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import xin.dshaa.ui.shellapi.data.SettingsStore
import xin.dshaa.ui.design.component.SettingsCard
import xin.dshaa.ui.design.component.SettingsExpandablePickerRow
import xin.dshaa.ui.design.component.SettingsGroup
import xin.dshaa.ui.design.component.SettingsInfoBanner
import xin.dshaa.ui.design.component.SettingsOption
import xin.dshaa.ui.design.component.SettingsSwitchRow
import xin.dshaa.ui.design.theme.DshTheme2
import xin.dshaa.ui.design.theme.wash

/**
 * 显示与行为：思考过程展开偏好（Operit `expand_thinking_process_default`）+ 时间上下文 + 工具审批 + 主题。
 *
 * 2026-11-10（M71）：大肥鱼 / 3D 鲸鱼娘 / 鲸鱼娘挂件三组原生替身设置项全部删除
 * （用户原话「这些都不是我想要的，赶紧删掉吧」）；这些插件的窗口改由通用客户端插件宿主
 * 打开插件自己的 `dsh.client`，设置入口在插件详情页，不再占「显示与行为」。
 */
@Composable
fun BehaviorScreen(
    settings: SettingsStore,
    onBack: () -> Unit,
) {
    val c = DshTheme2.colors
    Column(Modifier.fillMaxSize().background(Color.Transparent)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp).liquidGlass(shape = RoundedCornerShape(LiquidRadius.xl), level = LiquidLevel.Panel).statusBarsPadding().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(40.dp).clip(CircleShape).clickable { onBack() }, contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.ArrowBack, "返回", tint = c.text, modifier = Modifier.size(23.dp))
            }
            Spacer(Modifier.width(4.dp))
            Text("显示与行为", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, color = c.text)
        }

        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp)) {
            SettingsGroup(icon = Icons.Rounded.Visibility, title = "对话显示") {
                SettingsSwitchRow(
                    title = "默认展开思考过程",
                    checked = settings.expandThinking,
                    onCheckedChange = { settings.setExpandThinking(it) },
                )
                SettingsSwitchRow(
                    title = "时间上下文",
                    checked = settings.timeContext,
                    onCheckedChange = { settings.setTimeContext(it) },
                )
            }

            // 0.3.4（用户 2026-11-15 第 1 条）：之前两行各画一个下拉箭头，点一下却是开关切换。
            // 现在合并成一行「发送方式」，点开在行内二选一；聊天页只有一个普通发送按钮，不加按钮。
            SettingsGroup(icon = Icons.Rounded.Visibility, title = "发送方式") {
                SettingsExpandablePickerRow(
                    title = "生成中发送",
                    value = settings.sendWhileRunningMode,
                    options = listOf(
                        SettingsOption("queue", "排队发送", "当前任务完成后，按顺序处理新消息"),
                        SettingsOption("steer", "插话发送", "在下一次安全边界交付补充，等待当前工具完成"),
                    ),
                    onSelect = { settings.setSendWhileRunningMode(it) },
                )
            }

            // M79（用户 2026-11-12）：上下文自动压缩以前只有「窗口 × 0.8」写死、界面看不到，
            // 用户的原话是「没有压缩上下文功能，或者是说没有可以设置上下文、用到一定程度自动压缩」。
            SettingsGroup(icon = Icons.Rounded.Memory, title = "上下文与压缩") {
                SettingsSwitchRow(
                    title = "自动压缩上下文",
                    checked = settings.autoCompact,
                    onCheckedChange = { settings.setAutoCompact(it) },
                )
                Text(
                    "触发比例：${(settings.autoCompactRatio * 100).toInt()}%",
                    style = MaterialTheme.typography.bodyMedium,
                    color = c.text,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(50, 70, 80, 90).forEach { percent ->
                        val selected = (settings.autoCompactRatio * 100).toInt() == percent
                        Box(
                            Modifier
                                .weight(1f)
                                .clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                                .background(if (selected) c.brand else c.surfaceAlt.wash(0.7f))
                                .clickable { settings.setAutoCompactRatio(percent / 100f) }
                                .padding(vertical = 8.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                "$percent%",
                                style = MaterialTheme.typography.labelMedium,
                                color = if (selected) androidx.compose.ui.graphics.Color.White else c.sub,
                            )
                        }
                    }
                }
                SettingsInfoBanner(
                    "当前模型窗口：" +
                        (if (settings.contextWindow > 0) {
                            "${settings.contextWindow} tokens"
                        } else {
                            "未设置（自动压缩不生效）"
                        }),
                )
            }

            SettingsGroup(icon = Icons.Rounded.Security, title = "工具权限") {
                SettingsExpandablePickerRow(
                    title = "审批方式",
                    value = settings.approvalMode,
                    options = listOf(
                        SettingsOption("ask", "每次询问", "write/edit/bash 首次调用会弹审批，可选「总是允许」"),
                        SettingsOption("auto", "自动允许", "模型调用 write/edit/bash 不再弹窗，风险自负"),
                    ),
                    onSelect = { settings.setApprovalMode(it) },
                )
                SettingsInfoBanner(
                    if (settings.approvalMode == "auto") {
                        "自动允许：模型调用 write/edit/bash 不再弹窗，风险自负。"
                    } else {
                        "每次询问：write/edit/bash 首次调用会弹审批，可选「总是允许」。"
                    },
                )
            }

            SettingsGroup(icon = Icons.Rounded.Palette, title = "主题") {
                SettingsExpandablePickerRow(
                    title = "外观",
                    value = settings.themeMode,
                    options = listOf(
                        SettingsOption("system", "跟随系统"),
                        SettingsOption("light", "浅色"),
                        SettingsOption("dark", "深色"),
                    ),
                    onSelect = { settings.setThemeMode(it) },
                )
                SettingsExpandablePickerRow(
                    title = "强调色",
                    value = settings.accentId,
                    options = xin.dshaa.ui.design.theme.DshAccents.map {
                        SettingsOption(it.id, it.name)
                    },
                    onSelect = { settings.setAccentId(it) },
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

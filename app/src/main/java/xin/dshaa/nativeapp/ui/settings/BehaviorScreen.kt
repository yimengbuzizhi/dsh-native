package xin.dshaa.nativeapp.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
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
import xin.dshaa.nativeapp.data.SettingsStore
import xin.dshaa.ui.design.component.SettingsCard
import xin.dshaa.ui.design.component.SettingsGroup
import xin.dshaa.ui.design.component.SettingsInfoBanner
import xin.dshaa.ui.design.component.SettingsSelectorRow
import xin.dshaa.ui.design.component.SettingsSwitchRow
import xin.dshaa.ui.design.theme.DshTheme2

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
    Column(Modifier.fillMaxSize().background(c.background)) {
        Row(
            Modifier.fillMaxWidth().background(c.surface).statusBarsPadding().padding(horizontal = 12.dp, vertical = 10.dp),
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
                    subtitle = "对应 Operit 的 expand_thinking_process_default；关闭时思考结束自动折叠",
                    checked = settings.expandThinking,
                    onCheckedChange = { settings.setExpandThinking(it) },
                )
                SettingsSwitchRow(
                    title = "时间上下文",
                    subtitle = "每个请求带上当前时间与距上次消息的间隔（上游 dsh-time-context，10 分钟节流）",
                    checked = settings.timeContext,
                    onCheckedChange = { settings.setTimeContext(it) },
                )
            }

            SettingsGroup(icon = Icons.Rounded.Security, title = "工具权限") {
                SettingsSelectorRow(
                    title = "审批方式",
                    subtitle = "写类工具与命令执行",
                    value = if (settings.approvalMode == "auto") "自动允许" else "每次询问",
                    onClick = { settings.setApprovalMode(if (settings.approvalMode == "auto") "ask" else "auto") },
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
                SettingsSelectorRow(
                    title = "外观",
                    subtitle = "跟随系统 / 浅色 / 深色",
                    value = when (settings.themeMode) {
                        "light" -> "浅色"
                        "dark" -> "深色"
                        else -> "跟随系统"
                    },
                    onClick = {
                        settings.setThemeMode(
                            when (settings.themeMode) {
                                "system" -> "light"
                                "light" -> "dark"
                                else -> "system"
                            },
                        )
                    },
                )
                SettingsSelectorRow(
                    title = "强调色",
                    subtitle = "6 套预设（Operit 蓝 / 靛 / 青 / 紫 / 琥珀 / 玫瑰）",
                    value = xin.dshaa.ui.design.theme.accentById(settings.accentId).name,
                    onClick = {
                        val list = xin.dshaa.ui.design.theme.DshAccents
                        val index = list.indexOfFirst { it.id == settings.accentId }.coerceAtLeast(0)
                        settings.setAccentId(list[(index + 1) % list.size].id)
                    },
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

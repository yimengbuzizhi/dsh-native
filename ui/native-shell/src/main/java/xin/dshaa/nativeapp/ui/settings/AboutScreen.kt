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
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Widgets
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import xin.dshaa.ui.design.component.SettingsGroup
import xin.dshaa.ui.design.theme.DshTheme2

/**
 * M84-B：「关于」从设置页的弹窗补成**二级页**。
 *
 * 内容沿用 M79 的关于弹窗（版本 / 协议 / 界面参考 / 许可），入口由内置页面插件
 * `builtin.page.about` 提供——关掉后设置页的「关于」行消失、直接路由也被拦。
 */
@Composable
fun AboutScreen(
    onBack: () -> Unit,
    versionName: String = "",
    versionCode: Int = 0,
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
            Text("关于", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, color = c.text)
        }

        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp)) {
            SettingsGroup(icon = Icons.Rounded.Info, title = "鲸歌（DSH 原生版）") {
                AboutLine("版本", "$versionName（versionCode $versionCode）")
                AboutLine("协议", "Anthropic Messages（DSH 0.2.2-rc.1）")
                AboutLine("实现", "Kotlin + Jetpack Compose 重写")
                AboutLine("许可", "LGPL-3.0")
            }

            Spacer(Modifier.height(12.dp))
            SettingsGroup(icon = Icons.Rounded.Palette, title = "界面参考") {
                AboutLine(
                    "Operit",
                    "设置分组、模型配置页、思考折叠与角色面板（LGPL-3.0）",
                )
                AboutLine(
                    "RikkaHub",
                    "聊天落地、抽屉会话列表、工作区切换（AGPL-3.0；只学信息架构与排版，未复制代码）",
                )
            }

            Spacer(Modifier.height(12.dp))
            SettingsGroup(icon = Icons.Rounded.Widgets, title = "插件化") {
                AboutLine(
                    "当前进度",
                    "M84-B 页面插件化：页面清单由内置插件提供，可逐页开关；工具按 12 个插件组注册/注销",
                )
                AboutLine("终局", "一切功能都能被插件增加、修改、替换（docs/115）")
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun AboutLine(title: String, value: String) {
    val c = DshTheme2.colors
    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 9.dp)) {
        Text(title, style = MaterialTheme.typography.labelMedium, color = c.sub, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(3.dp))
        Text(value, style = MaterialTheme.typography.bodySmall, color = c.text)
    }
}

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.Api
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.FolderZip
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.Workspaces
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import xin.dshaa.nativeapp.BuildConfig
import xin.dshaa.nativeapp.data.SettingsStore
import xin.dshaa.ui.design.component.MiniChip
import xin.dshaa.ui.design.component.SettingsCard
import xin.dshaa.ui.design.component.SettingsGroup
import xin.dshaa.ui.design.component.SettingsNavRow
import xin.dshaa.ui.design.theme.DshTheme2

/**
 * 设置首页：照 Operit `SettingsScreen` 的信息架构（分组标题 + 卡片 + 图标/标题/副标题/箭头行），
 * 文案换成 DSH 的实体（账号/助手/模型与参数/数据/关于）。
 */
@Composable
fun SettingsScreen(
    settings: SettingsStore,
    onOpenDrawer: () -> Unit,
    onOpenModelConfig: () -> Unit,
    onOpenAssistant: () -> Unit,
    onOpenBehavior: () -> Unit,
    onOpenBackup: () -> Unit,
    onOpenMigration: () -> Unit,
    onOpenSkills: () -> Unit,
    onOpenSessions: () -> Unit,
    onOpenWorkspaces: () -> Unit = {},
    onOpenPermissions: () -> Unit = {},
    /** 已收进设置的「功能与工具」入口（2026-11-08 夜间：删掉抽屉「功能」后的唯一入口）。 */
    onOpenFeature: (xin.dshaa.nativeapp.ui.Screen) -> Unit = {},
    currentWorkspaceName: String = "",
    onOpenAbout: () -> Unit,
) {
    val c = DshTheme2.colors
    val provider = SettingsStore.providerOf(settings.providerId)
    Column(Modifier.fillMaxSize().background(c.background)) {
        // Operit 顶栏：☰ + 页面标题
        Row(
            Modifier.fillMaxWidth().background(c.surface).statusBarsPadding().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(40.dp).clip(CircleShape).clickable { onOpenDrawer() }, contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Menu, "菜单", tint = c.text, modifier = Modifier.size(23.dp))
            }
            Spacer(Modifier.width(4.dp))
            Text("设置", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = c.text)
        }

        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp)) {
            // ======= 账号 =======
            SettingsGroup(icon = Icons.Rounded.AccountCircle, title = "账号") {
                SettingsNavRow(
                    icon = Icons.Rounded.Person,
                    title = "DeepSeek 账号",
                    subtitle = if (settings.accountToken.isNotBlank()) {
                        "已登录 · 令牌尾号 ${settings.accountToken.takeLast(4)}"
                    } else {
                        "未登录 · 可导入桌面版令牌"
                    },
                    onClick = onOpenModelConfig,
                )
                SettingsNavRow(
                    icon = Icons.Rounded.Api,
                    title = "当前提供商",
                    subtitle = "${provider.name} · ${settings.model}",
                    onClick = onOpenModelConfig,
                )
            }

            // ======= 个性化 =======
            SettingsGroup(icon = Icons.Rounded.Person, title = "个性化") {
                SettingsNavRow(
                    icon = Icons.Rounded.Person,
                    title = "助手配置",
                    subtitle = "名字、头像、人设（当前：${settings.assistantName}）",
                    onClick = onOpenAssistant,
                )
                SettingsNavRow(
                    icon = Icons.Rounded.Visibility,
                    title = "显示与行为",
                    subtitle = "思考过程展开、工具审批方式",
                    onClick = onOpenBehavior,
                )
                SettingsNavRow(
                    icon = Icons.Rounded.Palette,
                    title = "主题和外观",
                    subtitle = "跟随系统深色模式",
                    onClick = onOpenBehavior,
                    trailing = { MiniChip("跟随系统") },
                )
            }

            // ======= 工作区（2026-11-07 RikkaHub 外壳：会话按 cwd 分组，工具根跟着切） =======
            SettingsGroup(icon = Icons.Rounded.Workspaces, title = "工作区") {
                SettingsNavRow(
                    icon = Icons.Rounded.Workspaces,
                    title = "工作区管理",
                    subtitle = if (currentWorkspaceName.isBlank()) {
                        "切换、新建、重命名；会话按工作区分开"
                    } else {
                        "当前：$currentWorkspaceName · 可切换/新建/重命名"
                    },
                    onClick = onOpenWorkspaces,
                )
            }

            // ======= 功能与工具（2026-11-08 夜间：控制台与抽屉「功能」删除后，页面入口收在这里；
            // 2026-11-09：工具箱整页下线，权限与能力入口收进这里） =======
            SettingsGroup(icon = Icons.Rounded.Build, title = "功能与工具") {
                SettingsNavRow(
                    icon = Icons.Rounded.Extension,
                    title = "插件",
                    subtitle = "本地导入、试运行、配置与权限",
                    onClick = { onOpenFeature(xin.dshaa.nativeapp.ui.Screen.Plugins) },
                )
                SettingsNavRow(
                    icon = Icons.Rounded.Person,
                    title = "子代理",
                    subtitle = "运行中 / 历史子会话，可停止与回放",
                    onClick = { onOpenFeature(xin.dshaa.nativeapp.ui.Screen.Agents) },
                )
                SettingsNavRow(
                    icon = Icons.Rounded.PlayArrow,
                    title = "后台任务",
                    subtitle = "bash 后台执行与 job_* 的输出",
                    onClick = { onOpenFeature(xin.dshaa.nativeapp.ui.Screen.Jobs) },
                )
                SettingsNavRow(
                    icon = Icons.Rounded.Terminal,
                    title = "终端",
                    subtitle = "设备上的持久 shell 会话",
                    onClick = { onOpenFeature(xin.dshaa.nativeapp.ui.Screen.Terminal) },
                )
                SettingsNavRow(
                    icon = Icons.Rounded.PlayArrow,
                    title = "虚拟屏",
                    subtitle = "showerclient：后台虚拟显示器",
                    onClick = { onOpenFeature(xin.dshaa.nativeapp.ui.Screen.VirtualScreen) },
                )
                SettingsNavRow(
                    icon = Icons.Rounded.Visibility,
                    title = "无障碍",
                    subtitle = "读屏 / 点击 / 截图服务状态",
                    onClick = { onOpenFeature(xin.dshaa.nativeapp.ui.Screen.Accessibility) },
                )
                SettingsNavRow(
                    icon = Icons.Rounded.Security,
                    title = "权限与能力",
                    subtitle = "Shizuku / 无障碍 / 悬浮窗 / 通知 / 存储",
                    onClick = onOpenPermissions,
                    trailing = { MiniChip("设备") },
                )
            }

            // ======= AI 模型配置 =======
            SettingsGroup(icon = Icons.Rounded.Settings, title = "AI模型配置") {
                SettingsNavRow(
                    icon = Icons.Rounded.Api,
                    title = "模型与参数配置",
                    subtitle = "模型参数和API配置",
                    onClick = onOpenModelConfig,
                )
                SettingsNavRow(
                    icon = Icons.Rounded.Tune,
                    title = "功能模型配置",
                    subtitle = "对话 / 标题生成分别用哪个模型",
                    onClick = onOpenModelConfig,
                    trailing = { MiniChip("共用") },
                )
            }

            // ======= 数据和权限 =======
            SettingsGroup(icon = Icons.Rounded.Security, title = "数据和权限") {
                SettingsNavRow(
                    icon = Icons.Rounded.Security,
                    title = "工具权限",
                    subtitle = if (settings.approvalMode == "auto") "自动允许（不再弹审批）" else "写类工具每次询问",
                    onClick = onOpenBehavior,
                )
                SettingsNavRow(
                    icon = Icons.Rounded.Storage,
                    title = "对话历史管理",
                    subtitle = "本地 v4 会话，只读回放",
                    onClick = onOpenSessions,
                )
                SettingsNavRow(
                    icon = Icons.Rounded.FolderZip,
                    title = "备份与恢复",
                    subtitle = "导出会话/插件/设置 zip；恢复后会话可直接续聊",
                    onClick = onOpenBackup,
                    trailing = { MiniChip("不含密钥", color = DshTheme2.colors.success) },
                )
                SettingsNavRow(
                    icon = Icons.Rounded.Restore,
                    title = "旧版数据导入",
                    subtitle = "从 DSH备份-*.zip 只读恢复旧会话（v0–v4）",
                    onClick = onOpenMigration,
                    trailing = { MiniChip("只读") },
                )
                SettingsNavRow(
                    icon = Icons.Rounded.Star,
                    title = "技能",
                    subtitle = "SKILL.md 目录：发现、优先级与加载（skill 工具）",
                    onClick = onOpenSkills,
                    trailing = { MiniChip("M19") },
                )
                SettingsNavRow(
                    icon = Icons.Rounded.DeleteSweep,
                    title = "隐私与数据清理",
                    subtitle = "清理 Cookie / 临时文件",
                    onClick = onOpenBehavior,
                    trailing = { MiniChip("待做") },
                )
            }

            // ======= 关于 =======
            SettingsGroup(icon = Icons.Rounded.Info, title = "关于") {
                SettingsNavRow(
                    icon = Icons.Rounded.Info,
                    title = "关于 DSH 原生版",
                    subtitle = "版本 ${BuildConfig.VERSION_NAME} · LGPL-3.0",
                    onClick = onOpenAbout,
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

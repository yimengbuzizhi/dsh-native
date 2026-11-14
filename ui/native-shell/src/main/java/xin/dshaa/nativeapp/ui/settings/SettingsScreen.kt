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
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.Api
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.FolderZip
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Memory
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
import androidx.compose.material.icons.rounded.Widgets
import androidx.compose.material.icons.rounded.Workspaces
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import xin.dshaa.ui.shellapi.BuiltinPageIds
import xin.dshaa.nativeapp.data.GitHubAuth
import xin.dshaa.ui.shellapi.data.SettingsStore
import xin.dshaa.ui.design.component.SettingsGroup
import xin.dshaa.ui.design.component.SettingsNavRow
import xin.dshaa.ui.design.theme.DshTheme2

/**
 * 设置首页：照 Operit `SettingsScreen` 的信息架构（分组标题 + 卡片 + 图标/标题/副标题/箭头行），
 * 文案换成 DSH 的实体（账号/助手/模型与参数/数据/关于）。
 *
 * M84-B：所有页面入口都按内置页面插件的开关过滤（`screenEnabled(pageId)`）；
 * 一个分组里的入口全被关掉时整张卡片也不显示，不留空壳。
 */
@Composable
fun SettingsScreen(
    settings: SettingsStore,
    onOpenDrawer: () -> Unit,
    /** 滚动位置由外层持有：进二级页再返回时不再跳回顶部（M74，用户 23:00 第 3 条）。 */
    scrollState: androidx.compose.foundation.ScrollState = rememberScrollState(),
    /** 宽屏侧边栏常驻时隐藏 ☰（抽屉已经一直显示）。 */
    menuVisible: Boolean = true,
    /** app 版本号（宿主传入；UI 模块不依赖 app BuildConfig）。 */
    versionName: String = "",
    onOpenModelConfig: () -> Unit,
    onOpenAssistant: () -> Unit,
    onOpenBehavior: () -> Unit,
    onOpenPrivacy: () -> Unit,
    onOpenBackup: () -> Unit,
    onOpenMigration: () -> Unit,
    onOpenSkills: () -> Unit,
    onOpenSessions: () -> Unit,
    onOpenWorkspaces: () -> Unit = {},
    onOpenPermissions: () -> Unit = {},
    /** M83 阶段 4：远程 DSH（地址/令牌/会话/远程聊天）。 */
    onOpenRemote: () -> Unit = {},
    /** 已收进设置的「功能与工具」入口（2026-11-08 夜间：删掉抽屉「功能」后的唯一入口）。 */
    onOpenFeature: (String) -> Unit = {},
    /** M83 阶段 2 / M84-B：内置页面插件被关掉时，设置里的入口也不显示。 */
    screenEnabled: (String) -> Boolean = { true },
    currentWorkspaceName: String = "",
    onOpenAbout: () -> Unit,
) {
    val c = DshTheme2.colors
    val context = LocalContext.current
    val github = remember { GitHubAuth.get(context) }
    var showGitHubLogin by remember { mutableStateOf(false) }
    val provider = SettingsStore.providerOf(settings.providerId)

    // 每个入口对应一个页面插件；关掉后行不显示（整组都关掉时整组不显示）。
    val modelOn = screenEnabled(BuiltinPageIds.ModelConfig)
    val assistantOn = screenEnabled(BuiltinPageIds.Assistant)
    val behaviorOn = screenEnabled(BuiltinPageIds.Behavior)
    val workspacesOn = screenEnabled(BuiltinPageIds.Workspaces)
    val memoryOn = screenEnabled(BuiltinPageIds.Memory)
    val packagesOn = screenEnabled(BuiltinPageIds.Packages)
    val agentsOn = screenEnabled(BuiltinPageIds.Agents)
    val jobsOn = screenEnabled(BuiltinPageIds.Jobs)
    val terminalOn = screenEnabled(BuiltinPageIds.Terminal)
    val virtualScreenOn = screenEnabled(BuiltinPageIds.VirtualScreen)
    val accessibilityOn = screenEnabled(BuiltinPageIds.Accessibility)
    val permissionsOn = screenEnabled(BuiltinPageIds.Permissions)
    val remoteOn = screenEnabled(BuiltinPageIds.RemoteDsh)
    val sessionsOn = screenEnabled(BuiltinPageIds.Sessions)
    val backupOn = screenEnabled(BuiltinPageIds.Backup)
    val migrationOn = screenEnabled(BuiltinPageIds.DataMigration)
    val skillsOn = screenEnabled(BuiltinPageIds.Skills)
    val privacyOn = screenEnabled(BuiltinPageIds.Privacy)
    val aboutOn = screenEnabled(BuiltinPageIds.About)
    val featureOn = memoryOn || packagesOn || skillsOn || agentsOn || jobsOn || terminalOn ||
        virtualScreenOn || accessibilityOn || permissionsOn || remoteOn

    Column(Modifier.fillMaxSize().background(c.background)) {
        // Operit 顶栏：☰ + 页面标题
        Row(
            Modifier.fillMaxWidth().background(c.surface).statusBarsPadding().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (menuVisible) {
                Box(Modifier.size(40.dp).clip(CircleShape).clickable { onOpenDrawer() }, contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Menu, "菜单", tint = c.text, modifier = Modifier.size(23.dp))
                }
                Spacer(Modifier.width(4.dp))
            }
            Text("设置", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = c.text)
        }

        Column(Modifier.fillMaxSize().verticalScroll(scrollState).padding(horizontal = 16.dp, vertical = 8.dp)) {
            // ======= 账号 =======
            SettingsGroup(icon = Icons.Rounded.AccountCircle, title = "账号") {
                SettingsNavRow(
                    icon = Icons.Rounded.AccountCircle,
                    title = "GitHub 账号",
                    subtitle = github.user?.let { "已登录 · ${it.login}" } ?: "未登录 · 点击登录 GitHub",
                    onClick = { showGitHubLogin = true },
                )
                if (modelOn) {
                    SettingsNavRow(
                        icon = Icons.Rounded.Api,
                        title = "当前提供商",
                        subtitle = "${provider.name} · ${settings.model}",
                        onClick = onOpenModelConfig,
                    )
                }
            }

            // ======= 个性化 =======
            if (assistantOn || behaviorOn) {
                SettingsGroup(icon = Icons.Rounded.Person, title = "个性化") {
                    if (assistantOn) {
                        SettingsNavRow(
                            icon = Icons.Rounded.Person,
                            title = "助手配置",
                            subtitle = "名字、头像、人设（当前：${settings.assistantName}）",
                            onClick = onOpenAssistant,
                        )
                    }
                    if (behaviorOn) {
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
                        )
                    }
                }
            }

            // ======= 工作区（2026-11-07 RikkaHub 外壳：会话按 cwd 分组，工具根跟着切） =======
            if (workspacesOn) {
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
            }

            // ======= 功能与工具（2026-11-08 夜间：控制台与抽屉「功能」删除后，页面入口收在这里；
            // 2026-11-09：工具箱整页下线，权限与能力入口收进这里） =======
            if (featureOn) {
                SettingsGroup(icon = Icons.Rounded.Build, title = "功能与工具") {
                    if (memoryOn) {
                        SettingsNavRow(
                            icon = Icons.Rounded.Memory,
                            title = "记忆库",
                            subtitle = "记忆图谱 / 文件夹 / 标签 / 文档；AI 可读写",
                            onClick = { onOpenFeature(BuiltinPageIds.Memory) },
                        )
                    }
                    if (packagesOn) {
                        SettingsNavRow(
                            icon = Icons.Rounded.Widgets,
                            title = "包管理",
                            subtitle = "插件 / 技能 / MCP：导入、启停与配置",
                            onClick = { onOpenFeature(BuiltinPageIds.Packages) },
                        )
                    }
                    if (skillsOn) {
                        SettingsNavRow(
                            icon = Icons.Rounded.Star,
                            title = "技能",
                            subtitle = "技能文件扫描、开关与删除（与包管理 → 技能同一份）",
                            onClick = onOpenSkills,
                        )
                    }
                    if (agentsOn) {
                        SettingsNavRow(
                            icon = Icons.Rounded.Person,
                            title = "子代理",
                            subtitle = "运行中 / 历史子会话，可停止与回放",
                            onClick = { onOpenFeature(BuiltinPageIds.Agents) },
                        )
                    }
                    if (jobsOn) {
                        SettingsNavRow(
                            icon = Icons.Rounded.PlayArrow,
                            title = "后台任务",
                            subtitle = "bash 后台执行与 job_* 的输出",
                            onClick = { onOpenFeature(BuiltinPageIds.Jobs) },
                        )
                    }
                    if (terminalOn) {
                        SettingsNavRow(
                            icon = Icons.Rounded.Terminal,
                            title = "终端",
                            subtitle = "设备上的持久 shell 会话",
                            onClick = { onOpenFeature(BuiltinPageIds.Terminal) },
                        )
                    }
                    if (virtualScreenOn) {
                        SettingsNavRow(
                            icon = Icons.Rounded.PlayArrow,
                            title = "虚拟屏",
                            subtitle = "showerclient：后台虚拟显示器",
                            onClick = { onOpenFeature(BuiltinPageIds.VirtualScreen) },
                        )
                    }
                    if (accessibilityOn) {
                        SettingsNavRow(
                            icon = Icons.Rounded.Visibility,
                            title = "无障碍",
                            subtitle = "读屏 / 点击 / 截图服务状态",
                            onClick = { onOpenFeature(BuiltinPageIds.Accessibility) },
                        )
                    }
                    if (permissionsOn) {
                        SettingsNavRow(
                            icon = Icons.Rounded.Security,
                            title = "权限与能力",
                            subtitle = "Shizuku / 无障碍 / 悬浮窗 / 通知 / 存储",
                            onClick = onOpenPermissions,
                        )
                    }
                    if (remoteOn) {
                        SettingsNavRow(
                            icon = Icons.Rounded.Cloud,
                            title = "远程 DSH",
                            subtitle = "连电脑上的 DSH：地址 + 令牌；会话/消息/工具都在鲸歌里",
                            onClick = onOpenRemote,
                        )
                    }
                }
            }

            // ======= AI 模型配置 =======
            if (modelOn) {
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
                    )
                }
            }

            // ======= 数据和权限 =======
            if (behaviorOn || sessionsOn || backupOn || migrationOn || privacyOn) {
                SettingsGroup(icon = Icons.Rounded.Security, title = "数据和权限") {
                    if (behaviorOn) {
                        SettingsNavRow(
                            icon = Icons.Rounded.Security,
                            title = "工具权限",
                            subtitle = if (settings.approvalMode == "auto") "自动允许（不再弹审批）" else "写类工具每次询问",
                            onClick = onOpenBehavior,
                        )
                    }
                    if (sessionsOn) {
                        SettingsNavRow(
                            icon = Icons.Rounded.Storage,
                            title = "对话历史管理",
                            subtitle = "本地 v4 会话，只读回放",
                            onClick = onOpenSessions,
                        )
                    }
                    if (backupOn) {
                        SettingsNavRow(
                            icon = Icons.Rounded.FolderZip,
                            title = "备份与恢复",
                            subtitle = "导出会话/插件/设置 zip（不含密钥）；恢复后会话可直接续聊",
                            onClick = onOpenBackup,
                        )
                    }
                    if (migrationOn) {
                        SettingsNavRow(
                            icon = Icons.Rounded.Restore,
                            title = "旧版数据导入",
                            subtitle = "从 DSH备份-*.zip 只读恢复旧会话（v0–v4）",
                            onClick = onOpenMigration,
                        )
                    }
                    if (privacyOn) {
                        SettingsNavRow(
                            icon = Icons.Rounded.DeleteSweep,
                            title = "隐私与数据清理",
                            subtitle = "清网页缓存 / Cookie / 临时文件；密钥、会话、插件不动",
                            onClick = onOpenPrivacy,
                        )
                    }
                }
            }

            // ======= 关于（M84-B 从弹窗改成二级页） =======
            if (aboutOn) {
                SettingsGroup(icon = Icons.Rounded.Info, title = "关于") {
                    SettingsNavRow(
                        icon = Icons.Rounded.Info,
                        title = "关于 DSH 原生版",
                        subtitle = "版本 $versionName · LGPL-3.0",
                        onClick = onOpenAbout,
                    )
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (showGitHubLogin) {
        GitHubLoginDialog(github) { showGitHubLogin = false }
    }
}

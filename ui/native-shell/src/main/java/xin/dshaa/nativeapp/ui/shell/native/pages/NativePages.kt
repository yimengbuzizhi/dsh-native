package xin.dshaa.nativeapp.ui.shell.native.pages

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Api
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderZip
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.Widgets
import androidx.compose.material.icons.rounded.Workspaces
import androidx.compose.ui.graphics.vector.ImageVector
import xin.dshaa.ui.shellapi.BuiltinPageIds
import xin.dshaa.ui.shellapi.PageLevel
import xin.dshaa.ui.shellapi.ShellPage

/**
 * M85-C2：壳内部的**页面**：页面元数据 + 抽屉/设置里的图标。
 *
 * 图标只有壳用得到（抽屉/设置页），所以它不进 `shell/v1` 契约；宿主拿到的是 [ShellPage]。
 * 22 页的标题/图标/层级/渲染 dispatch 从宿主的 `ui/pages/BuiltinPages.kt` 整体搬到这里，
 * 内容一行没改（`docs/152` §3、`docs/153`）。
 */
data class NativePage(
    val id: String,
    val title: String,
    val icon: ImageVector,
    val level: PageLevel,
) {
    /** 交给宿主的页面声明（不含图标）。 */
    val declaration: ShellPage get() = ShellPage(id, title, level)
}

/**
 * 22 个页面的清单（11 一级 + 11 二级，含 M83 的远程 DSH 与 M84-B 补的「关于」整页）。
 *
 * 这就是「页面由壳提供」的查表入口：`NativeShellContent` 的路由 `when` 按 [id] 找内容，
 * 设置/抽屉/聊天的入口按插件开关过滤；宿主没有任何第二份页面列表。
 */
object NativePages {

    val all: List<NativePage> = listOf(
        NativePage(BuiltinPageIds.Sessions, "会话", Icons.Rounded.ChatBubbleOutline, PageLevel.Primary),
        NativePage(BuiltinPageIds.Workspace, "工作区", Icons.Rounded.Folder, PageLevel.Primary),
        NativePage(BuiltinPageIds.Agents, "子代理", Icons.Rounded.Person, PageLevel.Primary),
        NativePage(BuiltinPageIds.Jobs, "任务", Icons.Rounded.PlayArrow, PageLevel.Primary),
        NativePage(BuiltinPageIds.Terminal, "终端", Icons.Rounded.Terminal, PageLevel.Primary),
        NativePage(BuiltinPageIds.VirtualScreen, "虚拟屏", Icons.Rounded.PlayArrow, PageLevel.Primary),
        NativePage(BuiltinPageIds.Accessibility, "无障碍", Icons.Rounded.CheckCircle, PageLevel.Primary),
        NativePage(BuiltinPageIds.Plugins, "插件", Icons.Rounded.Extension, PageLevel.Primary),
        NativePage(BuiltinPageIds.Packages, "包管理", Icons.Rounded.Widgets, PageLevel.Primary),
        NativePage(BuiltinPageIds.Memory, "记忆库", Icons.Rounded.Memory, PageLevel.Primary),
        NativePage(BuiltinPageIds.Settings, "设置", Icons.Rounded.Settings, PageLevel.Primary),

        NativePage(BuiltinPageIds.ModelConfig, "模型配置", Icons.Rounded.Api, PageLevel.Secondary),
        NativePage(BuiltinPageIds.Assistant, "助手配置", Icons.Rounded.Person, PageLevel.Secondary),
        NativePage(BuiltinPageIds.Behavior, "显示与行为", Icons.Rounded.Visibility, PageLevel.Secondary),
        NativePage(BuiltinPageIds.Backup, "备份与恢复", Icons.Rounded.FolderZip, PageLevel.Secondary),
        NativePage(BuiltinPageIds.DataMigration, "旧版数据导入", Icons.Rounded.Restore, PageLevel.Secondary),
        NativePage(BuiltinPageIds.Skills, "技能", Icons.Rounded.Star, PageLevel.Secondary),
        NativePage(BuiltinPageIds.Workspaces, "工作区管理", Icons.Rounded.Workspaces, PageLevel.Secondary),
        NativePage(BuiltinPageIds.Permissions, "权限与能力", Icons.Rounded.Security, PageLevel.Secondary),
        NativePage(BuiltinPageIds.Privacy, "隐私与数据清理", Icons.Rounded.DeleteSweep, PageLevel.Secondary),
        NativePage(BuiltinPageIds.RemoteDsh, "远程 DSH", Icons.Rounded.Cloud, PageLevel.Secondary),
        NativePage(BuiltinPageIds.About, "关于", Icons.Rounded.Info, PageLevel.Secondary),
    )

    val primary: List<NativePage> = all.filter { it.level == PageLevel.Primary }
    val secondary: List<NativePage> = all.filter { it.level == PageLevel.Secondary }

    /** 交给宿主的声明（`shell/v1` 的 `pages`）。 */
    val declarations: List<ShellPage> = all.map { it.declaration }

    fun byId(id: String?): NativePage? = id?.let { key -> all.firstOrNull { it.id == key } }

    /**
     * 调试通道/旧命令的宽松匹配：id、标题、`<id>Screen` 都认（大小写不敏感）。
     * 例：`--es dsh_open_screen Agents`、`agents`、`子代理`、`modelconfigscreen`。
     */
    fun find(requested: String): NativePage? {
        val key = requested.trim()
        if (key.isEmpty()) return null
        return all.firstOrNull {
            it.id.equals(key, ignoreCase = true) ||
                it.title.equals(key, ignoreCase = true) ||
                "${it.id}Screen".equals(key, ignoreCase = true)
        }
    }
}

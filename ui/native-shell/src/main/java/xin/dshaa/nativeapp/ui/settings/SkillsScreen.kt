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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import xin.dshaa.core.skill.SkillRoot
import xin.dshaa.core.skill.SkillSummary
import xin.dshaa.ui.design.component.MiniChip
import xin.dshaa.ui.design.component.SettingsCard
import xin.dshaa.ui.design.component.SettingsGroup
import xin.dshaa.ui.design.component.SettingsInfoBanner
import xin.dshaa.ui.design.component.SettingsNavRow
import xin.dshaa.ui.design.theme.DshTheme2

/**
 * 技能（M19，对齐上游 `dsh-tool-skill` / `dsh-skill-filesystem`）。
 *
 * 这一页回答三个问题：技能文件放哪、现在发现了什么、为什么某个文件没被收进来。
 * 目录消息与 `skill` 工具的行为在引擎侧（每个 step 起点比较摘要），这里纯只读展示。
 */
@Composable
fun SkillsScreen(
    skills: List<SkillSummary>,
    roots: List<SkillRoot>,
    complete: Boolean,
    warnings: List<String>,
    /** App 的 files 目录 / 工作区绝对路径：把长路径缩成 `files/…`、`workspace/…` 再显示。 */
    homeDir: String,
    workspaceDir: String,
    onRefresh: () -> Unit,
    onBack: () -> Unit,
) {
    val c = DshTheme2.colors
    LaunchedEffect(Unit) { onRefresh() }
    Column(Modifier.fillMaxSize().background(c.background)) {
        Row(
            Modifier.fillMaxWidth().background(c.surface).statusBarsPadding().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(40.dp).clip(CircleShape).clickable { onBack() }, contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.ArrowBack, "返回", tint = c.text, modifier = Modifier.size(23.dp))
            }
            Spacer(Modifier.width(4.dp))
            Column(Modifier.weight(1f)) {
                Text("技能", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, color = c.text)
                Text(
                    "${skills.size} 个可用 · ${if (complete) "发现完整" else "发现不完整"}",
                    style = MaterialTheme.typography.labelSmall,
                    color = c.sub,
                )
            }
            Box(Modifier.size(40.dp).clip(CircleShape).clickable { onRefresh() }, contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Refresh, "重新扫描", tint = c.sub, modifier = Modifier.size(21.dp))
            }
        }

        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp)) {
            SettingsInfoBanner(
                "技能 = 一个文件夹里的 SKILL.md（YAML frontmatter 的 name/description + 正文说明）。" +
                    "目录变化时会在下一个 step 起点自动同步给模型；模型用 skill 工具加载完整说明后再照做。",
            )

            SettingsGroup(icon = Icons.Rounded.Star, title = "已发现的技能") {
                if (skills.isEmpty()) {
                    SettingsInfoBanner("还没有技能。新建 files/skills/<名字>/SKILL.md，回到这里点右上角刷新。")
                } else {
                    skills.forEach { skill -> SkillRow(skill, homeDir, workspaceDir) }
                }
            }

            SettingsGroup(icon = Icons.Rounded.Star, title = "扫描目录") {
                roots.forEach { root -> RootRow(root, homeDir, workspaceDir) }
                SettingsInfoBanner(
                    "同名技能先到先得：project-dsh(100) → project-agents(200) → user-dsh(400) → user-agents(500)。" +
                        "子目录里的 SKILL.md 是目录型技能，根目录下的 *.md 是单文件技能。",
                    tint = c.sub,
                )
            }

            if (warnings.isNotEmpty()) {
                SettingsGroup(icon = Icons.Rounded.Warning, title = "被忽略的文件") {
                    warnings.forEach { warning ->
                        Text(
                            warning,
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = c.warn,
                        )
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SkillRow(skill: SkillSummary, homeDir: String, workspaceDir: String) {
    val c = DshTheme2.colors
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                skill.name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = c.text,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            MiniChip(
                when {
                    !skill.modelInvocable -> "仅用户"
                    !skill.userInvocable -> "仅模型"
                    else -> skill.source
                },
                color = if (skill.modelInvocable) c.brand else c.sub,
            )
        }
        Spacer(Modifier.height(2.dp))
        Text(
            skill.description,
            style = MaterialTheme.typography.bodySmall,
            color = c.sub,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            shortPath(skill.path, homeDir, workspaceDir),
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = c.faint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun RootRow(root: SkillRoot, homeDir: String, workspaceDir: String) {
    val c = DshTheme2.colors
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                shortPath(root.path.absolutePath, homeDir, workspaceDir),
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = c.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "${root.source} · rank ${root.rank}",
                style = MaterialTheme.typography.labelSmall,
                color = c.faint,
            )
        }
        MiniChip(root.source, color = c.sub)
    }
}

/** 把 App 私有目录前缀换成 `files/…` / `workspace/…`，设置页里能直接对上用户的直觉。 */
private fun shortPath(path: String, homeDir: String, workspaceDir: String): String = when {
    workspaceDir.isNotEmpty() && path.startsWith(workspaceDir) -> "workspace" + path.removePrefix(workspaceDir)
    homeDir.isNotEmpty() && path.startsWith(homeDir) -> "files" + path.removePrefix(homeDir)
    else -> path
}

package xin.dshaa.nativeapp.ui.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import xin.dshaa.core.input.CommandChoice
import xin.dshaa.core.input.FileCandidate
import xin.dshaa.core.input.InputTriggerKind
import xin.dshaa.core.input.rankByName
import xin.dshaa.core.skill.SkillSummary
import xin.dshaa.ui.design.theme.DshTheme2


/**
 * 输入框上方的触发面板（M21）：`/` 显示命令 + 技能，`@` 显示工作区文件/目录。
 * 纯展示 + 点击回调；查询过滤在 [rankByName] 里做（前缀优先、再子序列）。
 */
@Composable
fun InputTriggerPanel(
    kind: InputTriggerKind,
    query: String,
    commands: List<CommandChoice>,
    skills: List<SkillSummary>,
    files: List<FileCandidate>,
    onPickCommand: (String) -> Unit,
    onPickSkill: (String) -> Unit,
    onPickFile: (FileCandidate) -> Unit,
) {
    val c = DshTheme2.colors
    val commandMatches = if (kind == InputTriggerKind.COMMAND) {
        rankByName(commands.map { it.name }, query).mapNotNull { name -> commands.firstOrNull { it.name == name } }
    } else {
        emptyList()
    }
    val skillMatches = if (kind == InputTriggerKind.COMMAND) {
        val names = rankByName(skills.map { it.name }, query)
        names.mapNotNull { name -> skills.firstOrNull { it.name == name } }
    } else {
        emptyList()
    }
    val fileMatches = if (kind == InputTriggerKind.FILE) files else emptyList()
    val empty = commandMatches.isEmpty() && skillMatches.isEmpty() && fileMatches.isEmpty()

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        shape = RoundedCornerShape(16.dp),
        color = c.surface,
        border = BorderStroke(1.dp, c.line),
        shadowElevation = 6.dp,
    ) {
        Column(Modifier.heightIn(max = 264.dp).verticalScroll(rememberScrollState()).padding(vertical = 6.dp)) {
            if (commandMatches.isNotEmpty()) {
                GroupLabel("命令")
                commandMatches.forEach { command ->
                    TriggerRow(
                        icon = { tint -> Icon(Icons.Rounded.Terminal, null, tint = tint, modifier = Modifier.size(18.dp)) },
                        title = command.display(),
                        description = command.description,
                        onClick = { onPickCommand(command.name) },
                    )
                }
            }
            if (skillMatches.isNotEmpty()) {
                GroupLabel("技能")
                skillMatches.forEach { skill ->
                    TriggerRow(
                        icon = { tint -> Icon(Icons.Rounded.Star, null, tint = tint, modifier = Modifier.size(18.dp)) },
                        title = "/${skill.name}",
                        description = skill.description,
                        onClick = { onPickSkill(skill.name) },
                    )
                }
            }
            if (fileMatches.isNotEmpty()) {
                GroupLabel("文件")
                fileMatches.forEach { file ->
                    TriggerRow(
                        icon = { tint ->
                            Icon(
                                Icons.Rounded.Storage,
                                null,
                                tint = tint,
                                modifier = Modifier.size(18.dp),
                            )
                        },
                        title = if (file.directory) "${file.path}/" else file.path,
                        description = if (file.directory) "目录" else "文件",
                        onClick = { onPickFile(file) },
                    )
                }
            }
            if (empty) {
                Text(
                    if (kind == InputTriggerKind.COMMAND) "没有匹配的命令或技能" else "没有匹配的文件",
                    style = MaterialTheme.typography.bodySmall,
                    color = c.faint,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }
        }
    }
}

@Composable
private fun GroupLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = DshTheme2.colors.faint,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 2.dp),
    )
}

@Composable
private fun TriggerRow(
    icon: @Composable (androidx.compose.ui.graphics.Color) -> Unit,
    title: String,
    description: String,
    onClick: () -> Unit,
) {
    val c = DshTheme2.colors
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon(c.brand)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (description.isNotBlank()) {
                Text(
                    description,
                    style = MaterialTheme.typography.labelSmall,
                    color = c.faint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

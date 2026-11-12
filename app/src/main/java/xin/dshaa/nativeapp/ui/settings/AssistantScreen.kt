package xin.dshaa.nativeapp.ui.settings

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.UploadFile
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import xin.dshaa.core.plugins.PluginRecord
import xin.dshaa.nativeapp.data.AssistantProfile
import xin.dshaa.nativeapp.data.SettingsStore
import xin.dshaa.ui.design.component.AvatarLoader
import xin.dshaa.ui.design.component.DshAvatar
import xin.dshaa.ui.design.component.SettingsCard
import xin.dshaa.ui.design.component.SettingsInfoBanner
import xin.dshaa.ui.design.component.SettingsSectionHeader
import xin.dshaa.ui.design.component.SettingsTextField
import xin.dshaa.ui.design.theme.DshTheme2
import java.io.File

/**
 * 助手配置 / 角色卡（M71 单份 → M73 多份）。
 *
 * 多份语义（照 Operit 的角色卡列表）：
 * - 可以同时留多份，侧边栏「助手」那一格切换**当前使用**的一份；
 * - 每份有自己的名字/简介/头像/人设/开场白/系统提示词；
 * - 注入语义照 DSH 上游（`deployment:persona-prefix` / `deployment:persona-suffix`），
 *   插件的 `ctx.systemPrompt.section` 与当前这份在同一套段拼装里按 order 排序，同名段插件覆盖角色卡。
 *
 * 页面下半部分是**插件自带的助手配置**（只读）：插件 `ctx.systemPrompt.section/context`
 * 注册的段原样列出来，用户能看见「哪个插件往提示词里塞了什么」，不用翻日志。
 */
@Composable
fun AssistantScreen(
    settings: SettingsStore,
    plugins: List<PluginRecord> = emptyList(),
    onBack: () -> Unit,
) {
    val c = DshTheme2.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val profiles = settings.assistantProfiles
    val activeId = settings.activeAssistantId
    // 正在编辑哪一份：默认是当前使用的那份
    var editingId by remember(activeId) { mutableStateOf(activeId) }
    val editing = profiles.firstOrNull { it.id == editingId } ?: settings.activeAssistant

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val path = withContext(Dispatchers.IO) { copyAvatarToFiles(context, uri) }
            if (path != null) {
                AvatarLoader.clear()
                settings.updateAssistantProfile(editing.copy(avatar = path))
            }
        }
    }

    Column(Modifier.fillMaxSize().background(c.background)) {
        Row(
            Modifier.fillMaxWidth().background(c.surface).statusBarsPadding().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(40.dp).clip(CircleShape).clickable { onBack() }, contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.ArrowBack, "返回", tint = c.text, modifier = Modifier.size(23.dp))
            }
            Spacer(Modifier.width(4.dp))
            Text("助手配置", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, color = c.text)
            Spacer(Modifier.weight(1f))
            Text(
                "${profiles.size} 份 · 当前「${settings.assistantName}」",
                style = MaterialTheme.typography.labelSmall,
                color = c.faint,
            )
        }

        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp)) {

            // ======= 我的助手（多份列表） =======
            SettingsSectionHeader(icon = Icons.Rounded.Person, title = "我的助手")
            SettingsCard {
                profiles.forEachIndexed { index, profile ->
                    val isActive = profile.id == activeId
                    val isEditing = profile.id == editing.id
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (isEditing) c.surfaceAlt.copy(alpha = 0.55f) else c.surface)
                            .clickable { editingId = profile.id }
                            .padding(horizontal = 10.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        DshAvatar(avatar = profile.avatar, name = profile.name, size = 42.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    profile.name,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = c.text,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (isActive) {
                                    Spacer(Modifier.width(6.dp))
                                    Pill("正在使用", c.brandBright)
                                }
                            }
                            Spacer(Modifier.height(2.dp))
                            Text(
                                profile.summary(),
                                style = MaterialTheme.typography.labelSmall,
                                color = c.faint,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        if (!isActive) {
                            Spacer(Modifier.width(6.dp))
                            Text(
                                "切换",
                                style = MaterialTheme.typography.labelMedium,
                                color = c.brandBright,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(999.dp))
                                    .clickable { settings.setActiveAssistant(profile.id) }
                                    .padding(horizontal = 10.dp, vertical = 5.dp),
                            )
                        }
                        IconButton(onClick = { settings.duplicateAssistantProfile(profile.id) }) {
                            Icon(Icons.Rounded.ContentCopy, "复制一份", tint = c.faint, modifier = Modifier.size(17.dp))
                        }
                        if (profiles.size > 1) {
                            IconButton(onClick = {
                                settings.deleteAssistantProfile(profile.id)
                                if (editingId == profile.id) editingId = settings.activeAssistantId
                            }) {
                                Icon(Icons.Rounded.Delete, "删除这份", tint = c.danger, modifier = Modifier.size(17.dp))
                            }
                        }
                    }
                    if (index != profiles.lastIndex) {
                        Box(Modifier.fillMaxWidth().padding(horizontal = 4.dp).height(1.dp).background(c.line.copy(alpha = 0.5f)))
                    }
                }
                Spacer(Modifier.height(6.dp))
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable {
                            val created = settings.addAssistantProfile()
                            editingId = created.id
                        }
                        .background(c.brand.copy(alpha = 0.08f))
                        .padding(vertical = 11.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("＋ 新建一份助手配置", style = MaterialTheme.typography.titleSmall, color = c.brandBright)
                }
            }
            SettingsInfoBanner(
                "每份助手是一张独立角色卡：名字/简介/头像只做展示，人设与系统提示词才进提示词；" +
                    "侧边栏「工作区」下面新增的「助手」那一格用来切换**当前使用**的那份，" +
                    "新会话立即生效，已有会话从下一轮请求开始用新助手。",
            )

            Spacer(Modifier.height(16.dp))

            // ======= 编辑当前选中的那份 =======
            SettingsSectionHeader(icon = Icons.Rounded.Person, title = "编辑「${editing.name}」")
            SettingsCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(
                            Modifier.size(72.dp).clip(CircleShape).clickable {
                                picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                            },
                            contentAlignment = Alignment.Center,
                        ) {
                            DshAvatar(avatar = editing.avatar, name = editing.name, size = 72.dp)
                        }
                        Spacer(Modifier.height(6.dp))
                        Surface(
                            shape = RoundedCornerShape(999.dp),
                            color = c.surfaceAlt.copy(alpha = 0.7f),
                            modifier = Modifier.clickable {
                                picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                            },
                        ) {
                            Text(
                                "换头像",
                                style = MaterialTheme.typography.labelSmall,
                                color = c.brandBright,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                            )
                        }
                    }
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            editing.name.ifBlank { "未命名" },
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = c.text,
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(editing.summary(), style = MaterialTheme.typography.bodySmall, color = c.sub)
                        Spacer(Modifier.height(10.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(
                                shape = RoundedCornerShape(999.dp),
                                color = c.surfaceAlt.copy(alpha = 0.7f),
                                modifier = Modifier.clickable {
                                    settings.updateAssistantProfile(editing.copy(avatar = ""))
                                    AvatarLoader.clear()
                                },
                            ) {
                                Row(Modifier.padding(horizontal = 10.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Rounded.Refresh, null, tint = c.sub, modifier = Modifier.size(14.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("重置头像", style = MaterialTheme.typography.labelSmall, color = c.sub)
                                }
                            }
                            Spacer(Modifier.width(8.dp))
                            if (editing.id != activeId) {
                                Text(
                                    "设为当前使用",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = c.brandBright,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(999.dp))
                                        .clickable { settings.setActiveAssistant(editing.id) }
                                        .padding(horizontal = 10.dp, vertical = 5.dp),
                                )
                            } else if (editing.avatar.isNotBlank()) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Rounded.Check, null, tint = c.success, modifier = Modifier.size(14.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("已使用自定义图片", style = MaterialTheme.typography.labelSmall, color = c.success)
                                }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            SettingsSectionHeader(icon = Icons.Rounded.Person, title = "角色卡")
            SettingsCard {
                SettingsTextField(
                    title = "名字",
                    subtitle = "显示在每条 AI 消息头、聊天页顶栏与工作台；写进人设段（「你的名字是…」）",
                    value = editing.name,
                    onValueChange = { settings.updateAssistantProfile(editing.copy(name = it.trimStart())) },
                    placeholder = "鲸歌",
                )
                AssistantLongField(
                    title = "简介",
                    subtitle = "只用于展示（设置页/预览），不进入提示词",
                    value = editing.desc,
                    onValueChange = { settings.updateAssistantProfile(editing.copy(desc = it)) },
                    placeholder = "一句话描述这个助手",
                )
                AssistantLongField(
                    title = "开场白",
                    subtitle = "新会话还没消息时显示的第一句话（只显示，不写进提示词、不伪造 assistant 历史）",
                    value = editing.opening,
                    onValueChange = { settings.updateAssistantProfile(editing.copy(opening = it)) },
                    placeholder = "比如：今天想让我帮你做点什么？",
                )
            }

            Spacer(Modifier.height(16.dp))
            SettingsSectionHeader(icon = Icons.Rounded.Person, title = "人设与系统提示词")
            SettingsCard {
                AssistantLongField(
                    title = "人设",
                    subtitle = "注入 deployment:persona-prefix（order 0），替换默认的 coding agent 文案；留空 = 用上游默认人设",
                    value = editing.persona,
                    onValueChange = { settings.updateAssistantProfile(editing.copy(persona = it)) },
                    placeholder = "例如：你是一只住在手机里的鲸鱼，说话温柔、爱用短句，遇到问题先动手再解释。",
                )
                AssistantLongField(
                    title = "系统提示词",
                    subtitle = "注入 deployment:persona-suffix（order 10200，全文最后一段）；适合放行为规则/口癖/禁忌",
                    value = editing.system,
                    onValueChange = { settings.updateAssistantProfile(editing.copy(system = it)) },
                    placeholder = "例如：回答尽量简短；不确定时直接说不确定；不要用感叹号。",
                )
            }
            SettingsInfoBanner(
                "注入走上游同一套段拼装：人设段在 harness 身份之后、工具说明之前；系统提示词在整个 system 的末尾。" +
                    "插件用 ctx.systemPrompt.section 注册的段落按 order 一起排序，同名的段以插件为准" +
                    "（人设插件会接管这里的角色卡，不会拼出两段人设）。",
            )

            // ======= 插件预设（M74：组合包里的 @deepseek-ai/dsh-agent-preset） =======
            val withPresets = plugins.filter { it.presets.isNotEmpty() }
            if (withPresets.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                SettingsSectionHeader(icon = Icons.Rounded.Extension, title = "插件预设")
                SettingsCard {
                    withPresets.forEach { record ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                record.name + " 提供的预设",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = c.text,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (!record.enabled) Pill("插件已停用", c.faint) else Pill("可套用", c.brandBright)
                        }
                        Spacer(Modifier.height(4.dp))
                        record.presets.forEach { preset ->
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(top = 6.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(c.surfaceAlt.copy(alpha = 0.45f))
                                    .border(1.dp, c.line.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
                                    .padding(horizontal = 10.dp, vertical = 8.dp),
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        preset.name,
                                        style = MaterialTheme.typography.titleSmall,
                                        color = c.text,
                                        modifier = Modifier.weight(1f),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Pill("${preset.pluginCount} 个子插件", c.faint)
                                }
                                if (preset.description.isNotBlank()) {
                                    Spacer(Modifier.height(3.dp))
                                    Text(
                                        preset.description.take(160),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = c.sub,
                                        maxLines = 3,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                // 预览也走宿主变量：{{model}} 不该裸露给用户（注入时由 PromptPack 填）
                                val previewVars = record.promptVariables + mapOf("model" to settings.model)
                                val prefix = previewVariables(preset.personaPrefix, previewVars)
                                val suffix = previewVariables(preset.personaSuffix, previewVars)
                                if (prefix.isNotBlank()) {
                                    Spacer(Modifier.height(6.dp))
                                    Text("人设 · ${prefix.take(160)}", style = MaterialTheme.typography.labelSmall, color = c.faint, maxLines = 3, overflow = TextOverflow.Ellipsis)
                                }
                                if (suffix.isNotBlank()) {
                                    Spacer(Modifier.height(3.dp))
                                    Text("系统提示词 · ${suffix.take(160)}", style = MaterialTheme.typography.labelSmall, color = c.faint, maxLines = 3, overflow = TextOverflow.Ellipsis)
                                }
                                if (prefix.isBlank() && suffix.isBlank()) {
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        "这个预设没有声明 dsh-persona 的 prefix/suffix（只有子插件列表），套用后是一份空白角色卡。",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = c.faint,
                                    )
                                }
                                Spacer(Modifier.height(8.dp))
                                Row(
                                    Modifier
                                        .clip(RoundedCornerShape(999.dp))
                                        .clickable {
                                            val created = settings.addAssistantProfileFromPreset(
                                                name = preset.name,
                                                persona = prefix,
                                                system = suffix,
                                                desc = "来自插件预设「${preset.name}」（${record.name}）",
                                            )
                                            editingId = created.id
                                        }
                                        .background(c.brand.copy(alpha = 0.14f))
                                        .padding(horizontal = 12.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text("套用成一份助手配置", style = MaterialTheme.typography.labelMedium, color = c.brandBright)
                                }
                            }
                        }
                    }
                }
                SettingsInfoBanner(
                    "插件预设读自组合包的 cordis.patch.yml（@deepseek-ai/dsh-agent-preset + dsh-persona 的 " +
                        "prefix/suffix）。套用只是把这两段文本复制成一份新的角色卡并设为当前使用，" +
                        "补丁里的其它配置（子插件、工具开关）鲸歌不执行；预设本体仍跟着插件走，卸载插件后这里会消失。",
                )
            }

            // ======= 插件自带的助手配置（只读，M73） =======
            val withPrompt = plugins.filter { it.promptSections.isNotEmpty() || it.contexts.isNotEmpty() }
            Spacer(Modifier.height(16.dp))
            SettingsSectionHeader(icon = Icons.Rounded.Extension, title = "插件自带的助手配置")
            SettingsCard {
                if (withPrompt.isEmpty()) {
                    Text(
                        "装好的插件里，没有往提示词里注册段落（ctx.systemPrompt.section/context）的。\n" +
                            "带人设/上下文注入的插件装进来后，会在这里原样列出来（只读，跟随插件启用状态生效）。",
                        style = MaterialTheme.typography.bodySmall,
                        color = c.sub,
                    )
                } else {
                    withPrompt.forEachIndexed { index, record ->
                        Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    record.name,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = c.text,
                                    modifier = Modifier.weight(1f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (!record.enabled) Pill("已停用", c.faint) else Pill("生效中", c.success)
                            }
                            Spacer(Modifier.height(6.dp))
                            (record.promptSections + record.contexts).forEach { section ->
                                Column(
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(bottom = 6.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(c.surfaceAlt.copy(alpha = 0.45f))
                                        .border(1.dp, c.line.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
                                        .padding(horizontal = 10.dp, vertical = 8.dp),
                                ) {
                                    Text(
                                        sectionLabel(section.name) + "  ·  " + section.name + "  ·  order " + section.order,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = c.brandBright,
                                    )
                                    Spacer(Modifier.height(3.dp))
                                    Text(
                                        previewVariables(section.text, record.promptVariables).ifBlank { "（空段）" }.take(200),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = c.sub,
                                        maxLines = 4,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                            if (record.promptVariables.isNotEmpty()) {
                                Text(
                                    "变量 · " + record.promptVariables.entries.joinToString("；") {
                                        "${it.key} = ${it.value.take(60).ifBlank { "（空）" }}"
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = c.faint,
                                )
                            }
                        }
                        if (index != withPrompt.lastIndex) {
                            Box(Modifier.fillMaxWidth().height(1.dp).background(c.line.copy(alpha = 0.5f)))
                        }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun Pill(text: String, color: androidx.compose.ui.graphics.Color) {
    Surface(shape = RoundedCornerShape(999.dp), color = color.copy(alpha = 0.14f)) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
        )
    }
}

/**
 * 段名 → 人话（DSH 上游 `dsh-system-prompt` 的段名常量）。
 *
 * 用户看插件注入时不该面对 `deployment:persona-prefix` 这种内部名字；未知段名原样显示，
 * 不做猜测。
 */
private fun sectionLabel(name: String): String = when (name) {
    "deployment:persona-prefix" -> "人设"
    "deployment:persona-suffix" -> "系统提示词"
    "harness:identity" -> "身份"
    "tool:*" -> "工具说明"
    "cosplay-persona", "persona-card:persona", "persona:active" -> "插件人设"
    "persona", "soul" -> "插件人设"
    else -> "注入段"
}

/**
 * 插件段里的 `{{name}}` 在面板上换成真实值（M74）：替换成果直接决定用户能不能看出
 * 「插件人设到底注进去了什么」，只显示占位符等于白装。
 */
private fun previewVariables(text: String, variables: Map<String, String>): String {
    if (text.indexOf("{{") < 0) return text
    return VARIABLE_PLACEHOLDER.replace(text) { match ->
        variables[match.groupValues[1]] ?: match.value
    }
}

/** 占位符正则：结尾 `}}` 必须转义（Android ICU 把裸 `}}` 当语法错误）。 */
private val VARIABLE_PLACEHOLDER = Regex("\\{\\{([A-Za-z0-9_.:\\-]+)\\}\\}")

/**
 * 长文本字段（M79，用户 2026-11-12）：
 * 「简介/开场白/人设/系统提示词有时候写得很长，很不方便」——照 RikkaHub 的 `TextArea`
 * （`ui/components/ui/TextArea.kt`）做两件事：
 * 1. 内联最多 6 行左右，再长就折叠（可滚动），点「全屏」进全屏编辑；
 * 2. 「导入文件」从 md / txt / json 等文本文件整段读进来（SAF OpenDocument，UTF-8/UTF-16/GB18030）。
 *
 * 与 RikkaHub 的差别（故意的）：全屏是「取消/保存」两键，返回键丢弃修改前会先回到原值，
 * 不会像上游那样点外面直接丢；导入有 2MB / 字面上限并在状态行显示字数。
 */
@Composable
private fun AssistantLongField(
    title: String,
    subtitle: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
) {
    val c = DshTheme2.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var fullscreen by remember { mutableStateOf(false) }

    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val text = withContext(Dispatchers.IO) { readTextDocument(context, uri) }
            if (text == null) {
                Toast.makeText(context, "读取失败：只支持 2MB 以内的文本文件", Toast.LENGTH_SHORT).show()
            } else if (text.isBlank()) {
                Toast.makeText(context, "文件是空的，没有导入", Toast.LENGTH_SHORT).show()
            } else {
                onValueChange(text)
                Toast.makeText(context, "已导入 ${text.length} 字", Toast.LENGTH_SHORT).show()
            }
        }
    }

    SettingsTextField(
        title = title,
        subtitle = subtitle,
        value = value,
        onValueChange = onValueChange,
        placeholder = placeholder,
        singleLine = false,
        collapsedMaxHeight = 132.dp,
        footerActions = {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text("${value.length} 字", style = MaterialTheme.typography.labelSmall, color = c.faint)
                Spacer(Modifier.weight(1f))
                TextButton(
                    onClick = {
                        importer.launch(
                            arrayOf("text/*", "text/markdown", "application/json", "application/octet-stream"),
                        )
                    },
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                ) {
                    Icon(Icons.Rounded.UploadFile, null, tint = c.brandBright, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("导入文件", style = MaterialTheme.typography.labelMedium, color = c.brandBright)
                }
                TextButton(
                    onClick = { fullscreen = true },
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                ) {
                    Icon(Icons.Rounded.Fullscreen, null, tint = c.brandBright, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("全屏", style = MaterialTheme.typography.labelMedium, color = c.brandBright)
                }
            }
        },
    )

    if (fullscreen) {
        FullscreenTextDialog(
            title = title,
            initial = value,
            onDismiss = { fullscreen = false },
            onSave = { text ->
                onValueChange(text)
                fullscreen = false
            },
        )
    }
}

/** 全屏文本编辑器（照 RikkaHub `TextArea.FullScreenTextEditor`：Dialog + 顶栏 + 大输入框）。 */
@Composable
private fun FullscreenTextDialog(
    title: String,
    initial: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    val c = DshTheme2.colors
    var text by remember { mutableStateOf(initial) }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = c.background) {
            Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
                Row(
                    Modifier.fillMaxWidth().background(c.surface).padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onDismiss) { Text("取消", color = c.sub) }
                    Text(
                        "编辑 · $title",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = c.text,
                        modifier = Modifier.weight(1f),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                    Text(
                        "${text.length} 字",
                        style = MaterialTheme.typography.labelSmall,
                        color = c.faint,
                    )
                    Spacer(Modifier.width(4.dp))
                    TextButton(onClick = { onSave(text) }) {
                        Text("保存", color = c.brandBright, fontWeight = FontWeight.SemiBold)
                    }
                }
                BasicTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(12.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(c.surface)
                        .padding(14.dp),
                    textStyle = TextStyle(color = c.text, fontSize = 15.sp, lineHeight = 23.sp),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(c.brand),
                )
            }
        }
    }
}

/**
 * 读一个用户选的文本文件：最多 2MB；BOM 探测（UTF-8 / UTF-16LE / UTF-16BE）→ 严格 UTF-8 →
 * 失败回退 GB18030；统一 CRLF→LF，去掉末尾空白。读不了返回 null（调用方给 Toast）。
 */
private fun readTextDocument(context: android.content.Context, uri: Uri): String? = runCatching {
    val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
        val limit = 2 * 1024 * 1024
        val buffer = ByteArray(limit + 1)
        var read = 0
        while (read < buffer.size) {
            val n = input.read(buffer, read, buffer.size - read)
            if (n <= 0) break
            read += n
        }
        buffer.copyOf(read)
    } ?: return@runCatching null
    if (bytes.size > 2 * 1024 * 1024) return@runCatching null
    val text = when {
        bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte() ->
            String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() ->
            String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
        bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() ->
            String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        else -> runCatching {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(bytes))
                .toString()
        }.getOrElse { String(bytes, charset("GB18030")) }
    }
    text.replace("\r\n", "\n").replace("\r", "\n").trimEnd()
}.getOrNull()

private fun copyAvatarToFiles(context: android.content.Context, uri: Uri): String? = runCatching {
    val dir = File(context.filesDir, "avatars").apply { mkdirs() }
    val target = File(dir, "assistant_${System.currentTimeMillis()}.img")
    context.contentResolver.openInputStream(uri)?.use { input ->
        target.outputStream().use { output -> input.copyTo(output) }
    } ?: return@runCatching null
    // 旧头像清理，避免 filesDir 里越堆越多
    dir.listFiles()?.filter { it != target && it.name.startsWith("assistant_") }?.forEach { it.delete() }
    target.absolutePath
}.getOrNull()

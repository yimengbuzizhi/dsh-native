package xin.dshaa.nativeapp.ui.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
 * 助手配置：名字 + 头像（真实图片，相册选择后复制到 filesDir/avatars）。
 * 照 Operit `AvatarPicker`（70dp 圆形、2dp 主色描边、点按换图、重置按钮）与角色卡字段。
 */
@Composable
fun AssistantScreen(
    settings: SettingsStore,
    onBack: () -> Unit,
) {
    val c = DshTheme2.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val path = withContext(Dispatchers.IO) { copyAvatarToFiles(context, uri) }
            if (path != null) {
                AvatarLoader.clear()
                settings.setAssistantAvatar(path)
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
        }

        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp)) {
            // 预览卡：这就是聊天页/工作台会显示的样子
            SettingsCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(
                            Modifier.size(72.dp).clip(CircleShape).clickable {
                                picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                            },
                            contentAlignment = Alignment.Center,
                        ) {
                            DshAvatar(
                                avatar = settings.assistantAvatar,
                                name = settings.assistantName,
                                size = 72.dp,
                            )
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
                            settings.assistantName.ifBlank { "DSH" },
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = c.text,
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            settings.assistantDesc.ifBlank { "点下面「重置头像」可恢复首字兜底" },
                            style = MaterialTheme.typography.bodySmall,
                            color = c.sub,
                        )
                        Spacer(Modifier.height(10.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(
                                shape = RoundedCornerShape(999.dp),
                                color = c.surfaceAlt.copy(alpha = 0.7f),
                                modifier = Modifier.clickable {
                                    settings.setAssistantAvatar("")
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
                            if (settings.assistantAvatar.isNotBlank()) {
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
            SettingsSectionHeader(icon = Icons.Rounded.Person, title = "角色")
            SettingsCard {
                SettingsTextField(
                    title = "名字",
                    subtitle = "显示在每条 AI 消息头、聊天页顶栏与工作台",
                    value = settings.assistantName,
                    onValueChange = { settings.setAssistantName(it) },
                    placeholder = "DSH",
                )
                SettingsTextField(
                    title = "简介",
                    subtitle = "只用于展示，不进入提示词",
                    value = settings.assistantDesc,
                    onValueChange = { settings.setAssistantDesc(it) },
                    placeholder = "一句话描述这个助手",
                )
                SettingsInfoBanner("角色卡（人设/开场白/多角色）在 M4 接入提示词拼装后再开放，避免污染契约 #1 的 system 段。")
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

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

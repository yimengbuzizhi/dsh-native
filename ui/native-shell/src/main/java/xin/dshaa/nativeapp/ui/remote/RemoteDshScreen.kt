package xin.dshaa.nativeapp.ui.remote

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import xin.dshaa.ui.design.theme.DshTheme2
import xin.dshaa.ui.shellapi.remote.RemoteProfileUi
import xin.dshaa.ui.shellapi.remote.ShellRemote

/** 这里只管理电脑配置；远程聊天使用主聊天页的同一套 ChatScreen/ChatComposer。 */
@Composable
fun RemoteDshScreen(
    controller: ShellRemote,
    onBack: () -> Unit,
    onSelectTarget: (String) -> Unit = {},
) {
    val c = DshTheme2.colors
    var editingId by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    var notice by remember { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf<RemoteProfileUi?>(null) }
    fun edit(profile: RemoteProfileUi?) {
        editingId = profile?.id
        name = profile?.name.orEmpty()
        url = profile?.baseUrl.orEmpty()
        token = ""
        editing = true
        notice = null
    }
    Column(Modifier.fillMaxSize().background(c.background)) {
        Row(Modifier.fillMaxWidth().background(c.surface).statusBarsPadding().padding(horizontal = 6.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回", tint = c.text) }
            Text("远程电脑", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                color = c.text, modifier = Modifier.weight(1f))
            TextButton(onClick = { edit(null) }) { Text("添加") }
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("保存电脑上的 DSH 地址和令牌。对话来源从侧边栏顶部切换，聊天继续使用鲸歌主界面。",
                style = MaterialTheme.typography.bodySmall, color = c.sub)
            if (controller.profiles.isEmpty() && !editing) Text("还没有远程电脑，点右上角添加。", color = c.faint)
            controller.profiles.forEach { profile ->
                Surface(shape = RoundedCornerShape(14.dp), color = c.surface, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f).clickable { onSelectTarget(profile.id) }) {
                                Text(profile.name, color = c.text, fontWeight = FontWeight.SemiBold)
                                Text(profile.baseUrl, color = c.faint, style = MaterialTheme.typography.labelSmall,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(if (profile.hasToken) "已保存令牌" else "未保存令牌", color = c.faint,
                                    style = MaterialTheme.typography.labelSmall)
                            }
                            TextButton(onClick = { onSelectTarget(profile.id) }) { Text("使用") }
                        }
                        Row {
                            TextButton(onClick = { edit(profile) }) { Text("编辑") }
                            TextButton(onClick = { deleting = profile }) { Text("删除", color = c.danger) }
                        }
                    }
                }
            }
            if (editing) Surface(shape = RoundedCornerShape(14.dp), color = c.surfaceAlt) {
                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(if (editingId == null) "添加远程电脑" else "编辑远程电脑", color = c.text, fontWeight = FontWeight.SemiBold)
                    OutlinedTextField(name, { name = it }, label = { Text("名称") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(url, { url = it }, label = { Text("地址") }, placeholder = { Text("http://100.x.x.x:8137") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(token, { token = it }, label = { Text("令牌") },
                        placeholder = { Text(if (editingId == null) "电脑助手提供的令牌" else "留空保留同地址的令牌") },
                        visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth())
                    Text("更改地址后须为新地址填写令牌。", color = c.faint, style = MaterialTheme.typography.labelSmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            val saved = controller.saveProfile(editingId, name, url, token.takeIf { it.isNotBlank() })
                            if (saved == null) notice = controller.errorText ?: "保存失败"
                            else { editing = false; notice = "已保存"; token = "" }
                        }, enabled = url.isNotBlank() && name.isNotBlank()) { Text("保存") }
                        TextButton(onClick = { editing = false; token = "" }) { Text("取消") }
                    }
                }
            }
            notice?.let { Text(it, color = c.sub, style = MaterialTheme.typography.bodySmall) }
            if (controller.statusText.isNotBlank()) Text(controller.statusText, color = c.faint, style = MaterialTheme.typography.labelSmall)
            controller.errorText?.let { Text(it, color = c.danger, style = MaterialTheme.typography.bodySmall) }
            Spacer(Modifier.height(24.dp))
        }
    }
    deleting?.let { profile ->
        AlertDialog(onDismissRequest = { deleting = null }, title = { Text("删除这台远程电脑？") },
            text = { Text("删除「${profile.name}」的手机连接配置和令牌。电脑上的会话与运行任务不受影响。") },
            confirmButton = { TextButton(onClick = {
                notice = if (controller.removeProfile(profile.id)) "已删除配置" else controller.errorText ?: "删除失败"
                deleting = null
            }) { Text("删除", color = c.danger) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } }, containerColor = c.surface)
    }
}

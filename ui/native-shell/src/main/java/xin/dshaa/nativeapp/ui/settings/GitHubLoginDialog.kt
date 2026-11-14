package xin.dshaa.nativeapp.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import xin.dshaa.nativeapp.data.GitHubAuth
import xin.dshaa.ui.design.theme.DshTheme2

/**
 * GitHub 账号登录弹层（2026-11-09：账号区从 DeepSeek 账号改为 GitHub）。
 *
 * 用 Personal Access Token 登录：GitHub OAuth 需要 OAuth App 的 client id 或第三方
 * 中转服务，本 App 不带后端，所以走 PAT（token 只存本机）。
 */
@Composable
fun GitHubLoginDialog(auth: GitHubAuth, onDismiss: () -> Unit) {
    val c = DshTheme2.colors
    val scope = rememberCoroutineScope()
    var tokenText by remember { mutableStateOf("") }
    var showToken by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val user = auth.user

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        containerColor = c.surface,
        title = { Text("GitHub 账号", fontWeight = FontWeight.SemiBold) },
        text = {
            Column {
                if (user != null) {
                    Text("已登录：${user.login}", style = MaterialTheme.typography.bodyMedium, color = c.text)
                    user.name?.takeIf { it.isNotBlank() }?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = c.sub)
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "token 只保存在本机（dsh_github），不会上传、不进备份。",
                        style = MaterialTheme.typography.labelSmall,
                        color = c.faint,
                    )
                } else {
                    Text(
                        "用 GitHub Personal Access Token 登录",
                        style = MaterialTheme.typography.bodyMedium,
                        color = c.text,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "GitHub → Settings → Developer settings → Personal access tokens 生成；" +
                            "勾 read:user 即可，要用 GitHub Models 再加 models 权限。",
                        style = MaterialTheme.typography.labelSmall,
                        color = c.faint,
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = tokenText,
                        onValueChange = {
                            tokenText = it.trim()
                            error = null
                        },
                        label = { Text("ghp_…") },
                        singleLine = true,
                        visualTransformation = if (showToken) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            TextButton(onClick = { showToken = !showToken }) {
                                Text(if (showToken) "隐藏" else "显示", style = MaterialTheme.typography.labelSmall)
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    error?.let {
                        Spacer(Modifier.height(8.dp))
                        Text(it, style = MaterialTheme.typography.labelSmall, color = c.danger)
                    }
                }
            }
        },
        confirmButton = {
            if (user != null) {
                TextButton(onClick = {
                    auth.signOut()
                    onDismiss()
                }) {
                    Text("退出登录", color = c.danger)
                }
            } else {
                TextButton(
                    enabled = !busy && tokenText.isNotBlank(),
                    onClick = {
                        busy = true
                        error = null
                        scope.launch {
                            val result = withContext(Dispatchers.IO) { auth.signIn(tokenText) }
                            busy = false
                            result.onSuccess { onDismiss() }
                                .onFailure { error = it.message ?: "登录失败" }
                        }
                    },
                ) {
                    Text(if (busy) "验证中…" else "登录")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = { if (!busy) onDismiss() }) { Text("关闭") }
        },
    )
}

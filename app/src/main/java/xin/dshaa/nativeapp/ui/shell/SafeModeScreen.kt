package xin.dshaa.nativeapp.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import xin.dshaa.nativeapp.chat.AppViewModel
import xin.dshaa.nativeapp.data.BuiltinPlugins
import xin.dshaa.ui.design.theme.DshTheme2

/**
 * M85-C：内置界面插件（`builtin.shell.native`）被停用、且用户没有选可选壳时的**安全模式**。
 *
 * 这是宿主唯一保留的最小兜底界面（不是产品 UI）：解释发生了什么，并提供一键重新打开。
 * 其余任何页面/聊天/设置都由界面插件提供——插件坏了或被人为关掉时，用户仍然能自救。
 */
@Composable
fun SafeModeScreen(vm: AppViewModel, onOpenPlugins: () -> Unit = {}) {
    val c = DshTheme2.colors
    val context = androidx.compose.ui.platform.LocalContext.current
    Column(
        Modifier
            .fillMaxSize()
            .background(c.background)
            .padding(horizontal = 28.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            Icons.Rounded.Extension,
            contentDescription = null,
            tint = c.warn,
            modifier = Modifier.size(48.dp),
        )
        Spacer(Modifier.height(18.dp))
        Text(
            "界面插件已停用",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            color = c.text,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            "内置界面插件（${BuiltinPlugins.NATIVE_UI_SHELL}）当前被关掉，所以没有聊天页/设置/抽屉可显示。\n" +
                "这是安全模式：重新打开即可回到原来的界面，数据不受影响。",
            style = MaterialTheme.typography.bodyMedium,
            color = c.sub,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.surfaceAlt)
                .padding(horizontal = 16.dp, vertical = 14.dp),
        )
        Spacer(Modifier.height(20.dp))
        Button(
            onClick = {
                vm.setBuiltinPluginEnabled(BuiltinPlugins.NATIVE_UI_SHELL, true)
                // 从安全模式切回整屏 UI 时**冷启一个干净任务**（而不是就地重组）：
                // 真机复现过「重新启用后抽屉停在打开态、返回键直接退出」的残留布局；
                // 安全模式是低频自救路径，重建任务可以接受，换回和冷启动一样的干净界面。
                runCatching {
                    val intent = android.content.Intent(context, xin.dshaa.nativeapp.MainActivity::class.java)
                        .addFlags(
                            android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                                android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK,
                        )
                    context.startActivity(intent)
                }
            },
            colors = ButtonDefaults.buttonColors(containerColor = c.brand, contentColor = c.onHero),
            shape = RoundedCornerShape(14.dp),
        ) {
            Text("重新启用原生界面", fontWeight = FontWeight.Medium)
        }
        Spacer(Modifier.height(10.dp))
        androidx.compose.material3.TextButton(onClick = onOpenPlugins) {
            Text("管理插件 / 选择界面壳", color = c.brand)
        }
        vm.shellTargetsUi.filter { it.unavailableReason == null && !it.active }.forEach { target ->
            androidx.compose.material3.TextButton(onClick = { vm.selectShell(target.selectionValue) }) {
                Text("使用 ${target.label}", color = c.sub)
            }
        }
    }
}

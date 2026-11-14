package xin.dshaa.nativeapp.ui.boot

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import xin.dshaa.core.plugins.PluginRecord
import xin.dshaa.nativeapp.chat.AppViewModel
import xin.dshaa.ui.shellapi.NativeShellHostUi
import xin.dshaa.ui.shellapi.NativeShells
import xin.dshaa.ui.shellapi.PluginPageTarget

/** Only native plugin management is borrowed; third-party page JS cannot run through this slot. */
@Composable
fun BootRecoveryPanel(vm: AppViewModel, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    var blockedPage by remember { mutableStateOf(false) }
    val hostUi = remember {
        object : NativeShellHostUi {
            @Composable
            override fun PluginPage(target: PluginPageTarget, onBack: () -> Unit) {
                Column(Modifier.fillMaxSize().padding(24.dp)) {
                    Text("启动恢复期间不运行第三方页面，请先修复或停用故障插件。")
                    TextButton(onClick = onBack) { Text("返回插件管理") }
                }
            }
            override fun openPluginPage(context: Context, record: PluginRecord, routeId: String) {
                blockedPage = true
            }
        }
    }
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        TextButton(onClick = onClose) { Text("返回并重试启动") }
        if (blockedPage) Text("启动恢复期间不打开第三方页面。", Modifier.padding(horizontal = 16.dp))
        Box(Modifier.weight(1f)) {
            val native = NativeShells.builtin()
            if (native != null) native.PluginManager(vm, hostUi, onClose)
            else Text("内置插件管理不可用，请返回后重试。", Modifier.padding(24.dp))
        }
    }
}

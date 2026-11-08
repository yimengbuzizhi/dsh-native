package xin.dshaa.nativeapp.ui.terminal

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.ai.assistance.operit.terminal.TerminalManager
import com.ai.assistance.operit.terminal.main.TerminalScreen
import com.ai.assistance.operit.terminal.rememberTerminalEnv

/**
 * 终端页：直接接 OperitTerminalCore（LGPL，源码复制）的 `TerminalScreen`。
 *
 * 首次进入走它自己的 SetupScreen（会把 rootfs / proot 展开到 App 私有目录），
 * 之后是正常的多会话终端。TerminalManager 是进程级单例，切页不会杀会话。
 */
@Composable
fun TerminalPage(useLocalImeHandling: Boolean = true) {
    val context = LocalContext.current
    val manager = remember { TerminalManager.getInstance(context) }
    val env = rememberTerminalEnv(manager)
    TerminalScreen(env = env, useLocalImeHandling = useLocalImeHandling, checkUpdatesOnEnter = false)
}

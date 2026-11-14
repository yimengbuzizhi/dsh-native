package xin.dshaa.nativeapp.ui.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ai.assistance.operit.terminal.TerminalManager
import com.ai.assistance.operit.terminal.main.TerminalScreen
import com.ai.assistance.operit.terminal.rememberTerminalEnv
import xin.dshaa.ui.design.theme.DshTheme2

/**
 * 终端页：直接接 OperitTerminalCore（LGPL，源码复制）的 `TerminalScreen`。
 *
 * 首次进入走它自己的 SetupScreen（会把 rootfs / proot 展开到 App 私有目录），
 * 之后是正常的多会话终端。TerminalManager 是进程级单例，切页不会杀会话。
 *
 * M74：Operit 的 TerminalScreen 没有返回入口（它是独立 App 的首页），从设置进来
 * 只能开抽屉 → 加一条自己的细顶栏 + 返回箭头（用户 23:00 的第 1 条）。
 */
@Composable
fun TerminalPage(
    useLocalImeHandling: Boolean = true,
    onBack: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val manager = remember { TerminalManager.getInstance(context) }
    val env = rememberTerminalEnv(manager)
    Column(Modifier.fillMaxSize()) {
        if (onBack != null) {
            val c = DshTheme2.colors
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(c.surface)
                    .statusBarsPadding()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.size(40.dp).clip(CircleShape).clickable { onBack() },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Rounded.ArrowBack, "返回设置", tint = c.text, modifier = Modifier.size(23.dp))
                }
                Spacer(Modifier.width(4.dp))
                Text(
                    "终端",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = c.text,
                )
            }
        }
        TerminalScreen(env = env, useLocalImeHandling = useLocalImeHandling, checkUpdatesOnEnter = false)
    }
}

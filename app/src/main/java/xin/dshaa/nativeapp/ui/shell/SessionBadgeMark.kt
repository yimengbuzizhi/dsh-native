package xin.dshaa.nativeapp.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import xin.dshaa.nativeapp.chat.SessionBadge
import xin.dshaa.ui.design.theme.DshTheme2

/**
 * 会话列表右侧状态标（2026-11-08 夜间）：生成中转圈、等回答橙点、未读完成绿点。
 * 抽屉与「全部会话」页共用同一套视觉。
 */
@Composable
fun SessionBadgeMark(badge: SessionBadge?) {
    val c = DshTheme2.colors
    when (badge) {
        SessionBadge.RUNNING -> CircularProgressIndicator(
            modifier = Modifier.size(13.dp),
            strokeWidth = 1.6.dp,
            color = c.brand,
        )
        SessionBadge.WAITING -> Box(Modifier.size(8.dp).clip(CircleShape).background(c.warn))
        SessionBadge.DONE -> Box(Modifier.size(8.dp).clip(CircleShape).background(c.success))
        null -> Unit
    }
}

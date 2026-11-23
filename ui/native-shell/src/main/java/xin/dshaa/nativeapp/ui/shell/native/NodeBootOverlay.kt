package xin.dshaa.nativeapp.ui.shell.native

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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import xin.dshaa.ui.design.component.liquidGlass
import xin.dshaa.ui.design.theme.DshTheme2
import xin.dshaa.ui.design.theme.LiquidLevel
import xin.dshaa.ui.design.theme.LiquidRadius
import xin.dshaa.ui.shellapi.NodeBootProgressUi

/**
 * docs/190-C：Node 启动小卡片（非模态、任何页面可见）。
 *
 * 用户拍板的四态：解压（真实条目百分比）/ 启动（含重启第 n 次）/ 已就绪（1s 后由 VM 自动隐藏）/
 * 失败（保持）。按钮「收起 / 取消启动 / 重试 / 查看日志」。不提供永久「不再提示」。
 */
@Composable
fun NodeBootOverlay(
    progress: NodeBootProgressUi,
    logs: List<String>,
    onDismiss: () -> Unit,
    onRetry: () -> Unit,
    onStop: () -> Unit,
) {
    if (!progress.visible) return
    val c = DshTheme2.colors
    var showLogs by remember(progress.startedAtMs) { mutableStateOf(progress.failed) }
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    // 启动中每 0.5s 刷新「已用 N 秒」；READY/FAILED 后不再跳。
    LaunchedEffect(progress.phase, progress.startedAtMs) {
        nowMs = System.currentTimeMillis()
        while (progress.busy) {
            delay(500)
            nowMs = System.currentTimeMillis()
        }
    }
    val elapsedSec = if (progress.startedAtMs > 0L) {
        ((nowMs - progress.startedAtMs) / 1000L).coerceAtLeast(0L)
    } else {
        0L
    }

    Box(
        Modifier
            .fillMaxSize()
            .navigationBarsPadding()
            .imePadding()
            .padding(horizontal = 12.dp, vertical = 12.dp),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .liquidGlass(shape = RoundedCornerShape(LiquidRadius.xl), level = LiquidLevel.Panel)
                .padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = when {
                        progress.failed -> Icons.Rounded.ErrorOutline
                        progress.ready -> Icons.Rounded.CheckCircle
                        else -> Icons.Rounded.Memory
                    },
                    contentDescription = null,
                    tint = when {
                        progress.failed -> c.danger
                        progress.ready -> c.success
                        else -> c.brandBright
                    },
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = when (progress.phase) {
                        "extracting" -> "准备 Node 运行时 · ${progress.percent}%"
                        "starting" -> "启动 Node 宿主…"
                        "restarting" -> "重启 Node 宿主（第 ${progress.attempt} 次）…"
                        "ready" -> "Node 宿主已就绪"
                        "failed" -> "Node 宿主启动失败"
                        else -> "Node 宿主"
                    },
                    style = MaterialTheme.typography.titleSmall,
                    color = c.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "已用 ${elapsedSec}s",
                    style = MaterialTheme.typography.labelSmall,
                    color = c.faint,
                )
            }

            if (progress.busy) {
                Spacer(Modifier.height(8.dp))
                if (progress.phase == "extracting") {
                    LinearProgressIndicator(
                        progress = { (progress.percent.coerceIn(0, 100)) / 100f },
                        modifier = Modifier.fillMaxWidth(),
                        color = c.brandBright,
                        trackColor = c.line,
                    )
                } else {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth(),
                        color = c.brandBright,
                        trackColor = c.line,
                    )
                }
            }

            Spacer(Modifier.height(6.dp))
            val detail = when {
                progress.failed -> progress.error.ifBlank { progress.detail }
                progress.phase == "extracting" -> progress.detail
                progress.ready -> progress.detail
                else -> progress.detail
            }
            if (detail.isNotBlank()) {
                Text(
                    detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (progress.failed) c.danger else c.sub,
                    maxLines = if (progress.failed) 4 else 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (progress.phase == "extracting" && progress.percent < 100) {
                Text(
                    "首次解压约 25MB，之后 1–3 秒。",
                    style = MaterialTheme.typography.labelSmall,
                    color = c.faint,
                )
            }

            if (showLogs) {
                Spacer(Modifier.height(6.dp))
                Column(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 132.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    if (logs.isEmpty()) {
                        Text("还没有宿主日志。", style = MaterialTheme.typography.labelSmall, color = c.faint)
                    } else {
                        logs.takeLast(30).forEach { line ->
                            Text(
                                line,
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                color = c.sub,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onDismiss) { Text("收起", color = c.sub) }
                TextButton(onClick = { showLogs = !showLogs }) {
                    Text(if (showLogs) "收起日志" else "查看日志", color = c.brandBright)
                }
                if (progress.busy) {
                    TextButton(onClick = onStop) { Text("取消启动", color = c.danger) }
                }
                if (progress.failed) {
                    TextButton(onClick = onRetry) { Text("重试", color = c.brandBright) }
                }
            }
        }
    }
}

package xin.dshaa.nativeapp.memory

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import xin.dshaa.ui.design.theme.DshTheme2

/**
 * 「记忆」附件的真实记忆库选择器（替代 M55 的「当前会话记录」临时方案）。
 *
 * 交互：列出记忆库全部记忆（可按关键词过滤），勾选多条后一次性作为文本附件引用给 AI。
 * 内容是「标题 + 文件夹/标签/来源 + 正文」，正文按块截断，避免一次塞爆上下文。
 */
@Composable
fun MemoryPickerDialog(
    onDismiss: () -> Unit,
    onSelect: (List<Memory>) -> Unit,
) {
    val c = DshTheme2.colors
    val context = LocalContext.current
    val store = remember { MemoryStore.get(context) }
    var memories by remember { mutableStateOf<List<Memory>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var query by remember { mutableStateOf("") }
    val selected = remember { mutableStateMapOf<Long, Memory>() }

    LaunchedEffect(Unit) {
        memories = withContext(Dispatchers.IO) { store.allMemories() }
        loading = false
    }

    val filtered = remember(memories, query) {
        if (query.isBlank()) {
            memories
        } else {
            memories.filter { memory ->
                memory.title.contains(query, true) ||
                    memory.content.contains(query, true) ||
                    memory.tags.any { it.contains(query, true) } ||
                    folderLabel(memory.folderPath).contains(query, true)
            }
        }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp).fillMaxHeight(0.82f), contentAlignment = Alignment.Center) {
            Surface(
                Modifier.fillMaxWidth().fillMaxHeight(),
                shape = RoundedCornerShape(24.dp),
                color = c.surface,
            ) {
                Column(Modifier.fillMaxWidth().padding(18.dp)) {
                    Text("从记忆库引用", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, color = c.text)
                    Text(
                        "勾选后作为文本附件引用给 AI；AI 也能用 query_memory 自己查。",
                        style = MaterialTheme.typography.bodySmall,
                        color = c.faint,
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("过滤记忆…") },
                        leadingIcon = { Icon(Icons.Rounded.Search, null, modifier = Modifier.size(18.dp)) },
                        singleLine = true,
                    )
                    Spacer(Modifier.height(8.dp))
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        when {
                            loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                            filtered.isEmpty() -> Text(
                                if (memories.isEmpty()) "记忆库还是空的" else "没有匹配的记忆",
                                Modifier.align(Alignment.Center),
                                color = c.faint,
                            )
                            else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                items(filtered, key = { it.id }) { memory ->
                                    val checked = memory.id in selected
                                    Row(
                                        Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(12.dp))
                                            .background(if (checked) c.brandSoft else c.surfaceAlt)
                                            .clickable { if (checked) selected.remove(memory.id) else selected[memory.id] = memory }
                                            .padding(horizontal = 10.dp, vertical = 9.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Column(Modifier.weight(1f)) {
                                            Text(
                                                memory.title,
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = FontWeight.Medium,
                                                color = c.text,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                            Text(
                                                folderLabel(memory.folderPath) +
                                                    if (memory.tags.isNotEmpty()) " · ${memory.tags.joinToString("、")}" else "",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = c.faint,
                                                maxLines = 1,
                                            )
                                        }
                                        if (checked) {
                                            Icon(Icons.Rounded.CheckCircle, "已选", tint = c.brand, modifier = Modifier.size(20.dp))
                                        }
                                    }
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        OutlinedButton(onClick = onDismiss) { Text("取消") }
                        Spacer(Modifier.width(8.dp))
                        Button(
                            onClick = { onSelect(selected.values.toList()) },
                            enabled = selected.isNotEmpty(),
                        ) { Text("引用 ${selected.size} 条") }
                    }
                }
            }
        }
    }
}

/** 记忆 → 附件正文（照 Operit「记忆上下文」的字段，正文每块最多 6000 字符）。 */
fun buildMemoryReference(memories: List<Memory>): String = buildString {
    appendLine("# 记忆库引用（${memories.size} 条）")
    appendLine()
    memories.forEach { memory ->
        appendLine("## ${memory.title}")
        appendLine("- 文件夹：${folderLabel(memory.folderPath)}")
        if (memory.tags.isNotEmpty()) appendLine("- 标签：${memory.tags.joinToString("、")}")
        appendLine("- 来源：${memory.source} · 重要性 ${"%.2f".format(memory.importance)} · 可信度 ${"%.2f".format(memory.credibility)}")
        if (memory.isDocumentNode) appendLine("- 类型：文档记忆（${memory.documentPath ?: "无路径"}）")
        appendLine()
        appendLine(memory.content.take(6000))
        appendLine()
    }
}

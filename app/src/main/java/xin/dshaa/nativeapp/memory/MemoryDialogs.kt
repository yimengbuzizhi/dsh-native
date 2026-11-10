package xin.dshaa.nativeapp.memory

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Cancel
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

private fun formatTime(ms: Long): String = timeFormat.format(Date(ms))

/**
 * 新建/编辑记忆（照搬 Operit `EditMemoryDialog` 的字段与布局）。
 * 文档节点的正文只读（内容由分块维护）。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun EditMemoryDialog(
    memory: Memory?,
    allFolderPaths: List<String>,
    onDismiss: () -> Unit,
    onSave: (Memory?, String, String, String, String, Float, Float, String, List<String>) -> Unit,
) {
    var title by remember(memory) { mutableStateOf(memory?.title ?: "") }
    var content by remember(memory) { mutableStateOf(memory?.content ?: "") }
    val contentType = memory?.contentType ?: "text/plain"
    var source by remember(memory) { mutableStateOf(memory?.source ?: "user_input") }
    var credibility by remember(memory) { mutableStateOf(memory?.credibility ?: 0.8f) }
    var importance by remember(memory) { mutableStateOf(memory?.importance ?: 0.5f) }
    var folderPath by remember(memory) {
        mutableStateOf(memory?.folderPath?.takeIf { it.isNotBlank() } ?: UNCATEGORIZED)
    }
    val tags = remember(memory) { mutableStateListOf<String>().apply { addAll(memory?.tags.orEmpty()) } }
    val scrollState = rememberScrollState()

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(
            Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 20.dp),
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                Modifier.fillMaxWidth().widthIn(max = 560.dp).fillMaxHeight(0.92f).imePadding(),
                shape = MaterialTheme.shapes.extraLarge,
                tonalElevation = 6.dp,
            ) {
                Column(Modifier.fillMaxSize()) {
                    Text(
                        if (memory == null) "新建记忆" else "编辑记忆",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(start = 24.dp, top = 20.dp, end = 24.dp, bottom = 14.dp),
                    )
                    HorizontalDivider()
                    Column(
                        Modifier.weight(1f).verticalScroll(scrollState).padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        OutlinedTextField(
                            value = title,
                            onValueChange = { title = it },
                            label = { Text("标题") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                        )
                        OutlinedTextField(
                            value = content,
                            onValueChange = { content = it },
                            label = { Text("内容") },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 240.dp),
                            enabled = memory?.isDocumentNode != true,
                        )
                        FolderSelector(allFolderPaths, folderPath) { folderPath = it }
                        TagsEditor(tags) {
                            tags.clear()
                            tags.addAll(it)
                        }
                        OutlinedTextField(
                            value = source,
                            onValueChange = { source = it },
                            label = { Text("来源") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                        )
                        Text("可信度：${"%.2f".format(credibility)}")
                        Slider(value = credibility, onValueChange = { credibility = it }, valueRange = 0f..1f)
                        Text("重要性：${"%.2f".format(importance)}")
                        Slider(value = importance, onValueChange = { importance = it }, valueRange = 0f..1f)
                    }
                    HorizontalDivider()
                    Row(
                        Modifier.fillMaxWidth().padding(16.dp),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        OutlinedButton(onClick = onDismiss) { Text("取消") }
                        Spacer(Modifier.width(8.dp))
                        Button(onClick = {
                            onSave(memory, title.trim(), content, contentType, source, credibility, importance, folderPath, tags.toList())
                            onDismiss()
                        }) { Text("保存") }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FolderSelector(
    allFolderPaths: List<String>,
    selectedPath: String,
    onPathSelected: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val options = (listOf(UNCATEGORIZED) + allFolderPaths).distinct()
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = !expanded }) {
        OutlinedTextField(
            value = selectedPath,
            onValueChange = {},
            readOnly = true,
            label = { Text("文件夹") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { path ->
                DropdownMenuItem(text = { Text(path) }, onClick = {
                    onPathSelected(path)
                    expanded = false
                })
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TagsEditor(tags: List<String>, onTagsChanged: (List<String>) -> Unit) {
    var newTagText by remember { mutableStateOf("") }
    val keyboard = LocalSoftwareKeyboardController.current
    fun commit() {
        val clean = newTagText.trim()
        if (clean.isNotEmpty() && clean !in tags) onTagsChanged(tags + clean)
        newTagText = ""
        keyboard?.hide()
    }
    Column {
        Text("标签", style = MaterialTheme.typography.labelLarge)
        if (tags.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                tags.forEach { tag ->
                    InputChip(
                        selected = false,
                        onClick = {},
                        label = { Text(tag) },
                        trailingIcon = {
                            IconButton(onClick = { onTagsChanged(tags - tag) }, modifier = Modifier.size(18.dp)) {
                                Icon(Icons.Rounded.Cancel, "移除标签", modifier = Modifier.size(14.dp))
                            }
                        },
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(
            value = newTagText,
            onValueChange = { newTagText = it },
            placeholder = { Text("添加标签（回车确认）") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { commit() }),
            trailingIcon = {
                IconButton(onClick = { commit() }) {
                    Icon(Icons.Rounded.Add, "添加标签")
                }
            },
        )
    }
}

/** 记忆详情（照搬 Operit `MemoryInfoDialog`，文档节点多一个「查看文档」）。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MemoryInfoDialog(
    memory: Memory,
    onDismiss: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onOpenDocument: (() -> Unit)? = null,
) {
    var confirmDelete by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("记忆详情") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(memory.title, style = MaterialTheme.typography.titleMedium)
                HorizontalDivider()
                Text("内容：", style = MaterialTheme.typography.titleSmall)
                Text(memory.content.ifBlank { "（文档记忆，内容在分块里）" })
                HorizontalDivider()
                Text("文件夹：${folderLabel(memory.folderPath)}", style = MaterialTheme.typography.bodySmall)
                Text("标签：${memory.tags.joinToString("、").ifBlank { "无" }}", style = MaterialTheme.typography.bodySmall)
                Text("来源：${memory.source}", style = MaterialTheme.typography.bodySmall)
                Text("重要性：${"%.2f".format(memory.importance)}", style = MaterialTheme.typography.bodySmall)
                Text("可信度：${"%.2f".format(memory.credibility)}", style = MaterialTheme.typography.bodySmall)
                Text("创建：${formatTime(memory.createdAt)}", style = MaterialTheme.typography.bodySmall)
                Text("更新：${formatTime(memory.updatedAt)}", style = MaterialTheme.typography.bodySmall)
                Text("UUID：${memory.uuid}", style = MaterialTheme.typography.bodySmall)
                if (confirmDelete) {
                    Text("再点一次「删除」确认删除这条记忆。", color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            FlowRow(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            ) {
                Button(onClick = onEdit) { Text("编辑") }
                if (onOpenDocument != null) {
                    Button(onClick = onOpenDocument) { Text("查看文档") }
                }
                Button(
                    onClick = { if (confirmDelete) onDelete() else confirmDelete = true },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) { Text("删除") }
                OutlinedButton(onClick = onDismiss) { Text("关闭") }
            }
        },
    )
}

@Composable
fun EdgeInfoDialog(
    edge: MemoryEdge,
    graph: MemoryGraph,
    onDismiss: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val source = graph.nodes.firstOrNull { it.id == edge.sourceId }?.label ?: "?"
    val target = graph.nodes.firstOrNull { it.id == edge.targetId }?.label ?: "?"
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("连接详情") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("从：$source")
                Text("到：$target")
                HorizontalDivider()
                Text("类型：${edge.label ?: "related"}")
                Text("权重：${edge.weight}")
                if (edge.isCrossFolderLink) Text("跨文件夹连接", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onEdit) { Text("编辑") }
                Button(
                    onClick = onDelete,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) { Text("删除") }
                OutlinedButton(onClick = onDismiss) { Text("关闭") }
            }
        },
    )
}

@Composable
fun EditEdgeDialog(
    edge: MemoryEdge,
    onDismiss: () -> Unit,
    onSave: (String, Float, String) -> Unit,
) {
    var type by remember { mutableStateOf(edge.label ?: "related") }
    var weight by remember { mutableStateOf(edge.weight.toString()) }
    var description by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("编辑连接") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(type, { type = it }, label = { Text("类型") }, singleLine = true)
                OutlinedTextField(weight, { weight = it }, label = { Text("权重（0-1）") }, singleLine = true)
                OutlinedTextField(description, { description = it }, label = { Text("描述") })
            }
        },
        confirmButton = {
            Button(onClick = { onSave(type, weight.toFloatOrNull()?.coerceIn(0f, 1f) ?: 1.0f, description) }) {
                Text("保存")
            }
        },
        dismissButton = { OutlinedButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
fun LinkMemoryDialog(
    sourceLabel: String,
    targetLabel: String,
    onDismiss: () -> Unit,
    onLink: (String, Float, String) -> Unit,
) {
    var type by remember { mutableStateOf("related") }
    var weight by remember { mutableStateOf("1.0") }
    var description by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("连接：$sourceLabel → $targetLabel") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(type, { type = it }, label = { Text("类型") }, singleLine = true)
                OutlinedTextField(weight, { weight = it }, label = { Text("权重（0-1）") }, singleLine = true)
                OutlinedTextField(description, { description = it }, label = { Text("描述") })
            }
        },
        confirmButton = {
            Button(onClick = { onLink(type.ifBlank { "related" }, weight.toFloatOrNull()?.coerceIn(0f, 1f) ?: 1.0f, description) }) {
                Text("建立连接")
            }
        },
        dismissButton = { OutlinedButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
fun BatchDeleteConfirmDialog(
    selectedCount: Int,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("删除所选记忆") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("将删除 $selectedCount 条记忆，且不可恢复。")
                Text("相关连接和文档分块会一起删除。", color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
            ) { Text("删除") }
        },
        dismissButton = { OutlinedButton(onClick = onDismiss) { Text("取消") } },
    )
}

/** 文档查看/编辑（照搬 Operit `DocumentViewDialog`：分块列表 + 文档内搜索 + 保存/删除）。 */
@Composable
fun DocumentViewDialog(
    memoryTitle: String,
    onTitleChange: (String) -> Unit,
    chunks: List<DocumentChunk>,
    chunkStates: Map<Long, String>,
    onChunkChange: (Long, String) -> Unit,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    onPerformSearch: () -> Unit,
    onDismiss: () -> Unit,
    onSave: () -> Unit,
    onDelete: () -> Unit,
) {
    val keyboard = LocalSoftwareKeyboardController.current
    AlertDialog(
        modifier = Modifier.fillMaxHeight(0.88f),
        onDismissRequest = onDismiss,
        title = {
            OutlinedTextField(
                value = memoryTitle,
                onValueChange = onTitleChange,
                label = { Text("文档标题") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
        },
        text = {
            Column(Modifier.fillMaxSize()) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = onSearchQueryChange,
                    label = { Text("在文档内搜索") },
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    singleLine = true,
                    trailingIcon = {
                        IconButton(onClick = {
                            onPerformSearch()
                            keyboard?.hide()
                        }) { Icon(Icons.Rounded.Search, "搜索") }
                    },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = {
                        onPerformSearch()
                        keyboard?.hide()
                    }),
                )
                if (chunks.isEmpty()) {
                    Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                        Text(if (searchQuery.isBlank()) "这个文档没有分块" else "没有匹配的分块")
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(chunks, key = { it.id }) { chunk ->
                            OutlinedTextField(
                                value = chunkStates[chunk.id] ?: chunk.content,
                                onValueChange = { onChunkChange(chunk.id, it) },
                                modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 280.dp),
                                label = { Text("第 ${chunk.chunkIndex + 1} 块") },
                            )
                        }
                    }
                }
            }
        },
        dismissButton = { OutlinedButton(onClick = onDismiss) { Text("关闭") } },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onSave) { Text("保存全部") }
                Button(
                    onClick = onDelete,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) { Text("删除文档") }
            }
        },
    )
}

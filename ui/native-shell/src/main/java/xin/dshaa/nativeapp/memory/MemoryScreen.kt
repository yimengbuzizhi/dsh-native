package xin.dshaa.nativeapp.memory

import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SelectAll
import androidx.compose.material.icons.rounded.UploadFile
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import xin.dshaa.ui.design.theme.DshTheme2

/**
 * 记忆库页面（照搬 Operit `MemoryScreen` 的结构）：
 *
 * 1. 顶栏：返回 / 标题 / 记忆条数；
 * 2. 搜索行：文件夹按钮 / 搜索框 / 搜索按钮（Operit 的搜索设置按钮鲸歌本版不摆）；
 * 3. 图谱区：GraphVisualizer（Operit 原样搬来的 Canvas 力导向图）；
 * 4. 右下 FAB 栈（照 Operit 顺序）：批量删除（仅框选模式）/ 框选 / 连接 / 导入文档 / 新建；
 * 5. 左侧文件夹抽屉：全部 / 未分类 / 文件夹树 + 新建/重命名/删除；
 * 6. 详情弹层：编辑记忆 / 记忆详情 / 文档分块 / 连接详情 / 连接编辑 / 批量删除确认。
 *
 * 数据层是 [MemoryStore]（SQLite），没有 embedding/HNSW；语义检索与「记忆空间（profile）」
 * 明确未搬（见 docs/HANDOFF.md）。
 */
@Composable
fun MemoryScreen(
    onBack: () -> Unit,
    vm: MemoryViewModel = viewModel(),
) {
    val c = DshTheme2.colors
    val context = LocalContext.current
    val keyboard = LocalSoftwareKeyboardController.current
    val state by vm.uiState.collectAsState()

    var showFolderNav by remember { mutableStateOf(false) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    LaunchedEffect(state.message) {
        state.message?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            vm.clearMessage()
        }
    }
    LaunchedEffect(state.error) {
        state.error?.let {
            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
            vm.clearError()
        }
    }

    // 导入文档：只收文本（PDF/Word 的文本抽取还没接，见 HANDOFF 缺口）
    fun documentTitle(uri: Uri): String {
        runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index >= 0) return cursor.getString(index).orEmpty()
                }
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "文档"
    }

    val documentPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val title = documentTitle(uri).ifBlank { "文档" }
        scope.launch {
            val text = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.use { stream ->
                        stream.readBytes().toString(Charsets.UTF_8)
                    }
                }.getOrNull()
            }
            if (text == null) {
                Toast.makeText(context, "读取文件失败", Toast.LENGTH_LONG).show()
                return@launch
            }
            val nulCount = text.take(4000).count { it == '\u0000' }
            if (nulCount > 8) {
                Toast.makeText(context, "这个文件不像文本文件，暂不支持（PDF/Word 抽取未接）", Toast.LENGTH_LONG).show()
            } else {
                vm.importDocument(title, uri.toString(), text)
            }
        }
    }

    Box(Modifier.fillMaxSize().background(c.background)) {
        Column(Modifier.fillMaxSize()) {
            // ---- 顶栏 ----
            Row(
                Modifier.fillMaxWidth().background(c.surface).statusBarsPadding()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.size(40.dp).clip(CircleShape).clickable { onBack() },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Rounded.ArrowBack, "返回", tint = c.text, modifier = Modifier.size(23.dp))
                }
                Spacer(Modifier.width(4.dp))
                Column(Modifier.weight(1f)) {
                    Text("记忆库", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, color = c.text)
                    Text(
                        "${state.graph.nodes.size} 条记忆 · ${state.folderPaths.size} 个文件夹" +
                            if (state.selectedFolderPath.isNotEmpty()) " · ${state.selectedFolderPath}" else "",
                        style = MaterialTheme.typography.labelSmall,
                        color = c.faint,
                        maxLines = 1,
                    )
                }
                Text(
                    "AI 可用",
                    style = MaterialTheme.typography.labelSmall,
                    color = c.brand,
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(c.brandSoft)
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                )
            }

            // ---- 搜索行（Operit MemorySearchBar）----
            Row(
                Modifier.fillMaxWidth().background(c.surface).padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                IconButton(onClick = { showFolderNav = !showFolderNav }) {
                    Icon(Icons.Rounded.Folder, "文件夹", tint = c.brand)
                }
                OutlinedTextField(
                    value = state.searchQuery,
                    onValueChange = { vm.onSearchQueryChange(it) },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("搜索记忆…", style = MaterialTheme.typography.bodySmall) },
                    singleLine = true,
                    trailingIcon = {
                        if (state.searchQuery.isNotEmpty()) {
                            IconButton(onClick = {
                                vm.onSearchQueryChange("")
                                vm.searchMemories()
                            }) { Icon(Icons.Rounded.Clear, "清空", modifier = Modifier.size(18.dp)) }
                        }
                    },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = {
                        keyboard?.hide()
                        vm.searchMemories()
                    }),
                )
                IconButton(onClick = {
                    keyboard?.hide()
                    vm.searchMemories()
                }) {
                    Icon(Icons.Rounded.Search, "搜索", tint = c.sub)
                }
            }

            // ---- 图谱区 ----
            Box(Modifier.weight(1f).fillMaxWidth()) {
                GraphVisualizer(
                    graph = state.graph,
                    modifier = Modifier.fillMaxSize(),
                    selectedNodeId = state.selectedNodeId,
                    boxSelectedNodeIds = state.boxSelectedNodeIds,
                    isBoxSelectionMode = state.isBoxSelectionMode,
                    linkingNodeIds = state.linkingNodeIds,
                    selectedEdgeId = state.selectedEdge?.id,
                    onNodeClick = { vm.selectNode(it) },
                    onEdgeClick = { vm.selectEdge(it) },
                    onNodesSelected = { vm.addNodesToSelection(it) },
                )
                if (state.isLoading) {
                    CircularProgressIndicator(Modifier.align(Alignment.Center))
                }
                if (!state.isLoading && state.graph.nodes.isEmpty()) {
                    Column(
                        Modifier.align(Alignment.Center).padding(horizontal = 36.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text("记忆库还是空的", style = MaterialTheme.typography.titleMedium, color = c.sub)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "点右下角 + 新建一条，或让 AI 用 create_memory / query_memory 记住与检索事情。",
                            style = MaterialTheme.typography.bodySmall,
                            color = c.faint,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
        }

        // ---- FAB 栈（Operit 顺序：批量删除 / 框选 / 连接 / 导入 / 新建）----
        Column(
            Modifier
                .align(Alignment.BottomEnd)
                .navigationBarsPadding()
                .padding(end = 16.dp, bottom = 18.dp),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (state.isBoxSelectionMode) {
                FloatingActionButton(
                    onClick = { vm.showBatchDeleteConfirm() },
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    modifier = Modifier.size(48.dp),
                ) { Icon(Icons.Rounded.Delete, "删除所选") }
            }
            FloatingActionButton(
                onClick = { vm.toggleBoxSelectionMode(!state.isBoxSelectionMode) },
                containerColor = if (state.isBoxSelectionMode) c.purpleSoft else c.surfaceAlt,
                modifier = Modifier.size(48.dp),
            ) { Icon(Icons.Rounded.SelectAll, "框选", tint = if (state.isBoxSelectionMode) c.purple else c.text) }
            FloatingActionButton(
                onClick = {
                    vm.toggleLinkingMode(!state.isLinkingMode)
                    if (!state.isLinkingMode) Toast.makeText(context, "点两个节点建立连接", Toast.LENGTH_SHORT).show()
                },
                containerColor = if (state.isLinkingMode) c.brand else c.surfaceAlt,
                modifier = Modifier.size(48.dp),
            ) { Icon(Icons.Rounded.Link, "连接", tint = if (state.isLinkingMode) Color.White else c.text) }
            FloatingActionButton(
                onClick = { runCatching { documentPicker.launch(arrayOf("text/*", "application/json", "application/octet-stream")) } },
                containerColor = c.surfaceAlt,
                modifier = Modifier.size(48.dp),
            ) { Icon(Icons.Rounded.UploadFile, "导入文档", tint = c.text) }
            FloatingActionButton(
                onClick = { vm.startEditing(null) },
                containerColor = c.brand,
                modifier = Modifier.size(52.dp),
            ) { Icon(Icons.Rounded.Add, "新建记忆", tint = Color.White) }
        }

        // ---- 左侧文件夹抽屉 ----
        if (showFolderNav) {
            Box(
                Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.28f)).clickable { showFolderNav = false },
            )
            AnimatedVisibility(
                visible = showFolderNav,
                enter = slideInHorizontally(initialOffsetX = { -it }),
                exit = slideOutHorizontally(targetOffsetX = { -it }),
            ) {
                FolderNavigator(
                    folderPaths = state.folderPaths,
                    selectedFolderPath = state.selectedFolderPath,
                    onFolderSelected = {
                        showFolderNav = false
                        vm.selectFolder(it)
                    },
                    onFolderRename = { old, new -> vm.renameFolder(old, new) },
                    onFolderDelete = { vm.deleteFolder(it) },
                    onFolderCreate = { vm.createFolder(it) },
                    onRefresh = { vm.refreshFolderList() },
                    onDismissRequest = { showFolderNav = false },
                )
            }
        }
    }

    // ---- 弹层 ----
    if (state.isEditing) {
        EditMemoryDialog(
            memory = state.editingMemory,
            allFolderPaths = state.folderPaths,
            onDismiss = { vm.cancelEditing() },
            onSave = { memory, title, content, contentType, source, credibility, importance, folderPath, tags ->
                if (memory == null) {
                    vm.createMemory(title, content, contentType, source, credibility, importance, folderPath, tags)
                } else {
                    vm.updateMemory(memory, title, content, contentType, source, credibility, importance, folderPath, tags)
                }
            },
        )
    }

    if (state.linkingNodeIds.size == 2) {
        val source = state.graph.nodes.firstOrNull { it.id == state.linkingNodeIds[0] }
        val target = state.graph.nodes.firstOrNull { it.id == state.linkingNodeIds[1] }
        if (source != null && target != null) {
            LinkMemoryDialog(
                sourceLabel = source.label,
                targetLabel = target.label,
                onDismiss = { vm.toggleLinkingMode(false) },
                onLink = { type, weight, description ->
                    vm.linkMemories(source.id, target.id, type, weight, description)
                    vm.toggleLinkingMode(false)
                },
            )
        }
    }

    if (state.isEditingEdge && state.editingEdge != null) {
        EditEdgeDialog(
            edge = state.editingEdge!!,
            onDismiss = { vm.cancelEditingEdge() },
            onSave = { type, weight, description ->
                vm.updateEdge(state.editingEdge!!, type, weight, description)
            },
        )
    }

    state.selectedEdge?.let { edge ->
        EdgeInfoDialog(
            edge = edge,
            graph = state.graph,
            onDismiss = { vm.clearSelection() },
            onEdit = { vm.startEditingEdge(edge) },
            onDelete = { vm.deleteEdge(edge.id) },
        )
    }

    if (state.showBatchDeleteConfirm) {
        BatchDeleteConfirmDialog(
            selectedCount = state.boxSelectedNodeIds.size,
            onDismiss = { vm.dismissBatchDeleteConfirm() },
            onConfirm = { vm.deleteSelectedNodes() },
        )
    }

    val memory = state.selectedMemory
    if (memory != null && state.isDocumentViewOpen) {
        var memoryTitle by remember(memory.id) { mutableStateOf(memory.title) }
        val chunkStates = remember(memory.id) { mutableStateMapOf<Long, String>() }
        LaunchedEffect(memory.id, state.selectedDocumentChunks) {
            chunkStates.clear()
            state.selectedDocumentChunks.forEach { chunkStates[it.id] = it.content }
        }
        DocumentViewDialog(
            memoryTitle = memoryTitle,
            onTitleChange = { memoryTitle = it },
            chunks = state.selectedDocumentChunks,
            chunkStates = chunkStates,
            onChunkChange = { id, content -> chunkStates[id] = content },
            searchQuery = state.documentSearchQuery,
            onSearchQueryChange = { vm.onDocumentSearchQueryChange(it) },
            onPerformSearch = { vm.performSearchInDocument() },
            onDismiss = { vm.closeDocumentView() },
            onSave = {
                vm.saveDocument(memory, memoryTitle, chunkStates.toMap())
            },
            onDelete = { vm.deleteMemory(memory.id) },
        )
    } else if (memory != null) {
        MemoryInfoDialog(
            memory = memory,
            onDismiss = { vm.clearSelection() },
            onEdit = {
                vm.startEditing(memory)
                vm.clearSelection()
            },
            onDelete = { vm.deleteMemory(memory.id) },
            onOpenDocument = if (memory.isDocumentNode) {
                { vm.openDocument(memory) }
            } else {
                null
            },
        )
    }
}

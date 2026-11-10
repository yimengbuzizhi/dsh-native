package xin.dshaa.nativeapp.memory

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 记忆库 UI 状态（照搬 Operit `MemoryUiState`，去掉 embedding/搜索设置/工具测试等
 * 鲸歌没有对应服务或本批不做的字段）。
 */
data class MemoryUiState(
    val graph: MemoryGraph = MemoryGraph(),
    val memories: List<Memory> = emptyList(),
    val selectedMemory: Memory? = null,
    val selectedNodeId: String? = null,
    val isLoading: Boolean = false,
    val searchQuery: String = "",
    val error: String? = null,
    val editingMemory: Memory? = null,
    val isEditing: Boolean = false,
    val isLinkingMode: Boolean = false,
    val linkingNodeIds: List<String> = emptyList(),
    val selectedEdge: MemoryEdge? = null,
    val editingEdge: MemoryEdge? = null,
    val isEditingEdge: Boolean = false,
    val isBoxSelectionMode: Boolean = false,
    val boxSelectedNodeIds: Set<String> = emptySet(),
    val showBatchDeleteConfirm: Boolean = false,
    val folderPaths: List<String> = emptyList(),
    val selectedFolderPath: String = "",
    val message: String? = null,
    val selectedDocumentChunks: List<DocumentChunk> = emptyList(),
    val documentSearchQuery: String = "",
    val isDocumentViewOpen: Boolean = false,
)

/** 记忆库页面 ViewModel（Operit MemoryViewModel 的鲸歌版）。 */
class MemoryViewModel(app: Application) : AndroidViewModel(app) {

    private val store = MemoryStore.get(app)
    private val _uiState = MutableStateFlow(MemoryUiState())
    val uiState: StateFlow<MemoryUiState> = _uiState.asStateFlow()

    init {
        loadMemoryGraph()
        loadFolderPaths()
    }

    private suspend fun graphForCurrentScope(): MemoryGraph {
        val folder = _uiState.value.selectedFolderPath
        return if (folder.isEmpty()) store.memoryGraph() else store.graphForFolder(folder)
    }

    fun loadMemoryGraph() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            try {
                val graph = graphForCurrentScope()
                _uiState.update { it.copy(graph = graph, isLoading = false, error = null) }
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false, error = "加载记忆图谱失败：${e.message}") }
            }
        }
    }

    fun searchMemories() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            try {
                val state = _uiState.value
                val folder = state.selectedFolderPath.takeIf { it.isNotEmpty() }
                val memories = store.searchMemories(state.searchQuery, folder)
                // 与 Operit 一致：命中结果 + 直接邻居一起画
                val graph = if (state.searchQuery.isBlank() && folder == null) {
                    store.memoryGraph()
                } else {
                    store.graphForMemories(memories)
                }
                _uiState.update { it.copy(graph = graph, memories = memories, isLoading = false, error = null) }
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false, error = "搜索失败：${e.message}") }
            }
        }
    }

    fun onSearchQueryChange(newQuery: String) {
        _uiState.update { it.copy(searchQuery = newQuery) }
    }

    fun refreshFolderList() {
        viewModelScope.launch {
            val paths = store.folderPaths()
            _uiState.update { it.copy(folderPaths = paths) }
        }
    }

    private fun loadFolderPaths() = refreshFolderList()

    fun selectFolder(folderPath: String) {
        _uiState.update { it.copy(selectedFolderPath = folderPath, searchQuery = "") }
        loadMemoryGraph()
    }

    fun moveSelectedMemoriesToFolder(targetFolderPath: String) {
        viewModelScope.launch {
            val memories = _uiState.value.memories
            val ids = memories.mapNotNull { memory ->
                if (memory.uuid in _uiState.value.boxSelectedNodeIds) memory.id else null
            }
            val ok = store.moveMemoriesToFolder(ids, targetFolderPath)
            _uiState.update { it.copy(message = if (ok) "已移动 ${ids.size} 条记忆" else "移动失败") }
            refreshFolderList()
            loadMemoryGraph()
        }
    }

    // ------------------------------------------------------------ 选中

    fun selectMemory(memory: Memory) {
        _uiState.update { it.copy(selectedMemory = memory, selectedNodeId = memory.uuid) }
        if (memory.isDocumentNode) openDocument(memory)
    }

    fun selectNode(node: MemoryNode) {
        val state = _uiState.value
        if (state.isLinkingMode) {
            val ids = state.linkingNodeIds.toMutableList()
            if (node.id !in ids) ids += node.id
            _uiState.update { it.copy(linkingNodeIds = ids) }
            return
        }
        if (state.isBoxSelectionMode) {
            val ids = state.boxSelectedNodeIds.toMutableSet()
            if (!ids.add(node.id)) ids.remove(node.id)
            _uiState.update { it.copy(boxSelectedNodeIds = ids) }
            return
        }
        viewModelScope.launch {
            val memory = store.findMemoryByUuid(node.id) ?: return@launch
            _uiState.update {
                it.copy(selectedMemory = memory, selectedNodeId = node.id, selectedEdge = null)
            }
            if (memory.isDocumentNode) openDocument(memory)
        }
    }

    fun selectEdge(edge: MemoryEdge) {
        _uiState.update { it.copy(selectedEdge = edge, selectedNodeId = null, selectedMemory = null) }
    }

    fun clearSelection() {
        _uiState.update {
            it.copy(selectedMemory = null, selectedNodeId = null, selectedEdge = null, isDocumentViewOpen = false)
        }
    }

    fun clearError() = _uiState.update { it.copy(error = null) }

    fun clearMessage() = _uiState.update { it.copy(message = null) }

    // ------------------------------------------------------------ 文档

    fun openDocument(memory: Memory) {
        viewModelScope.launch {
            val query = _uiState.value.searchQuery
            val chunks = if (query.isBlank()) {
                store.chunksFor(memory.id)
            } else {
                store.searchChunksInDocument(memory.id, query)
            }
            _uiState.update {
                it.copy(
                    selectedDocumentChunks = chunks,
                    isDocumentViewOpen = true,
                    documentSearchQuery = query,
                )
            }
        }
    }

    fun onDocumentSearchQueryChange(query: String) {
        _uiState.update { it.copy(documentSearchQuery = query) }
    }

    fun performSearchInDocument() {
        val memory = _uiState.value.selectedMemory ?: return
        val query = _uiState.value.documentSearchQuery
        viewModelScope.launch {
            val chunks = if (query.isBlank()) {
                store.chunksFor(memory.id)
            } else {
                store.searchChunksInDocument(memory.id, query)
            }
            _uiState.update { it.copy(selectedDocumentChunks = chunks) }
        }
    }

    fun closeDocumentView() {
        _uiState.update {
            it.copy(
                isDocumentViewOpen = false,
                documentSearchQuery = "",
                selectedDocumentChunks = emptyList(),
                selectedMemory = null,
                selectedNodeId = null,
            )
        }
    }

    fun updateChunkContent(chunkId: Long, newContent: String) {
        viewModelScope.launch {
            store.updateChunk(chunkId, newContent)
            _uiState.update { state ->
                state.copy(selectedDocumentChunks = state.selectedDocumentChunks.map {
                    if (it.id == chunkId) it.copy(content = newContent) else it
                })
            }
        }
    }

    fun importDocument(title: String, filePath: String, text: String) {
        viewModelScope.launch {
            try {
                val memory = store.createDocumentMemory(
                    title = title,
                    originalPath = filePath,
                    text = text,
                    folderPath = _uiState.value.selectedFolderPath,
                )
                _uiState.update { it.copy(message = "已导入文档：${memory.title}") }
                refreshFolderList()
                loadMemoryGraph()
            } catch (e: Exception) {
                _uiState.update { it.copy(error = "导入文档失败：${e.message}") }
            }
        }
    }

    // ------------------------------------------------------------ 编辑

    fun startEditing(memory: Memory? = null) {
        _uiState.update { it.copy(editingMemory = memory, isEditing = true, selectedMemory = null) }
    }

    fun cancelEditing() {
        _uiState.update { it.copy(editingMemory = null, isEditing = false) }
    }

    fun createMemory(
        title: String,
        content: String,
        contentType: String = "text/plain",
        source: String = "user_input",
        credibility: Float = 0.8f,
        importance: Float = 0.5f,
        folderPath: String = "",
        tags: List<String> = emptyList(),
    ) {
        if (title.isBlank()) {
            _uiState.update { it.copy(message = "标题不能为空") }
            return
        }
        viewModelScope.launch {
            store.createMemory(
                title = title.trim(),
                content = content,
                contentType = contentType,
                source = source,
                folderPath = folderPath.ifBlank { _uiState.value.selectedFolderPath },
                tags = tags,
                credibility = credibility,
                importance = importance,
            )
            _uiState.update { it.copy(message = "已创建记忆：${title.trim()}", isEditing = false) }
            refreshFolderList()
            loadMemoryGraph()
        }
    }

    fun updateMemory(
        memory: Memory,
        newTitle: String,
        newContent: String,
        newContentType: String,
        newSource: String,
        newCredibility: Float,
        newImportance: Float,
        newFolderPath: String,
        newTags: List<String>,
    ) {
        if (newTitle.isBlank()) {
            _uiState.update { it.copy(message = "标题不能为空") }
            return
        }
        viewModelScope.launch {
            store.updateMemory(
                memory = memory,
                newTitle = newTitle.trim(),
                newContent = newContent,
                newContentType = newContentType,
                newSource = newSource,
                newCredibility = newCredibility,
                newImportance = newImportance,
                newFolderPath = newFolderPath,
                newTags = newTags,
            )
            _uiState.update { it.copy(message = "已保存：${newTitle.trim()}", isEditing = false) }
            refreshFolderList()
            loadMemoryGraph()
        }
    }

    /**
     * 文档保存：标题 → 各分块 → 刷新 → 关闭，全部在同一个协程里顺序执行。
     * （Operit 是先异步 updateMemory 再逐块更新最后立刻关闭，存在竞态；这里不复刻那个坑。）
     */
    fun saveDocument(memory: Memory, newTitle: String, editedChunks: Map<Long, String>) {
        viewModelScope.launch {
            if (newTitle.trim() != memory.title) {
                store.updateMemory(
                    memory = memory,
                    newTitle = newTitle.trim(),
                    newContent = memory.content,
                    newContentType = memory.contentType,
                    newSource = memory.source,
                    newCredibility = memory.credibility,
                    newImportance = memory.importance,
                    newFolderPath = memory.folderPath,
                    newTags = memory.tags,
                )
            }
            val originals = store.chunksFor(memory.id).associateBy { it.id }
            editedChunks.forEach { (id, content) ->
                if (originals[id]?.content != content) store.updateChunk(id, content)
            }
            _uiState.update {
                it.copy(
                    isDocumentViewOpen = false,
                    selectedMemory = null,
                    selectedNodeId = null,
                    documentSearchQuery = "",
                    selectedDocumentChunks = emptyList(),
                    message = "文档已保存",
                )
            }
            loadMemoryGraph()
        }
    }

    fun deleteMemory(memoryId: Long) {
        viewModelScope.launch {
            val ok = store.deleteMemory(memoryId)
            _uiState.update {
                it.copy(
                    selectedMemory = null,
                    selectedNodeId = null,
                    isDocumentViewOpen = false,
                    message = if (ok) "已删除记忆" else "删除失败",
                )
            }
            loadMemoryGraph()
        }
    }

    // ------------------------------------------------------------ 批量选择

    fun toggleBoxSelectionMode(enabled: Boolean) {
        _uiState.update {
            it.copy(
                isBoxSelectionMode = enabled,
                boxSelectedNodeIds = if (enabled) it.boxSelectedNodeIds else emptySet(),
                showBatchDeleteConfirm = false,
                isLinkingMode = false,
                linkingNodeIds = emptyList(),
            )
        }
    }

    fun addNodesToSelection(nodeIds: Set<String>) {
        _uiState.update { it.copy(boxSelectedNodeIds = nodeIds) }
    }

    fun showBatchDeleteConfirm() {
        if (_uiState.value.boxSelectedNodeIds.isEmpty()) {
            _uiState.update { it.copy(message = "还没有框选记忆") }
            return
        }
        _uiState.update { it.copy(showBatchDeleteConfirm = true) }
    }

    fun dismissBatchDeleteConfirm() {
        _uiState.update { it.copy(showBatchDeleteConfirm = false) }
    }

    fun deleteSelectedNodes() {
        viewModelScope.launch {
            val selected = _uiState.value.boxSelectedNodeIds
            val ids = _uiState.value.memories.filter { it.uuid in selected }.map { it.id }
            val removed = store.deleteMemoriesByIds(ids.toSet())
            _uiState.update {
                it.copy(
                    showBatchDeleteConfirm = false,
                    boxSelectedNodeIds = emptySet(),
                    isBoxSelectionMode = false,
                    message = "已删除 $removed 条记忆",
                )
            }
            loadMemoryGraph()
        }
    }

    // ------------------------------------------------------------ 连接

    fun toggleLinkingMode(enabled: Boolean) {
        _uiState.update {
            it.copy(
                isLinkingMode = enabled,
                linkingNodeIds = if (enabled) it.linkingNodeIds else emptyList(),
                isBoxSelectionMode = false,
            )
        }
    }

    fun linkMemories(sourceNodeId: String, targetNodeId: String, type: String, weight: Float, description: String) {
        viewModelScope.launch {
            val source = store.findMemoryByUuid(sourceNodeId)
            val target = store.findMemoryByUuid(targetNodeId)
            if (source == null || target == null) {
                _uiState.update { it.copy(message = "记忆不存在，无法建立连接") }
            } else {
                store.linkMemories(source.id, target.id, type, weight, description)
                _uiState.update {
                    it.copy(message = "已连接：${source.title} → ${target.title}", linkingNodeIds = emptyList())
                }
            }
            loadMemoryGraph()
        }
    }

    fun startEditingEdge(edge: MemoryEdge) {
        _uiState.update { it.copy(editingEdge = edge, isEditingEdge = true, selectedEdge = null) }
    }

    fun cancelEditingEdge() {
        _uiState.update { it.copy(editingEdge = null, isEditingEdge = false) }
    }

    fun updateEdge(edge: MemoryEdge, type: String, weight: Float, description: String) {
        viewModelScope.launch {
            store.updateLink(edge.id, type, weight, description)
            _uiState.update { it.copy(isEditingEdge = false, editingEdge = null, message = "已更新连接") }
            loadMemoryGraph()
        }
    }

    fun deleteEdge(edgeId: Long) {
        viewModelScope.launch {
            store.deleteLink(edgeId)
            _uiState.update { it.copy(selectedEdge = null, message = "已删除连接") }
            loadMemoryGraph()
        }
    }

    // ------------------------------------------------------------ 文件夹

    fun createFolder(folderPath: String) {
        viewModelScope.launch {
            val ok = store.createFolder(folderPath)
            _uiState.update { it.copy(message = if (ok) "已创建文件夹：$folderPath" else "文件夹路径无效") }
            refreshFolderList()
        }
    }

    fun renameFolder(oldPath: String, newPath: String) {
        viewModelScope.launch {
            val ok = store.renameFolder(oldPath, newPath)
            _uiState.update {
                it.copy(
                    message = if (ok) "已重命名：$oldPath → $newPath" else "重命名失败",
                    selectedFolderPath = if (it.selectedFolderPath == oldPath) newPath else it.selectedFolderPath,
                )
            }
            refreshFolderList()
            loadMemoryGraph()
        }
    }

    fun deleteFolder(folderPath: String) {
        viewModelScope.launch {
            store.deleteFolder(folderPath)
            _uiState.update {
                it.copy(
                    message = "已删除文件夹：$folderPath（记忆移到未分类）",
                    selectedFolderPath = if (it.selectedFolderPath == folderPath) "" else it.selectedFolderPath,
                )
            }
            refreshFolderList()
            loadMemoryGraph()
        }
    }
}

from pathlib import Path
p = Path('app/src/main/java/xin/dshaa/nativeapp/chat/AppViewModel.kt')
s = p.read_text(encoding='utf-8')
def replace(old, new):
    global s
    assert s.count(old) == 1, (old[:120], s.count(old))
    s = s.replace(old, new)
replace('    override fun attachImage(uri: Uri) {\n        val state = chat ?: return\n        if (state.readOnly || state.streaming) return', '''    private fun attachmentLive(state: ChatSessionState?): LiveChat? = state?.let { target ->
        lives[target]?.takeIf { it.acceptingInputs && !target.readOnly }
    }

    override fun attachImage(uri: Uri, target: ChatSessionState?) {
        val live = attachmentLive(target) ?: return
        val state = live.state''')
replace('    override fun attachPhoto(file: File) {\n        val state = chat ?: return\n        if (state.readOnly || state.streaming) {\n            file.delete()\n            return\n        }', '''    override fun attachPhoto(file: File, target: ChatSessionState?) {
        val live = attachmentLive(target) ?: run {
            deleteCameraTemp(file)
            return
        }
        val state = live.state''')
replace('runCatching { file.readBytes() }.getOrNull().also { file.delete() }', 'runCatching { file.inputStream().use { readAtMost(it, maxImageFileBytes + 1) } }.getOrNull().also { deleteCameraTemp(file) }')
replace('''            state.attachError = savePendingImage(state, bytes, null, file.name)
        }
    }

    /**
     * 截图''', '''            if (bytes.size > maxImageFileBytes) {
                state.attachError = "图片超过 50MB，请先压缩"
                return@launch
            }
            state.attachError = savePendingImage(state, bytes, null, file.name)
        }
    }

    /** Only remove a verified regular file from the app's private camera cache. */
    private fun deleteCameraTemp(file: File) {
        val camera = File(getApplication<Application>().cacheDir, "camera").canonicalFile
        val resolved = file.canonicalFile
        if (resolved.parentFile == camera && resolved.isFile && !java.nio.file.Files.isSymbolicLink(file.toPath())) {
            resolved.delete()
        }
    }

    /**
     * 截图''')
replace('    override fun attachScreenshot() {\n        val state = chat ?: return\n        if (state.readOnly || state.streaming) return', '''    override fun attachScreenshot(target: ChatSessionState?) {
        val live = attachmentLive(target) ?: return
        val state = live.state''')
replace('    override fun attachFile(uri: Uri) {\n        val state = chat ?: return\n        if (state.readOnly || state.streaming) return', '''    override fun attachFile(uri: Uri, target: ChatSessionState?) {
        val live = attachmentLive(target) ?: return
        val state = live.state
        val attachmentWorkspace = File(live.queueWorkspace)''')
replace('''            val error = withContext(Dispatchers.IO) { readPendingFile(state, uri) }
            if (chat !== state) return@launch
            state.attachError = error''', '''            val error = readPendingFile(state, uri, attachmentWorkspace)
            if (attachmentLive(state) !== live) return@launch
            state.attachError = error''')
replace('''     * LocationAttachActivity）组好；只读会话和生成中不允许加，避免把附件塞进正在发的回合。''', '''     * LocationAttachActivity）组好；写入发起时的草稿，已交付的输入有独立快照。''')
replace('''    override fun attachTextFile(name: String, text: String): Boolean {
        val state = chat ?: return false
        if (state.readOnly || state.streaming) return false''', '''    override fun attachTextFile(name: String, text: String, target: ChatSessionState?): Boolean {
        val state = attachmentLive(target)?.state ?: return false''')
replace('''    private suspend fun readPendingFile(state: ChatSessionState, uri: Uri): String? {''', '''    private suspend fun readPendingFile(state: ChatSessionState, uri: Uri, attachmentWorkspace: File): String? = withContext(Dispatchers.IO) {
        readPendingFileContents(state, uri, attachmentWorkspace)
    }

    private suspend fun readPendingFileContents(state: ChatSessionState, uri: Uri, attachmentWorkspace: File): String? {''')
replace('''            var finalName = name
            var n = 2
            while (state.pendingFiles.any { it.name == finalName }) {
                finalName = "${name.substringBeforeLast('.')} ($n).${name.substringAfterLast('.')}"
                n += 1
            }
            state.pendingFiles += PendingFile(finalName, text)
            return null''', '''            return withContext(Dispatchers.Main.immediate) {
                if (attachmentLive(state) == null) "原会话已关闭，附件未添加"
                else { addPendingFile(state, PendingFile(name, text)); null }
            }''')
replace('copyAttachmentToWorkspace(uri, name)', 'copyAttachmentToWorkspace(uri, name, attachmentWorkspace)')
replace('''            var finalName = name
            var n = 2
            while (state.pendingFiles.any { it.name == finalName }) {
                finalName = "${name.substringBeforeLast('.')} ($n).${name.substringAfterLast('.')}"
                n += 1
            }
            state.pendingFiles += PendingFile(
                name = finalName,
                text = "",
                path = relative,
                mime = mime,
                size = bytes,
            )
            null''', '''            withContext(Dispatchers.Main.immediate) {
                if (attachmentLive(state) == null) "原会话已关闭，附件未添加"
                else {
                    addPendingFile(state, PendingFile(name = name, text = "", path = relative, mime = mime, size = bytes))
                    null
                }
            }''')
replace('''    private fun copyAttachmentToWorkspace(uri: Uri, name: String): Pair<String, Long> {
        val dir = File(workspace, ".dsh/attachments").apply { mkdirs() }''', '''    private fun copyAttachmentToWorkspace(uri: Uri, name: String, attachmentWorkspace: File): Pair<String, Long> {
        val dir = File(attachmentWorkspace, ".dsh/attachments").apply { mkdirs() }''')
replace('val relative = target.relativeTo(workspace).path.replace', 'val relative = target.relativeTo(attachmentWorkspace).path.replace')
replace('''            // 选图期间用户切了会话：附件已落盘，但不该出现在新会话的草稿里
            if (chat !== state) return null
            state.pendingImages += PendingImage(ref, path)
            null''', '''            withContext(Dispatchers.Main.immediate) {
                if (attachmentLive(state) == null) "原会话已关闭，图片未添加"
                else { state.pendingImages += PendingImage(ref, path); null }
            }''')
replace('''        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var finalName = name
        var n = 2
        while (state.pendingFiles.any { it.name == finalName }) {
            finalName = "$base ($n)$ext"
            n += 1
        }
        state.pendingFiles += PendingFile(finalName, text)
        state.attachError = null
        return true
    }''', '''        addPendingFile(state, PendingFile(name, text))
        state.attachError = null
        return true
    }

    /** Main-thread mutation preserves all attachment completions even after switching foreground chat. */
    private fun addPendingFile(state: ChatSessionState, file: PendingFile) {
        val dot = file.name.lastIndexOf('.')
        val base = if (dot > 0) file.name.substring(0, dot) else file.name
        val ext = if (dot > 0) file.name.substring(dot) else ""
        var finalName = file.name
        var n = 2
        while (state.pendingFiles.any { it.name == finalName }) {
            finalName = "$base ($n)$ext"
            n += 1
        }
        state.pendingFiles += file.copy(name = finalName)
    }''')
replace('ShellPickRequest(++shellPickSerial, kind)', 'ShellPickRequest(++shellPickSerial, kind, chat)')
p.write_text(s, encoding='utf-8', newline='\n')

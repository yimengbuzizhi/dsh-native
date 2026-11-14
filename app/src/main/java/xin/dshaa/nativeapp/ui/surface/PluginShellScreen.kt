package xin.dshaa.nativeapp.ui.surface

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import xin.dshaa.core.model.BlockType
import xin.dshaa.core.model.toImageRef
import xin.dshaa.nativeapp.chat.AppViewModel
import xin.dshaa.nativeapp.chat.ShellPickKind
import xin.dshaa.nativeapp.data.PluginShellHost
import xin.dshaa.ui.design.theme.DshTheme2

/**
 * M85-A：插件整壳的宿主屏。
 *
 * 宿主在这里只做三件事：构造 state、把用户动作 dispatch 给插件、渲染拿回来的 Surface 树。
 * 没有抽屉/输入栏/消息气泡——那些全部由插件用 18 个原语拼出来。
 */
@Composable
fun PluginShellScreen(vm: AppViewModel, target: PluginShellHost.Target) {
    val c = DshTheme2.colors
    val host = vm.shellHost
    // M85-B2-2：运行中耗时每秒刷新一次；只有 bind 里读 now() 的文本节点会重组。
    var clock by remember { mutableStateOf(System.currentTimeMillis()) }
    val data = remember(vm) { ViewModelSurfaceData(vm) { clock } }
    LaunchedEffect(vm.chat?.streaming) {
        while (vm.chat?.streaming == true) {
            clock = System.currentTimeMillis()
            delay(1_000)
        }
        clock = System.currentTimeMillis()
    }
    ShellAttachmentPickers(vm)

    LaunchedEffect(target) {
        if (vm.chat == null) vm.newChat()
        host.start(target)
    }
    // 只观察结构变化（消息 id/条数、会话列表、回合状态）；token 由 bind 直接读 state，不跨 JS。
    LaunchedEffect(target) {
        snapshotFlow { shellSignature(vm) }
            .distinctUntilChanged()
            .collect { host.requestState() }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(c.background)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        val tree = host.tree
        if (tree == null) {
            Column(
                Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                CircularProgressIndicator(color = c.brand)
                Spacer(Modifier.height(14.dp))
                Text(target.label, color = c.text, fontWeight = FontWeight.SemiBold)
                Text("插件界面由 ${target.pluginName} 提供", color = c.faint)
                host.error?.let { message ->
                    Spacer(Modifier.height(10.dp))
                    Text(message, color = c.danger, modifier = Modifier.padding(horizontal = 24.dp))
                    TextButton(onClick = { vm.setActiveShell(null) }) {
                        Text("回到原生界面", color = c.brand)
                    }
                }
            }
        } else {
            SurfaceTreeView(
                tree = tree,
                data = data,
                onAction = { action, row, index -> host.action(action, row, index) },
                onDraft = { text -> vm.setShellDraft(text) },
            )
        }
    }
}

/** Surface / WebView 共用系统选择器；结果固定写入发起命令时的会话。 */
@Composable
internal fun ShellAttachmentPickers(vm: AppViewModel) {
    val context = LocalContext.current
    // 不保存到 saved state：配置重建后没有原回调就忽略迟到结果，绝不回落到当前会话。
    var imageResult by remember { mutableStateOf<((Uri) -> Unit)?>(null) }
    var fileResult by remember { mutableStateOf<((Uri) -> Unit)?>(null) }
    var photoResult by remember { mutableStateOf<((File) -> Unit)?>(null) }
    var cameraTemp by remember { mutableStateOf<File?>(null) }
    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(9),
    ) { uris: List<Uri> ->
        val attach = imageResult
        imageResult = null
        if (attach != null) uris.forEach(attach)
    }
    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris: List<Uri> ->
        val attach = fileResult
        fileResult = null
        if (attach != null) uris.forEach(attach)
    }
    val takePicture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val file = cameraTemp
        val attach = photoResult
        cameraTemp = null
        photoResult = null
        if (ok && file != null && attach != null) attach(file) else file?.delete()
    }

    LaunchedEffect(vm.shellPickRequest) {
        val request = vm.shellPickRequest ?: return@LaunchedEffect
        val target = request.target
        if (target == null || target.readOnly) {
            vm.consumeShellPick(request.serial)
            return@LaunchedEffect
        }
        // 一个 picker 未返回时不覆盖任何已捕获的目标/相机文件。
        if (imageResult != null || fileResult != null || photoResult != null) {
            vm.consumeShellPick(request.serial)
            Toast.makeText(context, "请先完成已打开的附件选择", Toast.LENGTH_SHORT).show()
            return@LaunchedEffect
        }
        when (request.kind) {
            ShellPickKind.IMAGE -> {
                imageResult = { uri -> vm.attachImage(uri, target = target) }
                runCatching {
                    imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }.onFailure {
                    imageResult = null
                    Toast.makeText(context, "无法打开相册", Toast.LENGTH_SHORT).show()
                }
            }
            ShellPickKind.FILE -> {
                fileResult = { uri -> vm.attachFile(uri, target = target) }
                runCatching { filePicker.launch(arrayOf("*/*")) }.onFailure {
                    fileResult = null
                    Toast.makeText(context, "无法打开文件选择器", Toast.LENGTH_SHORT).show()
                }
            }
            ShellPickKind.CAMERA -> {
                val file = runCatching {
                    val dir = File(context.cacheDir, "camera").apply { mkdirs() }
                    File.createTempFile("chat-", ".jpg", dir)
                }.getOrNull()
                if (file == null) {
                    Toast.makeText(context, "没有可用的缓存目录", Toast.LENGTH_SHORT).show()
                } else {
                    val uri = runCatching {
                        FileProvider.getUriForFile(context, "${context.packageName}.files", file)
                    }.getOrNull()
                    if (uri == null) {
                        file.delete()
                        Toast.makeText(context, "无法创建拍照文件", Toast.LENGTH_SHORT).show()
                    } else {
                        cameraTemp = file
                        photoResult = { photo -> vm.attachPhoto(photo, target = target) }
                        runCatching { takePicture.launch(uri) }.onFailure {
                            file.delete()
                            cameraTemp = null
                            photoResult = null
                            Toast.makeText(context, "没有可用的相机应用", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        }
        vm.consumeShellPick(request.serial)
        android.util.Log.i("DshShell", "pick consumed=${request.kind} serial=${request.serial}")
    }
}

/** 与原生聊天页一致的内部消息来源：不显示正文（goal 通知/技能目录/时间上下文/压缩 checkpoint）。 */
private val INTERNAL_MESSAGE_KINDS = setOf(
    "goal", "tool-goal", "tool-jobs", "subagent-settled",
    "skill-catalog", "skill-invocation", "time-context", "compact-checkpoint",
)

/** 结构签名：只含 id/角色/流式标记，不含正文（正文变化不触发 shell.state）。 */
internal fun shellSignature(vm: AppViewModel): String {
    val chat = vm.chat
    val messages = chat?.messages
    return buildString {
        append(chat?.sessionId).append('|')
        append(chat?.title).append('|')
        append(chat?.streaming).append('|')
        append(chat?.stopping).append('|')
        append(chat?.statusText).append('|')
        append(chat?.error).append('|')
        // 队列交付/暂停/取消及模式变化都需要重算壳结构；正文仍由 bind 直读。
        append(chat?.queueNotice).append('|')
        chat?.queuedPrompts?.forEach { queued ->
            append(queued.id).append(':').append(queued.status).append(':')
                .append(queued.delivery).append(':').append(queued.pausedReason).append(',')
        }
        append('|').append(vm.settings.sendWhileRunningMode).append('|')
        // M85-B3：goal 控制改的是宿主 goal 状态（暂停/继续/清空），壳要重算按钮结构
        append(chat?.goal?.goal?.phase).append('|')
        append(chat?.goal?.activation).append('|')
        append(chat?.goal?.roundsStarted).append('|')
        // M85-B2：待发附件从 0→1 要重算树（附件栏显隐），附件增删也要刷新缩略图列表
        append(chat?.pendingImages?.size).append('|')
        append(chat?.pendingFiles?.size).append('|')
        append(messages?.size).append('|')
        messages?.forEach { message ->
            append(message.id).append(':').append(message.role).append(':').append(message.streaming).append(',')
        }
        append('|')
        vm.sessions.forEach { session ->
            append(session.id).append(':').append(session.title).append(':').append(session.updatedAt).append(',')
        }
    }
}

/** 把 ViewModel 的 Compose 状态暴露成 Surface 的只读绑定。 */
private class ViewModelSurfaceData(
    private val vm: AppViewModel,
    private val now: () -> Long = { System.currentTimeMillis() },
) : SurfaceDataSource {

    @Composable
    override fun rows(bind: String, parent: SurfaceRow?): List<SurfaceRow>? {
        val chat = vm.chat
        return when (bind) {
            "chat.messages", "chat.visibleMessages" -> {
                val all = chat?.messages.orEmpty().filter { message ->
                    if (bind == "chat.messages") {
                        true
                    } else {
                        // 与原生聊天页一致：工具消息单独渲染；goal/技能目录/时间上下文等内部注入不显示正文。
                        message.role != xin.dshaa.core.model.MessageRole.TOOL &&
                            message.sourceKind !in INTERNAL_MESSAGE_KINDS
                    }
                }
                val results = chat?.toolResults.orEmpty()
                all.mapIndexed { index, message ->
                    val calls = message.content
                        .filter { it.type == BlockType.TOOL_CALL }
                        .mapIndexed { callIndex, block ->
                            val result = block.toolCallId?.let { results[it] }
                            SurfaceRow.ToolCall(
                                id = block.toolCallId ?: "${message.id}:$callIndex",
                                index = callIndex,
                                name = block.name ?: "tool",
                                arguments = block.arguments,
                                result = result?.content
                                    ?.filter { it.type == BlockType.TEXT }
                                    ?.joinToString("") { it.text }
                                    .orEmpty(),
                                isError = result?.error != null,
                                running = message.streaming && result == null,
                                durationMs = block.toolCallId?.let { id -> chat?.toolDurations?.get(id) } ?: 0L,
                            )
                        }
                    val images = message.content
                        .filter { it.type == BlockType.IMAGE }
                        .mapNotNull { it.toImageRef() }
                        .mapIndexed { imageIndex, image ->
                            SurfaceRow.Attachment(
                                id = image.attachmentId,
                                index = imageIndex,
                                kind = "image",
                                name = image.name ?: "图片",
                                path = vm.imagePathFor(image.attachmentId).orEmpty(),
                                detail = image.mediaType,
                            )
                        }
                    // M85-B3：用户消息里的文件附件块（`[附件: …]` / `<attachment …/>`）折成卡片行，
                    // 正文从消息 text 里拿掉，壳用「点开看原文」展开（与 legacy QuotedAttachment 一致）。
                    val textParts = ArrayList<String>()
                    val files = ArrayList<SurfaceRow.Attachment>()
                    message.content.forEachIndexed { blockIndex, block ->
                        if (block.type != BlockType.TEXT) return@forEachIndexed
                        val parsed = parseMessageAttachment(block.text)
                        if (parsed == null) {
                            textParts += block.text
                        } else {
                            files += SurfaceRow.Attachment(
                                id = "${message.id}:file:$blockIndex",
                                index = images.size + files.size,
                                kind = "file",
                                name = parsed.name,
                                path = "",
                                detail = parsed.detail,
                                body = parsed.body.orEmpty(),
                            )
                        }
                    }
                    SurfaceRow.Message(
                        id = message.id,
                        index = index,
                        role = message.role.name.lowercase(),
                        text = textParts.joinToString(""),
                        thinking = message.content
                            .filter { it.type == BlockType.REASONING }
                            .joinToString("") { it.text },
                        streaming = message.streaming,
                        error = message.error,
                        toolCalls = calls,
                        attachments = images + files,
                    )
                }
            }

            "chat.queuedPrompts" -> chat?.queuedPrompts.orEmpty().mapIndexed { queueIndex, queued ->
                SurfaceRow.QueuedPrompt(
                    id = queued.id,
                    index = queueIndex,
                    text = queued.payload.text,
                    delivery = queued.delivery.name.lowercase(),
                    status = queued.status.name.lowercase(),
                    pausedReason = queued.pausedReason,
                    imageCount = queued.payload.images.size,
                    fileCount = queued.payload.textBlocks.count {
                        it.startsWith("[附件: ") || it.startsWith("<attachment ")
                    },
                    createdAt = queued.createdAt,
                    canCancel = queued.status == xin.dshaa.core.session.PromptQueueStatus.PENDING &&
                        chat?.readOnly != true,
                )
            }

            "chat.pendingImages" -> chat?.pendingImages.orEmpty().mapIndexed { imageIndex, pending ->
                SurfaceRow.Attachment(
                    id = pending.ref.attachmentId,
                    index = imageIndex,
                    kind = "image",
                    name = pending.ref.name ?: "图片 ${imageIndex + 1}",
                    path = pending.path,
                    detail = pending.ref.mediaType,
                )
            }

            "chat.pendingFiles" -> chat?.pendingFiles.orEmpty().mapIndexed { fileIndex, file ->
                SurfaceRow.Attachment(
                    id = "pending:${file.name}",
                    index = fileIndex,
                    kind = "file",
                    name = file.name,
                    path = file.path.orEmpty(),
                    detail = if (file.isReference) {
                        "${formatAttachmentBytes(file.size)} · 引用"
                    } else {
                        "${file.chars} 字"
                    },
                    body = if (file.isReference) "" else file.text,
                )
            }

            // M85-B3：当前会话的定时提醒（查看/删除，创建仍由模型的 schedule_* 工具负责）
            "chat.schedules" -> vm.currentSchedules().mapIndexed { scheduleIndex, task ->
                SurfaceRow.Schedule(
                    id = task.id,
                    index = scheduleIndex,
                    title = task.title.ifBlank { "提醒" },
                    label = if (task.active) {
                        "下次 " + xin.dshaa.core.tools.schedule.ScheduleTime.localText(task.nextRunAt, task.timing.timeZone)
                    } else {
                        "已结束"
                    },
                    active = task.active,
                )
            }

            // M85-B3：其它会话正在等审批/回答（M78 横幅）
            "chat.waiting" -> vm.waitingElsewhere().mapIndexed { waitingIndex, item ->
                SurfaceRow.Waiting(
                    id = item.sessionId,
                    index = waitingIndex,
                    title = item.title,
                    toolName = item.toolName,
                    summary = item.summary,
                    question = item.question,
                )
            }

            // M85-B2-3：模型提问（选中的状态存在壳的通用 view 里，宿主不解释 user 的选择）
            "chat.pendingQuestions.items" -> chat?.pendingQuestions.orEmpty().mapIndexed { questionIndex, question ->
                SurfaceRow.Question(
                    id = question.id,
                    index = questionIndex,
                    header = question.header.orEmpty(),
                    question = question.question,
                    multiSelect = question.multiSelect,
                    detail = question.detail.orEmpty(),
                    options = question.options.mapIndexed { optionIndex, option ->
                        SurfaceRow.Option(
                            id = "${question.id}\u001f${option.label}",
                            index = optionIndex,
                            questionId = question.id,
                            label = option.label,
                            description = option.description.orEmpty(),
                            selected = chat?.shellView?.get("answer:${question.id}:${option.label}") == true,
                        )
                    },
                )
            }

            "sessions" -> vm.sessions.mapIndexed { index, session ->
                SurfaceRow.Session(
                    id = session.id,
                    index = index,
                    title = session.title.ifBlank { "未命名会话" },
                    current = session.id == chat?.sessionId,
                    updatedAt = session.updatedAt,
                )
            }

            // 消息模板里再嵌一层工具调用列表（M85-B）
            "item.toolCalls" -> (parent as? SurfaceRow.Message)?.toolCalls

            // M85-B2：消息里的图片（历史消息）
            "item.attachments" -> (parent as? SurfaceRow.Message)?.attachments

            // M85-B2-3：一条问题的选项
            "item.options" -> (parent as? SurfaceRow.Question)?.options

            else -> null
        }
    }

    @Composable
    override fun text(bind: String, row: SurfaceRow?): String {
        val chat = vm.chat
        return when (bind) {
            "chat.draft" -> chat?.draft?.text.orEmpty()
            "chat.status" -> when {
                chat == null -> ""
                chat.stopping -> "停止中…"
                chat.streaming -> chat.statusText?.takeIf { it.isNotBlank() } ?: "正在回复…"
                else -> chat.statusText.orEmpty()
            }

            "chat.title" -> chat?.title.orEmpty()
            "chat.sessionId" -> chat?.sessionId.orEmpty()
            "chat.error" -> chat?.error.orEmpty()
            "chat.attachError" -> chat?.attachError.orEmpty()
            "chat.queueNotice" -> chat?.queueNotice.orEmpty()
            "chat.queueLabel" -> "待发送 ${chat?.queuedPrompts?.size ?: 0} 条"
            "chat.sendLabel" -> if (chat?.streaming == true) {
                if (vm.settings.sendWhileRunningMode == "steer") "插话发送" else "排队发送"
            } else "发送"
            "chat.usage" -> chat?.usageText.orEmpty()
            "chat.turnSummary" -> chat?.let { turnSummary(it) }.orEmpty()
            "chat.turn.step" -> chat?.statusText.orEmpty()
            "chat.context.label" -> chat?.let { contextLabel(it) }.orEmpty()
            "chat.context.ratio" -> chat?.let { contextRatio(it).toString() } ?: "0"
            "chat.context.prompt" -> chat?.lastPromptTokens?.toString().orEmpty()
            "chat.context.window" -> chat?.contextWindow?.toString().orEmpty()
            "chat.pendingApproval.toolName" -> chat?.pendingApproval?.toolName.orEmpty()
            "chat.pendingApproval.summary" -> chat?.pendingApproval?.summary.orEmpty()
            "chat.pendingApproval.arguments" -> chat?.pendingApproval?.arguments.orEmpty()
            "chat.pendingQuestions.header" -> chat?.pendingQuestions?.firstOrNull()?.let { question ->
                question.header?.takeIf { it.isNotBlank() } ?: question.question
            }.orEmpty()
            "chat.goal.objective" -> chat?.goal?.goal?.objective.orEmpty()
            "chat.goal.label" -> goalLabel(chat?.goal)
            "chat.goal.countdown" -> chat?.goal?.let {
                "第 ${it.roundsStarted}/${it.goal.maxGoalRounds} 轮"
            }.orEmpty()
            // M85-B3：其它会话在等你（M78 横幅）
            "chat.waiting.label" -> waitingLabel()
            "chat.schedules.count" -> vm.currentSchedules().let { tasks ->
                if (tasks.isEmpty()) "" else "${tasks.size} 条提醒"
            }
            "settings.model" -> vm.settings.model
            "settings.assistantName" -> vm.settings.assistantName
            "settings.sendWhileRunningMode" -> vm.settings.sendWhileRunningMode
            "settings.sendWhileRunningModeLabel" ->
                if (vm.settings.sendWhileRunningMode == "steer") "生成时：插话" else "生成时：排队"
            "theme.mode" -> vm.settings.themeMode
            "item.text" -> when (row) {
                is SurfaceRow.Message -> row.text
                is SurfaceRow.QueuedPrompt -> row.text
                else -> ""
            }
            "item.queuePreview" -> (row as? SurfaceRow.QueuedPrompt)?.let {
                it.text.ifBlank { if (it.fileCount > 0) "附件消息" else "图片消息" }
            }.orEmpty()
            "item.delivery" -> (row as? SurfaceRow.QueuedPrompt)?.delivery.orEmpty()
            "item.pausedReason" -> (row as? SurfaceRow.QueuedPrompt)?.pausedReason.orEmpty()
            "item.imageCount" -> ((row as? SurfaceRow.QueuedPrompt)?.imageCount ?: 0).toString()
            "item.fileCount" -> ((row as? SurfaceRow.QueuedPrompt)?.fileCount ?: 0).toString()
            "item.createdAt" -> (row as? SurfaceRow.QueuedPrompt)?.createdAt?.toString().orEmpty()
            "item.queueStatus" -> (row as? SurfaceRow.QueuedPrompt)?.let { queued ->
                buildString {
                    append(when {
                        queued.status == "claimed" -> "正在交付"
                        queued.pausedReason != null -> "已暂停：${queued.pausedReason}"
                        queued.delivery == "steer" -> "等待插话"
                        else -> "排队中"
                    })
                    if (queued.imageCount > 0) append(" · ${queued.imageCount} 张图片")
                    if (queued.fileCount > 0) append(" · ${queued.fileCount} 个文件")
                }
            }.orEmpty()
            "item.thinking" -> (row as? SurfaceRow.Message)?.thinking.orEmpty()
            "item.role" -> (row as? SurfaceRow.Message)?.role.orEmpty()
            "item.error" -> (row as? SurfaceRow.Message)?.error.orEmpty()
            "item.toolCount" -> ((row as? SurfaceRow.Message)?.toolCalls?.size ?: 0).toString()
            "item.title" -> when (row) {
                is SurfaceRow.Session -> row.title
                is SurfaceRow.Schedule -> row.title
                is SurfaceRow.Waiting -> row.title
                else -> ""
            }
            "item.summary" -> (row as? SurfaceRow.Waiting)?.summary.orEmpty()
            "item.id" -> row?.id.orEmpty()
            "item.name" -> when (row) {
                is SurfaceRow.ToolCall -> row.name
                is SurfaceRow.Attachment -> row.name
                else -> ""
            }
            "item.arguments" -> (row as? SurfaceRow.ToolCall)?.arguments.orEmpty()
            "item.result" -> (row as? SurfaceRow.ToolCall)?.result.orEmpty()
            "item.path" -> (row as? SurfaceRow.Attachment)?.path.orEmpty()
            "item.body" -> (row as? SurfaceRow.Attachment)?.body.orEmpty()
            "item.detail" -> when (row) {
                is SurfaceRow.Attachment -> row.detail
                is SurfaceRow.Question -> row.detail
                is SurfaceRow.Schedule -> row.label
                else -> ""
            }
            "item.kind" -> (row as? SurfaceRow.Attachment)?.kind.orEmpty()
            "item.question" -> (row as? SurfaceRow.Question)?.question.orEmpty()
            "item.header" -> (row as? SurfaceRow.Question)?.header.orEmpty()
            "item.label" -> (row as? SurfaceRow.Option)?.label.orEmpty()
            "item.description" -> (row as? SurfaceRow.Option)?.description.orEmpty()
            "item.duration" -> (row as? SurfaceRow.ToolCall)?.let { formatDuration(it.durationMs) }.orEmpty()
            "item.status" -> when (row) {
                is SurfaceRow.QueuedPrompt -> row.status
                is SurfaceRow.ToolCall -> when {
                    row.running -> "执行中"
                    row.isError -> "失败"
                    else -> "完成"
                }
                else -> ""
            }
            "item.thinkingChars" -> (row as? SurfaceRow.Message)?.thinking?.length?.let {
                if (it > 0) "$it 字" else ""
            }.orEmpty()
            "item.thinkingLabel" -> if (expanded("thinking", row)) "收起" else "展开"
            "item.toolLabel" -> if (expanded("tool", row)) "收起" else "展开"
            "item.fileLabel" -> if (expanded("file", row)) "收起原文" else "点开看原文"
            else -> ""
        }
    }

    @Composable
    override fun flag(bind: String, row: SurfaceRow?): Boolean {
        val negate = bind.startsWith("!")
        val path = bind.removePrefix("!")
        val chat = vm.chat
        val value = when (path) {
            "chat.streaming" -> chat?.streaming == true
            "chat.turn.active" -> chat?.streaming == true || chat?.stopping == true
            "chat.readOnly" -> chat?.readOnly == true
            "chat.stopping" -> chat?.stopping == true
            "chat.hasDraft" -> !chat?.draft?.text.isNullOrBlank() || chat?.let {
                it.pendingImages.isNotEmpty() || it.pendingFiles.isNotEmpty()
            } == true
            "chat.canSend" -> chat != null && !chat.readOnly && !chat.stopping &&
                (chat.draft.text.isNotBlank() || chat.pendingImages.isNotEmpty() || chat.pendingFiles.isNotEmpty())
            "chat.hasQueuedPrompts" -> !chat?.queuedPrompts.isNullOrEmpty()
            "chat.hasQueueNotice" -> !chat?.queueNotice.isNullOrBlank()
            "chat.showQueue" -> !chat?.queuedPrompts.isNullOrEmpty() || !chat?.queueNotice.isNullOrBlank()
            "chat.queuePaused" -> chat?.queuedPrompts?.any { it.pausedReason != null } == true
            "chat.canResumeQueuedPrompts" -> chat != null && !chat.readOnly && !chat.stopping &&
                chat.queuedPrompts.any { it.pausedReason != null }
            "chat.pendingApproval.active" -> chat?.pendingApproval != null
            "chat.pendingQuestions.active" -> !chat?.pendingQuestions.isNullOrEmpty()
            "chat.pendingQuestions.custom" -> chat?.pendingQuestions?.let { questions ->
                questions.size == 1 && questions.first().detail.isNullOrBlank()
            } == true
            "chat.hasAttachments" -> chat != null &&
                (chat.pendingImages.isNotEmpty() || chat.pendingFiles.isNotEmpty())
            "chat.hasPendingImages" -> !chat?.pendingImages.isNullOrEmpty()
            "chat.hasPendingFiles" -> !chat?.pendingFiles.isNullOrEmpty()
            "chat.hasAttachError" -> !chat?.attachError.isNullOrBlank()
            "chat.hasTurnSummary" -> chat?.let { turnSummary(it).isNotBlank() } == true
            "chat.hasUsage" -> !chat?.usageText.isNullOrBlank()
            "chat.goal.active" -> chat?.goal != null
            "chat.goal.armed" -> chat?.goal?.let {
                it.goal.phase == xin.dshaa.core.model.GoalPhase.ACTIVE &&
                    it.activation == xin.dshaa.core.model.GoalActivation.ARMED
            } == true
            "chat.goal.paused" -> chat?.goal?.goal?.phase == xin.dshaa.core.model.GoalPhase.PAUSED
            "chat.goal.blocked" -> chat?.goal?.goal?.phase == xin.dshaa.core.model.GoalPhase.BLOCKED
            "chat.goal.complete" -> chat?.goal?.goal?.phase == xin.dshaa.core.model.GoalPhase.COMPLETE
            "chat.goal.resumable" -> chat?.goal?.let { view ->
                val phase = view.goal.phase
                val canResume = phase == xin.dshaa.core.model.GoalPhase.PAUSED ||
                    phase == xin.dshaa.core.model.GoalPhase.BLOCKED ||
                    (phase == xin.dshaa.core.model.GoalPhase.ACTIVE &&
                        view.activation != xin.dshaa.core.model.GoalActivation.ARMED)
                canResume && view.roundsStarted < view.goal.maxGoalRounds
            } == true
            "chat.goal.exhausted" -> chat?.goal?.let {
                it.roundsStarted >= it.goal.maxGoalRounds
            } == true
            "chat.goal.editable" -> chat?.goal?.goal?.phase != xin.dshaa.core.model.GoalPhase.COMPLETE
            "chat.hasSchedules" -> vm.currentSchedules().isNotEmpty()
            "chat.hasWaiting" -> vm.waitingElsewhere().isNotEmpty()
            "settings.planActive" -> chat?.planActive == true
            "settings.sendWhileRunningSteer" -> vm.settings.sendWhileRunningMode == "steer"
            "settings.sendWhileRunningQueue" -> vm.settings.sendWhileRunningMode != "steer"
            "item.canCancel" -> (row as? SurfaceRow.QueuedPrompt)?.canCancel == true
            "item.paused" -> (row as? SurfaceRow.QueuedPrompt)?.pausedReason != null
            "item.hasImages" -> ((row as? SurfaceRow.QueuedPrompt)?.imageCount ?: 0) > 0
            "item.hasFiles" -> ((row as? SurfaceRow.QueuedPrompt)?.fileCount ?: 0) > 0
            "item.streaming" -> (row as? SurfaceRow.Message)?.streaming == true
            "item.current" -> (row as? SurfaceRow.Session)?.current == true
            "item.hasThinking" -> (row as? SurfaceRow.Message)?.thinking?.isNotBlank() == true
            "item.hasError" -> !(row as? SurfaceRow.Message)?.error.isNullOrBlank()
            "item.hasToolCalls" -> (row as? SurfaceRow.Message)?.toolCalls?.isNotEmpty() == true
            "item.hasAttachments" -> (row as? SurfaceRow.Message)?.attachments?.isNotEmpty() == true
            "item.running" -> (row as? SurfaceRow.ToolCall)?.running == true
            "item.isError" -> (row as? SurfaceRow.ToolCall)?.isError == true
            "item.done" -> (row as? SurfaceRow.ToolCall)?.let { !it.running && !it.isError } == true
            "item.thinkingExpanded" -> expanded("thinking", row)
            "item.toolExpanded" -> expanded("tool", row)
            "item.multiSelect" -> (row as? SurfaceRow.Question)?.multiSelect == true
            "item.hasDetail" -> !(row as? SurfaceRow.Question)?.detail.isNullOrBlank()
            "item.hasDescription" -> !(row as? SurfaceRow.Option)?.description.isNullOrBlank()
            "item.selected" -> (row as? SurfaceRow.Option)?.selected == true
            "item.isImage" -> (row as? SurfaceRow.Attachment)?.kind == "image"
            "item.isFile" -> (row as? SurfaceRow.Attachment)?.kind == "file"
            "item.hasBody" -> !(row as? SurfaceRow.Attachment)?.body.isNullOrBlank()
            "item.fileExpanded" -> expanded("file", row)
            "item.question" -> (row as? SurfaceRow.Waiting)?.question == true
            "item.active" -> (row as? SurfaceRow.Schedule)?.active == true
            else -> false
        }
        return if (negate) !value else value
    }

    /** 目标条文案（与 legacy GoalStrip 同一套阶段语义；M85-B3 起壳也能暂停/继续/编辑/清空）。 */
    private fun goalLabel(view: xin.dshaa.core.model.GoalView?): String {        if (view == null) return ""
        val phase = when (view.goal.phase) {
            xin.dshaa.core.model.GoalPhase.ACTIVE ->
                if (view.activation == xin.dshaa.core.model.GoalActivation.ARMED) "进行中" else "已停续轮"

            xin.dshaa.core.model.GoalPhase.PAUSED -> "已暂停"
            xin.dshaa.core.model.GoalPhase.BLOCKED -> "受阻"
            xin.dshaa.core.model.GoalPhase.COMPLETE -> "已完成"
            else -> view.goal.phase
        }
        return "$phase · 第 ${view.roundsStarted}/${view.goal.maxGoalRounds} 轮"
    }

    /** 其它会话在等你的横幅文案（与 legacy WaitingElsewhereBar 同语义，壳只显示第一条+总数）。 */
    private fun waitingLabel(): String {
        val items = vm.waitingElsewhere()
        if (items.isEmpty()) return ""
        val first = items.first()
        val base = if (first.question) {
            "「${first.title}」在等你回答"
        } else {
            "「${first.title}」正在等你点审批"
        }
        return if (items.size > 1) "$base（还有 ${items.size - 1} 个会话）" else base
    }

    /** 壳的通用展开状态（key 由壳决定：`thinking:<消息id>` / `tool:<toolCallId>`）。 */
    private fun expanded(kind: String, row: SurfaceRow?): Boolean {
        val id = row?.id ?: return false
        return vm.chat?.shellView?.get("$kind:$id") == true
    }

    /** 运行状态头：运行中每秒刷新耗时；结束后只显示上一轮用时。 */
    private fun turnSummary(chat: xin.dshaa.ui.shellapi.chat.ChatSessionState): String {
        if (chat.stopping) return "停止中…"
        val start = chat.turnStartedAt
        if (chat.streaming) {
            val elapsed = if (start > 0) ((now() - start) / 1000).coerceAtLeast(0) else 0
            val step = chat.statusText?.takeIf { it.isNotBlank() }
            return buildString {
                append("运行中")
                if (elapsed > 0) append(" · ").append(elapsed).append('s')
                if (step != null) append(" · ").append(step)
            }
        }
        if (chat.lastTurnDurationMs > 0) {
            return "上一轮 · 用时 ${formatElapsed(chat.lastTurnDurationMs)}"
        }
        if (start > 0) {
            // 老会话/进程重启后没有实际耗时，退回消息时间戳估算（与 legacy 一致）
            val end = chat.messages.lastOrNull()?.createdAt ?: 0L
            if (end > start) return "上一轮 · 用时 ${formatElapsed(end - start)}"
        }
        return ""
    }

    private fun formatElapsed(ms: Long): String {
        val seconds = (ms / 1000).coerceAtLeast(0)
        return if (seconds < 60) "${seconds}s" else "${seconds / 60}m${seconds % 60}s"
    }

    private fun formatDuration(ms: Long): String = when {
        ms <= 0 -> ""
        ms < 1_000 -> "${ms}ms"
        else -> String.format(java.util.Locale.US, "%.1fs", ms / 1000.0)
    }

    private fun contextRatio(state: xin.dshaa.ui.shellapi.chat.ChatSessionState): Float {
        val window = state.contextWindow
        if (window <= 0) return 0f
        return (state.lastPromptTokens.toFloat() / window.toFloat()).coerceIn(0f, 1f)
    }

    private fun contextLabel(state: xin.dshaa.ui.shellapi.chat.ChatSessionState): String {
        val window = state.contextWindow
        if (window <= 0) return ""
        val percent = (contextRatio(state) * 100).toInt()
        return "$percent% · ${state.lastPromptTokens}/${window}"
    }
}

package xin.dshaa.nativeapp.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Api
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import xin.dshaa.nativeapp.data.SettingsStore
import xin.dshaa.ui.design.component.CollapsibleSettingsCard
import xin.dshaa.ui.design.component.MiniChip
import xin.dshaa.ui.design.component.SettingsCard
import xin.dshaa.ui.design.component.SettingsInfoBanner
import xin.dshaa.ui.design.component.SettingsSectionHeader
import xin.dshaa.ui.design.component.SettingsSelectorRow
import xin.dshaa.ui.design.component.SettingsTextField
import xin.dshaa.ui.design.component.SettingsSwitchRow
import xin.dshaa.ui.design.theme.DshTheme2

/**
 * 模型与参数配置（独立页）——照 Operit `ModelConfigScreen` 的页面拆分与字段顺序：
 *   选择模型配置 → API设置（提供商/端点/密钥/模型名 + 能力开关）→ 上下文/思考 → 模型参数 → 高级设置。
 * 本轮把 DSH 真实支持的字段都接上（provider/baseUrl/key/model/maxTokens/temperature/effort），
 * 未实现的开关置灰并标「待做」，不画假开关。
 */
@Composable
fun ModelConfigScreen(
    settings: SettingsStore,
    onBack: () -> Unit,
    onTestConnection: suspend () -> String,
    onFetchModels: suspend () -> Result<List<String>> = { Result.failure(IllegalStateException("未接入")) },
) {
    val c = DshTheme2.colors
    val scope = rememberCoroutineScope()
    var showProviderDialog by remember { mutableStateOf(false) }
    var showKey by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }
    var testOk by remember { mutableStateOf(false) }
    var paramsExpanded by remember { mutableStateOf(true) }
    var advancedExpanded by remember { mutableStateOf(false) }
    // 群反馈（beca_se）：思考强度点一下转一档太麻烦 → 改成弹层四选一（docs/48）
    var effortPicker by remember { mutableStateOf(false) }
    // 拉取模型列表（2026-11-09 用户要求）：fetching 表示请求中，models 非 null 时弹选择层
    var fetchingModels by remember { mutableStateOf(false) }
    var fetchedModels by remember { mutableStateOf<List<String>?>(null) }
    var fetchError by remember { mutableStateOf<String?>(null) }

    val provider = SettingsStore.providerOf(settings.providerId)
    val focusManager = LocalFocusManager.current

    // 用户反馈：以前每次按键都直接写 settings 并 clamp，导致模型名删空也会立刻变回
    // deepseek-flash。改成编辑期只改本地文本，失焦/完成时再校验落盘。
    // 2026-11-09：删掉「最大输出 tokens」设置（用户要求；请求固定用默认 8192）。
    // 上下文窗口（M44）：空 = 0 = 未知（自动压缩关闭）；按模型记忆
    var contextWindowText by remember {
        mutableStateOf(settings.contextWindow.takeIf { it > 0 }?.toString().orEmpty())
    }
    var contextWindowFocused by remember { mutableStateOf(false) }
    var temperatureText by remember { mutableStateOf(settings.temperature?.toString().orEmpty()) }
    var temperatureFocused by remember { mutableStateOf(false) }
    var modelText by remember { mutableStateOf(settings.model) }
    var modelFocused by remember { mutableStateOf(false) }

    LaunchedEffect(settings.contextWindow, contextWindowFocused) {
        if (!contextWindowFocused) {
            contextWindowText = settings.contextWindow.takeIf { it > 0 }?.toString().orEmpty()
        }
    }
    LaunchedEffect(settings.temperature, temperatureFocused) {
        if (!temperatureFocused) temperatureText = settings.temperature?.toString().orEmpty()
    }
    LaunchedEffect(settings.model, modelFocused) {
        if (!modelFocused) modelText = settings.model
    }

    // 真机回归补充：只靠「失焦」落盘还不够——键盘没有 Done 动作，输入完直接返回也会丢编辑。
    // 统一成三处落盘：键盘完成（ImeAction.Done）、失焦、离开页面（onDispose 兜底）。
    fun commitModelText() {
        settings.setModel(modelText)
        modelText = settings.model
    }

    fun commitContextWindowText() {
        settings.setContextWindow(contextWindowText.toIntOrNull() ?: 0)
        contextWindowText = settings.contextWindow.takeIf { it > 0 }?.toString().orEmpty()
    }

    fun commitTemperatureText() {
        if (temperatureText.isBlank()) settings.setTemperature(null)
        else temperatureText.toDoubleOrNull()?.let { settings.setTemperature(it) }
        temperatureText = settings.temperature?.toString().orEmpty()
    }

    DisposableEffect(Unit) {
        onDispose {
            settings.setModel(modelText)
            settings.setContextWindow(contextWindowText.toIntOrNull() ?: 0)
            if (temperatureText.isBlank()) settings.setTemperature(null)
            else temperatureText.toDoubleOrNull()?.let { settings.setTemperature(it) }
        }
    }

    Column(Modifier.fillMaxSize().background(c.background)) {
        Row(
            Modifier.fillMaxWidth().background(c.surface).statusBarsPadding().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(40.dp).clip(CircleShape).clickable { onBack() }, contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.ArrowBack, "返回", tint = c.text, modifier = Modifier.size(23.dp))
            }
            Spacer(Modifier.width(4.dp))
            Text("模型与参数配置", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, color = c.text)
        }

        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp)) {

            // ---------- 选择模型配置 ----------
            SettingsCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "选择模型配置",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = c.text,
                        modifier = Modifier.weight(1f),
                    )
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = c.brand.copy(alpha = 0.12f),
                        border = androidx.compose.foundation.BorderStroke(1.dp, c.brand.copy(alpha = 0.4f)),
                    ) {
                        Text(
                            "默认配置",
                            style = MaterialTheme.typography.labelMedium,
                            color = c.brandBright,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SettingsConnectionButton(
                        testing = testing,
                        onClick = {
                            if (testing) return@SettingsConnectionButton
                            testing = true
                            testResult = null
                            scope.launch {
                                val result = runCatching { onTestConnection() }.getOrElse { "连接失败：${it.message}" }
                                testOk = result.startsWith("连接成功")
                                testResult = result
                                testing = false
                            }
                        },
                    )
                    Spacer(Modifier.width(12.dp))
                    MiniChip("API 密钥通道")
                }
                testResult?.let { text ->
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = (if (testOk) c.successSoft else c.dangerSoft).copy(alpha = if (c.isDark) 0.6f else 1f),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                if (testOk) Icons.Rounded.Check else Icons.Rounded.ErrorOutline,
                                null,
                                tint = if (testOk) c.success else c.danger,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(text, style = MaterialTheme.typography.bodySmall, color = c.text)
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // ---------- API 设置 ----------
            SettingsSectionHeader(icon = Icons.Rounded.Api, title = "API设置")
            SettingsCard {
                SettingsSelectorRow(
                    title = "API提供商",
                    subtitle = "点击选择API提供商",
                    value = provider.name,
                    onClick = { showProviderDialog = true },
                )
                if (provider.id == SettingsStore.PROVIDER_CUSTOM) {
                    SettingsInfoBanner("自定义端点：任意 Anthropic Messages 兼容服务，路径按 {base}/v1/messages 拼。")
                }
                SettingsTextField(
                    title = "API端点",
                    subtitle = "例如: https://api.deepseek.com/anthropic",
                    value = settings.baseUrl,
                    onValueChange = { settings.setBaseUrl(it.replace("\n", "").replace(" ", "")) },
                    placeholder = SettingsStore.defaultBaseUrl(provider.id),
                )
                Text(
                    "实际请求地址：${requestUrl(settings.baseUrl)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = c.faint,
                )
                SettingsTextField(
                    title = "API密钥",
                    subtitle = if (settings.apiKey.isBlank()) "未设置密钥，可直接输入" else "已设置，点击右侧眼睛显示",
                    value = settings.apiKey,
                    onValueChange = { settings.setApiKey(it.replace("\n", "").replace(" ", "")) },
                    placeholder = "sk-…",
                    visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingContent = {
                        Box(Modifier.size(40.dp).clip(CircleShape).clickable { showKey = !showKey }, contentAlignment = Alignment.Center) {
                            Text(if (showKey) "隐藏" else "显示", style = MaterialTheme.typography.labelSmall, color = c.brandBright)
                        }
                    },
                )
                SettingsTextField(
                    title = "模型名称",
                    subtitle = "例如: deepseek-flash / deepseek-v4-pro",
                    value = modelText,
                    onValueChange = { modelText = it.replace("\n", "").replace(" ", "") },
                    placeholder = SettingsStore.DEFAULT_MODEL,
                    onFocusChanged = { focused ->
                        modelFocused = focused
                        if (!focused) commitModelText()
                    },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(
                        enabled = !fetchingModels,
                        onClick = {
                            fetchingModels = true
                            fetchError = null
                            scope.launch {
                                val result = onFetchModels()
                                fetchingModels = false
                                result.onSuccess { fetchedModels = it }
                                    .onFailure { fetchError = it.message ?: "拉取失败" }
                            }
                        },
                    ) {
                        Text(
                            if (fetchingModels) "拉取中…" else "从供应商拉取模型列表",
                            color = c.brandBright,
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                    if (fetchingModels) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp,
                            color = c.brandBright,
                        )
                    }
                }
                fetchError?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall, color = c.danger)
                }
                SettingsSwitchRow(
                    title = "模型支持识图",
                    subtitle = if (settings.visionEnabled) {
                        "图片直接发给模型（read_image / 聊天发图都走这条）"
                    } else {
                        "关闭后 read_image 与聊天发图都会被拒；打开由你自己声明模型能力"
                    },
                    checked = settings.visionEnabled,
                    onCheckedChange = { settings.setVisionEnabled(it) },
                )
                SettingsSwitchRow(
                    title = "启用 DeepSeek 网络搜索",
                    subtitle = if (settings.webSearchEnabled) {
                        "模型可调用 web_search（服务端搜索，一次搜索一个模型轮次）"
                    } else {
                        "已隐藏 web_search：请求里不给这个工具，模型不会再联网"
                    },
                    checked = settings.webSearchEnabled,
                    onCheckedChange = { settings.setWebSearchEnabled(it) },
                )
                SettingsSwitchRow(
                    title = "模型支持ToolCall",
                    subtitle = "DSH 原生工具调用，始终开启",
                    checked = true,
                    onCheckedChange = {},
                    enabled = false,
                )
            }

            Spacer(Modifier.height(16.dp))

            // ---------- 模型参数 ----------
            CollapsibleSettingsCard(
                icon = Icons.Rounded.Tune,
                title = "模型参数设置",
                subtitle = "生成参数",
                expanded = paramsExpanded,
                onToggle = { paramsExpanded = !paramsExpanded },
            ) {
                SettingsTextField(
                    title = "上下文窗口 tokens",
                    subtitle = if (settings.contextWindow > 0) {
                        "自动压缩按它算触发线（窗口 × 0.8）；deepseek-flash 默认 1048576"
                    } else {
                        "留空/0 = 未知：不自动压缩，仍可手动 /compact"
                    },
                    value = contextWindowText,
                    onValueChange = { v -> contextWindowText = v.filter { it.isDigit() }.take(8) },
                    onFocusChanged = { focused ->
                        contextWindowFocused = focused
                        if (!focused) commitContextWindowText()
                    },
                    placeholder = "例如 1048576",
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                )
                SettingsTextField(
                    title = "温度 temperature",
                    subtitle = if (settings.temperature == null) "未设置（服务端默认）" else "当前 ${settings.temperature}",
                    value = temperatureText,
                    onValueChange = { v -> temperatureText = v.filter { it.isDigit() || it == '.' } },
                    onFocusChanged = { focused ->
                        temperatureFocused = focused
                        if (!focused) commitTemperatureText()
                    },
                    placeholder = "留空 = 默认",
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                )
                SettingsSelectorRow(
                    title = "思考强度",
                    subtitle = when (settings.reasoningEffort) {
                        "off" -> "关闭：thinking=disabled、不发 output_config（回答快、不思考）"
                        else -> "reasoning effort（output_config.effort）"
                    },
                    value = reasoningEffortLabel(settings.reasoningEffort),
                    onClick = { effortPicker = true },
                )
                if (effortPicker) {
                    AlertDialog(
                        onDismissRequest = { effortPicker = false },
                        containerColor = c.surface,
                        title = { Text("思考强度", fontWeight = FontWeight.SemiBold) },
                        text = {
                            Column {
                                REASONING_CHOICES.forEach { (id, name, desc) ->
                                    val selected = settings.reasoningEffort == id
                                    Row(
                                        Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(10.dp))
                                            .clickable {
                                                settings.setReasoningEffort(id)
                                                effortPicker = false
                                            }
                                            .padding(vertical = 10.dp, horizontal = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Column(Modifier.weight(1f)) {
                                            Text(
                                                name,
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                                color = c.text,
                                            )
                                            Spacer(Modifier.height(2.dp))
                                            Text(desc, style = MaterialTheme.typography.bodySmall, color = c.sub)
                                        }
                                        if (selected) {
                                            Icon(Icons.Rounded.Check, null, tint = c.brandBright, modifier = Modifier.size(18.dp))
                                        }
                                    }
                                }
                            }
                        },
                        confirmButton = {
                            TextButton(onClick = { effortPicker = false }) { Text("关闭") }
                        },
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            // ---------- 高级设置 ----------
            CollapsibleSettingsCard(
                icon = Icons.Rounded.Dns,
                title = "高级设置",
                subtitle = "请求与通道",
                expanded = advancedExpanded,
                onToggle = { advancedExpanded = !advancedExpanded },
            ) {
                SettingsInfoBanner(
                    if (settings.protocol == "openai") {
                        "OpenAI Chat Completions：POST {base}/v1/chat/completions，Authorization: Bearer，流式 SSE。"
                    } else {
                        "Anthropic Messages：POST {base}/v1/messages，x-api-key，流式 SSE。"
                    },
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    MiniChip(if (settings.protocol == "openai") "chat/completions" else "anthropic-version: 2023-06-01")
                    Spacer(Modifier.width(8.dp))
                    MiniChip("stream: true")
                }
                TextButton(onClick = { settings.rememberEndpointForCurrentProvider() }) {
                    Text("记住当前端点", color = c.brandBright)
                }
            }
            Spacer(Modifier.height(28.dp))
        }
    }

    if (showProviderDialog) {
        AlertDialog(
            onDismissRequest = { showProviderDialog = false },
            title = { Text("API提供商", fontWeight = FontWeight.Bold) },
            text = {
                Column(
                    Modifier
                        .heightIn(max = 460.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    SettingsStore.providers.forEach { p ->
                        val selected = p.id == settings.providerId
                        Surface(
                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable {
                                settings.setProviderId(p.id)
                                showProviderDialog = false
                            },
                            shape = RoundedCornerShape(10.dp),
                            color = if (selected) c.brand.copy(alpha = 0.12f) else c.surfaceAlt.copy(alpha = 0.6f),
                        ) {
                            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        p.name,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Medium,
                                        color = if (selected) c.brandBright else c.text,
                                    )
                                    Text(p.subtitle, style = MaterialTheme.typography.bodySmall, color = c.sub)
                                }
                                if (selected) Icon(Icons.Rounded.Check, null, tint = c.brand, modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showProviderDialog = false }) { Text("关闭") } },
            containerColor = c.surface,
        )
    }

    fetchedModels?.let { models ->
        // M74：拉取后**多选**（用户 23:20）。勾选的进「已启用模型」，输入条模型浮层只在这些模型里切换；
        // 以前是点一下就改当前模型、其余不落库，导致列表里混进历史用过但没勾选的模型。
        var selection by remember(models) {
            mutableStateOf(
                (settings.enabledModels.filter { it in models } + settings.model)
                    .filter { it in models }
                    .toSet()
                    .ifEmpty { setOf(settings.model) },
            )
        }
        AlertDialog(
            onDismissRequest = { fetchedModels = null },
            containerColor = c.surface,
            title = {
                Text(
                    "选择要启用的模型（已选 ${selection.size}/${models.size}）",
                    fontWeight = FontWeight.SemiBold,
                )
            },
            text = {
                Column(
                    Modifier
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    Text(
                        "勾选的模型会出现在输入条的模型浮层里；没勾的不会出现。当前模型：${settings.model}（一律保留）。",
                        style = MaterialTheme.typography.labelSmall,
                        color = c.faint,
                    )
                    Spacer(Modifier.height(6.dp))
                    models.forEach { name ->
                        val checked = name in selection
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable {
                                    selection = if (checked) selection - name else selection + name
                                }
                                .padding(vertical = 4.dp, horizontal = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked = checked,
                                onCheckedChange = { next ->
                                    selection = if (next) selection + name else selection - name
                                },
                            )
                            Text(
                                name,
                                style = MaterialTheme.typography.bodyMedium,
                                color = c.text,
                                modifier = Modifier.weight(1f),
                            )
                            if (name == settings.model) {
                                MiniChip("当前")
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    settings.setEnabledModels(selection.toList())
                    fetchedModels = null
                }) { Text("保存（${selection.size}）") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { selection = models.toSet() }) { Text("全选") }
                    TextButton(onClick = { fetchedModels = null }) { Text("取消") }
                }
            },
        )
    }
}

@Composable
private fun SettingsConnectionButton(testing: Boolean, onClick: () -> Unit) {
    val c = DshTheme2.colors
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = c.brand.copy(alpha = 0.14f),
        modifier = Modifier.clickable { onClick() },
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (testing) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = c.brandBright)
            } else {
                Icon(Icons.Rounded.Dns, null, tint = c.brandBright, modifier = Modifier.size(16.dp))
            }
            Spacer(Modifier.width(6.dp))
            Text(
                if (testing) "取消" else "测试连接",
                style = MaterialTheme.typography.labelMedium,
                color = c.brandBright,
            )
        }
    }
}

/** 设置行里显示的思考强度文案（上游名：Off / Low / High / Max）。 */
private fun reasoningEffortLabel(value: String): String = when (value) {
    "off" -> "关闭"
    "low" -> "低"
    "max" -> "最高"
    else -> "高（默认）"
}

/**
 * 思考强度四选一（上游 dsh-llm-deepseek 的 REASONING_EFFORTS 原文，说明也照抄）：
 * id / 显示名 / 说明。默认 high。
 */
private val REASONING_CHOICES = listOf(
    Triple("off", "关闭（Off）", "简单任务不需要思考：不发 output_config，最快"),
    Triple("low", "低（Low）", "日常任务 / 对延迟敏感时用"),
    Triple("high", "高（High，默认）", "大多数任务的默认平衡档"),
    Triple("max", "最高（Max）", "最难、质量优先的任务才用"),
)

private fun requestUrl(base: String): String {
    val b = base.trim().trimEnd('/')
    return when {
        b.endsWith("/v1/messages") -> b
        b.endsWith("/v1") -> "$b/messages"
        else -> "$b/v1/messages"
    }
}

package xin.dshaa.nativeapp.data

import android.content.SharedPreferences
import androidx.compose.runtime.mutableStateOf

/**
 * 本地设置（API Key 先用私有 SharedPreferences；M3 换 Keystore 加密并兼容读取）。
 *
 * 2026-11-07 按 Operit「模型与参数配置」页重排：
 * - [providerId] 是唯一的提供商标识（deepseek-account / deepseek-api / anthropic / custom），
 *   [provider] 保留旧语义（account / api-key）给已有代码与调试通道用。
 * - 端点、密钥、模型名按 provider 各自记；切 provider 时自动回到该 provider 上次的值。
 * - 头像存图片绝对路径（复制到 filesDir/avatars 后），不再是两个字母。
 */
class SettingsStore(private val sp: SharedPreferences) {

    // ---- 文本设置（Compose 可观察） ----
    private val _apiKey = mutableStateOf(sp.getString(KEY_API_KEY, "").orEmpty())
    private val _baseUrl = mutableStateOf(sp.getString(KEY_BASE_URL, DEFAULT_BASE_URL)!!)
    private val _model = mutableStateOf(sp.getString(KEY_MODEL, DEFAULT_MODEL)!!)
    private val _accountToken = mutableStateOf(sp.getString(KEY_ACCOUNT_TOKEN, "").orEmpty())
    private val _providerId = mutableStateOf(normalizeProviderId(sp.getString(KEY_PROVIDER_ID, null), sp.getString(KEY_PROVIDER, null)))
    private val _approvalMode = mutableStateOf(sp.getString(KEY_APPROVAL, "ask")!!)
    // 旧默认值 "DSH" 是历史占位（用户没改过名字时用它）：升级后统一显示新名字「鲸歌」
    private val _assistantName = mutableStateOf(
        sp.getString(KEY_ASSISTANT_NAME, "鲸歌")!!.let { if (it == "DSH") "鲸歌" else it },
    )
    private val _assistantDesc = mutableStateOf(sp.getString(KEY_ASSISTANT_DESC, "").orEmpty())
    private val _assistantAvatar = mutableStateOf(sp.getString(KEY_ASSISTANT_AVATAR, "").orEmpty())

    // ---- 模型参数 ----
    private val _maxTokens = mutableStateOf(sp.getInt(KEY_MAX_TOKENS, DEFAULT_MAX_TOKENS))
    private val _temperature = mutableStateOf(sp.getFloat(KEY_TEMPERATURE, -1f).takeIf { it >= 0f }?.toDouble())
    // 上下文窗口（M44）：按模型记忆；0 = 未知（自动压缩关闭，手工 /compact 不受影响）
    private val _contextWindow = mutableStateOf(
        sp.getInt(contextWindowKey(_model.value), defaultContextWindow(_model.value)),
    )
    // off/low/high/max 是上游 dsh-llm-deepseek 认的全部四个值；旧值/脏值一律回落 high。
    private val _reasoningEffort = mutableStateOf(
        sp.getString(KEY_REASONING_EFFORT, "high")!!.takeIf { it in REASONING_EFFORTS } ?: "high",
    )

    // ---- 模型能力（按模型名记忆，见 IMAGE_CAPABLE_MODEL） ----
    private val _visionEnabled = mutableStateOf(sp.getBoolean(visionKey(_model.value), defaultVision(_model.value)))
    private val _webSearchEnabled = mutableStateOf(sp.getBoolean(webSearchKey(_model.value), true))

    /**
     * 最近用过的模型（输入条下拉直接切）。
     *
     * 不内置「猜」的模型名：只记用户在模型配置页真正保存过的名字，避免给一个不存在的 model id。
     * 首次为空时放当前模型，保证下拉至少有一项。
     */
    private val _recentModels = mutableStateOf(
        sp.getString(KEY_RECENT_MODELS, null)
            ?.split('\n')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.take(6)
            .orEmpty()
            .ifEmpty { listOf(_model.value) },
    )

    // ---- 显示与行为 ----
    private val _expandThinking = mutableStateOf(sp.getBoolean(KEY_EXPAND_THINKING, true))
    // 时间上下文（M42，上游 dsh-time-context 默认开）：每个 step 起点给模型带上当前时间与间隔
    private val _timeContext = mutableStateOf(sp.getBoolean(KEY_TIME_CONTEXT, true))
    private val _themeMode = mutableStateOf(sp.getString(KEY_THEME_MODE, "system")!!)
    private val _accentId = mutableStateOf(sp.getString(KEY_ACCENT_ID, "blue")!!)

    val apiKey: String get() = _apiKey.value
    val baseUrl: String get() = _baseUrl.value
    val model: String get() = _model.value
    val accountToken: String get() = _accountToken.value
    val providerId: String get() = _providerId.value
    /** 旧字段：account / api-key。由 [providerId] 推导，别直接写。 */
    val provider: String get() = if (_providerId.value == PROVIDER_DEEPSEEK_ACCOUNT) "account" else "api-key"
    val approvalMode: String get() = _approvalMode.value
    val assistantName: String get() = _assistantName.value
    val assistantDesc: String get() = _assistantDesc.value
    val assistantAvatar: String get() = _assistantAvatar.value
    val maxTokens: Int get() = _maxTokens.value
    val temperature: Double? get() = _temperature.value
    val reasoningEffort: String get() = _reasoningEffort.value
    /** 当前模型的上下文窗口（tokens，M44）；0 = 未知 → 自动压缩关闭。 */
    val contextWindow: Int get() = _contextWindow.value

    /** 最近用过的模型（新的在前，最多 6 个）。 */
    val recentModels: List<String> get() = _recentModels.value

    /** 当前模型是否声明了识图（模型配置页可改；deepseek-flash 默认开）。 */
    val visionEnabled: Boolean get() = _visionEnabled.value

    /** 当前模型是否允许联网搜索（模型配置页可改；默认开）。 */
    val webSearchEnabled: Boolean get() = _webSearchEnabled.value
    val expandThinking: Boolean get() = _expandThinking.value
    /** 时间上下文（M42）：关掉后每个 step 的请求体不再带时间注入，默认开。 */
    val timeContext: Boolean get() = _timeContext.value
    /** system / light / dark */
    val themeMode: String get() = _themeMode.value
    /** 强调色预设 id（DshAccents） */
    val accentId: String get() = _accentId.value

    fun setApiKey(v: String) {
        _apiKey.value = v.trim()
        sp.edit().putString(KEY_API_KEY, v.trim()).apply()
    }

    fun setBaseUrl(v: String) {
        _baseUrl.value = v.trim().ifBlank { defaultBaseUrl(_providerId.value) }
        sp.edit().putString(KEY_BASE_URL, _baseUrl.value).apply()
    }

    fun setModel(v: String) {
        _model.value = v.trim().ifBlank { DEFAULT_MODEL }
        sp.edit().putString(KEY_MODEL, _model.value).apply()
        // 能力开关按模型各自记忆：换模型后重读，界面不会顶着上一个模型的开关
        _visionEnabled.value = sp.getBoolean(visionKey(_model.value), defaultVision(_model.value))
        _webSearchEnabled.value = sp.getBoolean(webSearchKey(_model.value), true)
        _contextWindow.value = sp.getInt(contextWindowKey(_model.value), defaultContextWindow(_model.value))
        rememberModel(_model.value)
    }

    /** 记录最近模型（输入条下拉用；只记录用户真实保存过的名字）。 */
    private fun rememberModel(model: String) {
        val next = (listOf(model) + _recentModels.value.filterNot { it == model }).take(6)
        if (next == _recentModels.value) return
        _recentModels.value = next
        sp.edit().putString(KEY_RECENT_MODELS, next.joinToString("\n")).apply()
    }

    fun setVisionEnabled(v: Boolean) {
        _visionEnabled.value = v
        sp.edit().putBoolean(visionKey(_model.value), v).apply()
    }

    fun setWebSearchEnabled(v: Boolean) {
        _webSearchEnabled.value = v
        sp.edit().putBoolean(webSearchKey(_model.value), v).apply()
    }

    fun setAccountToken(v: String) {
        _accountToken.value = v.trim()
        sp.edit().putString(KEY_ACCOUNT_TOKEN, v.trim()).apply()
    }

    fun setProviderId(id: String) {
        val normalized = normalizeProviderId(id, null)
        _providerId.value = normalized
        sp.edit().putString(KEY_PROVIDER_ID, normalized).apply()
        // 旧键同步，调试通道与历史代码仍读得到
        sp.edit().putString(KEY_PROVIDER, if (normalized == PROVIDER_DEEPSEEK_ACCOUNT) "account" else "api-key").apply()
        // 切 provider 时端点跟到该 provider 的默认值；密钥/模型保留各自记忆
        val remembered = sp.getString(endpointKey(normalized), null)
        _baseUrl.value = remembered?.takeIf { it.isNotBlank() } ?: defaultBaseUrl(normalized)
        sp.edit().putString(KEY_BASE_URL, _baseUrl.value).apply()
    }

    fun setApprovalMode(v: String) {
        _approvalMode.value = v
        sp.edit().putString(KEY_APPROVAL, v).apply()
    }

    fun setAssistantName(v: String) {
        _assistantName.value = v.trim().ifBlank { "鲸歌" }
        sp.edit().putString(KEY_ASSISTANT_NAME, _assistantName.value).apply()
    }

    fun setAssistantDesc(v: String) {
        _assistantDesc.value = v.trim()
        sp.edit().putString(KEY_ASSISTANT_DESC, _assistantDesc.value).apply()
    }

    fun setAssistantAvatar(v: String) {
        _assistantAvatar.value = v.trim()
        sp.edit().putString(KEY_ASSISTANT_AVATAR, _assistantAvatar.value).apply()
    }

    fun setMaxTokens(v: Int) {
        _maxTokens.value = v.coerceIn(1024, 1_000_000)
        sp.edit().putInt(KEY_MAX_TOKENS, _maxTokens.value).apply()
    }

    /** 上下文窗口（M44）：0 = 未知/关闭自动压缩。按当前模型记忆。 */
    fun setContextWindow(v: Int) {
        _contextWindow.value = v.coerceIn(0, 10_000_000)
        sp.edit().putInt(contextWindowKey(_model.value), _contextWindow.value).apply()
    }

    fun setTemperature(v: Double?) {
        _temperature.value = v
        if (v == null) sp.edit().remove(KEY_TEMPERATURE).apply()
        else sp.edit().putFloat(KEY_TEMPERATURE, v.toFloat()).apply()
    }

    fun setReasoningEffort(v: String) {
        _reasoningEffort.value = v
        sp.edit().putString(KEY_REASONING_EFFORT, v).apply()
    }

    fun setExpandThinking(v: Boolean) {
        _expandThinking.value = v
        sp.edit().putBoolean(KEY_EXPAND_THINKING, v).apply()
    }

    fun setTimeContext(v: Boolean) {
        _timeContext.value = v
        sp.edit().putBoolean(KEY_TIME_CONTEXT, v).apply()
    }

    fun setAccentId(v: String) {
        _accentId.value = v
        sp.edit().putString(KEY_ACCENT_ID, v).apply()
    }

    fun setThemeMode(v: String) {
        _themeMode.value = v
        sp.edit().putString(KEY_THEME_MODE, v).apply()
    }

    /** 记住当前 provider 的端点，切走再切回来不丢。 */
    fun rememberEndpointForCurrentProvider() {
        sp.edit().putString(endpointKey(_providerId.value), _baseUrl.value).apply()
    }

    // ---- 旧版数据导入（调试通道用；正常路径走 SAF，不落设置） ----
    var debugImportPath: String?
        get() = sp.getString(KEY_DEBUG_IMPORT_ZIP, null)?.takeIf { it.isNotBlank() }
        set(value) {
            if (value == null) sp.edit().remove(KEY_DEBUG_IMPORT_ZIP).apply()
            else sp.edit().putString(KEY_DEBUG_IMPORT_ZIP, value).apply()
        }

    /** 调试通道：启动即落到指定一级页面（工作台/会话/子代理/插件/设置），真机验收用。 */
    var debugScreen: String?
        get() = sp.getString(KEY_DEBUG_SCREEN, null)?.takeIf { it.isNotBlank() }
        set(value) {
            if (value == null) sp.edit().remove(KEY_DEBUG_SCREEN).apply()
            else sp.edit().putString(KEY_DEBUG_SCREEN, value).apply()
        }

    private fun endpointKey(id: String) = "endpoint_$id"

    companion object {
        const val PROVIDER_DEEPSEEK_ACCOUNT = "deepseek-account"
        const val PROVIDER_DEEPSEEK_API = "deepseek-api"
        const val PROVIDER_ANTHROPIC = "anthropic"
        const val PROVIDER_CUSTOM = "custom"

        const val DEFAULT_BASE_URL = "https://api.deepseek.com/anthropic"
        const val ANTHROPIC_BASE_URL = "https://api.anthropic.com"
        const val DEFAULT_MODEL = "deepseek-flash"
        const val DEFAULT_MAX_TOKENS = 256_000

        /**
         * 上游模型目录里明确声明 `inputModalities=["text","image"]` 的模型（docs/26）。
         * 只有它默认开「识图」；别的模型由用户在模型配置页自己声明。
         */
        const val IMAGE_CAPABLE_MODEL = "deepseek-flash"

        private fun visionKey(model: String) = "vision_$model"
        private fun webSearchKey(model: String) = "web_search_$model"
        private fun contextWindowKey(model: String) = "context_window_$model"
        private fun defaultVision(model: String) = model == IMAGE_CAPABLE_MODEL

        /**
         * 已知模型的上下文窗口（tokens，M44）。deepseek-flash 的 1048576 来自桌面
         * `request/context` 实测（provider=deepseek-account, model=deepseek-flash）；
         * chat/reasoner 是公开的 128k；其余模型不猜 → 0（自动压缩关闭，用户可在模型配置页填）。
         */
        fun defaultContextWindow(model: String): Int = when (model) {
            IMAGE_CAPABLE_MODEL -> 1_048_576
            "deepseek-chat", "deepseek-reasoner" -> 128_000
            else -> 0
        }

        /** Operit 的 provider 列表；「自定义」沿用 Anthropic Messages 协议。 */
        data class Provider(val id: String, val name: String, val subtitle: String, val defaultBaseUrl: String, val needsKey: Boolean)

        val providers = listOf(
            Provider(PROVIDER_DEEPSEEK_ACCOUNT, "DeepSeek 账号", "用 DSH 桌面版登录的账号令牌", DEFAULT_BASE_URL, false),
            Provider(PROVIDER_DEEPSEEK_API, "DeepSeek API 密钥", "api.deepseek.com 的 Anthropic 兼容端点", DEFAULT_BASE_URL, true),
            Provider(PROVIDER_ANTHROPIC, "Anthropic 官方", "api.anthropic.com", ANTHROPIC_BASE_URL, true),
            Provider(PROVIDER_CUSTOM, "自定义", "任意 Anthropic Messages 兼容端点", DEFAULT_BASE_URL, true),
        )

        fun defaultBaseUrl(id: String): String =
            providers.firstOrNull { it.id == id }?.defaultBaseUrl ?: DEFAULT_BASE_URL

        fun providerOf(id: String): Provider =
            providers.firstOrNull { it.id == id } ?: providers[0]

        private fun normalizeProviderId(id: String?, legacy: String?): String {
            when (id) {
                PROVIDER_DEEPSEEK_ACCOUNT, PROVIDER_DEEPSEEK_API, PROVIDER_ANTHROPIC, PROVIDER_CUSTOM -> return id
            }
            return if (legacy == "account") PROVIDER_DEEPSEEK_ACCOUNT else PROVIDER_DEEPSEEK_API
        }

        private const val KEY_API_KEY = "api_key"
        private const val KEY_BASE_URL = "base_url"
        private const val KEY_MODEL = "model"
        private const val KEY_ACCOUNT_TOKEN = "account_token"
        private const val KEY_PROVIDER = "provider"
        private const val KEY_PROVIDER_ID = "provider_id"
        private const val KEY_APPROVAL = "approval_mode"
        private const val KEY_ASSISTANT_NAME = "assistant_name"
        private const val KEY_ASSISTANT_DESC = "assistant_desc"
        private const val KEY_ASSISTANT_AVATAR = "assistant_avatar"
        private const val KEY_MAX_TOKENS = "max_tokens"
        private const val KEY_TEMPERATURE = "temperature"
        private const val KEY_REASONING_EFFORT = "reasoning_effort"
        private const val KEY_EXPAND_THINKING = "expand_thinking"
        private const val KEY_TIME_CONTEXT = "time_context"
        private const val KEY_THEME_MODE = "theme_mode"
        private const val KEY_ACCENT_ID = "accent_id"
        private const val KEY_RECENT_MODELS = "recent_models"
        private const val KEY_DEBUG_IMPORT_ZIP = "debug_import_zip"
        private const val KEY_DEBUG_SCREEN = "debug_open_screen"
    }
}

/**
 * 上游 dsh-llm-deepseek 的 reasoningEffort 允许值（`z.union(["off","low","high","max"])`）。
 * `off` 不是「不指定」：它把 `thinking` 设成 disabled 并且不发 `output_config`。
 */
private val REASONING_EFFORTS = setOf("off", "low", "high", "max")

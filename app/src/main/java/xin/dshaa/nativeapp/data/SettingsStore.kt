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
    private val _providerId = mutableStateOf(normalizeProviderId(sp.getString(KEY_PROVIDER_ID, null), sp.getString(KEY_PROVIDER, null)))
    private val _approvalMode = mutableStateOf(sp.getString(KEY_APPROVAL, "ask")!!)
    // ---- 助手配置（M73）：多份角色卡，侧边栏切换当前使用 ----
    // 旧单份字段（assistant_name/desc/avatar/persona/opening/system）只作首次迁移来源，
    // 之后每次改动继续同步写回，保证备份/调试通道/降级还能读到「当前这份」。
    // 旧的单份角色卡（M71）：persona = 人设（deployment:persona-prefix）、
    // system = 系统提示词（deployment:persona-suffix）、opening = 开场白（空会话第一句，不进提示词）。
    private val _assistantProfiles = mutableStateOf(loadAssistantProfiles())
    private val _activeAssistantId = mutableStateOf(
        sp.getString(KEY_ASSISTANT_ACTIVE, "").orEmpty()
            .ifBlank { _assistantProfiles.value.firstOrNull()?.id ?: AssistantProfile.DEFAULT_ID },
    )

    // ---- 模型参数 ----
    // 2026-11-09：用户要求删掉「最大输出 tokens」设置；请求固定用 DEFAULT_MAX_TOKENS。
    private val _maxTokens = mutableStateOf(DEFAULT_MAX_TOKENS)
    private val _temperature = mutableStateOf(sp.getFloat(KEY_TEMPERATURE, -1f).takeIf { it >= 0f }?.toDouble())
    // 上下文窗口（M44）：按模型记忆；0 = 未知（自动压缩关闭，手工 /compact 不受影响）
    private val _contextWindow = mutableStateOf(
        sp.getInt(contextWindowKey(_model.value), defaultContextWindow(_model.value)),
    )
    // off/low/high/max 是上游 dsh-llm-deepseek 认的全部四个值；旧值/脏值一律回落 high。
    private val _reasoningEffort = mutableStateOf(
        sp.getString(KEY_REASONING_EFFORT, "high")!!.takeIf { it in REASONING_EFFORTS } ?: "high",
    )
    // Max 模式（M58，照 Operit `max_mode`「超大上下文模式」）：
    // 开 = 不自动压缩，整段上下文直接进请求；关 = 按模型窗口 [contextWindow] 自动压缩。
    private val _maxMode = mutableStateOf(sp.getBoolean(KEY_MAX_MODE, false))

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

    /**
     * 用户**已勾选**的模型（M74，用户 23:20：输入条模型浮层只能在勾选过的模型里切）。
     *
     * 和旧 `recentModels`（「历史用过」）的区别：那个是自动记忆，用户只加了 deepseek-flash，
     * 列表里却冒出以前用过的 deepseek-chat，看着像「自己加的模型」。现在只有用户在模型配置页
     * 勾选的才进这个列表；首次升级把旧的最近使用列表当作已勾选（那是用户真实保存过的名字）。
     */
    private val _enabledModels = mutableStateOf(loadEnabledModels())

    // ---- 显示与行为 ----
    private val _expandThinking = mutableStateOf(sp.getBoolean(KEY_EXPAND_THINKING, true))
    // 时间上下文（M42，上游 dsh-time-context 默认开）：每个 step 起点给模型带上当前时间与间隔
    private val _timeContext = mutableStateOf(sp.getBoolean(KEY_TIME_CONTEXT, true))
    private val _themeMode = mutableStateOf(sp.getString(KEY_THEME_MODE, "system")!!)
    private val _accentId = mutableStateOf(sp.getString(KEY_ACCENT_ID, "blue")!!)

    val apiKey: String get() = _apiKey.value
    val baseUrl: String get() = _baseUrl.value
    val model: String get() = _model.value
    val providerId: String get() = _providerId.value
    /** 当前供应商走哪套协议：anthropic（Messages）/ openai（chat/completions）。 */
    val protocol: String get() = providerOf(_providerId.value).protocol
    /** 旧字段：account / api-key。由 [providerId] 推导，别直接写。 */
    val provider: String get() = "api-key"
    val approvalMode: String get() = _approvalMode.value

    /** 全部助手配置（M73）：至少一份；顺序 = 用户创建顺序。 */
    val assistantProfiles: List<AssistantProfile> get() = _assistantProfiles.value

    /** 当前正在使用的助手配置 id。 */
    val activeAssistantId: String get() = _activeAssistantId.value

    /** 当前正在使用的助手配置（id 对不上时退回第一份，保证永远有值）。 */
    val activeAssistant: AssistantProfile
        get() = _assistantProfiles.value.firstOrNull { it.id == _activeAssistantId.value }
            ?: _assistantProfiles.value.firstOrNull()
            ?: AssistantProfile(AssistantProfile.DEFAULT_ID)

    val assistantName: String get() = activeAssistant.name
    val assistantDesc: String get() = activeAssistant.desc
    val assistantAvatar: String get() = activeAssistant.avatar
    /** 角色卡人设（M71）：注入 system 的 `deployment:persona-prefix`（order 0，替换默认 persona 段）。 */
    val assistantPersona: String get() = activeAssistant.persona
    /** 角色卡开场白（M71）：空会话显示的问候语，只显示、不进提示词。 */
    val assistantOpening: String get() = activeAssistant.opening
    /** 角色卡系统提示词（M71）：注入 system 的 `deployment:persona-suffix`（order 10200，最后一段）。 */
    val assistantSystem: String get() = activeAssistant.system
    val maxTokens: Int get() = _maxTokens.value
    val temperature: Double? get() = _temperature.value
    val reasoningEffort: String get() = _reasoningEffort.value
    /** 当前模型的上下文窗口（tokens，M44）；0 = 未知 → 自动压缩关闭。 */
    val contextWindow: Int get() = _contextWindow.value

    /** Max 模式（M58）：开启 = 超大上下文，自动压缩关掉。 */
    val maxMode: Boolean get() = _maxMode.value

    /** 自动压缩实际参考的窗口：Max 模式返回 0（不压缩）。 */
    val effectiveContextWindow: Int get() = if (_maxMode.value) 0 else _contextWindow.value

    /** 最近用过的模型（新的在前，最多 6 个）。 */
    val recentModels: List<String> get() = _recentModels.value

    /** 已勾选模型（切换器只列这些；当前使用的模型一定在里面）。 */
    val enabledModels: List<String> get() = _enabledModels.value

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
        // 手动输入/保存的模型一定进「已勾选」，否则切换器里连当前模型都没有
        ensureEnabledModel(_model.value)
    }

    /** 首次迁移：没有 `enabled_models` 就把旧的「最近使用」当已勾选（只记录真实保存过的名字）。 */
    private fun loadEnabledModels(): List<String> {
        val saved = sp.getString(KEY_ENABLED_MODELS, null)
        val names = if (saved != null) {
            saved.split('\n')
        } else {
            sp.getString(KEY_RECENT_MODELS, null)?.split('\n').orEmpty()
        }
        val base = names.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        return (base.ifEmpty { listOf(_model.value) } + _model.value).distinct()
    }

    /** 保存用户勾选的模型集合（当前使用的模型保留，否则切换器里没有「当前」）。 */
    fun setEnabledModels(models: List<String>) {
        val next = (models.map { it.trim() }.filter { it.isNotEmpty() } + _model.value).distinct()
        _enabledModels.value = next
        sp.edit().putString(KEY_ENABLED_MODELS, next.joinToString("\n")).apply()
    }

    fun toggleEnabledModel(model: String, enabled: Boolean) {
        val next = if (enabled) _enabledModels.value + model else _enabledModels.value - model
        setEnabledModels(next)
    }

    private fun ensureEnabledModel(model: String) {
        if (_enabledModels.value.contains(model)) return
        setEnabledModels(_enabledModels.value + model)
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

    fun setProviderId(id: String) {
        val normalized = normalizeProviderId(id, null)
        _providerId.value = normalized
        sp.edit().putString(KEY_PROVIDER_ID, normalized).apply()
        // 旧键同步，调试通道与历史代码仍读得到
        sp.edit().putString(KEY_PROVIDER, "api-key").apply()
        // 切 provider 时端点跟到该 provider 的默认值；密钥/模型保留各自记忆
        val remembered = sp.getString(endpointKey(normalized), null)
        _baseUrl.value = remembered?.takeIf { it.isNotBlank() } ?: defaultBaseUrl(normalized)
        sp.edit().putString(KEY_BASE_URL, _baseUrl.value).apply()
    }

    fun setApprovalMode(v: String) {
        _approvalMode.value = v
        sp.edit().putString(KEY_APPROVAL, v).apply()
    }

    fun setAssistantName(v: String) = updateActiveAssistant { it.copy(name = v.trim().ifBlank { "鲸歌" }) }

    fun setAssistantDesc(v: String) = updateActiveAssistant { it.copy(desc = v.trim()) }

    fun setAssistantAvatar(v: String) = updateActiveAssistant { it.copy(avatar = v.trim()) }

    /** 角色卡人设：空 = 用上游默认 persona 段（不注入自定义人设）。 */
    fun setAssistantPersona(v: String) = updateActiveAssistant { it.copy(persona = v.trim()) }

    /** 角色卡开场白：空 = 空会话只显示默认提示语。 */
    fun setAssistantOpening(v: String) = updateActiveAssistant { it.copy(opening = v.trim()) }

    /** 角色卡系统提示词：空 = 不追加 `deployment:persona-suffix`。 */
    fun setAssistantSystem(v: String) = updateActiveAssistant { it.copy(system = v.trim()) }

    // ---- 助手配置的多份管理（M73） ----

    /** 切换当前使用的助手配置（侧边栏 / 助手配置页都能调）。 */
    fun setActiveAssistant(id: String) {
        if (_assistantProfiles.value.none { it.id == id }) return
        _activeAssistantId.value = id
        persistAssistantProfiles(_assistantProfiles.value, id)
    }

    /** 新建一份助手配置并**设为当前**（用户点了就会开始用新助手）。 */
    fun addAssistantProfile(name: String = "新助手"): AssistantProfile {
        val clean = name.trim().ifBlank { "新助手" }
        val profile = AssistantProfile(id = "a" + System.currentTimeMillis().toString(36), name = clean)
        val list = _assistantProfiles.value + profile
        _assistantProfiles.value = list
        _activeAssistantId.value = profile.id
        persistAssistantProfiles(list, profile.id)
        return profile
    }

    /** 复制一份（改文案试错时省事），新的一份设为当前。 */
    fun duplicateAssistantProfile(id: String): AssistantProfile? {
        val source = _assistantProfiles.value.firstOrNull { it.id == id } ?: return null
        val copy = source.copy(
            id = "a" + System.currentTimeMillis().toString(36),
            name = source.name + " 副本",
        )
        val list = _assistantProfiles.value + copy
        _assistantProfiles.value = list
        _activeAssistantId.value = copy.id
        persistAssistantProfiles(list, copy.id)
        return copy
    }

    /**
     * 从插件预设套用成一份新角色卡（M74）：预设的 `dsh-persona` prefix → 人设段，
     * suffix → 系统提示词段。套用完直接设为当前使用（和「新建」一致，用户能立刻看到效果）。
     */
    fun addAssistantProfileFromPreset(
        name: String,
        persona: String,
        system: String,
        desc: String = "",
    ): AssistantProfile {
        val profile = AssistantProfile(
            id = "a" + System.currentTimeMillis().toString(36),
            name = name.trim().ifBlank { "插件预设助手" },
            desc = desc,
            persona = persona,
            system = system,
        )
        val list = _assistantProfiles.value + profile
        _assistantProfiles.value = list
        _activeAssistantId.value = profile.id
        persistAssistantProfiles(list, profile.id)
        return profile
    }

    fun updateAssistantProfile(profile: AssistantProfile) {
        val current = _assistantProfiles.value
        if (current.none { it.id == profile.id }) return
        val list = current.map { if (it.id == profile.id) profile else it }
        _assistantProfiles.value = list
        persistAssistantProfiles(list, _activeAssistantId.value)
    }

    /** 删一份；最后一份不给删（否则没有助手可用）。正在用的被删 → 退回第一份。 */
    fun deleteAssistantProfile(id: String): Boolean {
        val current = _assistantProfiles.value
        if (current.size <= 1) return false
        val list = current.filterNot { it.id == id }
        if (list.size == current.size) return false
        _assistantProfiles.value = list
        val active = if (_activeAssistantId.value == id) list.first().id else _activeAssistantId.value
        _activeAssistantId.value = active
        persistAssistantProfiles(list, active)
        return true
    }

    private inline fun updateActiveAssistant(transform: (AssistantProfile) -> AssistantProfile) {
        updateAssistantProfile(transform(activeAssistant))
    }

    private fun loadAssistantProfiles(): List<AssistantProfile> {
        val raw = sp.getString(KEY_ASSISTANT_PROFILES, null)
        if (!raw.isNullOrBlank()) {
            val parsed = AssistantProfile.listFromJson(raw)
            if (parsed.isNotEmpty()) return parsed
        }
        // 迁移 M71 的单份角色卡 → 第一份助手配置
        val legacy = AssistantProfile(
            id = AssistantProfile.DEFAULT_ID,
            name = sp.getString(KEY_ASSISTANT_NAME, "鲸歌")!!.let { if (it == "DSH") "鲸歌" else it },
            desc = sp.getString(KEY_ASSISTANT_DESC, "").orEmpty(),
            avatar = sp.getString(KEY_ASSISTANT_AVATAR, "").orEmpty(),
            persona = sp.getString(KEY_ASSISTANT_PERSONA, "").orEmpty(),
            opening = sp.getString(KEY_ASSISTANT_OPENING, "").orEmpty(),
            system = sp.getString(KEY_ASSISTANT_SYSTEM, "").orEmpty(),
        )
        sp.edit()
            .putString(KEY_ASSISTANT_PROFILES, AssistantProfile.listToJson(listOf(legacy)))
            .putString(KEY_ASSISTANT_ACTIVE, legacy.id)
            .apply()
        return listOf(legacy)
    }

    /** 落盘：JSON 数组 + 当前 id，并把「当前这份」同步回旧的单份键（备份/调试通道要用）。 */
    private fun persistAssistantProfiles(list: List<AssistantProfile>, activeId: String) {
        sp.edit()
            .putString(KEY_ASSISTANT_PROFILES, AssistantProfile.listToJson(list))
            .putString(KEY_ASSISTANT_ACTIVE, activeId)
            .apply()
        val active = list.firstOrNull { it.id == activeId } ?: list.firstOrNull() ?: return
        sp.edit()
            .putString(KEY_ASSISTANT_NAME, active.name)
            .putString(KEY_ASSISTANT_DESC, active.desc)
            .putString(KEY_ASSISTANT_AVATAR, active.avatar)
            .putString(KEY_ASSISTANT_PERSONA, active.persona)
            .putString(KEY_ASSISTANT_OPENING, active.opening)
            .putString(KEY_ASSISTANT_SYSTEM, active.system)
            .apply()
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

    /** Max 模式（M58）：只影响自动压缩；模型窗口值本身保留，关掉后立刻恢复。 */
    fun setMaxMode(v: Boolean) {
        _maxMode.value = v
        sp.edit().putBoolean(KEY_MAX_MODE, v).apply()
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
        const val PROVIDER_DEEPSEEK_API = "deepseek-api"
        const val PROVIDER_DEEPSEEK_OPENAI = "deepseek-openai"
        const val PROVIDER_ANTHROPIC = "anthropic"
        const val PROVIDER_OPENAI = "openai"
        const val PROVIDER_OPENAI_COMPATIBLE = "openai-compatible"
        const val PROVIDER_CUSTOM = "custom"

        const val DEFAULT_BASE_URL = "https://api.deepseek.com/anthropic"
        const val ANTHROPIC_BASE_URL = "https://api.anthropic.com"
        const val DEEPSEEK_OPENAI_BASE_URL = "https://api.deepseek.com"
        const val OPENAI_BASE_URL = "https://api.openai.com/v1"
        const val DEFAULT_MODEL = "deepseek-flash"

        /** 单轮请求的 max_tokens（上游默认档；2026-11-09 起不再暴露给用户改）。 */
        const val DEFAULT_MAX_TOKENS = 8_192

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
        data class Provider(
            val id: String,
            val name: String,
            val subtitle: String,
            val defaultBaseUrl: String,
            val needsKey: Boolean,
            val protocol: String = "anthropic",
        )

        val providers = listOf(
            Provider(PROVIDER_DEEPSEEK_API, "DeepSeek API 密钥", "api.deepseek.com 的 Anthropic 兼容端点", DEFAULT_BASE_URL, true),
            Provider(
                PROVIDER_DEEPSEEK_OPENAI,
                "DeepSeek（OpenAI 格式）",
                "api.deepseek.com/v1/chat/completions",
                DEEPSEEK_OPENAI_BASE_URL,
                true,
                protocol = "openai",
            ),
            Provider(PROVIDER_ANTHROPIC, "Anthropic 官方", "api.anthropic.com", ANTHROPIC_BASE_URL, true),
            Provider(
                PROVIDER_OPENAI,
                "OpenAI 官方",
                "api.openai.com/v1/chat/completions",
                OPENAI_BASE_URL,
                true,
                protocol = "openai",
            ),
            Provider(
                PROVIDER_OPENAI_COMPATIBLE,
                "OpenAI 兼容（自定义）",
                "任意 /v1/chat/completions 端点：Ollama、vLLM、硅基流动、OneAPI…",
                OPENAI_BASE_URL,
                true,
                protocol = "openai",
            ),
            Provider(PROVIDER_CUSTOM, "自定义（Anthropic 格式）", "任意 Anthropic Messages 兼容端点", DEFAULT_BASE_URL, true),
        )

        fun defaultBaseUrl(id: String): String =
            providers.firstOrNull { it.id == id }?.defaultBaseUrl ?: DEFAULT_BASE_URL

        fun providerOf(id: String): Provider =
            providers.firstOrNull { it.id == id } ?: providers[0]

        private fun normalizeProviderId(id: String?, legacy: String?): String {
            when (id) {
                PROVIDER_DEEPSEEK_API,
                PROVIDER_DEEPSEEK_OPENAI,
                PROVIDER_ANTHROPIC,
                PROVIDER_OPENAI,
                PROVIDER_OPENAI_COMPATIBLE,
                PROVIDER_CUSTOM -> return id
                // 旧版「DeepSeek 账号」通道已删除：老配置统一落到 API 密钥模式。
                "deepseek-account" -> return PROVIDER_DEEPSEEK_API
            }
            return PROVIDER_DEEPSEEK_API
        }

        private const val KEY_API_KEY = "api_key"
        private const val KEY_BASE_URL = "base_url"
        private const val KEY_MODEL = "model"
        private const val KEY_PROVIDER = "provider"
        private const val KEY_PROVIDER_ID = "provider_id"
        private const val KEY_APPROVAL = "approval_mode"
        private const val KEY_ASSISTANT_NAME = "assistant_name"
        private const val KEY_ASSISTANT_DESC = "assistant_desc"
        private const val KEY_ASSISTANT_AVATAR = "assistant_avatar"
        private const val KEY_ASSISTANT_PERSONA = "assistant_persona"
        private const val KEY_ASSISTANT_OPENING = "assistant_opening"
        private const val KEY_ASSISTANT_SYSTEM = "assistant_system"
        // M73：多份助手配置（JSON 数组）+ 当前使用的那份 id
        private const val KEY_ASSISTANT_PROFILES = "assistant_profiles"
        private const val KEY_ASSISTANT_ACTIVE = "assistant_active_id"
        private const val KEY_MAX_TOKENS = "max_tokens"
        private const val KEY_TEMPERATURE = "temperature"
        private const val KEY_REASONING_EFFORT = "reasoning_effort"
        private const val KEY_MAX_MODE = "max_mode"
        private const val KEY_EXPAND_THINKING = "expand_thinking"
        private const val KEY_TIME_CONTEXT = "time_context"
        private const val KEY_THEME_MODE = "theme_mode"
        private const val KEY_ACCENT_ID = "accent_id"
        private const val KEY_RECENT_MODELS = "recent_models"
        private const val KEY_ENABLED_MODELS = "enabled_models"
        private const val KEY_DEBUG_IMPORT_ZIP = "debug_import_zip"
        private const val KEY_DEBUG_SCREEN = "debug_open_screen"
    }
}

/**
 * 上游 dsh-llm-deepseek 的 reasoningEffort 允许值（`z.union(["off","low","high","max"])`）。
 * `off` 不是「不指定」：它把 `thinking` 设成 disabled 并且不发 `output_config`。
 */
private val REASONING_EFFORTS = setOf("off", "low", "high", "max")

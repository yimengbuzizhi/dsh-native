package xin.dshaa.core.plugins

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File

/** 插件包格式。目前只认两种清单，运行形态都按「JS 模块 + apply(ctx)」处理。 */
enum class PluginFormat(val label: String) {
    /** DSH 桌面端 npm 包：`package.json`（name/version/main）+ `lib/index.js`。 */
    DSH("DSH"),

    /** Operit / ToolPkg 风格：`manifest.json` + 入口脚本（兼容登记的兜底格式）。 */
    OPERIT("Operit"),
}

/** `package.json` / `manifest.json` 解析出来的最小清单。 */
data class PluginManifest(
    val id: String,
    val name: String,
    val version: String,
    val description: String,
    /** 包内相对入口路径，如 `lib/index.js`。 */
    val main: String,
    val format: PluginFormat,
    /** 清单里申请的权限（已规范化，M28）。 */
    val permissions: List<String> = emptyList(),
    /**
     * 清单里的配置 schema（ToolPkg/Operit 风格：`manifest.json` 的 `config.schema`；M32）。
     * DSH 包用 `export const Config`，这里为 null。
     */
    val configSchema: JsonObject? = null,
    /** 清单里的配置默认值（`config.default`；M32）。 */
    val configDefaults: JsonObject? = null,
    /** DSH `dsh.client`：插件带浏览器半侧（Web UI）；手机不执行，只标记。 */
    val clientUi: Boolean = false,
    /** DSH `dsh.bundle.patch`：组合包补丁路径（`cordis.patch.yml`）。 */
    val bundlePatch: String? = null,
    /** DSH `engines.dsh`：插件要求的 DSH 版本范围。 */
    val enginesDsh: String? = null,
)

/** 插件注册的一句提示词段（DSH `ctx.systemPrompt.section`）。 */
data class PluginPromptSection(
    val name: String,
    val order: Int = 100,
    val text: String = "",
    val complete: Boolean = false,
)

/** 插件注册的斜杠命令（DSH `ctx.commands.register`）。 */
data class PluginCommand(val name: String, val description: String = "")

/**
 * M84-E2：插件通过 `ctx.llm.register({...})` 注册的**配置式供应商**。
 *
 * 手机端没有常驻 JS 运行时，接不了 DSH 里「插件实现 `stream()` 回调」的完整形态；
 * 这里只接受配置（id / 协议 / 端点 / key / 模型），宿主包装成 [xin.dshaa.core.llm.LlmProvider]，
 * 走内置的 Anthropic Messages 或 OpenAI 兼容通道。插件只能登记，**用哪个由宿主/用户选**。
 */
data class PluginLlmProvider(
    /** 稳定 id（`--es dsh_llm_provider <id>` / 以后的选择器用它）。 */
    val id: String,
    val label: String = "",
    /** `anthropic` / `openai`；空 = 跟随宿主当前协议（推荐，插件不猜端点形状）。 */
    val protocol: String = "",
    /** 空 = 跟随宿主当前端点（此时 apiKey 也跟随宿主）。 */
    val baseUrl: String = "",
    /** 空 = 跟随宿主当前模型。 */
    val model: String = "",
    /** 空 = 端点为空时跟随宿主 key；端点自定义时必须自带 key（否则报未配置）。 */
    val apiKey: String = "",
    /** M84-J：插件带 `stream(request)` 回调（常驻运行时里执行，模型真的由插件产出）。 */
    val hasStream: Boolean = false,
)

/** M84-E2：`ctx.ui.registerRoute` 登记的页面入口。最小版只登记元数据 + 打开插件自己的 dsh.client。 */
data class PluginRoute(
    val id: String,
    val title: String,
    val description: String = "",
    /** M84-G：`page: true` = 作为整页内容提供（可替换同 id 的宿主内置页）。 */
    val page: Boolean = false,
)

/**
 * M84-H：`ctx.sessionProjections.register({key, stateVersion, ...})` 登记的会话投影。
 *
 * 定义本身留在插件的常驻 JS 运行时里（`init` / `apply` / `wire.view` 是闭包）；
 * 这里只持久化元数据，插件页展示「投影：key（vN，有视图）」。
 */
data class PluginProjection(
    val key: String,
    val stateVersion: Int = 1,
    /** 有 `wire.view` 时宿主拿到的是视图；否则是投影 state 本身。 */
    val hasView: Boolean = false,
    val hasApply: Boolean = true,
)

/**
 * M85-A：`ctx.ui.registerShell({id, label, mount, onState, onAction})` 登记的一个**整壳**。
 *
 * 定义（mount/onState/onAction 闭包）留在插件常驻 JS 运行时里；这里只持久化元数据。
 * 一个插件可以带多个壳，`dsh_shell` 用 `插件id` 或 `插件id#壳id` 选择。
 */
data class PluginShell(
    val id: String,
    val label: String,
    /**
     * M85-F：壳后端。`surface`（默认）= 插件返回 Surface 树，宿主 Compose 渲染；
     * `webview` = 插件返回整屏 HTML 片段，宿主用 WebView 渲染；两种后端共用
     * `shell.mount/state/action/event` 与 `surface.command`。
     */
    val backend: String = "surface",
    /** mount 是必需的；其余回调决定宿主能不能发对应 dispatch。 */
    val hasState: Boolean = false,
    val hasAction: Boolean = false,
    val hasEvent: Boolean = false,
)

/**
 * M85-D：`ctx.agent.register({id, label, run})` 登记的一个**回合实现**（`agent/v1`）。
 *
 * 定义（run 闭包）留在插件常驻 JS 运行时里；这里只持久化元数据，供宿主解析
 * 「当前用哪个回合插件」和插件页展示。内置 `builtin.agent.dsh` 不在这里登记
 * （它是宿主编译期的 native 实现，但走同一套开关/授权/同 id 覆盖规则）。
 */
data class PluginAgent(
    val id: String,
    val label: String,
)

/**
 * M85-E：`ctx.storage.register({id, label, readOnly, list, load})` 登记的一个**存储实现**
 * （`storage/v1`）。
 *
 * 定义（list/load 闭包）留在插件常驻 JS 运行时里；这里只持久化元数据，供宿主解析
 * 「当前用哪个存储插件」和插件页展示。v1 第三方存储只开放只读导出（list/load），
 * 新建/写入仍由内置 `builtin.storage.jsonl` 的 native 实现负责。
 */
data class PluginStorage(
    val id: String,
    val label: String,
    val readOnly: Boolean = true,
    val hasList: Boolean = true,
    val hasLoad: Boolean = true,
)

/**
 * M85-E：`ctx.systemPrompt.register({id, label, assemble})` 登记的一个**提示词实现**
 * （`prompt/v1`）。定义留在常驻 JS 里；宿主把内置拼装结果作为 base 传进去，
 * 插件可以整体替换/包一层（同 id 覆盖内置 `builtin.prompt.dsh`）。
 */
data class PluginPrompt(
    val id: String,
    val label: String,
)

/**
 * M85-F：`ctx.capability.register({id, label, description, kind, invoke})` 登记的一个**能力提供方**
 * （`capability/v1`）。
 *
 * 定义（invoke 闭包）留在插件常驻 JS 运行时里；这里只持久化元数据，供宿主解析
 * 「这个能力由谁提供」和插件页展示。root/NFC/Shizuku/adb-shell 等特权能力只能通过它登记，
 * 宿主不写死；同一 id 的插件提供方优先于内置平台能力（同 id 覆盖）。
 */
data class PluginCapabilityProvider(
    val id: String,
    val label: String = "",
    val description: String = "",
    /** 展示用分类（`device` / `privileged` / `system` …）；宿主不解释。 */
    val kind: String = "",
)

/** 声明字段的规范化/校验：JS 试运行和 `plugins.json` 读盘走同一套，坏值直接丢。 */
internal object PluginDecl {
    private val PROVIDER_ID = Regex("""^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$""")

    fun providerId(raw: String?): String? =
        raw?.trim()?.takeIf { PROVIDER_ID.matches(it) }

    fun protocol(raw: String?): String = when (raw?.trim()?.lowercase()) {
        "openai", "openai-completions", "openai-compatible", "chat" -> "openai"
        "anthropic", "anthropic-messages", "messages", "claude" -> "anthropic"
        // 空/auto/host/认不出 = 跟随宿主当前协议（不猜，避免把请求发到错误端点）
        else -> ""
    }

    fun provider(
        id: String?,
        label: String?,
        protocol: String?,
        baseUrl: String?,
        model: String?,
        apiKey: String?,
        hasStream: Boolean? = null,
    ): PluginLlmProvider? {
        val cleanId = providerId(id) ?: return null
        return PluginLlmProvider(
            id = cleanId,
            label = label?.trim()?.take(120).orEmpty().ifBlank { cleanId },
            protocol = protocol(protocol),
            baseUrl = baseUrl?.trim()?.take(400).orEmpty(),
            model = model?.trim()?.take(200).orEmpty(),
            apiKey = apiKey?.trim()?.take(500).orEmpty(),
            hasStream = hasStream == true,
        )
    }

    fun route(id: String?, title: String?, description: String?, page: Boolean = false): PluginRoute? {
        val clean = id?.trim()?.take(160)?.takeIf { it.isNotBlank() } ?: return null
        if (clean.any { it.isISOControl() }) return null
        return PluginRoute(
            id = clean,
            title = title?.trim()?.take(120).orEmpty().ifBlank { clean },
            description = description?.trim()?.take(300).orEmpty(),
            page = page,
        )
    }

    /** M84-H：投影 key 的长度/字符门（和 DSH 的 key 用法对齐：短、可读、无控制字符）。 */
    fun projection(key: String?, stateVersion: Int?, hasView: Boolean?, hasApply: Boolean?): PluginProjection? {
        val clean = key?.trim()?.take(80)?.takeIf { it.isNotBlank() } ?: return null
        if (clean.any { it.isISOControl() }) return null
        return PluginProjection(
            key = clean,
            stateVersion = (stateVersion ?: 1).coerceAtLeast(0),
            hasView = hasView == true,
            hasApply = hasApply != false,
        )
    }

    /** M85-A：整壳 id + 展示名 + 回调能力。id 和 route 同一档长度/字符限制。 */
    fun shell(
        id: String?,
        label: String?,
        backend: String?,
        hasState: Boolean?,
        hasAction: Boolean?,
        hasEvent: Boolean?,
    ): PluginShell? {
        val clean = id?.trim()?.take(80)?.takeIf { it.isNotBlank() } ?: return null
        if (clean.any { it.isISOControl() }) return null
        return PluginShell(
            id = clean,
            label = label?.trim()?.take(120).orEmpty().ifBlank { clean },
            // M85-F：未知后端按 surface 处理（不猜新协议）；webview 是唯一新增后端。
            backend = if (backend?.trim()?.equals("webview", true) == true) "webview" else "surface",
            hasState = hasState == true,
            hasAction = hasAction == true,
            hasEvent = hasEvent == true,
        )
    }

    /** M85-D：回合实现 id + 展示名（同一档长度/字符限制；run 闭包不进清单）。 */
    fun agent(id: String?, label: String?): PluginAgent? {
        val clean = id?.trim()?.take(80)?.takeIf { it.isNotBlank() } ?: return null
        if (clean.any { it.isISOControl() }) return null
        return PluginAgent(
            id = clean,
            label = label?.trim()?.take(120).orEmpty().ifBlank { clean },
        )
    }

    /** M85-E：存储实现 id + 展示名 + 只读/回调能力（list/load 闭包留在常驻 JS 里）。 */
    fun storage(
        id: String?,
        label: String?,
        readOnly: Boolean?,
        hasList: Boolean?,
        hasLoad: Boolean?,
    ): PluginStorage? {
        val clean = id?.trim()?.take(80)?.takeIf { it.isNotBlank() } ?: return null
        if (clean.any { it.isISOControl() }) return null
        return PluginStorage(
            id = clean,
            label = label?.trim()?.take(120).orEmpty().ifBlank { clean },
            // v1 只支持只读导出：插件显式写 readOnly=false 也不放手（写入路径未定义）。
            readOnly = true,
            hasList = hasList != false,
            hasLoad = hasLoad != false,
        )
    }

    /** M85-E：提示词实现 id + 展示名（assemble 闭包留在常驻 JS 里）。 */
    fun prompt(id: String?, label: String?): PluginPrompt? {
        val clean = id?.trim()?.take(80)?.takeIf { it.isNotBlank() } ?: return null
        if (clean.any { it.isISOControl() }) return null
        return PluginPrompt(
            id = clean,
            label = label?.trim()?.take(120).orEmpty().ifBlank { clean },
        )
    }

    /** M85-F：能力提供方 id/说明/分类（invoke 闭包留在常驻 JS 里；id 规则与其它角色同一档）。 */
    fun capability(
        id: String?,
        label: String?,
        description: String?,
        kind: String?,
    ): PluginCapabilityProvider? {
        val clean = id?.trim()?.take(80)?.takeIf { it.isNotBlank() } ?: return null
        if (clean.any { it.isISOControl() }) return null
        val cleanKind = kind?.trim()?.take(32).orEmpty().filter { it.isLetterOrDigit() || it == '.' || it == '_' }
        return PluginCapabilityProvider(
            id = clean,
            label = label?.trim()?.take(120).orEmpty().ifBlank { clean },
            description = description?.trim()?.take(300).orEmpty(),
            kind = cleanKind,
        )
    }
}

/** 包里的一个文件（路径已归一化成 `/` 分隔、已去掉公共顶层目录）。 */
class PluginFile(val path: String, val bytes: ByteArray)

sealed interface PackageParse {
    data class Ok(val manifest: PluginManifest, val root: String, val files: List<PluginFile>) : PackageParse

    data class Failed(val error: String) : PackageParse
}

sealed interface ArchiveRead {
    data class Ok(val files: List<PluginFile>) : ArchiveRead

    data class Failed(val error: String) : ArchiveRead
}

/**
 * 读清单的结果（M37）。
 *
 * [Missing] 只出现在「读已安装目录」时：目录里没有 package.json/manifest.json
 * （旧安装或被手删），此时重载只能重跑入口，不能改清单字段。
 */
sealed interface ManifestRead {
    data class Ok(val manifest: PluginManifest) : ManifestRead

    object Missing : ManifestRead

    data class Failed(val error: String) : ManifestRead
}

/** 试运行里「插件注册了哪些工具」。 */
data class PluginTool(
    val name: String,
    val description: String,
    /** 归一化后的 JSON Schema 文本（null = 认不出参数形状，暂不注入对话）。 */
    val parameters: String? = null,
)

/** 插件试运行（干跑）结果：只读，不改宿主持久状态。 */
data class PluginDryRun(
    val name: String? = null,
    val version: String? = null,
    val inject: List<String> = emptyList(),
    val tools: List<PluginTool> = emptyList(),
    val imports: List<String> = emptyList(),
    val relativeImports: List<String> = emptyList(),
    val applied: Boolean = false,
    val error: String? = null,
    /** 插件 `export const Config = …` 归一化后的 JSON Schema 文本（M30；null = 没有配置）。 */
    val configSchema: String? = null,
    /** Config 序列化失败的原因（插件页/日志可见，不影响工具注册）。 */
    val configError: String? = null,
    /** 插件注册的提示词段（`ctx.systemPrompt.section`；按 order 拼进 system）。 */
    val promptSections: List<PluginPromptSection> = emptyList(),
    /** 插件注册的动态上下文（`ctx.systemPrompt.context`；当前按静态文本注入）。 */
    val contexts: List<PluginPromptSection> = emptyList(),
    /** 插件 `ctx.systemPrompt.variable(name, fn)` 在 apply 时求值出的快照（M74）。 */
    val promptVariables: Map<String, String> = emptyMap(),
    /**
     * M84-E1：插件调用了 `ctx.systemPrompt.suppressRuntimeContext()`。
     * 启用期间宿主的运行时上下文快照不再注入（其它 `ctx.systemPrompt.context` 仍注入）。
     */
    val suppressRuntimeContext: Boolean = false,
    /** 插件注册的斜杠命令（`ctx.commands.register`）。 */
    val commands: List<PluginCommand> = emptyList(),
    /** M84-E2：`ctx.llm.register({...})` 登记的配置式供应商。 */
    val llmProviders: List<PluginLlmProvider> = emptyList(),
    /** M84-E2：`ctx.ui.registerRoute(...)` 登记的页面入口（元数据）。 */
    val routes: List<PluginRoute> = emptyList(),
    /** M84-H：`ctx.sessionProjections.register(...)` 登记的会话投影（元数据）。 */
    val projections: List<PluginProjection> = emptyList(),
    /** M85-A：`ctx.ui.registerShell(...)` 登记的整壳（元数据，定义留在常驻 JS 里）。 */
    val shells: List<PluginShell> = emptyList(),
    /** Static loading/v1 declarations; usable before any plugin runtime starts. */
    val loadings: List<PluginLoading> = emptyList(),
    /** M85-D：`ctx.agent.register(...)` 登记的回合实现（agent/v1，定义留在常驻 JS 里）。 */
    val agents: List<PluginAgent> = emptyList(),
    /** M85-E：`ctx.storage.register(...)` 登记的存储实现（storage/v1，定义留在常驻 JS 里）。 */
    val storages: List<PluginStorage> = emptyList(),
    /** M85-E：`ctx.systemPrompt.register(...)` 登记的提示词实现（prompt/v1）。 */
    val prompts: List<PluginPrompt> = emptyList(),
    /** M85-F：`ctx.capability.register(...)` 登记的能力提供方（capability/v1，invoke 闭包留在常驻 JS 里）。 */
    val capabilities: List<PluginCapabilityProvider> = emptyList(),
    /**
     * 插件用到、但鲸歌未实现的宿主能力（`sessionProjections` / `llm.register` /
     * `ui.register*` / `ctx.on` 等）。插件页明确展示，不再静默吞掉。
     */
    val unsupported: List<String> = emptyList(),
    /** M83：`ctx.provide` 的服务快照。 */
    val provides: Map<String, String> = emptyMap(),
    /** M83：`ctx.inject` 声明的依赖。 */
    val injects: List<PluginDependencyRef> = emptyList(),
    /** M83：本次 apply 缺的服务（非 optional 且没人提供）。 */
    val missingServices: List<String> = emptyList(),
    /** M83：`ctx.effect` 登记的清理器标签。 */
    val effects: List<String> = emptyList(),
    /** M83：`ctx.on` 登记的事件名。 */
    val subscriptions: List<String> = emptyList(),
)

/**
 * 插件工具的一次真实执行结果（M27）。
 * [text] 是给模型的工具结果正文；[error] 非空 = 执行失败（原文给模型和用户看）。
 */
data class PluginInvokeResult(
    val ok: Boolean,
    val text: String = "",
    val error: String? = null,
)

/**
 * 已安装插件清单（`files/plugins.json`）。
 *
 * 一个插件 = 一个目录 + 一条记录。记录里的 [error] 是最近一次试运行的失败文本（没有就 null），
 * 插件页原样展示给用户和 AI 看（docs/05：加载一次就是干跑，报错直接可见）。
 */
data class PluginRecord(
    val id: String,
    val name: String,
    val version: String,
    val description: String,
    val path: String,
    val main: String,
    /** [PluginFormat] 的名字（写 JSON 用字符串，兼容旧文件）。 */
    val format: String,
    val enabled: Boolean,
    val tools: List<String>,
    /** 工具名 → 给模型看的说明（M27 起持久化；旧清单没有就是空，注入时用插件名兜底）。 */
    val toolDescriptions: Map<String, String> = emptyMap(),
    /** 工具名 → 归一化后的 JSON Schema 文本（M27 起持久化；没有 = 暂不注入）。 */
    val toolSchemas: Map<String, String> = emptyMap(),
    val imports: List<String>,
    val error: String?,
    val installedAt: Long,
    /** 清单里申请的权限（规范化后的原文，M28）。 */
    val permissions: List<String> = emptyList(),
    /** 用户已经确认/自动允许的权限；执行插件时才按这张表放行。 */
    val grantedPermissions: List<String> = emptyList(),
    /** `Config` 导出归一化后的 JSON Schema 文本（M30；null = 这个插件没有配置）。 */
    val configSchema: String? = null,
    /**
     * 用户改过的配置（稀疏 JSON 对象文本，只存用户显式设的项；M30）。
     * 空串/`"{}"` 表示全用默认值；执行/展示时由 [PluginSchema.withDefaults] 合并默认值。
     */
    val config: String = "{}",
    /** Config schema 算出来的默认配置（JSON 对象文本，M30；给「恢复默认」和详情展示用）。 */
    val configDefaults: String = "{}",
    /** `Config` 序列化/换算失败的原因（M30；不影响工具注册，但插件页要能看到）。 */
    val configError: String? = null,
    /** DSH `dsh.client`：带浏览器半侧（Web UI）。手机不执行，插件页标记。 */
    val clientUi: Boolean = false,
    /** DSH `dsh.bundle.patch`：组合包补丁路径。 */
    val bundlePatch: String? = null,
    /** DSH `engines.dsh`。 */
    val enginesDsh: String? = null,
    /** 组合包 `cordis.patch.yml` 里 insert 的子插件（M61，只展示；鲸歌不能按 name 自动装 npm 包）。 */
    val bundleChildren: List<BundleChild> = emptyList(),
    /** 组合包里没执行/不认识的条目（改已有行、动态 disabled 等）。 */
    val bundleNotes: List<String> = emptyList(),
    /** 组合包里的助手预设（M74，只读展示 + 可套用成角色卡）。 */
    val presets: List<BundlePreset> = emptyList(),
    /** 插件注册的提示词段（已按 order 排好；注入时直接拼）。 */
    val promptSections: List<PluginPromptSection> = emptyList(),
    /** 插件注册的动态上下文（当前按静态文本注入）。 */
    val contexts: List<PluginPromptSection> = emptyList(),
    /** 插件 `ctx.systemPrompt.variable` 求值出的变量快照（M74；段文本里的 `{{name}}` 用它替换）。 */
    val promptVariables: Map<String, String> = emptyMap(),
    /** M84-E1：本插件要求抑制宿主的运行时上下文快照（`ctx.systemPrompt.suppressRuntimeContext`）。 */
    val suppressRuntimeContext: Boolean = false,
    /** 插件注册的斜杠命令。 */
    val commands: List<PluginCommand> = emptyList(),
    /** M84-E2：`ctx.llm.register({...})` 登记的配置式供应商。 */
    val llmProviders: List<PluginLlmProvider> = emptyList(),
    /** M84-E2：`ctx.ui.registerRoute(...)` 登记的页面入口（元数据）。 */
    val routes: List<PluginRoute> = emptyList(),
    /** M84-H：本插件注册的会话投影（key → 版本/是否有 wire.view）。 */
    val projections: List<PluginProjection> = emptyList(),
    /** M85-A：本插件注册的整壳（`ctx.ui.registerShell`）。 */
    val shells: List<PluginShell> = emptyList(),
    /** Static loading/v1 declarations; usable before any plugin runtime starts. */
    val loadings: List<PluginLoading> = emptyList(),
    /** M85-D：本插件注册的回合实现（`ctx.agent.register`，agent/v1）。 */
    val agents: List<PluginAgent> = emptyList(),
    /** M85-E：本插件注册的存储实现（`ctx.storage.register`，storage/v1）。 */
    val storages: List<PluginStorage> = emptyList(),
    /** M85-E：本插件注册的提示词实现（`ctx.systemPrompt.register`，prompt/v1）。 */
    val prompts: List<PluginPrompt> = emptyList(),
    /** M85-F：本插件注册的能力提供方（`ctx.capability.register`，capability/v1）。 */
    val capabilities: List<PluginCapabilityProvider> = emptyList(),
    /** 插件用到但鲸歌未实现的宿主能力。 */
    val unsupported: List<String> = emptyList(),
    /** M83 服务容器：本插件 `ctx.provide(name, value)` 的服务名 → 值 JSON 快照。 */
    val provides: Map<String, String> = emptyMap(),
    /** M83：本插件 `ctx.inject([...])` 声明的依赖。 */
    val injects: List<PluginDependencyRef> = emptyList(),
    /** M83：按当前启用集合解析出的缺失服务（插件页标红「缺少服务 X」）。 */
    val missingServices: List<String> = emptyList(),
    /** M83：`ctx.effect` 登记的清理器标签（M84-I 起：插件停用时真的回调 cleanup）。 */
    val effects: List<String> = emptyList(),
    /** M83：`ctx.on` 登记的事件名。 */
    val subscriptions: List<String> = emptyList(),
)

/**
 * 插件清单文件的读写。格式刻意简单（和 WorkspaceStore 一样手写 JSON）：
 * 坏文件按「没有装过插件」降级，不阻塞插件页。
 */
class PluginRegistryFile(private val file: File) {

    @Synchronized
    fun list(): List<PluginRecord> = load()

    fun find(id: String): PluginRecord? = list().firstOrNull { it.id == id }

    @Synchronized
    fun upsert(record: PluginRecord) {
        val records = load().toMutableList()
        val index = records.indexOfFirst { it.id == record.id }
        if (index >= 0) records[index] = record else records.add(record)
        save(records)
    }

    @Synchronized
    fun remove(id: String): PluginRecord? {
        val records = load().toMutableList()
        val index = records.indexOfFirst { it.id == id }
        if (index < 0) return null
        val removed = records.removeAt(index)
        save(records)
        return removed
    }

    @Synchronized
    fun setEnabled(id: String, enabled: Boolean): PluginRecord? {
        val records = load().toMutableList()
        val index = records.indexOfFirst { it.id == id }
        if (index < 0) return null
        records[index] = records[index].copy(enabled = enabled)
        save(records)
        return records[index]
    }

    /**
     * 从备份里的 `plugins.json` 合并记录（M35）：只补当前清单里没有的 id，不覆盖已装插件。
     * [pathFor] 把备份里的相对路径（其实只写了插件 id）重写成当前设备上的绝对路径。
     * 返回真正新增的插件 id。
     */
    @Synchronized
    fun mergeFromRegistryJson(text: String, pathFor: (String) -> String): List<String> {
        val incoming = runCatching { parse(text) }.getOrDefault(emptyList())
        if (incoming.isEmpty()) return emptyList()
        val records = load().toMutableList()
        val existing = records.mapTo(HashSet()) { it.id }
        val added = ArrayList<String>()
        incoming.forEach { record ->
            if (existing.add(record.id)) {
                records += record.copy(path = pathFor(record.id))
                added += record.id
            }
        }
        if (added.isNotEmpty()) save(records)
        return added
    }

    private fun load(): List<PluginRecord> {
        if (!file.isFile) return emptyList()
        return runCatching { parse(file.readText(Charsets.UTF_8)) }.getOrDefault(emptyList())
    }

    private fun parse(text: String): List<PluginRecord> {
        return runCatching {
            val root = Json.parseToJsonElement(text).jsonObject
            root["plugins"]?.jsonArray?.mapNotNull { element ->
                val obj = element.jsonObject
                val id = obj["id"]?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() } ?: return@mapNotNull null
                val path = obj["path"]?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() } ?: return@mapNotNull null
                val permissions = obj.stringList("permissions")
                PluginRecord(
                    id = id,
                    name = obj.string("name") ?: id,
                    version = obj.string("version") ?: "",
                    description = obj.string("description") ?: "",
                    path = path,
                    main = obj.string("main") ?: "",
                    format = obj.string("format") ?: PluginFormat.DSH.label,
                    enabled = obj.boolean("enabled") ?: true,
                    tools = obj.stringList("tools"),
                    toolDescriptions = obj.stringMap("toolDescriptions"),
                    toolSchemas = obj.stringMap("toolSchemas"),
                    imports = obj.stringList("imports"),
                    error = obj.string("error")?.takeIf { it.isNotBlank() },
                    installedAt = obj.long("installedAt") ?: 0L,
                    permissions = permissions,
                    // 手改过的授权表也要重新过一遍白名单：清单里没申请的权限不生效。
                    grantedPermissions = PluginPermissions
                        .sanitizeGrants(PluginPermissions.parseAll(permissions), obj.stringList("grantedPermissions"))
                        .toList(),
                    configSchema = obj.string("configSchema")?.takeIf { it.isNotBlank() },
                    config = obj.string("config")?.takeIf { it.isNotBlank() } ?: "{}",
                    configDefaults = obj.string("configDefaults")?.takeIf { it.isNotBlank() } ?: "{}",
                    configError = obj.string("configError")?.takeIf { it.isNotBlank() },
                    clientUi = obj.boolean("clientUi") ?: false,
                    bundlePatch = obj.string("bundlePatch")?.takeIf { it.isNotBlank() },
                    enginesDsh = obj.string("enginesDsh")?.takeIf { it.isNotBlank() },
                    bundleChildren = (obj["bundleChildren"] as? kotlinx.serialization.json.JsonArray)
                        ?.mapNotNull { element ->
                            val child = element as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
                            val name = child.string("name")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                            BundleChild(
                                id = child.string("id")?.takeIf { it.isNotBlank() } ?: name,
                                name = name,
                                disabled = child.boolean("disabled") ?: false,
                                hasConfig = child.boolean("hasConfig") ?: false,
                                inject = child.stringList("inject"),
                                dynamicDisabled = child.boolean("dynamicDisabled") ?: false,
                            )
                        }.orEmpty(),
                    bundleNotes = obj.stringList("bundleNotes"),
                    presets = (obj["presets"] as? kotlinx.serialization.json.JsonArray)
                        ?.mapNotNull { element ->
                            val preset = element as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
                            val id = preset.string("presetId")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                            BundlePreset(
                                presetId = id,
                                name = preset.string("name")?.takeIf { it.isNotBlank() } ?: id,
                                description = preset.string("description").orEmpty(),
                                order = preset.long("order")?.toInt(),
                                personaPrefix = preset.string("personaPrefix").orEmpty(),
                                personaSuffix = preset.string("personaSuffix").orEmpty(),
                                pluginCount = preset.long("pluginCount")?.toInt() ?: 0,
                            )
                        }.orEmpty(),
                    promptSections = obj.sections("promptSections"),
                    contexts = obj.sections("contexts"),
                    promptVariables = obj.stringMap("promptVariables"),
                    suppressRuntimeContext = obj.boolean("suppressRuntimeContext") ?: false,
                    commands = obj.commands("commands"),
                    llmProviders = (obj["llmProviders"] as? kotlinx.serialization.json.JsonArray)
                        ?.mapNotNull { element ->
                            val provider = element as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
                            PluginDecl.provider(
                                id = provider.string("id"),
                                label = provider.string("label"),
                                protocol = provider.string("protocol"),
                                baseUrl = provider.string("baseUrl"),
                                model = provider.string("model"),
                                apiKey = provider.string("apiKey"),
                                hasStream = provider.boolean("hasStream"),
                            )
                        }.orEmpty(),
                    routes = (obj["routes"] as? kotlinx.serialization.json.JsonArray)
                        ?.mapNotNull { element ->
                            val route = element as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
                            PluginDecl.route(
                                id = route.string("id"),
                                title = route.string("title"),
                                description = route.string("description"),
                                page = route.boolean("page") ?: false,
                            )
                        }.orEmpty(),
                    projections = (obj["projections"] as? kotlinx.serialization.json.JsonArray)
                        ?.mapNotNull { element ->
                            val projection = element as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
                            PluginDecl.projection(
                                key = projection.string("key"),
                                stateVersion = projection.long("stateVersion")?.toInt(),
                                hasView = projection.boolean("hasView"),
                                hasApply = projection.boolean("hasApply"),
                            )
                        }.orEmpty(),
                    shells = (obj["shells"] as? kotlinx.serialization.json.JsonArray)
                        ?.mapNotNull { element ->
                            val shell = element as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
                            PluginDecl.shell(
                                id = shell.string("id"),
                                label = shell.string("label"),
                                backend = shell.string("backend"),
                                hasState = shell.boolean("hasState"),
                                hasAction = shell.boolean("hasAction"),
                                hasEvent = shell.boolean("hasEvent"),
                            )
                        }.orEmpty(),
                    loadings = LoadingSpec.parseAll(obj["loadings"]),
                    agents = (obj["agents"] as? kotlinx.serialization.json.JsonArray)
                        ?.mapNotNull { element ->
                            val agent = element as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
                            PluginDecl.agent(
                                id = agent.string("id"),
                                label = agent.string("label"),
                            )
                        }.orEmpty(),
                    storages = (obj["storages"] as? kotlinx.serialization.json.JsonArray)
                        ?.mapNotNull { element ->
                            val storage = element as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
                            PluginDecl.storage(
                                id = storage.string("id"),
                                label = storage.string("label"),
                                readOnly = storage.boolean("readOnly"),
                                hasList = storage.boolean("hasList"),
                                hasLoad = storage.boolean("hasLoad"),
                            )
                        }.orEmpty(),
                    prompts = (obj["prompts"] as? kotlinx.serialization.json.JsonArray)
                        ?.mapNotNull { element ->
                            val prompt = element as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
                            PluginDecl.prompt(
                                id = prompt.string("id"),
                                label = prompt.string("label"),
                            )
                        }.orEmpty(),
                    capabilities = (obj["capabilities"] as? kotlinx.serialization.json.JsonArray)
                        ?.mapNotNull { element ->
                            val capability = element as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
                            PluginDecl.capability(
                                id = capability.string("id"),
                                label = capability.string("label"),
                                description = capability.string("description"),
                                kind = capability.string("kind"),
                            )
                        }.orEmpty(),
                    unsupported = obj.stringList("unsupported"),
                    provides = obj.stringMap("provides"),
                    injects = (obj["injects"] as? kotlinx.serialization.json.JsonArray)?.mapNotNull { element ->
                        val dep = element as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
                        val name = dep.string("name")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                        PluginDependencyRef(name = name, optional = dep.boolean("optional") ?: false)
                    }.orEmpty(),
                    missingServices = obj.stringList("missingServices"),
                    effects = obj.stringList("effects"),
                    subscriptions = obj.stringList("subscriptions"),
                )
            }.orEmpty()
        }.getOrDefault(emptyList())
    }

    private fun save(records: List<PluginRecord>) {
        runCatching {
            file.parentFile?.mkdirs()
            val root = buildJsonObject {
                put("version", 1)
                put(
                    "plugins",
                    buildJsonArray {
                        records.forEach { record ->
                            add(
                                buildJsonObject {
                                    put("id", record.id)
                                    put("name", record.name)
                                    put("version", record.version)
                                    put("description", record.description)
                                    put("path", record.path)
                                    put("main", record.main)
                                    put("format", record.format)
                                    put("enabled", record.enabled)
                                    put("tools", buildJsonArray { record.tools.forEach { add(JsonPrimitive(it)) } })
                                    if (record.toolDescriptions.isNotEmpty()) {
                                        put(
                                            "toolDescriptions",
                                            buildJsonObject {
                                                record.toolDescriptions.forEach { (name, description) ->
                                                    put(name, description)
                                                }
                                            },
                                        )
                                    }
                                    if (record.toolSchemas.isNotEmpty()) {
                                        put(
                                            "toolSchemas",
                                            buildJsonObject {
                                                record.toolSchemas.forEach { (name, schema) -> put(name, schema) }
                                            },
                                        )
                                    }
                                    put("imports", buildJsonArray { record.imports.forEach { add(JsonPrimitive(it)) } })
                                    put("permissions", buildJsonArray { record.permissions.forEach { add(JsonPrimitive(it)) } })
                                    put(
                                        "grantedPermissions",
                                        buildJsonArray { record.grantedPermissions.forEach { add(JsonPrimitive(it)) } },
                                    )
                                    record.configSchema?.let { put("configSchema", it) }
                                    if (record.config != "{}") put("config", record.config)
                                    if (record.configDefaults != "{}") put("configDefaults", record.configDefaults)
                                    record.configError?.let { put("configError", it) }
                                    if (record.clientUi) put("clientUi", true)
                                    record.bundlePatch?.let { put("bundlePatch", it) }
                                    record.enginesDsh?.let { put("enginesDsh", it) }
                                    if (record.bundleChildren.isNotEmpty()) {
                                        put(
                                            "bundleChildren",
                                            buildJsonArray {
                                                record.bundleChildren.forEach { child ->
                                                    add(
                                                        buildJsonObject {
                                                            put("id", child.id)
                                                            put("name", child.name)
                                                            if (child.disabled) put("disabled", true)
                                                            if (child.hasConfig) put("hasConfig", true)
                                                            if (child.inject.isNotEmpty()) {
                                                                put(
                                                                    "inject",
                                                                    buildJsonArray {
                                                                        child.inject.forEach { add(JsonPrimitive(it)) }
                                                                    },
                                                                )
                                                            }
                                                            if (child.dynamicDisabled) put("dynamicDisabled", true)
                                                        },
                                                    )
                                                }
                                            },
                                        )
                                    }
                                    if (record.bundleNotes.isNotEmpty()) {
                                        put(
                                            "bundleNotes",
                                            buildJsonArray { record.bundleNotes.forEach { add(JsonPrimitive(it)) } },
                                        )
                                    }
                                    if (record.presets.isNotEmpty()) {
                                        put(
                                            "presets",
                                            buildJsonArray {
                                                record.presets.forEach { preset ->
                                                    add(
                                                        buildJsonObject {
                                                            put("presetId", preset.presetId)
                                                            put("name", preset.name)
                                                            if (preset.description.isNotBlank()) put("description", preset.description)
                                                            preset.order?.let { put("order", it) }
                                                            if (preset.personaPrefix.isNotBlank()) put("personaPrefix", preset.personaPrefix)
                                                            if (preset.personaSuffix.isNotBlank()) put("personaSuffix", preset.personaSuffix)
                                                            put("pluginCount", preset.pluginCount)
                                                        },
                                                    )
                                                }
                                            },
                                        )
                                    }
                                    if (record.promptSections.isNotEmpty()) {
                                        put("promptSections", sectionsJson(record.promptSections))
                                    }
                                    if (record.contexts.isNotEmpty()) {
                                        put("contexts", sectionsJson(record.contexts))
                                    }
                                    if (record.promptVariables.isNotEmpty()) {
                                        put(
                                            "promptVariables",
                                            buildJsonObject {
                                                record.promptVariables.forEach { (name, value) -> put(name, value) }
                                            },
                                        )
                                    }
                                    if (record.suppressRuntimeContext) put("suppressRuntimeContext", true)
                                    if (record.commands.isNotEmpty()) {
                                        put(
                                            "commands",
                                            buildJsonArray {
                                                record.commands.forEach { command ->
                                                    add(
                                                        buildJsonObject {
                                                            put("name", command.name)
                                                            put("description", command.description)
                                                        },
                                                    )
                                                }
                                            },
                                        )
                                    }
                                    if (record.llmProviders.isNotEmpty()) {
                                        put(
                                            "llmProviders",
                                            buildJsonArray {
                                                record.llmProviders.forEach { provider ->
                                                    add(
                                                        buildJsonObject {
                                                            put("id", provider.id)
                                                            if (provider.label.isNotBlank()) put("label", provider.label)
                                                            put("protocol", provider.protocol)
                                                            if (provider.baseUrl.isNotBlank()) put("baseUrl", provider.baseUrl)
                                                            if (provider.model.isNotBlank()) put("model", provider.model)
                                                            if (provider.apiKey.isNotBlank()) put("apiKey", provider.apiKey)
                                                            if (provider.hasStream) put("hasStream", true)
                                                        },
                                                    )
                                                }
                                            },
                                        )
                                    }
                                    if (record.routes.isNotEmpty()) {
                                        put(
                                            "routes",
                                            buildJsonArray {
                                                record.routes.forEach { route ->
                                                    add(
                                                        buildJsonObject {
                                                            put("id", route.id)
                                                            put("title", route.title)
                                                            if (route.description.isNotBlank()) put("description", route.description)
                                                            if (route.page) put("page", true)
                                                        },
                                                    )
                                                }
                                            },
                                        )
                                    }
                                    if (record.projections.isNotEmpty()) {
                                        put(
                                            "projections",
                                            buildJsonArray {
                                                record.projections.forEach { projection ->
                                                    add(
                                                        buildJsonObject {
                                                            put("key", projection.key)
                                                            put("stateVersion", projection.stateVersion)
                                                            if (projection.hasView) put("hasView", true)
                                                            if (!projection.hasApply) put("hasApply", false)
                                                        },
                                                    )
                                                }
                                            },
                                        )
                                    }
                                    if (record.shells.isNotEmpty()) {
                                        put(
                                            "shells",
                                            buildJsonArray {
                                                record.shells.forEach { shell ->
                                                    add(
                                                        buildJsonObject {
                                                            put("id", shell.id)
                                                            put("label", shell.label)
                                                            if (shell.backend != "surface") put("backend", shell.backend)
                                                            if (shell.hasState) put("hasState", true)
                                                            if (shell.hasAction) put("hasAction", true)
                                                            if (shell.hasEvent) put("hasEvent", true)
                                                        },
                                                    )
                                                }
                                            },
                                        )
                                    }
                                    if (record.loadings.isNotEmpty()) {
                                        put("loadings", buildJsonArray {
                                            record.loadings.forEach { add(LoadingSpec.declaration(it)) }
                                        })
                                    }
                                    if (record.agents.isNotEmpty()) {
                                        put(
                                            "agents",
                                            buildJsonArray {
                                                record.agents.forEach { agent ->
                                                    add(
                                                        buildJsonObject {
                                                            put("id", agent.id)
                                                            put("label", agent.label)
                                                        },
                                                    )
                                                }
                                            },
                                        )
                                    }
                                    if (record.storages.isNotEmpty()) {
                                        put(
                                            "storages",
                                            buildJsonArray {
                                                record.storages.forEach { storage ->
                                                    add(
                                                        buildJsonObject {
                                                            put("id", storage.id)
                                                            put("label", storage.label)
                                                            put("readOnly", storage.readOnly)
                                                            put("hasList", storage.hasList)
                                                            put("hasLoad", storage.hasLoad)
                                                        },
                                                    )
                                                }
                                            },
                                        )
                                    }
                                    if (record.prompts.isNotEmpty()) {
                                        put(
                                            "prompts",
                                            buildJsonArray {
                                                record.prompts.forEach { prompt ->
                                                    add(
                                                        buildJsonObject {
                                                            put("id", prompt.id)
                                                            put("label", prompt.label)
                                                        },
                                                    )
                                                }
                                            },
                                        )
                                    }
                                    if (record.capabilities.isNotEmpty()) {
                                        put(
                                            "capabilities",
                                            buildJsonArray {
                                                record.capabilities.forEach { capability ->
                                                    add(
                                                        buildJsonObject {
                                                            put("id", capability.id)
                                                            put("label", capability.label)
                                                            if (capability.description.isNotBlank()) {
                                                                put("description", capability.description)
                                                            }
                                                            if (capability.kind.isNotBlank()) put("kind", capability.kind)
                                                        },
                                                    )
                                                }
                                            },
                                        )
                                    }
                                    if (record.unsupported.isNotEmpty()) {
                                        put("unsupported", buildJsonArray { record.unsupported.forEach { add(JsonPrimitive(it)) } })
                                    }
                                    // M83 服务容器（provide/inject/effect/on 的登记结果）
                                    if (record.provides.isNotEmpty()) {
                                        put(
                                            "provides",
                                            buildJsonObject {
                                                record.provides.forEach { (name, value) -> put(name, value) }
                                            },
                                        )
                                    }
                                    if (record.injects.isNotEmpty()) {
                                        put(
                                            "injects",
                                            buildJsonArray {
                                                record.injects.forEach { dep ->
                                                    add(
                                                        buildJsonObject {
                                                            put("name", dep.name)
                                                            put("optional", dep.optional)
                                                        },
                                                    )
                                                }
                                            },
                                        )
                                    }
                                    if (record.missingServices.isNotEmpty()) {
                                        put("missingServices", buildJsonArray { record.missingServices.forEach { add(JsonPrimitive(it)) } })
                                    }
                                    if (record.effects.isNotEmpty()) {
                                        put("effects", buildJsonArray { record.effects.forEach { add(JsonPrimitive(it)) } })
                                    }
                                    if (record.subscriptions.isNotEmpty()) {
                                        put("subscriptions", buildJsonArray { record.subscriptions.forEach { add(JsonPrimitive(it)) } })
                                    }
                                    record.error?.let { put("error", it) }
                                    put("installedAt", record.installedAt)
                                },
                            )
                        }
                    },
                )
            }
            val text = root.toString()
            val tmp = File(file.parentFile, "${file.name}.tmp")
            tmp.writeText(text, Charsets.UTF_8)
            if (!tmp.renameTo(file)) {
                file.writeText(text, Charsets.UTF_8)
                tmp.delete()
            }
        }
    }

    private fun kotlinx.serialization.json.JsonObject.string(key: String): String? =
        this[key]?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }

    private fun kotlinx.serialization.json.JsonObject.boolean(key: String): Boolean? =
        this[key]?.let { runCatching { it.jsonPrimitive.content.toBooleanStrictOrNull() }.getOrNull() }

    private fun kotlinx.serialization.json.JsonObject.long(key: String): Long? =
        this[key]?.let { runCatching { it.jsonPrimitive.content.toLongOrNull() }.getOrNull() }

    private fun kotlinx.serialization.json.JsonObject.stringList(key: String): List<String> =
        this[key]?.let { element ->
            runCatching { element.jsonArray.mapNotNull { it.jsonPrimitive.contentOrNull } }.getOrNull()
        }.orEmpty()

    /** 字符串 map（工具说明 / 工具 schema）；值不是字符串就跳过，坏一条不影响整份清单。 */
    private fun kotlinx.serialization.json.JsonObject.stringMap(key: String): Map<String, String> =
        this[key]?.let { element ->
            runCatching {
                element.jsonObject.mapNotNull { (name, value) ->
                    value.jsonPrimitive.contentOrNull?.let { name to it }
                }.toMap()
            }.getOrNull()
        }.orEmpty()

    private fun sectionsJson(sections: List<PluginPromptSection>): kotlinx.serialization.json.JsonArray =
        buildJsonArray {
            sections.forEach { section ->
                add(
                    buildJsonObject {
                        put("name", section.name)
                        put("order", section.order)
                        put("text", section.text)
                        if (section.complete) put("complete", true)
                    },
                )
            }
        }

    private fun kotlinx.serialization.json.JsonObject.sections(key: String): List<PluginPromptSection> =
        this[key]?.let { element ->
            runCatching {
                element.jsonArray.mapNotNull { item ->
                    val obj = item.jsonObject
                    val name = obj.string("name") ?: return@mapNotNull null
                    PluginPromptSection(
                        name = name,
                        order = obj["order"]?.let { runCatching { it.jsonPrimitive.content.toIntOrNull() }.getOrNull() } ?: 100,
                        text = obj.string("text").orEmpty(),
                        complete = obj.boolean("complete") ?: false,
                    )
                }
            }.getOrNull()
        }.orEmpty()

    private fun kotlinx.serialization.json.JsonObject.commands(key: String): List<PluginCommand> =
        this[key]?.let { element ->
            runCatching {
                element.jsonArray.mapNotNull { item ->
                    val obj = item.jsonObject
                    val name = obj.string("name") ?: return@mapNotNull null
                    PluginCommand(name = name, description = obj.string("description").orEmpty())
                }
            }.getOrNull()
        }.orEmpty()
}

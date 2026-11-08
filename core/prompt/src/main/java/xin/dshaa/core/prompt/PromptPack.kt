package xin.dshaa.core.prompt

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import xin.dshaa.core.protocol.ApiTool

/**
 * 提示词 / 工具定义按「上游版本」打包成资源，不写死在 Kotlin 里。
 *
 * 资源目录：`src/main/resources/prompt/<version>/{system.txt,tools.json,sections.json,config.json}`
 * - `system.txt`：上游桌面版渲染好的全文（对拍基准，不要改）
 * - `sections.json`：把 system.txt 还原成「段」+ order + 依赖工具，用于 **按当前工具集动态拼装**
 *   （docs/port/02 §2.2；上游 `assemble()`：按 order 升序、同 order 按 name 码元序、空段丢弃、`join("\n\n")`）
 * - `tools.json`：32 个工具 schema（桌面快照；Android 把 `pwsh` 换 `bash`，见 [androidApiTools]）
 */
@Serializable
data class ToolDefinition(
    val name: String,
    val description: String? = null,
    val parameters: JsonObject? = null,
)

@Serializable
data class PromptSection(
    val name: String,
    val order: Int,
    /** 只有该工具可见时才渲染这一段（null = 恒渲染）。 */
    val requires: String? = null,
    /** any / android / desktop */
    val platform: String = "any",
    val text: String,
)

@Serializable
private data class SectionsFile(val version: String, val sections: List<PromptSection>)

class PromptPack(
    val version: String,
    /** 桌面版渲染快照，仅用于对拍/排查，不要直接发给模型。 */
    val desktopSystemSnapshot: String,
    val toolDefinitions: List<ToolDefinition>,
    val sections: List<PromptSection>,
    /**
     * 计划模式激活时追加的 `plan:policy` 段（M18）。
     * 上游把这段文案放在部署配置里（`dsh/web-app/presets/standard.patch.yml` 的
     * `plan-mode.config.section`），抓出来按版本存成资源；非计划模式不渲染。
     */
    val planPolicy: String = "",
    /**
     * Android 设备工具（Operit 能力移植）：`tools-android.json`。
     * 它们不在上游 32 个工具里，是原生追加的「设备能力」；`registry` 里没有同名工具时
     * `androidApiTools()` 返回的列表仍会被上层按 registry.names 过滤掉。
     */
    val androidToolDefinitions: List<ToolDefinition> = emptyList(),
) {
    /** 转成 Anthropic Messages API 的 tools 形状：{name, description, input_schema}。 */
    fun apiTools(): List<ApiTool> = toolDefinitions.map { def ->
        ApiTool(
            name = def.name,
            description = def.description,
            inputSchema = def.parameters ?: JsonObject(emptyMap()),
        )
    }

    /**
     * 动态拼装 system：按 order/name 排序，过滤掉「依赖的工具不在当前工具集」与「平台不符」的段，
     * `{{model}}` / `{{provider}}` 插值，空段丢弃，`join("\n\n")`（与上游 renderPrompt 一致，无结尾换行）。
     *
     * [planMode] 为 true 时插入 `plan:policy` 段（上游 SYSTEM section order 500，在 persona 之后、
     * file-reference 之前；见 `SECTION_ORDERS.PLAN_POLICY`）。
     */
    fun systemFor(
        model: String,
        provider: String,
        tools: Set<String>,
        platform: String = "android",
        planMode: Boolean = false,
    ): String {
        val all = if (planMode && planPolicy.isNotEmpty()) {
            sections + PromptSection(name = "plan:policy", order = PLAN_POLICY_ORDER, text = planPolicy)
        } else {
            sections
        }
        return all
            .filter { it.platform == "any" || it.platform == platform }
            .filter { it.requires == null || it.requires in tools }
            .sortedWith(compareBy({ it.order }, { it.name }))
            .map { render(it.text, model, provider) }
            .filter { it.isNotEmpty() }
            .joinToString("\n\n")
    }

    private fun render(text: String, model: String, provider: String): String =
        text.replace("{{model}}", model).replace("{{provider}}", provider)

    /**
     * Android 工具表：上游桌面版第 15 个工具是 `pwsh`，Android 换成 `bash`
     * （docs/port/02 §3.3：只改 name、description、properties.description.description、
     * properties.command.description 四处，键序与 required 不动）。
     */
    fun androidApiTools(): List<ApiTool> {
        val base = apiTools().map { tool ->
            if (tool.name != "pwsh") tool
            else tool.copy(
                name = "bash",
                description = BASH_DESCRIPTION,
                inputSchema = rewriteBashSchema(tool.inputSchema),
            )
        }
        // 设备工具追加在 32 个上游工具之后（registry 里没注册就会被上层过滤掉）
        val device = androidToolDefinitions.map { def ->
            ApiTool(
                name = def.name,
                description = def.description,
                inputSchema = def.parameters ?: JsonObject(emptyMap()),
            )
        }
        return base + device
    }

    private fun rewriteBashSchema(schema: JsonObject): JsonObject {
        val properties = schema["properties"] as? JsonObject ?: return schema
        val newProperties = kotlinx.serialization.json.buildJsonObject {
            properties.forEach { (key, value) ->
                when (key) {
                    "description" -> put(key, replaceDescription(value, BASH_ARG_DESCRIPTION))
                    "command" -> put(key, replaceDescription(value, BASH_COMMAND_DESCRIPTION))
                    else -> put(key, value)
                }
            }
        }
        return kotlinx.serialization.json.buildJsonObject {
            schema.forEach { (key, value) ->
                if (key == "properties") put(key, newProperties) else put(key, value)
            }
        }
    }

    private fun replaceDescription(value: kotlinx.serialization.json.JsonElement, text: String): kotlinx.serialization.json.JsonElement {
        val obj = value as? JsonObject ?: return value
        return kotlinx.serialization.json.buildJsonObject {
            obj.forEach { (k, v) ->
                if (k == "description") put(k, kotlinx.serialization.json.JsonPrimitive(text)) else put(k, v)
            }
        }
    }

    companion object {
        /** 当前打包的上游版本。 */
        const val CURRENT_VERSION = "0.2.2-rc.1"

        /** 上游 `SECTION_ORDERS.PLAN_POLICY`（plan:policy 段的位置：persona 之后、file-reference 之前）。 */
        const val PLAN_POLICY_ORDER = 500

        /** docs/port/02 §3.3 bash 段全文（Android 专用，逐字来自 dsh-tool-bash）。 */
        private const val BASH_DESCRIPTION =
            "Execute a bash command (`bash -c`) and return its stdout/stderr. Each call runs in a fresh shell; " +
                "pass `workdir` instead of using `cd`. Managed `\$DSH_*` variables expose current harness environment facts. " +
                "Long output is truncated to its tail; the full output is saved to a file whose path is reported when available. " +
                "Provide `description` before `command` in the arguments. Before any delete or move, verify that the resolved absolute target path is the intended one; " +
                "never run it against a computed path you have not checked. An unset variable expands to an empty string, so guard variables in such paths with `\${VAR:?}`. " +
                "Commands may run under a file sandbox; a blocked file operation is reported as `[sandbox: file access denied under <mode> mode]`, a policy denial: do not retry another way."

        private const val BASH_ARG_DESCRIPTION =
            "Clear, concise description of what this command does in active voice, 5-10 words (shown in the UI). " +
                "Examples: \"ls\" → \"List files in current directory\"; \"git status\" → \"Show working tree status\"; " +
                "\"npm install\" → \"Install package dependencies\"."

        private const val BASH_COMMAND_DESCRIPTION = "The bash command to execute."

        private val json = Json { ignoreUnknownKeys = true }

        fun load(version: String = CURRENT_VERSION): PromptPack {
            val base = "prompt/$version"
            val system = readResource("$base/system.txt")
            val toolsRaw = readResource("$base/tools.json")
            val tools = runCatching {
                json.decodeFromString(ListSerializer(ToolDefinition.serializer()), toolsRaw)
            }.getOrElse { emptyList() }
            val sections = runCatching {
                json.decodeFromString(SectionsFile.serializer(), readResource("$base/sections.json")).sections
            }.getOrElse { emptyList() }
            // 计划模式段是部署配置（非 system.txt 的一部分）：随版本存成单独资源，按需渲染
            val planPolicy = runCatching { readResource("$base/plan-policy.txt").trimEnd('\n') }.getOrElse { "" }
            // Android 设备工具（Operit 移植；不是上游 32 个工具的一部分）
            val androidTools = runCatching {
                json.decodeFromString(ListSerializer(ToolDefinition.serializer()), readResource("$base/tools-android.json"))
            }.getOrElse { emptyList() }
            return PromptPack(version, system, tools, sections, planPolicy, androidTools)
        }

        private fun readResource(path: String): String {
            val stream = PromptPack::class.java.classLoader?.getResourceAsStream(path)
                ?: error("missing prompt resource: $path")
            return stream.use { it.readBytes().toString(Charsets.UTF_8) }
        }
    }
}

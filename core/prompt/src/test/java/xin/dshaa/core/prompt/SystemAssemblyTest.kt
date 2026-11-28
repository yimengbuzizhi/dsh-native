package xin.dshaa.core.prompt

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 契约 #1 的 system 段拼装对拍：
 * 用 sections.json 按桌面平台 + 全部 32 个工具拼回来，必须与桌面快照 `system.txt` **逐字节相同**。
 *
 * 这条测试是「动态拼装」正确性的根：拼不回原快照，说明分段/order/插值有错。
 */
class SystemAssemblyTest {

    private val pack = PromptPack.load()

    @Test
    fun desktopSnapshotRoundTrips() {
        val desktopTools = pack.toolDefinitions.map { it.name }.toSet()
        val assembled = pack.systemFor(
            model = "deepseek-flash",
            provider = "deepseek",
            tools = desktopTools,
            platform = "desktop",
        )
        assertEquals(pack.desktopSystemSnapshot, assembled)
    }

    @Test
    fun androidSystemDropsUnimplementedTools() {
        val androidTools = setOf("read", "write", "edit", "glob", "grep", "bash", "todo_write")
        val system = pack.systemFor(model = "deepseek-flash", provider = "deepseek-account", tools = androidTools)

        // 只保留真正发出去的工具段：identity/persona/file-reference/bash/read/write/edit/glob/grep
        assertTrue(system.startsWith("You are an AI agent powered by DeepSeek Harness."))
        assertTrue("Check the [exit code: N] marker" in system)
        assertTrue("Use the read tool" in system)
        assertTrue("Use the grep tool" in system)
        // 没实现的工具段不能出现（否则模型会以为有 web_search / goal / workflow / 子代理）
        listOf("web_search results are external", "create_goal may infer", "Use the workflow tool", "Start independent delegations").forEach {
            assertTrue(it !in system, "should not contain: $it")
        }
        // 桌面专属段也不能出现
        assertTrue("DeepSeek Harness Web GUI" !in system)
        assertTrue("implementation checkout" !in system)
    }

    @Test
    fun personaUsesRuntimeModel() {
        val system = pack.systemFor(
            model = "deepseek-v4-pro",
            provider = "deepseek",
            tools = setOf("read"),
        )
        assertTrue("You are a coding agent powered by the deepseek-v4-pro model." in system)
    }

    @Test
    fun goalSectionAppearsOnceGoalToolsAreAvailable() {
        // require=create_goal 的「tool:goal」段：注册工具后必须自动拼进来（与上游动态挂载一致）
        val withGoal = pack.systemFor(
            model = "deepseek-flash",
            provider = "deepseek-account",
            tools = setOf("create_goal", "get_goal", "update_goal"),
        )
        assertTrue("create_goal may infer goal intent" in withGoal)

        val withoutGoal = pack.systemFor(
            model = "deepseek-flash",
            provider = "deepseek-account",
            tools = setOf("read", "write"),
        )
        assertTrue("create_goal may infer goal intent" !in withoutGoal)
    }

    @Test
    fun jobSectionAppearsOnceJobToolsAreAvailable() {
        // require=job_list 的「tool:jobs」段：注册 job_* 三件套后必须自动拼进来，
        // 文案与桌面快照逐字一致（docs/32）。
        val withJobs = pack.systemFor(
            model = "deepseek-flash",
            provider = "deepseek-account",
            tools = setOf("job_output", "job_list", "job_kill"),
        )
        assertTrue("Track every background job id you start." in withJobs)
        assertTrue("do not busy-poll or sleep on one" in withJobs)

        val withoutJobs = pack.systemFor(
            model = "deepseek-flash",
            provider = "deepseek-account",
            tools = setOf("read", "write"),
        )
        assertTrue("Track every background job id you start." !in withoutJobs)
    }

    @Test
    fun subagentSectionAppearsOnceDelegationToolsAreAvailable() {
        // require=subagent 的「tool:subagent」段：注册委派工具后自动拼进来（docs/33）。
        val withAgents = pack.systemFor(
            model = "deepseek-flash",
            provider = "deepseek-account",
            tools = setOf("subagent", "subagent_fork", "list_agents", "send_message", "interrupt_agent"),
        )
        assertTrue("Start independent delegations with `subagent` or `subagent_fork`" in withAgents)
        assertTrue("continue useful work while they run" in withAgents)

        val withoutAgents = pack.systemFor(
            model = "deepseek-flash",
            provider = "deepseek-account",
            tools = setOf("read", "write"),
        )
        assertTrue("Start independent delegations" !in withoutAgents)
    }

    /** M71 角色卡：人设段替换默认 persona 前缀，位置仍在 harness:identity 之后、file-reference 之前。 */
    @Test
    fun personaCardReplacesDefaultPersonaPrefix() {
        val system = pack.systemFor(
            model = "deepseek-flash",
            provider = "deepseek-account",
            tools = setOf("read"),
            personaPrefix = "你的名字是「鲸歌」。\n\n你是一只住在手机里的鲸鱼。",
        )
        assertTrue("你的名字是「鲸歌」。" in system)
        assertTrue("你是一只住在手机里的鲸鱼。" in system)
        assertTrue("You are a coding agent powered by" !in system)
        val identity = system.indexOf("You are an AI agent powered by DeepSeek Harness.")
        val persona = system.indexOf("你的名字是「鲸歌」。")
        val fileRef = system.indexOf("Tokens prefixed with @ are paths")
        assertTrue(identity >= 0 && persona > identity && fileRef > persona)
    }

    /** M71 角色卡：系统提示词落在 deployment:persona-suffix（order 10200），必须是全文最后一段。 */
    @Test
    fun personaSuffixIsTheLastSection() {
        val system = pack.systemFor(
            model = "deepseek-flash",
            provider = "deepseek-account",
            tools = setOf("read", "write"),
            personaSuffix = "回答尽量简短，不要用感叹号。",
        )
        assertTrue(system.endsWith("回答尽量简短，不要用感叹号。"))
    }

    /**
     * M71：插件 `ctx.systemPrompt.section` 和宿主段一起按 order 排序；同名段插件覆盖角色卡
     * （DSH 同一个段名只有一份，插件的人设走同一条路）。
     */
    @Test
    fun pluginSectionsJoinTheSameAssemblyAndOverrideBySameName() {
        val pluginSections = listOf(
            PromptSection("plugin:early", 1, text = "PLUGIN-EARLY"),
            PromptSection(PromptPack.PERSONA_PREFIX, 0, text = "PLUGIN-PERSONA"),
        )
        val system = pack.systemFor(
            model = "deepseek-flash",
            provider = "deepseek-account",
            tools = setOf("read"),
            personaPrefix = "USER-PERSONA",
            extraSections = pluginSections,
        )
        assertTrue("PLUGIN-EARLY" in system)
        assertTrue("PLUGIN-PERSONA" in system)
        assertTrue("USER-PERSONA" !in system, "同名段只保留后注册的")
        // order 1 的插件段紧跟 persona(0)，在 file-reference(900) 之前
        val persona = system.indexOf("PLUGIN-PERSONA")
        val early = system.indexOf("PLUGIN-EARLY")
        val fileRef = system.indexOf("Tokens prefixed with @ are paths")
        assertTrue(persona >= 0 && early > persona && fileRef > early)
    }

    /**
     * M76（用户第 3 条）：长任务播报约束是独立段 `app:turn-briefing`（order 10150），
     * 只在 Android 生效——桌面快照逐字节对拍不能被改；位置在 persona-suffix(10200) 之前。
     */
    @Test
    fun turnBriefingSectionIsAndroidOnlyAndBeforePersonaSuffix() {
        val tools = setOf("read", "write")
        val android = pack.systemFor(
            model = "deepseek-flash",
            provider = "deepseek-account",
            tools = tools,
            personaSuffix = "回答尽量简短。",
        )
        assertTrue("Before each batch of tool calls" in android)
        assertTrue("When a tool fails" in android)
        assertTrue("End every turn with a status" in android)
        assertTrue(android.endsWith("回答尽量简短。"), "播报段不能排在 persona-suffix 之后")

        val desktopTools = pack.toolDefinitions.map { it.name }.toSet()
        val desktop = pack.systemFor(
            model = "deepseek-flash",
            provider = "deepseek",
            tools = desktopTools,
            platform = "desktop",
        )
        assertTrue("Before each batch of tool calls" !in desktop, "桌面平台不注入播报段")
    }

    /** 没配角色卡时拼装结果必须和上游快照完全一致（默认人设段不被改动）。 */
    @Test
    fun emptyPersonaCardKeepsUpstreamSnapshot() {
        val desktopTools = pack.toolDefinitions.map { it.name }.toSet()
        val assembled = pack.systemFor(
            model = "deepseek-flash",
            provider = "deepseek",
            tools = desktopTools,
            platform = "desktop",
            personaPrefix = "",
            personaSuffix = "  ",
            extraSections = emptyList(),
        )
        assertEquals(pack.desktopSystemSnapshot, assembled)
    }

    /**
     * docs/190-E：`includeBuiltinSections=false`（关掉「提示词：鲸歌内置规则」）时
     * 打包 sections/plan:policy 一段不剩，但用户人设/系统提示词/插件段仍按 order 拼好。
     */
    @Test
    fun builtinSectionsCanBeDisabledWithoutLosingUserContent() {
        val plugin = PromptSection("plugin:extra", 7_000, text = "PLUGIN-EXTRA")
        val off = pack.systemFor(
            model = "deepseek-flash",
            provider = "deepseek-account",
            tools = setOf("read", "write", "bash", "start_virtual_display", "create_goal"),
            planMode = true,
            personaPrefix = "USER-PERSONA",
            personaSuffix = "USER-SYSTEM",
            extraSections = listOf(plugin),
            includeBuiltinSections = false,
        )
        // 内置段一个不剩（identity / file-reference / 各 tool 段 / plan:policy / Android 专属段）
        assertTrue("You are an AI agent powered by DeepSeek Harness." !in off)
        assertTrue("Tokens prefixed with @ are paths" !in off)
        assertTrue("Use the read tool" !in off)
        assertTrue("You are in plan mode." !in off)
        assertTrue("create a virtual display first" !in off)
        assertTrue("Before each batch of tool calls" !in off)
        // 用户/插件内容仍在，顺序 persona(0) < 插件段(7000) < system suffix(10200)
        assertTrue("USER-PERSONA" in off)
        assertTrue("PLUGIN-EXTRA" in off)
        assertTrue(off.endsWith("USER-SYSTEM"))
        val persona = off.indexOf("USER-PERSONA")
        val extra = off.indexOf("PLUGIN-EXTRA")
        val suffix = off.indexOf("USER-SYSTEM")
        assertTrue(persona >= 0 && extra > persona && suffix > extra)

        // 默认（不传参数）= 打开，和桌面快照逐字节一致——默认行为不能被这个开关改掉。
        val desktopTools = pack.toolDefinitions.map { it.name }.toSet()
        assertEquals(
            pack.desktopSystemSnapshot,
            pack.systemFor(
                model = "deepseek-flash",
                provider = "deepseek",
                tools = desktopTools,
                platform = "desktop",
                includeBuiltinSections = true,
            ),
        )
    }

    @Test
    fun planPolicySectionOnlyRendersInPlanMode() {        val tools = setOf("read", "write", "exit_plan_mode")
        val normal = pack.systemFor(model = "deepseek-flash", provider = "deepseek-account", tools = tools)
        val plan = pack.systemFor(
            model = "deepseek-flash",
            provider = "deepseek-account",
            tools = tools,
            planMode = true,
        )
        assertTrue("You are in plan mode." !in normal)
        assertTrue("You are in plan mode." in plan)

        // 位置：persona(0) 之后、file-reference(900) 之前（上游 SECTION_ORDERS.PLAN_POLICY = 500）
        val persona = plan.indexOf("You are a coding agent powered by the")
        val policy = plan.indexOf("You are in plan mode.")
        val fileRef = plan.indexOf("Tokens prefixed with @ are paths")
        assertTrue(persona >= 0 && policy > persona && fileRef > policy)
        // 逐字取自桌面 preset（不是自己编的文案）
        assertTrue("Make exit_plan_mode the only and final tool call in that assistant response" in plan)
        assertTrue("Do not use todo_write to track this planning phase" in plan)
    }

    /**
     * M74：插件 `ctx.systemPrompt.variable(name, fn)` + 段文本 `{{name}}`。
     * 已知变量替换成真实文本；`{{model}}`/`{{provider}}` 照旧；**未知变量原样保留**
     * （不静默清空，出问题能一眼看出来）。
     */
    @Test
    fun pluginVariablesReplacePlaceholdersAndUnknownOnesSurvive() {
        val pluginSections = listOf(
            PromptSection(
                name = "cosplay:persona",
                order = 1,
                text = "你现在在扮演 {{character}}（{{cosplay_active}}）；模型 {{model}} / {{provider}}；未知 {{nope}}。",
            ),
        )
        val system = pack.systemFor(
            model = "deepseek-flash",
            provider = "deepseek-account",
            tools = setOf("read"),
            extraSections = pluginSections,
            variables = mapOf("character" to "初音未来", "cosplay_active" to "true"),
        )
        assertTrue("你现在在扮演 初音未来（true）" in system)
        assertTrue("deepseek-flash / deepseek-account" in system)
        assertTrue("{{nope}}" in system, "未知变量必须原样保留")
        assertTrue("{{character}}" !in system)
    }

    /**
     * AI 终端工具（docs/77）：6 个工具名/参数逐字对齐 Operit，且都进了 Android 工具表
     * （`androidApiTools()` 追加 `tools-android.json`；上层再按 registry.names 过滤）。
     */
    @Test
    fun androidToolPackExposesTerminalSessions() {
        val tools = pack.androidApiTools().associateBy { it.name }
        val expected = mapOf(
            "create_terminal_session" to setOf("session_name"),
            "execute_in_terminal_session" to setOf("session_id", "command", "timeout_ms"),
            // M76：identity=app|shell|root（shell 走 Shizuku，root 走 su），schema 与真正的工具参数一致
            "execute_hidden_terminal_command" to setOf("command", "executor_key", "timeout_ms", "identity"),
            "input_in_terminal_session" to setOf("session_id", "input", "control"),
            "close_terminal_session" to setOf("session_id"),
            "get_terminal_session_screen" to setOf("session_id"),
        )
        expected.forEach { (name, params) ->
            val tool = tools[name] ?: error("missing Android tool: $name")
            val properties = tool.inputSchema["properties"]?.jsonObject?.keys.orEmpty()
            assertEquals(params, properties, "parameters of $name")
            assertTrue(!tool.description.isNullOrBlank(), "description of $name")
        }
        // 上游 32 个 + 29 个 Android 设备/终端/记忆/能力/HTTP 工具
        // （18 设备终端 + 8 记忆，M56 追加；M88-C 追加 capability/notify 2 个；M88-D 追加 http_request）
        // + P1/P3 的 20 个可用原语（PrimitiveCatalog 生成；P3-e 起 ui.openContainer 就绪）
        assertEquals(32 + 29 + 20, tools.size)
    }

    /**
     * bash 段的 Android 事实（2026-11-20，用户反馈「bash 工具箱不全」）：
     * 文案不能再宣称 `bash -c` / 分级沙箱（设备上没有），`env` / `stdin` 要真的进 schema，
     * 而 `required` 与上游保持同形（description + command），否则模型侧调用形态会变。
     */
    @Test
    fun androidBashSchemaMatchesDeviceFacts() {
        val bash = pack.androidApiTools().first { it.name == "bash" }
        val props = bash.inputSchema["properties"]?.jsonObject ?: error("bash 没有 properties")
        assertEquals("object", props["env"]?.jsonObject?.get("type")?.jsonPrimitive?.content)
        assertEquals("string", props["stdin"]?.jsonObject?.get("type")?.jsonPrimitive?.content)
        assertEquals(
            listOf("description", "command"),
            bash.inputSchema["required"]?.jsonArray?.map { it.jsonPrimitive.content },
            "required 不能变（上游同形）",
        )
        val description = bash.description.orEmpty()
        assertTrue(description.contains("bundled GNU Bash 5.3"), "应说明 arm64 真机内置 GNU Bash 5.3：$description")
        assertTrue(description.contains("curl"), "应说明内置 curl：$description")
        assertTrue(description.contains("/system/bin/sh"), "应说明 x86_64 退回 /system/bin/sh：$description")
        assertTrue("no graded sandbox" in description, "应说明没有分级沙箱：$description")
    }

    /**
     * 「虚拟屏优先」段（device:virtual-screen）：
     * - requires=start_virtual_display，工具不在时整段不渲染（PromptPack 的既有机制）；
     * - platform=android，桌面快照对拍不受影响；
     * - 必须在 Android system 里出现，且明确「先开虚拟屏、再传 display」。
     */
    @Test
    fun virtualScreenSectionRequiresTheStartToolAndOnlyRendersOnAndroid() {
        val marker = "create a virtual display first"

        val withTool = pack.systemFor(
            model = "deepseek-flash",
            provider = "deepseek-account",
            tools = setOf("start_virtual_display", "tap", "capture_screenshot"),
        )
        assertTrue(marker in withTool, "有 start_virtual_display 时必须渲染虚拟屏段")
        assertTrue("pass the returned display id as `display`" in withTool)
        assertTrue("close_all_virtual_displays" in withTool)

        val withoutTool = pack.systemFor(
            model = "deepseek-flash",
            provider = "deepseek-account",
            tools = setOf("read", "write", "bash"),
        )
        assertTrue(marker !in withoutTool, "没有 start_virtual_display 时不能渲染虚拟屏段")

        val desktop = pack.systemFor(
            model = "deepseek-flash",
            provider = "deepseek",
            tools = pack.toolDefinitions.map { it.name }.toSet() + "start_virtual_display",
            platform = "desktop",
        )
        assertTrue(marker !in desktop, "桌面平台不渲染 Android 专属段")
    }

    /**
     * docs/190-B：`device:node-admin` 段（order 10160，Android）。
     * - requires=nodehost_doctor：工具不在时不渲染（桌面快照/默认拼装不受影响）；
     * - 只在 Android 出现，且明确「先 doctor 再动手」+ 不允许改插件 JS；
     * - 排在 persona-suffix(10200) 之前（用户系统提示词仍是最后一段）。
     */
    @Test
    fun nodeAdminSectionRequiresTheDoctorToolAndOnlyRendersOnAndroid() {
        val marker = "start with `nodehost_doctor`"
        val withTools = pack.systemFor(
            model = "deepseek-flash",
            provider = "deepseek-account",
            tools = setOf("nodehost_doctor", "nodehost_control", "read"),
            personaSuffix = "回答尽量简短。",
        )
        assertTrue(marker in withTools, "有 nodehost_doctor 时必须渲染 Node 管理段")
        assertTrue("Never edit a plugin's JavaScript" in withTools)
        assertTrue("nodehost_autofix" in withTools)
        assertTrue(withTools.endsWith("回答尽量简短。"), "Node 管理段不能排在 persona-suffix 之后")

        val withoutTools = pack.systemFor(
            model = "deepseek-flash",
            provider = "deepseek-account",
            tools = setOf("read", "write", "bash"),
        )
        assertTrue(marker !in withoutTools, "没有 nodehost_doctor 时不能渲染 Node 管理段")

        val desktop = pack.systemFor(
            model = "deepseek-flash",
            provider = "deepseek",
            tools = pack.toolDefinitions.map { it.name }.toSet() + "nodehost_doctor",
            platform = "desktop",
        )
        assertTrue(marker !in desktop, "桌面平台不渲染 Android 专属段")
    }
}

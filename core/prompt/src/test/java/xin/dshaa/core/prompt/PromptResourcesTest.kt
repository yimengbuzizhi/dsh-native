package xin.dshaa.core.prompt

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import xin.dshaa.core.primitives.PrimitiveCatalog
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * prompt 资源完整性（M69 修复的回归测试）。
 *
 * 2026-11-10 发现 `tools-android.json` 在 `get_terminal_session_screen` 与 `query_memory`
 * 之间少一个 `},`：`PromptPack.load` 用 `runCatching` 解析，失败就返回空表，
 * 于是 **M56 之后 device/terminal/memory 工具的 schema 一直没发给模型**（registry 里有、
 * 请求 tools 里没有）。这个测试保证 4 份资源都能解析、Android 工具名还在。
 */
class PromptResourcesTest {

    @Test
    fun androidToolSchemasAreLoadable() {
        val names = PromptPack.load().androidToolDefinitions.map { it.name }.toSet()
        assertTrue("device_info" in names, "缺 device_info：tools-android.json 可能又解析失败了")
        assertTrue("query_memory" in names, "缺 query_memory：记忆工具没有发给模型")
        assertTrue("create_terminal_session" in names, "缺终端工具")
        assertTrue(names.size >= 25, "Android 工具数量异常：${names.size}")
    }

    @Test
    fun upstreamToolTableStillParses() {
        val names = PromptPack.load().toolDefinitions.map { it.name }.toSet()
        assertTrue("read" in names)
        assertTrue("bash" !in names && "pwsh" in names, "上游表里应是 pwsh，Android 侧才改名 bash")
        assertTrue(names.size >= 30)
    }

    @Test
    fun primitiveToolSchemasComeFromTheCatalog() {
        // P1（docs/185）：原语 schema 不再手写 JSON，由 core:primitives 的 PrimitiveCatalog 生成；
        // 这里锁住「工具面真的出现在 androidApiTools() 里」且必需参数与目录一致。
        val tools = PromptPack.load().androidApiTools().associateBy { it.name }
        assertEquals(20, PrimitiveCatalog.available.size)
        PrimitiveCatalog.available.forEach { definition ->
            val tool = tools[PrimitiveCatalog.toolName(definition.id)]
            assertTrue(tool != null, "missing primitive tool: ${definition.id}")
            assertEquals(definition.parameters, tool!!.inputSchema)
            assertTrue(!tool.description.isNullOrBlank(), "description of ${definition.id}")
        }
        assertEquals(
            setOf("model"),
            tools.getValue("model_set").inputSchema["required"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet(),
        )
        assertEquals(
            setOf("effort"),
            tools.getValue("effort_set").inputSchema["required"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet(),
        )
        // P3-e：ui.openContainer 从「如实标 P3」变成就绪 —— 它现在真的在工具表里，
        // 且 kind 只允许三种容器（红线：不做 slot / 会话流内嵌）。
        val container = tools.getValue("ui_openContainer")
        assertEquals(
            setOf("fullscreen", "floating", "sheet"),
            container.inputSchema["properties"]!!.jsonObject["kind"]!!.jsonObject["enum"]!!
                .jsonArray.map { it.jsonPrimitive.content }.toSet(),
        )
    }

    /**
     * 「虚拟屏优先」提示词段与工具描述必须一起存在：
     * 段靠 requires 过滤（PromptPack），描述负责在工具表里给出同样的第一句指引。
     */
    @Test
    fun virtualScreenSectionAndToolDescriptionPointTheSameWay() {
        val section = PromptPack.load().sections.first { it.name == "device:virtual-screen" }
        assertEquals("android", section.platform)
        assertEquals("start_virtual_display", section.requires)
        assertTrue(section.text.contains("create a virtual display first"), section.text)
        assertTrue(section.text.contains("close_all_virtual_displays"), section.text)

        val tool = PromptPack.load().androidToolDefinitions.first { it.name == "start_virtual_display" }
        val description = tool.description.orEmpty()
        assertTrue(description.contains("Call this FIRST"), description)
        assertTrue(description.contains("pass that id as `display`"), description)
    }
}

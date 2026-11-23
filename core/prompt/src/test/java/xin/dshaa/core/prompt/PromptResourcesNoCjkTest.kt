package xin.dshaa.core.prompt

import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * docs/190-G：模型可见文本全英文（硬约束）。
 *
 * 覆盖两处：
 * 1. `prompt/0.2.2-rc.1/` 下所有会被拼进 system / tools 的资源文件；
 * 2. 通过 [PromptPack] 暴露给模型的工具元数据（description + JSON schema 里的 description）。
 *
 * 中文注释/日志/UI 不在这条测试的范围里（它们不在模型输入中）。
 */
class PromptResourcesNoCjkTest {

    private val cjk = Regex("[\\u2E80-\\u9FFF\\uFF00-\\uFFEF]")

    private fun resource(path: String): String {
        val stream = PromptResourcesNoCjkTest::class.java.classLoader
            ?.getResourceAsStream(path) ?: error("missing prompt resource: $path")
        return stream.use { it.readBytes().toString(Charsets.UTF_8) }
    }

    @Test
    fun promptResourceFilesContainNoCjk() {
        listOf("system.txt", "sections.json", "tools.json", "tools-android.json", "plan-policy.txt").forEach { name ->
            val text = resource("prompt/${PromptPack.CURRENT_VERSION}/$name")
            val hit = cjk.find(text)
            assertTrue(hit == null, "$name 含中文（模型可见）：${hit?.value} @ ${hit?.range}")
        }
    }

    @Test
    fun assembledToolMetadataContainsNoCjk() {
        val pack = PromptPack.load()
        // 上游 32 个工具 + Android 设备/终端 + P1 原语（PrimitiveCatalog 生成）
        pack.androidApiTools().forEach { tool ->
            assertNoCjk(tool.description.orEmpty(), "tool ${tool.name} description")
            assertNoCjkInSchema(tool.inputSchema, "tool ${tool.name} schema")
        }
        pack.toolDefinitions.forEach { def ->
            assertNoCjk(def.description.orEmpty(), "toolDefinition ${def.name} description")
            def.parameters?.let { assertNoCjkInSchema(it, "toolDefinition ${def.name} schema") }
        }
        pack.androidToolDefinitions.forEach { def ->
            assertNoCjk(def.description.orEmpty(), "androidToolDefinition ${def.name} description")
            def.parameters?.let { assertNoCjkInSchema(it, "androidToolDefinition ${def.name} schema") }
        }
    }

    @Test
    fun systemSectionsContainNoCjk() {
        val pack = PromptPack.load()
        pack.sections.forEach { section ->
            val hit = cjk.find(section.text)
            assertTrue(hit == null, "section ${section.name} 含中文：${hit?.value} @ ${hit?.range}")
        }
        assertTrue(cjk.find(pack.planPolicy) == null, "plan-policy 含中文")
    }

    private fun assertNoCjk(text: String, where: String) {
        val hit = cjk.find(text)
        assertTrue(hit == null, "$where 含中文：${hit?.value} @ ${hit?.range}")
    }

    /** 递归检查 JSON schema 里所有 `description` 字段（含嵌套 items/properties）。 */
    private fun assertNoCjkInSchema(element: JsonElement, where: String) {
        when (element) {
            is JsonObject -> element.forEach { (key, value) ->
                if (key == "description" && value is JsonPrimitive) {
                    assertNoCjk(value.content, "$where/$key")
                } else {
                    assertNoCjkInSchema(value, "$where/$key")
                }
            }

            is JsonArray -> element.forEachIndexed { index, value ->
                assertNoCjkInSchema(value, "$where[$index]")
            }

            else -> Unit
        }
    }
}

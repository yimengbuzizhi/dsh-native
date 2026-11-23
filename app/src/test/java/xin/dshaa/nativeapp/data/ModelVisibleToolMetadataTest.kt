package xin.dshaa.nativeapp.data

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * docs/190-G：模型可见工具元数据（description / 参数 description）不得含 CJK。
 *
 * 这里覆盖 app 侧在 Kotlin 里定义的工具（`plugin_dev_*`）；prompt 资源与上游工具表由
 * `core:prompt` 的 `PromptResourcesNoCjkTest` 覆盖。工具名/参数键名不在检查范围（不是自然语言）。
 */
class ModelVisibleToolMetadataTest {

    private val cjk = Regex("[\\u2E80-\\u9FFF\\uFF00-\\uFFEF]")

    @Test
    fun pluginDevToolMetadataIsEnglish() {
        PluginDevTools.definitions.forEach { tool ->
            assertNoCjk(tool.description.orEmpty(), "${tool.name}.description")
            assertNoCjkInSchema(tool.inputSchema, "${tool.name}.inputSchema")
        }
    }

    private fun assertNoCjk(text: String, where: String) {
        val hit = cjk.find(text)
        assertTrue("$where 含中文（模型可见）：${hit?.value} @ ${hit?.range}", hit == null)
    }

    private fun assertNoCjkInSchema(element: JsonElement, where: String) {
        when (element) {
            is JsonObject -> element.forEach { (key, value) ->
                if (key == "description" && value is JsonPrimitive) {
                    assertNoCjk(value.content, "$where/$key")
                } else {
                    assertNoCjkInSchema(value, "$where/$key")
                }
            }

            is JsonArray -> element.forEachIndexed { index, value -> assertNoCjkInSchema(value, "$where[$index]") }
            else -> Unit
        }
    }
}

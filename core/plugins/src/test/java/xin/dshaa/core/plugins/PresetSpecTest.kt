package xin.dshaa.core.plugins

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * P3-d：预设是**纯数据**（模型 + 提示词段 + 工具 + 权限的组合）。
 *
 * 用例锁死：编解码往返、坏条目跳过但报错、slug 规则、权限名归一、
 * 以及「不写死插件」—— 同一份编码对任何插件 id 都成立。
 */
class PresetSpecTest {

    @Test
    fun encodeDecodeRoundTrip_keepsEveryField() {
        val preset = PluginPreset(
            id = "coding",
            name = "写代码",
            createdAt = 1_000,
            updatedAt = 2_000,
            modelProviderId = "node:hello-node-follow",
            modelLabel = "Node 演示",
            promptSections = listOf("hello-plugin/greeting", "native/tool-hints"),
            tools = listOf("bash", "write", "node_hello_probe", "session_list"),
            permissions = mapOf(
                "@dsh-external/dsh-client-ui-pet" to "fs:workspace,ui:overlay",
                " " to "fs:ignored",
            ),
            note = "少给点工具",
        )
        val text = PresetCodec.encode(listOf(preset), activeId = "coding")
        val decoded = PresetCodec.decode(text)
        assertTrue(decoded.errors.isEmpty(), decoded.errors.joinToString())
        assertEquals("coding", decoded.activeId)
        val back = decoded.presets.single()
        assertEquals(preset.copy(permissions = mapOf("@dsh-external/dsh-client-ui-pet" to "fs:workspace,ui:overlay")), back)
        assertTrue(back.summary().contains("工具：4"))
        assertTrue(back.summary().contains("模型：Node 演示"))
    }

    @Test
    fun decodeBadJson_returnsErrorNotEmptyCrash() {
        val decoded = PresetCodec.decode("{ this is not json")
        assertTrue(decoded.presets.isEmpty())
        assertEquals(1, decoded.errors.size)
        assertNull(decoded.activeId)
    }

    @Test
    fun decodeSkipsBadEntries_butKeepsGoodOnes() {
        val text = """
            {"version":1,"active":null,"presets":[
              {"id":"a","name":"A","tools":["bash"]},
              "not-an-object",
              {"name":"只有名字"},
              {"id":"a","name":"A 新","tools":["write"]}
            ]}
        """.trimIndent()
        val decoded = PresetCodec.decode(text)
        assertEquals(listOf("a"), decoded.presets.map { it.id })
        val first = decoded.presets.first { it.id == "a" }
        assertEquals("A 新", first.name)
        assertEquals(listOf("write"), first.tools)
        assertTrue(decoded.errors.any { it.contains("不是对象") })
        assertTrue(decoded.errors.any { it.contains("重复") })
        assertTrue(decoded.errors.any { it.contains("没有 id/名字") })
    }

    @Test
    fun newerVersion_isReadWithWarning() {
        val decoded = PresetCodec.decode("""{"version":9,"presets":[]}""")
        assertTrue(decoded.presets.isEmpty())
        assertTrue(decoded.errors.single().contains("版本 9"))
    }

    @Test
    fun slugRules() {
        assertEquals("hello-world", PresetCodec.slug("Hello World"))
        assertEquals("a-b", PresetCodec.slug("  a  b  "))
        assertNull(PresetCodec.slug("中文"), "纯非 ASCII 名字派生不出合法 id（调用方要报错，不能悄悄起个怪文件）")
        assertNull(PresetCodec.slug("   "))
        assertNull(PresetCodec.slug("--__--"))
        assertEquals(64, PresetCodec.slug("a".repeat(120))!!.length)
    }

    @Test
    fun permissionsAreNormalized_andEmptyOnesDropped() {
        val normalized = PresetCodec.normalizePermissions(
            mapOf(
                " pkg " to " fs:workspace , ,fs:workspace, ui:overlay ",
                "" to "fs:x",
                "empty" to ", ,",
            ),
        )
        assertEquals(mapOf("pkg" to "fs:workspace,ui:overlay"), normalized)
    }

    @Test
    fun summaryReadsLikeHuman() {
        val empty = PluginPreset(id = "x", name = "X")
        assertEquals("模型：跟随协议 · 提示词段：不限 · 工具：不限 · 权限：不动", empty.summary())
        val full = PluginPreset(
            id = "y",
            name = "Y",
            modelProviderId = "deepseek",
            promptSections = listOf("a"),
            tools = listOf("bash", "write"),
            permissions = mapOf("p" to "fs:workspace"),
        )
        assertTrue(full.summary().contains("模型：deepseek"))
        assertTrue(full.summary().contains("提示词段：1"))
        assertTrue(full.summary().contains("工具：2"))
        assertTrue(full.summary().contains("权限：1 个插件"))
    }
}

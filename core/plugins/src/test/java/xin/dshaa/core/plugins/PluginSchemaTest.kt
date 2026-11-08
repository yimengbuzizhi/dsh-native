package xin.dshaa.core.plugins

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class PluginSchemaTest {

    private fun schema(text: String) = Json.parseToJsonElement(text).jsonObject

    @Test
    fun keepsStandardJsonSchema() {
        val raw = schema(
            """{"type":"object","additionalProperties":false,
               "properties":{"text":{"type":"string"}},"required":["text"]}""",
        )
        assertEquals(raw, PluginSchema.normalize(raw))
    }

    @Test
    fun wrapsToolPkgShorthandAndMovesRequiredFlags() {
        val raw = schema("""{"city":{"type":"string","required":true},"days":{"type":"number"}}""")
        val normalized = PluginSchema.normalize(raw)
        assertNotNull(normalized)
        assertEquals("object", normalized["type"]?.jsonPrimitive?.content)
        val properties = normalized["properties"]!!.jsonObject
        assertEquals(setOf("city", "days"), properties.keys)
        assertNull(properties["city"]!!.jsonObject["required"])
        assertEquals("""["city"]""", normalized["required"].toString())
    }

    @Test
    fun missingParametersMeansNoArgumentTool() {
        val normalized = PluginSchema.normalize(null)
        assertEquals("object", normalized?.get("type")?.jsonPrimitive?.content)
        assertEquals("{}", normalized?.get("properties").toString())
    }

    @Test
    fun rejectsNonSchemaShapesInsteadOfInventingOne() {
        // 认不出的形状不能硬造 schema
        assertNull(PluginSchema.normalize(schema("""{"some":"thing"}""")))
        assertNull(PluginSchema.parse("not json"))
        assertNull(PluginSchema.parse("""{"a":"b"}"""))
    }

    @Test
    fun convertsSchemasteryGraph() {
        val raw = schema(
            """{"uid":0,"refs":{
              "0":{"type":"object","meta":{"default":{}},"dict":{"text":2}},
              "2":{"type":"string","meta":{"required":true}}}}""",
        )
        assertEquals(
            """{"type":"object","properties":{"text":{"type":"string"}},"required":["text"]}""",
            PluginSchema.normalize(raw)?.toString(),
        )
    }

    @Test
    fun unsupportedSchemasteryStaysUnrecognized() {
        // date 没有对应的 JSON Schema 形状 → 不注入，而不是给一个空 schema
        assertNull(PluginSchema.normalize(schema("""{"uid":0,"refs":{"0":{"type":"date","meta":{}}}}""")))
    }

    @Test
    fun fillsDefaultsLikeSchemasteryResolve() {
        val params = schema(
            """{"type":"object","properties":{
                 "text":{"type":"string","default":"hi"},
                 "flag":{"type":"boolean","default":false},
                 "mode":{"enum":["fast","safe"],"default":"safe"},
                 "tags":{"type":"array","items":{"type":"string"}},
                 "opts":{"type":"object","properties":{"n":{"type":"number","default":7}}},
                 "extra":{"type":"string"}}}""",
        )
        val filled = PluginSchema.withDefaults(params, schema("""{"text":"given","opts":{}}"""))
        assertEquals(
            """{"text":"given","opts":{"n":7},"flag":false,"mode":"safe","tags":[]}""",
            filled.toString(),
        )
        // 用户/模型给的值不覆盖（text=given、opts 里已有键也不动）
        assertEquals(
            """{"text":"given","opts":{"n":1},"flag":true,"mode":"safe","tags":[]}""",
            PluginSchema.withDefaults(params, schema("""{"text":"given","opts":{"n":1},"flag":true}""")).toString(),
        )
        // 没有 default 的标量保持缺失（插件读到 undefined，和 schemastery 一致）
        assertNull(filled["extra"])
    }

    @Test
    fun computesEffectiveConfigAndKnownKeys() {
        val schemaText = """{"type":"object","properties":{
            "city":{"type":"string","default":"Shanghai"},
            "limit":{"type":"number","default":3},
            "nested":{"type":"object","properties":{"flag":{"type":"boolean","default":true}}}}}"""
        assertEquals(
            """{"city":"Shanghai","limit":3,"nested":{"flag":true}}""",
            PluginSchema.defaultsText(schemaText),
        )
        assertEquals(
            """{"city":"Beijing","limit":3,"nested":{"flag":true}}""",
            PluginSchema.effectiveText(schemaText, """{"city":"Beijing"}"""),
        )
        assertEquals(setOf("city", "limit", "nested"), PluginSchema.configKeys(schemaText))
        // 坏配置按空对象处理，不炸
        assertEquals(
            """{"city":"Shanghai","limit":3,"nested":{"flag":true}}""",
            PluginSchema.effectiveText(schemaText, "not json"),
        )
    }

    @Test
    fun validatesRequiredTypesEnumsAndNestedPaths() {
        val params = schema(
            """{"type":"object","properties":{
                 "text":{"type":"string"},
                 "count":{"type":"number"},
                 "mode":{"enum":["fast","safe"]},
                 "opts":{"type":"object","properties":{"n":{"type":"integer"}}}},
               "required":["text"]}""",
        )
        assertNull(PluginSchema.validate(params, schema("""{"text":"ok","count":1,"mode":"fast","opts":{"n":2}}""")))
        // 多给的键不拒绝（JSON Schema 默认 additionalProperties:true）
        assertNull(PluginSchema.validate(params, schema("""{"text":"ok","extra":true}""")))

        assertEquals("缺少必填参数：text", PluginSchema.validate(params, schema("""{}""")))
        assertEquals("text 类型不对：要 string，实际是 number", PluginSchema.validate(params, schema("""{"text":1}""")))
        assertEquals("mode 不在允许的值里：\"fast\"、\"safe\"", PluginSchema.validate(params, schema("""{"text":"x","mode":"other"}""")))
        assertEquals("opts.n 类型不对：要 integer，实际是 number", PluginSchema.validate(params, schema("""{"text":"x","opts":{"n":1.5}}""")))
    }

    @Test
    fun validatesNumericRangesAndStringLengths() {
        val params = schema(
            """{"type":"object","properties":{
                 "limit":{"type":"number","minimum":1,"maximum":10},
                 "name":{"type":"string","minLength":2,"maxLength":4}}}""",
        )
        assertNull(PluginSchema.validate(params, schema("""{"limit":1,"name":"ab"}""")))
        assertEquals("limit 不能小于 1", PluginSchema.validate(params, schema("""{"limit":0}""")))
        assertEquals("limit 不能大于 10", PluginSchema.validate(params, schema("""{"limit":11}""")))
        assertEquals("name 至少 2 个字符", PluginSchema.validate(params, schema("""{"name":"a"}""")))
        assertEquals("name 最多 4 个字符", PluginSchema.validate(params, schema("""{"name":"abcde"}""")))
    }

    @Test
    fun validatesStringPatternsAndArrayLengths() {
        val params = schema(
            """{"type":"object","properties":{
                 "code":{"type":"string","pattern":"^[A-Z]{2}-[0-9]{3}$"},
                 "tags":{"type":"array","items":{"type":"string"},"minItems":1,"maxItems":3},
                 "broken":{"type":"string","pattern":"("}}}""",
        )
        assertNull(PluginSchema.validate(params, schema("""{"code":"AB-123","tags":["a"]}""")))
        assertEquals(
            "code 不匹配格式：^[A-Z]{2}-[0-9]{3}$",
            PluginSchema.validate(params, schema("""{"code":"ab-123"}""")),
        )
        assertEquals("tags 至少 1 项", PluginSchema.validate(params, schema("""{"tags":[]}""")))
        assertEquals("tags 最多 3 项", PluginSchema.validate(params, schema("""{"tags":["a","b","c","d"]}""")))
        // schema 里写了坏正则时忽略 pattern，不因为 schema 写错拒掉合法值。
        assertNull(PluginSchema.validate(params, schema("""{"broken":"anything"}""")))
    }

    @Test
    fun mergesManifestDefaultsAndUserConfig() {
        val schemaText = """{"type":"object","properties":{
            "city":{"type":"string","default":"Shanghai"},
            "limit":{"type":"number","default":3},
            "nested":{"type":"object","properties":{"flag":{"type":"boolean","default":true}}}}}"""
        // ToolPkg manifest 的 config.default：city=Beijing、nested.flag=false
        val manifestDefaults = """{"city":"Beijing","nested":{"flag":false}}"""
        assertEquals(
            """{"city":"Beijing","nested":{"flag":false},"limit":9}""",
            PluginSchema.effectiveText(schemaText, """{"limit":9}""", manifestDefaults),
        )
        // 用户显式改 nested.flag 时覆盖 manifest 默认
        assertEquals(
            """{"city":"Beijing","nested":{"flag":true},"limit":3}""",
            PluginSchema.effectiveText(schemaText, """{"nested":{"flag":true}}""", manifestDefaults),
        )
    }
}

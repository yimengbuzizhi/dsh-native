package xin.dshaa.core.plugins

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * schemastery → JSON Schema 的转换测试。
 *
 * 下面的输入**不是手写的**：是真实 `@deepseek-ai/schemastery` 3.18.2 在 node 里
 * `toJSON()` 出来的图（`docs/52` 记录了对照脚本），这样测的是「上游真实序列化格式」，
 * 而不是我们想象出来的格式。
 */
class SchemasterySchemaTest {

    private fun graph(text: String) = Json.parseToJsonElement(text).jsonObject

    @Test
    fun convertsObjectWithRequiredUnionArrayAndDict() {
        val raw = graph(
            """{"uid":18,"refs":{
              "0":{"type":"const","meta":{},"value":"add"},
              "1":{"type":"const","meta":{},"value":"list"},
              "3":{"type":"union","meta":{"required":true},"list":[0,1]},
              "6":{"type":"string","meta":{"description":"todo text","required":true}},
              "9":{"type":"number","meta":{"step":1,"min":0}},
              "10":{"type":"string","meta":{}},
              "11":{"type":"array","meta":{"default":[]},"inner":10},
              "13":{"type":"boolean","meta":{"required":true}},
              "14":{"type":"object","meta":{"default":{}},"dict":{"a":13}},
              "15":{"type":"number","meta":{}},
              "16":{"type":"dict","meta":{"default":{}},"inner":15,"sKey":17},
              "17":{"type":"string","meta":{}},
              "18":{"type":"object","meta":{"default":{}},"dict":{
                "action":3,"text":6,"count":9,"tags":11,"opts":14,"meta":16}}}}""",
        )
        assertTrue(SchemasterySchema.looksLike(raw))
        val converted = assertNotNull(SchemasterySchema.toJsonSchema(raw))
        assertEquals(
            graph(
                """{"type":"object","properties":{
                     "action":{"enum":["add","list"]},
                     "text":{"type":"string","description":"todo text"},
                     "count":{"type":"number","minimum":0},
                     "tags":{"type":"array","items":{"type":"string"}},
                     "opts":{"type":"object","properties":{"a":{"type":"boolean"}},"required":["a"]},
                     "meta":{"type":"object","additionalProperties":{"type":"number"}}},
                   "required":["action","text"]}""",
            ),
            converted,
        )
        // 转换结果本身是标准 JSON Schema，再归一化应当原样通过
        assertEquals(converted, PluginSchema.normalize(converted))
    }

    @Test
    fun convertsUnionOfNonConstantsToAnyOf() {
        val raw = graph(
            """{"uid":36,"refs":{
              "32":{"type":"string","meta":{}},
              "33":{"type":"number","meta":{}},
              "34":{"type":"union","meta":{},"list":[32,33]},
              "35":{"type":"any","meta":{}},
              "36":{"type":"object","meta":{"default":{}},"dict":{"mode":34,"any":35}}}}""",
        )
        assertEquals(
            graph("""{"type":"object","properties":{"mode":{"anyOf":[{"type":"string"},{"type":"number"}]},"any":{}}}"""),
            SchemasterySchema.toJsonSchema(raw),
        )
    }

    @Test
    fun opensLazySchema() {
        val raw = graph(
            """{"uid":28,"refs":{
              "27":{"type":"lazy","inner":31,"meta":{}},
              "28":{"type":"object","meta":{"default":{}},"dict":{"l":27}},
              "30":{"type":"string","meta":{"required":true}},
              "31":{"type":"object","meta":{"default":{}},"dict":{"v":30}}}}""",
        )
        assertEquals(
            graph(
                """{"type":"object","properties":{"l":{"type":"object",
                     "properties":{"v":{"type":"string"}},"required":["v"]}}}""",
            ),
            SchemasterySchema.toJsonSchema(raw),
        )
    }

    @Test
    fun refusesTypesWithoutJsonSchemaEquivalent() {
        // tuple + date（date 本身是 union(string | transform)）：认不出就整条放弃，不猜
        val tupleAndDate = graph(
            """{"uid":26,"refs":{
              "19":{"type":"string","meta":{}},
              "20":{"type":"tuple","meta":{"default":[]},"list":[19]},
              "21":{"type":"is","meta":{},"constructor":"Date"},
              "23":{"type":"string","meta":{"role":"datetime"}},
              "24":{"type":"transform","meta":{},"inner":23,"callback":"(value) => value"},
              "25":{"type":"union","meta":{},"list":[21,24]},
              "26":{"type":"object","meta":{"default":{}},"dict":{"t":20,"d":25}}}}""",
        )
        assertNull(SchemasterySchema.toJsonSchema(tupleAndDate))

        val transform = graph(
            """{"uid":1,"refs":{
              "0":{"type":"string","meta":{}},
              "1":{"type":"transform","meta":{},"inner":0,"callback":"(v) => v"}}}""",
        )
        assertNull(SchemasterySchema.toJsonSchema(transform))
    }

    @Test
    fun refusesCyclicGraph() {
        val cyclic = graph(
            """{"uid":0,"refs":{"0":{"type":"object","meta":{"default":{}},"dict":{"self":0}}}}""",
        )
        assertNull(SchemasterySchema.toJsonSchema(cyclic))
    }

    @Test
    fun rootMustBeObject() {
        val scalar = graph("""{"uid":0,"refs":{"0":{"type":"string","meta":{}}}}""")
        assertNull(SchemasterySchema.toJsonSchema(scalar))
    }

    @Test
    fun ignoresNonSchemasteryShapes() {
        val standard = graph("""{"type":"object","properties":{"a":{"type":"string"}}}""")
        assertFalse(SchemasterySchema.looksLike(standard))
        assertNull(SchemasterySchema.convertText("not json"))
        assertNull(SchemasterySchema.convertText("""{"type":"object"}"""))
    }

    @Test
    fun readsI18nDescriptions() {
        val raw = graph(
            """{"uid":0,"refs":{
              "0":{"type":"object","meta":{"default":{}},"dict":{"city":2}},
              "2":{"type":"string","meta":{"description":{"":"City name","zh":"城市"}}}}}""",
        )
        val converted = assertNotNull(SchemasterySchema.toJsonSchema(raw))
        val city = converted["properties"]!!.jsonObject["city"]!!.jsonObject
        assertEquals("City name", city["description"].toString().trim('"'))
    }

    @Test
    fun writesExplicitDefaultsButNotImplicitEmptyContainers() {
        val raw = graph(
            """{"uid":12,"refs":{
              "1":{"type":"string","meta":{"default":"hi"}},
              "3":{"type":"number","meta":{"default":3}},
              "5":{"type":"boolean","meta":{"default":false}},
              "7":{"type":"boolean","meta":{}},
              "8":{"type":"object","meta":{"default":{}},"dict":{"flag":7}},
              "9":{"type":"string","meta":{}},
              "11":{"type":"array","meta":{"default":[]},"inner":9},
              "12":{"type":"object","meta":{"default":{}},"dict":{"text":1,"n":3,"on":5,"opts":8,"tags":11}}}}""",
        )
        assertEquals(
            graph(
                """{"type":"object","properties":{
                     "text":{"type":"string","default":"hi"},
                     "n":{"type":"number","default":3},
                     "on":{"type":"boolean","default":false},
                     "opts":{"type":"object","properties":{"flag":{"type":"boolean"}}},
                     "tags":{"type":"array","items":{"type":"string"}}}}""",
            ),
            SchemasterySchema.toJsonSchema(raw),
        )
    }
}

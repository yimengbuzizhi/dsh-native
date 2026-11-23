package xin.dshaa.core.primitives

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * P1 原语目录（`docs/185` §九）：锁住「一份定义」的形状与三面共用的校验。
 */
class PrimitiveCatalogTest {

    private fun args(vararg pairs: Pair<String, Any?>): JsonObject = buildJsonObject {
        pairs.forEach { (key, value) ->
            when (value) {
                null -> Unit
                is String -> put(key, value)
                is Int -> put(key, value)
                is Long -> put(key, value)
                is Boolean -> put(key, value)
                is JsonObject -> put(key, value)
                else -> error("unsupported fixture: $value")
            }
        }
    }

    @Test
    fun catalogHasTwentyUniquePrimitives() {
        assertEquals(20, PrimitiveCatalog.definitions.size)
        assertEquals(20, PrimitiveCatalog.byId.size)
        // P3-e：ui.openContainer 就绪（三种容器），20 个原语全部 READY。
        assertEquals(20, PrimitiveCatalog.available.size)
        assertEquals(20, PrimitiveCatalog.toolNames.size)
        assertEquals(
            emptyList(),
            PrimitiveCatalog.definitions.filter { it.availability != PrimitiveAvailability.READY }.map { it.id },
        )
        assertTrue(PrimitiveCatalog.available.any { it.id == PrimitiveIds.UI_OPEN_CONTAINER })
        // 打开容器会占屏，必须走审批（不是 FREE）。
        assertEquals(
            PrimitiveApproval.ASK,
            PrimitiveCatalog.byId.getValue(PrimitiveIds.UI_OPEN_CONTAINER).approval,
        )
    }

    @Test
    fun everyDefinitionHasCompleteSchemaAndDocs() {
        PrimitiveCatalog.definitions.forEach { definition ->
            assertTrue(definition.id.matches(Regex("^[a-z]+\\.[A-Za-z_]+$")), "id ${definition.id}")
            assertTrue(definition.title.isNotBlank(), "title of ${definition.id}")
            assertTrue(definition.description.length > 40, "description of ${definition.id}")
            assertEquals("object", definition.parameters["type"]?.jsonPrimitive?.content)
            val properties = assertNotNull(definition.parameters["properties"]).jsonObject
            val required = definition.parameters["required"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
            required.forEach { name ->
                assertTrue(properties.containsKey(name), "${definition.id}: required $name missing from properties")
            }
            properties.values.forEach { spec ->
                assertTrue(spec.jsonObject.containsKey("description"), "${definition.id}: property without description")
            }
        }
    }

    @Test
    fun toolNamesAreUniqueSnakeCase() {
        val names = PrimitiveCatalog.available.map { PrimitiveCatalog.toolName(it.id) }
        assertEquals(names.size, names.toSet().size)
        names.forEach { name ->
            // `ui.openContainer` 是唯一保留 camelCase 的 id（P1 起就写进文档/调试通道，改名会破对拍）；
            // 其余工具名必须是小写 snake_case。
            if (name == "ui_openContainer") return@forEach
            assertTrue(name.matches(Regex("^[a-z][a-z0-9_]*$")), "tool name $name")
        }
        assertEquals("session_fork", PrimitiveCatalog.toolName(PrimitiveIds.SESSION_FORK))
        assertEquals("ui_openContainer", PrimitiveCatalog.toolName(PrimitiveIds.UI_OPEN_CONTAINER))
    }

    @Test
    fun onlyReadPrimitivesAreVisibleToPlugins() {
        assertTrue(PrimitiveCatalog.bridgeVisible.isNotEmpty())
        assertTrue(PrimitiveCatalog.bridgeVisible.all { it.risk == PrimitiveRisk.READ })
        assertTrue(PrimitiveCatalog.bridgeVisible.all { it.availability == PrimitiveAvailability.READY })
        assertEquals(
            setOf(
                PrimitiveIds.SESSION_LIST,
                PrimitiveIds.SESSION_READ,
                PrimitiveIds.SESSION_SUMMARIZE,
                PrimitiveIds.MODEL_LIST,
                PrimitiveIds.TURN_STATE,
                PrimitiveIds.TOOL_LIST,
                PrimitiveIds.STORE_GET,
                PrimitiveIds.STORE_LIST,
            ),
            PrimitiveCatalog.bridgeVisible.map { it.id }.toSet(),
        )
        // 写会话/改设置/执行工具/界面一律不给插件桥
        assertFalse(PrimitiveCatalog.byId.getValue(PrimitiveIds.TOPIC_SWITCH).bridgeVisible)
        assertFalse(PrimitiveCatalog.byId.getValue(PrimitiveIds.PERMISSION_SET).bridgeVisible)
        assertFalse(PrimitiveCatalog.byId.getValue(PrimitiveIds.TOOL_CALL).bridgeVisible)
    }

    @Test
    fun approvalPolicyOnlyAsksForDangerousPrimitives() {
        assertEquals(
            // P3-e：ui_openContainer 会真的占屏/盖住聊天页，所以它也要审批。
            setOf("permission_set", "tool_call", "ui_openContainer"),
            PrimitiveCatalog.approvalFreeToolNames.let { free ->
                PrimitiveCatalog.toolNames - free
            },
        )
        assertTrue("turn_send" in PrimitiveCatalog.approvalFreeToolNames)
        assertTrue("session_summarize" in PrimitiveCatalog.approvalFreeToolNames)
    }

    @Test
    fun requiredAndTypeValidation() {
        val summarize = PrimitiveCatalog.byId.getValue(PrimitiveIds.SESSION_SUMMARIZE)
        assertNull(PrimitiveArgs.validate(summarize, args()))
        assertEquals(
            "parameter from_seq must be an integer",
            PrimitiveArgs.validate(summarize, args("from_seq" to "later")),
        )
        assertEquals(
            "from_seq must be <= to_seq",
            PrimitiveArgs.validate(summarize, args("from_seq" to 9, "to_seq" to 3)),
        )

        val modelSet = PrimitiveCatalog.byId.getValue(PrimitiveIds.MODEL_SET)
        assertEquals("missing required parameter: model", PrimitiveArgs.validate(modelSet, args()))
        assertEquals("model must not be blank", PrimitiveArgs.validate(modelSet, args("model" to " ")))

        val effort = PrimitiveCatalog.byId.getValue(PrimitiveIds.EFFORT_SET)
        assertEquals(
            "parameter effort must be one of off/low/high/max",
            PrimitiveArgs.validate(effort, args("effort" to "medium")),
        )
        assertNull(PrimitiveArgs.validate(effort, args("effort" to "max")))

        val send = PrimitiveCatalog.byId.getValue(PrimitiveIds.TURN_SEND)
        assertEquals("text must not be blank", PrimitiveArgs.validate(send, args("text" to "  ")))
        assertNull(PrimitiveArgs.validate(send, args("text" to "继续")))
    }

    @Test
    fun storeValidation() {
        val get = PrimitiveCatalog.byId.getValue(PrimitiveIds.STORE_GET)
        assertEquals("missing required parameter: key", PrimitiveArgs.validate(get, args("namespace" to "probe")))
        assertEquals(
            "namespace must match [A-Za-z0-9_.:-]{1,64}",
            PrimitiveArgs.validate(get, args("namespace" to "bad name", "key" to "k")),
        )
        assertNull(PrimitiveArgs.validate(get, args("namespace" to "probe", "key" to "last")))

        val set = PrimitiveCatalog.byId.getValue(PrimitiveIds.STORE_SET)
        assertEquals(
            "missing required parameter: value",
            PrimitiveArgs.validate(set, args("namespace" to "probe", "key" to "last")),
        )
        val huge = "x".repeat(64 * 1024 + 1)
        assertTrue(
            PrimitiveArgs.validate(set, args("namespace" to "probe", "key" to "last", "value" to huge))
                ?.contains("too large") == true,
        )

        val list = PrimitiveCatalog.byId.getValue(PrimitiveIds.STORE_LIST)
        assertNull(PrimitiveArgs.validate(list, args("namespace" to "probe")))
    }

    @Test
    fun toolCallRejectsBlankNameAndAllowsArgumentObject() {
        val call = PrimitiveCatalog.byId.getValue(PrimitiveIds.TOOL_CALL)
        assertEquals("name must not be blank", PrimitiveArgs.validate(call, args("name" to "")))
        assertNull(
            PrimitiveArgs.validate(
                call,
                args("name" to "todo_write", "arguments" to buildJsonObject { put("todos", buildJsonArray { }) }),
            ),
        )
        assertEquals(
            "parameter arguments must be an object",
            PrimitiveArgs.validate(call, args("name" to "todo_write", "arguments" to "[]")),
        )
    }

    @Test
    fun bridgeJsonCarriesCatalogAndResultShape() {
        val list = PrimitiveArgs.parse(PrimitiveBridgeJson.list(PrimitiveCatalog.bridgeVisible))
        assertNotNull(list)
        assertEquals(true, list.getValue("ok").jsonPrimitive.boolean)
        val items = list.getValue("primitives").jsonArray
        assertEquals(PrimitiveCatalog.bridgeVisible.size, items.size)
        val first = items.first().jsonObject
        assertTrue(first.containsKey("id"))
        assertTrue(first.containsKey("availability"))
        assertEquals(true, first.getValue("bridge").jsonPrimitive.boolean)

        val ok = PrimitiveArgs.parse(
            PrimitiveBridgeJson.reply(PrimitiveResult.ok("hello", buildJsonObject { put("n", 1) })),
        )
        assertNotNull(ok)
        assertEquals(true, ok.getValue("ok").jsonPrimitive.boolean)
        assertEquals("hello", ok.getValue("summary").jsonPrimitive.content)
        assertEquals(1, ok.getValue("data").jsonObject.getValue("n").jsonPrimitive.content.toInt())

        val error = PrimitiveArgs.parse(PrimitiveBridgeJson.error("nope", "NOT_IMPLEMENTED"))
        assertNotNull(error)
        assertEquals(false, error.getValue("ok").jsonPrimitive.boolean)
        assertEquals("NOT_IMPLEMENTED", error.getValue("code").jsonPrimitive.content)
        assertEquals("nope", error.getValue("error").jsonPrimitive.content)
    }

    @Test
    fun rangeUsesInclusiveSeqBounds() {
        assertEquals(true, PrimitiveRange().contains(0))
        assertEquals(true, PrimitiveRange(2, 5).contains(2))
        assertEquals(true, PrimitiveRange(2, 5).contains(5))
        assertEquals(false, PrimitiveRange(2, 5).contains(1))
        assertEquals(false, PrimitiveRange(2, 5).contains(6))
        assertEquals(false, PrimitiveRange(2, 5).contains(null))
        assertEquals(true, PrimitiveRange().contains(null))
    }
}

package xin.dshaa.core.workflow

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WorkflowMetaTest {
    @Test
    fun parsesValidMeta() {
        val meta = WorkflowMeta.parse(
            """{"name":"audit","description":"audit files","phases":[{"title":"scan"},{"title":"report","detail":"d"}]}""",
        )
        assertEquals("audit", meta.name)
        assertEquals(2, meta.phases.size)
        assertEquals("report", meta.phases[1].title)
    }

    @Test
    fun rejectsMissingOrBlankFields() {
        assertFailsWith<WorkflowException> { WorkflowMeta.parse("""{"description":"x"}""") }
        assertFailsWith<WorkflowException> { WorkflowMeta.parse("""{"name":" ","description":"x"}""") }
        assertFailsWith<WorkflowException> { WorkflowMeta.parse("""{"name":"a","description":"x","phases":[{}]}""") }
        assertFailsWith<WorkflowException> { WorkflowMeta.parse("not json") }
    }
}

class WorkflowSchemaTest {
    private val objectSchema = Json.parseToJsonElement(
        """
        {"type":"object","properties":{"files":{"type":"array","items":{"type":"string"}},
         "verdict":{"type":"string","enum":["pass","fail"]}},"required":["files"],"additionalProperties":false}
        """.trimIndent(),
    ).jsonObject

    @Test
    fun acceptsObjectRootAndValidatesValues() {
        assertEquals(objectSchema, WorkflowSchema.assertObjectSchema(objectSchema))
        val good = Json.parseToJsonElement("""{"files":["a","b"],"verdict":"pass"}""")
        assertNull(WorkflowSchema.validate(good, objectSchema))
        val extra = Json.parseToJsonElement("""{"files":["a"],"bogus":1}""")
        assertNotNull(WorkflowSchema.validate(extra, objectSchema))
        val wrongEnum = Json.parseToJsonElement("""{"files":[],"verdict":"maybe"}""")
        assertNotNull(WorkflowSchema.validate(wrongEnum, objectSchema))
        val missing = Json.parseToJsonElement("""{}""")
        assertNotNull(WorkflowSchema.validate(missing, objectSchema))
    }

    @Test
    fun rejectsUnsupportedKeywordsAndNonObjectRoot() {
        assertFailsWith<WorkflowException> { WorkflowSchema.assertObjectSchema(Json.parseToJsonElement("""{"type":"string"}""")) }
        assertFailsWith<WorkflowException> {
            WorkflowSchema.assertObjectSchema(Json.parseToJsonElement("""{"type":"object","minProperties":1}"""))
        }
        assertFailsWith<WorkflowException> {
            WorkflowSchema.assertObjectSchema(Json.parseToJsonElement("""{"type":"object","properties":{"a":{"type":"string","pattern":"x"}}}"""))
        }
        assertFailsWith<WorkflowException> {
            WorkflowSchema.assertObjectSchema(Json.parseToJsonElement("""{"type":"object","required":["a"]}"""))
        }
    }

    @Test
    fun oneOfMustMatchExactlyOne() {
        val schema = Json.parseToJsonElement(
            """{"type":"object","properties":{"r":{"oneOf":[{"type":"string"},{"type":"integer"}]}}}""",
        ).jsonObject
        WorkflowSchema.assertObjectSchema(schema)
        assertNull(WorkflowSchema.validate(Json.parseToJsonElement("""{"r":"x"}"""), schema))
        assertNull(WorkflowSchema.validate(Json.parseToJsonElement("""{"r":3}"""), schema))
        assertNotNull(WorkflowSchema.validate(Json.parseToJsonElement("""{"r":true}"""), schema))
    }
}

class WorkflowRenderTest {
    @Test
    fun rendersResultWithPrettyJson() {
        val text = WorkflowRender.resultText("audit", 2, """{"a":1}""")
        assertTrue(text.startsWith("workflow \"audit\" completed (2 agents).\nReturn value:"))
        assertTrue(text.contains("\"a\": 1"))
    }

    @Test
    fun truncatesLongResults() {
        val value = "[\"" + "x".repeat(200) + "\"]"
        val text = WorkflowRender.resultText("w", 1, value, maxChars = 50)
        assertTrue(text.contains("more characters"))
    }

    @Test
    fun stopReasonMessagesMatchUpstream() {
        assertEquals(
            "workflow run was cancelled (parent step aborted)",
            WorkflowRender.stopReasonError(WorkflowRunResult(WorkflowStopReason.CANCELLED, error = "parent step aborted")),
        )
        assertEquals(
            "workflow run failed: boom",
            WorkflowRender.stopReasonError(WorkflowRunResult(WorkflowStopReason.ERROR, error = "boom")),
        )
        assertNull(WorkflowRender.stopReasonError(WorkflowRunResult(WorkflowStopReason.COMPLETED)))
    }
}

class WorkflowEngineTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private fun engine(result: WorkflowJsResult) = WorkflowEngine(
        jsFactory = { FakeRuntime(result) },
        children = WorkflowChildHost { WorkflowChildOutcome(completed = true, text = "child") },
        scope = scope,
    )

    @Test
    fun completedRunCarriesValueAndAgentCount() = runBlocking {
        val run = engine(WorkflowJsResult(WorkflowJsStatus.COMPLETED, """{"ok":true}"""))
            .start("return 1", WorkflowMeta.parse("""{"name":"w","description":"d"}"""), null)
        val result = withTimeout(10_000) { run.result.await() }
        assertEquals(WorkflowStopReason.COMPLETED, result.stopReason)
        assertEquals("""{"ok":true}""", result.valueJson)
    }

    @Test
    fun errorAndCancelMapToStopReasons() = runBlocking {
        val failed = engine(WorkflowJsResult(WorkflowJsStatus.ERROR, error = "ReferenceError: x is not defined"))
            .start("x", WorkflowMeta.parse("""{"name":"w","description":"d"}"""), null)
        val failedResult = withTimeout(10_000) { failed.result.await() }
        assertEquals(WorkflowStopReason.ERROR, failedResult.stopReason)
        assertTrue(failedResult.error!!.contains("ReferenceError"))

        val blocked = CompletableDeferred<Unit>()
        val slowEngine = WorkflowEngine(
            jsFactory = { object : WorkflowJsRuntime {
                override fun run(code: String, filename: String, host: WorkflowJsHost): WorkflowJsResult {
                    blocked.complete(Unit)
                    Thread.sleep(5_000)
                    return WorkflowJsResult(WorkflowJsStatus.COMPLETED, "null")
                }
                override fun complete(callId: Int, ok: Boolean, payloadJson: String?) = Unit
                override fun interrupt() = Unit
            } },
            children = WorkflowChildHost { WorkflowChildOutcome(completed = true) },
            scope = scope,
        )
        val run = slowEngine.start("return 1", WorkflowMeta.parse("""{"name":"w","description":"d"}"""), null)
        blocked.await()
        run.cancel("parent step aborted")
        val cancelled = withTimeout(10_000) { run.result.await() }
        assertEquals(WorkflowStopReason.CANCELLED, cancelled.stopReason)
        assertEquals("parent step aborted", cancelled.error)
    }

    private class FakeRuntime(private val result: WorkflowJsResult) : WorkflowJsRuntime {
        override fun run(code: String, filename: String, host: WorkflowJsHost): WorkflowJsResult {
            assertTrue(code.contains("__dshCallAsync"))
            assertEquals("workflow:w", filename)
            return result
        }

        override fun complete(callId: Int, ok: Boolean, payloadJson: String?) = Unit
        override fun interrupt() = Unit
    }

    @Test
    fun metaToJsonKeepsFields() {
        val meta = WorkflowMeta.parse("""{"name":"w","description":"d"}""")
        val obj = buildJsonObject {
            put("name", meta.name)
            put("description", meta.description)
        }
        assertTrue(obj.toString().contains("\"name\":\"w\""))
    }
}

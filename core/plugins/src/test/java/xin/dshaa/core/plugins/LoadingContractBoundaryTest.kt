package xin.dshaa.core.plugins

import kotlinx.serialization.json.*
import kotlin.test.*

/** Loading data accepted at registration must remain valid after registry/snapshot normalization. */
class LoadingContractBoundaryTest {
    private fun rawDeclaration(vararg children: JsonElement) = buildJsonObject {
        put("id", "a".repeat(80))
        put("tree", buildJsonObject { put("type", "column"); put("children", JsonArray(children.toList())) })
    }
    private fun text(value: String) = buildJsonObject { put("type", "text"); put("text", value) }

    @Test fun omittedLabelAtByteLimitCannotProduceAnUnreadableNormalizedDeclaration() {
        val children = List(21) { text("x".repeat(1500)) }
        val seed = rawDeclaration(*(children + text("")).toTypedArray())
        val remaining = LoadingSpec.MAX_BYTES - seed.toString().toByteArray(Charsets.UTF_8).size
        assertTrue(remaining in 0..2000)
        val raw = rawDeclaration(*(children + text("x".repeat(remaining))).toTypedArray())
        assertEquals(LoadingSpec.MAX_BYTES, raw.toString().toByteArray(Charsets.UTF_8).size)
        // Either reject before install or accept a declaration that survives persistent readback.
        val first = LoadingSpec.parse(raw)
        first.loading?.let { loading ->
            val normalized = LoadingSpec.declaration(loading)
            assertTrue(normalized.toString().toByteArray(Charsets.UTF_8).size <= LoadingSpec.MAX_BYTES,
                "Accepted loading expanded beyond its persistent read budget")
            assertEquals(loading, assertNotNull(LoadingSpec.parse(normalized).loading))
            assertEquals(listOf(loading), LoadingSpec.parseAll(buildJsonArray { add(normalized) }))
        } ?: assertNotNull(first.error)
    }

    @Test fun canonicalDeclarationAtExactByteLimitSurvivesRegistryReadback() {
        val children = List(21) { text("x".repeat(1500)) }
        fun withLabel(tail: String) = buildJsonObject {
            put("id", "a".repeat(80)); put("label", "上限")
            put("tree", rawDeclaration(*(children + text(tail)).toTypedArray()).getValue("tree"))
        }
        val remaining = LoadingSpec.MAX_BYTES - withLabel("").toString().toByteArray(Charsets.UTF_8).size
        assertTrue(remaining in 0..2000)
        val raw = withLabel("x".repeat(remaining))
        assertEquals(LoadingSpec.MAX_BYTES, raw.toString().toByteArray(Charsets.UTF_8).size)
        val parsed = LoadingSpec.parse(raw)
        assertNull(parsed.error)
        val loading = assertNotNull(parsed.loading)
        val normalized = LoadingSpec.declaration(loading)
        assertEquals(raw, normalized)
        assertEquals(loading, LoadingSpec.parse(normalized).loading)
        assertEquals(listOf(loading), LoadingSpec.parseAll(buildJsonArray { add(normalized) }))
    }

    @Test fun utf8BudgetRejectsChineseTextEvenWhenCharacterCountFits() {
        val raw = rawDeclaration(*Array(6) { text("鲸".repeat(1900)) })
        assertTrue(raw.toString().length < LoadingSpec.MAX_BYTES)
        assertTrue(raw.toString().toByteArray(Charsets.UTF_8).size > LoadingSpec.MAX_BYTES)
        assertNotNull(LoadingSpec.parse(raw).error)
        val run = PluginModule.parseDryRun(buildJsonObject { put("loadings", buildJsonArray { add(raw) }) }.toString())
        assertNotNull(run.error)
        assertTrue(run.loadings.isEmpty())
    }
}

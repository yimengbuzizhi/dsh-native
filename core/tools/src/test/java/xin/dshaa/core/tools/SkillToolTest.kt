package xin.dshaa.core.tools

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import xin.dshaa.core.skill.SkillContent
import xin.dshaa.core.skill.SkillHost
import xin.dshaa.core.skill.SkillInvocation
import xin.dshaa.core.skill.SkillLoad
import xin.dshaa.core.skill.SkillSnapshot
import xin.dshaa.core.skill.SkillSummary
import xin.dshaa.core.tools.skill.SkillTool

/**
 * `skill` 工具（M19）：四条分支的文案逐字对齐上游 `dsh-tool-skill`，
 * 成功结果 = `<skill_content>` 外壳（与「用户直接调用」共用同一渲染）。
 */
class SkillToolTest {

    private val demo = SkillSummary(
        name = "demo",
        description = "demo skill",
        invocation = SkillInvocation(),
        source = "user-dsh",
        path = "/tmp/skills/demo/SKILL.md",
        directory = "/tmp/skills/demo",
    )

    private class FakeHost(
        val skills: List<SkillSummary>,
        val content: Map<String, String> = emptyMap(),
    ) : SkillHost {
        override fun snapshot(): SkillSnapshot = SkillSnapshot(skills, complete = true)

        override fun load(name: String): SkillLoad {
            if (!xin.dshaa.core.skill.isSkillName(name)) return SkillLoad.InvalidName(name)
            val summary = skills.firstOrNull { it.name == name } ?: return SkillLoad.Unknown(name)
            if (!summary.modelInvocable) return SkillLoad.NotModelInvocable(name)
            return SkillLoad.Ok(summary.toContent(content[name].orEmpty()))
        }
    }

    private fun args(vararg pairs: Pair<String, String>): JsonObject = buildJsonObject {
        pairs.forEach { (k, v) -> put(k, v) }
    }

    private fun ctx(host: SkillHost?): ToolContext = ToolContext(workspace = File("."), skills = host)

    @Test
    fun loadsSkillContentWithDirectoryBase() = runBlocking {
        val host = FakeHost(listOf(demo), mapOf("demo" to "# Demo\nDo the thing."))
        val result = SkillTool().execute(args("name" to "demo"), ctx(host))

        assertTrue(!result.isError)
        assertEquals(
            """
            <skill_content name="demo">
            <skill_resources>
            Base directory for this skill: /tmp/skills/demo
            Resolve relative paths mentioned by this skill against the base directory before using them. Load referenced resources only as needed.
            </skill_resources>

            <skill_instructions>
            # Demo
            Do the thing.
            </skill_instructions>
            </skill_content>
            """.trimIndent(),
            result.text,
        )
        assertEquals("demo", result.meta["skill"])
    }

    @Test
    fun invalidNameMatchesUpstreamText() = runBlocking {
        val result = SkillTool().execute(args("name" to "Bad Name"), ctx(FakeHost(emptyList())))
        assertTrue(result.isError)
        assertEquals("invalid skill name \"Bad Name\"", result.text)
    }

    @Test
    fun unknownSkillMatchesUpstreamText() = runBlocking {
        val result = SkillTool().execute(args("name" to "missing"), ctx(FakeHost(listOf(demo))))
        assertTrue(result.isError)
        assertEquals("skill \"missing\" is unknown or no longer available", result.text)
    }

    @Test
    fun modelInvocationDisabledMatchesUpstreamText() = runBlocking {
        val manual = demo.copy(name = "manual", invocation = SkillInvocation(modelInvocable = false))
        val result = SkillTool().execute(args("name" to "manual"), ctx(FakeHost(listOf(manual))))
        assertTrue(result.isError)
        assertEquals("skill \"manual\" is not available for model invocation", result.text)
    }

    @Test
    fun missingServiceReportsUnavailable() = runBlocking {
        val result = SkillTool().execute(args("name" to "demo"), ctx(null))
        assertTrue(result.isError)
        assertEquals("no skill service is mounted", result.text)
    }

    @Test
    fun emptyNameIsInvalid() = runBlocking {
        val result = SkillTool().execute(buildJsonObject { }, ctx(FakeHost(listOf(demo))))
        assertTrue(result.isError)
        assertEquals("invalid skill name \"\"", result.text)
    }
}

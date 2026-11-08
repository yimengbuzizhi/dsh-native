package xin.dshaa.core.skill

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 技能系统（M19）单测：frontmatter 解析、根目录发现/优先级、目录消息与摘要、
 * `skill` 工具的加载分支、`<skill_content>` 外壳。
 *
 * 期望文案逐字取自上游 `dsh-tool-skill` / `dsh-skill`（docs/36 §二）。
 */
class SkillCatalogTest {

    private fun tempDir(prefix: String = "skill-test"): File =
        Files.createTempDirectory(prefix).toFile().apply { deleteOnExit() }

    private fun skillFile(root: File, name: String, description: String, body: String, extra: String = ""): File {
        val dir = File(root, name).apply { mkdirs() }
        return File(dir, "SKILL.md").apply {
            writeText(
                """
                ---
                name: $name
                description: $description
                $extra
                ---
                $body
                """.trimIndent(),
                Charsets.UTF_8,
            )
        }
    }

    // ---------------- frontmatter ----------------

    @Test
    fun frontmatterParsesNameDescriptionAndInvocation() {
        val raw = """
            ---
            name: demo-skill
            description: Use when testing the skill system.
            whenToUse: when a test needs a skill
            disable-model-invocation: false
            user-invocable: true
            ---
            Body line 1
            Body line 2
        """.trimIndent()
        val parsed = assertNotNull(SkillFrontmatter.parse(raw))
        assertEquals("demo-skill", parsed.data["name"])
        assertEquals("when a test needs a skill", parsed.data["whenToUse"])
        assertEquals(SkillInvocation(modelInvocable = true, userInvocable = true), SkillFrontmatter.invocation(parsed.data))
        assertEquals("Body line 1\nBody line 2", parsed.body)
    }

    @Test
    fun frontmatterSupportsQuotesCommentsAndBlockScalar() {
        val raw = """
            ---
            name: quoted
            description: "a: quoted # value"
            # a comment line
            whenToUse: |
              folded
              lines
            ---
            body
        """.trimIndent()
        val parsed = assertNotNull(SkillFrontmatter.parse(raw))
        assertEquals("a: quoted # value", parsed.data["description"])
        assertEquals("folded\nlines", parsed.data["whenToUse"])
    }

    @Test
    fun legacyInvocationKeysAreRejected() {
        val data = mapOf("name" to "x", "description" to "d", "disableModelInvocation" to "true")
        val error = runCatching { SkillFrontmatter.invocation(data) }.exceptionOrNull()
        assertTrue(error is SkillFrontmatter.Invalid)
        assertTrue(error.message!!.contains("use \"disable-model-invocation\""))
    }

    @Test
    fun nonBooleanInvocationValueIsRejected() {
        val error = runCatching { SkillFrontmatter.invocation(mapOf("user-invocable" to "maybe")) }.exceptionOrNull()
        assertTrue(error is SkillFrontmatter.Invalid)
    }

    @Test
    fun missingOrUnclosedFrontmatterIsRejected() {
        assertNull(SkillFrontmatter.parse("no frontmatter here"))
        assertNull(SkillFrontmatter.parse("---\nname: x\ndescription: y\n"))
        assertNull(SkillFrontmatter.parse("body only\n---\nname: x\n---\n"))
    }

    // ---------------- 发现 ----------------

    @Test
    fun discoveryFindsDirectoryBundlesAndFlatMarkdown() {
        val userRoot = tempDir()
        skillFile(userRoot, "dir-skill", "directory skill", "dir body")
        File(userRoot, "flat.md").writeText("---\nname: flat-skill\ndescription: flat skill\n---\nflat body\n")

        val skills = FileSystemSkills(listOf(SkillRoot(userRoot, "user-dsh", 400, skipSystem = true)))
        val snapshot = skills.snapshot()

        assertTrue(snapshot.complete)
        assertEquals(listOf("dir-skill", "flat-skill"), snapshot.skills.map { it.name })
        // 目录型：resourceBase = 技能目录；平铺：resourceBase = 根目录
        assertEquals(File(userRoot, "dir-skill").absolutePath, snapshot.skills[0].directory)
        assertEquals(userRoot.absolutePath, snapshot.skills[1].directory)
        assertEquals("user-dsh", snapshot.skills[0].source)
    }

    @Test
    fun discoveryPrefersHigherRankRootAndSkipsSystem() {
        val project = tempDir()
        val user = tempDir()
        skillFile(File(project, ".dsh/skills").apply { mkdirs() }, "same-name", "project wins", "project body")
        skillFile(user, "same-name", "user loses", "user body")
        skillFile(user, "other", "other skill", "o")
        File(user, ".system").mkdirs()
        skillFile(File(user, ".system"), "hidden", "hidden skill", "h")

        val skills = FileSystemSkills(
            listOf(
                SkillRoot(File(project, ".dsh/skills"), "project-dsh", 100),
                SkillRoot(user, "user-dsh", 400, skipSystem = true),
            ),
        )
        val snapshot = skills.snapshot()
        assertEquals(listOf("other", "same-name"), snapshot.skills.map { it.name })
        assertEquals("project wins", snapshot.skills.first { it.name == "same-name" }.description)
        assertEquals("project-dsh", snapshot.skills.first { it.name == "same-name" }.source)
    }

    @Test
    fun invalidSkillFilesAreIgnoredWithWarning() {
        val root = tempDir()
        skillFile(root, "good", "good skill", "body")
        File(root, "broken").mkdirs()
        File(root, "broken/SKILL.md").writeText("---\nname: Broken_Name\ndescription: d\n---\n")
        File(root, "legacy").mkdirs()
        File(root, "legacy/SKILL.md").writeText("---\nname: legacy\ndescription: d\nmodelInvocable: true\n---\n")

        val skills = FileSystemSkills(listOf(SkillRoot(root, "user-dsh", 400, skipSystem = true)))
        assertEquals(listOf("good"), skills.snapshot().skills.map { it.name })
        assertEquals(2, skills.lastWarnings().size)
    }

    @Test
    fun disableModelInvocationHidesFromCatalogButLoadReportsIt() {
        val root = tempDir()
        val file = skillFile(root, "manual-only", "manual only", "body")
        file.writeText(
            """
            ---
            name: manual-only
            description: manual only
            disable-model-invocation: true
            ---
            body
            """.trimIndent(),
        )
        val skills = FileSystemSkills(listOf(SkillRoot(root, "user-dsh", 400, skipSystem = true)))

        assertTrue(skills.snapshot().skills.single().modelInvocable.not())
        assertTrue(SkillCatalog.entries(skills.snapshot().skills).isEmpty())
        assertTrue(skills.load("manual-only") is SkillLoad.NotModelInvocable)
    }

    @Test
    fun loadRejectsInvalidNameUnknownAndReturnsContent() {
        val root = tempDir()
        skillFile(root, "demo", "demo skill", "  body text  ")
        val skills = FileSystemSkills(listOf(SkillRoot(root, "user-dsh", 400, skipSystem = true)))

        assertEquals(SkillLoad.InvalidName("Bad Name"), skills.load("Bad Name"))
        assertEquals(SkillLoad.Unknown("missing"), skills.load("missing"))
        val ok = skills.load("demo")
        assertTrue(ok is SkillLoad.Ok)
        // 上游 parseSkillFile：body 走 trim()
        assertEquals("body text", (ok as SkillLoad.Ok).skill.content)
        // 目录型技能的 resourceBase 是它自己的目录
        assertEquals(File(root, "demo").absolutePath, ok.skill.directory)
    }

    // ---------------- 目录消息 ----------------

    @Test
    fun firstCatalogMessageMatchesUpstreamText() {
        val text = SkillCatalog.firstMessage(listOf(SkillCatalogEntry("demo", "示例技能")))
        assertEquals(
            """
            <system-reminder>
            A skill is a reusable set of task-specific instructions. The following skills are available in this session:

            <available_skills>
            - `demo`: 示例技能
            </available_skills>

            If the user names a skill, or the task clearly matches a skill's description, call the `skill` tool with the exact skill name before taking task actions. Load all applicable skills, then follow their full instructions. This catalog contains summaries only; do not infer or follow a skill's instructions until it has been loaded.
            A user may also invoke a skill directly; its <skill_content> block then appears in this conversation. Follow it, and do not call the `skill` tool again for that skill.
            </system-reminder>
            """.trimIndent(),
            text,
        )
    }

    @Test
    fun updateCatalogMessageReplacesEarlierLists() {
        val text = SkillCatalog.updateMessage(emptyList(), incomplete = false)
        assertTrue(text.startsWith("<system-reminder>\nThe available skill catalog changed."))
        assertTrue(text.contains("<available_skills>\n</available_skills>"))
        assertTrue(text.contains("No skills are currently available through the `skill` tool."))
    }

    @Test
    fun incompleteCatalogCarriesDiscoveryNotice() {
        val text = SkillCatalog.firstMessage(listOf(SkillCatalogEntry("a", "b")), incomplete = true)
        assertTrue(text.contains("Some skills could not be checked."))
        assertTrue(text.contains("<available_skills>\n- `a`: b\n</available_skills>\n\nSome skills could not be checked."))
    }

    @Test
    fun descriptionNormalizesWhitespaceAndTruncates() {
        assertEquals("a b c", SkillCatalog.description("  a\n\tb   c  "))
        val long = "x".repeat(600)
        val truncated = SkillCatalog.description(long)
        assertEquals(500, truncated.length)
        assertTrue(truncated.endsWith("..."))
    }

    @Test
    fun digestChangesWithEntriesAndIncompleteFlag() {
        val a = listOf(SkillCatalogEntry("a", "x"))
        val b = listOf(SkillCatalogEntry("a", "y"))
        assertEquals(SkillCatalog.digest(a), SkillCatalog.digest(a))
        assertFalse(SkillCatalog.digest(a) == SkillCatalog.digest(b))
        assertFalse(SkillCatalog.digest(a) == SkillCatalog.digest(a, incomplete = true))
    }

    @Test
    fun decideEmitsFirstCatalogOnceThenUpdateOnChange() {
        val root = tempDir()
        skillFile(root, "demo", "demo skill", "body")
        val skills = FileSystemSkills(listOf(SkillRoot(root, "user-dsh", 400, skipSystem = true)))
        val snapshot = skills.snapshot()

        // 首次：发目录
        val first = assertNotNull(SkillCatalog.decide(snapshot, null))
        assertFalse(first.update)
        val source = SkillCatalog.source(first)
        assertEquals("skill-catalog", source["kind"]!!.toString().trim('"'))

        // 摘要没变：不再发（请求体不动）
        assertNull(SkillCatalog.decide(snapshot, source))

        // 目录变了：发替换消息（update = true）
        skillFile(root, "second", "第二个技能", "body")
        val changed = assertNotNull(SkillCatalog.decide(skills.snapshot(), source))
        assertTrue(changed.update)
        assertEquals(2, changed.entries.size)
        assertEquals(true, SkillCatalog.source(changed)["update"].toString().toBooleanStrict())
    }

    @Test
    fun emptyCatalogIsNotPublishedButPublishesRemovalAfterFirstCatalog() {
        val empty = SkillSnapshot(emptyList(), complete = true)
        // 从没发过 + 没有技能 → 什么都不加
        assertNull(SkillCatalog.decide(empty, null))

        val previous = SkillCatalog.source(
            SkillCatalog.Notice(
                text = "t",
                entries = listOf(SkillCatalogEntry("removed", "旧技能")),
                incomplete = false,
                update = false,
            ),
        )
        // 发过目录后技能被删光 → 要发一条「没有技能」的替换消息
        val removal = assertNotNull(SkillCatalog.decide(empty, previous))
        assertTrue(removal.update)
        assertTrue(removal.entries.isEmpty())
    }

    @Test
    fun incompleteSnapshotPublishesEmptyCatalog() {
        val incomplete = SkillSnapshot(emptyList(), complete = false)
        val notice = assertNotNull(SkillCatalog.decide(incomplete, null))
        assertTrue(notice.incomplete)
        assertTrue(notice.text.contains("No skills could be confirmed in this discovery attempt."))
        assertTrue(SkillCatalog.source(notice)["incomplete"].toString().toBooleanStrict())
    }

    // ---------------- <skill_content> ----------------

    @Test
    fun renderSkillContentUsesDirectoryBaseDir() {
        val text = renderSkillContent(
            SkillContent(
                name = "demo",
                description = "d",
                provider = "filesystem",
                directory = "/tmp/skills/demo",
                content = "# Demo\nDo the thing.",
            ),
        )
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
            text,
        )
    }

    @Test
    fun escapeTextProtectsFramingTags() {
        assertEquals("a &lt;b&gt; &amp; c", escapeText("a <b> & c"))
    }

    @Test
    fun sourceRoundTripKeepsEntries() {
        val notice = SkillCatalog.Notice(
            text = "t",
            entries = listOf(SkillCatalogEntry("a", "d1"), SkillCatalogEntry("b", "d2")),
            incomplete = false,
            update = false,
        )
        val source = SkillCatalog.source(notice)
        assertEquals(notice.entries, assertNotNull(SkillCatalog.readEntries(source)))
        assertNull(SkillCatalog.readEntries(buildJsonObject { put("kind", "skill-catalog") }))
    }
}

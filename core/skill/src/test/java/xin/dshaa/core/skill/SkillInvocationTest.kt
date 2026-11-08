package xin.dshaa.core.skill

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** M21：用户 `/名字` 手势与「用户可调用」加载路径。 */
class SkillInvocationTest {

    private fun tempDir(prefix: String = "skill-invoke"): File =
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

    // ---------------- 手势 ----------------

    @Test
    fun gestureMatchesWhitespaceBoundedSkillNames() {
        assertEquals(listOf("demo"), invokedSkillNames(listOf("/demo")))
        assertEquals(listOf("demo-skill", "other"), invokedSkillNames(listOf("please use /demo-skill and /other")))
        assertEquals(listOf("a", "b"), invokedSkillNames(listOf("/a /b /a")))
        assertEquals(listOf("x"), invokedSkillNames(listOf("email@host and /x")))
        assertEquals(emptyList(), invokedSkillNames(listOf("5/8")))
        assertEquals(emptyList(), invokedSkillNames(listOf("/Demo")))
        assertEquals(emptyList(), invokedSkillNames(listOf("/foo_bar")))
        assertEquals(emptyList(), invokedSkillNames(listOf("/foo/bar")))
    }

    @Test
    fun gestureScansEveryTextBlockInFirstSeenOrder() {
        assertEquals(
            listOf("one", "two", "three"),
            invokedSkillNames(listOf("start /one", "middle /two and /one", "end /three")),
        )
    }

    // ---------------- 用户可调用加载 ----------------

    @Test
    fun userInvocationLoadsDisableModelInvocationSkill() {
        val root = tempDir()
        skillFile(root, "hidden", "Only the user can call this.", "HIDDEN-BODY", "disable-model-invocation: true")
        val skills = FileSystemSkills(listOf(SkillRoot(root, "project-dsh", 100)))

        assertIs<SkillLoad.NotModelInvocable>(skills.load("hidden"))
        val loaded = assertIs<SkillLoad.Ok>(skills.loadForUser("hidden"))
        assertEquals("HIDDEN-BODY", loaded.skill.content)
        assertTrue(!loaded.skill.invocation.modelInvocable)
    }

    @Test
    fun userInvocationRejectsUserInvocableFalse() {
        val root = tempDir()
        skillFile(root, "no-user", "Not for users.", "BODY", "user-invocable: false")
        val skills = FileSystemSkills(listOf(SkillRoot(root, "project-dsh", 100)))

        assertIs<SkillLoad.Unknown>(skills.loadForUser("no-user"))
        assertIs<SkillLoad.Unknown>(skills.loadForUser("missing"))
        assertIs<SkillLoad.InvalidName>(skills.loadForUser("Bad_Name"))
    }
}

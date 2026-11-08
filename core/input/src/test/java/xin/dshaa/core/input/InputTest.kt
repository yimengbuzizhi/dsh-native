package xin.dshaa.core.input

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CommandLineTest {

    @Test
    fun parsesExactUpstreamGrammar() {
        assertEquals(CommandInvocation("plan", ""), parseCommandLine("/plan"))
        assertEquals(CommandInvocation("plan", " off"), parseCommandLine("/plan off"))
        assertEquals(CommandInvocation("plan", "  message with spaces"), parseCommandLine("/plan  message with spaces"))
        assertEquals(CommandInvocation("a-b_c2", "\nx"), parseCommandLine("/a-b_c2\nx"))
        assertEquals(CommandInvocation("x", "\ty"), parseCommandLine("/x\ty"))
    }

    @Test
    fun rejectsNonCommandLines() {
        assertNull(parseCommandLine(""))
        assertNull(parseCommandLine("/"))
        assertNull(parseCommandLine(" //plan"))
        assertNull(parseCommandLine("hello /plan"))
        assertNull(parseCommandLine("/Plan"))
        assertNull(parseCommandLine("/1plan"))
        assertNull(parseCommandLine("/plan!"))
        assertNull(parseCommandLine("/plan/x"))
        assertEquals(CommandInvocation("plan-x", " y"), parseCommandLine("/plan-x y"))
        assertNull(parseCommandLine("plan"))
    }
}

class InputTriggerTest {

    @Test
    fun detectsCommandAtStartAndAfterWhitespace() {
        val a = assertNotNull(inputTrigger("/pl", 3))
        assertEquals(InputTriggerKind.COMMAND, a.kind)
        assertEquals(0, a.start)
        assertEquals(3, a.end)
        assertEquals("pl", a.query)

        val b = assertNotNull(inputTrigger("hello /pl", 9))
        assertEquals(6, b.start)
        assertEquals("pl", b.query)

        val c = assertNotNull(inputTrigger("/pl an", 3))
        assertEquals(0, c.start)
        assertEquals(3, c.end) // token 到空格为止
    }

    @Test
    fun doesNotTriggerInsideWordsOrAtCursorAfterSpace() {
        assertNull(inputTrigger("hello/pl", 8))
        assertNull(inputTrigger("hello /pl ", 10))
        assertNull(inputTrigger("5/8", 3))
        assertNull(inputTrigger("", 0))
    }

    @Test
    fun detectsFileMention() {
        val a = assertNotNull(inputTrigger("check @hel", 10))
        assertEquals(InputTriggerKind.FILE, a.kind)
        assertEquals(6, a.start)
        assertEquals("hel", a.query)
        assertNull(inputTrigger("mail@host", 9))
    }

    @Test
    fun quotedFileMentionAllowsSpaces() {
        val a = assertNotNull(inputTrigger("read @\"my file", 14))
        assertEquals(InputTriggerKind.FILE, a.kind)
        assertTrue(a.quoted)
        assertEquals("my file", a.query)
        assertEquals(5, a.start)

        // 光标在闭合引号之后就关掉菜单
        assertNull(inputTrigger("read @\"my file\" x", 16))
        val b = assertNotNull(inputTrigger("read @\"my file\"", 14))
        assertEquals("my file", b.query)
        assertEquals(15, b.end)
    }

    @Test
    fun mentionFormatting() {
        assertEquals("@hello.md", formatFileMention("hello.md", false))
        assertEquals("@dir/", formatFileMention("dir", true))
        assertEquals("@\"a b/c d.md\"", formatFileMention("a b/c d.md", false))
        assertEquals("@\"a b/\"", formatFileMention("a b/", true))
        assertNull(formatFileMention("a\"b", false))
        assertNull(formatFileMention("", false))
    }
}

class FileReferenceSearchTest {

    private fun workspace(): File {
        val root = Files.createTempDirectory("file-ref").toFile()
        File(root, "hello.md").writeText("hi")
        File(root, "src/main").mkdirs()
        File(root, "src/main/Main.kt").writeText("fun main() {}")
        File(root, "src/test").mkdirs()
        File(root, "build").mkdirs()
        File(root, "build/junk.txt").writeText("junk")
        File(root, "node_modules").mkdirs()
        File(root, "node_modules/x.js").writeText("x")
        File(root, "with space.txt").writeText("s")
        return root
    }

    @Test
    fun emptyQueryListsTopLevelDirectoriesFirst() {
        val search = FileReferenceSearch(workspace())
        val result = search.candidates("")
        assertEquals("src", result.first().path)
        assertTrue(result.first().directory)
        assertTrue(result.any { it.path == "hello.md" && !it.directory })
        assertTrue(result.none { it.path.startsWith("build/") || it.path.startsWith("node_modules/") })
    }

    @Test
    fun bareQueryFindsNestedPathsAndExcludesBuildDirs() {
        val search = FileReferenceSearch(workspace())
        assertEquals("hello.md", search.candidates("hello").first().path)
        assertEquals("src/main", search.candidates("main").first().path)
        assertEquals("src/main/Main.kt", search.candidates("Main.kt").first().path)
        assertEquals("src/main/Main.kt", search.candidates("smk").first().path)
        assertTrue(search.candidates("junk").isEmpty())
        assertTrue(search.candidates("x.js").isEmpty())
    }

    @Test
    fun pathQueryListsDirectoryEntries() {
        val search = FileReferenceSearch(workspace())
        val result = search.candidates("src/")
        assertTrue(result.any { it.path == "src/main" && it.directory })
        assertTrue(result.any { it.path == "src/test" && it.directory })
        assertTrue(result.none { it.path.startsWith("src/main/") })

        val nested = search.candidates("src/main/M")
        assertEquals("src/main/Main.kt", nested.first().path)
    }

    @Test
    fun invalidateRebuildsIndex() {
        val root = workspace()
        val search = FileReferenceSearch(root)
        assertTrue(search.candidates("newfile").isEmpty())
        File(root, "newfile.txt").writeText("x")
        search.invalidate()
        assertEquals("newfile.txt", search.candidates("newfile").first().path)
    }

    @Test
    fun pathsWithSpacesStillFound() {
        val search = FileReferenceSearch(workspace())
        assertEquals("with space.txt", search.candidates("space").first().path)
        assertEquals("@\"with space.txt\"", formatFileMention("with space.txt", false))
    }

    @Test
    fun maxResultsIsBounded() {
        val root = Files.createTempDirectory("file-ref-many").toFile()
        repeat(40) { File(root, "file-$it.txt").writeText("x") }
        val search = FileReferenceSearch(root, maxResults = 20)
        assertEquals(20, search.candidates("file").size)
    }
}

class RankByNameTest {

    @Test
    fun prefixFirstThenSubsequenceThenOriginalOrder() {
        val names = listOf("plan", "permission", "compact", "p-l-a-n", "help")
        assertEquals(listOf("plan", "permission", "p-l-a-n", "compact", "help"), rankByName(names, "p"))
        assertEquals(listOf("plan", "p-l-a-n"), rankByName(names, "plan"))
        assertEquals(names, rankByName(names, ""))
        assertEquals(listOf("permission"), rankByName(names, "perm"))
    }

    @Test
    fun caseInsensitive() {
        assertEquals(listOf("Plan"), rankByName(listOf("Plan"), "pl"))
    }
}

package xin.dshaa.core.tools.fs

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import xin.dshaa.core.tools.ToolContext
import xin.dshaa.core.tools.ToolResult
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * docs/187 §8.4：`write` 的 append 语义（分块写大文件）+ `edit` 的大文本阈值。
 *
 * `write` / `edit` 的 16MB 硬守卫在 `:core:engine` 的 `ToolInputGuard`（ToolInputGuardTest 覆盖），
 * 这里只测工具实现本身：追加/覆盖/自动建目录/UTF-8，以及 edit 单侧 >2MB 时引导改用 write append。
 */
class WriteEditToolTest {

    private val workspace: File = Files.createTempDirectory("write-edit-test").toFile()

    private val ctx = ToolContext(workspace = workspace)

    @AfterTest
    fun cleanup() {
        runCatching { workspace.deleteRecursively() }
    }

    private fun args(vararg pairs: Pair<String, Any?>): JsonObject = buildJsonObject {
        pairs.forEach { (key, value) ->
            when (value) {
                null -> Unit
                is String -> put(key, value)
                is Boolean -> put(key, value)
                else -> error("unsupported fixture value: $value")
            }
        }
    }

    private fun write(path: String, content: String, append: Boolean): ToolResult = runBlocking {
        WriteTool().execute(args("file_path" to path, "content" to content, "append" to append), ctx)
    }

    private fun edit(path: String, old: String, new: String): ToolResult = runBlocking {
        EditTool().execute(args("file_path" to path, "old_string" to old, "new_string" to new), ctx)
    }

    @Test
    fun appendFalseCreatesThenAppendTrueAppends() {
        val file = File(workspace, "notes/a.txt")
        val first = write("notes/a.txt", "A", append = false)
        assertFalse(first.isError, first.text)
        assertTrue(first.text.contains("Created file"), first.text)
        assertEquals("A", file.readText(Charsets.UTF_8))

        val second = write("notes/a.txt", "B", append = true)
        assertFalse(second.isError, second.text)
        assertTrue(second.text.contains("Appended to file"), second.text)
        assertEquals("AB", file.readText(Charsets.UTF_8))
    }

    @Test
    fun appendFalseOverwritesExistingFile() {
        assertFalse(write("over.txt", "first", append = false).isError)
        assertFalse(write("over.txt", "second", append = false).isError)
        assertEquals("second", File(workspace, "over.txt").readText(Charsets.UTF_8))
        assertTrue(write("over.txt", "third", append = false).text.contains("Updated file"))
    }

    @Test
    fun appendCreatesMissingFileAndParentDirectories() {
        val file = File(workspace, "deep/nested/dir/new.txt")
        assertFalse(file.exists())
        val result = write("deep/nested/dir/new.txt", "鲸歌", append = true)
        assertFalse(result.isError, result.text)
        assertTrue(file.isFile)
        assertEquals("鲸歌", file.readText(Charsets.UTF_8))
        assertEquals(6L, file.length(), "UTF-8 中文 2 字 = 6 字节")
    }

    @Test
    fun chunksConcatenateInOrder() {
        // 3 次「第一次 append=false 建文件、后续 append=true 追加」拼出完整内容（大文件的实际用法）。
        val chunk1 = "a".repeat(300 * 1024)
        val chunk2 = "b".repeat(300 * 1024)
        val chunk3 = "c".repeat(300 * 1024)
        assertFalse(write("big.txt", chunk1, append = false).isError)
        assertFalse(write("big.txt", chunk2, append = true).isError)
        assertFalse(write("big.txt", chunk3, append = true).isError)
        assertEquals(900L * 1024, File(workspace, "big.txt").length())
        assertEquals(chunk1 + chunk2 + chunk3, File(workspace, "big.txt").readText(Charsets.UTF_8))
    }

    @Test
    fun editAcceptsSixHundredKbText() {
        // docs/187 §8.4.4：600KB 的 edit 不再被旧 512KB 守卫拒绝。
        val original = "HEAD\n" + "a".repeat(600 * 1024) + "\nTAIL\n"
        val file = File(workspace, "big-edit.txt")
        file.writeText(original, Charsets.UTF_8)
        val old = "a".repeat(600 * 1024)
        val new = "b".repeat(600 * 1024)
        val result = edit("big-edit.txt", old, new)
        assertFalse(result.isError, result.text)
        assertEquals("HEAD\n" + new + "\nTAIL\n", file.readText(Charsets.UTF_8))
    }

    @Test
    fun editOverTwoMbOnOneSidePointsAtWriteAppend() {
        val old = "a".repeat(2 * 1024 * 1024 + 1)
        val result = edit("whatever.txt", old, "b")
        assertTrue(result.isError)
        assertTrue(result.text.contains("append"), result.text)
        assertTrue(result.text.contains("write"), result.text)
    }

    @Test
    fun emptyContentStillCreatesEmptyFile() {
        // 「内容非空但写后 0 字节」才算失败；空内容建个空文件是合法用法。
        val result = write("empty.txt", "", append = false)
        assertFalse(result.isError, result.text)
        assertEquals(0L, File(workspace, "empty.txt").length())
    }
}

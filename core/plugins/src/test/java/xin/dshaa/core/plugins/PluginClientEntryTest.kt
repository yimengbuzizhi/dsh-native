package xin.dshaa.core.plugins

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * M70：`dsh.client` 入口解析的单测（不碰 Android/WebView）。
 *
 * 覆盖真实社区插件的三种清单写法：
 * - `exports["./client"]` 字符串（dsh-client-ui-pet / dsh-dafeiyu-pet）；
 * - `exports["./client"].default` 对象（部分插件）；
 * - 无 exports 时按 `lib/client.js` 约定（dsh-plugin-whale）。
 */
class PluginClientEntryTest {

    private fun dir(vararg files: Pair<String, String>): File {
        val root = Files.createTempDirectory("client-entry").toFile()
        files.forEach { (path, text) ->
            File(root, path).apply { parentFile?.mkdirs() }.writeText(text, Charsets.UTF_8)
        }
        return root
    }

    private fun packageJson(body: String): Pair<String, String> = "package.json" to body

    @Test
    fun exportsStringResolvesAndStripsDotSlash() {
        val root = dir(
            packageJson(
                """{"name":"x","dsh":{"client":{"platform":"web"}},"exports":{"./client":"./lib/client.js"}}""",
            ),
            "lib/client.js" to "window.__ModuleLoader__.load({});",
        )
        val result = PluginClientEntry.resolve(root)
        assertTrue(result is PluginClientEntry.Result.Resolved)
        assertEquals("lib/client.js", (result as PluginClientEntry.Result.Resolved).entry)
    }

    @Test
    fun exportsDefaultObjectResolves() {
        val root = dir(
            packageJson(
                """{"name":"x","dsh":{"client":{}},"exports":{"./client":{"default":"./dist/client.js"}}}""",
            ),
            "dist/client.js" to "1",
        )
        val result = PluginClientEntry.resolve(root) as PluginClientEntry.Result.Resolved
        assertEquals("dist/client.js", result.entry)
    }

    @Test
    fun fallsBackToLibClientJs() {
        val root = dir(
            packageJson("""{"name":"x","dsh":{"client":{"platform":"web"}}}"""),
            "lib/client.js" to "1",
        )
        val result = PluginClientEntry.resolve(root) as PluginClientEntry.Result.Resolved
        assertEquals("lib/client.js", result.entry)
    }

    @Test
    fun missingClientSectionIsUnsupported() {
        val root = dir(packageJson("""{"name":"x","dsh":{"bundle":{"patch":"./cordis.patch.yml"}}}"""))
        val result = PluginClientEntry.resolve(root) as PluginClientEntry.Result.Unsupported
        assertTrue("dsh.client" in result.reason)
    }

    @Test
    fun nonWebPlatformIsUnsupported() {
        val root = dir(
            packageJson("""{"name":"x","dsh":{"client":{"platform":"electron"}}}"""),
            "lib/client.js" to "1",
        )
        val result = PluginClientEntry.resolve(root) as PluginClientEntry.Result.Unsupported
        assertTrue("electron" in result.reason)
    }

    @Test
    fun declaredButMissingEntryIsUnsupported() {
        val root = dir(
            packageJson("""{"name":"x","dsh":{"client":{"platform":"web"}},"exports":{"./client":"./nope.js"}}"""),
        )
        val result = PluginClientEntry.resolve(root) as PluginClientEntry.Result.Unsupported
        assertTrue("nope.js" in result.reason)
    }
}

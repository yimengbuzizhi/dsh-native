package xin.dshaa.core.input

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** M40/M41：`/` 菜单的宿主命令表、`/help` 插入文本、`/permission` 参数归一化。 */
class CommandCatalogTest {

    @Test
    fun registeredCommandsAreParseableAndUnique() {
        val names = DSH_COMMANDS.map { it.name }
        assertEquals(names.distinct(), names, "命令名不能重复")
        names.forEach { name ->
            // 菜单里出现的名字必须能被 parseCommandLine 解析出来，否则点了会变成「未知命令」
            val parsed = parseCommandLine("/$name")
            assertNotNull(parsed, "/$name 不是合法命令语法")
            assertEquals(name, parsed.name)
            assertEquals(DEFAULT_COMMAND, parsed.rawInput)
        }
    }

    @Test
    fun catalogContainsPlanModelPermissionCompactHelp() {
        assertEquals(listOf("plan", "model", "permission", "compact", "help"), DSH_COMMANDS.map { it.name })
        DSH_COMMANDS.forEach { command ->
            assertTrue(command.description.isNotBlank(), "${command.name} 缺说明")
        }
        assertEquals("/plan [off|消息]", findCommand("plan")?.display())
        assertEquals("/model [名字]", findCommand("model")?.display())
        assertEquals("/permission [auto|ask]", findCommand("permission")?.display())
        // M43：/compact 不带参数（上游 `Usage: /compact (no arguments)`）
        assertEquals("/compact", findCommand("compact")?.display())
        assertEquals("/help [命令名]", findCommand("help")?.display())
        assertNull(findCommand("goal"), "/goal 还没做，不能挂进菜单")
    }

    @Test
    fun helpInsertTextLeavesCursorAfterCommand() {
        // 帮助面板点一行：带参数的命令补空格（光标落在参数位），不带参数的只放名字
        assertEquals("/plan ", findCommand("plan")?.insertText())
        assertEquals("/model ", findCommand("model")?.insertText())
        assertEquals("/permission ", findCommand("permission")?.insertText())
        assertEquals("/help ", findCommand("help")?.insertText())
        // insertText() 的结果本身必须仍能被命令解析器认出来（否则点了帮助反而变「未知命令」）
        DSH_COMMANDS.forEach { command ->
            val inserted = command.insertText()
            val parsed = parseCommandLine(inserted)
            assertNotNull(parsed, "${command.name} 的插入文本解析失败：$inserted")
            assertEquals(command.name, parsed.name)
        }
    }

    @Test
    fun approvalArgumentNormalizesAliases() {
        assertEquals("auto", parseApprovalArg("auto"))
        assertEquals("auto", parseApprovalArg(" ALWAYS "))
        assertEquals("auto", parseApprovalArg("allow"))
        assertEquals("ask", parseApprovalArg("ask"))
        assertEquals("ask", parseApprovalArg("confirm"))
        assertEquals("ask", parseApprovalArg("every"))
        // 空 = 不带参数（打开面板）；其他 = 非法，调用方给用法提示
        assertNull(parseApprovalArg(""))
        assertNull(parseApprovalArg("   "))
        assertNull(parseApprovalArg("deny"))
        assertNull(parseApprovalArg("yes"))
    }

    private companion object {
        /** `parseCommandLine("/plan")` 的 rawInput 是空串；这里借它验证名字后面没有残留参数。 */
        const val DEFAULT_COMMAND = ""
    }
}

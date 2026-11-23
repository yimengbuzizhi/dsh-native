package xin.dshaa.nativeapp.device.tools

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import xin.dshaa.core.tools.DshTool
import xin.dshaa.core.tools.ToolContext
import xin.dshaa.core.tools.ToolResult
import xin.dshaa.nativeapp.data.BuiltinPluginStore
import xin.dshaa.nativeapp.data.BuiltinPlugins
import xin.dshaa.nativeapp.data.CapabilityHost
import java.io.File

/**
 * M88-C（docs/183 §3）：`capability` + `notify` 进模型工具表。
 *
 * 用户反馈「能通知，但 AI 没用工具调用」的根因是能力没进工具表；这两个工具补上后，
 * 未知 id 要列出可用 id，notify 没系统权限要给可行动错误，内置组关掉时两个工具都不该注册。
 */
class CapabilityToolsTest {

    private val calls = mutableListOf<Pair<String, String?>>()
    private var notifyAllowed = true

    private fun tools(invokeResult: (String) -> CapabilityHost.InvokeResult = { id ->
        CapabilityHost.InvokeResult(ok = true, valueJson = "{\"ok\":true,\"id\":\"$id\"}")
    }): Map<String, DshTool> = CapabilityTools(
        invoke = { id, args ->
            calls += id to args
            invokeResult(id)
        },
        availableIds = { listOf("notify", "notify.toast", "clipboard.read", "shell.exec") },
        notificationsAllowed = { notifyAllowed },
    ).tools.associateBy { it.name }

    private fun exec(tools: Map<String, DshTool>, name: String, argsJson: String): ToolResult = runBlocking {
        tools.getValue(name).execute(Json.parseToJsonElement(argsJson).jsonObject, ToolContext(File(".")))
    }

    @Test
    fun capabilityAndNotifyAreTheOnlyTwoToolsInTheGroup() {
        val names = CapabilityTools(
            invoke = { _, _ -> CapabilityHost.InvokeResult(ok = true) },
            availableIds = { emptyList() },
            notificationsAllowed = { true },
        ).tools.map { it.name }.toSet()
        assertEquals(setOf("capability", "notify"), names)

        val group = BuiltinPlugins.byId(CapabilityTools.GROUP_ID) ?: error("缺内置组 builtin.capability")
        assertEquals(names, group.tools)
        assertEquals(CapabilityTools.GROUP_ID, BuiltinPlugins.byTool("capability")?.id)
        assertEquals(CapabilityTools.GROUP_ID, BuiltinPlugins.byTool("notify")?.id)

        // 关掉该组后两个工具的开关判定都为 false（syncBuiltinTools 据此注销 registry）。
        val off = setOf(CapabilityTools.GROUP_ID)
        assertFalse(BuiltinPluginStore.enabledFor(group.defaultEnabled, off, CapabilityTools.GROUP_ID))
        assertTrue(BuiltinPluginStore.enabledFor(group.defaultEnabled, emptySet(), CapabilityTools.GROUP_ID))
        assertTrue(BuiltinPlugins.byTool("capability") != null && BuiltinPlugins.byTool("notify") != null)
    }

    @Test
    fun unknownCapabilityListsAvailableIds() {
        val result = exec(tools(), "capability", """{"id":"nfc"}""")
        assertTrue(result.isError)
        assertTrue(result.text.contains("Unknown capability id: nfc"), result.text)
        assertTrue(result.text.contains("clipboard.read"), result.text)
        assertTrue(result.text.contains("capability permission"), result.text)
        assertTrue(calls.isEmpty(), "未知 id 不能下发到宿主")
    }

    @Test
    fun missingIdAndNonObjectArgsAreExplicit() {
        val missing = exec(tools(), "capability", "{}")
        assertTrue(missing.isError)
        assertTrue(missing.text.contains("capability requires id"), missing.text)

        val badArgs = exec(tools(), "capability", """{"id":"clipboard.read","args":"x"}""")
        assertTrue(badArgs.isError)
        assertTrue(badArgs.text.contains("capability args must be a JSON object"), badArgs.text)
        assertTrue(calls.isEmpty())
    }

    @Test
    fun knownCapabilityIsForwardedWithItsArgsObject() {
        val result = exec(tools(), "capability", """{"id":"clipboard.read","args":{"text":"hi"}}""")
        assertFalse(result.isError)
        assertEquals("clipboard.read", calls.single().first)
        assertEquals("""{"text":"hi"}""", calls.single().second)
        assertTrue(result.text.contains("\"ok\":true"))
    }

    @Test
    fun failingCapabilityBecomesAnErrorResult() {
        val result = exec(
            tools { CapabilityHost.InvokeResult(ok = false, error = "没有提供方", summary = "没有提供方") },
            "capability",
            """{"id":"shell.exec","args":{"command":"id"}}""",
        )
        assertTrue(result.isError)
        assertTrue(result.text.contains("没有提供方"), result.text)
    }

    @Test
    fun notifyWithoutSystemPermissionReturnsActionableError() {
        notifyAllowed = false
        val result = exec(tools(), "notify", """{"title":"hi","body":"there"}""")
        assertTrue(result.isError)
        assertTrue(result.text.contains("System notification permission is not granted"), result.text)
        assertTrue(result.text.contains("Settings → Permissions & Capabilities → Notifications"), result.text)
        assertTrue(result.text.contains("notify.toast"), result.text)
        assertTrue(calls.isEmpty(), "没权限时不能假装发过")
    }

    @Test
    fun notifyWithPermissionSendsThroughTheHost() {
        val result = exec(tools(), "notify", """{"title":"鲸歌","body":"验收"}""")
        assertFalse(result.isError)
        assertEquals("notify", calls.single().first)
        assertTrue(calls.single().second!!.contains("\"body\":\"验收\""))
        assertTrue(result.text.contains("Notification sent"), result.text)

        val empty = exec(tools(), "notify", "{}")
        assertTrue(empty.isError)
        assertTrue(empty.text.contains("at least one of title or body"), empty.text)
    }

    @Test
    fun mergedRootExecIsExplainedInsteadOfTreatedAsUnknown() {
        val result = exec(tools(), "capability", """{"id":"root.exec"}""")
        assertTrue(result.isError)
        assertTrue(result.text.contains("merged_into_shell_exec"), result.text)
        assertTrue(result.text.contains("root → Shizuku → App"), result.text)
    }
}

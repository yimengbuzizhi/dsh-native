package xin.dshaa.core.capability

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/** M85-F：capability/v1 的选择规则（插件优先内置 / 缺授权 / 停用回落 / 不可用如实报错）。 */
class CapabilityRoutingTest {

    private fun builtin(id: String, label: String = "内置 $id") = CapabilityCandidate(
        id = id,
        label = label,
        kind = "system",
        pluginId = null,
        builtin = true,
    )

    private fun plugin(
        id: String,
        pluginId: String = "demo.cap",
        enabled: Boolean = true,
        permitted: Boolean = true,
        label: String = "插件 $id",
    ) = CapabilityCandidate(
        id = id,
        label = label,
        kind = "demo",
        pluginId = pluginId,
        builtin = false,
        enabled = enabled,
        permitted = permitted,
    )

    @Test
    fun builtinOnlyResolves() {
        val result = CapabilityRouting.resolve("notify", listOf(builtin("notify")))
        val found = assertIs<CapabilityLookup.Found>(result)
        assertNull(found.capability.pluginId)
        assertEquals(true, found.capability.builtin)
        assertEquals("notify", found.capability.id)
    }

    @Test
    fun pluginOverridesBuiltinWithSameId() {
        val result = CapabilityRouting.resolve(
            "notify",
            listOf(builtin("notify"), plugin("notify", pluginId = "m85f.capability-demo")),
        )
        val found = assertIs<CapabilityLookup.Found>(result)
        assertEquals("m85f.capability-demo", found.capability.pluginId)
        assertEquals(false, found.capability.builtin)
        assertEquals("插件 notify", found.capability.label)
    }

    @Test
    fun disabledPluginFallsBackToBuiltin() {
        val result = CapabilityRouting.resolve(
            "notify",
            listOf(builtin("notify"), plugin("notify", enabled = false)),
        )
        val found = assertIs<CapabilityLookup.Found>(result)
        assertEquals(true, found.capability.builtin)
    }

    @Test
    fun unauthorizedPluginFallsBackToBuiltin() {
        val result = CapabilityRouting.resolve(
            "notify",
            listOf(builtin("notify"), plugin("notify", permitted = false)),
        )
        val found = assertIs<CapabilityLookup.Found>(result)
        assertEquals(true, found.capability.builtin)
    }

    @Test
    fun disabledPluginOnlyProviderIsUnavailable() {
        val result = CapabilityRouting.resolve("nfc", listOf(plugin("nfc", enabled = false)))
        val unavailable = assertIs<CapabilityLookup.Unavailable>(result)
        assertEquals("能力 nfc 的提供方插件 demo.cap 已停用。", unavailable.reason)
    }

    @Test
    fun unauthorizedPluginOnlyProviderIsUnavailable() {
        val result = CapabilityRouting.resolve("nfc", listOf(plugin("nfc", permitted = false)))
        val unavailable = assertIs<CapabilityLookup.Unavailable>(result)
        assertEquals("能力 nfc 的提供方插件 demo.cap 缺少 capability 授权。", unavailable.reason)
    }

    @Test
    fun missingProviderIsHonestUnavailable() {
        val result = CapabilityRouting.resolve("adb-shell", listOf(builtin("notify")))
        val unavailable = assertIs<CapabilityLookup.Unavailable>(result)
        assertEquals(true, unavailable.reason.contains("没有提供方"))
        assertEquals(true, unavailable.reason.contains("adb-shell"))
    }

    @Test
    fun invalidIdIsRejected() {
        assertNull(normalizeCapabilityId(""))
        assertNull(normalizeCapabilityId("bad id"))
        assertNull(normalizeCapabilityId("a".repeat(81)))
        assertEquals("clipboard.read", normalizeCapabilityId(" clipboard.read "))
        val result = CapabilityRouting.resolve("bad id", listOf(builtin("notify")))
        assertEquals(true, assertIs<CapabilityLookup.Unavailable>(result).reason.contains("不合法"))
    }

    @Test
    fun catalogDeduplicatesKeepingFirstProvider() {
        val catalog = CapabilityRouting.catalog(
            listOf(builtin("notify"), plugin("notify"), builtin("clipboard.read"), plugin("nfc")),
        )
        assertEquals(listOf("notify", "clipboard.read", "nfc"), catalog.map { it.id })
        assertEquals(true, catalog.first().builtin)
    }

    @Test
    fun pluginFirstInListWinsAmongPlugins() {
        val result = CapabilityRouting.resolve(
            "nfc",
            listOf(plugin("nfc", pluginId = "first"), plugin("nfc", pluginId = "second")),
        )
        val found = assertIs<CapabilityLookup.Found>(result)
        assertEquals("first", found.capability.pluginId)
    }
}

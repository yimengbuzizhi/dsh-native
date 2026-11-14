package xin.dshaa.core.plugins

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * M83 阶段 1：服务容器 / 依赖解析 / 能力清单的单测（纯 JVM，不碰 QuickJS）。
 *
 * 真机验收见 `docs/117-M83-阶段1-底盘骨架.md`：两个测试插件 A/B 的 provide/inject 与「关掉 A → B 缺服务」。
 */
class PluginServicesTest {

    private fun record(
        id: String,
        enabled: Boolean = true,
        provides: Map<String, String> = emptyMap(),
        injects: List<PluginDependencyRef> = emptyList(),
    ) = PluginRecord(
        id = id,
        name = id,
        version = "1.0.0",
        description = "",
        path = "/tmp/$id",
        main = "index.js",
        format = PluginFormat.DSH.label,
        enabled = enabled,
        tools = emptyList(),
        imports = emptyList(),
        error = null,
        installedAt = 0L,
        provides = provides,
        injects = injects,
    )

    @Test
    fun resolvesProviderAndDependent() {
        val a = record("a", provides = mapOf("clock" to """{"now":1}"""))
        val b = record("b", injects = listOf(PluginDependencyRef("clock")))
        val resolved = PluginServices.resolve(listOf(a, b))
        assertEquals("a", resolved.providers["clock"]?.ownerId)
        assertEquals("""{"now":1}""", resolved.providers["clock"]?.content)
        val edge = resolved.edges["b"]!!.single()
        assertTrue(edge.satisfied)
        assertEquals("a", edge.providerId)
        assertTrue(resolved.missing.isEmpty())
    }

    @Test
    fun reportsMissingWhenProviderDisabledOrAbsent() {
        val only = record("b", injects = listOf(PluginDependencyRef("clock")))
        val missing = PluginServices.resolve(listOf(only))
        assertEquals(listOf("clock"), missing.missing["b"])
        assertFalse(missing.edges["b"]!!.single().satisfied)

        // 关掉 A：A 不再提供服务，B 立刻变成缺依赖
        val a = record("a", enabled = false, provides = mapOf("clock" to "{}"))
        val resolved = PluginServices.resolve(listOf(a, only))
        assertNull(resolved.providers["clock"])
        assertEquals(listOf("clock"), resolved.missing["b"])
    }

    @Test
    fun optionalDependencyDoesNotBlock() {
        val b = record("b", injects = listOf(PluginDependencyRef("log", optional = true)))
        val resolved = PluginServices.resolve(listOf(b))
        assertTrue(resolved.missing.isEmpty())
        assertTrue(resolved.edges["b"]!!.single().satisfied)
    }

    @Test
    fun ordersProvidersBeforeDependents() {
        val a = record("a", provides = mapOf("clock" to "{}"))
        val b = record("b", injects = listOf(PluginDependencyRef("clock")))
        // 清单里故意把依赖者排在前面
        val order = PluginServices.topologicalOrder(listOf(b, a))
        assertTrue(order.indexOf("a") < order.indexOf("b"), "order=$order")
        val resolved = PluginServices.resolve(listOf(b, a))
        assertTrue(resolved.order.indexOf("a") < resolved.order.indexOf("b"), "order=${resolved.order}")
    }

    @Test
    fun detectsCyclesWithoutDroppingPlugins() {
        // a 依赖 b 的服务、b 依赖 a 的服务：环里的插件仍然都要在 order 里
        val a = record("a", provides = mapOf("sa" to "{}"), injects = listOf(PluginDependencyRef("sb")))
        val b = record("b", provides = mapOf("sb" to "{}"), injects = listOf(PluginDependencyRef("sa")))
        val resolved = PluginServices.resolve(listOf(a, b))
        assertEquals(setOf("a", "b"), resolved.order.toSet())
        assertEquals(setOf("a", "b"), resolved.cycles.toSet())
    }

    @Test
    fun snapshotSkipsDisabledAndSelf() {
        val a = record("a", provides = mapOf("clock" to "1"))
        val b = record("b", provides = mapOf("clock" to "2", "other" to "3"))
        val off = record("c", enabled = false, provides = mapOf("off" to "4"))
        val snapshot = PluginServices.snapshot(listOf(a, b, off), excludingOwner = "b")
        assertEquals(mapOf("clock" to "1"), snapshot)
    }

    @Test
    fun hostAnswersInjectValuesAndRecordsProvide() {
        val host = PluginServiceHost(initial = mapOf("clock" to """{"now":7}"""))
        val values = host.callSync(
            "plugin.services.values",
            """{"names":["clock","missing"]}""",
        )
        assertTrue(values!!.contains("\"clock\":\"{\\\"now\\\":7}\""))
        assertTrue(values.contains("\"missing\":[\"missing\"]"))

        val provided = host.callSync("plugin.services.provide", """{"name":"extra","value":"42"}""")
        assertTrue(provided!!.contains("\"ok\":true"))
        // 同一次 apply 里后写的 provide 后面的 inject 能看到
        val after = host.callSync("plugin.services.values", """{"names":["extra"]}""")
        assertTrue(after!!.contains("\"extra\":\"42\""))
        assertNull(host.callSync("plugin.services.unknown", "{}"))
    }

    @Test
    fun capabilityCatalogHasCorePrimitives() {
        assertTrue(PluginCapabilities.all.size >= 20)
        assertEquals("services.provide", PluginCapabilities.byId("services.provide")?.id)
        assertTrue(PluginCapabilities.byId("services.inject")!!.implemented)
        assertTrue(PluginCapabilities.byId("llm.register")!!.implemented)
        assertTrue(PluginCapabilities.byId("ui.route")!!.implemented)
        assertTrue(PluginCapabilities.byId("ui.page")!!.implemented)
        // registerView 仍是登记面；sessionProjections 已由常驻运行时实现（M84-H）
        assertFalse(PluginCapabilities.byId("ui.view")!!.implemented)
        assertTrue(PluginCapabilities.byId("session.projections")!!.implemented)
        assertEquals(
            1,
            PluginCapabilities.unsupported(listOf("ui.view", "session.projections", "tools.register")).size,
        )
    }
}

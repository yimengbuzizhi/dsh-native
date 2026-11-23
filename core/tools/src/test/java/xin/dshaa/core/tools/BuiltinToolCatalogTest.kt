package xin.dshaa.core.tools

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** M84-A2：内置工具目录的分组契约（App 层的 BuiltinPlugins 用同一套 id）。 */
class BuiltinToolCatalogTest {

    private val expectedGroupIds = setOf(
        "builtin.fs", "builtin.shell", "builtin.jobs", "builtin.web", "builtin.agent",
        "builtin.schedule", "builtin.goal", "builtin.plan", "builtin.interaction",
        "builtin.todo", "builtin.skill", "builtin.workflow",
    )

    @Test
    fun catalogCoversThirtyThreeToolsWithoutDuplicates() {
        // M88-D 加 http_request（32 → 33）；docs/187 §6.5 把它改名 `curl`（数量不变）。
        assertEquals(33, BuiltinToolCatalog.allTools().size)
        assertEquals(emptyList(), BuiltinToolCatalog.duplicateTools())
        assertTrue(BuiltinToolCatalog.allTools().any { it.name == "curl" })
        assertTrue(BuiltinToolCatalog.allTools().none { it.name == "http_request" })
        // 旧名字只做别名，不进工具表
        assertEquals(listOf("http_request"), BuiltinToolCatalog.aliases().map { it.name })
    }

    @Test
    fun groupIdsAreStable() {
        assertEquals(expectedGroupIds, BuiltinToolCatalog.groups.map { it.id }.toSet())
        assertEquals(expectedGroupIds.size, BuiltinToolCatalog.groups.size)
    }

    @Test
    fun everyGroupHasToolsAndIdsAreUnique() {
        val seen = mutableSetOf<String>()
        BuiltinToolCatalog.groups.forEach { group ->
            assertTrue(group.tools.isNotEmpty(), "empty group ${group.id}")
            assertTrue(seen.add(group.id), "duplicate group ${group.id}")
        }
    }
}

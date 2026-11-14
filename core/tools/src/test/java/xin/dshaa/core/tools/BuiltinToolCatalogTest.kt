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
    fun catalogCoversThirtyTwoToolsWithoutDuplicates() {
        assertEquals(32, BuiltinToolCatalog.allTools().size)
        assertEquals(emptyList(), BuiltinToolCatalog.duplicateTools())
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

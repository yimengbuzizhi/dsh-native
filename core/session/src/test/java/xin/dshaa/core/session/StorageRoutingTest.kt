package xin.dshaa.core.session

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** M85-E：storage/v1 选择规则（默认 / 同 id 覆盖 / 停用 / 缺授权 / native 强制 / 覆盖回退）。 */
class StorageRoutingTest {

    private fun builtin(enabled: Boolean = true) = StorageCandidate(
        storageId = BUILTIN_STORAGE_ID,
        label = "内置 JSONL",
        pluginId = null,
        builtin = true,
        enabled = enabled,
    )

    private fun plugin(
        storageId: String = BUILTIN_STORAGE_ID,
        pluginId: String = "demo.readonly",
        enabled: Boolean = true,
        permitted: Boolean = true,
        readOnly: Boolean = true,
    ) = StorageCandidate(
        storageId = storageId,
        label = "只读导出器",
        pluginId = pluginId,
        builtin = false,
        enabled = enabled,
        permitted = permitted,
        readOnly = readOnly,
    )

    @Test
    fun emptyPrefUsesBuiltinWhenNoOverride() {
        val result = StorageRouting.resolve("", listOf(builtin()))
        val active = assertIs<StorageResolution.Active>(result)
        assertTrue(active.storage.builtin)
        assertEquals(BUILTIN_STORAGE_ID, active.storage.storageId)
    }

    @Test
    fun sameIdPluginOverridesDefault() {
        val result = StorageRouting.resolve("", listOf(builtin(), plugin()))
        val active = assertIs<StorageResolution.Active>(result)
        assertEquals("demo.readonly", active.storage.pluginId)
        assertTrue(active.storage.readOnly)
    }

    @Test
    fun nativeForcesBuiltinEvenWithOverride() {
        val result = StorageRouting.resolve("native", listOf(builtin(), plugin()))
        val active = assertIs<StorageResolution.Active>(result)
        assertTrue(active.storage.builtin)
    }

    @Test
    fun disabledBuiltinWithoutOverrideIsUnavailable() {
        val result = StorageRouting.resolve("", listOf(builtin(enabled = false)))
        val unavailable = assertIs<StorageResolution.Unavailable>(result)
        assertTrue(unavailable.reason.contains("已停用"))
    }

    @Test
    fun disabledSameIdPluginFallsBackToBuiltin() {
        val result = StorageRouting.resolve("", listOf(builtin(), plugin(enabled = false)))
        val active = assertIs<StorageResolution.Active>(result)
        assertTrue(active.storage.builtin)
    }

    @Test
    fun missingPermissionIsUnavailable() {
        val result = StorageRouting.resolve("demo.readonly", listOf(builtin(), plugin(permitted = false)))
        val unavailable = assertIs<StorageResolution.Unavailable>(result)
        assertTrue(unavailable.reason.contains("storage 授权"))
    }

    @Test
    fun pluginIdSelectsItsFirstStore() {
        val result = StorageRouting.resolve("demo.readonly", listOf(builtin(), plugin(storageId = "export")))
        val active = assertIs<StorageResolution.Active>(result)
        assertEquals("export", active.storage.storageId)
        assertEquals("demo.readonly#export", StorageRouting.prefOf(active.storage))
    }

    @Test
    fun pluginAndStoreIdSelectsExact() {
        val result = StorageRouting.resolve("demo.readonly#export", listOf(builtin(), plugin(storageId = "export")))
        val active = assertIs<StorageResolution.Active>(result)
        assertEquals("export", active.storage.storageId)
    }

    @Test
    fun unknownPluginIsUnavailable() {
        val result = StorageRouting.resolve("nope", listOf(builtin()))
        val unavailable = assertIs<StorageResolution.Unavailable>(result)
        assertTrue(unavailable.reason.contains("未安装"))
    }

    @Test
    fun builtinPrefFallsBackToBuiltinAfterOverrideDisabled() {
        val result = StorageRouting.resolve(BUILTIN_STORAGE_ID, listOf(builtin(), plugin(enabled = false)))
        val active = assertIs<StorageResolution.Active>(result)
        assertTrue(active.storage.builtin)
        assertEquals("native", StorageRouting.prefOf(active.storage))
    }
}

package xin.dshaa.nativeapp.data

import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import xin.dshaa.core.plugins.*
import xin.dshaa.nativeapp.ui.boot.BootState
import java.io.File

class LoadingSnapshotStoreTest {
    @get:Rule val temp = TemporaryFolder()
    private fun loading(id: String = "screen", text: String = "hello") = LoadingSpec.parse(Json.parseToJsonElement(
        """{"id":"$id","label":"示例","tree":{"type":"text","text":"$text"}}"""
    )).loading!!
    private fun record(loadings: List<PluginLoading> = listOf(loading())) = PluginRecord(
        id = "local.loading", name = "demo", version = "1", description = "", path = "/not-read", main = "index.js",
        format = "dsh", enabled = true, tools = emptyList(), imports = emptyList(), error = null, installedAt = 1,
        permissions = listOf("ui"), grantedPermissions = listOf("ui"), loadings = loadings,
    )

    @Test fun readsOnlySnapshotWithoutPluginFilesOrRegistry() {
        val file = File(temp.root, "loading.json")
        val store = LoadingSnapshotStore(file)
        assertNull(store.read())
        val expected = store.refresh(listOf(record()), "local.loading#screen").snapshot
        assertEquals(expected, LoadingSnapshotStore(file).read("local.loading#screen"))
        assertNull(store.read("other#screen"))
        assertEquals(listOf("loading.json"), temp.root.list()!!.toList())
    }

    @Test fun refreshInvalidatesDisabledRevokedDeletedAndVersionChangedOwners() {
        val store = LoadingSnapshotStore(File(temp.root, "loading.json"))
        val original = record()
        val first = store.refresh(listOf(original), original.id).snapshot
        val newer = store.refresh(listOf(original.copy(version = "2")), original.id).snapshot
        assertNotEquals(first.revision, newer.revision)
        assertNotEquals(first.revision, store.refresh(listOf(original.copy(installedAt = 2)), original.id).snapshot.revision)
        assertNotEquals(first.revision, store.refresh(listOf(original.copy(loadings = listOf(loading(text = "new")))), original.id).snapshot.revision)
        assertNotEquals(first.revision, store.refresh(listOf(original.copy(grantedPermissions = listOf("ui", "fs:workspace"))), original.id).snapshot.revision)
        listOf(emptyList(), listOf(original.copy(enabled = false)), listOf(original.copy(grantedPermissions = emptyList())), listOf(original.copy(error = "broken"))).forEach { records ->
            val result = store.refresh(records, original.id)
            assertNotNull(result.unavailableReason)
            assertEquals(BuiltinLoadingPlugin.ID, result.snapshot.ownerPlugin)
            assertEquals(result.snapshot, store.read())
        }
    }

    @Test fun supportsMultipleDeclarationsAndRejectsCorruptOrOversizedSnapshot() {
        val file = File(temp.root, "loading.json")
        val store = LoadingSnapshotStore(file)
        val result = store.refresh(listOf(record(listOf(loading("one"), loading("two")))), "local.loading#two")
        assertEquals("two", result.snapshot.loading.id)
        file.writeText("{bad")
        assertNull(store.read())
        file.writeBytes(ByteArray(LoadingSnapshotStore.MAX_SNAPSHOT_BYTES + 1))
        assertNull(store.read())
        assertEquals(BuiltinLoadingPlugin.ID, store.refresh(emptyList(), null).snapshot.selection)
    }

    @Test fun readRejectsChangedTreeChecksumAndBootBindingsAreReadOnlyDisplay() {
        val file = File(temp.root, "loading.json")
        val store = LoadingSnapshotStore(file)
        store.refresh(listOf(record()), "local.loading")
        file.writeText(file.readText().replace("hello", "changed"))
        assertNull(store.read())
        val state = BootState(phase = "plugins", message = "准备插件", elapsedMs = 1234)
        assertEquals("plugins", state.binding("boot.phase"))
        assertEquals("准备插件", state.binding("boot.message"))
        assertEquals("1s", state.binding("boot.elapsed"))
        assertEquals("", state.binding("chat.messages"))
    }
}

package xin.dshaa.nativeapp.remote

import org.junit.Assert.*
import org.junit.Test

class RemoteProfileRecoveryTest {
    private class Preferences : RemoteProfilePreferences {
        val values = mutableMapOf(RemoteProfileStore.METADATA to "broken-original",
            RemoteProfileStore.LEGACY_TOKEN to "unrelated-legacy-token")
        var commits = 0
        var fail = false
        override fun string(key: String) = values[key]
        override fun long(key: String) = 0L
        override fun commit(values: Map<String, String>, removed: Set<String>): Boolean {
            commits++
            if (fail) return false
            this.values.putAll(values); removed.forEach { this.values.remove(it) }
            return true
        }
    }

    @Test fun controllerRecoveryReadPreservesCorruptMetadataAndNeverReimportsLegacyCredentials() {
        val prefs = Preferences()
        val before = prefs.values.toMap()
        val store = RemoteProfileStore(prefs, tolerateCorruption = true) { "fresh" }
        assertTrue(store.profiles().isEmpty()); assertNull(store.activeId); assertNotNull(store.loadError)
        assertNull(store.token("fresh")); assertFalse(store.select("missing")); assertFalse(store.remove("missing"))
        assertEquals(before, prefs.values); assertEquals(0, prefs.commits)
    }

    @Test fun onlyExplicitSuccessfulSaveReplacesCorruptMetadataWithoutUsingOldToken() {
        val prefs = Preferences()
        val store = RemoteProfileStore(prefs, tolerateCorruption = true) { "fresh" }
        prefs.fail = true
        assertThrows(IllegalStateException::class.java) { store.save(null, "New", "http://fixture", null) }
        assertEquals("broken-original", prefs.values[RemoteProfileStore.METADATA]); assertNotNull(store.loadError)
        prefs.fail = false
        assertEquals("fresh", store.save(null, "New", "http://fixture", null))
        assertNull(store.loadError); assertEquals("", store.token("fresh")); assertEquals("fresh", store.activeId)
        assertFalse(store.profiles().single().hasToken)
        assertEquals("unrelated-legacy-token", prefs.values[RemoteProfileStore.LEGACY_TOKEN])
        assertEquals(store.profiles(), RemoteProfileStore(prefs).profiles())
    }
}

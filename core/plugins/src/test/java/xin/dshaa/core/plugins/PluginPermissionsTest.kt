package xin.dshaa.core.plugins

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PluginPermissionsTest {

    @Test
    fun parsesKnownPermissionsIntoCanonicalForm() {
        val parsed = PluginPermissions.parseAll(
            listOf("fs:workspace", "FS:ANY", "net:API.Example.com", "shell", "ui", "notify", "device"),
        )
        assertEquals(
            listOf("fs:workspace", "fs:any", "net:api.example.com", "shell", "ui", "notify", "device"),
            parsed.map { it.raw },
        )
        assertEquals(PluginPermissionKind.NET_HOST, parsed[2].kind)
        assertEquals("api.example.com", parsed[2].target)
        assertTrue(parsed[2].dangerous)
        assertFalse(parsed[0].dangerous)
    }

    @Test
    fun unknownOrUrlLikePermissionsAreNotGuessed() {
        val unknown = PluginPermissions.parseAll(
            listOf(
                "net:https://api.example.com/v1",
                "net:api.example.com:443",
                "fs:readonly",
                "",
            ),
        )
        assertEquals(3, unknown.size)
        unknown.forEach {
            assertEquals(PluginPermissionKind.UNKNOWN, it.kind)
            assertFalse(it.implemented)
        }
    }

    @Test
    fun autoGrantOnlyWorkspaceAndUiAndOnlyImplemented() {
        val permissions = PluginPermissions.parseAll(listOf("fs:workspace", "ui", "net:api.example.com", "shell", "notify"))
        // ui 这一版还没实现，所以不自动授予（授权也不会生效）；fs:workspace 自动允许。
        assertEquals(setOf("fs:workspace"), PluginPermissions.autoGrants(permissions))
        assertEquals(
            listOf("net:api.example.com", "shell"),
            PluginPermissions.approvalNeeded(permissions).map { it.raw },
        )
    }

    @Test
    fun sanitizeDropsUnrequestedAndUnimplemented() {
        val permissions = PluginPermissions.parseAll(listOf("fs:workspace", "net:api.example.com", "notify"))
        val grants = PluginPermissions.sanitizeGrants(
            permissions,
            listOf("fs:workspace", "net:api.example.com", "net:evil.example.org", "notify", "shell"),
        )
        assertEquals(setOf("fs:workspace", "net:api.example.com"), grants)
        // 手改清单里出现 fs:any 但没申请 → 不能生效
        assertEquals(emptySet<String>(), PluginPermissions.sanitizeGrants(permissions, listOf("fs:any")))
    }

    @Test
    fun fsAnyImpliesWorkspace() {
        assertEquals(PluginFsMode.ANY, PluginPermissions.fsMode(listOf("fs:any")))
        assertEquals(PluginFsMode.WORKSPACE, PluginPermissions.fsMode(listOf("fs:workspace")))
        assertEquals(PluginFsMode.NONE, PluginPermissions.fsMode(emptyList()))
        assertTrue(
            PluginPermissions.isGranted(
                PluginPermissions.parse("fs:workspace")!!,
                listOf("fs:any"),
            ),
        )
    }

    @Test
    fun netHostCoversSubdomainsOnly() {
        val grants = listOf("net:api.example.com")
        assertTrue(PluginPermissions.netAllows(grants, "api.example.com"))
        assertTrue(PluginPermissions.netAllows(grants, "cdn.api.example.com"))
        assertFalse(PluginPermissions.netAllows(grants, "example.com"))
        assertFalse(PluginPermissions.netAllows(grants, "api.example.com.evil.net"))
        assertTrue(PluginPermissions.netAllows(listOf("net:any"), "anything.example"))
        assertFalse(PluginPermissions.netAllows(emptyList(), "api.example.com"))
    }

    @Test
    fun loopbackAndPrivateRangesDetected() {
        listOf("localhost", "127.0.0.1", "::1", "0.0.0.0", "169.254.1.1").forEach {
            assertTrue(PluginPermissions.isLoopbackOrLocal(it), it)
        }
        listOf("10.1.2.3", "192.168.1.1", "172.20.0.1").forEach {
            assertTrue(PluginPermissions.isPrivate(it), it)
        }
        assertFalse(PluginPermissions.isLoopbackOrLocal("api.example.com"))
        assertFalse(PluginPermissions.isPrivate("8.8.8.8"))
    }

    @Test
    fun packageManifestCarriesPermissions() {
        val parsed = PluginPackage.parse(
            listOf(
                PluginFile(
                    "package.json",
                    """{"name":"demo","main":"index.js","permissions":["fs:workspace","net:api.example.com"]}""".toByteArray(),
                ),
                PluginFile("index.js", "".toByteArray()),
            ),
        ) as PackageParse.Ok
        assertEquals(listOf("fs:workspace", "net:api.example.com"), parsed.manifest.permissions)
    }

    @Test
    fun broadStorageRootsAreDetected() {
        listOf("/", "/sdcard", "/mnt/sdcard/", "/storage/emulated/0", "/storage/emulated/1", "/storage/self/primary").forEach {
            assertTrue(PluginPermissions.isBroadStorageRoot(it), it)
        }
        listOf("/storage/emulated/0/Download", "/data/user/0/x/files/workspace").forEach {
            assertFalse(PluginPermissions.isBroadStorageRoot(it), it)
        }
    }

    @Test
    fun permissionLabelsAndExplanationsAreHumanReadable() {
        val net = PluginPermissions.parse("net:api.example.com")!!
        assertTrue(net.title.contains("api.example.com"))
        assertTrue(net.explain.contains("https"))
        val shell = PluginPermissions.parse("shell")!!
        assertTrue(shell.explain.contains("root"))
    }
}

package xin.dshaa.core.plugins

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * M37 插件清单热重载的纯逻辑单测：不碰 QuickJS/Android，只验记录合并规则。
 */
class PluginReloadTest {

    @Test
    fun manifestFieldsReplaceRecordButIdentityStays() {
        val record = record(version = "0.1.0", enabled = false)
        val manifest = PluginManifest(
            id = "com.example.cfg",
            name = "Config Plus",
            version = "0.2.0",
            description = "new description",
            main = "lib/main.js",
            format = PluginFormat.OPERIT,
            permissions = listOf("fs:workspace"),
        )
        val run = PluginDryRun(
            tools = listOf(PluginTool("cfg_echo", "echo", """{"type":"object","properties":{}}""")),
            imports = listOf("@deepseek-ai/dsh-tools"),
            configSchema = """{"type":"object","properties":{"city":{"type":"string","default":"Shanghai"}}}""",
        )

        val updated = PluginReload.apply(record, manifest, run)

        assertEquals("Config Plus", updated.name)
        assertEquals("0.2.0", updated.version)
        assertEquals("new description", updated.description)
        assertEquals("lib/main.js", updated.main)
        assertEquals("Operit", updated.format)
        assertEquals(listOf("cfg_echo"), updated.tools)
        assertEquals("echo", updated.toolDescriptions["cfg_echo"])
        assertEquals(listOf("@deepseek-ai/dsh-tools"), updated.imports)
        assertNull(updated.error)
        // 身份/目录关系不动
        assertEquals(record.id, updated.id)
        assertEquals(record.path, updated.path)
        assertEquals(false, updated.enabled)
        assertEquals(record.installedAt, updated.installedAt)
    }

    @Test
    fun newSchemaPrunesOldConfigAndRecomputesDefaults() {
        val record = record(
            config = """{"city":"Beijing","limit":9,"legacy":true}""",
            configDefaults = """{"city":"Beijing","limit":3}""",
            configSchema = """{"type":"object","properties":{"city":{"type":"string"},"limit":{"type":"number"}}}""",
        )
        val manifest = PluginManifest(
            id = record.id,
            name = "Cfg",
            version = "0.2.0",
            description = "",
            main = "index.js",
            format = PluginFormat.OPERIT,
            configSchema = jsonObject(
                """{"type":"object","properties":{"city":{"type":"string","default":"Shanghai"},"country":{"type":"string","default":"CN"}}}""",
            ),
            configDefaults = jsonObject("""{"city":"Shenzhen"}"""),
        )

        val updated = PluginReload.apply(record, manifest, PluginDryRun())

        // limit/legacy 不在新 schema 里 → 裁掉；city 保留用户值。
        assertEquals("""{"city":"Beijing"}""", updated.config)
        // 清单默认值覆盖 schema 默认值，country 用 schema 的 default。
        assertEquals("""{"city":"Shenzhen","country":"CN"}""", updated.configDefaults)
        assertEquals(
            """{"type":"object","properties":{"city":{"type":"string","default":"Shanghai"},"country":{"type":"string","default":"CN"}}}""",
            updated.configSchema,
        )
    }

    @Test
    fun exportedConfigWinsOverManifestSchema() {
        val record = record(config = """{"city":"Beijing","mode":"fast"}""")
        val manifest = PluginManifest(
            id = record.id,
            name = "Cfg",
            version = "0.2.0",
            description = "",
            main = "index.js",
            format = PluginFormat.OPERIT,
            configSchema = jsonObject("""{"type":"object","properties":{"city":{"type":"string"}}}"""),
        )
        // 插件导出了 Config（M30 风格）：导出优先，manifest 的 schema 不再生效。
        val run = PluginDryRun(
            configSchema = """{"type":"object","properties":{"mode":{"type":"string","default":"fast"}}}""",
        )

        val updated = PluginReload.apply(record, manifest, run)

        assertEquals("""{"type":"object","properties":{"mode":{"type":"string","default":"fast"}}}""", updated.configSchema)
        assertEquals("""{"mode":"fast"}""", updated.config)
        assertEquals("""{"mode":"fast"}""", updated.configDefaults)
    }

    @Test
    fun permissionsFollowManifestAndAutoGrantsAreRestored() {
        val record = record(
            permissions = listOf("fs:workspace", "net:api.example.com"),
            grantedPermissions = listOf("fs:workspace", "net:api.example.com"),
        )
        val manifest = PluginManifest(
            id = record.id,
            name = "Cfg",
            version = "0.2.0",
            description = "",
            main = "index.js",
            format = PluginFormat.OPERIT,
            // 撤销了 net 权限，新增 ui（未实现，不授予）
            permissions = listOf("ui", "fs:workspace"),
        )

        val updated = PluginReload.apply(record, manifest, PluginDryRun())

        assertEquals(listOf("ui", "fs:workspace"), updated.permissions)
        // 旧的 net 授权失效；fs:workspace 按安装时规则自动补授；ui 未实现不授予。
        assertEquals(listOf("fs:workspace"), updated.grantedPermissions)
    }

    @Test
    fun keepsDangerousGrantWhenStillRequested() {
        val record = record(
            permissions = listOf("net:api.example.com"),
            grantedPermissions = listOf("net:api.example.com"),
        )
        val manifest = PluginManifest(
            id = record.id,
            name = "Cfg",
            version = "0.2.0",
            description = "",
            main = "index.js",
            format = PluginFormat.OPERIT,
            permissions = listOf("net:api.example.com", "shell"),
        )

        val updated = PluginReload.apply(record, manifest, PluginDryRun())

        // 老授权保留；新增的危险权限 shell 不会自动授予。
        assertEquals(listOf("net:api.example.com"), updated.grantedPermissions)
        assertTrue("shell" in updated.permissions)
    }

    @Test
    fun keepsOldSchemaWhenManifestHasNoConfig() {
        val record = record(
            config = """{"city":"Beijing"}""",
            configSchema = """{"type":"object","properties":{"city":{"type":"string"}}}""",
            configDefaults = """{"city":"Beijing"}""",
        )
        val manifest = PluginManifest(
            id = record.id,
            name = "Cfg",
            version = "0.2.0",
            description = "",
            main = "index.js",
            format = PluginFormat.OPERIT,
        )

        // 清单没有 config、插件也没导出 Config：沿用旧 schema，别因为一次重载把配置清掉。
        val updated = PluginReload.apply(record, manifest, PluginDryRun())
        assertEquals(record.configSchema, updated.configSchema)
        assertEquals("""{"city":"Beijing"}""", updated.config)
    }

    private fun record(
        version: String = "0.1.0",
        enabled: Boolean = true,
        config: String = "{}",
        configSchema: String? = null,
        configDefaults: String = "{}",
        permissions: List<String> = emptyList(),
        grantedPermissions: List<String> = emptyList(),
    ): PluginRecord = PluginRecord(
        id = "com.example.cfg",
        name = "Cfg",
        version = version,
        description = "old description",
        path = "/data/plugins/com.example.cfg",
        main = "index.js",
        format = PluginFormat.OPERIT.label,
        enabled = enabled,
        tools = emptyList(),
        imports = emptyList(),
        error = "old error",
        installedAt = 123L,
        permissions = permissions,
        grantedPermissions = grantedPermissions,
        configSchema = configSchema,
        config = config,
        configDefaults = configDefaults,
    )

    private fun jsonObject(text: String) = Json.parseToJsonElement(text).jsonObject
}

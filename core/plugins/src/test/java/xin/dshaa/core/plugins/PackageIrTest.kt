package xin.dshaa.core.plugins

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * M87-G：统一包 IR 与多格式导入的 JVM 验收。
 *
 * 覆盖 docs/181 §3.6.7 的 1/3/5：四种入口 fixture（md / json / zip / DSH 包）、
 * 工坊导出→再导入 IR 相等、单文件误装的可读错误；另加旧记录迁移与贡献物落盘往返。
 */
class PackageIrTest {

    @Test
    fun importsMarkdownSkillWithFrontmatter() {
        val md = """
            ---
            kind: skill
            id: local.demo.skill
            name: 演示技能
            version: 1.2.0
            description: 一个演示技能
            permissions:
              - fs:workspace
              - shell
            ---
            # 演示技能

            按步骤做。
        """.trimIndent()
        val imported = ok(PackageImporter.importText(md, "demo.md"))
        assertEquals("local.demo.skill", imported.ir.id)
        assertEquals("演示技能", imported.ir.name)
        assertEquals("1.2.0", imported.ir.version)
        assertEquals(PackageFormat.WHALE_LITE, imported.ir.format)
        assertEquals(listOf("fs:workspace", "shell"), imported.ir.permissions)
        val contribution = imported.ir.contributions.single()
        assertEquals(ContributionKind.SKILL, contribution.kind)
        assertTrue(contribution.config.string("text").contains("按步骤做"))
        assertEquals(ImportSource.MARKDOWN, imported.source)
        // 单文件导入会生成 dsh.package.json，重新导入应当还是同一个 IR。
        val generated = imported.files.single { it.path == PackageImporter.MANIFEST_NAME }
            .bytes.toString(Charsets.UTF_8)
        assertEquals(imported.ir, decode(generated))
    }

    @Test
    fun markdownWithoutFrontmatterDefaultsToSkill() {
        val imported = ok(PackageImporter.importText("# 随手记\n\n把这件事记下来。", "note.md"))
        assertEquals(ContributionKind.SKILL, imported.ir.contributions.single().kind)
        assertEquals("随手记", imported.ir.name)
    }

    @Test
    fun importsSingleJsonTheme() {
        val json = """
            {"kind":"theme","id":"local.demo.theme","name":"海蓝","version":"1.0.0",
             "permissions":["ui"],
             "tokens":{"background":"#001122","accent":"#22ccff"}}
        """.trimIndent()
        val imported = ok(PackageImporter.importText(json, "theme.json"))
        val contribution = imported.ir.contributions.single()
        assertEquals(ContributionKind.THEME, contribution.kind)
        val tokens = contribution.config["tokens"] as kotlinx.serialization.json.JsonObject
        assertEquals("#22ccff", (tokens["accent"] as kotlinx.serialization.json.JsonPrimitive).content)
        assertEquals(listOf("ui"), imported.ir.permissions)
    }

    @Test
    fun importsWhaleLiteZipWithPageAndMacro() {
        val manifest = """
            {"id":"local.demo.page","name":"演示页面","version":"1.0.0","format":"whale-lite",
             "permissions":["ui:slot"],
             "contributions":[
               {"kind":"page","id":"home",
                "config":{"surface":{"type":"column","children":[{"type":"text","text":"你好"}]}},
                "assets":["pages/home.json"]},
               {"kind":"macro","id":"daily","config":{"steps":[{"command":"/help"}]}}
             ]}
        """.trimIndent()
        val zip = zipOf(
            "demo/dsh.package.json" to manifest,
            "demo/pages/home.json" to """{"type":"column","children":[]}""",
        )
        val imported = ok(PackageImporter.importFile(zip))
        assertEquals(ImportSource.DSH_PACKAGE, imported.source)
        assertEquals(listOf("page", "macro"), imported.ir.contributions.map { it.kind.id })
        assertTrue(imported.files.any { it.path == "pages/home.json" }, "顶层目录应被剥掉")
        assertTrue(imported.files.none { it.path.startsWith("demo/") })
    }

    @Test
    fun importsDshPackageAsScriptClientAndPreset() {
        val packageJson = """
            {"name":"dsh-demo","version":"2.0.0","description":"演示 DSH 包","main":"lib/index.js",
             "permissions":["ui","net:api.example.com"],
             "dsh":{"client":{"entry":"client.js"},"bundle":{"patch":"cordis.patch.yml"}}}
        """.trimIndent()
        val patch = """
            - insert:
                - name: '@deepseek-ai/dsh-agent-preset'
                  config:
                    id: whale-demo
                    name: 演示角色
                    description: 组合包预设
                    plugins:
                      - name: '@deepseek-ai/dsh-persona'
                        config:
                          prefix: 你是鲸歌助手
        """.trimIndent()
        val zip = zipOf(
            "package.json" to packageJson,
            "lib/index.js" to "export const apply = () => {}",
            "client.js" to "export const mount = () => {}",
            "cordis.patch.yml" to patch,
        )
        val imported = ok(PackageImporter.importFile(zip))
        assertEquals(ImportSource.DSH_MANIFEST, imported.source)
        assertEquals(PackageFormat.DSH, imported.ir.format)
        assertTrue(imported.ir.hasRuntimeScript())
        val kinds = imported.ir.contributions.map { it.kind }
        assertTrue(ContributionKind.SCRIPT in kinds)
        assertTrue(ContributionKind.CLIENT in kinds)
        val preset = imported.ir.contribution(ContributionKind.PRESET).single()
        assertEquals("whale-demo", preset.id)
        assertEquals("演示角色", preset.config.string("name"))
        assertEquals(imported.ir, roundTrip(imported.ir))
    }

    @Test
    fun importsOperitManifestAsLegacyJs() {
        val zip = zipOf(
            "manifest.json" to """{"id":"toolpkg.demo","name":"Demo","main":"index.js","permissions":["fs:workspace"]}""",
            "index.js" to "module.exports = {}",
        )
        val imported = ok(PackageImporter.importFile(zip))
        assertEquals(ImportSource.LEGACY_MANIFEST, imported.source)
        assertEquals(PackageFormat.LEGACY_JS, imported.ir.format)
        assertEquals("index.js", imported.ir.main)
        assertEquals(ContributionKind.SCRIPT, imported.ir.contributions.first().kind)
        assertEquals(imported.ir, roundTrip(imported.ir))
    }

    @Test
    fun roundTripKeepsEveryFixtureEqual() {
        val fixtures = listOf(
            PackageImporter.importText(
                "---\nkind: prompt\nid: local.demo.prompt\nname: 提示词\n---\n你是鲸歌。",
                "prompt.md",
            ),
            PackageImporter.importText(
                """{"kind":"macro","id":"local.demo.macro","name":"宏","steps":[{"command":"/help"}]}""",
                "macro.json",
            ),
            PackageImporter.importFile(
                zipOf(
                    "dsh.package.json" to
                        """{"id":"local.demo.asset","name":"素材","format":"whale-lite","contributions":[{"kind":"asset","id":"a","assets":["a.png"]}]}""",
                    "a.png" to "png",
                ),
            ),
        )
        fixtures.forEach { imported ->
            val bundle = ok(imported)
            assertEquals(bundle.ir, roundTrip(bundle.ir))
        }
    }

    @Test
    fun rejectsUnknownKindAndUnknownFields() {
        val unknownKind = failed(
            PackageImporter.importText("""{"kind":"decoration","id":"local.demo.deco","name":"装饰"}""", "deco.json"),
        )
        assertTrue(unknownKind.error.contains("认不出 kind"))

        val unknownField = failed(
            PackageImporter.importText(
                """{"id":"local.demo.x","name":"X","format":"whale-lite","contributions":[{"kind":"skill","id":"s","config":{"text":"hi"}}],"extra":1}""",
                PackageImporter.MANIFEST_NAME,
            ),
        )
        assertTrue(unknownField.error.contains("不认识的字段"))
    }

    @Test
    fun rejectsKindMissingRequiredConfigAndMissingEntry() {
        val missingSurface = failed(
            PackageImporter.importText(
                """{"id":"local.demo.page","name":"页","format":"whale-lite","contributions":[{"kind":"page","id":"home","config":{}}]}""",
                PackageImporter.MANIFEST_NAME,
            ),
        )
        assertTrue(missingSurface.error.contains("surface"))

        val missingEntry = failed(
            PackageImporter.importText(
                """{"id":"local.demo.script","name":"脚本","format":"legacy-js","contributions":[{"kind":"script","id":"s","entry":"lib/index.js"}]}""",
                PackageImporter.MANIFEST_NAME,
            ),
        )
        assertTrue(missingEntry.error.contains("找不到"))
    }

    @Test
    fun rejectsUnsafeAssetPathAndDuplicateContribution() {
        val unsafe = failed(
            PackageImporter.importText(
                """{"kind":"skill","id":"local.demo.skill","name":"S","config":{"text":"x"},"assets":["../escape.md"]}""",
                "s.json",
            ),
        )
        assertTrue(unsafe.error.contains("不安全"), unsafe.error)

        val duplicate = failed(
            PackageImporter.importText(
                """{"id":"local.demo.dup","name":"D","format":"whale-lite","contributions":[
                    {"kind":"skill","id":"s","config":{"text":"a"}},
                    {"kind":"skill","id":"s","config":{"text":"b"}}]}""",
                PackageImporter.MANIFEST_NAME,
            ),
        )
        assertTrue(duplicate.error.contains("重复"), duplicate.error)
    }

    @Test
    fun migratesOldRegistryRecordsToLegacyJsIr() {
        val dir = createTempDir()
        try {
            val file = File(dir, "plugins.json")
            val path = File(dir, "old").absolutePath.replace("\\", "\\\\")
            file.writeText(
                """
                {"version":1,"plugins":[{
                  "id":"old.plugin","name":"旧插件","version":"1.0.0","path":"$path",
                  "main":"index.js","format":"DSH","enabled":true,"tools":["t"],
                  "permissions":["fs:workspace"],"grantedPermissions":["fs:workspace"],
                  "clientUi":true,"installedAt":1
                }]}
                """.trimIndent(),
            )
            val record = PluginRegistryFile(file).list().single()
            assertEquals("legacy-js", record.irFormat)
            assertEquals("old.plugin", record.id)
            assertEquals(true, record.enabled)
            assertEquals(listOf("fs:workspace"), record.permissions)
            assertEquals(
                listOf(ContributionKind.SCRIPT, ContributionKind.CLIENT),
                record.contributions.map { it.kind },
            )
            assertEquals("index.js", record.contributions.first().entry)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun newContributionsSurviveRegistryRoundTrip() {
        val dir = createTempDir()
        try {
            val file = File(dir, "plugins.json")
            val registry = PluginRegistryFile(file)
            val record = PluginRecord(
                id = "local.demo.page",
                name = "演示页面",
                version = "1.0.0",
                description = "声明式",
                path = File(dir, "plugins/local.demo.page").absolutePath,
                main = "",
                format = PackageFormat.WHALE_LITE.id,
                irFormat = PackageFormat.WHALE_LITE.id,
                contributions = listOf(
                    PluginContribution(
                        kind = ContributionKind.PAGE,
                        id = "home",
                        config = kotlinx.serialization.json.Json.parseToJsonElement(
                            """{"surface":{"type":"column","children":[]}}""",
                        ) as kotlinx.serialization.json.JsonObject,
                        assets = listOf("pages/home.json"),
                    ),
                    PluginContribution(ContributionKind.MACRO, "daily"),
                ),
                enabled = false,
                tools = emptyList(),
                imports = emptyList(),
                error = null,
                installedAt = 2L,
                permissions = listOf("ui:slot"),
                toolMiddleware = true,
            )
            registry.upsert(record)
            assertEquals(record, registry.find(record.id))
            assertFalse(registry.find(record.id)!!.enabled)
            assertTrue(registry.find(record.id)!!.toolMiddleware, "M87-A 中间件标记必须落盘")
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun nonZipTextWithZipExtensionIsDetectedByContent() {
        // SAF 复制出来的临时文件永远叫 .zip：必须按内容判断，不能看扩展名。
        val file = File.createTempFile("plugin-import", ".zip")
        try {
            file.writeText("""{"kind":"theme","id":"local.demo.theme","name":"T","tokens":{"accent":"#fff"}}""")
            assertFalse(PackageImporter.isZip(file))
            ok(PackageImporter.importFile(file))
        } finally {
            file.delete()
        }
    }

    @Test
    fun importsMultiScriptWhaleLiteZipWithSyntheticEntry() {
        val manifest = """
            {"id":"local.demo.multi","name":"多脚本插件","version":"1.0.0","format":"whale-lite",
             "permissions":["fs:workspace"],
             "contributions":[
               {"kind":"script","id":"alpha","entry":"scripts/alpha.js"},
               {"kind":"script","id":"beta","entry":"scripts/beta.js"},
               {"kind":"asset","id":"docs","assets":["data/ref.txt"]}
             ]}
        """.trimIndent()
        val zip = zipOf(
            "dsh.package.json" to manifest,
            "scripts/alpha.js" to "export function apply(ctx) {}",
            "scripts/beta.js" to "export function apply(ctx) {}",
            "data/ref.txt" to "reference content",
        )
        val imported = ok(PackageImporter.importFile(zip))
        assertEquals(ImportSource.DSH_PACKAGE, imported.source)
        val syntheticManifest = imported.manifest
        assertTrue(syntheticManifest != null, "多脚本 dsh.package.json 应生成运行清单")
        assertEquals("__dsh_multi_entry.js", syntheticManifest!!.main)
        val bootstrap = imported.files.firstOrNull { it.path == "__dsh_multi_entry.js" }
            ?.bytes?.toString(Charsets.UTF_8).orEmpty()
        assertTrue(bootstrap.contains("./scripts/alpha.js"), bootstrap)
        assertTrue(bootstrap.contains("./scripts/beta.js"), bootstrap)
    }

    @Test
    fun importsStandaloneJsAndYamlFiles() {
        val js = ok(PackageImporter.importText("export function apply(ctx) {}", "quick-tool.js"))
        assertTrue(js.manifest != null)
        assertEquals("quick-tool.js", js.manifest!!.main)

        val yaml = ok(
            PackageImporter.importText(
                "kind: prompt\nid: local.demo.yaml\nname: YAML 提示\ncontent: 你好\n",
                "demo.yaml",
            ),
        )
        assertEquals("local.demo.yaml", yaml.ir.id)
        assertEquals(ContributionKind.PROMPT, yaml.ir.contributions.single().kind)
    }

    @Test
    fun summarizeZipSkipsNodeModulesAndDetectsDshPackage() {
        val zip = zipOf(
            "pkg/dsh.package.json" to """{"id":"a","name":"A","format":"whale-lite","contributions":[]}""",
            "pkg/index.js" to "export function apply() {}",
            "pkg/node_modules/foo/index.js" to "module.exports = 1;",
        )
        val summary = PluginPackage.summarizeZip(zip)
        assertTrue(summary != null)
        assertEquals("pkg/dsh.package.json", summary!!.manifestName)
        assertEquals("a", summary.id)
        assertFalse(summary.hasClient)
    }

    private fun ok(result: PackageImport): PackageImport.Ok {
        assertTrue(result is PackageImport.Ok, "导入失败：${(result as? PackageImport.Failed)?.error}")
        return result as PackageImport.Ok
    }

    private fun failed(result: PackageImport): PackageImport.Failed {
        assertTrue(result is PackageImport.Failed, "本应失败却导入成功：${(result as? PackageImport.Ok)?.ir}")
        return result as PackageImport.Failed
    }

    private fun roundTrip(ir: PackageIr): PackageIr = decode(PackageIrCodec.encode(ir))

    private fun decode(text: String): PackageIr {
        val read = PackageIrCodec.decode(text)
        assertTrue(read is IrRead.Ok, "IR 解码失败：${(read as? IrRead.Failed)?.error}\n$text")
        return (read as IrRead.Ok).ir
    }

    private fun kotlinx.serialization.json.JsonObject.string(key: String): String =
        (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty()

    private fun createTempDir(): File = File.createTempFile("plugins-ir-test", "").let { file ->
        file.delete()
        file.mkdirs()
        file
    }

    private fun zipOf(vararg entries: Pair<String, String>): File {
        val file = File.createTempFile("plugin-ir", ".zip")
        ZipOutputStream(file.outputStream()).use { out ->
            entries.forEach { (name, content) ->
                out.putNextEntry(ZipEntry(name))
                out.write(content.toByteArray(Charsets.UTF_8))
                out.closeEntry()
            }
        }
        file.deleteOnExit()
        return file
    }
}

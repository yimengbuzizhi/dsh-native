package xin.dshaa.nativeapp.node

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import xin.dshaa.nativeapp.data.BuiltinPlugins
import java.io.File

/**
 * docs/190-B：Node 宿主自治工具的契约 + 纯函数（doctor / repair 阶梯 / 限流 / 脱敏 / 隔离计数）。
 *
 * 这里不构造 [NodePluginHost]（需要 Android Context）：工具执行面在模拟器上验，
 * JVM 只锁「工具表/英文/只读与写类划分」和可脱离设备判定的算法。
 */
class NodeHostToolsTest {

    private val cjk = Regex("[\\u2E80-\\u9FFF\\uFF00-\\uFFEF]")

    private val expectedTools = setOf(
        "nodehost_status", "nodehost_plugins", "nodehost_logs", "nodehost_doctor",
        "nodehost_control", "nodehost_repair", "nodehost_autofix",
        "nodehost_install", "nodehost_uninstall",
    )

    @Test
    fun nineToolsAreRegisteredInTheBuiltinNodeHostGroup() {
        val group = BuiltinPlugins.byId(BuiltinPlugins.NODE_HOST) ?: error("缺内置组 builtin.node.host")
        assertTrue(group.defaultEnabled)
        assertEquals(expectedTools, group.tools)
        assertEquals(expectedTools, NodeHostTools.toolNames)
        assertEquals(expectedTools.size, NodeHostTools.definitions.size)
        expectedTools.forEach { name ->
            assertEquals("$name 未归属内置组", BuiltinPlugins.NODE_HOST, BuiltinPlugins.byTool(name)?.id)
        }
    }

    @Test
    fun readOnlyAndWriteToolsPartitionTheToolTable() {
        assertEquals(
            setOf("nodehost_status", "nodehost_plugins", "nodehost_logs", "nodehost_doctor"),
            NodeHostTools.readOnlyNames,
        )
        assertEquals(
            setOf(
                "nodehost_control", "nodehost_repair", "nodehost_autofix",
                "nodehost_install", "nodehost_uninstall",
            ),
            NodeHostTools.writeNames,
        )
        // 每个工具恰好属于一边：写类必须弹审批，只读类不能误进写名单。
        assertEquals(expectedTools, NodeHostTools.readOnlyNames + NodeHostTools.writeNames)
        assertTrue(NodeHostTools.readOnlyNames.intersect(NodeHostTools.writeNames).isEmpty())
    }

    @Test
    fun toolMetadataIsEnglishAndDescriptionsArePresent() {
        NodeHostTools.definitions.forEach { tool ->
            assertTrue("${tool.name} 缺 description", tool.description.orEmpty().isNotBlank())
            val hit = cjk.find(tool.description.orEmpty())
            assertTrue("${tool.name}.description 含中文: ${hit?.value}", hit == null)
            assertNoCjkInSchema(tool.inputSchema, "${tool.name}.inputSchema")
        }
    }

    @Test
    fun rateLimiterAllowsThreeActionsPerWindowThenBlocks() {
        val limiter = NodeHostRateLimiter()
        val now = 1_000_000L
        assertTrue(limiter.check(now).allowed)
        assertTrue(limiter.check(now + 1_000).allowed)
        assertTrue(limiter.check(now + 2_000).allowed)
        val blocked = limiter.check(now + 3_000)
        assertFalse(blocked.allowed)
        assertEquals(3, blocked.used)
        assertTrue("应给出重试等待时间", blocked.retryAfterMs > 0)
        // 窗口滑过后恢复：第 1 次的时间戳 + 5 分钟之后。
        assertTrue(limiter.check(now + 5 * 60_000L + 1).allowed)
    }

    @Test
    fun redactionRemovesTokensButKeepsPaths() {
        val bearer = NodeHostRedaction.redact("Authorization: Bearer abc123def456")
        assertTrue(bearer.contains("<redacted>"))
        assertFalse(bearer.contains("abc123def456"))

        val env = NodeHostRedaction.redact("DSH_NODE_TOKEN=deadbeefcafebabe")
        assertTrue(env.contains("<redacted>"))
        assertFalse(env.contains("deadbeefcafebabe"))

        val apiKey = NodeHostRedaction.redact("api_key=sk-0123456789abcdef")
        assertFalse(apiKey.contains("0123456789abcdef"))

        val path = NodeHostRedaction.redact("缺少 /data/user/0/xin.dshaa.nativeapp/lib/x86_64/libnode.so")
        assertTrue(path.contains("libnode.so"))
        assertEquals(path, NodeHostRedaction.redact(path))
    }

    @Test
    fun doctorFlagsMissingLibnodeForTheCurrentAbi() {
        val report = NodeHostDoctor.evaluate(input())
        assertFalse(report.ok)
        val finding = report.findings.first { it.code == "missing_libnode_abi" }
        assertEquals(NodeHostDoctor.SEVERITY_ERROR, finding.severity)
        assertTrue("应说清缺的是 libnode.so: ${finding.detail}", finding.detail.contains("libnode.so"))
        assertTrue("应说清 ABI: ${finding.detail}", finding.detail.contains("x86_64"))
        assertEquals("run-on-arm64", finding.fix)
        // 同一次失败还应该有 state_failed（宿主现在就是 FAILED）。
        assertTrue(report.findings.any { it.code == "state_failed" })
    }

    @Test
    fun doctorPassesAHealthyHost() {
        val report = NodeHostDoctor.evaluate(
            input(
                state = "ready",
                lastError = null,
                binaryPresent = true,
                abi = "arm64-v8a",
                pid = 4242,
                port = 51515,
            ),
        )
        assertTrue(report.ok)
        assertTrue(report.findings.none { it.severity == NodeHostDoctor.SEVERITY_ERROR })
        assertTrue(report.findings.any { it.code == "host_ready" })
        assertTrue(NodeHostDoctor.summary(report).contains("no error-level problem"))
    }

    @Test
    fun doctorNamesTheMissingHostScriptInsteadOfTheWholeRuntime() {
        // 真机 R16：stamp 对、运行时其余文件都在，只缺 dsh/host.mjs。
        // `runtime_installed` 本身包含 host.mjs 的检查，只按它判断会说成
        // 「stamp 缺失或版本不符」，是误导；这里必须是 host_script_missing。
        val input = input(
            runtimeInstalled = false,
            hostScriptPresent = false,
            state = "ready",
            lastError = null,
            binaryPresent = true,
            abi = "arm64-v8a",
            pid = 7,
            port = 8,
        )
        val report = NodeHostDoctor.evaluate(input)
        assertTrue(report.findings.any { it.code == "host_script_missing" })
        assertFalse("不能报成 runtime_missing：${report.findings.map { it.code }}", report.findings.any { it.code == "runtime_missing" })
        assertEquals(
            "reinstall-runtime",
            report.findings.first { it.code == "host_script_missing" }.fix,
        )
        assertEquals(
            listOf("reinstall-runtime"),
            NodeHostRepairPlanner.plan(input, report.findings).map { it.action },
        )
    }

    @Test
    fun doctorSuggestsReinstallAndQuarantine() {
        val report = NodeHostDoctor.evaluate(
            input(
                hostScriptPresent = false,
                stampEntries = 7,
                missingPluginDirs = listOf("ghost"),
                failedPlugins = listOf(
                    NodeHostDoctor.FailedPlugin("bad", "./plugins/bad/index.js", "boom"),
                ),
            ),
        )
        val fixes = report.findings.map { it.fix }
        assertTrue("host.mjs 缺失应建议 reinstall-runtime", "reinstall-runtime" in fixes)
        assertTrue("坏插件应建议 quarantine", "quarantine:bad" in fixes)
        assertTrue("缺目录应建议 quarantine", "quarantine:ghost" in fixes)
        assertTrue(report.findings.any { it.code == "runtime_entry_count_mismatch" })
    }

    @Test
    fun repairLadderReinstallsQuarantinesThenRestarts() {
        val input = input(
            state = "failed",
            hostScriptPresent = false,
            stampEntries = 3,
            binaryPresent = true,
            abi = "arm64-v8a",
            failedPlugins = listOf(
                NodeHostDoctor.FailedPlugin("bad", "./plugins/bad/index.js", "boom"),
            ),
        )
        val report = NodeHostDoctor.evaluate(input)
        val steps = NodeHostRepairPlanner.plan(input, report.findings).map { it.action }
        assertEquals(listOf("reinstall-runtime", "quarantine", "restart"), steps)
    }

    @Test
    fun repairLadderDoesNotTouchAnUnsupportedAbi() {
        val input = input(state = "failed", binaryPresent = false)
        val report = NodeHostDoctor.evaluate(input)
        val steps = NodeHostRepairPlanner.plan(input, report.findings).map { it.action }
        assertFalse("缺 libnode 的 ABI 不能靠重装/重启修", steps.any { it in setOf("reinstall-runtime", "start", "restart") })
    }

    @Test
    fun repairLadderLeavesTheHostOffWhenTheSwitchIsOff() {
        val input = input(
            enabled = false,
            state = "disabled",
            failedPlugins = listOf(
                NodeHostDoctor.FailedPlugin("bad", "./plugins/bad/index.js", "boom"),
            ),
        )
        val report = NodeHostDoctor.evaluate(input)
        val steps = NodeHostRepairPlanner.plan(input, report.findings).map { it.action }
        assertEquals(listOf("quarantine"), steps)
    }

    @Test
    fun healthyHostHasNoAutofixPlan() {
        val input = input(
            state = "ready",
            lastError = null,
            binaryPresent = true,
            abi = "arm64-v8a",
            pid = 1,
            port = 2,
        )
        val report = NodeHostDoctor.evaluate(input)
        assertTrue(NodeHostRepairPlanner.plan(input, report.findings).isEmpty())
    }

    @Test
    fun failureTrackerQuarantinesOnlyOnTheSecondConsecutiveFailure() {
        val first = NodePluginFailureTracker.update(emptyMap(), listOf("m" to false, "ok" to true))
        assertEquals(mapOf("m" to 1), first.counts)
        assertTrue(first.quarantine.isEmpty())

        val second = NodePluginFailureTracker.update(first.counts, listOf("m" to false))
        assertTrue("第二次仍失败才隔离", second.quarantine == listOf("m"))
        assertTrue("隔离后清计数，避免重复通知", second.counts.isEmpty())

        // 中间成功一次会清零：下一轮失败重新从 1 开始。
        val recovered = NodePluginFailureTracker.update(mapOf("m" to 1), listOf("m" to true))
        assertTrue(recovered.counts.isEmpty())
        assertTrue(recovered.quarantine.isEmpty())
    }

    @Test
    fun pluginDirectoryIsPackagedWithASingleRootAndInstalls() {
        val root = File(System.getProperty("java.io.tmpdir"), "nodehost-packager-${System.nanoTime()}")
        val plugin = File(root, "my-node-plugin")
        try {
            File(plugin, "lib").mkdirs()
            File(plugin, "package.json").writeText(
                """{"name":"my-node-plugin","version":"0.1.0","main":"lib/index.js"}""",
                Charsets.UTF_8,
            )
            File(plugin, "lib/index.js").writeText(
                "export const name = 'my-node-plugin'\nexport function apply(ctx) {}\n",
                Charsets.UTF_8,
            )
            val bytes = NodePluginPackager.zipDirectory(plugin).getOrThrow()
            val entries = java.util.zip.ZipInputStream(java.io.ByteArrayInputStream(bytes)).use { zip ->
                buildList {
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        add(entry.name)
                    }
                }
            }
            // 单根目录：NodePluginInstaller 会剥掉它，装成 files/node/data/plugins/my-node-plugin。
            assertTrue(entries.toString(), entries.any { it == "my-node-plugin/package.json" })
            assertTrue(entries.toString(), entries.any { it == "my-node-plugin/lib/index.js" })

            val installed = NodePluginInstaller(File(root, "data")).installZip(bytes, "fallback").getOrThrow()
            assertEquals("my-node-plugin", installed.id)
            assertEquals("./plugins/my-node-plugin/lib/index.js", installed.module)
            assertTrue(File(root, "data/plugins/my-node-plugin/lib/index.js").isFile)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun emptyPluginDirectoryIsRejected() {
        val root = File(System.getProperty("java.io.tmpdir"), "nodehost-packager-empty-${System.nanoTime()}")
        try {
            root.mkdirs()
            assertTrue(NodePluginPackager.zipDirectory(root).isFailure)
        } finally {
            root.deleteRecursively()
        }
    }

    private fun assertNoCjkInSchema(element: kotlinx.serialization.json.JsonElement, where: String) {
        when (element) {
            is kotlinx.serialization.json.JsonObject -> element.forEach { (key, value) ->
                if (key == "description" && value is kotlinx.serialization.json.JsonPrimitive) {
                    val hit = cjk.find(value.content)
                    assertTrue("$where 含中文: ${hit?.value}", hit == null)
                } else {
                    assertNoCjkInSchema(value, "$where/$key")
                }
            }

            is kotlinx.serialization.json.JsonArray ->
                element.forEachIndexed { index, value -> assertNoCjkInSchema(value, "$where[$index]") }

            else -> Unit
        }
    }

    /** 默认就是「模拟器上的真实失败」：runtime 装好、宿主 FAILED、x86_64 缺 libnode.so。 */
    private fun input(
        enabled: Boolean = true,
        state: String = "failed",
        lastError: String? = "缺少 /data/app/…/lib/x86_64/libnode.so",
        runtimeInstalled: Boolean = true,
        stampVersion: String? = NodeRuntimeInstaller.RUNTIME_VERSION,
        stampEntries: Int? = NodeRuntimeInstaller.TOTAL_ENTRIES,
        hostScriptPresent: Boolean = true,
        binaryPresent: Boolean = false,
        abi: String = "x86_64",
        manifestCount: Int = 1,
        missingPluginDirs: List<String> = emptyList(),
        missingPluginEntries: List<String> = emptyList(),
        failedPlugins: List<NodeHostDoctor.FailedPlugin> = emptyList(),
        crashCount: Int = 0,
        freeBytes: Long = 2L * 1024 * 1024 * 1024,
        pid: Int = -1,
        port: Int = -1,
    ) = NodeHostDoctor.Input(
        enabled = enabled,
        state = state,
        lastError = lastError,
        runtimeInstalled = runtimeInstalled,
        runtimeVersion = NodeRuntimeInstaller.RUNTIME_VERSION,
        stampVersion = stampVersion,
        stampEntries = stampEntries,
        expectedEntries = NodeRuntimeInstaller.TOTAL_ENTRIES,
        hostScriptPresent = hostScriptPresent,
        binaryPresent = binaryPresent,
        abi = abi,
        manifestCount = manifestCount,
        missingPluginDirs = missingPluginDirs,
        missingPluginEntries = missingPluginEntries,
        failedPlugins = failedPlugins,
        crashCount = crashCount,
        freeBytes = freeBytes,
        pid = pid,
        port = port,
    )
}

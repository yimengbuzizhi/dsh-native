package xin.dshaa.nativeapp.node

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import xin.dshaa.core.plugins.PluginSchema
import xin.dshaa.core.protocol.ApiTool
import xin.dshaa.core.tools.DshTool
import xin.dshaa.core.tools.ToolContext
import xin.dshaa.core.tools.ToolResult
import xin.dshaa.core.tools.resolvePath
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * docs/190-B：Node 宿主自治工具（`nodehost_*`）。
 *
 * 为什么是**静态注册**：宿主关着/失败时正是最需要看状态、看日志、修它的时候，
 * 所以这 9 个工具不经过 NodeToolSurface（那是一张"宿主 Ready 才有"的动态表），
 * 由内置插件组 `builtin.node.host` 直接注册进 ToolRegistry。
 *
 * 契约：
 * - 4 个只读（status/plugins/logs/doctor）进 `READ_ONLY_TOOLS`；5 个写类走全局审批档；
 * - 全部动作写 `files/node-audit.jsonl`（`phase:"event"`），日志/审计返回前脱敏；
 * - 模型可见文本全英文（描述/参数/结果），UI 提示仍是中文（[onNotice]）；
 * - v1 **不允许** AI 改插件 JS：隔离 = 卸载，模型只能让用户重装修好的包；
 * - `nodehost_control` 有 3 次/5 分钟退避（宿主自己的崩溃策略仍是 5 分钟 3 次）。
 *
 * 纯函数部分（[NodeHostDoctor] / [NodeHostRepairPlanner] / [NodeHostRateLimiter] /
 * [NodeHostRedaction] / [NodePluginFailureTracker]）拆出来做 JVM 单测，不依赖 Android。
 */
class NodeHostTools(
    private val host: NodePluginHost,
    private val isHostEnabled: () -> Boolean,
    /** 关闭宿主开关（打开是由 [restartHost] 统一做的，避免 attach/start 两次并发）。 */
    private val setHostEnabled: (Boolean) -> Unit,
    /** 清「上次失败跳过自动启动」标记并重启/启动宿主；返回是否 Ready。 */
    private val restartHost: suspend () -> Boolean,
    /** 删运行时重装（插件目录不动）；返回是否 Ready。 */
    private val reinstallRuntime: suspend () -> Boolean,
    /** 写操作后刷新状态卡/提示词段/供应商（VM 负责，不启动宿主）。 */
    private val refreshHost: suspend () -> Unit,
    /** UI 提示（中文）。 */
    private val onNotice: (String) -> Unit,
    private val workspace: () -> File,
    private val approvalMode: () -> String,
    private val auditTail: (Int) -> List<String> = { host.auditTail(it) },
) {

    private val limiter = NodeHostRateLimiter()

    val tools: List<DshTool> = definitions.map { api ->
        object : DshTool {
            override val name: String = api.name

            override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
                PluginSchema.validate(api.inputSchema, args)?.let { return ToolResult.err(it) }
                val result = try {
                    runTool(name, args, ctx)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    ToolResult.err(error.message ?: error.javaClass.simpleName)
                }
                // 全部进审计（docs/190-B）：只读调用也落一行 event，方便回答"AI 到底做了什么"。
                runCatching {
                    host.auditEvent(name, auditDetail(name, args, result), !result.isError, approvalMode())
                }
                return result
            }
        }
    }

    fun apiTools(): List<ApiTool> = definitions

    private suspend fun runTool(name: String, args: JsonObject, ctx: ToolContext): ToolResult = when (name) {
        "nodehost_status" -> ToolResult.ok(statusJson().toString())
        "nodehost_plugins" -> ToolResult.ok(pluginsJson().toString())
        "nodehost_logs" -> logs(args)
        "nodehost_doctor" -> ToolResult.ok(doctorJson(NodeHostDoctor.evaluate(doctorInput())))
        "nodehost_control" -> control(args)
        "nodehost_repair" -> repair(args)
        "nodehost_autofix" -> autofix(args)
        "nodehost_install" -> install(args, ctx)
        "nodehost_uninstall" -> uninstall(args)
        else -> ToolResult.err("Unknown node host tool: $name")
    }

    // ---------------- nodehost_status ----------------

    private fun statusJson(): JsonObject {
        val ready = host.state.value as? NodePluginHost.State.Ready
        val stamp = host.runtimeStamp()
        val manifest = runCatching { host.manifestEntries() }.getOrDefault(emptyList())
        return buildJsonObject {
            put("enabled", isHostEnabled())
            put("state", host.stateCode())
            put("ready", ready != null)
            put("detail", stateSummary(host.stateCode()))
            put("abi", host.abi)
            put("runtime_version", host.runtimeVersion)
            put("runtime_installed", host.runtimeInstalled())
            put("runtime_entries", stamp?.entries)
            put("runtime_bytes", stamp?.bytes)
            put("runtime_installed_at", stamp?.installedAt)
            put("runtime_stamp_version", stamp?.version)
            put("binary_present", host.nodeBinary.isFile)
            put("host_script_present", host.hostScriptFile.isFile)
            put("pid", host.pid)
            put("port", host.port)
            put("rss_kb", host.rssKb)
            put("boot_ms", ready?.bootMs ?: 0L)
            put("node_version", ready?.version ?: "")
            put("tool_count", ready?.toolCount ?: 0)
            put("manifest_plugins", manifest.size)
            put("loaded_plugins", ready?.plugins.orEmpty().size)
            put("failed_plugins", ready?.plugins.orEmpty().count { !it.ok })
            put("crash_count", host.crashTimes().size)
            put("crash_times", JsonArray(host.crashTimes().map { JsonPrimitive(it) }))
            put("last_error", host.lastFailureReason())
            put("free_bytes", host.freeBytes())
        }
    }

    /** 状态的人话英文版（`describeState()` 是给 UI 的中文文案，不能进模型可见结果）。 */
    private fun stateSummary(code: String): String = when (code) {
        "ready" -> "Node host is ready."
        "extracting" -> "Node runtime is being extracted."
        "starting" -> "Node host is starting."
        "restarting" -> "Node host is restarting after an unexpected exit."
        "failed" -> "Node host failed; see last_error and nodehost_logs."
        "stopped" -> "Node host process is stopped (switch may still be on)."
        "disabled" -> "Node host is disabled."
        else -> "Unknown Node host state."
    }

    // ---------------- nodehost_plugins ----------------

    private fun pluginsJson(): JsonObject {
        val ready = host.state.value as? NodePluginHost.State.Ready
        val manifest = runCatching { host.manifestEntries() }.getOrDefault(emptyList())
        return buildJsonObject {
            put("state", host.stateCode())
            put("ready", ready != null)
            put("manifest_count", manifest.size)
            put("manifest", buildJsonArray {
                manifest.forEach { entry ->
                    val installed = isInstalledModule(entry.module)
                    val dir = if (installed) host.pluginDir(entry.module) else null
                    add(buildJsonObject {
                        put("id", entry.id)
                        put("name", entry.name)
                        put("version", entry.version)
                        put("module", entry.module)
                        put("entry", entry.entry)
                        put("has_client", entry.hasClient)
                        put("runtime_package", !installed)
                        if (installed) {
                            put("dir_present", dir?.isDirectory == true)
                            put("entry_present", dir != null && entryPresent(dir, entry.entry))
                        }
                    })
                }
            })
            put("loaded", buildJsonArray {
                ready?.plugins.orEmpty().forEach { plugin ->
                    add(buildJsonObject {
                        put("module", plugin.module)
                        put("ok", plugin.ok)
                        if (!plugin.error.isNullOrBlank()) put("error", plugin.error)
                        put("tools", JsonArray(plugin.tools.map(::JsonPrimitive)))
                    })
                }
            })
            put("failed_modules", JsonArray(
                ready?.plugins.orEmpty().filter { !it.ok }.map { JsonPrimitive(it.module) },
            ))
            put("llm_providers", JsonArray(host.llmProviders.value.map { JsonPrimitive(it.id) }))
            put("commands", JsonArray(host.commands.value.map { JsonPrimitive(it.name) }))
        }
    }

    // ---------------- nodehost_logs ----------------

    private fun logs(args: JsonObject): ToolResult {
        val lines = (args["lines"]?.jsonPrimitive?.content?.toIntOrNull() ?: 40).coerceIn(1, 200)
        val source = args["source"]?.jsonPrimitive?.content?.lowercase().orEmpty().ifBlank { "all" }
        if (source !in setOf("host", "audit", "all")) {
            return ToolResult.err("source must be one of: host / audit / all")
        }
        val grep = args["grep"]?.jsonPrimitive?.content?.trim().orEmpty()
        fun linesFrom(raw: List<String>): List<String> = raw
            .map { NodeHostRedaction.redact(it) }
            .filter { grep.isEmpty() || it.contains(grep, ignoreCase = true) }
            .takeLast(lines)
        val hostRaw = if (source != "audit") {
            runCatching { host.tailLogs(lines) }.getOrDefault(emptyList())
        } else {
            emptyList()
        }
        // 宿主在起进程前就失败（例如缺 libnode.so）时没有任何 stdout：把失败原因当最后一行日志，
        // 否则模型打开 logs 只会看到空数组。
        val hostLines = if (hostRaw.isEmpty() && source != "audit") {
            host.lastFailureReason()?.let { listOf("Node host failed: $it") }.orEmpty()
        } else {
            hostRaw
        }
        val auditLines = if (source != "host") auditTail(lines) else emptyList()
        return ToolResult.ok(
            buildJsonObject {
                put("source", source)
                put("lines_requested", lines)
                put("grep", grep)
                put("host", JsonArray(linesFrom(hostLines).map(::JsonPrimitive)))
                put("audit", JsonArray(linesFrom(auditLines).map(::JsonPrimitive)))
            }.toString(),
        )
    }

    // ---------------- nodehost_doctor ----------------

    private fun doctorInput(): NodeHostDoctor.Input {
        val state = host.state.value
        val ready = state as? NodePluginHost.State.Ready
        val stamp = host.runtimeStamp()
        val manifest = runCatching { host.manifestEntries() }.getOrDefault(emptyList())
        // 只有 `./plugins/<dir>/…` 是用户装进 data/plugins 的插件；`@deepseek-ai/…` 这类是运行时
        // 自带的包（runtime/node_modules 里），没有独立目录，不能按「目录缺失」报。
        val installed = manifest.filter { isInstalledModule(it.module) }
        val missingDirs = installed.filter { host.pluginDir(it.module)?.isDirectory != true }.map { it.id }
        val missingEntries = installed.filter { entry ->
            val dir = host.pluginDir(entry.module)
            dir != null && dir.isDirectory && !entryPresent(dir, entry.entry)
        }.map { it.id }
        val failed = ready?.plugins.orEmpty().filter { !it.ok }.map { plugin ->
            val meta = manifest.firstOrNull { it.module == plugin.module }
            NodeHostDoctor.FailedPlugin(
                id = meta?.id ?: plugin.module,
                module = plugin.module,
                error = plugin.error.orEmpty(),
            )
        }
        return NodeHostDoctor.Input(
            enabled = isHostEnabled(),
            state = host.stateCode(),
            lastError = host.lastFailureReason(),
            runtimeInstalled = host.runtimeInstalled(),
            runtimeVersion = host.runtimeVersion,
            stampVersion = stamp?.version,
            stampEntries = stamp?.entries,
            expectedEntries = NodeRuntimeInstaller.TOTAL_ENTRIES,
            hostScriptPresent = host.hostScriptFile.isFile,
            binaryPresent = host.nodeBinary.isFile,
            abi = host.abi,
            manifestCount = manifest.size,
            missingPluginDirs = missingDirs,
            missingPluginEntries = missingEntries,
            failedPlugins = failed,
            crashCount = host.crashTimes().size,
            freeBytes = host.freeBytes(),
            pid = ready?.pid ?: -1,
            port = if (ready != null) host.port else -1,
        )
    }

    /** data/plugins 下的模块（NodePluginInstaller 只管理这一种）。 */
    private fun isInstalledModule(module: String): Boolean = module.startsWith("./plugins/")

    /**
     * 入口文件是否存在。旧清单/无 package.json 的插件 `entry` 是空串，host.mjs 会自己找
     * index.js / main.js / 任意 js —— 这时只要目录里还有 js 文件就算「有入口」。
     */
    private fun entryPresent(dir: File, entry: String): Boolean {
        if (entry.isNotBlank()) return File(dir, entry).isFile
        return runCatching {
            dir.walkTopDown().any { it.isFile && it.extension in setOf("js", "mjs", "cjs") }
        }.getOrDefault(false)
    }

    private fun doctorJson(report: NodeHostDoctor.Report): String = buildJsonObject {
        put("ok", report.ok)
        put("summary", NodeHostDoctor.summary(report))
        put("findings", findingsJson(report))
        put("state", statusJson())
    }.toString()

    private fun findingsJson(report: NodeHostDoctor.Report): JsonArray = buildJsonArray {
        report.findings.forEach { finding ->
            add(buildJsonObject {
                put("code", finding.code)
                put("severity", finding.severity)
                put("detail", finding.detail)
                if (finding.fix.isNotBlank()) put("fix", finding.fix)
            })
        }
    }

    // ---------------- nodehost_control ----------------

    private suspend fun control(args: JsonObject): ToolResult {
        val action = args["action"]?.jsonPrimitive?.content?.lowercase().orEmpty()
        val reason = args["reason"]?.jsonPrimitive?.content.orEmpty().ifBlank { "ai request" }
        return when (action) {
            "start" -> {
                if (host.isReady()) {
                    return ToolResult.ok(hostResult("start", true, "Node host is already ready", reason))
                }
                if (host.stateCode() in BUSY_STATES) {
                    waitForSettle(START_WAIT_MS)
                    return ToolResult.ok(
                        hostResult(
                            "start",
                            host.isReady(),
                            "start requested while the host was already starting; waited for it to settle",
                            reason,
                        ),
                    )
                }
                val decision = limiter.check(System.currentTimeMillis())
                if (!decision.allowed) return rateLimited("start", decision)
                val ok = restartHost()
                refreshHost()
                ToolResult.ok(hostResult("start", ok, "start requested", reason))
            }

            "stop" -> {
                if (!isHostEnabled()) {
                    return ToolResult.ok(hostResult("stop", true, "Node host is already off", reason))
                }
                val decision = limiter.check(System.currentTimeMillis())
                if (!decision.allowed) return rateLimited("stop", decision)
                setHostEnabled(false)
                refreshHost()
                onNotice("Node 宿主已停止（AI 操作）")
                ToolResult.ok(hostResult("stop", true, "Node host stopped and the switch was turned off", reason))
            }

            "restart" -> {
                val decision = limiter.check(System.currentTimeMillis())
                if (!decision.allowed) return rateLimited("restart", decision)
                val ok = restartHost()
                refreshHost()
                ToolResult.ok(hostResult("restart", ok, "restart requested", reason))
            }

            else -> ToolResult.err("action must be one of: start / stop / restart")
        }
    }

    private fun rateLimited(action: String, decision: NodeHostRateLimiter.Decision): ToolResult =
        ToolResult.err(
            "Rate limited: at most ${NodeHostRateLimiter.MAX_ACTIONS} host control actions per " +
                "${NodeHostRateLimiter.WINDOW_MS / 60_000} minutes (used=${decision.used}). " +
                "Retry after ${(decision.retryAfterMs + 999) / 1000}s; the host's own crash policy still restarts it automatically.",
        )

    private suspend fun waitForSettle(timeoutMs: Long) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (host.stateCode() !in BUSY_STATES) return
            delay(250)
        }
    }

    private fun hostResult(action: String, ok: Boolean, detail: String, reason: String): String = buildJsonObject {
        put("action", action)
        put("ok", ok)
        put("state", host.stateCode())
        put("ready", host.isReady())
        put("detail", detail)
        if (reason.isNotBlank()) put("reason", reason)
        put("last_error", host.lastFailureReason())
        put("pid", host.pid)
        put("port", host.port)
        put("runtime_version", host.runtimeVersion)
    }.toString()

    // ---------------- nodehost_repair ----------------

    private suspend fun repair(args: JsonObject): ToolResult {
        val action = args["action"]?.jsonPrimitive?.content?.lowercase().orEmpty()
        val id = args["id"]?.jsonPrimitive?.content?.trim().orEmpty()
        val reason = args["reason"]?.jsonPrimitive?.content.orEmpty().ifBlank { "nodehost_repair" }
        return when (action) {
            "reload" -> {
                if (!isHostEnabled()) return ToolResult.err("Node host is disabled; use nodehost_control start first")
                val ok = if (host.isReady()) {
                    runCatching { host.refreshRegistrations() }.getOrDefault(false)
                } else {
                    restartHost()
                }
                refreshHost()
                ToolResult.ok(repairResult("reload", ok, id, "re-read plugin registrations without restarting the process"))
            }

            "restart" -> {
                val ok = restartHost()
                refreshHost()
                ToolResult.ok(repairResult("restart", ok, id, "host process restarted"))
            }

            "reinstall-runtime" -> {
                val ok = reinstallRuntime()
                refreshHost()
                ToolResult.ok(repairResult("reinstall-runtime", ok, id, "runtime re-extracted; plugin files were kept"))
            }

            "quarantine" -> {
                if (id.isBlank()) return ToolResult.err("quarantine requires id (plugin id or module)")
                val removed = host.quarantinePlugin(id, reason, restart = isHostEnabled())
                refreshHost()
                if (removed.isSuccess) {
                    ToolResult.ok(
                        repairResult(
                            "quarantine", true, id,
                            "uninstalled Node plugin ${removed.getOrThrow()}; the rest keep working",
                        ),
                    )
                } else {
                    ToolResult.err("Quarantine failed for $id: ${removed.exceptionOrNull()?.message.orEmpty()}")
                }
            }

            "clear-cache" -> {
                val freed = host.clearCache()
                refreshHost()
                ToolResult.ok(
                    buildJsonObject {
                        put("action", "clear-cache")
                        put("ok", true)
                        put("freed_bytes", freed)
                        put("state", host.stateCode())
                        put("detail", "temporary extraction files removed")
                    }.toString(),
                )
            }

            else -> ToolResult.err(
                "action must be one of: reload / restart / reinstall-runtime / quarantine / clear-cache",
            )
        }
    }

    private fun repairResult(action: String, ok: Boolean, id: String, detail: String): String = buildJsonObject {
        put("action", action)
        put("ok", ok)
        put("id", id)
        put("state", host.stateCode())
        put("ready", host.isReady())
        put("detail", detail)
        put("last_error", host.lastFailureReason())
    }.toString()

    // ---------------- nodehost_autofix ----------------

    private suspend fun autofix(args: JsonObject): ToolResult {
        val dryRun = args["dry_run"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() == true
        val input = doctorInput()
        val report = NodeHostDoctor.evaluate(input)
        val plan = NodeHostRepairPlanner.plan(input, report.findings)
        val plannedJson = buildJsonArray {
            plan.forEach { step ->
                add(buildJsonObject {
                    put("action", step.action)
                    if (step.target.isNotBlank()) put("target", step.target)
                    if (step.reason.isNotBlank()) put("reason", step.reason)
                })
            }
        }
        if (plan.isEmpty()) {
            return ToolResult.ok(
                buildJsonObject {
                    put("ok", report.ok)
                    put("found", findingsJson(report))
                    put("planned", plannedJson)
                    put("did", buildJsonArray { })
                    put("now", statusJson())
                    put(
                        "message",
                        if (report.ok) {
                            "No repair needed: the Node host passes every doctor check."
                        } else {
                            "No automatic repair is available for the remaining findings; they need a manual fix (see findings)."
                        },
                    )
                }.toString(),
            )
        }
        if (dryRun) {
            return ToolResult.ok(
                buildJsonObject {
                    put("ok", report.ok)
                    put("dry_run", true)
                    put("found", findingsJson(report))
                    put("planned", plannedJson)
                    put("did", buildJsonArray { })
                    put("now", statusJson())
                }.toString(),
            )
        }
        val did = buildJsonArray {
            plan.take(MAX_AUTOFIX_STEPS).forEach { step ->
                val (ok, detail) = executeRepairStep(step)
                add(buildJsonObject {
                    put("action", step.action)
                    if (step.target.isNotBlank()) put("target", step.target)
                    put("ok", ok)
                    put("detail", detail)
                })
            }
        }
        refreshHost()
        val afterInput = doctorInput()
        val after = NodeHostDoctor.evaluate(afterInput)
        return ToolResult.ok(
            buildJsonObject {
                put("ok", after.ok)
                put("found", findingsJson(report))
                put("planned", plannedJson)
                put("did", did)
                put("now", statusJson())
                put("after_ok", after.ok)
                put("after_findings", findingsJson(after))
                put("evidence", buildJsonObject {
                    put("crash_count", afterInput.crashCount)
                    put("state", afterInput.state)
                    put("last_error", afterInput.lastError)
                    put("host_log_tail", buildJsonArray {
                        runCatching { host.tailLogs(10) }.getOrDefault(emptyList()).forEach { line ->
                            add(JsonPrimitive(NodeHostRedaction.redact(line)))
                        }
                    })
                })
            }.toString(),
        )
    }

    private suspend fun executeRepairStep(step: NodeHostRepairPlanner.Step): Pair<Boolean, String> = when (step.action) {
        "reinstall-runtime" -> {
            val ok = reinstallRuntime()
            ok to "runtime re-extracted (ok=$ok)"
        }

        "start", "restart" -> {
            val ok = restartHost()
            ok to "host restart attempted (ok=$ok)"
        }

        "quarantine" -> {
            val removed = host.quarantinePlugin(step.target, step.reason, restart = false)
            if (removed.isSuccess) {
                true to "uninstalled ${removed.getOrThrow()}"
            } else {
                false to removed.exceptionOrNull()?.message.orEmpty()
            }
        }

        "clear-cache" -> {
            val freed = host.clearCache()
            true to "cleared $freed bytes"
        }

        else -> false to "unknown repair action: ${step.action}"
    }

    // ---------------- nodehost_install / uninstall ----------------

    private suspend fun install(args: JsonObject, ctx: ToolContext): ToolResult {
        val raw = args["path"]?.jsonPrimitive?.content?.trim().orEmpty()
        if (raw.isBlank()) return ToolResult.err("path is required")
        val path = runCatching { resolvePath(raw, ctx) }.getOrElse {
            return ToolResult.err(it.message ?: "invalid path")
        }
        if (!withinAllowedRoots(path)) {
            return ToolResult.err(
                "Node plugin zip must live inside the workspace or the app's Node directory: ${path.absolutePath}",
            )
        }
        val bytes: ByteArray
        val fallbackName: String
        if (path.isDirectory) {
            // 手机上通常没有 zip 命令：允许直接给插件目录，宿主按「单根目录」打 zip 再走同一个导入器。
            bytes = withContext(Dispatchers.IO) { NodePluginPackager.zipDirectory(path) }
                .getOrElse { return ToolResult.err("Cannot package ${path.absolutePath}: ${it.message}") }
            fallbackName = path.name
        } else {
            if (!path.isFile) return ToolResult.err("Node plugin path not found: ${path.absolutePath}")
            if (!path.name.lowercase().endsWith(".zip")) {
                return ToolResult.err("Node plugin package must be a directory or a .zip file: ${path.name}")
            }
            val limit = NodePluginInstaller.Limits().maxZipBytes
            if (path.length() > limit) {
                return ToolResult.err(
                    "Node plugin zip is too large: ${path.length()} bytes (limit $limit). " +
                        "Limits: ${NodePluginInstaller.limitsText()}",
                )
            }
            bytes = withContext(Dispatchers.IO) { runCatching { path.readBytes() }.getOrNull() }
                ?: return ToolResult.err("Cannot read ${path.absolutePath}")
            fallbackName = path.nameWithoutExtension
        }
        val installed = host.installPluginZip(
            bytes = bytes,
            fallbackName = args["name"]?.jsonPrimitive?.content?.ifBlank { null } ?: fallbackName,
            restart = isHostEnabled(),
        )
        refreshHost()
        if (installed.isSuccess) {
            val value = installed.getOrThrow()
            return ToolResult.ok(
                buildJsonObject {
                    put("ok", true)
                    put("id", value.id)
                    put("name", value.name)
                    put("version", value.version)
                    put("module", value.module)
                    put("has_client", value.hasClient)
                    put("state", host.stateCode())
                    put(
                        "detail",
                        "installed; ${if (isHostEnabled()) "host restarted" else "host is off, plugin loads on next start"}",
                    )
                }.toString(),
            )
        }
        return ToolResult.err("Node plugin install failed: ${installed.exceptionOrNull()?.message.orEmpty()}")
    }

    private suspend fun uninstall(args: JsonObject): ToolResult {
        val id = args["id"]?.jsonPrimitive?.content?.trim().orEmpty()
        if (id.isBlank()) return ToolResult.err("id is required")
        val removed = host.uninstallPlugin(id, restart = isHostEnabled())
        refreshHost()
        if (removed.isSuccess) {
            return ToolResult.ok(
                buildJsonObject {
                    put("ok", true)
                    put("id", id)
                    put("name", removed.getOrThrow())
                    put("state", host.stateCode())
                    put("detail", "uninstalled")
                }.toString(),
            )
        }
        return ToolResult.err("Node plugin uninstall failed: ${removed.exceptionOrNull()?.message.orEmpty()}")
    }

    // ---------------- audit ----------------

    /** 导入路径只允许落在工作区或 `files/node`（路径边界，v1 不做任意文件读取）。 */
    private fun withinAllowedRoots(path: File): Boolean {
        val canonical = runCatching { path.canonicalPath }.getOrNull() ?: return false
        val roots = listOfNotNull(
            runCatching { workspace().canonicalPath }.getOrNull(),
            runCatching { host.installRoot.canonicalPath }.getOrNull(),
        )
        return roots.any { root -> canonical == root || canonical.startsWith(root + File.separator) }
    }

    private fun auditDetail(name: String, args: JsonObject, result: ToolResult): String {
        val target = when (name) {
            "nodehost_install" -> args["path"]?.jsonPrimitive?.content.orEmpty()
                .substringAfterLast('/').substringAfterLast('\\')
            "nodehost_uninstall", "nodehost_quarantine" -> args["id"]?.jsonPrimitive?.content.orEmpty()
            "nodehost_repair" -> listOf(
                args["action"]?.jsonPrimitive?.content.orEmpty(),
                args["id"]?.jsonPrimitive?.content.orEmpty(),
            ).filter { it.isNotBlank() }.joinToString("/")
            "nodehost_control" -> args["action"]?.jsonPrimitive?.content.orEmpty()
            else -> ""
        }
        return buildString {
            if (target.isNotBlank()) append("target=").append(target).append(' ')
            append("ok=").append(!result.isError)
            append(" result=").append(result.text.take(400).replace('\n', ' '))
        }
    }

    companion object {
        private const val START_WAIT_MS = 120_000L
        private const val MAX_AUTOFIX_STEPS = 6
        private val BUSY_STATES = setOf("extracting", "starting", "restarting")

        /** 只读工具名（进 AppViewModel 的 `READ_ONLY_TOOLS`，审批档为 ask 时也不弹卡）。 */
        val readOnlyNames = setOf(
            "nodehost_status",
            "nodehost_plugins",
            "nodehost_logs",
            "nodehost_doctor",
        )

        /** 写类工具名（全部走全局审批档 + 审计）。 */
        val writeNames = setOf(
            "nodehost_control",
            "nodehost_repair",
            "nodehost_autofix",
            "nodehost_install",
            "nodehost_uninstall",
        )

        private fun schema(properties: String, required: String = "[]"): JsonObject =
            Json.parseToJsonElement(
                """{"type":"object","properties":$properties,"required":$required,"additionalProperties":false}""",
            ).jsonObject

        internal val definitions = listOf(
            ApiTool(
                "nodehost_status",
                "Read the Node plugin host state: enabled, state code, pid/port/RSS, runtime version and " +
                    "entry count, crash count and last error. Works even when the host is off or failed; " +
                    "read-only, no approval.",
                schema("""{}"""),
            ),
            ApiTool(
                "nodehost_plugins",
                "List Node plugins from the host manifest (id/name/version/module/entry, whether the directory " +
                    "and entry file exist) plus the modules the host has loaded, their errors and tools, and the " +
                    "registered model providers and slash commands. Read-only, no approval.",
                schema("""{}"""),
            ),
            ApiTool(
                "nodehost_logs",
                "Read the tail of the Node host stdout/stderr and/or the node audit log, with secrets redacted. " +
                    "Use grep to filter after redaction. Read-only, no approval.",
                schema(
                    """{"source":{"type":"string","enum":["host","audit","all"],"default":"all","description":"Which log to read: host output, the audit log, or both."},"lines":{"type":"integer","minimum":1,"maximum":200,"default":40,"description":"How many tail lines to return per source."},"grep":{"type":"string","description":"Case-insensitive substring filter, applied after redaction."}}""",
                ),
            ),
            ApiTool(
                "nodehost_doctor",
                "Diagnose the Node host before changing anything: runtime files/version/entry count, host.mjs, " +
                    "device ABI and libnode.so, manifest integrity, plugin load errors, crash history, disk space " +
                    "and port. Returns ok=false plus findings with a fix hint when something is wrong. " +
                    "Read-only, no approval.",
                schema("""{}"""),
            ),
            ApiTool(
                "nodehost_control",
                "Start, stop, or restart the Node host. Rate-limited to 3 actions per 5 minutes; the host's own " +
                    "crash policy still restarts it automatically. Write action: asks for approval and is audited.",
                schema(
                    """{"action":{"type":"string","enum":["start","stop","restart"],"description":"start enables the host switch and starts it; stop disables the switch and stops the process; restart restarts the process."},"reason":{"type":"string","description":"Short reason recorded in the audit log."}}""",
                    """["action"]""",
                ),
            ),
            ApiTool(
                "nodehost_repair",
                "Apply one targeted repair: reload (re-read plugin registrations without restarting the process), " +
                    "restart (restart the process), reinstall-runtime (delete and re-extract the Node runtime; plugin " +
                    "files are kept), quarantine (uninstall one broken plugin so the rest can load), or clear-cache " +
                    "(delete temporary extraction files). Write action: asks for approval and is audited.",
                schema(
                    """{"action":{"type":"string","enum":["reload","restart","reinstall-runtime","quarantine","clear-cache"]},"id":{"type":"string","description":"Plugin id or module to quarantine; required for quarantine."},"reason":{"type":"string","description":"Short reason recorded in the audit log."}}""",
                    """["action"]""",
                ),
            ),
            ApiTool(
                "nodehost_autofix",
                "Run nodehost_doctor, then apply a bounded repair ladder (reinstall runtime, quarantine failing " +
                    "plugins, clear cache, restart/start) and report what was found, what was done, the current state " +
                    "and evidence. Use dry_run to inspect the plan first. Write action: asks for approval and is audited.",
                schema(
                    """{"dry_run":{"type":"boolean","default":false,"description":"Only compute the repair plan; change nothing."}}""",
                ),
            ),
            ApiTool(
                "nodehost_install",
                "Install a Node plugin from a directory or .zip inside the workspace, after the Node importer's " +
                    "size/entry checks. A directory is packaged as a single-root zip first (Android has no zip command). " +
                    "The package must bundle its own node_modules or only use packages the host already provides; the " +
                    "host is restarted when it is on. Write action: asks for approval and is audited.",
                schema(
                    """{"path":{"type":"string","minLength":1,"description":"Plugin directory or .zip path, resolved against the workspace."},"name":{"type":"string","description":"Fallback directory name when the package has no single root folder."}}""",
                    """["path"]""",
                ),
            ),
            ApiTool(
                "nodehost_uninstall",
                "Uninstall a Node plugin by id or module and restart the host when it is on, so the remaining plugins " +
                    "load without it. Write action: asks for approval and is audited.",
                schema(
                    """{"id":{"type":"string","minLength":1,"description":"Plugin id, module specifier, or directory name."}}""",
                    """["id"]""",
                ),
            ),
        )

        /** 9 个工具名（与 `builtin.node.host` 组的 tools 集合一致）。 */
        val toolNames: Set<String> get() = definitions.map { it.name }.toSet()
    }
}

// ---------------- 纯函数（JVM 单测覆盖） ----------------

/**
 * `nodehost_control` 的写操作退避：任意 5 分钟内最多 3 次。纯 Kotlin，喂时间戳即可测。
 *
 * 只拦 AI 的 start/stop/restart；宿主自身崩溃重启由 [NodeCrashPolicy] 管，两者不叠加。
 */
internal class NodeHostRateLimiter(
    private val maxActions: Int = MAX_ACTIONS,
    private val windowMs: Long = WINDOW_MS,
) {
    data class Decision(val allowed: Boolean, val used: Int, val retryAfterMs: Long)

    private val times = ArrayDeque<Long>()

    @Synchronized
    fun check(now: Long): Decision {
        while (times.isNotEmpty() && now - times.first() > windowMs) times.removeFirst()
        if (times.size >= maxActions) {
            val retryAfter = (times.first() + windowMs - now).coerceAtLeast(0L)
            return Decision(false, times.size, retryAfter)
        }
        times.addLast(now)
        return Decision(true, times.size, 0L)
    }

    @Synchronized
    fun reset() = times.clear()

    companion object {
        const val MAX_ACTIONS = 3
        const val WINDOW_MS = 5 * 60_000L
    }
}

/** 日志/审计脱敏（token / Authorization / api key / DSH_NODE_TOKEN / sk-…）。 */
internal object NodeHostRedaction {

    private val patterns = listOf(
        Regex("(?i)(authorization\\s*:\\s*bearer\\s+)[^\\s\"',;}]+"),
        Regex("(?i)((?:api[_-]?key|access[_-]?token|token|secret|password)\\s*[=:]\\s*[\"']?)[^\\s\"',;}]+"),
        Regex("sk-[A-Za-z0-9_-]{6,}"),
    )

    fun redact(line: String): String {
        var out = line
        out = patterns[0].replace(out, "$1<redacted>")
        out = patterns[1].replace(out, "$1<redacted>")
        out = patterns[2].replace(out, "sk-<redacted>")
        return out
    }
}

/**
 * `nodehost_doctor` 的检查与判定。
 *
 * 输入全是事实快照（由 [NodeHostTools.doctorInput] 从宿主读），输出 findings；
 * `ok=false` 表示仍有 error 级问题。**纯 Kotlin**，不依赖 Android/进程，JVM 可测。
 */
internal object NodeHostDoctor {

    const val SEVERITY_ERROR = "error"
    const val SEVERITY_WARNING = "warning"
    const val SEVERITY_INFO = "info"

    data class FailedPlugin(val id: String, val module: String, val error: String)

    data class Input(
        val enabled: Boolean,
        val state: String,
        val lastError: String?,
        val runtimeInstalled: Boolean,
        val runtimeVersion: String,
        val stampVersion: String?,
        val stampEntries: Int?,
        val expectedEntries: Int,
        val hostScriptPresent: Boolean,
        val binaryPresent: Boolean,
        /** 设备主 ABI（`Build.SUPPORTED_ABIS[0]`，例如 x86_64 / arm64-v8a）。 */
        val abi: String,
        val manifestCount: Int,
        val missingPluginDirs: List<String>,
        val missingPluginEntries: List<String>,
        val failedPlugins: List<FailedPlugin>,
        val crashCount: Int,
        val freeBytes: Long,
        val pid: Int,
        val port: Int,
    )

    data class Finding(val code: String, val severity: String, val detail: String, val fix: String = "")

    data class Report(val ok: Boolean, val findings: List<Finding>)

    fun evaluate(input: Input): Report {
        val findings = mutableListOf<Finding>()
        if (!input.enabled) {
            findings += Finding(
                "host_disabled",
                SEVERITY_INFO,
                "Node host is disabled: Node plugins are not loaded; built-in tools and chat are unaffected.",
                "start",
            )
        }
        if (input.enabled && input.state == "failed") {
            findings += Finding(
                "state_failed",
                SEVERITY_ERROR,
                "Node host is in failed state: ${input.lastError?.ifBlank { "unknown" } ?: "unknown"}",
                if (input.binaryPresent) "restart" else "",
            )
        }
        // `runtime_installed` 来自 `NodeRuntimeInstaller.isInstalled()`，而它本身要求 host.mjs 存在：
        // 只用 runtimeInstalled 判断会把「stamp 对、只缺 host.mjs」误报成 runtime_missing
        // （真机 R16 实测踩到）。这里先按具体证据分派，让 AI 拿到的是能直接照做的结论。
        val stampMatches = input.stampVersion == input.runtimeVersion
        if (!input.hostScriptPresent && stampMatches) {
            findings += Finding(
                "host_script_missing",
                SEVERITY_ERROR,
                "Node runtime ${input.runtimeVersion} is incomplete: dsh/host.mjs is missing " +
                    "(stamp and other files are present).",
                "reinstall-runtime",
            )
        } else if (!input.runtimeInstalled) {
            if (input.stampVersion != null && !stampMatches) {
                findings += Finding(
                    "runtime_version_mismatch",
                    SEVERITY_ERROR,
                    "Installed runtime stamp is ${input.stampVersion}, expected ${input.runtimeVersion}.",
                    "reinstall-runtime",
                )
            } else {
                findings += Finding(
                    "runtime_missing",
                    SEVERITY_ERROR,
                    "Node runtime ${input.runtimeVersion} is not installed " +
                        "(files under files/node/runtime are missing or incomplete).",
                    "reinstall-runtime",
                )
            }
        }
        if (stampMatches && input.stampEntries != null && input.stampEntries != input.expectedEntries) {
            findings += Finding(
                "runtime_entry_count_mismatch",
                SEVERITY_WARNING,
                "Installed runtime has ${input.stampEntries} entries, packaged zip has ${input.expectedEntries}.",
                "reinstall-runtime",
            )
        }
        if (!input.binaryPresent) {
            findings += Finding(
                "missing_libnode_abi",
                SEVERITY_ERROR,
                "Node executable for ABI ${input.abi.ifBlank { "unknown" }} is missing: libnode.so is not " +
                    "packaged for this ABI (this app ships arm64 only). Reinstalling the runtime cannot fix a " +
                    "missing ABI; use an arm64 device or an arm64 build.",
                "run-on-arm64",
            )
        }
        input.missingPluginDirs.forEach { id ->
            findings += Finding(
                "plugin_dir_missing",
                SEVERITY_WARNING,
                "Node plugin $id is in the manifest but its directory is missing.",
                "quarantine:$id",
            )
        }
        input.missingPluginEntries.forEach { id ->
            findings += Finding(
                "plugin_entry_missing",
                SEVERITY_WARNING,
                "Node plugin $id has no readable entry file.",
                "quarantine:$id",
            )
        }
        input.failedPlugins.forEach { plugin ->
            findings += Finding(
                "plugin_load_failed",
                SEVERITY_WARNING,
                "Node plugin ${plugin.id} (${plugin.module}) failed to load: " +
                    plugin.error.ifBlank { "unknown error" }.take(400),
                "quarantine:${plugin.id}",
            )
        }
        if (input.crashCount >= CRASH_LOOP_THRESHOLD) {
            findings += Finding(
                "crash_loop",
                SEVERITY_WARNING,
                "The host crashed ${input.crashCount} times in the recent window; automatic restarts stopped.",
                if (input.binaryPresent) "restart" else "",
            )
        }
        if (input.freeBytes in 1 until LOW_DISK_BYTES) {
            findings += Finding(
                "low_disk",
                SEVERITY_WARNING,
                "Only ${input.freeBytes / 1024 / 1024}MB free in the app data directory.",
                "clear-cache",
            )
        }
        if (input.state == "ready") {
            findings += Finding(
                "host_ready",
                SEVERITY_INFO,
                "Node host is ready (pid=${input.pid}, port=${input.port}, plugins=${input.manifestCount}).",
            )
        }
        return Report(ok = findings.none { it.severity == SEVERITY_ERROR }, findings = findings)
    }

    fun summary(report: Report): String {
        val errors = report.findings.count { it.severity == SEVERITY_ERROR }
        val warnings = report.findings.count { it.severity == SEVERITY_WARNING }
        val infos = report.findings.count { it.severity == SEVERITY_INFO }
        return if (report.ok) {
            "Doctor found no error-level problem (warnings=$warnings, info=$infos)."
        } else {
            "Doctor found $errors error-level problem(s) and $warnings warning(s); see findings for fixes."
        }
    }

    const val LOW_DISK_BYTES = 50L * 1024 * 1024
    private const val CRASH_LOOP_THRESHOLD = 3
}

/**
 * `nodehost_autofix` 的阶梯：doctor findings → 有界、有序的修复步骤。
 *
 * 顺序固定：先换运行时（文件坏了才换），再隔离坏插件，再清缓存，最后重启/启动一次。
 * 缺 libnode.so（ABI 不匹配）时**不做**重装/重启——那修不好，只会白折腾。
 * 宿主关着时只做隔离/清缓存，不替用户把开关打开。
 */
internal object NodeHostRepairPlanner {

    data class Step(val action: String, val target: String = "", val reason: String = "")

    fun plan(input: NodeHostDoctor.Input, findings: List<NodeHostDoctor.Finding>): List<Step> {
        val codes = findings.map { it.code }.toSet()
        val abiMissing = "missing_libnode_abi" in codes
        val runtimeBroken = codes.any {
            it in setOf(
                "runtime_missing",
                "host_script_missing",
                "runtime_version_mismatch",
                "runtime_entry_count_mismatch",
            )
        }
        val steps = mutableListOf<Step>()
        if (input.enabled && !abiMissing && runtimeBroken) {
            steps += Step("reinstall-runtime", reason = "runtime files are missing or inconsistent")
        }
        val quarantined = linkedSetOf<String>()
        input.failedPlugins.forEach { plugin ->
            if (quarantined.add(plugin.id)) {
                steps += Step("quarantine", plugin.id, "plugin failed to load: ${plugin.error.take(200)}")
            }
        }
        input.missingPluginDirs.forEach { id ->
            if (quarantined.add(id)) steps += Step("quarantine", id, "plugin directory is missing")
        }
        input.missingPluginEntries.forEach { id ->
            if (quarantined.add(id)) steps += Step("quarantine", id, "plugin entry file is missing")
        }
        if (input.freeBytes in 1 until NodeHostDoctor.LOW_DISK_BYTES) {
            steps += Step("clear-cache", reason = "free space is low")
        }
        if (!input.enabled) {
            // 开关关着：只做不启动宿主的修复（隔离/清缓存），启动留给用户或 nodehost_control。
            return steps.filter { it.action == "quarantine" || it.action == "clear-cache" }
        }
        val needStart = !abiMissing && (input.state != "ready" || quarantined.isNotEmpty())
        if (needStart) {
            steps += Step(
                action = if (input.state == "stopped" || input.state == "disabled") "start" else "restart",
                reason = if (quarantined.isNotEmpty()) "restart after quarantine" else "host is not running",
            )
        }
        return steps
    }
}

/**
 * docs/190-B：把工作区里的插件目录打成 Node 插件 zip（单根目录）。
 *
 * 为什么需要：Android 上没有 `zip` 命令，AI 在工作区写好插件后无法自己压包；`nodehost_install`
 * 直接收目录，由这里按 `NodePluginInstaller` 的同一套限额打 zip，再走同一个导入器。
 * 纯 JVM（不依赖 Android），限额/空目录/无文件都在这里报显式错误。
 */
internal object NodePluginPackager {

    fun zipDirectory(dir: File, limits: NodePluginInstaller.Limits = NodePluginInstaller.Limits()): Result<ByteArray> =
        runCatching {
            if (!dir.isDirectory) error("not a directory: ${dir.absolutePath}")
            val files = dir.walkTopDown()
                .filter { it.isFile }
                .sortedBy { it.relativeTo(dir).invariantSeparatorsPath }
                .toList()
            if (files.isEmpty()) error("plugin directory has no files: ${dir.absolutePath}")
            if (files.size > limits.maxEntries) {
                error("plugin directory has too many files: ${files.size} (limit ${limits.maxEntries})")
            }
            var total = 0L
            files.forEach { file ->
                if (file.length() > limits.maxEntryBytes) {
                    error("plugin file is too large: ${file.name} (limit ${limits.maxEntryBytes} bytes)")
                }
                total += file.length()
                if (total > limits.maxTotalBytes) {
                    error("plugin directory is too large after packaging (limit ${limits.maxTotalBytes} bytes)")
                }
            }
            val root = dir.name
            val out = ByteArrayOutputStream()
            ZipOutputStream(out).use { zip ->
                files.forEach { file ->
                    val rel = file.relativeTo(dir).invariantSeparatorsPath
                    zip.putNextEntry(ZipEntry("$root/$rel"))
                    file.inputStream().buffered().use { input -> input.copyTo(zip) }
                    zip.closeEntry()
                }
            }
            val bytes = out.toByteArray()
            if (bytes.size > limits.maxZipBytes) {
                error("packaged plugin is too large: ${bytes.size} bytes (limit ${limits.maxZipBytes} bytes)")
            }
            bytes
        }
}

/**
 * 反复加载失败的 Node 插件计数 → 自动隔离候选。
 *
 * v1 归因只认 `hello.plugins[].ok=false`（宿主能起来、插件自己抛错）。
 * 若插件把整个宿主进程搞崩，`hello` 根本到不了，这类崩溃无法归因到单个插件——
 * 只会在 `crash_loop` finding 里体现（局限写在 docs/190-evidence/b-node/README.md）。
 */
internal object NodePluginFailureTracker {

    /** 连续失败 2 次就隔离（"反复" = 下一轮启动仍然失败）。 */
    const val THRESHOLD = 2

    data class Result(val counts: Map<String, Int>, val quarantine: List<String>)

    fun update(previous: Map<String, Int>, loaded: List<Pair<String, Boolean>>): Result {
        val counts = previous.toMutableMap()
        val quarantine = mutableListOf<String>()
        loaded.forEach { (module, ok) ->
            if (ok) {
                counts.remove(module)
            } else {
                val next = (counts[module] ?: 0) + 1
                if (next >= THRESHOLD) {
                    quarantine += module
                    counts.remove(module)
                } else {
                    counts[module] = next
                }
            }
        }
        return Result(counts, quarantine.distinct())
    }
}

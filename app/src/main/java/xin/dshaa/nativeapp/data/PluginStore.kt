package xin.dshaa.nativeapp.data

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import xin.dshaa.core.js.JsHost
import xin.dshaa.core.js.JsOutcome
import xin.dshaa.core.js.JsStatus
import xin.dshaa.core.js.QuickJs
import xin.dshaa.core.plugins.ArchiveRead
import xin.dshaa.core.plugins.BundlePatchParser
import xin.dshaa.core.plugins.ManifestRead
import xin.dshaa.core.plugins.PackageParse
import xin.dshaa.core.plugins.PluginDryRun
import xin.dshaa.core.plugins.PluginModule
import xin.dshaa.core.plugins.PluginPackage
import xin.dshaa.core.plugins.PluginPermissions
import xin.dshaa.core.plugins.PluginRecord
import xin.dshaa.core.plugins.PluginRegistryFile
import xin.dshaa.core.plugins.PluginReload
import xin.dshaa.core.plugins.PluginSchema
import xin.dshaa.core.plugins.PluginFile
import xin.dshaa.core.plugins.ToolPkg
import java.io.File

/**
 * 插件页的落地实现（2026-11-08，第二十六棒）。
 *
 * 现在的范围（和 docs/05 的结论一致）：
 * - **导入**：SAF 选一个 zip → 解到 `files/plugins/<id>/` → 解析 `package.json`/`manifest.json`
 *   → 用 QuickJS **试运行一次**（真的执行 `apply(ctx)`，收集 `ctx.tools.register` 的注册信息）
 *   → 清单写 `files/plugins.json`。
 * - **管理**：启用/停用、重新加载（再试运行一次）、删除（目录 + 清单一起删）。
 * - **工具接入**（M27）：`AppViewModel.syncPluginTools()` 把已启用且参数可归一成 JSON Schema 的工具
 *   注册进 `ToolRegistry`；真正执行在 [PluginToolRuntime]（每次调用一个 QuickJS 实例）。
 *
 * 为什么试运行要真跑：docs/05 定的是「插件页加载一次就是干跑，报错信息直接给 AI 看」；
 * 只解析 JSON 看不到 `apply` 里的错误（比如 import 了不存在的包、ctx 用法不对）。
 */
class PluginStore(private val context: Context) {

    private val root = File(context.filesDir, "plugins")
    private val registryFile = File(context.filesDir, "plugins.json")
    private val registry = PluginRegistryFile(registryFile)

    data class Result(val ok: Boolean, val message: String)

    fun list(): List<PluginRecord> = registry.list().sortedByDescending { it.installedAt }

    @Synchronized
    fun setEnabled(id: String, enabled: Boolean): Boolean = registry.setEnabled(id, enabled) != null

    /**
     * M84-F：把所有导入插件一起开/关（空壳与全装配验收）。
     * 返回真正改了状态的条数；不动 `plugins.json` 里其它字段。
     */
    @Synchronized
    fun setAllEnabled(enabled: Boolean): Int {
        var changed = 0
        registry.list().forEach { record ->
            if (record.enabled != enabled) {
                registry.upsert(record.copy(enabled = enabled))
                changed += 1
            }
        }
        return changed
    }

    /** M83：当前依赖图（谁提供什么、谁依赖谁、谁缺服务）。插件页直接用。 */
    fun resolution(): xin.dshaa.core.plugins.PluginServiceResolution =
        xin.dshaa.core.plugins.PluginServices.resolve(registry.list())

    /**
     * M83 服务容器：启用集合变了之后重算依赖。
     *
     * 顺序按依赖排序（提供者在前面）重载所有「提供 / 依赖 / 以前缺依赖」的插件：
     * - 提供者的 `ctx.provide` 快照跟着这次 apply 更新；
     * - 依赖者的 `ctx.inject` 回调重新执行（拿到服务 → 重新注册工具；缺服务 → 不注册）；
     * - 最后把 `missingServices` 写回记录，插件页立刻能显示「缺少服务 X」。
     *
     * 返回 (重载的插件数, 仍然缺依赖的插件数)。
     */
    @Synchronized
    fun reconcileServices(): Pair<Int, Int> {
        val before = resolution()
        val byId = registry.list().associateBy { it.id }
        var reloaded = 0
        before.order.forEach { id ->
            val record = byId[id] ?: return@forEach
            if (!record.enabled) return@forEach
            if (record.provides.isEmpty() && record.injects.isEmpty() && record.missingServices.isEmpty()) return@forEach
            reload(id)
            reloaded += 1
        }
        val after = xin.dshaa.core.plugins.PluginServices.resolve(registry.list())
        registry.list().forEach { record ->
            val missing = after.missing[record.id].orEmpty()
            if (record.missingServices != missing) {
                registry.upsert(record.copy(missingServices = missing))
            }
        }
        return reloaded to after.missing.size
    }

    /**
     * 用户点「确认授权」：只允许清单里申请过、这一版已实现、且确实是危险能力的项。
     * 自动允许的权限（fs:workspace / ui）不需要走这里。
     */
    @Synchronized
    fun grant(id: String, raw: String): Result {
        val record = registry.find(id) ?: return Result(false, "这个插件不在清单里")
        val permission = PluginPermissions.parse(raw) ?: return Result(false, "认不出这条权限：$raw")
        if (permission.raw !in record.permissions) return Result(false, "清单里没有申请这条权限：${permission.raw}")
        if (!permission.implemented) return Result(false, "这个版本还没实现「${permission.title}」")
        if (!permission.dangerous) return Result(false, "「${permission.title}」已默认允许")
        val grants = PluginPermissions.sanitizeGrants(
            PluginPermissions.parseAll(record.permissions),
            record.grantedPermissions + permission.raw,
        )
        registry.upsert(record.copy(grantedPermissions = grants.toList()))
        return Result(true, "已允许 ${record.name}：${permission.title}")
    }

    /** 调试通道用：一次把清单里所有危险且已实现的权限都授了（锁屏无 UI 时验宿主链路）。 */
    @Synchronized
    fun grantAll(id: String): Result {
        val record = registry.find(id) ?: return Result(false, "这个插件不在清单里")
        val requested = PluginPermissions.parseAll(record.permissions)
        val dangerous = PluginPermissions.approvalNeeded(requested).map { it.raw }
        if (dangerous.isEmpty()) return Result(false, "${record.name} 没有需要确认的权限")
        val grants = PluginPermissions.sanitizeGrants(requested, record.grantedPermissions + dangerous)
        registry.upsert(record.copy(grantedPermissions = grants.toList()))
        return Result(true, "已允许 ${record.name}：${grants.joinToString("、")}")
    }

    /** 删除插件目录 + 清单记录。返回给用户看的文案。 */
    @Synchronized
    fun uninstall(id: String): Result {
        val record = registry.remove(id) ?: return Result(false, "这个插件不在清单里")
        val removed = runCatching { File(record.path).deleteRecursively() }.getOrDefault(false)
        return if (removed || !File(record.path).exists()) {
            Result(true, "已删除 ${record.name}")
        } else {
            Result(true, "已从清单移除 ${record.name}，但目录删不掉：${record.path}")
        }
    }

    /**
     * 保存用户配置（M30）。只接受 Config schema 里认识的键；值类型暂不校验（下一轮和工具参数校验一起做）。
     * 传空文本 = 恢复默认（清空用户值，执行时再合并默认值）。
     */
    @Synchronized
    fun setConfig(id: String, jsonText: String): Result {
        val record = registry.find(id) ?: return Result(false, "这个插件不在清单里")
        val schema = record.configSchema ?: return Result(false, "${record.name} 没有声明配置（Config）")
        if (jsonText.isBlank()) {
            registry.upsert(record.copy(config = "{}"))
            return Result(true, "已恢复 ${record.name} 的默认配置")
        }
        val config = runCatching { Json.parseToJsonElement(jsonText).jsonObject }.getOrElse {
            return Result(false, "配置不是合法的 JSON 对象：${it.message ?: "解析失败"}")
        }
        val known = PluginSchema.configKeys(schema)
        if (known != null) {
            val unknown = config.keys - known
            if (unknown.isNotEmpty()) {
                return Result(false, "配置里有 schema 不认识的键：${unknown.joinToString("、")}")
            }
        }
        // 类型/必填按 schema 查一遍（先补默认值再查，必填项有默认值就不算缺）。
        val parsed = PluginSchema.parse(schema)
        if (parsed != null) {
            val effective = PluginSchema.parseObject(
                PluginSchema.effectiveText(schema, config.toString(), record.configDefaults),
            )
            PluginSchema.validate(parsed, effective)?.let { invalid ->
                return Result(false, "配置不合法：$invalid")
            }
        }
        registry.upsert(record.copy(config = config.toString()))
        return Result(true, "已保存 ${record.name} 的配置")
    }

    /**
     * 重新读盘试运行（改过插件文件、或上次 QuickJS 不可用时用）。
     *
     * M37 起每次重载都先重读 `files/plugins/<id>/` 里的 `package.json`/`manifest.json`：
     * name/version/description/main/configSchema/configDefaults/permissions 跟着新清单走，
     * 用户配置按新 schema 裁剪，已授权限按新清单过滤。改清单不用再重装。
     */
    @Synchronized
    fun reload(id: String): Result {
        val record = registry.find(id) ?: return Result(false, "这个插件不在清单里")
        val dir = File(record.path)
        if (!dir.isDirectory) {
            val text = "插件目录不在了：${record.path}"
            registry.upsert(record.copy(error = text))
            return Result(false, text)
        }
        val manifest = when (val read = PluginPackage.readManifest(dir)) {
            is ManifestRead.Ok -> read.manifest
            ManifestRead.Missing -> null
            is ManifestRead.Failed -> {
                val text = "清单读取失败：${read.error}"
                registry.upsert(record.copy(error = text))
                return Result(false, text)
            }
        }
        // id 是目录名和授权表的键，重载不跟着变：要换 id 就重新导入。
        if (manifest != null && manifest.id != record.id) {
            val text = "清单 id 改了（${record.id} → ${manifest.id}）：目录和授权还挂在 ${record.id}，要换 id 请重新导入"
            registry.upsert(record.copy(error = text))
            return Result(false, text)
        }
        val mainPath = manifest?.main ?: record.main
        val main = File(dir, mainPath)
        if (!main.isFile) {
            val text = "入口文件不在了：$mainPath"
            registry.upsert(record.copy(error = text))
            return Result(false, text)
        }
        val run = dryRunForConfig(
            main = main,
            mainPath = mainPath,
            format = manifest?.format?.label ?: record.format,
            cwd = dir.absolutePath,
            configJson = record.config,
            defaultsJson = if (manifest != null) manifest.configDefaults?.toString() ?: "{}" else record.configDefaults,
            manifestSchema = manifest?.configSchema?.let { PluginSchema.normalize(it)?.toString() } ?: record.configSchema,
            // 重载时能看到别的插件提供的服务；自己旧快照不算（避免自己 inject 自己）。
            services = xin.dshaa.core.plugins.PluginServices.snapshot(registry.list(), excludingOwner = id),
        )
        val updated = if (manifest != null) {
            PluginReload.apply(record, manifest, run)
        } else {
            // 目录里没有清单文件（旧安装/被手删）：只重跑入口，清单字段沿用记录。
            val newSchema = run.configSchema ?: record.configSchema
            record.copy(
                tools = run.tools.map { it.name },
                toolDescriptions = run.tools.associate { it.name to it.description },
                toolSchemas = run.tools.mapNotNull { tool -> tool.parameters?.let { tool.name to it } }.toMap(),
                imports = run.imports,
                error = run.error,
                configSchema = newSchema,
                config = if (newSchema != null) PluginSchema.prune(newSchema, record.config) else "{}",
                configDefaults = PluginSchema.effectiveText(newSchema, "{}", record.configDefaults),
                configError = run.configError,
                promptSections = run.promptSections,
                contexts = run.contexts,
                promptVariables = run.promptVariables,
                suppressRuntimeContext = run.suppressRuntimeContext,
                commands = run.commands,
                llmProviders = run.llmProviders,
                routes = run.routes,
                projections = run.projections,
                shells = run.shells,
                loadings = run.loadings,
                agents = run.agents,
                storages = run.storages,
                prompts = run.prompts,
                capabilities = run.capabilities,
                unsupported = run.unsupported,
                provides = run.provides,
                injects = run.injects,
                missingServices = run.missingServices,
                effects = run.effects,
                subscriptions = run.subscriptions,
            )
        }
        registry.upsert(updated)
        if (run.error != null) return Result(false, "${updated.name}：试运行报错（见详情）")
        val dropped = PluginSchema.parseObject(record.config).keys - PluginSchema.parseObject(updated.config).keys
        val detail = buildString {
            if (manifest != null && manifest.version != record.version) {
                append("；版本 ${record.version} → ${manifest.version}")
            }
            if (dropped.isNotEmpty()) append("；配置里已不支持的键已移除：${dropped.joinToString("、")}")
        }
        return Result(true, "${updated.name}：试运行通过，注册 ${run.tools.size} 个工具$detail")
    }

    /** 导入一个 zip。复制/解压/试运行都在 IO 线程。 */
    suspend fun install(uri: Uri): Result = withContext(Dispatchers.IO) {
        val temp = File(context.cacheDir, "plugin-import-${System.currentTimeMillis()}.zip")
        try {
            val copyError = copyTo(uri, temp, MAX_IMPORT_BYTES)
            if (copyError != null) return@withContext Result(false, copyError)
            installFrom(temp)
        } catch (t: Throwable) {
            Result(false, "导入失败：${t.message ?: t.javaClass.simpleName}")
        } finally {
            temp.delete()
        }
    }

    /** 从本地文件导入（调试通道 `--es dsh_plugin_zip` 无 UI 验收用；也方便以后接「最近下载」）。 */
    suspend fun installFile(file: File): Result = withContext(Dispatchers.IO) {
        if (!file.isFile) return@withContext Result(false, "文件不存在：${file.absolutePath}")
        if (file.length() > MAX_IMPORT_BYTES) {
            return@withContext Result(false, "插件包太大（>${MAX_IMPORT_BYTES / 1024 / 1024}MB）")
        }
        installFrom(file)
    }

    data class TestResult(
        val ok: Boolean,
        val message: String,
        val id: String? = null,
        val run: PluginDryRun? = null,
    )

    /** Real apply(ctx) in a disposable package directory, without registry/host capabilities. */
    suspend fun testPath(path: File): TestResult = withContext(Dispatchers.IO) {
        val scratch = File(context.cacheDir, "plugin-test-${java.util.UUID.randomUUID()}")
        try {
            check(scratch.mkdirs()) { "建不了试运行目录" }
            val archive = if (path.isDirectory) File(scratch, "package.zip").also { PluginDevPackage.pack(path, it) } else path
            val read = PluginPackage.read(archive)
            if (read is ArchiveRead.Failed) return@withContext TestResult(false, read.error)
            val parsed = PluginPackage.parse((read as ArchiveRead.Ok).files)
            if (parsed is PackageParse.Failed) return@withContext TestResult(false, parsed.error)
            parsed as PackageParse.Ok
            val dir = File(scratch, "package").apply { check(mkdir()) }
            writeFiles(dir, wrappedFiles(parsed))
            val manifest = parsed.manifest
            val run = dryRunForConfig(
                File(dir, manifest.main), manifest.main, manifest.format.label, dir.absolutePath,
                "{}", manifest.configDefaults?.toString() ?: "{}",
                manifestSchema = manifest.configSchema?.let { PluginSchema.normalize(it)?.toString() },
            )
            TestResult(run.error == null && run.configError == null,
                run.error ?: run.configError ?: "试运行通过：${manifest.name}", manifest.id, run)
        } catch (e: Exception) {
            TestResult(false, "试运行失败：${e.message}")
        } finally {
            // UUID direct child created by this invocation; never the source/user directory.
            require(scratch.canonicalFile.parentFile == context.cacheDir.canonicalFile)
            scratch.deleteRecursively()
        }
    }

    /** Directory installation still uses installFile, preserving schema/config/grant rules. */
    suspend fun installDirectory(directory: File): Result = withContext(Dispatchers.IO) {
        val archive = File(context.cacheDir, "plugin-directory-${java.util.UUID.randomUUID()}.zip")
        try {
            PluginDevPackage.pack(directory, archive)
            installFile(archive)
        } catch (e: Exception) {
            Result(false, "目录安装失败：${e.message}")
        } finally {
            require(archive.canonicalFile.parentFile == context.cacheDir.canonicalFile)
            archive.delete()
        }
    }

    /** Parse and trial in staging before replacing the installed package. */
    @Synchronized
    private fun installFrom(archive: File): Result = try {
        installValidated(archive)
    } catch (e: Exception) {
        Result(false, "安装失败：${e.message ?: e.javaClass.simpleName}")
    }

    private fun installValidated(archive: File): Result {
        val read = PluginPackage.read(archive)
        if (read is ArchiveRead.Failed) return Result(false, read.error)

        val parsed = PluginPackage.parse((read as ArchiveRead.Ok).files)
        if (parsed is PackageParse.Failed) return Result(false, parsed.error)
        parsed as PackageParse.Ok

        val manifest = parsed.manifest
        val target = File(root, dirName(manifest.id))
        val previous = registry.find(manifest.id)
        require(registry.list().none { it.id != manifest.id && File(it.path).canonicalFile == target.canonicalFile }) {
            "插件 id 与另一个已安装插件的目录冲突"
        }
        require(previous == null || File(previous.path).canonicalFile == target.canonicalFile) {
            "已安装插件路径不属于预期目录，拒绝替换"
        }
        require(!target.exists() || previous != null) { "目标目录已有未登记内容，拒绝覆盖" }
        PluginInstallTransaction(root, dirName(manifest.id)).use { transaction ->
        val dir = transaction.staging
        writeFiles(dir, wrappedFiles(parsed))

        // M61：组合包补丁只读解析（cordis.patch.yml 的 insert 行）。鲸歌没有 npm 安装环境，
        // 不按 name 装子插件，只在详情里列出组合包的组成和未执行的条目。
        val bundle = manifest.bundlePatch?.let { relative ->
            PluginPackage.normalizePath(relative)?.let { safe ->
                File(dir, safe).takeIf { it.isFile }?.let { BundlePatchParser.parseFile(it) }
            }
        }

        val run = dryRunForConfig(
            File(dir, manifest.main),
            manifest.main,
            manifest.format.label,
            dir.absolutePath,
            previous?.config ?: "{}",
            manifest.configDefaults?.toString() ?: "{}",
            manifestSchema = manifest.configSchema?.let { PluginSchema.normalize(it)?.toString() },
            services = xin.dshaa.core.plugins.PluginServices.snapshot(registry.list(), excludingOwner = manifest.id),
        )
        if (run.error != null || run.configError != null) {
            return Result(false, "${manifest.name}：试运行失败：${run.error ?: run.configError}")
        }
        // 配置来源：插件导出的 Config 优先；没有就用清单里的 config.schema（ToolPkg 风格）。
        val manifestSchema = manifest.configSchema?.let { PluginSchema.normalize(it)?.toString() }
        val configSchema = run.configSchema ?: manifestSchema
        val configDefaults = PluginSchema.effectiveText(configSchema, "{}", manifest.configDefaults?.toString())
        // 覆盖安装/升级：同 id 的老配置能对上新的 Config schema 就保留，不因为重装丢用户配置。
        val config = if (previous != null && configSchema != null) {
            PluginSchema.prune(configSchema, previous.config)
        } else {
            "{}"
        }
        val record = PluginRecord(
            id = manifest.id,
            name = manifest.name,
            version = manifest.version,
            description = manifest.description,
            path = transaction.target.absolutePath,
            main = manifest.main,
            format = manifest.format.label,
            enabled = true,
            tools = run.tools.map { it.name },
            toolDescriptions = run.tools.associate { it.name to it.description },
            toolSchemas = run.tools.mapNotNull { tool -> tool.parameters?.let { tool.name to it } }.toMap(),
            imports = run.imports,
            error = run.error,
            installedAt = System.currentTimeMillis(),
            permissions = manifest.permissions,
            // 导入时只自动给「工作区文件 / 界面」这类非危险能力；网络和 shell 等用户逐项确认。
            grantedPermissions = PluginPermissions.autoGrants(PluginPermissions.parseAll(manifest.permissions)).toList(),
            configSchema = configSchema,
            config = config,
            configDefaults = configDefaults,
            configError = run.configError,
            clientUi = manifest.clientUi,
            bundlePatch = manifest.bundlePatch,
            enginesDsh = manifest.enginesDsh,
            bundleChildren = bundle?.children.orEmpty(),
            bundleNotes = bundle?.notes.orEmpty(),
            presets = bundle?.presets.orEmpty(),
            promptSections = run.promptSections,
            contexts = run.contexts,
            promptVariables = run.promptVariables,
            suppressRuntimeContext = run.suppressRuntimeContext,
            commands = run.commands,
            llmProviders = run.llmProviders,
            routes = run.routes,
            projections = run.projections,
            shells = run.shells,
            loadings = run.loadings,
            agents = run.agents,
            storages = run.storages,
            prompts = run.prompts,
            capabilities = run.capabilities,
            unsupported = run.unsupported,
            provides = run.provides,
            injects = run.injects,
            missingServices = run.missingServices,
            effects = run.effects,
            subscriptions = run.subscriptions,
        )
        require(registryFile.canonicalFile.parentFile == context.filesDir.canonicalFile && registryFile.canonicalFile.name == "plugins.json") {
            "插件清单路径异常"
        }
        val registryBefore = registryFile.takeIf { it.exists() }?.readBytes()
        transaction.commit {
            try {
                registry.upsert(record)
                // Registry save currently swallows IO errors, so verify persisted metadata.
                check(registry.find(record.id) == record) { "插件清单写入失败" }
            } catch (failure: Exception) {
                // Restore metadata before the transaction rolls the package directory back.
                if (registryBefore != null) registryFile.writeBytes(registryBefore)
                else if (registryFile.exists()) check(registryFile.delete()) { "插件清单回滚失败" }
                throw failure
            }
        }
        val suffix = when {
            run.error != null -> "；试运行报错（见详情）"
            run.tools.isEmpty() -> "；没有注册工具（可能只是 hook 插件）"
            else -> "；注册 ${run.tools.size} 个工具：" + run.tools.take(3).joinToString("、") { it.name }
        } + if (bundle != null && bundle.children.isNotEmpty()) {
            "；组合包 insert ${bundle.children.size} 个子插件"
        } else {
            ""
        }
        return Result(true, "已导入 ${manifest.name} ${manifest.version}$suffix")
        }
    }

    private fun wrappedFiles(parsed: PackageParse.Ok): List<PluginFile> {
        val files = parsed.files.toMutableList()
        val main = parsed.manifest.main
        val index = files.indexOfFirst { it.path == main }
        if (index >= 0) {
            val original = files[index].bytes
            val source = original.toString(Charsets.UTF_8)
            if (ToolPkg.isToolPkg(source)) {
                files[index] = PluginFile(main, ToolPkg.wrap(source).toByteArray(Charsets.UTF_8))
                if (files.none { it.path == "toolpkg-original.js" }) files.add(PluginFile("toolpkg-original.js", original))
            }
        }
        return files
    }

    // ---- 内部 ----

    /** 复制 SAF 流到缓存文件；超过 [limit] 或读不了时返回错误文案。 */
    private fun copyTo(uri: Uri, target: File, limit: Long): String? {
        val input = runCatching { context.contentResolver.openInputStream(uri) }.getOrNull()
            ?: return "选中的文件打不开（可能没有读权限）"
        input.use { stream ->
            target.outputStream().use { out ->
                val buffer = ByteArray(32 * 1024)
                var total = 0L
                while (true) {
                    val read = stream.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > limit) return "插件包太大（>${limit / 1024 / 1024}MB）"
                    out.write(buffer, 0, read)
                }
            }
        }
        return if (target.length() == 0L) "选中的文件是空的" else null
    }

    private fun writeFiles(dir: File, files: List<xin.dshaa.core.plugins.PluginFile>) {
        val canonicalDir = dir.canonicalFile
        val prefix = canonicalDir.path + File.separator
        files.forEach { file ->
            val dest = File(canonicalDir, file.path).canonicalFile
            if (!dest.path.startsWith(prefix)) {
                throw IllegalStateException("不安全的路径：${file.path}")
            }
            dest.parentFile?.mkdirs()
            dest.writeBytes(file.bytes)
        }
    }

    /**
     * 带配置的试运行：先跑一次拿到 `Config` schema，再按「默认值 + 用户配置」跑第二次。
     *
     * 为什么要两次：apply 可能按 config 决定注册什么工具（例如开关型插件），第一次用 `{}` 只能
     * 拿到 Config 形状；第二次才是用户实际会看到的行为。安装/重新加载多跑一次 QuickJS 可接受。
     */
    private fun dryRunForConfig(
        main: File,
        mainPath: String,
        format: String,
        cwd: String?,
        configJson: String,
        defaultsJson: String = "{}",
        manifestSchema: String? = null,
        services: Map<String, String> = emptyMap(),
    ): PluginDryRun = configuredDryRun(configJson, defaultsJson, manifestSchema) { effective ->
        dryRun(main, mainPath, format, cwd, effective, services)
    }

    /** 在 QuickJS 里跑一次插件模块；任何失败都归一成 [PluginDryRun.error]。 */
    private fun dryRun(
        main: File,
        mainPath: String,
        format: String,
        cwd: String? = null,
        configJson: String = "{}",
        services: Map<String, String> = emptyMap(),
    ): PluginDryRun {
        if (!main.isFile) return PluginDryRun(error = "入口文件不存在：$mainPath")
        val source = runCatching { main.readText(Charsets.UTF_8) }
            .getOrElse { return PluginDryRun(error = "读不了入口文件：${it.message}") }
        val module = runCatching { PluginModule.transform(source) }
            .getOrElse { return PluginDryRun(error = "解析模块失败：${it.message}") }
        val warnings = module.warnings
        // M83：把「其他已启用插件提供的服务快照」交给这一次 apply，ctx.inject 才有值可用。
        val serviceHost = xin.dshaa.core.plugins.PluginServiceHost(initial = services)
        val js = runCatching {
            QuickJs(object : JsHost {
                override fun callSync(method: String, payloadJson: String): String? =
                    serviceHost.callSync(method, payloadJson)

                override fun callAsync(method: String, payloadJson: String, callId: Int) = Unit
            })
        }.getOrElse {
            return PluginDryRun(
                imports = module.imports,
                error = "QuickJS 不可用（这个 ABI 没编进去？）：${it.message}",
            )
        }
        return try {
            // 插件里写了 while(true) 或 async apply 永远不结算，不能让「导入」把界面线程焊死。
            val watchdog = Thread {
                try {
                    Thread.sleep(DRY_RUN_TIMEOUT_MS)
                    js.interrupt()
                } catch (_: InterruptedException) {
                    // 正常收尾
                }
            }.apply {
                name = "plugin-dry-run-watchdog"
                isDaemon = true
                start()
            }
            val outcome: JsOutcome = try {
                js.evaluate(PluginModule.dryRunScript(module, cwd, configJson), mainPath)
            } finally {
                watchdog.interrupt()
            }
            when (outcome.status) {
                JsStatus.COMPLETED -> {
                    val payload = decodeString(outcome.valueJson)
                    if (payload == null) {
                        PluginDryRun(imports = module.imports, error = "试运行没有返回结果（${format}）")
                    } else {
                        val run = PluginModule.parseDryRun(payload)
                        val withWarnings = when {
                            run.error != null -> run
                            warnings.isNotEmpty() -> run.copy(error = "警告：" + warnings.joinToString("；"))
                            run.relativeImports.isNotEmpty() ->
                                run.copy(error = "相对依赖没有加载：" + run.relativeImports.joinToString("、") + "（插件要打包成单文件）")
                            else -> run
                        }
                        withWarnings
                    }
                }

                JsStatus.INTERRUPTED -> PluginDryRun(imports = module.imports, error = "试运行超时（>${DRY_RUN_TIMEOUT_MS / 1000} 秒）或已被中断")
                JsStatus.ERROR -> PluginDryRun(imports = module.imports, error = outcome.error ?: "试运行报错")
            }
        } catch (t: Throwable) {
            PluginDryRun(imports = module.imports, error = "试运行崩溃：${t.message ?: t.javaClass.simpleName}")
        } finally {
            runCatching { js.close() }
        }
    }

    /** QuickJS 返回值是「JSON 编码过的 JSON 字符串」：解两层。 */
    private fun decodeString(valueJson: String?): String? {
        if (valueJson == null) return null
        val element = runCatching { Json.parseToJsonElement(valueJson) }.getOrNull() ?: return null
        return runCatching { element.jsonPrimitive.content }.getOrNull()
    }

    /** id 只用来当目录名：`@acme/plugin-x` → `acme-plugin-x`。 */
    private fun dirName(id: String): String =
        id.map { ch -> if (ch.isLetterOrDigit() || ch == '.' || ch == '_' || ch == '-') ch else '-' }
            .joinToString("")
            .take(80)
            .ifBlank { "plugin" }

    companion object {
        /** Shared by test/reload/install; [evaluate] stays replaceable for JVM config regressions. */
        internal fun configuredDryRun(
            configJson: String,
            defaultsJson: String = "{}",
            manifestSchema: String? = null,
            evaluate: (String) -> PluginDryRun,
        ): PluginDryRun {
            val first = evaluate("{}")
            val schema = first.configSchema ?: manifestSchema ?: return first
            val pruned = PluginSchema.prune(schema, configJson)
            val effective = PluginSchema.effectiveText(schema, pruned, defaultsJson)
            if (effective == "{}") return first.copy(configSchema = schema)
            val second = evaluate(effective)
            return second.copy(
                configSchema = second.configSchema ?: schema,
                configError = second.configError ?: first.configError,
            )
        }

        private const val MAX_IMPORT_BYTES = 64L * 1024 * 1024
        /** 试运行上限：async apply 挂住/死循环时中断，别让导入页一直转圈。 */
        private const val DRY_RUN_TIMEOUT_MS = 12_000L
    }
}

package xin.dshaa.nativeapp.node

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.util.zip.ZipInputStream

/**
 * Node 运行时落盘（docs/185 P2 / docs/187 §九 第 6 项）。
 *
 * 目录约定（升级只换运行时，**不动用户插件**）：
 * ```
 * files/node/runtime/<RUNTIME_VERSION>/   解压出来的 lib/ + dsh/（可整体删掉重来）
 * files/node/data/                        用户数据，升级永远不删
 *   plugins/                              用户装的 Node 插件
 *   node-host-plugins.json                插件清单（host.mjs 按它加载）
 *   home/                                 HOME
 *   node_modules -> runtime/<v>/dsh/node_modules（软链：插件里 import 裸包用）
 * files/node-audit.jsonl                  桥审计（在 data 之外，按方案要求）
 * ```
 *
 * 老版本（PR #6）是 `files/node/dsh`、`files/node/lib`、`files/node/home` 平铺。
 * 升级时 [migrateLegacy] 先把插件/清单/home 搬进 `data/` 再删旧目录——老用户插件不能丢。
 *
 * 为什么必须解压：
 * - `libnode.so` 只能从 `nativeLibraryDir` 里 exec（targetSdk 34 下数据目录 noexec），所以它走
 *   `jniLibs`，由系统在安装时落地；
 * - 剩下 10 个依赖库必须真实存在于 `LD_LIBRARY_PATH` 的某个目录里，且 `dsh/` 那棵树要能被
 *   Node 用相对 cwd 找到 `node_modules`，这两样在 APK 的 assets 里都没法直接用。
 */
class NodeRuntimeInstaller(private val context: Context) {

    /** 首次安装进度：percent 只是估算（按 zip 条目算），detail 给用户看正在解哪个文件。 */
    data class Progress(val percent: Int, val detail: String)

    /**
     * docs/190-B：`install.json` 的内容（运行时对账用）。
     *
     * 条目数/字节数写的是**真实解压结果**，`nodehost_doctor` 拿它和 [TOTAL_ENTRIES] 对账；
     * 文件缺失/坏掉返回 null（doctor 会报 runtime_missing）。
     */
    data class Stamp(
        val version: String,
        val entries: Int,
        val bytes: Long,
        val installedAt: Long,
    )

    val root: File = File(context.filesDir, "node")
    val runtimeRoot: File = File(root, "runtime")

    /** 当前版本的运行时目录：升级 = 换一个 `<version>` 子目录。 */
    val runtimeDir: File = File(runtimeRoot, RUNTIME_VERSION)
    val libDir: File = File(runtimeDir, "lib")
    val dshDir: File = File(runtimeDir, "dsh")
    val hostScript: File = File(dshDir, "host.mjs")

    /** 用户数据（插件/清单/home）：升级运行时不碰这里。 */
    val dataDir: File = File(root, "data")
    val pluginsDir: File = File(dataDir, "plugins")
    val manifestFile: File = File(dataDir, "node-host-plugins.json")
    val homeDir: File = File(dataDir, "home")

    val tmpDir: File = File(context.cacheDir, "node-tmp")

    private val stampFile: File = File(runtimeDir, "install.json")

    /** Node 二进制：只有 nativeLibraryDir 里的这份能执行。 */
    val binaryFile: File
        get() = File(context.applicationInfo.nativeLibraryDir, "libnode.so")

    /** 依赖库目录必须在 LD_LIBRARY_PATH 第一位（nativeLibraryDir 第二，见到 libc++ 之类时兜底）。 */
    val nativeLibraryDir: String
        get() = context.applicationInfo.nativeLibraryDir

    /** 软链：插件从 `data/plugins/<x>` 里 import `@deepseek-ai/…` 时靠它爬回运行时。 */
    val dataNodeModules: File = File(dataDir, "node_modules")

    fun isInstalled(): Boolean = stampMatches() && hostScript.isFile && libDir.isDirectory

    /** 解压或校验；任何失败都用 Result 带出去，不往调用方抛。 */
    suspend fun ensureInstalled(onProgress: (Progress) -> Unit = {}): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            dataDir.mkdirs()
            pluginsDir.mkdirs()
            homeDir.mkdirs()
            tmpDir.mkdirs()

            if (isInstalled()) {
                ensureDataNodeModules()
                // 运行时自带的演示插件随运行时走：升级后必须对齐（用户插件不动，见 refreshRuntimeOwnedPlugins）。
                refreshRuntimeOwnedPlugins()
                onProgress(Progress(100, "已就绪"))
                return@runCatching
            }
            if (!context.assets.list("node").orEmpty().contains(RUNTIME_ASSET_NAME)) {
                error("安装包缺少 assets/node/$RUNTIME_ASSET_NAME（这个 APK 没带 Node 运行时）")
            }

            // 1) 老布局迁移：先把用户插件/清单/home 搬到 data/，再删旧目录。
            migrateLegacy()

            // 2) 只重建「当前版本」的运行时目录；别的版本等成功后再清。
            runtimeDir.deleteRecursively()
            runtimeRoot.mkdirs()
            runtimeDir.mkdirs()

            val started = System.currentTimeMillis()
            var entries = 0
            var bytes = 0L
            var lastPercent = -1
            context.assets.open("node/$RUNTIME_ASSET_NAME").use { raw ->
                ZipInputStream(raw.buffered(BUFFER_SIZE)).use { zip ->
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        // 打包工具在 Windows 上可能写出反斜杠条目名，解出来会变成
                        // 一堆名为 `dsh\node_modules…` 的扁平文件，直接判坏更省事。
                        if (entry.name.contains('\\')) error("安装包条目名不合法（含反斜杠）：${entry.name}")
                        val target = File(runtimeDir, entry.name)
                        // zip 里的相对路径只允许落在 runtimeDir 之下（防 zip-slip）。
                        if (!target.canonicalPath.startsWith(runtimeDir.canonicalPath + File.separator)) {
                            error("安装包里有越界路径：${entry.name}")
                        }
                        if (entry.isDirectory) {
                            target.mkdirs()
                        } else {
                            target.parentFile?.mkdirs()
                            FileOutputStream(target).use { out ->
                                val buffer = ByteArray(BUFFER_SIZE)
                                while (true) {
                                    val read = zip.read(buffer)
                                    if (read <= 0) break
                                    out.write(buffer, 0, read)
                                    bytes += read
                                }
                            }
                        }
                        zip.closeEntry()
                        entries++
                        val percent = (entries * 100 / TOTAL_ENTRIES).coerceIn(0, 99)
                        if (percent != lastPercent) {
                            lastPercent = percent
                            onProgress(Progress(percent, entry.name))
                        }
                    }
                }
            }
            if (!hostScript.isFile) error("解压后找不到 dsh/host.mjs")
            writeStamp(entries, bytes)

            // 3) 首次安装把运行时自带的演示插件种进 data/；老用户已有清单时不动。
            seedDataFromRuntime()
            ensureDataNodeModules()
            pruneOldRuntimes()

            val elapsed = System.currentTimeMillis() - started
            Log.i(TAG, "node runtime installed: version=$RUNTIME_VERSION entries=$entries bytes=$bytes in ${elapsed}ms")
            onProgress(Progress(100, "已就绪"))
        }
    }

    /**
     * 老布局（`files/node/dsh|lib|home` + `files/node/install.json`）迁移。
     *
     * 只搬用户的东西：`dsh/plugins/` 下全部文件、`dsh/node-host-plugins.json`、`home/` 下全部文件。
     * 搬完才删旧目录；任何一步抛错都保留旧目录（宁可多占一份，也不丢插件）。
     */
    private fun migrateLegacy() {
        val legacyDsh = File(root, "dsh")
        val legacyLib = File(root, "lib")
        val legacyHome = File(root, "home")
        val legacyStamp = File(root, "install.json")
        val hasLegacy = legacyDsh.isDirectory || legacyLib.isDirectory || legacyHome.isDirectory
        if (!hasLegacy) {
            runCatching { legacyStamp.delete() }
            return
        }
        dataDir.mkdirs()
        pluginsDir.mkdirs()

        val oldPlugins = File(legacyDsh, "plugins")
        if (oldPlugins.isDirectory) {
            copyMissing(oldPlugins, pluginsDir)
        }
        val oldManifest = File(legacyDsh, "node-host-plugins.json")
        if (oldManifest.isFile && !manifestFile.exists()) {
            runCatching { oldManifest.copyTo(manifestFile, overwrite = false) }
        }
        if (legacyHome.isDirectory && !homeDir.exists()) {
            runCatching {
                if (!legacyHome.renameTo(homeDir)) copyMissing(legacyHome, homeDir)
            }
        }

        // 复制完成后再删；失败只记日志（旧目录留着不致命，下次启动还会重试）。
        val migratedPlugins = pluginsDir.listFiles()?.size ?: 0
        val hadPlugins = oldPlugins.isDirectory && (oldPlugins.listFiles()?.isNotEmpty() == true)
        if (hadPlugins && migratedPlugins == 0) {
            Log.w(TAG, "legacy migration incomplete, keep ${legacyDsh.absolutePath}")
            return
        }
        runCatching { legacyDsh.deleteRecursively() }
        runCatching { legacyLib.deleteRecursively() }
        runCatching { legacyHome.deleteRecursively() }
        runCatching { legacyStamp.delete() }
        Log.i(TAG, "legacy node layout migrated: plugins=$migratedPlugins data=${dataDir.absolutePath}")
    }

    /** 逐文件复制，目标已存在就跳过（不覆盖用户后来改过的插件）。 */
    private fun copyMissing(from: File, to: File) {
        runCatching {
            from.walkTopDown().forEach { src ->
                if (!src.isFile) return@forEach
                val rel = src.relativeTo(from).invariantSeparatorsPath
                val dst = File(to, rel)
                if (dst.exists()) return@forEach
                dst.parentFile?.mkdirs()
                runCatching { src.copyTo(dst, overwrite = false) }
                    .onFailure { Log.w(TAG, "migrate copy failed: $rel ${it.message}") }
            }
        }.onFailure { Log.w(TAG, "migrate walk failed: ${it.message}") }
    }

    /** 新装用户：运行时 zip 里带了演示插件与清单，第一次落进 data/。 */
    private fun seedDataFromRuntime() {
        val runtimeManifest = File(dshDir, "node-host-plugins.json")
        if (!manifestFile.exists() && runtimeManifest.isFile) {
            runCatching { runtimeManifest.copyTo(manifestFile, overwrite = false) }
        }
        val runtimePlugins = File(dshDir, "plugins")
        if (runtimePlugins.isDirectory) copyMissing(runtimePlugins, pluginsDir)
    }

    /**
     * **运行时自带**的演示插件（`dsh/plugins/<name>`）每次启动对齐到当前运行时。
     *
     * 为什么必须覆盖：`seedDataFromRuntime` 用的是「缺才复制」，老用户升级后 `data/plugins/hello-plugin`
     * 还是上一版（P3 真机实测：新运行时起来了，但演示插件没有 `ctx.llm.register`/`ctx.commands.register`，
     * 于是 llm/command 两条链静默为空）。演示插件属于运行时资产，不是用户插件 ——
     * 名单写死在 [RUNTIME_OWNED_PLUGINS]，**不碰**用户导入的任何目录。
     */
    private fun refreshRuntimeOwnedPlugins() {
        val runtimePlugins = File(dshDir, "plugins")
        if (!runtimePlugins.isDirectory) return
        RUNTIME_OWNED_PLUGINS.forEach { name ->
            val source = File(runtimePlugins, name)
            if (!source.isDirectory) return@forEach
            val target = File(pluginsDir, name)
            copyTree(source, target, overwrite = true)
            Log.i(TAG, "runtime-owned plugin refreshed: $name -> ${target.absolutePath}")
        }
    }

    /** 整棵目录复制；[overwrite] 为 true 时文件级覆盖（运行时资产用），false 时跳过已存在（用户插件用）。 */
    private fun copyTree(from: File, to: File, overwrite: Boolean) {
        runCatching {
            from.walkTopDown().forEach { src ->
                if (!src.isFile) return@forEach
                val rel = src.relativeTo(from).invariantSeparatorsPath
                val dst = File(to, rel)
                if (!overwrite && dst.exists()) return@forEach
                dst.parentFile?.mkdirs()
                runCatching { src.copyTo(dst, overwrite = overwrite) }
                    .onFailure { Log.w(TAG, "copy failed: $rel ${it.message}") }
            }
        }.onFailure { Log.w(TAG, "walk failed for ${from.absolutePath}: ${it.message}") }
    }

    /**
     * `data/node_modules` → `runtime/<version>/dsh/node_modules` 软链。
     * 插件文件在 data/ 下，裸包导入（`@deepseek-ai/dsh-tools`）要靠这条链爬回运行时。
     */
    private fun ensureDataNodeModules() {        val target = File(dshDir, "node_modules")
        if (!target.isDirectory) return
        runCatching {
            Files.deleteIfExists(dataNodeModules.toPath())
            Files.createSymbolicLink(dataNodeModules.toPath(), target.toPath())
            Log.i(TAG, "data/node_modules -> ${target.absolutePath}")
        }.onFailure {
            // 不致命：不 import 裸包的插件照常可用；失败的插件会在 host.hello.plugins 里带原因。
            Log.w(TAG, "create data/node_modules symlink failed: ${it.message}")
        }
    }

    /** 成功换到新版本后，删掉旧版本运行时（data/ 不动）。 */
    private fun pruneOldRuntimes() {
        runtimeRoot.listFiles()?.forEach { dir ->
            if (dir.isDirectory && dir.name != RUNTIME_VERSION) {
                Log.i(TAG, "prune old node runtime: ${dir.name}")
                runCatching { dir.deleteRecursively() }
            }
        }
    }

    private fun stampMatches(): Boolean = stamp()?.version == RUNTIME_VERSION

    /** docs/190-B：读 `install.json`（运行时版本/条目数/字节数/安装时间）；缺失或损坏返回 null。 */
    fun stamp(): Stamp? = runCatching {
        if (!stampFile.isFile) return null
        val obj = Json.parseToJsonElement(stampFile.readText(Charsets.UTF_8)) as? JsonObject
            ?: return null
        Stamp(
            version = (obj["version"] as? JsonPrimitive)?.content.orEmpty(),
            entries = (obj["entries"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 0,
            bytes = (obj["bytes"] as? JsonPrimitive)?.content?.toLongOrNull() ?: 0L,
            installedAt = (obj["installedAt"] as? JsonPrimitive)?.content?.toLongOrNull() ?: 0L,
        )
    }.getOrNull()

    private fun writeStamp(entries: Int, bytes: Long) {
        val json = buildJsonObject {
            put("version", RUNTIME_VERSION)
            put("entries", entries)
            put("bytes", bytes)
            put("installedAt", System.currentTimeMillis())
        }
        stampFile.writeText(json.toString(), Charsets.UTF_8)
    }

    companion object {
        private const val TAG = "DshNodeHost"

        /**
         * 改运行时内容（host.mjs / 依赖）就要动这个版本号：它决定已装用户会不会重新解压。
         *
         * p3.1：`host.mjs` 加了 `llm` / `commands` 两个宿主服务（P3-b/c），
         * `plugins/hello-plugin` 加了演示供应商与演示命令；条目集合未变（2526 条）。
         */
        const val RUNTIME_VERSION = "node26.4.0-dsh0.2.2-rc.5-p3.1"

        private const val RUNTIME_ASSET_NAME = "runtime.zip"
        private const val BUFFER_SIZE = 64 * 1024

        /**
         * 运行时自带的演示插件目录名（`dsh/plugins/<name>`）。这些是**运行时资产**：
         * 每次启动都对齐到当前运行时；不在名单里的 `data/plugins/<x>` 一律当用户插件，永不覆盖。
         */
        private val RUNTIME_OWNED_PLUGINS = setOf("hello-plugin")

        /**
         * 运行时 zip 的条目数（含目录），只用来算进度百分比。
         *
         * docs/190-C：旧值 2184 是早期估算，实际打包 2526 条 → 进度会提前冲到 99% 再长时间不动。
         * 现在与 `assets/node/runtime.zip` 的真实条目数对齐，并由 JVM 测试
         * `NodeRuntimeInstallerEntryCountTest` 守住（改 zip 忘了改常量会红）。
         */
        internal const val TOTAL_ENTRIES = 2526
    }
}

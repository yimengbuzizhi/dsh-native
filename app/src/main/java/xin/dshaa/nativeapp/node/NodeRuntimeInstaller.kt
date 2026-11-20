package xin.dshaa.nativeapp.node

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipInputStream

/**
 * Node 运行时落盘（docs/185 P2）：把 APK 里 `assets/node/runtime.zip` 解到 `files/node/`。
 *
 * 为什么必须解压：
 * - `libnode.so` 只能从 `nativeLibraryDir` 里 exec（targetSdk 34 下数据目录 noexec），所以它走
 *   `jniLibs`，由系统在安装时落地；
 * - 剩下 10 个依赖库必须真实存在于 `LD_LIBRARY_PATH` 的某个目录里，且 `dsh/` 那棵树要能被
 *   Node 用相对 cwd 找到 `node_modules`，这两样在 APK 的 assets 里都没法直接用。
 *
 * 幂等：装完写 `install.json`（版本号 + 已完成标记）。版本号不匹配或 `dsh/host.mjs` 不在，
 * 就整个目录重解一遍——宁可多花一两秒，也不留半装状态去 spawn。
 */
class NodeRuntimeInstaller(private val context: Context) {

    /** 首次安装进度：percent 只是估算（按 zip 条目算），detail 给用户看正在解哪个文件。 */
    data class Progress(val percent: Int, val detail: String)

    val root: File = File(context.filesDir, "node")
    val libDir: File = File(root, "lib")
    val dshDir: File = File(root, "dsh")
    val homeDir: File = File(root, "home")
    val tmpDir: File = File(context.cacheDir, "node-tmp")
    val hostScript: File = File(dshDir, "host.mjs")

    private val stampFile: File = File(root, "install.json")

    /** Node 二进制：只有 nativeLibraryDir 里的这份能执行。 */
    val binaryFile: File
        get() = File(context.applicationInfo.nativeLibraryDir, "libnode.so")

    /** 依赖库目录必须在 LD_LIBRARY_PATH 第一位（nativeLibraryDir 第二，见到 libc++ 之类时兜底）。 */
    val nativeLibraryDir: String
        get() = context.applicationInfo.nativeLibraryDir

    fun isInstalled(): Boolean = stampMatches() && hostScript.isFile && libDir.isDirectory

    /** 解压或校验；任何失败都用 Result 带出去，不往调用方抛。 */
    suspend fun ensureInstalled(onProgress: (Progress) -> Unit = {}): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            if (isInstalled()) {
                onProgress(Progress(100, "已就绪"))
                return@runCatching
            }
            if (!context.assets.list("node").orEmpty().contains(RUNTIME_ASSET_NAME)) {
                error("安装包缺少 assets/node/$RUNTIME_ASSET_NAME（这个 APK 没带 Node 运行时）")
            }
            // 半装/旧版本/坏 zip 一律整目录重来：root 只归我们，清掉最干净
            // （踩过：zip 条目用反斜杠时，解出来的文件叫 `dsh\node_modules…`，
            //  只删 dsh/ 删不掉这些扁平垃圾，下次启动就会误判已装）。
            root.deleteRecursively()
            root.mkdirs()
            libDir.mkdirs()
            dshDir.mkdirs()
            tmpDir.mkdirs()
            homeDir.mkdirs()

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
                        val target = File(root, entry.name)
                        // zip 里的相对路径只允许落在 root 之下（防 zip-slip）。
                        if (!target.canonicalPath.startsWith(root.canonicalPath + File.separator)) {
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
            val elapsed = System.currentTimeMillis() - started
            Log.i(TAG, "node runtime installed: entries=$entries bytes=$bytes in ${elapsed}ms")
            onProgress(Progress(100, "已就绪"))
        }
    }

    private fun stampMatches(): Boolean {
        if (!stampFile.isFile) return false
        return runCatching {
            val text = stampFile.readText(Charsets.UTF_8)
            Json.parseToJsonElement(text).let { element ->
                val obj = element as? kotlinx.serialization.json.JsonObject ?: return@runCatching false
                obj["version"]?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content } == RUNTIME_VERSION
            }
        }.getOrDefault(false)
    }

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

        /** 改运行时内容就要动这个版本号：它决定已装用户会不会重新解压。 */
        const val RUNTIME_VERSION = "node26.4.0-dsh0.2.2-rc.5-p2.3"

        private const val RUNTIME_ASSET_NAME = "runtime.zip"
        private const val BUFFER_SIZE = 64 * 1024

        /** 打 zip 时的条目数（不含目录）；只用来算进度百分比。 */
        private const val TOTAL_ENTRIES = 2184
    }
}

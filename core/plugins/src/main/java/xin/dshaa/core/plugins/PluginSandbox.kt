package xin.dshaa.core.plugins

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** 插件越权时抛这个；会变成 `{ok:false, code:"PERMISSION_DENIED"}`，不会把宿主异常栈给插件。 */
class PluginAccessDenied(message: String) : Exception(message)

/**
 * 插件宿主能力的**唯一入口**（M28）：受限 fs、域名白名单 https、需确认的 shell。
 *
 * JS 侧不直接碰文件/网络：`node:fs` / `ctx.http` / `fetch` / `child_process` 全部落到
 * `__dshCallSync("fs.read", …)` / `__dshCallAsync("net.fetch", …)`，由这里先查授权表再执行。
 *
 * 边界（文档和群里都要说清楚）：
 * - **不是内核级沙箱**：插件代码仍和 App 同进程（QuickJS），这里拦的是「能力调用」，
 *   不是内存/CPU 隔离；恶意插件写死循环要靠宿主超时中断。
 * - `fs:workspace` 的根 = 当前工作区；`fs:any` 才是任意 App 可访问路径。
 * - 网络只允许 https；本机/链路本地地址任何权限都拒绝；重定向每一跳都重新查域名。
 * - shell 以 **App 身份**执行（不是 root），命令和输出都有上限。
 */
class PluginSandbox(
    private val pluginId: String,
    private val workspace: File,
    private val permissions: List<PluginPermission> = emptyList(),
    private val grantedPermissions: List<String> = emptyList(),
    /** M67：设备/无障碍/记忆库等 app 侧能力；null = 当前宿主不提供（单测/试运行）。 */
    private val bridge: PluginHostBridge? = null,
) {

    /** 宿主调用结果。[payloadJson] 是给 JS 的完整 JSON（`{ok:true,…}` / `{ok:false,error,code}`）。 */
    data class Reply(
        val ok: Boolean,
        val payloadJson: String,
        val error: String? = null,
        val code: String? = null,
    )

    private val grants: Set<String> = PluginPermissions.sanitizeGrants(permissions, grantedPermissions)

    init {
        runCatching { workspace.mkdirs() }
    }

    /** 同步能力（fs/shell/log）。在 JS 线程上调用，阻塞到执行完。 */
    fun call(method: String, payloadJson: String): Reply {
        val args = parseArgs(payloadJson)
            ?: return fail("宿主调用参数不是 JSON 对象：$method", "INVALID_ARGUMENT")
        return try {
            when (method) {
                "log" -> {
                    // 只是给 JS console 一个出口；真正的日志由 App 侧 JsHost 打。
                    ok { put("logged", true) }
                }
                "fs.read" -> fsRead(args)
                "fs.readBinary" -> fsReadBinary(args)
                "fs.write" -> fsWrite(args)
                "fs.writeBinary" -> fsWriteBinary(args)
                "fs.exists" -> fsExists(args)
                "fs.list" -> fsList(args)
                "fs.dir" -> fsDir(args)
                "fs.info" -> fsInfo(args)
                "fs.stat" -> fsStat(args)
                "fs.mkdir" -> fsMkdir(args)
                "fs.remove" -> fsRemove(args)
                "fs.rename" -> fsRename(args)
                "fs.copy" -> fsCopy(args)
                "fs.zip" -> fsZip(args)
                "fs.unzip" -> fsUnzip(args)
                "net.fetch" -> netFetch(args)
                "net.upload" -> netUpload(args)
                "net.visit.put" -> visitPut(args)
                "net.visit.get" -> visitGet(args)
                "host.call" -> hostCall(args)
                // M85-A：整壳 → 宿主的同步命令（send/stop/切会话/关壳…）；白名单在 app 侧。
                "surface.command" -> surfaceCommand(args)
                // M83 阶段 3：把 APK 里已有的库开成原语
                "zip.zstd" -> zstd(args)
                "ocr.recognize" -> ocr(args)
                "shell.exec" -> shellExec(args)
                "timer.wait" -> timerWait(args)
                else -> fail("未知的宿主能力：$method", "UNKNOWN_METHOD")
            }
        } catch (e: PluginAccessDenied) {
            fail(e.message ?: "插件没有这个权限", "PERMISSION_DENIED")
        } catch (e: PluginPathException) {
            fail(e.message ?: "路径不合法", "PATH_DENIED")
        } catch (e: Exception) {
            fail(e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName, "PLUGIN_HOST_ERROR")
        }
    }

    // ---- 文件 ----

    private fun fsRead(args: JsonObject): Reply {
        val raw = args.text("path") ?: throw IllegalArgumentException("fs.read 缺少 path")
        val file = fsFile(raw)
        if (!file.exists()) throw IOException("文件不存在：$raw")
        if (file.isDirectory) throw IOException("是个目录，不是文件：$raw")
        if (file.length() > MAX_READ_BYTES) {
            throw IOException("文件太大（>${MAX_READ_BYTES / 1024 / 1024}MB）：$raw")
        }
        val bytes = file.readBytes()
        return ok {
            put("text", bytes.toString(Charsets.UTF_8))
            put("size", bytes.size)
        }
    }

    private fun fsReadBinary(args: JsonObject): Reply {
        val raw = args.text("path") ?: throw IllegalArgumentException("fs.readBinary 缺少 path")
        val file = fsFile(raw)
        if (!file.exists()) throw IOException("文件不存在：$raw")
        if (file.isDirectory) throw IOException("是个目录，不是文件：$raw")
        if (file.length() > MAX_BINARY_BYTES) {
            throw IOException("文件太大（>${MAX_BINARY_BYTES / 1024 / 1024}MB）：$raw")
        }
        val bytes = file.readBytes()
        return ok {
            put("base64", java.util.Base64.getEncoder().encodeToString(bytes))
            put("size", bytes.size)
        }
    }

    private fun fsWriteBinary(args: JsonObject): Reply {
        val raw = args.text("path") ?: throw IllegalArgumentException("fs.writeBinary 缺少 path")
        val encoded = args.text("base64") ?: throw IllegalArgumentException("fs.writeBinary 缺少 base64")
        val bytes = runCatching { java.util.Base64.getDecoder().decode(encoded) }.getOrElse {
            throw IllegalArgumentException("base64 内容不合法")
        }
        if (bytes.size > MAX_BINARY_BYTES) {
            throw IOException("写入内容太大（>${MAX_BINARY_BYTES / 1024 / 1024}MB）")
        }
        val file = fsFile(raw)
        if (file.isDirectory) throw IOException("是个目录，不是文件：$raw")
        val parent = file.parentFile
        if (parent != null && !parent.exists()) throw IOException("上级目录不存在：${parent.path}")
        file.writeBytes(bytes)
        return ok { put("size", bytes.size) }
    }

    private fun fsWrite(args: JsonObject): Reply {
        val raw = args.text("path") ?: throw IllegalArgumentException("fs.write 缺少 path")
        val text = args.text("text") ?: ""
        val bytes = text.toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_WRITE_BYTES) {
            throw IOException("写入内容太大（>${MAX_WRITE_BYTES / 1024 / 1024}MB）")
        }
        val file = fsFile(raw)
        if (file.isDirectory) throw IOException("是个目录，不是文件：$raw")
        val parent = file.parentFile
        if (parent != null && !parent.exists()) throw IOException("上级目录不存在：${parent.path}")
        if (args.flag("append")) file.appendBytes(bytes) else file.writeBytes(bytes)
        return ok { put("size", bytes.size) }
    }

    private fun fsExists(args: JsonObject): Reply {
        val raw = args.text("path") ?: throw IllegalArgumentException("fs.exists 缺少 path")
        // 越界仍然报「没有权限」，不静默返回 false——否则插件会以为文件不存在而继续写。
        val file = fsFile(raw)
        return ok { put("exists", file.exists()) }
    }

    private fun fsList(args: JsonObject): Reply {
        val raw = args.text("path") ?: "."
        val file = fsFile(raw)
        if (!file.isDirectory) throw IOException("不是目录：$raw")
        val children = file.listFiles() ?: throw IOException("读不了目录：$raw")
        val sorted = children.sortedBy { it.name.lowercase() }
        return ok {
            put(
                "names",
                buildJsonArray { sorted.forEach { add(JsonPrimitive(it.name)) } },
            )
        }
    }

    /** 目录明细（Operit `Tools.Files.list` 的形状）：名字 / 是否目录 / 大小 / 修改时间。 */
    private fun fsDir(args: JsonObject): Reply {
        val raw = args.text("path") ?: "."
        val file = fsFile(raw)
        if (!file.isDirectory) throw IOException("不是目录：$raw")
        val children = file.listFiles() ?: throw IOException("读不了目录：$raw")
        val sorted = children.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
        val entries = buildJsonArray {
            sorted.forEach { child ->
                add(
                    buildJsonObject {
                        put("name", child.name)
                        put("isDirectory", child.isDirectory)
                        put("size", if (child.isFile) child.length() else 0L)
                        put("mtimeMs", child.lastModified())
                    },
                )
            }
        }
        return ok {
            put("path", file.path)
            put("entries", entries)
        }
    }

    /** 文件详情（Operit `Tools.Files.info` / `file_info` 的字段）。 */
    private fun fsInfo(args: JsonObject): Reply {
        val raw = args.text("path") ?: throw IllegalArgumentException("fs.info 缺少 path")
        val file = fsFile(raw)
        val exists = file.exists()
        val permissions = buildString {
            append(if (file.canRead()) "r" else "-")
            append(if (file.canWrite()) "w" else "-")
            append(if (file.canExecute()) "x" else "-")
        }
        val type = when {
            !exists -> "none"
            file.isDirectory -> "directory"
            file.isFile -> "file"
            else -> "other"
        }
        return ok {
            put("path", raw)
            put("exists", exists)
            put("isDirectory", file.isDirectory)
            put("isFile", file.isFile)
            put("fileType", type)
            put("size", if (file.isFile) file.length() else 0L)
            put("mtimeMs", file.lastModified())
            put("permissions", permissions)
        }
    }

    private fun fsStat(args: JsonObject): Reply {
        val raw = args.text("path") ?: throw IllegalArgumentException("fs.stat 缺少 path")
        val file = fsFile(raw)
        return ok {
            put("exists", file.exists())
            put("isFile", file.isFile)
            put("isDirectory", file.isDirectory)
            put("size", if (file.isFile) file.length() else 0L)
            put("mtimeMs", file.lastModified())
        }
    }

    private fun fsMkdir(args: JsonObject): Reply {
        val raw = args.text("path") ?: throw IllegalArgumentException("fs.mkdir 缺少 path")
        val file = fsFile(raw)
        val recursive = args.flag("recursive")
        val created = if (recursive) file.mkdirs() else file.mkdir()
        if (!created && !file.isDirectory) throw IOException("建不了目录：$raw")
        return ok { put("created", created) }
    }

    private fun fsRemove(args: JsonObject): Reply {
        val raw = args.text("path") ?: throw IllegalArgumentException("fs.remove 缺少 path")
        val file = fsFile(raw)
        if (!file.exists()) {
            if (args.flag("force")) return ok { put("removed", false) }
            throw IOException("路径不存在：$raw")
        }
        val removed = if (file.isDirectory) {
            if (!args.flag("recursive") && file.list()?.isNotEmpty() == true) {
                throw IOException("目录不是空的（需要 recursive）：$raw")
            }
            file.deleteRecursively()
        } else {
            file.delete()
        }
        if (!removed) throw IOException("删不掉：$raw")
        return ok { put("removed", true) }
    }

    private fun fsRename(args: JsonObject): Reply {
        val from = args.text("from") ?: throw IllegalArgumentException("fs.rename 缺少 from")
        val to = args.text("to") ?: throw IllegalArgumentException("fs.rename 缺少 to")
        val source = fsFile(from)
        val target = fsFile(to)
        if (!source.exists()) throw IOException("源路径不存在：$from")
        val parent = target.parentFile
        if (parent != null && !parent.exists()) throw IOException("目标上级目录不存在：${parent.path}")
        if (!source.renameTo(target)) throw IOException("重命名失败（跨分区？）：$from → $to")
        return ok { put("from", source.path); put("to", target.path) }
    }

    private fun fsCopy(args: JsonObject): Reply {
        val from = args.text("from") ?: throw IllegalArgumentException("fs.copy 缺少 from")
        val to = args.text("to") ?: throw IllegalArgumentException("fs.copy 缺少 to")
        val source = fsFile(from)
        val target = fsFile(to)
        val parent = target.parentFile
        if (parent != null && !parent.exists()) throw IOException("目标上级目录不存在：${parent.path}")
        if (source.isDirectory) {
            if (!args.flag("recursive")) throw IOException("源是目录（需要 recursive）：$from")
            if (target.exists() && !target.isDirectory) throw IOException("目标已存在且不是目录：$to")
            var total = 0L
            source.walkTopDown().forEach { child ->
                if (child == source) return@forEach
                val relative = child.relativeTo(source).path
                val dest = File(target, relative)
                if (child.isDirectory) {
                    dest.mkdirs()
                } else {
                    total += child.length()
                    if (total > MAX_COPY_BYTES) throw IOException("目录太大（>${MAX_COPY_BYTES / 1024 / 1024}MB）：$from")
                    dest.parentFile?.mkdirs()
                    child.copyTo(dest, overwrite = true)
                }
            }
            return ok { put("size", total); put("directory", true) }
        }
        if (!source.isFile) throw IOException("源不是文件：$from")
        if (source.length() > MAX_COPY_BYTES) {
            throw IOException("文件太大（>${MAX_COPY_BYTES / 1024 / 1024}MB）：$from")
        }
        source.copyTo(target, overwrite = true)
        return ok { put("size", target.length()) }
    }

    /** 压缩文件/目录（Operit `Tools.Files.zip`）：目录默认保留源目录名作为顶层目录。 */
    private fun fsZip(args: JsonObject): Reply {
        val sourceRaw = args.text("source") ?: throw IllegalArgumentException("fs.zip 缺少 source")
        val destRaw = args.text("destination") ?: throw IllegalArgumentException("fs.zip 缺少 destination")
        val source = fsFile(sourceRaw)
        if (!source.exists()) throw IOException("源路径不存在：$sourceRaw")
        val destination = fsFile(destRaw)
        destination.parentFile?.mkdirs()
        val includeRoot = !args.containsKey("includeRoot") || args.flag("includeRoot")
        var entries = 0
        var total = 0L
        java.util.zip.ZipOutputStream(java.io.BufferedOutputStream(destination.outputStream())).use { zip ->
            fun add(entryName: String, file: File) {
                if (entries >= MAX_ZIP_ENTRIES) throw IOException("条目太多（>$MAX_ZIP_ENTRIES）")
                if (!file.isFile) return
                total += file.length()
                if (total > MAX_ARCHIVE_BYTES) throw IOException("压缩内容太大（>${MAX_ARCHIVE_BYTES / 1024 / 1024}MB）")
                if (entryName.endsWith("/")) return
                zip.putNextEntry(java.util.zip.ZipEntry(entryName))
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
                entries += 1
            }
            if (source.isFile) {
                add(source.name, source)
            } else {
                val base = source.absoluteFile
                val rootPrefix = if (includeRoot) base.name + "/" else ""
                base.walkTopDown().forEach { child ->
                    if (child == base) return@forEach
                    val relative = child.relativeTo(base).path.replace('\\', '/')
                    if (child.isDirectory) {
                        zip.putNextEntry(java.util.zip.ZipEntry(rootPrefix + relative + "/"))
                        zip.closeEntry()
                    } else {
                        add(rootPrefix + relative, child)
                    }
                }
            }
        }
        return ok {
            put("destination", destination.path)
            put("entries", entries)
            put("size", destination.length())
        }
    }

    /** 解压 zip（Operit `Tools.Files.unzip`）：逐条校验解压路径不越界（zip-slip）。 */
    private fun fsUnzip(args: JsonObject): Reply {
        val sourceRaw = args.text("source") ?: throw IllegalArgumentException("fs.unzip 缺少 source")
        val destRaw = args.text("destination") ?: throw IllegalArgumentException("fs.unzip 缺少 destination")
        val source = fsFile(sourceRaw)
        if (!source.isFile) throw IOException("源文件不存在：$sourceRaw")
        val destination = fsFile(destRaw)
        if (!destination.mkdirs() && !destination.isDirectory) throw IOException("建不了解压目录：$destRaw")
        val destCanonical = destination.canonicalFile
        val prefix = destCanonical.path + File.separator
        var entries = 0
        var total = 0L
        java.util.zip.ZipInputStream(java.io.BufferedInputStream(source.inputStream())).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entries += 1
                if (entries > MAX_ZIP_ENTRIES) throw IOException("条目太多（>$MAX_ZIP_ENTRIES）")
                val target = File(destCanonical, entry.name).canonicalFile
                if (target.path != destCanonical.path && !target.path.startsWith(prefix)) {
                    throw IOException("压缩包里有越界路径：${entry.name}")
                }
                if (entry.isDirectory) {
                    target.mkdirs()
                } else {
                    target.parentFile?.mkdirs()
                    var written = 0L
                    target.outputStream().use { out ->
                        val buffer = ByteArray(8 * 1024)
                        while (true) {
                            val read = zip.read(buffer)
                            if (read < 0) break
                            written += read
                            total += read
                            if (total > MAX_ARCHIVE_BYTES) throw IOException("解压内容太大（>${MAX_ARCHIVE_BYTES / 1024 / 1024}MB）")
                            out.write(buffer, 0, read)
                        }
                    }
                    if (written == 0L) target.createNewFile()
                }
                zip.closeEntry()
            }
        }
        return ok {
            put("destination", destination.path)
            put("entries", entries)
        }
    }

    /** 按授权表解析/校验路径；`fs:workspace` 强制 canonical 包含检查。 */
    private fun fsFile(raw: String): File {
        if (raw.indexOf('\u0000') >= 0) throw PluginPathException("路径里有非法字符")
        return when (PluginPermissions.fsMode(grants)) {
            PluginFsMode.NONE ->
                throw PluginAccessDenied("插件没有文件权限（manifest 里要声明 fs:workspace）")
            PluginFsMode.WORKSPACE -> PluginPathGuard.resolve(workspace, raw)
            PluginFsMode.ANY -> {
                val asFile = File(raw.trim().replace('\\', '/'))
                (if (asFile.isAbsolute) asFile else File(workspace, raw)).canonicalFile
            }
        }
    }

    // ---- M83 阶段 3：开桥（APK 里已有的库 → 插件原语） ----

    /**
     * zstd 压缩/解压（`com.github.luben:zstd-jni` 已经在 APK 里，之前只给会话存储用）。
     *
     * 路径走 [fsFile]：`fs:workspace` 只能读写工作区、`fs:any` 才能碰任意路径；
     * 真正的字节处理交给 app 侧宿主桥（core 是纯 JVM 模块，不依赖 zstd 的 Android AAR）。
     * 参数：`{ mode: "compress"|"decompress", path, out?, level? }`；不传 out 时按 `path.zst` / 去 `.zst` 生成。
     */
    private fun zstd(args: JsonObject): Reply {
        val mode = (args.text("mode") ?: "compress").trim().lowercase()
        if (mode != "compress" && mode != "decompress") {
            throw IllegalArgumentException("zip.zstd 的 mode 只能是 compress / decompress")
        }
        val input = fsFile(args.text("path")?.trim().orEmpty())
        if (!input.isFile) return fail("文件不存在：${input.name}", "NOT_FOUND")
        val outRaw = args.text("out")?.trim().orEmpty()
        val output = when {
            outRaw.isNotEmpty() -> fsFile(outRaw)
            mode == "compress" -> File(input.parentFile, input.name + ".zst")
            else -> File(input.parentFile, input.name.removeSuffix(".zst") + ".out")
        }
        if (output.absolutePath == input.absolutePath) {
            throw IllegalArgumentException("输入和输出不能是同一个文件（会边读边写坏）")
        }
        val host = bridge ?: return fail("当前宿主没有实现 zip.zstd", "NOT_SUPPORTED")
        val payload = buildJsonObject {
            put("mode", mode)
            put("in", input.absolutePath)
            put("out", output.absolutePath)
            put("level", args.long("level")?.toInt() ?: 3)
        }
        val reply = host.call("zip.zstd", payload.toString())
            ?: return fail("当前宿主没有实现 zip.zstd", "NOT_SUPPORTED")
        return Reply(true, reply)
    }

    /**
     * OCR（ML Kit 中文+拉丁，已经在 APK 里：屏幕圈选 OCR 用的就是它）。
     *
     * 权限：要 `device`（危险能力，插件页逐项确认）；图片路径还要过文件权限检查
     * （`fs:workspace` 时图片要先放进工作区）。
     * 参数：`{ path }`；返回 `{ ok, text, chars }`。
     */
    private fun ocr(args: JsonObject): Reply {
        if (PluginPermissions.DEVICE !in grants) {
            throw PluginAccessDenied("插件没有设备权限（OCR 需要 manifest 声明 device 并在插件页确认）")
        }
        val path = args.text("path")?.trim().orEmpty()
        if (path.isEmpty()) throw IllegalArgumentException("ocr.recognize 缺少 path")
        val file = fsFile(path)
        if (!file.isFile) return fail("图片不存在：${file.name}", "NOT_FOUND")
        val host = bridge ?: return fail("当前宿主没有实现 ocr.recognize", "NOT_SUPPORTED")
        val reply = host.call(
            "ocr.recognize",
            buildJsonObject { put("path", file.absolutePath) }.toString(),
        ) ?: return fail("当前宿主没有实现 ocr.recognize", "NOT_SUPPORTED")
        return Reply(true, reply)
    }

    // ---- 网络 ----

    private fun netFetch(args: JsonObject): Reply {
        val initial = args.text("url")?.trim().orEmpty()
        if (initial.isEmpty()) throw IllegalArgumentException("net.fetch 缺少 url")
        val method = (args.text("method") ?: "GET").trim().uppercase()
        if (method !in METHODS) throw IllegalArgumentException("不支持的 HTTP 方法：$method")
        val body = args.text("body").takeUnless { method == "GET" || method == "HEAD" }
        val headers = args["headers"] as? JsonObject
        var current = initial
        var redirects = 0

        while (true) {
            val uri = parseAllowedUri(current)
            val connection = (uri.toURL().openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                requestMethod = method
                setRequestProperty("User-Agent", "DSH-Android-Plugin/0.1")
                setRequestProperty("Accept", "*/*")
                setRequestProperty("Accept-Encoding", "identity")
                headers?.forEach { (name, value) ->
                    val key = name.trim()
                    if (key.isNotEmpty() && key.lowercase() !in FORBIDDEN_HEADERS) {
                        runCatching { setRequestProperty(key, value.jsonPrimitive.content.take(1000)) }
                    }
                }
                if (body != null) {
                    doOutput = true
                    outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                }
            }

            val status = try {
                connection.responseCode
            } catch (e: Exception) {
                runCatching { connection.disconnect() }
                throw IOException("请求失败：${e.message ?: e.javaClass.simpleName}")
            }

            if (status in 301..308) {
                val location = connection.getHeaderField("Location")
                runCatching { connection.disconnect() }
                if (location.isNullOrBlank()) throw IOException("重定向缺少 Location")
                redirects += 1
                if (redirects > MAX_REDIRECTS) throw IOException("重定向次数太多")
                current = runCatching { URI(current).resolve(location).toString() }
                    .getOrElse { throw IOException("重定向地址不合法：$location") }
                continue
            }

            val binary = args.flag("binary")
            val text = if (binary) "" else readHttpBody(connection, status)
            val bytes = if (binary) readHttpBytes(connection, status) else ByteArray(0)
            val headersJson = buildJsonObject {
                runCatching {
                    connection.headerFields.forEach { (name, values) ->
                        if (!name.isNullOrBlank() && values.isNotEmpty()) {
                            put(name, values.joinToString(", ").take(2000))
                        }
                    }
                }
            }
            runCatching { connection.disconnect() }
            return ok {
                put("status", status)
                put("url", current)
                put("headers", headersJson)
                put("body", text)
                if (binary) {
                    put("base64", java.util.Base64.getEncoder().encodeToString(bytes))
                    put("size", bytes.size)
                }
            }
        }
    }

    private fun parseAllowedUri(url: String): URI {
        val uri = runCatching { URI(url) }.getOrElse { throw IllegalArgumentException("URL 不合法：$url") }
        if (!uri.isAbsolute || uri.scheme?.lowercase() != "https") {
            throw PluginAccessDenied("插件网络只允许 https：$url")
        }
        if (!uri.userInfo.isNullOrBlank()) throw PluginAccessDenied("URL 里不允许带用户名密码")
        val host = uri.host?.lowercase()?.trimEnd('.') ?: throw IllegalArgumentException("URL 没有主机名：$url")
        if (PluginPermissions.isLoopbackOrLocal(host)) {
            throw PluginAccessDenied("插件不能访问本机/链路本地地址：$host")
        }
        if (!PluginPermissions.netAllows(grants, host)) {
            throw PluginAccessDenied("插件没有访问 $host 的权限（manifest 里声明 net:$host）")
        }
        return uri
    }

    private fun readHttpBody(connection: HttpURLConnection, status: Int): String {
        val stream = (if (status >= 400) connection.errorStream else connection.inputStream) ?: return ""
        stream.use { return readCapped(it, MAX_HTTP_BODY).toString(Charsets.UTF_8) }
    }

    private fun readHttpBytes(connection: HttpURLConnection, status: Int): ByteArray {
        val stream = (if (status >= 400) connection.errorStream else connection.inputStream) ?: return ByteArray(0)
        stream.use { return readCapped(it, MAX_HTTP_BODY) }
    }

    /**
     * multipart/form-data 上传（Operit `Tools.Net.uploadFile`）。
     * Operit 包用它把本地图片传到图床；字段名、文件字段名、Content-Type 都由包指定。
     */
    private fun netUpload(args: JsonObject): Reply {
        val url = args.text("url")?.trim().orEmpty()
        if (url.isEmpty()) throw IllegalArgumentException("net.upload 缺少 url")
        val uri = parseAllowedUri(url)
        val method = (args.text("method") ?: "POST").trim().uppercase()
        if (method != "POST" && method != "PUT") throw IllegalArgumentException("net.upload 只支持 POST/PUT")
        val fields = args["fields"] as? JsonObject ?: JsonObject(emptyMap())
        val files = args["files"] as? kotlinx.serialization.json.JsonArray ?: kotlinx.serialization.json.JsonArray(emptyList())
        val headers = args["headers"] as? JsonObject
        val boundary = "----DshPluginBoundary" + java.util.UUID.randomUUID().toString().replace("-", "")
        val body = ByteArrayOutputStream()

        fun writeText(text: String) = body.write(text.toByteArray(Charsets.UTF_8))
        fun writeCrlf() = writeText("\r\n")

        fields.forEach { (name, value) ->
            writeText("--$boundary")
            writeCrlf()
            writeText("Content-Disposition: form-data; name=\"${name.replace("\"", "")}\"")
            writeCrlf()
            writeCrlf()
            writeText((value as? JsonPrimitive)?.content ?: value.toString())
            writeCrlf()
        }
        files.forEach { element ->
            val file = element as? JsonObject ?: return@forEach
            val field = file.text("field") ?: file.text("field_name") ?: "file"
            val path = file.text("path") ?: file.text("file_path") ?: throw IllegalArgumentException("net.upload 的文件缺少 path")
            val contentType = file.text("contentType") ?: file.text("content_type") ?: "application/octet-stream"
            val local = fsFile(path)
            if (!local.isFile) throw IOException("上传的文件不存在：$path")
            if (body.size().toLong() + local.length() > MAX_UPLOAD_BYTES) {
                throw IOException("上传内容太大（>${MAX_UPLOAD_BYTES / 1024 / 1024}MB）")
            }
            val name = file.text("name") ?: local.name
            writeText("--$boundary")
            writeCrlf()
            writeText(
                "Content-Disposition: form-data; name=\"${field.replace("\"", "")}\"; " +
                    "filename=\"${name.replace("\"", "")}\"",
            )
            writeCrlf()
            writeText("Content-Type: $contentType")
            writeCrlf()
            writeCrlf()
            local.inputStream().use { it.copyTo(body) }
            writeCrlf()
        }
        writeText("--$boundary--")
        writeCrlf()

        val connection = (uri.toURL().openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = false
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            requestMethod = method
            doOutput = true
            setRequestProperty("User-Agent", "DSH-Android-Plugin/0.1")
            setRequestProperty("Accept", "*/*")
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            headers?.forEach { (name, value) ->
                val key = name.trim()
                if (key.isNotEmpty() && key.lowercase() !in FORBIDDEN_HEADERS) {
                    runCatching { setRequestProperty(key, value.jsonPrimitive.content.take(1000)) }
                }
            }
        }
        val status = try {
            connection.outputStream.use { it.write(body.toByteArray()) }
            connection.responseCode
        } catch (e: Exception) {
            runCatching { connection.disconnect() }
            throw IOException("上传失败：${e.message ?: e.javaClass.simpleName}")
        }
        val text = readHttpBody(connection, status)
        runCatching { connection.disconnect() }
        return ok {
            put("status", status)
            put("url", url)
            put("body", text)
        }
    }

    /**
     * M85-A：整壳命令。只有拿到 `ui` 授权的插件才能把自己的界面接到宿主行为上；
     * 具体命令白名单在 app 侧 `AppViewModel.shellCommand`，这里只做权限门与转发。
     */
    private fun surfaceCommand(args: JsonObject): Reply {
        if (PluginPermissions.UI !in grants) {
            throw PluginAccessDenied("插件没有界面权限（manifest 声明 ui 并在插件页确认后才能提供整壳）")
        }
        val command = args.text("command")?.trim().orEmpty()
        if (command.isEmpty()) throw IllegalArgumentException("surface.command 缺少 command")
        val host = bridge ?: return fail("当前宿主没有实现 surface.command", "NOT_SUPPORTED")
        val reply = host.call("surface.command", args.toString())
            ?: return fail("当前宿主没有实现 surface.command", "NOT_SUPPORTED")
        return Reply(true, reply)
    }

    /**
     * 转发给 app 侧宿主桥（M67）：设备信息 / 应用列表 / 无障碍手势 / 截图 / 记忆库。
     *
     * 权限：`device.*`、`ui.*`、`app.*` 需要 `device` 授权（危险能力，用户逐项确认）；
     * `file.open` / `file.share` 跟文件权限走。
     */
    private fun hostCall(args: JsonObject): Reply {
        val method = args.text("method")?.trim().orEmpty()
        if (method.isEmpty()) throw IllegalArgumentException("host.call 缺少 method")
        val callArgs = args["args"] as? JsonObject ?: JsonObject(emptyMap())
        val needsDevice = method.startsWith("device.") || method.startsWith("ui.") || method.startsWith("app.")
        if (needsDevice && PluginPermissions.DEVICE !in grants) {
            throw PluginAccessDenied("插件没有设备权限（manifest 里声明 device，并在插件页确认）")
        }
        if (method.startsWith("memory.") && PluginPermissions.MEMORY !in grants) {
            throw PluginAccessDenied("插件没有记忆库权限（manifest 里声明 memory，并在插件页确认）")
        }
        // 终端会话 = 以 App 身份跑任意命令（还能跨调用保持状态），和 shell 是同一档危险能力。
        if (method.startsWith("terminal.") && !PluginPermissions.shellGranted(grants)) {
            throw PluginAccessDenied("插件没有 shell 权限（真 PTY 终端会话需要 manifest 声明 shell 并在插件页确认）")
        }
        if ((method == "file.open" || method == "file.share") && PluginPermissions.fsMode(grants) == PluginFsMode.NONE) {
            throw PluginAccessDenied("插件没有文件权限（manifest 里要声明 fs:workspace / fs:any）")
        }
        val host = bridge ?: return fail("当前宿主没有实现 $method", "NOT_SUPPORTED")
        val reply = host.call(method, callArgs.toString())
            ?: return fail("当前宿主没有实现 $method", "NOT_SUPPORTED")
        return Reply(true, reply)
    }

    // ---- visit 缓存（M68） ----

    /**
     * `visit({visit_key, link_number})` 的宿主侧支持：把「这一次 visit 的链接表」存进进程级缓存。
     *
     * 纯内存、不碰文件/网络，所以不额外要权限；key 按插件隔离（见 [PluginVisitCache]）。
     */
    private fun visitPut(args: JsonObject): Reply {
        val key = args.text("key")?.trim().orEmpty()
        if (key.isEmpty()) throw IllegalArgumentException("net.visit.put 缺少 key")
        val rawLinks = args["links"] as? kotlinx.serialization.json.JsonArray
        val links = rawLinks?.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val url = obj.text("url")?.trim().orEmpty()
            if (url.isEmpty()) null else PluginVisitCache.Link(url, obj.text("text").orEmpty())
        }.orEmpty()
        val rawImages = args["imageLinks"] as? kotlinx.serialization.json.JsonArray
        val images = rawImages?.mapNotNull { element ->
            (element as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
        }.orEmpty()
        PluginVisitCache.put(
            pluginId,
            key,
            PluginVisitCache.Entry(
                url = args.text("url").orEmpty(),
                title = args.text("title").orEmpty(),
                links = links,
                imageLinks = images,
            ),
        )
        return ok {
            put("key", key)
            put("links", links.size)
            put("cached", PluginVisitCache.size())
        }
    }

    /** 取回某次 visit 的链接表；没有这条 key 时给 `VISIT_KEY_NOT_FOUND`（包按 Operit 原文报 `Invalid visit key.`）。 */
    private fun visitGet(args: JsonObject): Reply {
        val key = args.text("key")?.trim().orEmpty()
        if (key.isEmpty()) return fail("Invalid visit key.", "VISIT_KEY_NOT_FOUND")
        val entry = PluginVisitCache.get(pluginId, key)
            ?: return fail("Invalid visit key.", "VISIT_KEY_NOT_FOUND")
        return ok {
            put("url", entry.url)
            put("title", entry.title)
            put(
                "links",
                buildJsonArray {
                    entry.links.forEach { link ->
                        add(buildJsonObject { put("url", link.url); put("text", link.text) })
                    }
                },
            )
            put(
                "imageLinks",
                buildJsonArray { entry.imageLinks.forEach { add(JsonPrimitive(it)) } },
            )
        }
    }

    // ---- 计时器 ----
    /**
     * 全局 setTimeout/setInterval 的宿主实现（M63）。异步调用在宿主线程池里跑，
     * 到点后由 QuickJS 桥回灌 Promise；上限 10 分钟，避免插件把线程挂死。
     */
    private fun timerWait(args: JsonObject): Reply {
        val ms = (args.long("ms") ?: 0L).coerceIn(0L, MAX_TIMER_MS)
        if (ms > 0) {
            try {
                Thread.sleep(ms)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                throw IOException("计时器被中断")
            }
        }
        return ok { put("waited", ms) }
    }

    // ---- shell ----

    private fun shellExec(args: JsonObject): Reply {
        if (!PluginPermissions.shellGranted(grants)) {
            throw PluginAccessDenied("插件没有 shell 权限（要在插件页逐项确认）")
        }
        val command = args.text("command")?.trim().orEmpty()
        if (command.isEmpty()) throw IllegalArgumentException("shell.exec 缺少 command")
        if (command.length > MAX_COMMAND_CHARS) throw IllegalArgumentException("命令太长（>${MAX_COMMAND_CHARS} 字符）")
        val timeoutMs = (args.long("timeoutMs") ?: SHELL_TIMEOUT_MS).coerceIn(1_000, 60_000)

        val process = ProcessBuilder("/system/bin/sh", "-c", command)
            .directory(workspace)
            .redirectErrorStream(false)
            .start()
        runCatching { process.outputStream.close() }

        val stdout = AtomicReference("")
        val stderr = AtomicReference("")
        val outThread = Thread { stdout.set(readCappedQuietly(process.inputStream, MAX_SHELL_OUTPUT)) }
        val errThread = Thread { stderr.set(readCappedQuietly(process.errorStream, MAX_SHELL_OUTPUT)) }
        outThread.isDaemon = true
        errThread.isDaemon = true
        outThread.start()
        errThread.start()

        val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        if (!finished) {
            process.destroyForcibly()
            process.waitFor(2, TimeUnit.SECONDS)
        }
        outThread.join(1_000)
        errThread.join(1_000)
        val status = if (finished) runCatching { process.exitValue() }.getOrDefault(-1) else -1
        return ok {
            put("status", status)
            put("stdout", stdout.get())
            put("stderr", stderr.get())
            put("timedOut", !finished)
        }
    }

    // ---- JSON / 读取工具 ----

    private fun parseArgs(payloadJson: String): JsonObject? = runCatching {
        if (payloadJson.isBlank()) JsonObject(emptyMap()) else Json.parseToJsonElement(payloadJson) as JsonObject
    }.getOrNull()

    private fun ok(build: JsonObjectBuilder.() -> Unit): Reply {
        val body = buildJsonObject(build)
        val payload = buildJsonObject {
            put("ok", true)
            body.forEach { (key, value) -> put(key, value) }
        }
        return Reply(true, payload.toString())
    }

    private fun fail(message: String?, code: String): Reply {
        val text = message?.takeIf { it.isNotBlank() } ?: "插件宿主调用失败"
        val payload = buildJsonObject {
            put("ok", false)
            put("error", text)
            put("code", code)
        }
        return Reply(false, payload.toString(), text, code)
    }

    private fun readCapped(stream: InputStream, cap: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        var total = 0
        while (true) {
            val read = stream.read(buffer)
            if (read < 0) break
            total += read
            if (total > cap) throw IOException("内容太大（>${cap / 1024}KB）")
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    private fun readCappedQuietly(stream: InputStream, cap: Int): String {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        try {
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                val remaining = cap - out.size()
                if (remaining <= 0) continue
                out.write(buffer, 0, minOf(read, remaining))
            }
        } catch (_: IOException) {
            // 进程被强杀时管道会断，已经读到的内容仍然有用。
        }
        return out.toByteArray().toString(Charsets.UTF_8)
    }

    private fun JsonObject.text(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.flag(key: String): Boolean =
        (this[key] as? JsonPrimitive)?.content?.equals("true", ignoreCase = true) == true

    private fun JsonObject.long(key: String): Long? =
        (this[key] as? JsonPrimitive)?.content?.toLongOrNull()

    private companion object {
        const val MAX_READ_BYTES = 4L * 1024 * 1024
        const val MAX_WRITE_BYTES = 4 * 1024 * 1024
        const val MAX_COPY_BYTES = 32L * 1024 * 1024
        const val MAX_BINARY_BYTES = 8L * 1024 * 1024
        const val MAX_ARCHIVE_BYTES = 128L * 1024 * 1024
        const val MAX_ZIP_ENTRIES = 10_000
        const val MAX_UPLOAD_BYTES = 32L * 1024 * 1024
        const val MAX_HTTP_BODY = 2 * 1024 * 1024
        const val MAX_SHELL_OUTPUT = 64 * 1024
        const val MAX_COMMAND_CHARS = 8_000
        const val SHELL_TIMEOUT_MS = 15_000L
        const val MAX_TIMER_MS = 600_000L
        const val CONNECT_TIMEOUT_MS = 10_000
        const val READ_TIMEOUT_MS = 20_000
        const val MAX_REDIRECTS = 3
        val METHODS = setOf("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD")
        val FORBIDDEN_HEADERS = setOf(
            "host", "content-length", "connection", "transfer-encoding", "upgrade", "keep-alive",
        )
    }
}

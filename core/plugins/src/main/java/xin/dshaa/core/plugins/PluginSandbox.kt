package xin.dshaa.core.plugins

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
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
                "fs.write" -> fsWrite(args)
                "fs.exists" -> fsExists(args)
                "fs.list" -> fsList(args)
                "fs.stat" -> fsStat(args)
                "fs.mkdir" -> fsMkdir(args)
                "fs.remove" -> fsRemove(args)
                "fs.rename" -> fsRename(args)
                "fs.copy" -> fsCopy(args)
                "net.fetch" -> netFetch(args)
                "shell.exec" -> shellExec(args)
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
        if (!source.isFile) throw IOException("源不是文件：$from")
        val parent = target.parentFile
        if (parent != null && !parent.exists()) throw IOException("目标上级目录不存在：${parent.path}")
        if (source.length() > MAX_COPY_BYTES) {
            throw IOException("文件太大（>${MAX_COPY_BYTES / 1024 / 1024}MB）：$from")
        }
        source.copyTo(target, overwrite = true)
        return ok { put("size", target.length()) }
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

            val text = readHttpBody(connection, status)
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
        const val MAX_HTTP_BODY = 2 * 1024 * 1024
        const val MAX_SHELL_OUTPUT = 64 * 1024
        const val MAX_COMMAND_CHARS = 8_000
        const val SHELL_TIMEOUT_MS = 15_000L
        const val CONNECT_TIMEOUT_MS = 10_000
        const val READ_TIMEOUT_MS = 20_000
        const val MAX_REDIRECTS = 3
        val METHODS = setOf("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD")
        val FORBIDDEN_HEADERS = setOf(
            "host", "content-length", "connection", "transfer-encoding", "upgrade", "keep-alive",
        )
    }
}

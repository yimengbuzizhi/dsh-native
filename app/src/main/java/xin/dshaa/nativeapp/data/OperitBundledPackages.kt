package xin.dshaa.nativeapp.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import xin.dshaa.core.plugins.ToolPkg
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import xin.dshaa.ui.shellapi.data.OperitBundledPackage
import xin.dshaa.ui.shellapi.data.OperitStatus

/**
 * Operit 自带包目录（M67，用户点名「包管理搬 Operit 自带包」）。
 *
 * 做法：把 Operit `app/src/main/assets/packages/` 下的 .js 文件原样放进 App assets（附 LGPL-3.0 LICENSE），
 * 运行时用 [ToolPkg.metadata] 解析 METADATA，得到名称/描述/工具数；安装时现拼一个
 * `package.json + <包>.js` 的 zip 交给 [PluginStore]（走和导入 zip 完全相同的链路）。
 *
 * 权限不照抄 Operit（它没有清单权限）：按源码里实际用到的 `Tools.*` 推断，
 * `net:any` / `shell` / `device` 仍然要用户在插件页逐项确认。
 *
 * 兼容状态是**静态扫描**：命中已知不支持的调用面就标「部分可用」，`METADATA.env`
 * 有必填项且本版没有配置页就标「需要 API Key」。不是「跑过了」的意思，真机结论看 docs/94。
 */
class OperitBundledPackages(private val context: Context) {

    /** 扫描 assets；返回按分类、名字排序的目录。读 assets 是 IO，调用方放到 IO 线程。 */
    fun load(): List<OperitBundledPackage> {
        val names = runCatching { context.assets.list(DIR)?.toList() }.getOrNull().orEmpty()
            .filter { it.endsWith(".js") }
            .sorted()
        val out = ArrayList<OperitBundledPackage>(names.size)
        names.forEach { name ->
            val source = runCatching {
                context.assets.open("$DIR/$name").use { it.readBytes().toString(Charsets.UTF_8) }
            }.getOrNull() ?: return@forEach
            val meta = runCatching { ToolPkg.metadata(source) }.getOrNull()
            val base = name.removeSuffix(".js")
            // env 有两种写法（纯字符串 / 对象），统一由 ToolPkg.envSpecs 归一（M68）。
            val envSpecs = ToolPkg.envSpecs(meta)
            val envNames = envSpecs.mapNotNull { spec ->
                ((spec as? JsonObject)?.get("name") as? JsonPrimitive)?.content
            }
            val requiredEnv = envSpecs.mapNotNull { spec ->
                val obj = spec as? JsonObject ?: return@mapNotNull null
                val required = (obj["required"] as? JsonPrimitive)?.content == "true"
                if (required) (obj["name"] as? JsonPrimitive)?.content else null
            }
            val unsupported = UNSUPPORTED.filter { (needle, _) -> source.contains(needle) }
                .map { it.second }
                .distinct()
            val status = when {
                // METADATA 声明了 env / 包体用 getEnv(...)：M68 起可在「插件详情 → 配置」里填，
                // 但仍要先填才能跑，所以照实标「需要 API Key」，不写成「可用」。
                envNames.isNotEmpty() || source.contains("getEnv(") -> OperitStatus.NEEDS_ENV
                unsupported.isNotEmpty() -> OperitStatus.PARTIAL
                else -> OperitStatus.READY
            }
            val notes = buildList {
                if (envNames.isNotEmpty()) {
                    add("需要填：${envNames.joinToString("、")}（装好后在插件详情 → 配置里填）")
                } else if (source.contains("getEnv(")) {
                    add("包体用 getEnv(...) 读 API Key（在插件详情 → 配置里填）")
                }
                if (requiredEnv.isNotEmpty()) {
                    add("其中必填：${requiredEnv.joinToString("、")}（没填时工具会直接报错）")
                }
                if (unsupported.isNotEmpty()) {
                    add("依赖暂不支持的能力：${unsupported.joinToString("、")}")
                }
            }
            out += OperitBundledPackage(
                assetName = name,
                pluginId = "operit-" + base.replace('_', '-'),
                title = text(meta?.get("display_name")) ?: text(meta?.get("name")) ?: base,
                description = text(meta?.get("description")) ?: "Operit 自带工具包",
                category = text(meta?.get("category")) ?: "ToolPkg",
                tools = ((meta?.get("tools") as? JsonArray) ?: JsonArray(emptyList())).mapNotNull { tool ->
                    ((tool as? JsonObject)?.get("name") as? JsonPrimitive)?.content
                },
                permissions = inferPermissions(source),
                status = status,
                notes = notes,
            )
        }
        return out.sortedWith(compareBy({ it.category }, { it.title }))
    }

    /** 现拼 zip 后交给 [store]（与「导入插件 zip」同一条链路）。 */
    suspend fun install(store: PluginStore, pkg: OperitBundledPackage): PluginStore.Result =
        withContext(Dispatchers.IO) {
            val bytes = runCatching {
                context.assets.open("$DIR/${pkg.assetName}").use { it.readBytes() }
            }.getOrElse { return@withContext PluginStore.Result(false, "读不到内置包：${pkg.assetName}") }
            val manifest = buildJsonObject {
                put("name", pkg.pluginId)
                put("version", "1.0.0")
                put("description", pkg.title + "（Operit 自带包）")
                put("main", pkg.assetName)
                put(
                    "permissions",
                    buildJsonArray { pkg.permissions.forEach { add(JsonPrimitive(it)) } },
                )
            }
            val zip = File(context.cacheDir, "operit-bundled-${pkg.pluginId}.zip")
            runCatching {
                ZipOutputStream(zip.outputStream()).use { out ->
                    out.putNextEntry(ZipEntry("package.json"))
                    out.write(manifest.toString().toByteArray(Charsets.UTF_8))
                    out.closeEntry()
                    out.putNextEntry(ZipEntry(pkg.assetName))
                    out.write(bytes)
                    out.closeEntry()
                }
            }.onFailure {
                Log.w(TAG, "打包 ${pkg.pluginId} 失败", it)
                return@withContext PluginStore.Result(false, "打包失败：${it.message}")
            }
            try {
                store.installFile(zip)
            } finally {
                zip.delete()
            }
        }

    /** 按源码里实际出现的调用面推断权限（宁多勿漏；危险项仍要用户确认）。 */
    private fun inferPermissions(source: String): List<String> {
        val out = LinkedHashSet<String>()
        if (source.contains("Tools.Files.")) out += "fs:any"
        if (source.contains("OkHttp.") || source.contains("fetch(") ||
            source.contains("Tools.Net.") || source.contains("https://")
        ) {
            out += "net:any"
        }
        if (source.contains("Tools.System.shell") || source.contains("Tools.System.terminal") ||
            source.contains("tools.System.shell")
        ) {
            out += "shell"
        }
        if (source.contains("Tools.UI.") || source.contains("Tools.System.getDeviceInfo") ||
            source.contains("Tools.System.listApps") || source.contains("Tools.System.startApp") ||
            source.contains("Tools.System.stopApp") || source.contains("Tools.System.getSetting") ||
            source.contains("Tools.System.setSetting") || source.contains("Tools.System.sendBroadcast") ||
            source.contains("Tools.System.toast")
        ) {
            out += "device"
        }
        if (source.contains("Tools.Memory.")) out += "memory"
        return out.toList()
    }

    /** METADATA 里的 zh/en 对象或纯字符串。 */
    private fun text(element: JsonElement?): String? = when (element) {
        is JsonPrimitive -> element.content.takeIf { it.isNotBlank() && it != "null" }
        is JsonObject -> {
            val zh = (element["zh"] as? JsonPrimitive)?.content
            val en = (element["en"] as? JsonPrimitive)?.content
            zh?.takeIf { it.isNotBlank() } ?: en?.takeIf { it.isNotBlank() }
        }
        else -> null
    }

    private companion object {
        const val DIR = "operit-packages"
        const val TAG = "DshPlugin"

        /**
         * 已知「鲸歌这版做不到」的调用面。命中就标「部分可用」并写明原因，
         * 不假装整个包都能跑（包里的其它工具往往仍然可用）。
         */
        val UNSUPPORTED = listOf(
            "Tools.Chat." to "Operit 聊天数据库",
            "Tools.Workflow." to "工作流",
            "Tools.SoftwareSettings." to "Operit 软件设置",
            "Tools.FFmpeg." to "随包 ffmpeg",
            "Tools.Net.browser" to "浏览器自动化",
            "Tools.Net.cookies" to "Cookie 管理",
            "Tools.Net.http" to "Operit 原生 HTTP 工具",
            "Tools.System.bluetooth" to "蓝牙",
            "Tools.System.getLocation" to "定位",
            "Tools.System.getNotifications" to "通知读取",
            "Tools.System.getAppUsageTime" to "应用使用时长",
            "Tools.System.installApp" to "静默安装",
            "Tools.System.uninstallApp" to "静默卸载",
            "Tools.System.intent" to "任意 Intent",
            "Tools.System.usePackage" to "Operit 子包机制",
            "Tools.UI.runSubAgent" to "子 Agent",
            "readToolPkgResource" to "包内资源读取",
            "manage_cookies" to "Cookie 管理",
        )
    }
}

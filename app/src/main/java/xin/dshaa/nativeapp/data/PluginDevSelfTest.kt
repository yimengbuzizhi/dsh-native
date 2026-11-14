package xin.dshaa.nativeapp.data

import kotlinx.coroutines.delay
import kotlinx.serialization.json.*
import xin.dshaa.core.tools.ToolContext
import xin.dshaa.core.tools.ToolResult
import java.io.File

/** Debug-only caller supplies the real registered tools; fixtures live in a fresh workspace directory. */
internal object PluginDevSelfTest {
    suspend fun run(
        directory: File,
        context: ToolContext,
        invoke: suspend (String, JsonObject) -> ToolResult,
        writeReport: (String) -> Unit,
    ) {
        val results = mutableListOf<JsonObject>()
        val installed = mutableListOf<String>()
        suspend fun call(name: String, args: JsonObject): ToolResult = invoke(name, args)
        fun args(vararg fields: Pair<String, String>) = buildJsonObject { fields.forEach { put(it.first, it.second) } }
        fun record(name: String, ok: Boolean, detail: String = "") {
            results += buildJsonObject { put("name", name); put("ok", ok); put("detail", detail.take(8000)) }
            writeReport(buildJsonObject { put("running", true); put("checks", JsonArray(results)) }.toString())
        }
        val suffix = System.currentTimeMillis().toString()
        try {
            check(!directory.exists() && directory.parentFile?.isDirectory == true && directory.mkdir())
            for (template in listOf("tool", "surface-shell", "webview-shell")) {
                val id = "m86.selftest.$template.$suffix"
                val path = File(directory, template)
                val scaffold = call("plugin_dev_scaffold", args("path" to path.path, "id" to id, "template" to template))
                record("$template scaffold", !scaffold.isError, scaffold.text)
                val test = call("plugin_dev_test", args("path" to path.path))
                record("$template apply", !test.isError, test.text)
                val install = call("plugin_dev_install", args("path" to path.path))
                if (!install.isError) installed += id
                record("$template install", !install.isError, install.text)
                val listed = call("plugin_dev_list", args("id" to id))
                record("$template list", !listed.isError && listed.text.contains(id), listed.text)
                if (template == "tool") {
                    val tool = id.replace(Regex("[^A-Za-z0-9_]"), "_") + "_hello"
                    val executed = call("plugin_dev_call", buildJsonObject {
                        put("id", id); put("tool", tool); put("args", buildJsonObject { put("name", "M86") })
                    })
                    record("tool execute", !executed.isError && executed.text.contains("M86"), executed.text)
                    val disable = call("plugin_dev_enable", buildJsonObject { put("id", id); put("enabled", false) })
                    val blocked = call("plugin_dev_call", buildJsonObject { put("id", id); put("tool", tool) })
                    record("tool disabled blocks", !disable.isError && blocked.isError, blocked.text)
                    call("plugin_dev_enable", buildJsonObject { put("id", id); put("enabled", true) })
                    val reload = call("plugin_dev_reload", args("id" to id))
                    record("tool reload", !reload.isError, reload.text)
                    // Malformed upgrade must leave the installed module executable.
                    File(path, "index.js").writeText("export function apply(ctx) { throw new Error('M86_EXPECTED_BAD_UPGRADE'); }", Charsets.UTF_8)
                    val bad = call("plugin_dev_install", args("path" to path.path))
                    val after = call("plugin_dev_call", buildJsonObject { put("id", id); put("tool", tool); put("args", buildJsonObject { put("name", "ROLLBACK") }) })
                    record("failed upgrade preserves old tool", bad.isError && !after.isError && after.text.contains("ROLLBACK"), bad.text + "\n" + after.text)
                } else {
                    val probe = call("plugin_dev_test_shell", args("id" to id, "action" to "close"))
                    val report = runCatching { Json.parseToJsonElement(probe.text).jsonObject }.getOrNull()
                    val commands = report?.get("commands") as? JsonArray
                    record("$template offscreen mount/state/action", !probe.isError && report?.get("commandsExecuted")?.jsonPrimitive?.intOrNull == 0 &&
                        commands?.any { it.jsonObject["command"]?.jsonPrimitive?.contentOrNull == "closeShell" } == true, probe.text)
                    if (template == "surface-shell") record("surface button has label", probe.text.contains("返回原生界面"), probe.text)
                }
            }
            val capId = "m86.selftest.capability.$suffix"
            val capPath = File(directory, "capability").apply { mkdir() }
            File(capPath, "package.json").writeText(buildJsonObject {
                put("name", capId); put("version", "0.1.0"); put("main", "index.js")
                put("permissions", buildJsonArray { add("capability") })
            }.toString(), Charsets.UTF_8)
            File(capPath, "index.js").writeText("""
                export function apply(ctx) {
                  ctx.capability.register({id:'$capId',label:'M86 本地回声',invoke:function(args){return {echo:args.text};}});
                }
            """.trimIndent(), Charsets.UTF_8)
            val capInstall = call("plugin_dev_install", args("path" to capPath.path))
            if (!capInstall.isError) installed += capId
            delay(1200)
            val capResult = call("plugin_dev_test_capability", buildJsonObject {
                put("capability", capId); put("args", buildJsonObject { put("text", "M86_CAP_OK") })
            })
            record("capability actual invoke", !capResult.isError && capResult.text.contains("M86_CAP_OK"), capResult.text)
            call("plugin_dev_enable", buildJsonObject { put("id", capId); put("enabled", false) })
            val missing = call("plugin_dev_test_capability", args("capability" to capId))
            record("capability disabled refuses", missing.isError, missing.text)

            val deniedId = "m86.selftest.denied.$suffix"
            val deniedPath = File(directory, "denied").apply { mkdir() }
            File(deniedPath, "package.json").writeText(buildJsonObject {
                put("name", deniedId); put("version", "0.1.0"); put("main", "index.js")
                put("permissions", buildJsonArray { add("shell") })
            }.toString(), Charsets.UTF_8)
            File(deniedPath, "index.js").writeText("""
                export function apply(ctx) {
                  ctx.tools.register({name:'m86_permission_$suffix',description:'权限拒绝探针',parameters:{type:'object',properties:{}},
                    execute:function(){return ctx.shell.exec('echo SHOULD_NOT_RUN');}});
                }
            """.trimIndent(), Charsets.UTF_8)
            val deniedInstall = call("plugin_dev_install", args("path" to deniedPath.path))
            if (!deniedInstall.isError) installed += deniedId
            val denied = call("plugin_dev_call", args("id" to deniedId, "tool" to "m86_permission_$suffix"))
            record("dangerous shell permission not auto granted", denied.isError && (denied.text.contains("PERMISSION") || denied.text.contains("权限") || denied.text.contains("授权")), denied.text)
        } catch (e: Exception) {
            record("selftest exception", false, e.message.orEmpty())
        } finally {
            installed.forEach { id -> call("plugin_dev_enable", buildJsonObject { put("id", id); put("enabled", false) }) }
            writeReport(buildJsonObject {
                put("running", false); put("directory", directory.path); put("checks", JsonArray(results))
                put("passed", results.count { it["ok"]?.jsonPrimitive?.booleanOrNull == true }); put("total", results.size)
                put("fixturesRetained", true); put("allCreatedPluginsDisabled", true)
            }.toString())
        }
    }
}

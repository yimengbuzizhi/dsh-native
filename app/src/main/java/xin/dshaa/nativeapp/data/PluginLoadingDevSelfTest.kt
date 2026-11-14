package xin.dshaa.nativeapp.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import xin.dshaa.core.plugins.LoadingSpec
import xin.dshaa.core.tools.ToolResult
import java.io.File
import java.util.UUID

/**
 * Device-only real QuickJS registration/install test. The caller supplies the registered dev tools.
 * Uses fresh fixture files and a separate snapshot store; never changes the user's loading selection.
 * Fixtures are retained and the created plugin is disabled on exit, including failed runs.
 */
internal object PluginLoadingDevSelfTest {
    suspend fun run(
        directory: File,
        store: PluginStore,
        invoke: suspend (String, JsonObject) -> ToolResult,
        writeReport: (String) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val results = mutableListOf<JsonObject>()
        val id = "m86.selftest.loading.${UUID.randomUUID()}"
        val selection = "$id#startup"
        val fixture = File(directory, "loading")
        var installed = false
        var disabled = false
        var created = false
        var snapshotRevision: String? = null
        fun args(vararg fields: Pair<String, String>) = buildJsonObject {
            fields.forEach { (key, value) -> put(key, value) }
        }
        fun ToolResult.json(): JsonObject = Json.parseToJsonElement(text).jsonObject
        fun record(name: String, ok: Boolean, detail: String = "") {
            results += buildJsonObject { put("name", name); put("ok", ok); put("detail", detail.take(8_000)) }
            writeReport(buildJsonObject { put("running", true); put("checks", JsonArray(results)) }.toString())
        }
        fun source(vararg declarations: JsonObject): String = "export function apply(ctx) {\n" +
            declarations.joinToString("\n") { "  ctx.ui.registerLoading($it);" } + "\n}\n"
        fun declaration(loadingId: String, tree: JsonElement) = buildJsonObject {
            put("id", loadingId); put("label", "M86 静态加载页 $loadingId"); put("tree", tree)
        }
        suspend fun checkRejected(name: String, script: String) {
            File(fixture, "index.js").writeText(script, Charsets.UTF_8)
            val tested = invoke("plugin_dev_test", args("path" to fixture.path))
            val message = runCatching { tested.json()["message"]?.jsonPrimitive?.contentOrNull }.getOrNull().orEmpty()
            record(name, tested.isError && message.contains("loading/v1"), tested.text)
        }
        try {
            check(!directory.exists() && directory.parentFile?.isDirectory == true && directory.mkdir()) {
                "专项自测目录必须是父目录下尚不存在的新目录"
            }
            created = true
            val scaffold = invoke("plugin_dev_scaffold", args("path" to fixture.path, "id" to id, "template" to "loading"))
            record("loading scaffold", !scaffold.isError, scaffold.text)
            check(!scaffold.isError) { "无法生成 loading 模板" }
            val tested = invoke("plugin_dev_test", args("path" to fixture.path))
            val metadata = tested.json()["loadings"]?.jsonArray?.singleOrNull()?.jsonObject
            record("real QuickJS registerLoading metadata", !tested.isError && metadata != null &&
                metadata["id"]?.jsonPrimitive?.contentOrNull == "startup" &&
                metadata["protocol"]?.jsonPrimitive?.contentOrNull == LoadingSpec.VERSION &&
                metadata["selectionValue"]?.jsonPrimitive?.contentOrNull == selection, tested.text)
            check(!tested.isError) { "loading 模板真实 apply 失败" }

            val first = declaration("startup", buildJsonObject {
                put("type", "text"); put("text", "M86_LOADING_FIRST")
            })
            val second = declaration("second", buildJsonObject {
                put("type", "column"); put("children", buildJsonArray {
                    add(buildJsonObject { put("type", "text"); put("bind", "boot.message") })
                    add(buildJsonObject { put("type", "progress") })
                })
            })
            val validSource = source(first, second)
            File(fixture, "index.js").writeText(validSource, Charsets.UTF_8)
            val multiple = invoke("plugin_dev_test", args("path" to fixture.path))
            val loadingIds = multiple.json()["loadings"]?.jsonArray?.map { it.jsonObject["id"]?.jsonPrimitive?.contentOrNull }
            record("real QuickJS collects multiple loadings", !multiple.isError && loadingIds == listOf("startup", "second"), multiple.text)
            val install = invoke("plugin_dev_install", args("path" to fixture.path))
            installed = !install.isError
            record("loading install", installed, install.text)
            check(installed) { "安装 loading fixture 失败" }
            val listed = invoke("plugin_dev_list", args("id" to id))
            val listedLoadings = listed.json()["plugins"]?.jsonArray?.singleOrNull()?.jsonObject?.get("loadings")?.jsonArray
            record("list returns persisted loadings", !listed.isError && listedLoadings != null && listedLoadings.size == 2 &&
                listedLoadings.first().jsonObject["selectionValue"]?.jsonPrimitive?.contentOrNull == selection, listed.text)
            val original = store.list().single { it.id == id }
            val snapshots = LoadingSnapshotStore(File(directory, "isolated-loading-snapshot.json"))
            val refreshed = snapshots.refresh(listOf(original), selection)
            snapshotRevision = refreshed.snapshot.revision
            record("static snapshot roundtrip without user selection", refreshed.unavailableReason == null &&
                LoadingSnapshotStore(File(directory, "isolated-loading-snapshot.json")).read(selection) == refreshed.snapshot &&
                refreshed.snapshot.loading.treeJson.contains("M86_LOADING_FIRST"), refreshed.snapshot.revision)
            val secondSnapshot = snapshots.refresh(listOf(original), "$id#second")
            record("explicit declaration selection", secondSnapshot.snapshot.loading.id == "second" &&
                secondSnapshot.snapshot.revision != snapshotRevision, secondSnapshot.snapshot.revision)

            checkRejected("QuickJS rejects loading functions", """
                export function apply(ctx) {
                  ctx.ui.registerLoading({id:'startup',tree:{type:'text',text:'bad',onTap:function(){}}});
                }
            """.trimIndent())
            val forbidden = declaration("startup", buildJsonObject { put("type", "webview"); put("html", "<p>bad</p>") })
            checkRejected("core rejects browser content", source(forbidden))
            val badUpgrade = invoke("plugin_dev_install", args("path" to fixture.path))
            val preserved = store.list().single { it.id == id }
            record("invalid upgrade preserves installed loading", badUpgrade.isError && preserved.loadings == original.loadings,
                badUpgrade.text)
            checkRejected("core rejects chat binding", source(declaration("startup", buildJsonObject {
                put("type", "text"); put("bind", "chat.messages")
            })))
            checkRejected("QuickJS rejects duplicate loading ids", source(first, first))
            checkRejected("QuickJS rejects ninth declaration", source(*Array(LoadingSpec.MAX_DECLARATIONS + 1) { i ->
                declaration("page$i", buildJsonObject { put("type", "progress") })
            }))
            File(fixture, "index.js").writeText(validSource, Charsets.UTF_8)

            val disabledResult = invoke("plugin_dev_enable", buildJsonObject { put("id", id); put("enabled", false) })
            disabled = !disabledResult.isError && store.list().single { it.id == id }.enabled == false
            val fallback = snapshots.refresh(store.list(), selection)
            record("disabled provider invalidates snapshot", disabled && fallback.unavailableReason != null &&
                fallback.snapshot.ownerPlugin == BuiltinLoadingPlugin.ID, fallback.unavailableReason.orEmpty())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            record("loading selftest exception", false, error.message ?: error.javaClass.simpleName)
        } finally {
            // Finish cleanup even if the calling debug coroutine was cancelled after install committed.
            // Mutations still use the caller's real dev enable path and its normal synchronization.
            withContext(NonCancellable) {
                val existing = store.list().firstOrNull { it.id == id }
                if (existing != null) {
                    installed = true
                    disabled = !existing.enabled
                    if (!disabled) try {
                        val result = invoke("plugin_dev_enable", buildJsonObject { put("id", id); put("enabled", false) })
                        disabled = !result.isError && store.list().firstOrNull { it.id == id }?.enabled == false
                    } catch (error: Exception) {
                        record("disable fixture cleanup", false, error.message.orEmpty())
                    }
                }
            }
            writeReport(buildJsonObject {
                put("running", false); put("directory", directory.path); put("pluginId", id)
                put("checks", JsonArray(results))
                put("passed", results.count { it["ok"]?.jsonPrimitive?.booleanOrNull == true }); put("total", results.size)
                put("fixturesRetained", created); put("allCreatedPluginsDisabled", !installed || disabled)
                put("loadingSelectionChanged", false); put("coldStartVerified", false)
                snapshotRevision?.let { put("snapshotRevision", it) }
            }.toString())
        }
    }
}

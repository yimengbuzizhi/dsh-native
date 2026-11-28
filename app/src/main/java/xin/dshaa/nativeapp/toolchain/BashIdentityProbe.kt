package xin.dshaa.nativeapp.toolchain

import android.content.Context
import android.util.Log
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import xin.dshaa.core.tools.ToolContext
import xin.dshaa.core.tools.shell.BashTool
import xin.dshaa.nativeapp.device.DeviceShell
import java.io.File

/**
 * `bash identity=app/shell/root` 的真机自检（docs/198 §二 / docs/199）。
 *
 * 为什么需要：Shizuku / `su` 这条链只有在真机上才走得通（binder、授权状态、uid 都不可 mock）。
 * 这个探针走**真实工具路径**——`BashTool` → `Toolchain` → `DeviceShell`——把每条命令的
 * 请求身份、实际身份、退出码和输出落成 JSON，锁屏/无 UI/CI 都能验：
 *
 * ```
 * adb shell am start -n xin.dshaa.nativeapp/.MainActivity --ez dsh_bash_identity_probe true
 * adb shell run-as xin.dshaa.nativeapp cat files/bash-identity-probe.json
 * ```
 *
 * 有 root 的设备上同一份探针会多验一条 `identity=root`（无 root 时如实返回不可用文案）。
 * `--es dsh_bash_identity_probe_out <绝对路径>` 可改输出位置。
 */
object BashIdentityProbe {

    private const val TAG = "DshBashIdentity"

    private data class Case(
        val name: String,
        val identity: String,
        val command: String,
        val workdir: File? = null,
        val stdin: String? = null,
    )

    fun run(context: Context, outPath: String?) = runBlocking {
        val toolchain = Toolchain(context)
        toolchain.prepare()
        val base = File(context.filesDir, "bash-identity-probe").apply { mkdirs() }
        val cases = listOf(
            Case("app_uid", "app", "id"),
            Case("shell_uid", "shell", "id"),
            Case("shell_pm_list", "shell", "pm list packages moe.shizuku.privileged.api"),
            Case("shell_workdir_public", "shell", "pwd; id -u", workdir = File("/data/local/tmp")),
            Case("shell_stdin", "shell", "cat", stdin = "hello-from-dsh\n"),
            Case("root_uid", "root", "id"),
        )
        val results = cases.map { case ->
            val workspace = case.workdir ?: base
            val ctx = ToolContext(
                workspace = File(context.filesDir, "bash-identity-probe"),
                allowOutsideWorkspace = true,
                shell = toolchain,
            )
            val args = buildJsonObject {
                put("description", "identity probe ${case.name}")
                put("command", case.command)
                put("identity", case.identity)
                put("timeoutMs", 15_000)
                case.stdin?.let { put("stdin", it) }
                case.workdir?.let { put("workdir", it.absolutePath) }
            }
            val started = System.currentTimeMillis()
            val result = runCatching { BashTool().execute(args, ctx) }
            val outcome = result.getOrNull()
            val text = when {
                outcome == null -> "ERROR ${result.exceptionOrNull()?.let { "${it.javaClass.simpleName}: ${it.message}" }}"
                outcome.isError -> "ERROR ${outcome.text}"
                else -> outcome.text
            }.let { if (it.length > 2_000) it.take(2_000) + "…" else it }
            buildJsonObject {
                put("name", case.name)
                put("requested_identity", case.identity)
                put("command", case.command)
                put("elapsed_ms", System.currentTimeMillis() - started)
                put("ok", outcome != null && !outcome.isError)
                put("text", text)
            }
        }
        val payload = buildJsonObject {
            put("root_available", runCatching { DeviceShell.hasRoot() }.getOrDefault(false))
            put("shizuku_binder", DeviceShell.shizukuReady)
            put("shizuku_granted", runCatching { DeviceShell.hasShizukuPermission() }.getOrDefault(false))
            put("shizuku_usable", runCatching { DeviceShell.hasShizuku() }.getOrDefault(false))
            put("launcher", DeviceShell.launcherStatus().let { if (it.available) "ready ${it.path}" else "missing: ${it.reason}" })
            put("channel", DeviceShell.shellChannelReason())
            put("cases", kotlinx.serialization.json.JsonArray(results))
        }
        val file = outPath?.takeIf { it.isNotBlank() }?.let { File(it) }
            ?: File(context.filesDir, "bash-identity-probe.json")
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(payload.toString(), Charsets.UTF_8)
        }.onFailure { Log.w(TAG, "write probe failed: ${it.message}") }
        Log.i(TAG, "bash identity probe -> ${file.absolutePath}\n$payload")
        Unit
    }
}

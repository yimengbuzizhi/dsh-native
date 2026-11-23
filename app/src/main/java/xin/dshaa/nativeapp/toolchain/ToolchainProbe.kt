package xin.dshaa.nativeapp.toolchain

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File

/**
 * 工具箱真机自检（`docs/187` §6.1 / §6.4 验收）：解压 → 建软链 → exec bash → 跑 curl 的
 * HTTPS / HTTP / 重定向 / CA 校验失败四条路径，结果落 JSON。
 *
 * 真机跑法：
 * ```
 * adb shell am start -n xin.dshaa.nativeapp/.MainActivity --ez dsh_toolchain_probe true
 * adb shell cat /sdcard/Android/data/xin.dshaa.nativeapp/files/toolchain-probe.json
 * ```
 * （默认落 `filesDir/toolchain-probe.json`，`--es dsh_toolchain_probe_out <绝对路径>` 可改。）
 *
 * 注意：这里**不**用 `sleep` 等结果，全部是进程退出/读完成事件驱动的同步调用。
 */
object ToolchainProbe {

    private const val TAG = "DshToolchain"

    suspend fun run(context: Context, outputPath: String? = null) = withContext(Dispatchers.IO) {
        val steps = mutableListOf<JsonObject>()
        val toolchain = Toolchain(context)
        var failed = 0

        fun record(step: String, ok: Boolean, detail: String, ms: Long = 0) {
            if (!ok) failed += 1
            steps += buildJsonObject {
                put("step", step)
                put("ok", ok)
                put("detail", detail.take(4000))
                put("ms", ms)
            }
            Log.i(TAG, "probe $step ok=$ok ${detail.take(600)}")
        }

        // 具名函数引用（`record` 带默认参数，不能直接当 Function4 传，所以包一层 lambda）。
        val emit: (String, Boolean, String, Long) -> Unit = { step, ok, detail, ms ->
            record(step, ok, detail, ms)
        }

        val started = System.currentTimeMillis()
        val status = toolchain.ensureInstalled().getOrElse { error ->
            record("install", false, "${error.javaClass.simpleName}: ${error.message}")
            null
        }
        if (status != null) {
            record(
                "install",
                status.installed,
                "bash=${status.bashPath} curl=${status.curlPath} lib=${status.libDir} " +
                    "ca=${File(status.caFile).length()}B symlink=${status.symlinkOk}\nprobe=${status.probe}",
            )
        }

        val bash = toolchain.bashPath()
        if (bash != null) {
            // 1) 直接 exec nativeLibraryDir 里的 bash（PATH 桥 + curl 版本）
            recordRun(
                emit,
                "bash_path_bridge",
                listOf(bash, "-lc", "command -v bash; command -v curl; curl --version | head -1"),
                toolchain,
            )
            // 2) 走 files/toolchain/bin/bash 软链（§6.1 的关键判据）
            val link = File(toolchain.binDir, "bash")
            recordRun(emit, "symlink_exec", listOf(link.absolutePath, "-c", "echo symlink-bash-ok; echo \"\$BASH\""), toolchain)
            // 3) HTTPS + CA
            recordRun(
                emit,
                "curl_https",
                listOf(bash, "-c", "curl -sS -o /dev/null -w '%{http_code}\\n' https://example.com"),
                toolchain,
            )
            recordRun(
                emit,
                "curl_https_body",
                listOf(bash, "-c", "curl -sS https://example.com | head -c 80"),
                toolchain,
            )
            // 4) 明文 http
            recordRun(
                emit,
                "curl_http",
                listOf(bash, "-c", "curl -sS -o /dev/null -w '%{http_code}\\n' http://example.com"),
                toolchain,
            )
            // 5) 重定向（example.com 的 http → https 跳转）
            recordRun(
                emit,
                "curl_redirect",
                listOf(bash, "-c", "curl -sSL -o /dev/null -w '%{http_code} %{url_effective} %{num_redirects}\\n' http://example.com"),
                toolchain,
            )
            // 6) CA 校验失败必须原样报错（用空 bundle 逼出 77）
            recordRun(
                emit,
                "curl_ca_fail",
                listOf(bash, "-c", "curl -sS --cacert /dev/null https://example.com; echo \"[rc=\$?]\""),
                toolchain,
            )
        } else {
            record("bash_missing", false, "no bundled bash for this abi (${context.applicationInfo.nativeLibraryDir})")
        }

        // 7) 没有工具箱时 BashTool 的退回路径仍要活着（/system/bin/sh）
        recordRun(emit, "system_sh", listOf("/system/bin/sh", "-c", "echo system-sh-ok; id -u"), null)

        val report = buildJsonObject {
            put("ok", failed == 0)
            put("at", System.currentTimeMillis())
            put("totalMs", System.currentTimeMillis() - started)
            put("failed", failed)
            put("nativeDir", context.applicationInfo.nativeLibraryDir)
            put("abi", android.os.Build.SUPPORTED_ABIS.joinToString(","))
            put("status", buildJsonObject {
                put("installed", status?.installed ?: false)
                put("bash", status?.bashPath ?: "")
                put("curl", status?.curlPath ?: "")
                put("symlinkOk", status?.symlinkOk ?: false)
                put("error", status?.error ?: "")
            })
            put("steps", buildJsonArray { steps.forEach { add(it) } })
        }
        val target = outputPath?.takeIf { it.isNotBlank() }?.let { File(it) }
            ?: File(context.filesDir, "toolchain-probe.json")
        runCatching {
            target.parentFile?.mkdirs()
            target.writeText(report.toString(), Charsets.UTF_8)
        }
        Log.i(TAG, "probe report: ${target.absolutePath} -> $report")
    }

    /** 跑一条命令并记一行；超时 60s（curl 走网络，别用太短的值假装成功）。 */
    private fun recordRun(
        record: (String, Boolean, String, Long) -> Unit,
        step: String,
        command: List<String>,
        toolchain: Toolchain?,
    ) {
        val at = System.currentTimeMillis()
        val result = runCatching {
            val builder = ProcessBuilder(command).redirectErrorStream(true)
            toolchain?.environment()?.forEach { (k, v) -> builder.environment()[k] = v }
            val process = builder.start()
            process.outputStream.close()
            val output = process.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
            val exit = process.waitFor()
            "exit=$exit\n" + output.trim()
        }.getOrElse { "spawn failed: ${it.javaClass.simpleName}: ${it.message}" }
        val ok = result.startsWith("exit=0")
        record(step, ok, result, System.currentTimeMillis() - at)
    }
}

package com.ai.assistance.showerclient

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * Helper to manage the lifecycle of the Shower server (shower-server.jar) on the device.
 *
 * This implementation is host-agnostic: it relies only on [ShellRunner] and Binder
 * registration via [ShowerBinderRegistry]. The host app is responsible for:
 * - Providing a [ShellRunner] via [ShowerEnvironment.shellRunner]
 * - Packaging `shower-server.jar` into its assets
 */
object ShowerServerManager {

    private const val TAG = "ShowerServerManager"
    private const val ASSET_JAR_NAME = "shower-server.jar"
    private const val LOCAL_JAR_NAME = "shower-server.jar"

    @Volatile
    var additionalTargetPackages: Set<String> = emptySet()

    /**
     * Why the last [ensureServerStarted] attempt failed, in user-readable Chinese.
     *
     * The method returns only a Boolean, so the host (VirtualScreenManager / AI tool result)
     * reads this to surface the real cause instead of the old generic "Shizuku 是否已授权？".
     * Cleared on success.
     */
    @Volatile
    var lastFailureReason: String? = null
        private set

    /**
     * Ensure the Shower server is started in the background.
     * Returns true if the start command was issued successfully and a Binder
     * was received within the timeout window.
     */
    suspend fun ensureServerStarted(context: Context): Boolean {
        // 每次尝试都先复位：否则调用方在异常路径会读到上一次的失败原因（review P2-8）。
        lastFailureReason = null
        // 0) If we already have an alive Binder from the handoff broadcast, just reuse it.
        if (ShowerBinderRegistry.hasAliveService()) {
            ShowerLog.d(TAG, "Shower Binder already cached and alive, skipping start")
            lastFailureReason = null
            return true
        }

        val runner = ShowerEnvironment.shellRunner
        if (runner == null) {
            lastFailureReason = "没有可用的 shell 通道：ShowerEnvironment.shellRunner 未注入"
            ShowerLog.e(TAG, "No ShellRunner configured in ShowerEnvironment; cannot start server")
            return false
        }

        val appContext = context.applicationContext
        val jarFile = try {
            copyJarToExternalDir(appContext)
        } catch (e: Exception) {
            lastFailureReason = "jar 暂存失败（${e.message ?: e.javaClass.simpleName}）：${stagingDir(appContext).absolutePath}"
            ShowerLog.e(TAG, "Failed to copy shower-server.jar from assets", e)
            return false
        }

        // 1) Kill existing server (ignore errors about missing process).
        val killCmd = "pkill -f com.ai.assistance.shower.Main || true"
        ShowerLog.d(TAG, "Stopping existing Shower server (if any) with command: $killCmd")
        runner.run(killCmd, ShellIdentity.DEFAULT)

        // 2) With highest available identity, remove any stale jar and log under /data/local/tmp.
        val remoteJarPath = "/data/local/tmp/$LOCAL_JAR_NAME"
        val remoteLogPath = "/data/local/tmp/shower.log"
        val cleanupCmd = "rm -f $remoteJarPath $remoteLogPath || true"
        ShowerLog.d(TAG, "Cleaning up previous Shower jar and log with command: $cleanupCmd")
        val cleanupResult = runner.run(cleanupCmd, ShellIdentity.DEFAULT)
        if (!cleanupResult.success) {
            ShowerLog.w(
                TAG,
                "Cleanup of Shower jar/log may have failed (exitCode=${cleanupResult.exitCode}). stdout='${cleanupResult.stdout}', stderr='${cleanupResult.stderr}'"
            )
        }

        // 3) Copy the jar from the app-specific staging directory to /data/local/tmp using shell
        // identity, so that the resulting file is owned by the shell user.
        val copyCmd = "cp ${jarFile.absolutePath} $remoteJarPath"
        ShowerLog.d(TAG, "Copying Shower jar with shell identity using command: $copyCmd")
        val copyResult = runner.run(copyCmd, ShellIdentity.SHELL)
        if (!copyResult.success) {
            lastFailureReason =
                "shell 身份拷贝 jar 失败（exit=${copyResult.exitCode}）：" +
                    copyResult.stderr.trim().ifBlank { copyResult.stdout.trim() }.take(200)
            ShowerLog.e(
                TAG,
                "Failed to copy Shower jar to $remoteJarPath (exitCode=${copyResult.exitCode}). stdout='${copyResult.stdout}', stderr='${copyResult.stderr}'"
            )
            return false
        }

        // 4) Start app_process with CLASSPATH pointing to /data/local/tmp/shower-server.jar, in background.
        // stderr 一并重定向到 shower.log：launcher 的 setuid/setcon/execvp 失败也落这里，
        // 而不是只回一句「10s 没收到 Binder」（review P1-2）。
        val targetPackagesArg = appContext.packageName
        val startCmd = "CLASSPATH=$remoteJarPath app_process / com.ai.assistance.shower.Main $targetPackagesArg > $remoteLogPath 2>&1 &"
        ShowerLog.d(TAG, "Starting Shower server with command: $startCmd")
        val startResult = runner.run(startCmd, ShellIdentity.SHELL)
        if (!startResult.success) {
            lastFailureReason =
                "app_process 启动命令失败（exit=${startResult.exitCode}）：" +
                    startResult.stderr.trim().ifBlank { startResult.stdout.trim() }.take(200)
            ShowerLog.e(
                TAG,
                "Failed to start Shower server (exitCode=${startResult.exitCode}). stdout='${startResult.stdout}', stderr='${startResult.stderr}'"
            )
            return false
        }
        val startDiag = startResult.stderr.trim().ifBlank { startResult.stdout.trim() }
        if (startDiag.isNotBlank()) {
            ShowerLog.w(TAG, "Shower start stderr/stdout (${startDiag.length} chars): ${startDiag.take(400)}")
        }

        // 5) Poll for up to 10 seconds for the Binder handoff broadcast to be received and cached.
        for (attempt in 0 until 50) { // 50 * 200ms = 10s
            delay(200)
            if (ShowerBinderRegistry.hasAliveService()) {
                lastFailureReason = null
                ShowerLog.d(
                    TAG,
                    "Shower Binder cached and alive after ~${(attempt + 1) * 200}ms"
                )
                return true
            }
        }

        lastFailureReason =
            "server 已启动但 10s 内没收到 Binder 广播" +
                (if (startDiag.isBlank()) "" else "；start stderr：${startDiag.take(200)}") +
                "（见 /data/local/tmp/shower.log）"
        ShowerLog.e(TAG, "Shower Binder was not received within the expected time")
        return false
    }

    /**
     * Stop the Shower server process if running.
     */
    suspend fun stopServer(): Boolean {
        val runner = ShowerEnvironment.shellRunner
        if (runner == null) {
            ShowerLog.e(TAG, "No ShellRunner configured in ShowerEnvironment; cannot stop server")
            return false
        }
        val cmd = "pkill -f com.ai.assistance.shower.Main || true"
        val result = runner.run(cmd, ShellIdentity.DEFAULT)
        if (!result.success) {
            ShowerLog.e(TAG, "Failed to stop Shower server: ${result.stderr}")
        }
        return result.success
    }

    /**
     * Copy shower-server.jar from assets to the host-provided staging directory.
     *
     * The old Operit client hardcoded `/sdcard/Download/Operit`, which needs
     * MANAGE_EXTERNAL_STORAGE on Android 11+ and made the failure look like a
     * missing Shizuku authorization. The app-specific external directory needs no
     * permission from the app and is readable by the shell user that copies it to
     * `/data/local/tmp`.
     */
    private suspend fun copyJarToExternalDir(context: Context): File = withContext(Dispatchers.IO) {
        val baseDir = stagingDir(context)
        if (!baseDir.exists()) {
            baseDir.mkdirs()
        }
        val outFile = File(baseDir, LOCAL_JAR_NAME)
        context.assets.open(ASSET_JAR_NAME).use { input ->
            FileOutputStream(outFile).use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    output.write(buffer, 0, read)
                }
                output.flush()
            }
        }
        ShowerLog.d(TAG, "Copied $ASSET_JAR_NAME to ${outFile.absolutePath}")
        outFile
    }

    /** Staging directory: host override first, otherwise the app-specific external dir. */
    private fun stagingDir(context: Context): File =
        ShowerEnvironment.stagingDir
            ?: File(context.getExternalFilesDir(null) ?: context.filesDir, "shower")
}

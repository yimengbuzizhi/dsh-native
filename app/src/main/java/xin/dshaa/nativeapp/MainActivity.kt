package xin.dshaa.nativeapp

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import xin.dshaa.nativeapp.ui.DshApp
import xin.dshaa.ui.design.theme.DshTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 调试通道：`am start --es dsh_account_token <token>` 导入 DeepSeek 账号令牌（仅 debug 包生效）。
        if (BuildConfig.DEBUG) {
            intent?.getStringExtra("dsh_account_token")?.takeIf { it.isNotBlank() }?.let { token ->
                getSharedPreferences("dsh", 0).edit()
                    .putString("account_token", token.trim())
                    .putString("provider", "account")
                    .putString("provider_id", "deepseek-account")
                    .apply()
            }
        }
        if (BuildConfig.DEBUG) {
            // 调试通道：`--es dsh_avatar <filesDir 里的绝对路径>` 直接指定助手头像（自动验收用）
            intent?.getStringExtra("dsh_avatar")?.takeIf { it.isNotBlank() }?.let { path ->
                getSharedPreferences("dsh", 0).edit().putString("assistant_avatar", path).apply()
            }
            // 调试通道：`--es dsh_dump_request <绝对路径>` 把下一次请求体原样落盘（门 2 对拍用）
            intent?.getStringExtra("dsh_dump_request")?.takeIf { it.isNotBlank() }?.let { path ->
                getSharedPreferences("dsh", 0).edit().putString("debug_dump_request", path).apply()
            }
            // 摘要请求落盘（M48）：`--es dsh_dump_summary_request <目录>` 每次摘要请求写 summary-<n>.json
            intent?.getStringExtra("dsh_dump_summary_request")?.takeIf { it.isNotBlank() }?.let { path ->
                getSharedPreferences("dsh", 0).edit().putString("debug_dump_summary_dir", path.trim()).apply()
            }
            // 门 2 录制：`--es dsh_dump_sse <目录>` 把每次请求的 SSE 原始字节落盘（llm-0001.jsonl…）
            intent?.getStringExtra("dsh_dump_sse")?.takeIf { it.isNotBlank() }?.let { path ->
                getSharedPreferences("dsh", 0).edit().putString("debug_dump_sse", path).apply()
            }
            // 门 2 运行日志：`--es dsh_parity_run <目录>` 写 run.jsonl（请求/事件/工具/最终会话）
            intent?.getStringExtra("dsh_parity_run")?.takeIf { it.isNotBlank() }?.let { path ->
                getSharedPreferences("dsh", 0).edit().putString("debug_parity_run", path).apply()
            }
            // 门 2 回放：`--es dsh_base_url http://127.0.0.1:18080`（`default` 还原官方端点）
            intent?.getStringExtra("dsh_base_url")?.takeIf { it.isNotBlank() }?.let { url ->
                val value = if (url.equals("default", ignoreCase = true)) {
                    xin.dshaa.nativeapp.data.SettingsStore.DEFAULT_BASE_URL
                } else {
                    url.trim()
                }
                getSharedPreferences("dsh", 0).edit().putString("base_url", value).apply()
            }
            intent?.getStringExtra("dsh_send")?.takeIf { it.isNotBlank() }?.let { text ->
                getSharedPreferences("dsh", 0).edit().putString("debug_send", text).apply()
            }
            // 悬浮窗验收：`--ez dsh_floating true` 启动 / `--ez dsh_floating_stop true` 关闭
            if (intent?.getBooleanExtra("dsh_floating", false) == true) {
                xin.dshaa.nativeapp.device.floating.FloatingBallService.start(this)
            }
            if (intent?.getBooleanExtra("dsh_floating_stop", false) == true) {
                xin.dshaa.nativeapp.device.floating.FloatingBallService.stop(this)
            }
            // 虚拟屏浮层验收：`--ez dsh_virtual_overlay true` 起 1080×1920 虚拟屏（QQ）
            // 并直接显示 VirtualScreenOverlay，不经过模型工具调用。
            if (intent?.getBooleanExtra("dsh_virtual_overlay", false) == true) {
                startDebugVirtualOverlay(automation = false)
            }
            // 虚拟屏自动化栏验收：`--ez dsh_virtual_automation true` 起浮层后直接显示
            // 「1/5 + 暂停/继续 + 退出」，2s/4s 后推进到 3/5、5/5（OverlayInstance.AutomationBar）。
            if (intent?.getBooleanExtra("dsh_virtual_automation", false) == true) {
                startDebugVirtualOverlay(automation = true)
            }
            // 位置附件验收：`--ez dsh_attach_location true` 起透明授权页 + 抓位置进附件
            // （要有已打开的会话；授权框截图见 docs/76 §十八）。
            if (intent?.getBooleanExtra("dsh_attach_location", false) == true) {
                window.decorView.postDelayed(
                    { xin.dshaa.nativeapp.device.attach.LocationCapture.start(this) },
                    1_200,
                )
            }
            // 附件验收：`--es dsh_attach_image <绝对路径>` 把一张图放进待发附件（与 dsh_send 连用）
            intent?.getStringExtra("dsh_attach_image")?.takeIf { it.isNotBlank() }?.let { path ->
                getSharedPreferences("dsh", 0).edit().putString("debug_attach_image", path.trim()).apply()
            }
            // 旧版数据导入：`--es dsh_import_zip <路径>` 直接把导入页打开并预扫描（真机自动验收用）
            intent?.getStringExtra("dsh_import_zip")?.takeIf { it.isNotBlank() }?.let { path ->
                getSharedPreferences("dsh", 0).edit().putString("debug_import_zip", path.trim()).apply()
            }
            // 原生备份验收：`--es dsh_backup_export <绝对路径>` / `--es dsh_backup_import <绝对路径>`
            intent?.getStringExtra("dsh_backup_export")?.takeIf { it.isNotBlank() }?.let { path ->
                getSharedPreferences("dsh", 0).edit().putString("debug_backup_export", path.trim()).apply()
            }
            intent?.getStringExtra("dsh_backup_import")?.takeIf { it.isNotBlank() }?.let { path ->
                getSharedPreferences("dsh", 0).edit().putString("debug_backup_import", path.trim()).apply()
            }
            // 恢复勾选验收：`--es dsh_backup_workspaces <slug1,slug2>` 只恢复匹配的工作区（M39）
            intent?.getStringExtra("dsh_backup_workspaces")?.takeIf { it.isNotBlank() }?.let { raw ->
                getSharedPreferences("dsh", 0).edit().putString("debug_backup_workspaces", raw.trim()).apply()
            }
            // 插件页验收：`--es dsh_plugin_zip <绝对路径>` 直接导入一个插件包并落到插件页
            intent?.getStringExtra("dsh_plugin_zip")?.takeIf { it.isNotBlank() }?.let { path ->
                getSharedPreferences("dsh", 0).edit().putString("debug_plugin_zip", path.trim()).apply()
            }
            // 插件工具验收：`--es dsh_plugin_call <工具名> --es dsh_plugin_args '{"…"}'`
            // 不经过模型直接调一次已接入的插件工具（锁屏/无 UI 也能验宿主链路）
            intent?.getStringExtra("dsh_plugin_call")?.takeIf { it.isNotBlank() }?.let { name ->
                getSharedPreferences("dsh", 0).edit().putString("debug_plugin_call", name.trim()).apply()
            }
            // AI 终端工具验收：`--ez dsh_terminal_selftest true` 不经过模型跑建会话 → echo →
            // 读写屏幕 → 超时/Ctrl+C → 隐藏执行 → 关会话，结果落 filesDir/terminal-selftest.log
            if (intent?.getBooleanExtra("dsh_terminal_selftest", false) == true) {
                getSharedPreferences("dsh", 0).edit().putBoolean("debug_terminal_selftest", true).apply()
            }
            // 截图模式：`--ez dsh_terminal_selftest_keep true` 跑完不关会话，并落到终端页
            if (intent?.getBooleanExtra("dsh_terminal_selftest_keep", false) == true) {
                getSharedPreferences("dsh", 0).edit().putBoolean("debug_terminal_selftest_keep", true).apply()
            }
            // 插件权限验收：`--es dsh_plugin_grant <插件id|all>` 一次授完清单里的危险权限
            intent?.getStringExtra("dsh_plugin_grant")?.takeIf { it.isNotBlank() }?.let { id ->
                getSharedPreferences("dsh", 0).edit().putString("debug_plugin_grant", id.trim()).apply()
            }
            // 插件重载验收：`--es dsh_plugin_reload <插件id>` 锁屏时重跑试运行（Config 变更/升级场景）
            intent?.getStringExtra("dsh_plugin_reload")?.takeIf { it.isNotBlank() }?.let { id ->
                getSharedPreferences("dsh", 0).edit().putString("debug_plugin_reload", id.trim()).apply()
            }
            // 插件详情验收/截图：`--es dsh_plugin_detail <插件id>` 启动即落到插件页并打开详情
            intent?.getStringExtra("dsh_plugin_detail")?.takeIf { it.isNotBlank() }?.let { id ->
                getSharedPreferences("dsh", 0).edit().putString("debug_plugin_detail", id.trim()).apply()
            }
            // 插件配置验收：`--es dsh_plugin_config <插件id|all> --es dsh_plugin_config_b64 <base64 JSON>`
            // 锁屏/无 UI 时也能验「Config 落盘 + apply 收到的配置」；不给 b64 = 恢复默认。
            intent?.getStringExtra("dsh_plugin_config")?.takeIf { it.isNotBlank() }?.let { id ->
                getSharedPreferences("dsh", 0).edit().putString("debug_plugin_config_id", id.trim()).apply()
            }
            intent?.getStringExtra("dsh_plugin_config_b64")?.takeIf { it.isNotBlank() }?.let { encoded ->
                val decoded = runCatching {
                    String(android.util.Base64.decode(encoded.trim(), android.util.Base64.NO_WRAP), Charsets.UTF_8)
                }.getOrNull()
                if (decoded != null) {
                    getSharedPreferences("dsh", 0).edit().putString("debug_plugin_config_json", decoded).apply()
                }
            }
            intent?.getStringExtra("dsh_plugin_args")?.let { args ->
                getSharedPreferences("dsh", 0).edit().putString("debug_plugin_args", args).apply()
            }
            // 参数带引号/空格时 adb shell 会吃掉引号：`--es dsh_plugin_args_b64 <base64>` 更稳
            intent?.getStringExtra("dsh_plugin_args_b64")?.takeIf { it.isNotBlank() }?.let { encoded ->
                val decoded = runCatching {
                    String(android.util.Base64.decode(encoded.trim(), android.util.Base64.NO_WRAP), Charsets.UTF_8)
                }.getOrNull()
                if (decoded != null) {
                    getSharedPreferences("dsh", 0).edit().putString("debug_plugin_args", decoded).apply()
                }
            }
            // goal 工具验收：`--es dsh_goal "<目标>"` 新建会话并写一个 active goal
            intent?.getStringExtra("dsh_goal")?.takeIf { it.isNotBlank() }?.let { objective ->
                getSharedPreferences("dsh", 0).edit().putString("debug_goal", objective.trim()).apply()
            }
            // goal-round-driver 验收：`--ei dsh_goal_max_rounds 2` 给调试目标一个小的轮次上限
            intent?.getIntExtra("dsh_goal_max_rounds", 0)?.takeIf { it > 0 }?.let { rounds ->
                getSharedPreferences("dsh", 0).edit().putInt("debug_goal_max_rounds", rounds).apply()
            }
            // 会话验收：`--es dsh_open_session <id>` 直接打开已有会话（可与 dsh_send 连用，验历史重放）
            intent?.getStringExtra("dsh_open_session")?.takeIf { it.isNotBlank() }?.let { id ->
                getSharedPreferences("dsh", 0).edit().putString("debug_open_session", id.trim()).apply()
            }
            // 页面验收：`--es dsh_open_screen agents` 启动即落到指定一级页面（子代理面板等）
            intent?.getStringExtra("dsh_open_screen")?.takeIf { it.isNotBlank() }?.let { name ->
                getSharedPreferences("dsh", 0).edit().putString("debug_open_screen", name.trim()).apply()
            }
            // 工作区验收：`--es dsh_workspace <绝对路径>` 启动即切到该工作区（抽屉会话列表/工具根）
            intent?.getStringExtra("dsh_workspace")?.takeIf { it.isNotBlank() }?.let { path ->
                getSharedPreferences("dsh", 0).edit().putString("workspace_path", path.trim()).apply()
            }
            if (intent?.getBooleanExtra("dsh_auto_approve", false) == true) {
                getSharedPreferences("dsh", 0).edit().putString("approval_mode", "auto").apply()
            }
            // 计划模式验收：`--ez dsh_plan_mode true` 新建会话时先进计划模式（可与 dsh_send 连用）
            if (intent?.getBooleanExtra("dsh_plan_mode", false) == true) {
                getSharedPreferences("dsh", 0).edit().putBoolean("debug_plan_mode", true).apply()
            }
            // 引导页验收：`--ez dsh_reset_onboarding true` 清掉「已看过引导」，下次冷启重新走一遍
            if (intent?.getBooleanExtra("dsh_reset_onboarding", false) == true) {
                getSharedPreferences("dsh", 0).edit().putBoolean("onboarding_done", false).apply()
            }
            if (intent?.getBooleanExtra("dsh_ask_approval", false) == true) {
                getSharedPreferences("dsh", 0).edit().putString("approval_mode", "ask").apply()
            }
            // 自动压缩验收（M44）：`--ei dsh_max_tokens 2048 --ei dsh_ctx_window 8192`
            // 锁屏/无 UI 时把当前模型的窗口与请求输出上限改小，让压力阈值够得着；
            // 测完用同样的 extras 写回正式值（256000 / 1048576）。
            intent?.getIntExtra("dsh_max_tokens", 0)?.takeIf { it > 0 }?.let { value ->
                getSharedPreferences("dsh", 0).edit().putInt("max_tokens", value).apply()
            }
            intent?.getIntExtra("dsh_ctx_window", 0)?.takeIf { it > 0 }?.let { value ->
                val sp = getSharedPreferences("dsh", 0)
                val model = sp.getString("model", "deepseek-flash") ?: "deepseek-flash"
                sp.edit().putInt("context_window_$model", value).apply()
            }
            // 溢出恢复验收（M45）：`--es dsh_overflow_once "<provider 报错文案>"` 让下一条请求假装溢出，
            // 走「识别 → 最大压缩 → 同一步重发」；消费一次自动清掉。
            intent?.getStringExtra("dsh_overflow_once")?.takeIf { it.isNotBlank() }?.let { text ->
                getSharedPreferences("dsh", 0).edit().putString("debug_overflow_once", text.trim()).apply()
            }
            // 图片卸载验收（M47）：`--ei dsh_image_budget_bytes 4096` 把内联图片字节预算临时改小，
            // 一张普通截图就会触发 IMAGE_OFFLOAD_REQUIRED；测完 sed 掉 prefs 里的 debug_image_budget_bytes。
            intent?.getIntExtra("dsh_image_budget_bytes", 0)?.takeIf { it > 0 }?.let { value ->
                getSharedPreferences("dsh", 0).edit().putInt("debug_image_budget_bytes", value).apply()
            }
        }
        enableEdgeToEdge()
        setContent {
            DshApp()
        }
    }

    /**
     * 虚拟屏浮层调试入口：`--ez dsh_virtual_overlay true`（只看浮层）/
     * `--ez dsh_virtual_automation true`（浮层 + 自动化栏）。
     *
     * Shizuku 的 binder 是异步到的：进程刚起来时 canUseShellIdentity() 还是 false，
     * 直接 start 会报「需要 Shizuku 或 root」，所以最多等 6 秒。
     * 目标 App 用 QQ：Settings 会带 HIDE_NON_SYSTEM_OVERLAY_WINDOWS 私有 flag，
     * 系统会把主屏上的悬浮窗一起强制隐藏（2026-11-08 实测），不适合验收。
     */
    private fun startDebugVirtualOverlay(automation: Boolean) {
        Thread {
            var waited = 0
            while (!xin.dshaa.nativeapp.device.DeviceShell.canUseShellIdentity() && waited < 20) {
                Thread.sleep(300)
                waited++
            }
            val result = kotlinx.coroutines.runBlocking {
                xin.dshaa.nativeapp.device.shower.VirtualScreenManager.start(
                    this@MainActivity,
                    xin.dshaa.nativeapp.device.shower.VirtualDisplaySpec(1080, 1920, 320),
                    "com.tencent.mobileqq",
                )
            }
            result.onSuccess { started ->
                runOnUiThread {
                    val overlay = xin.dshaa.nativeapp.device.shower.VirtualScreenOverlay
                    overlay.show(this, started.displayId)
                    if (automation) {
                        overlay.showAutomationControls(
                            totalSteps = 5,
                            initialStatus = "测试自动化：步骤 1/5",
                            onTogglePauseResume = { paused ->
                                android.util.Log.i("DshDebug", "automation toggle paused=$paused")
                            },
                            onExit = {
                                android.util.Log.i("DshDebug", "automation exit")
                                overlay.hideAutomationControls()
                            },
                        )
                        // 进度演示：验证 1/5 → 3/5 → 5/5 会刷新（updateAutomationProgress）
                        window.decorView.postDelayed(
                            { overlay.updateAutomationProgress(3, 5, "测试步骤 3/5") },
                            2_000,
                        )
                        window.decorView.postDelayed(
                            { overlay.updateAutomationProgress(5, 5, "测试步骤 5/5") },
                            4_000,
                        )
                    }
                }
            }
            android.util.Log.i(
                "DshDebug",
                "dsh_virtual_overlay(automation=$automation): ${result.exceptionOrNull()?.message ?: "ok"}",
            )
        }.start()
    }

    /**
     * 悬浮球跟随后台（2026-11-09 用户要求）：应用一回到前台就收起。
     * [DshApplication] 里的 ProcessLifecycleOwner 才是权威判断（约 700ms 延迟），
     * 这里再收一次是为了避免从悬浮球点回 App 时那一瞬间的重叠。
     */
    override fun onStart() {
        super.onStart()
        xin.dshaa.nativeapp.device.floating.FloatingBallService.hideWhenForeground(this)
    }

    /**
     * `am start --es dsh_send ...` 在 App 已在前台时走 onNewIntent，旧实现只读 onCreate 的
     * intent，导致自动化命令被静默吞掉。调试 extras 一律 recreate，让 onCreate/AppViewModel
     * 重新读 prefs（发送、直落页面、导图都走这一条）。
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // 悬浮球开关不需要重建 Activity：热启动直接起/停服务
        if (intent.getBooleanExtra("dsh_floating", false)) {
            xin.dshaa.nativeapp.device.floating.FloatingBallService.start(this)
        }
        if (intent.getBooleanExtra("dsh_floating_stop", false)) {
            xin.dshaa.nativeapp.device.floating.FloatingBallService.stop(this)
        }
        // 虚拟屏浮层/自动化栏：热启动也生效（不进 DEBUG_EXTRAS，避免 recreate 重复起浮层）
        if (BuildConfig.DEBUG && intent.getBooleanExtra("dsh_virtual_overlay", false)) {
            startDebugVirtualOverlay(automation = false)
        }
        if (BuildConfig.DEBUG && intent.getBooleanExtra("dsh_virtual_automation", false)) {
            startDebugVirtualOverlay(automation = true)
        }
        if (BuildConfig.DEBUG && intent.getBooleanExtra("dsh_attach_location", false)) {
            window.decorView.postDelayed(
                { xin.dshaa.nativeapp.device.attach.LocationCapture.start(this) },
                600,
            )
        }
        if (BuildConfig.DEBUG && DEBUG_EXTRAS.any { intent.hasExtra(it) }) {
            recreate()
        }
    }

    private companion object {
        val DEBUG_EXTRAS = listOf(
            "dsh_send",
            "dsh_open_screen",
            "dsh_open_session",
            "dsh_goal",
            "dsh_import_zip",
            "dsh_backup_export",
            "dsh_backup_import",
            "dsh_backup_workspaces",
            "dsh_plugin_zip",
            "dsh_plugin_call",
            "dsh_terminal_selftest",
            "dsh_terminal_selftest_keep",
            "dsh_plugin_grant",
            "dsh_plugin_reload",
            "dsh_plugin_detail",
            "dsh_plugin_config",
            "dsh_plugin_config_b64",
            "dsh_plugin_args",
            "dsh_plugin_args_b64",
            "dsh_reset_onboarding",
            "dsh_attach_image",
            "dsh_workspace",
            "dsh_max_tokens",
            "dsh_ctx_window",
            "dsh_overflow_once",
            "dsh_image_budget_bytes",
            "dsh_dump_summary_request",
        )
    }
}

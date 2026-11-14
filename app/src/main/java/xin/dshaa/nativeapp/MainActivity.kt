package xin.dshaa.nativeapp

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import xin.dshaa.nativeapp.chat.AppViewModel
import xin.dshaa.nativeapp.ui.boot.BootHost
import xin.dshaa.ui.design.theme.DshTheme
import xin.dshaa.ui.shellapi.data.SettingsStore

class MainActivity : ComponentActivity() {

    /** 和 Compose 里的 `viewModel()` 是同一个实例（同一 ViewModelStore）。 */
    private val appVm: AppViewModel by viewModels()
    private var bootReady = false
    private var debugRecreatePending = false
    private val pendingBootIntents = ArrayDeque<Pair<Intent, Boolean>>()

    override fun onCreate(savedInstanceState: Bundle?) {
        val activityStartedAt = android.os.SystemClock.elapsedRealtime()
        super.onCreate(savedInstanceState)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            // The platform icon must release as soon as app content can draw; its fade can hide
            // the entire replaceable loading page when plugin initialization finishes quickly.
            splashScreen.setOnExitAnimationListener { splash -> splash.remove() }
        }
        android.util.Log.i("DshBoot", "activityStart elapsedRealtimeMs=$activityStartedAt")
        // 调试通道：`am start --es dsh_account_token <token>` 导入 DeepSeek 账号令牌（仅 debug 包生效）。
        // 2026-11-09：DeepSeek 账号登录已删除，这个通道一并移除。
        if (BuildConfig.DEBUG && (savedInstanceState == null || savedInstanceState.getBoolean("dsh.debugRecreatePending", false))) {
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
                    xin.dshaa.ui.shellapi.data.SettingsStore.DEFAULT_BASE_URL
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
            // M74 引用 chip 验收：`--es dsh_attach_text <绝对路径>` 把文本文件放进待发附件
            intent?.getStringExtra("dsh_attach_text")?.takeIf { it.isNotBlank() }?.let { path ->
                getSharedPreferences("dsh", 0).edit().putString("debug_attach_text", path.trim()).apply()
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
            // 插件窗口验收（M70）：`--es dsh_client_plugin <插件id>` 打开插件自己的 dsh.client 界面
            intent?.getStringExtra("dsh_client_plugin")?.takeIf { it.isNotBlank() }?.let { id ->
                getSharedPreferences("dsh", 0).edit().putString("debug_client_plugin", id.trim()).apply()
            }
            // 「拉取模型」验收：`--es dsh_fetch_models <结果文件路径>` 拉一次供应商模型列表
            intent?.getStringExtra("dsh_fetch_models")?.takeIf { it.isNotBlank() }?.let { path ->
                getSharedPreferences("dsh", 0).edit().putString("debug_fetch_models", path.trim()).apply()
            }
            // 已勾选模型验收（M74）：`--es dsh_enabled_models "a,b,c"` 直接写 enabled_models
            //（逗号/竖线分隔；用来在无 UI 操作时验「模型浮层只列已勾选」）
            intent?.getStringExtra("dsh_enabled_models")?.takeIf { it.isNotBlank() }?.let { raw ->
                val names = raw.split(',', '|').map { it.trim() }.filter { it.isNotEmpty() }
                if (names.isNotEmpty()) {
                    getSharedPreferences("dsh", 0).edit()
                        .putString("enabled_models", names.joinToString("\n")).apply()
                }
            }
            // 大肥鱼状态机演示（M70 起只驱动会话快照，原生悬浮窗已删）：
            // `--es dsh_fish_demo thinking|working|waiting|success|error|idle`
            intent?.getStringExtra("dsh_fish_demo")?.takeIf { it.isNotBlank() }?.let { demo ->
                getSharedPreferences("dsh", 0).edit().putString("debug_fish_demo", demo.trim()).apply()
            }
            // 角色卡验收（M71）：`--es dsh_persona <base64(UTF-8 JSON)>`，
            // JSON 可含 name / persona / opening / system（键不存在就不动对应字段）。
            // M73：走 SettingsStore 写**当前使用的那份助手配置**（多份角色卡在 assistant_profiles JSON 里），
            // 不能直接写旧单份键——那样只会改 legacy 字段，页面/提示词读的是 profiles。
            intent?.getStringExtra("dsh_persona")?.takeIf { it.isNotBlank() }?.let { raw ->
                runCatching {
                    val text = String(android.util.Base64.decode(raw, android.util.Base64.NO_WRAP), Charsets.UTF_8)
                    val json = org.json.JSONObject(text)
                    val store = xin.dshaa.ui.shellapi.data.SettingsStore(getSharedPreferences("dsh", 0))
                    json.optString("name").takeIf { it.isNotEmpty() }?.let { store.setAssistantName(it) }
                    if (json.has("persona")) store.setAssistantPersona(json.optString("persona"))
                    if (json.has("opening")) store.setAssistantOpening(json.optString("opening"))
                    if (json.has("system")) store.setAssistantSystem(json.optString("system"))
                }.onFailure { android.util.Log.w("DshPersona", "bad dsh_persona: ${it.message}") }
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
            // 远程 DSH 验收（M83 阶段 4）：`--es dsh_remote_url http://100.x.x.x:8137 --es dsh_remote_token 12345678`
            intent?.getStringExtra("dsh_remote_url")?.takeIf { it.isNotBlank() }?.let { url ->
                getSharedPreferences("dsh", 0).edit().putString("debug_remote_url", url.trim()).apply()
            }
            intent?.getStringExtra("dsh_remote_token")?.takeIf { it.isNotBlank() }?.let { token ->
                getSharedPreferences("dsh", 0).edit().putString("debug_remote_token", token.trim()).apply()
            }
            // 内置插件验收（M84-A）：关掉/打开指定分组，并把当前 tools[] 名单落盘。
            // 例：`--es dsh_builtin_off builtin.fs --es dsh_dump_tools <files>/tools.json`
            // 真正执行在 setContent 之后的 handleBuiltinDebugIntent()（可以同进程反复 am start）。
            // M84-C 模型通道/提示词验收：`--es dsh_llm_provider debug.echo|off`
            // `--es dsh_prompt_override <标记>|off`（热切换见 handleProviderDebugIntent）
            intent?.getStringExtra("dsh_llm_provider")?.takeIf { it.isNotBlank() }?.let { value ->
                val sp = getSharedPreferences("dsh", 0)
                if (value.equals("off", ignoreCase = true)) sp.edit().remove("debug_llm_provider").apply()
                else sp.edit().putString("debug_llm_provider", value.trim()).apply()
            }
            intent?.getStringExtra("dsh_prompt_override")?.takeIf { it.isNotBlank() }?.let { value ->
                val sp = getSharedPreferences("dsh", 0)
                if (value.equals("off", ignoreCase = true)) sp.edit().remove("debug_prompt_override").apply()
                else sp.edit().putString("debug_prompt_override", value.trim()).apply()
            }
            // M84-C2 会话存储验收：`--es dsh_session_store memory|off`（需冷启；VM 构造时按它选实现）
            intent?.getStringExtra("dsh_session_store")?.takeIf { it.isNotBlank() }?.let { value ->
                val sp = getSharedPreferences("dsh", 0)
                if (value.equals("off", ignoreCase = true)) sp.edit().remove("debug_session_store").apply()
                else sp.edit().putString("debug_session_store", value.trim()).apply()
            }
            // M85-A 壳验收：`--es dsh_shell <插件id[#壳id]|native>`（需冷启；VM 构造时按它选壳）
            intent?.getStringExtra("dsh_shell")?.takeIf { it.isNotBlank() }?.let { value ->
                getSharedPreferences("dsh", 0).edit().putString("dsh_shell", value.trim()).apply()
            }
            // M85-D 回合验收：`--es dsh_agent <插件id[#回合id]|native|builtin.agent.dsh>`（VM 构造时按它选回合）
            intent?.getStringExtra("dsh_agent")?.takeIf { it.isNotBlank() }?.let { value ->
                getSharedPreferences("dsh", 0).edit().putString("dsh_agent", value.trim()).apply()
            }
            // M85-E 存储/提示词/模型验收（冷启按它解析；角色选择规则见 core 里的 Routing）：
            // `--es dsh_storage <插件id[#存储id]|native|builtin.storage.jsonl>`
            // `--es dsh_prompt  <插件id[#实现id]|native|builtin.prompt.dsh>`
            // `--es dsh_model   <provider id|off>`（off = 跟随协议；写 settings 的 llm_provider_id）
            intent?.getStringExtra("dsh_storage")?.takeIf { it.isNotBlank() }?.let { value ->
                getSharedPreferences("dsh", 0).edit().putString("dsh_storage", value.trim()).apply()
            }
            intent?.getStringExtra("dsh_prompt")?.takeIf { it.isNotBlank() }?.let { value ->
                getSharedPreferences("dsh", 0).edit().putString("dsh_prompt", value.trim()).apply()
            }
            intent?.getStringExtra("dsh_model")?.takeIf { it.isNotBlank() }?.let { value ->
                getSharedPreferences("dsh", 0).edit()
                    .putString("llm_provider_id", if (value.equals("off", ignoreCase = true)) "" else value.trim())
                    .apply()
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
        // Preserve arrivals across rotation/recreate while the boot gate is still closed.
        if (BuildConfig.DEBUG) {
            val restored = savedInstanceState?.getParcelableArrayList<Intent>("dsh.pendingBootIntents")
            val hotFlags = savedInstanceState?.getBooleanArray("dsh.pendingBootHot")
            if (restored != null) restored.forEachIndexed { index, captured ->
                pendingBootIntents.addLast(Intent(captured) to (hotFlags?.getOrNull(index) ?: false))
            } else if (savedInstanceState == null || savedInstanceState.getBoolean("dsh.debugRecreatePending", false)) {
                pendingBootIntents.addLast(Intent(intent) to false)
            }
        }
        setContent {
            BootHost(
                activity = this,
                activityStartedAt = activityStartedAt,
                createViewModel = { appVm },
                onReady = { bootReady = true; drainBootIntents() },
            )
        }
    }

    private fun drainBootIntents() {
        if (!bootReady) return
        while (pendingBootIntents.isNotEmpty()) {
            val (captured, hot) = pendingBootIntents.removeFirst()
            handleUserPluginDebugIntent(captured)
            handleBuiltinDebugIntent(captured)
            handlePageDebugIntent(captured)
            handleProviderDebugIntent(captured)
            handleSessionStoreDebugIntent(captured)
            handleProjectionDebugIntent(captured)
            handleShellDebugIntent(captured, applySelection = hot)
            if (hot && DEBUG_EXTRAS.any { captured.hasExtra(it) }) {
                setIntent(captured)
                pendingBootIntents.addFirst(Intent(captured) to false)
                debugRecreatePending = true
                bootReady = false
                recreate()
                return
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putParcelableArrayList("dsh.pendingBootIntents", ArrayList(pendingBootIntents.map { it.first }))
        outState.putBooleanArray("dsh.pendingBootHot", pendingBootIntents.map { it.second }.toBooleanArray())
        outState.putBoolean("dsh.debugRecreatePending", debugRecreatePending)
        super.onSaveInstanceState(outState)
    }

    /** M84-F：所有导入插件一起开/关 + 当前名单落盘（空壳/全装配验收）。 */
    private fun handleUserPluginDebugIntent(intent: Intent) {
        val off = intent.getBooleanExtra("dsh_plugins_all_off", false)
        val on = intent.getBooleanExtra("dsh_plugins_all_on", false)
        val dump = intent.getStringExtra("dsh_dump_plugins")
        // M84-H：单个插件开/关（验常驻运行时随开关起停 + 投影值收回）
        val offId = intent.getStringExtra("dsh_plugin_off")
        val onId = intent.getStringExtra("dsh_plugin_on")
        val projectionDump = intent.getStringExtra("dsh_dump_projections")
        if (off || on || !dump.isNullOrBlank()) appVm.applyDebugUserPlugins(off, on, dump)
        if (!offId.isNullOrBlank() || !onId.isNullOrBlank()) {
            appVm.applyDebugPluginToggle(offId, onId, projectionDump)
        }
    }

    /** M84-C：热切换模型供应商 / 提示词实现（不重建 Activity，下一次请求即生效）。 */
    private fun handleProviderDebugIntent(intent: Intent) {
        val provider = intent.getStringExtra("dsh_llm_provider")
        val prompt = intent.getStringExtra("dsh_prompt_override")
        val dump = intent.getStringExtra("dsh_dump_providers")
        if (provider.isNullOrBlank() && prompt.isNullOrBlank() && dump.isNullOrBlank()) return
        appVm.applyDebugProviders(provider, prompt)
        appVm.dumpProviders(dump)
    }

    /** M84-C2：把当前会话存储实现/活动会话/磁盘会话数落盘（热启动即可，不重建 Activity）。 */
    private fun handleSessionStoreDebugIntent(intent: Intent) {
        val dump = intent.getStringExtra("dsh_dump_sessions") ?: return
        if (dump.isBlank()) return
        appVm.dumpSessionStore(dump)
    }

    /** 内置插件验收通道（仅 debug）：同进程反复 `am start` 即可改开关并落盘 tools[]。 */
    private fun handleBuiltinDebugIntent(intent: Intent) {
        val off = intent.getStringExtra("dsh_builtin_off")
        val on = intent.getStringExtra("dsh_builtin_on")
        val reset = intent.getBooleanExtra("dsh_builtin_reset", false)
        val dump = intent.getStringExtra("dsh_dump_tools")
        if (off.isNullOrBlank() && on.isNullOrBlank() && !reset && dump.isNullOrBlank()) return
        appVm.applyDebugBuiltin(off, on, reset, dump)
    }

    /** M84-H：常驻 JS 运行时/会话投影落盘（`--es dsh_dump_projections <绝对路径>`）。 */
    private fun handleProjectionDebugIntent(intent: Intent) {
        val dump = intent.getStringExtra("dsh_dump_projections") ?: return
        if (dump.isBlank()) return
        appVm.dumpProjections(dump)
    }

    /**
     * M85-A 壳验收（仅 debug）：
     * - `--es dsh_shell <插件id[#壳id]|native>`：冷启在 onCreate 之前写 pref；热启动直接切；
     * - `--es dsh_shell_list <绝对路径>`：等常驻运行时/mount 落定后把壳清单和当前树状态落盘。
     */
    private fun handleShellDebugIntent(intent: Intent, applySelection: Boolean) {
        if (applySelection) {
            intent.getStringExtra("dsh_shell")?.takeIf { it.isNotBlank() }?.let { value ->
                appVm.setActiveShell(appVm.resolveShellTarget(value))
            }
        }
        intent.getStringExtra("dsh_shell_list")?.takeIf { it.isNotBlank() }?.let { path ->
            window.decorView.postDelayed({ appVm.dumpShells(path) }, 3_500)
        }
        // M85-D：回合插件选择/清单（与 dsh_shell 同一套热启动通道；冷启时 onCreate 会先写 pref）。
        if (applySelection) {
            intent.getStringExtra("dsh_agent")?.takeIf { it.isNotBlank() }?.let { value ->
                appVm.setActiveAgent(value.trim())
            }
        }
        intent.getStringExtra("dsh_agent_list")?.takeIf { it.isNotBlank() }?.let { path ->
            window.decorView.postDelayed({ appVm.dumpAgents(path) }, 3_500)
        }
        // M85-E：存储 / 提示词 / 模型三角色热切换 + 清单落盘。
        if (applySelection) {
            intent.getStringExtra("dsh_storage")?.takeIf { it.isNotBlank() }?.let { value ->
                appVm.setActiveStorage(value.trim())
            }
            intent.getStringExtra("dsh_prompt")?.takeIf { it.isNotBlank() }?.let { value ->
                appVm.setActivePrompt(value.trim())
            }
            intent.getStringExtra("dsh_model")?.takeIf { it.isNotBlank() }?.let { value ->
                if (value.equals("off", ignoreCase = true)) appVm.restoreBuiltinModel()
                else appVm.setActiveModel(value.trim())
            }
        }
        intent.getStringExtra("dsh_roles_list")?.takeIf { it.isNotBlank() }?.let { path ->
            window.decorView.postDelayed({ appVm.dumpRoles(path) }, 3_500)
        }
        intent.getStringExtra("dsh_plugin_dev_test")?.takeIf { it.isNotBlank() }?.let { path ->
            appVm.runPluginDevSelfTest(path)
        }
        intent.getStringExtra("dsh_context_usage")?.takeIf { it.isNotBlank() }?.let { path ->
            appVm.dumpContextUsage(path)
        }
        intent.getStringExtra("dsh_loading_dev_test")?.takeIf { it.isNotBlank() }?.let { appVm.runLoadingDevSelfTest(it) }
        intent.getStringExtra("dsh_loading")?.takeIf { it.isNotBlank() }?.let { value ->
            if (value == "default" || value == "native") appVm.restoreBuiltinLoading() else appVm.selectLoading(value)
        }
        intent.getStringExtra("dsh_loading_list")?.takeIf { it.isNotBlank() }?.let { appVm.dumpLoading(it) }
        intent.getStringExtra("dsh_ui_probe")?.takeIf { it.isNotBlank() }?.let { appVm.dumpUiProbeState(it) }
        intent.getStringExtra("dsh_queue_test")?.takeIf { it.isNotBlank() }?.let { appVm.runPromptQueueDeviceProbe(it) }
        intent.getStringExtra("dsh_remote_test")?.takeIf { it.isNotBlank() }?.let { appVm.runRemoteConversationDeviceProbe(it) }
        // M85-F：能力插件（capability/v1）清单 / 调用验收。
        intent.getStringExtra("dsh_capability_list")?.takeIf { it.isNotBlank() }?.let { path ->
            window.decorView.postDelayed({ appVm.dumpCapabilities(path) }, 3_500)
        }
        intent.getStringExtra("dsh_capability_test")?.takeIf { it.isNotBlank() }?.let { path ->
            window.decorView.postDelayed({ appVm.runCapabilityTest(path) }, 3_500)
        }
    }

    /**
     * M84-B 页面验收通道（仅 debug）：
     * - `--es dsh_open_screen <页面id>`：热启动直接落页面（不再 recreate；关掉的页面会被路由拦回）；
     * - `--es dsh_dump_pages <绝对路径>`：把 22 个页面的归属/开关落盘。
     */
    private fun handlePageDebugIntent(intent: Intent) {
        intent.getStringExtra("dsh_open_screen")?.takeIf { it.isNotBlank() }?.let { name ->
            appVm.requestDebugScreen(name.trim())
        }
        intent.getStringExtra("dsh_dump_pages")?.takeIf { it.isNotBlank() }?.let { path ->
            appVm.applyDebugPages(path.trim())
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
        // M84-A：内置插件验收不重建 Activity（VM 里的开关直接改，tools[] 现算）
        if (BuildConfig.DEBUG) {
            pendingBootIntents.addLast(Intent(intent) to true)
            drainBootIntents()
        }
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
        // VM-dependent debug actions/recreate are drained only after runtime readiness.
    }

    private companion object {
        val DEBUG_EXTRAS = listOf(
            "dsh_send",
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
            "dsh_remote_url",
            "dsh_remote_token",
        )
    }
}

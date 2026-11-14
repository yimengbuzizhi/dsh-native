package xin.dshaa.nativeapp.data

import kotlinx.serialization.json.*
import xin.dshaa.core.plugins.ManifestRead
import xin.dshaa.core.plugins.PluginFormat
import xin.dshaa.core.plugins.PluginPackage
import java.io.File

internal object PluginDevScaffold {
    fun create(directory: File, id: String, template: String) {
        require(template in setOf("tool", "surface-shell", "webview-shell", "loading")) { "未知模板" }
        require(id.length in 1..80 && !id.startsWith("builtin.")) { "id 必须为 1–80 字符，且不能占用 builtin 命名空间" }
        val manifest = buildJsonObject {
            put("name", id); put("version", "0.1.0"); put("description", "本地开发插件")
            put("main", "index.js")
            put("permissions", buildJsonArray { if (template != "tool") add("ui") })
        }.toString()
        val parsed = PluginPackage.parseManifestText(manifest, PluginFormat.DSH)
        require(parsed is ManifestRead.Ok) { (parsed as? ManifestRead.Failed)?.error ?: "插件 id 不合法" }
        require(!directory.exists()) { "目录已存在，拒绝覆盖" }
        val target = directory.canonicalFile
        require(target.parentFile?.isDirectory == true) { "父目录不存在" }
        require(target.mkdir()) { "创建插件目录失败" }
        // Only this newly-created, checked directory is written. Failure leaves reviewable partial files.
        File(target, "package.json").writeText(manifest + "\n")
        File(target, "index.js").writeText(source(id, template))
        File(target, "README.md").writeText("""
            # $id

            这是鲸歌 Android 插件的最小 $template 模板。
            先运行 plugin_dev_test，再运行 plugin_dev_install；修改已安装文件后可 plugin_dev_reload。
            test 只执行模块与 apply 注册，不调用工具 execute 或壳 mount。
            工具模板没有文件/网络/设备权限；壳与加载页仅声明 ui，安装启用不会自动选中。
            loading 模板通过 ctx.ui.registerLoading({id:'startup',label,tree}) 声明 loading/v1 静态树。
            用 list/test 的 loadings 检查登记，插件 → 角色 → 启动加载页选择后，下次冷启动验证快照与首帧。
            加载页无 mount/action/HTML/图片路径，只允许 boot.phase/message/elapsed 文本绑定。
            每包至多 8 项，每项 UTF-8 32 KiB、128 节点，根深度 0、最深 12，单段文本 2000 字符。
            不执行冷启动 JS，不自行延时等待，不把试运行通过当作首帧、错误恢复或启动预算已验收。
            导入不执行 npm/build 脚本；入口必须是自包含 JavaScript，相对 import 需要预先打包。
            同 id 升级按重装处理：兼容配置保留，插件会启用，危险权限收回后需在插件页重新授权。
        """.trimIndent() + "\n")
    }

    internal fun source(id: String, template: String): String {
        val quoted = JsonPrimitive(id).toString()
        return when (template) {
            "tool" -> """
                export function apply(ctx) {
                  ctx.tools.register({
                    name: ${JsonPrimitive(id.replace(Regex("[^A-Za-z0-9_]"), "_") + "_hello")},
                    description: '返回一条问候',
                    parameters: { type: 'object', properties: { name: { type: 'string' } }, additionalProperties: false },
                    execute: function(args) { return '你好，' + String(args.name || '世界'); }
                  });
                }
            """.trimIndent() + "\n"
            "loading" -> """
                export function apply(ctx) {
                  ctx.ui.registerLoading({
                    "id": "startup", "label": "我的启动加载页",
                    "tree": { "type": "column", "padding": 24, "gap": 12, "align": "center", "children": [
                      { "type": "icon", "name": "hourglass", "size": 40, "color": "brand" },
                      { "type": "text", "text": "正在准备…", "size": 24, "weight": "bold" },
                      { "type": "progress" },
                      { "type": "text", "bind": "boot.message", "color": "sub" },
                      { "type": "text", "bind": "boot.elapsed", "size": 12, "color": "faint" }
                    ] }
                  });
                }
            """.trimIndent() + "\n"
            "surface-shell" -> """
                function render() {
                  return { type: 'column', padding: 20, gap: 12, children: [
                    { type: 'text', text: '我的 Surface 壳', size: 22 },
                    { type: 'button', label: '返回原生界面', onTap: 'close' }
                  ] };
                }
                export function apply(ctx) {
                  ctx.ui.registerShell({
                    id: $quoted, label: '我的 Surface 壳', backend: 'surface',
                    mount: render, onState: render,
                    onAction: function(action) {
                      if (action === 'close') ctx.surface.command('closeShell', {});
                      return render();
                    }
                  });
                }
            """.trimIndent() + "\n"
            "webview-shell" -> """
                function render() {
                  return { title: '我的 WebView 壳', html: '<main style="padding:20px"><h1>我的 WebView 壳</h1><button onclick="DshWebShell.action(&quot;close&quot;,{})">返回原生界面</button></main>' };
                }
                export function apply(ctx) {
                  ctx.ui.registerShell({
                    id: $quoted, label: '我的 WebView 壳', backend: 'webview',
                    mount: render, onState: render,
                    onAction: function(action) {
                      if (action === 'close') ctx.surface.command('closeShell', {});
                      return render();
                    }
                  });
                }
            """.trimIndent() + "\n"
            else -> error("未知模板")
        }
    }
}

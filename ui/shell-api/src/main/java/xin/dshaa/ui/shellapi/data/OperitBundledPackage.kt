package xin.dshaa.ui.shellapi.data

/** 一个随 App 打包的 Operit 自带工具包（`assets/operit-packages/` 下的 .js 文件）。 */
data class OperitBundledPackage(
    /** assets 里的文件名，如 `crossref.js`。 */
    val assetName: String,
    /** 安装后插件清单里的 id，如 `operit-crossref`。 */
    val pluginId: String,
    val title: String,
    val description: String,
    val category: String,
    val tools: List<String>,
    /** 安装时写进清单的权限（用户仍要在插件页确认 net/shell/device/fs:any）。 */
    val permissions: List<String>,
    val status: OperitStatus,
    /** 为什么是「部分可用」/「需要 API Key」，展示给用户。 */
    val notes: List<String>,
) {
    val permissionLabel: String
        get() = when {
            permissions.isEmpty() -> "无特殊权限"
            else -> permissions.joinToString("、") { raw ->
                when (raw) {
                    "net:any" -> "网络"
                    "fs:any" -> "文件"
                    "shell" -> "命令"
                    "device" -> "设备"
                    else -> raw
                }
            }
        }
}

/** 兼容状态：只认三种真实结论，不做「大概能跑」的模糊评级。 */
enum class OperitStatus(val label: String) {
    READY("可用"),
    PARTIAL("部分可用"),
    NEEDS_ENV("需要 API Key"),
}

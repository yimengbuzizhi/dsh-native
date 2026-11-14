package xin.dshaa.nativeapp.chat

/**
 * M85-B2：壳发出的「打开系统选择器」请求。
 *
 * `surface.command` 是插件 → 宿主的同步调用，而 SAF/相机必须由 Activity 侧的
 * `rememberLauncherForActivityResult` 启动；中间用一个带序号的请求交接：
 * 命令只登记请求，壳宿主屏（[xin.dshaa.nativeapp.ui.surface.PluginShellScreen]）消费一次并启动对应选择器。
 *
 * 这样附件能力仍然在宿主骨架里（插件只发通用命令），而不是给壳开 Activity 权限。
 */
enum class ShellPickKind {
    /** 系统相册（Photo Picker，可多选）。 */
    IMAGE,

    /** 系统文件选择器（任意文件；图片会走图片管线，文本内联，二进制复制引用）。 */
    FILE,

    /** 系统相机（FileProvider 临时文件，不申请 CAMERA 权限）。 */
    CAMERA,
}

data class ShellPickRequest(
    val serial: Long,
    val kind: ShellPickKind,
    val target: xin.dshaa.ui.shellapi.chat.ChatSessionState?,
)

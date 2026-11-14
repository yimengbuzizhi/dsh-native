package xin.dshaa.nativeapp.device.shower

/**
 * M85-C3-3c：虚拟屏 UI 需要的宿主副作用钩子。
 *
 * `VirtualScreenOverlay` 搬进 `ui:native-shell` 后不能反向 import app 的 `ChatMirror`；
 * 由 app 启动时注入「停止虚拟屏时也停止当前聊天回合」。
 */
object VirtualScreenRuntime {
    /** 默认空操作；app 的 `AppViewModel.init` 注入 `ChatMirror.stop()`。 */
    var onStopChat: () -> Unit = {}
}

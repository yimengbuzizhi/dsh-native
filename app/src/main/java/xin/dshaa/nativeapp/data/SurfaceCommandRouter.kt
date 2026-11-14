package xin.dshaa.nativeapp.data

/**
 * M85-A：`surface.command` 的宿主路由。
 *
 * 调用链：壳的常驻 JS（`ctx.surface.command`）→ `__dshCallSync('surface.command')` →
 * [AndroidPluginHostBridge] → 这里 → `AppViewModel.shellCommand`（切主线程执行）。
 *
 * 用进程级单例是因为桥按插件构造、而命令必须落到同一个 ViewModel；
 * 只允许白名单命令（见 `SurfaceSpec.COMMANDS`），插件借不到任意宿主方法。
 */
object SurfaceCommandRouter {
    @Volatile
    var handler: ((command: String, argsJson: String) -> String?)? = null
}

package xin.dshaa.core.plugins

/**
 * 宿主桥（M67）：插件需要、但 `core:plugins` 没有 Android Context 的那部分能力。
 *
 * 为什么是一个「方法名 + JSON」的窄接口，而不是给每个能力写一个 Kotlin 方法：
 * Operit ToolPkg 的 `Tools.*` 面很宽（设备信息、应用列表、无障碍手势、截图、记忆库…），
 * 能力实现全在 app 模块（`AndroidPluginHostBridge`），core 只负责权限门和参数转发。
 * 这样单测里传 `null` 就是「宿主没有这个能力」，不会把 Android 依赖拖进 core。
 *
 * 约定：
 * - 返回 `null` = 宿主没有实现这个方法（插件拿到「暂不支持」错误，不会静默成功）；
 * - 返回值必须是完整 JSON 对象，且带 `"ok": true|false`（和 [PluginSandbox.Reply] 的 payload 同形）；
 * - 这个方法在插件宿主线程上同步调用，实现方不要阻塞超过几百毫秒。
 */
interface PluginHostBridge {
    fun call(method: String, payloadJson: String): String?
}

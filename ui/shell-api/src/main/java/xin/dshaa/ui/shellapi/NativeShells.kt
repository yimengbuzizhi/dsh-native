package xin.dshaa.ui.shellapi

import java.util.ServiceLoader

/**
 * M85-C3-3c：native 壳的**服务提供者**接口。
 *
 * `ui:shell-api` 不认识具体壳实现；`ui:native-shell` 通过
 * `META-INF/services/xin.dshaa.ui.shellapi.NativeShellProvider` 注册 [BuiltinNativeShell]。
 * 这样 app 只 import shell-api 的 [NativeShells]，不会反向 import 任何 `ui.*` 页面。
 */
interface NativeShellProvider {
    fun create(): NativeShell
}

/**
 * native 壳注册表（宿主的唯一入口）。
 *
 * 加载顺序：
 * 1. `ServiceLoader` 读 `META-INF/services/...NativeShellProvider`（Android library 的
 *    `src/main/resources` 会打进 APK）；
 * 2. 万一资源被裁掉，回落到按类名反射 `xin.dshaa.nativeapp.ui.shell.native.BuiltinNativeShell`
 *    （Kotlin object 的 `INSTANCE`），不产生编译期依赖。
 */
object NativeShells {

    /** 默认内置界面插件 id（`BuiltinPlugins.NATIVE_UI_SHELL` 与本常量同值）。 */
    const val BUILTIN_ID: String = "builtin.shell.native"

    private val providers: List<NativeShell> by lazy { loadProviders() }

    private fun loadProviders(): List<NativeShell> {
        val viaService = runCatching {
            ServiceLoader.load(NativeShellProvider::class.java, NativeShellProvider::class.java.classLoader)
                .map { it.create() }
                .toList()
        }.getOrDefault(emptyList())
        if (viaService.isNotEmpty()) return viaService
        return runCatching {
            val cls = Class.forName("xin.dshaa.nativeapp.ui.shell.native.BuiltinNativeShell")
            listOf(cls.getField("INSTANCE").get(null) as NativeShell)
        }.getOrDefault(emptyList())
    }

    fun byId(id: String?): NativeShell? = id?.let { key -> providers.firstOrNull { it.id == key } }

    /** 默认壳（内置 UI 插件）。是否启用由 `AppViewModel.nativeUiEnabled()` 判断。 */
    fun builtin(): NativeShell? = byId(BUILTIN_ID)

    /** 已注册的 native 壳（调试/插件页展示用）。 */
    fun all(): List<NativeShell> = providers

    /** M85-C2：所有已注册 native 壳声明的页面（宿主只用于校验/调试 dump）。 */
    fun pages(): List<ShellPage> = providers.flatMap { it.pages }

    /** M85-C2：把 `dsh_open_screen` 的字符串交给壳解释（第一张认领的壳胜出）。 */
    fun findPage(requested: String): ShellPage? =
        providers.firstNotNullOfOrNull { it.findPage(requested) }
}

package xin.dshaa.core.plugins

/**
 * P1（`docs/185` §四）：插件桥面到原语层的窄接口。
 *
 * 为什么是「方法名 + JSON」而不是给每个原语写一个 Kotlin 方法：原语目录在
 * `core:primitives`，三面共用同一份定义；插件侧只需要 `primitive.invoke(id, args)` /
 * `primitive.list()` 两个入口，新增原语不用改插件体系。
 *
 * 约定：
 * - 在插件 JS 的宿主线程上**同步**调用；实现方负责切到合适的调度器（不能阻塞主线程）；
 * - 返回完整 JSON（`{ok,summary,data,code}` / `{ok,primitives:[...]}`），与
 *   `PrimitiveBridgeJson` 同形；
 * - [PluginSandbox] 只做参数转发与超时/错误兜底，**不做第二份权限判断**；哪个原语对插件开放
 *   由 `core:primitives` 的 `PrimitiveDefinition.bridgeVisible` 决定。
 */
interface PluginPrimitiveBridge {
    /** 执行一个原语；[argsJson] 是参数对象的 JSON 文本。 */
    fun invoke(id: String, argsJson: String): String

    /** 列出原语目录（插件可发现能力用）。 */
    fun list(): String
}

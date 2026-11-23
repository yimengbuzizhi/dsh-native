package xin.dshaa.nativeapp.node

import kotlinx.serialization.json.JsonObject

/** 一个 Node 插件工具（参数 JSON Schema 原样保留，只做数据搬运）。 */
data class NodeToolSpec(
    val name: String,
    val description: String,
    val parameters: JsonObject,
)

/**
 * 当前挂到原生工具表上的 Node 工具（docs/187 §九 第 9 项要断言「摘除/恢复」的就是这张表）。
 *
 * 为什么单独抽出来：宿主 Ready → 换表；进程崩溃/EOF/协议错误/关开关 → 清空。
 * 纯 Kotlin（不碰 Android），JVM 用例直接复用生产逻辑，而不是在测试里另写一份。
 */
class NodeToolSurface {

    private val tools = LinkedHashMap<String, NodeToolSpec>()

    /** 用新 manifest 整体替换；返回替换后的快照（空列表 = 已摘除）。 */
    @Synchronized
    fun replace(specs: List<NodeToolSpec>): List<NodeToolSpec> {
        tools.clear()
        specs.forEach { tools[it.name] = it }
        return tools.values.toList()
    }

    /** 宿主失联 / 关开关：一个工具都不留（否则模型会一直调必然失败的工具）。 */
    @Synchronized
    fun clear(): List<NodeToolSpec> {
        tools.clear()
        return emptyList()
    }

    @Synchronized
    fun snapshot(): List<NodeToolSpec> = tools.values.toList()

    @Synchronized
    fun size(): Int = tools.size
}

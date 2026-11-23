package xin.dshaa.nativeapp.node

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * P3-c（`docs/187` §9.2）：Node 插件通过 `ctx.commands.register` 注册的斜杠命令。
 *
 * 原生这一侧只拿**无 handler 的描述**（跨进程不传函数）：名字、说明、输入提示。
 * 命中后由 [NodePluginHost.invokeCommand] 转发给宿主执行，审计沿用 P2 的
 * `files/node-audit.jsonl`（`host:"node"`、`tool="command:<name>"`）。
 *
 * 纯 Kotlin，JVM 用例锁死「名字必须合法」「未知命令要报出来」这两条。
 */
data class NodeCommandSpec(
    /** 不带斜杠的小写名字（与上游 `parseCommand()` 的语法一致）。 */
    val name: String,
    val description: String,
    /** 输入提示（输入框占位/面板副标题用）。 */
    val hint: String,
    /** 是否接受附件（P3 原生侧不支持带附件的命令提交，这里照实带出来）。 */
    val attachments: Boolean,
    val owner: String,
) {
    fun displayLabel(): String = "/$name"
}

/** 一次命令执行的结果（原生只需要「成功文本 / 失败文本」两种）。 */
sealed interface NodeCommandCall {
    val ms: Long

    data class Ok(
        val text: String,
        val commandId: String,
        override val ms: Long,
    ) : NodeCommandCall

    data class Failure(
        val error: String,
        val kind: String,
        override val ms: Long,
    ) : NodeCommandCall
}

object NodeCommands {

    /** 与 `core/input` 的 `parseCommandLine` 同一条语法：`^[a-z][a-z0-9_-]*$`。 */
    private val NAME = Regex("^[a-z][a-z0-9_-]{0,63}$")

    fun validName(name: String): Boolean = NAME.matches(name)

    private fun JsonObject.text(key: String): String = (this[key] as? JsonPrimitive)?.content.orEmpty()

    fun parse(raw: JsonObject): NodeCommandSpec? {
        // 不做 lowerCase：命令名必须**注册时就**是小写（与 `parseCommandLine` 的语法一致）。
        // 宿主侧（host.mjs）已经归一过；这里再自动降级会把 `/Hello` 这种永远匹配不到的写法藏起来。
        val name = raw.text("name").trim()
        if (!validName(name)) return null
        return NodeCommandSpec(
            name = name,
            description = raw.text("description"),
            hint = raw.text("hint"),
            attachments = raw.text("attachments").toBoolean(),
            owner = raw.text("owner"),
        )
    }

    /** 一批描述 → 去重按名字（后注册覆盖），按名字排序（面板顺序稳定）。 */
    fun parseAll(raw: List<JsonObject>): List<NodeCommandSpec> {
        val byName = LinkedHashMap<String, NodeCommandSpec>()
        raw.forEach { element ->
            parse(element)?.let { byName[it.name] = it }
        }
        return byName.values.sortedBy { it.name }
    }
}

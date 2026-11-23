package xin.dshaa.core.plugins

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * docs/187 §9.3 / docs/185 P4：插件包的**归属**（哪个宿主负责运行它）。
 *
 * 只描述「这个包是给谁写的」，不决定文件落在哪；原生安装把它写进 `PluginRecord.ownership`，
 * Node 安装写进 `node-host-plugins.json` 的条目，两边都只为「同一个 id 不许双装」和
 * 插件页分区服务。**不动 session.v4**。
 *
 * 判据（用户 2026-11-23 拍板）：
 * 1. `dependencies`（含 peer/optional，npm 真包常把 @deepseek-ai 写在 peerDependencies）里
 *    出现 `@deepseek-ai/...` → Node 包；
 * 2. `exports["./client"]` 存在 → 带 web client 的 DSH/Node 包；
 * 3. 其余 → 鲸歌原生包。
 *
 * 判错可以改：导入确认对话框允许用户把归属改成另一侧，再按同一 id 做迁移。
 */
object PluginOwnership {

    const val NATIVE = "native"
    const val NODE = "node"

    /** 稳定 id 的中文名；未知值原样返回（旧记录/手改 JSON 不崩）。 */
    fun label(id: String?): String = when (id) {
        NODE -> "Node"
        NATIVE -> "原生"
        else -> id?.takeIf { it.isNotBlank() } ?: "原生"
    }

    data class Detection(
        val ownership: String,
        /** 给用户看的判据（例如「dependencies 含 @deepseek-ai/cordis」）。空 = 按原生处理。 */
        val signals: List<String> = emptyList(),
        val hasClient: Boolean = false,
    )

    /** 从 DSH `package.json` 的 JSON 对象判断归属；Operit/声明式包没有这些字段 = 原生。 */
    fun detect(root: JsonObject): Detection {
        val signals = ArrayList<String>()
        listOf("dependencies", "peerDependencies", "optionalDependencies").forEach { section ->
            val deps = root[section] as? JsonObject ?: return@forEach
            val scoped = deps.keys.filter { it.startsWith("@deepseek-ai/") }.sorted()
            if (scoped.isNotEmpty()) {
                signals += "$section 含 " + scoped.take(3).joinToString("、") +
                    if (scoped.size > 3) " 等 ${scoped.size} 个 @deepseek-ai/*" else ""
            }
        }
        val exports = root["exports"] as? JsonObject
        val hasClientExport = exports?.containsKey("./client") == true
        if (hasClientExport) signals += "exports[\"./client\"]（有 web client）"
        val client = (root["dsh"] as? JsonObject)?.get("client")
        val hasDshClient = client != null
        if (hasDshClient && !hasClientExport) {
            val platform = ((client as? JsonObject)?.get("platform") as? JsonPrimitive)?.contentOrNull
            signals += "dsh.client" + if (platform.isNullOrBlank()) "" else "（platform=$platform）"
        }
        val node = signals.isNotEmpty()
        return Detection(
            ownership = if (node) NODE else NATIVE,
            signals = signals,
            hasClient = hasClientExport || hasDshClient,
        )
    }
}

package xin.dshaa.core.plugins

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * M83 阶段 1：插件服务容器（DSH `ctx.provide` / `ctx.inject` 的宿主侧实现）。
 *
 * 为什么需要「宿主侧」：鲸歌没有常驻 JS 运行时——每次试运行/工具调用都是一个新的 QuickJS 实例
 * （见 [PluginModule.invokeScript]）。所以服务不能是 JS 里互相持有的对象，而是：
 * 1. 插件 `ctx.provide(name, value)` 时把 value 的 JSON 快照记进 [PluginProducedService]（持久化到插件清单）；
 * 2. 插件 `ctx.inject([...], fn)` 时由宿主把「其他已启用插件提供的快照」注入回调；
 * 3. 依赖关系（谁提供、谁依赖、缺谁）是**纯数据**，插件页、启用/停用、重载都读同一份解析结果。
 *
 * 这样「关掉 A 后 B 报缺少服务 X」不需要跑 JS，也不会因为进程重启丢失。
 */
data class PluginProducedService(
    /** 服务名（DSH 里通常就是 `ctx.provide('memory', ...)` 的 'memory'）。 */
    val name: String,
    /** 值的 JSON 快照文本（`JSON.stringify(value)`；无法序列化时退化成 JSON 字符串）。 */
    val content: String,
)

/** 插件声明的一条依赖。 */
data class PluginDependencyRef(
    val name: String,
    val optional: Boolean = false,
)

/** 依赖图的一条边（插件页展示「依赖 X ← 提供者」）。 */
data class PluginServiceEdge(
    val name: String,
    val optional: Boolean,
    val providerId: String? = null,
    val providerName: String? = null,
    val satisfied: Boolean = false,
)

/** 一次依赖解析的结果；`records` 里所有**已启用**插件的视角。 */
data class PluginServiceResolution(
    /** 服务名 → 提供方（同名的最后一个启用插件生效，插件的启用顺序就是清单顺序）。 */
    val providers: Map<String, PluginServiceEntry> = emptyMap(),
    /** pluginId → 依赖边。 */
    val edges: Map<String, List<PluginServiceEdge>> = emptyMap(),
    /** pluginId → 缺失（非 optional 且没人提供）的服务名。 */
    val missing: Map<String, List<String>> = emptyMap(),
    /** 依赖排序后的已启用插件 id（提供者在前）；环里的插件保持原顺序并记进 [cycles]。 */
    val order: List<String> = emptyList(),
    /** 探测到的依赖环（pluginId 环成员；只记日志/展示，不阻塞启用）。 */
    val cycles: List<String> = emptyList(),
)

/** 服务提供方（解析结果里的条目，带提供者信息，插件页要显示「由谁提供」）。 */
data class PluginServiceEntry(
    val name: String,
    val ownerId: String,
    val ownerName: String,
    val content: String,
)

object PluginServices {

    /**
     * 从插件清单解析依赖图。只看向**已启用**的插件：停用的插件不提供服务，也不报缺依赖
     * （停用的插件不参与运行，插件页不把它标红）。
     */
    fun resolve(records: List<PluginRecord>): PluginServiceResolution {
        val enabled = records.filter { it.enabled }
        val providers = LinkedHashMap<String, PluginServiceEntry>()
        enabled.forEach { record ->
            record.provides.forEach { (name, content) ->
                providers[name] = PluginServiceEntry(name, record.id, record.name, content)
            }
        }
        val edges = LinkedHashMap<String, List<PluginServiceEdge>>()
        val missing = LinkedHashMap<String, List<String>>()
        enabled.forEach { record ->
            if (record.injects.isEmpty()) return@forEach
            val list = record.injects.map { dep ->
                val provider = providers[dep.name]
                PluginServiceEdge(
                    name = dep.name,
                    optional = dep.optional,
                    providerId = provider?.ownerId,
                    providerName = provider?.ownerName,
                    satisfied = provider != null || dep.optional,
                )
            }
            edges[record.id] = list
            val absent = list.filter { !it.satisfied }.map { it.name }
            if (absent.isNotEmpty()) missing[record.id] = absent
        }
        return PluginServiceResolution(
            providers = providers,
            edges = edges,
            missing = missing,
            order = topologicalOrder(enabled),
            cycles = findCycleMembers(enabled),
        )
    }

    /**
     * 依赖排序：提供者排在依赖它的插件前面。只按「已启用插件之间」的边排序；
     * 环里的节点按清单原顺序保留（不因为环让插件整个不跑）。
     */
    fun topologicalOrder(records: List<PluginRecord>): List<String> {
        val enabled = records.filter { it.enabled }
        val byId = enabled.associateBy { it.id }
        val providerOf = HashMap<String, String>()
        enabled.forEach { record -> record.provides.keys.forEach { providerOf[it] = record.id } }
        val deps: Map<String, Set<String>> = enabled.associate { record ->
            record.id to record.injects.mapNotNull { providerOf[it.name] }.filter { it != record.id }.toSet()
        }
        val result = ArrayList<String>(enabled.size)
        val visited = HashSet<String>()
        val visiting = HashSet<String>()
        fun visit(id: String) {
            if (!visited.add(id)) return
            if (!visiting.add(id)) return
            deps[id].orEmpty().forEach { dep -> if (byId.containsKey(dep)) visit(dep) }
            visiting.remove(id)
            result += id
        }
        enabled.forEach { visit(it.id) }
        // 环成员可能一次都没被加入（visit 提前返回）：按原顺序补齐，保证不漏插件。
        enabled.forEach { if (it.id !in result) result += it.id }
        return result
    }

    /** 找依赖环成员（只为展示/日志；Kahn 剥不掉的就是环里的）。 */
    fun findCycleMembers(records: List<PluginRecord>): List<String> {
        val enabled = records.filter { it.enabled }
        val providerOf = HashMap<String, String>()
        enabled.forEach { record -> record.provides.keys.forEach { providerOf[it] = record.id } }
        val deps = enabled.associate { record ->
            record.id to record.injects.mapNotNull { providerOf[it.name] }.filter { it != record.id }.toSet()
        }
        val indegree = enabled.associate { it.id to deps[it.id].orEmpty().size }.toMutableMap()
        val queue = ArrayDeque(enabled.map { it.id }.filter { indegree[it] == 0 })
        while (queue.isNotEmpty()) {
            val id = queue.removeFirst()
            enabled.forEach { other ->
                if (id in deps[other.id].orEmpty()) {
                    val left = (indegree[other.id] ?: 0) - 1
                    indegree[other.id] = left
                    if (left == 0) queue += other.id
                }
            }
        }
        return indegree.filterValues { it > 0 }.keys.toList()
    }

    /** 宿主侧快照：`服务名 -> JSON 文本`，给 JS 的 `ctx.inject` 用。 */
    fun snapshot(records: List<PluginRecord>, excludingOwner: String? = null): Map<String, String> {
        val map = LinkedHashMap<String, String>()
        records.filter { it.enabled && it.id != excludingOwner }.forEach { record ->
            record.provides.forEach { (name, content) -> map[name] = content }
        }
        return map
    }
}

/**
 * JS `ctx.provide / ctx.inject / ctx.effect / ctx.on / ctx.plugin` 的宿主调用处理。
 *
 * 只做数据登记（不执行插件代码）：
 * - `plugin.services.values`：给一次 apply 的 inject 取快照；
 * - `plugin.services.provide`：本次 apply 里新 provide 的服务（同一次 apply 内后面的 inject 能看到）；
 * - `plugin.services.effect` / `plugin.services.on`：登记清理器/事件订阅（当前只展示，见 [PluginCapability]）。
 *
 * 不在宿主里做权限判断：服务是插件之间的事，能力清单里标成 `services` 一类，安装时的清单声明
 * 仍然按 [PluginPermissions] 走（M83 起 `services` 不额外要权限，但插件页会显示依赖图）。
 */
class PluginServiceHost(
    /** 当前可用服务快照（服务名 → JSON 文本）；null = 这一次 apply 拿不到别的插件（例如试运行时还没启用）。 */
    private val initial: Map<String, String> = emptyMap(),
    /** 记录本次 apply 里新 provide 的服务（宿主用来判断同名覆盖/调试）。 */
    private val onProvide: (String, String) -> Unit = { _, _ -> },
) {
    private val services = LinkedHashMap<String, String>(initial)

    fun callSync(method: String, payloadJson: String): String? = when (method) {
        "plugin.services.values" -> values(payloadJson)
        "plugin.services.provide" -> provide(payloadJson)
        "plugin.services.effect" -> ok()
        "plugin.services.on" -> ok()
        "plugin.log" -> """{"ok":true}"""
        else -> null
    }

    private fun values(payloadJson: String): String {
        val obj = runCatching { Json.parseToJsonElement(payloadJson).jsonObject }.getOrNull()
            ?: return """{"ok":false,"error":"bad payload"}"""
        val names = runCatching {
            obj["names"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty()
        }.getOrDefault(emptyList())
        val values = buildJsonObject {
            names.forEach { name ->
                services[name]?.let { put(name, JsonPrimitive(it)) }
            }
        }
        val missing = names.filter { !services.containsKey(it) }
        return buildJsonObject {
            put("ok", true)
            put("values", values)
            put("missing", buildJsonArray { missing.forEach { add(JsonPrimitive(it)) } })
        }.toString()
    }

    private fun provide(payloadJson: String): String {
        val obj = runCatching { Json.parseToJsonElement(payloadJson).jsonObject }.getOrNull()
            ?: return """{"ok":false,"error":"bad payload"}"""
        val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: return """{"ok":false,"error":"missing name"}"""
        val value = obj["value"]?.jsonPrimitive?.contentOrNull ?: "null"
        services[name] = value
        onProvide(name, value)
        return """{"ok":true}"""
    }

    private fun ok(): String = """{"ok":true}"""
}

/**
 * 宿主能力清单（M83 阶段 1）：**能交给插件的原语**登记成目录，插件页可查。
 *
 * 这是「底盘有多大」的自检表：每加一个原语就在这里登记一条（id、要什么权限、是否已实现、
 * 对应哪个 JS API），插件页展示；以后阶段 2/3 每开一个原语也在这里补一条。
 */
data class PluginCapability(
    /** 稳定 id（插件页查询、以后插件可以声明 requires 用）。 */
    val id: String,
    /** 中文标题。 */
    val title: String,
    /** 对应 JS API 写法。 */
    val api: String,
    /** 需要的权限（null = 不需要额外授权）。 */
    val permission: String? = null,
    /** 宿主是否已经实现（false = 只登记，调了会返回 unsupported）。 */
    val implemented: Boolean = true,
    /** 一句话说明 / 已知限制。 */
    val note: String = "",
)

object PluginCapabilities {
    const val VERSION = "m86c"

    val all: List<PluginCapability> = listOf(
        PluginCapability("tools.register", "注册工具", "ctx.tools.register({name, description, parameters, execute})", note = "工具进模型 tools[]，名冲突时先注册的生效"),
        PluginCapability("tools.names", "查询已注册工具名", "ctx.tools.names()"),
        PluginCapability("prompt.section", "注册提示词段", "ctx.systemPrompt.section({name, order, text})", note = "按 order 拼进 system；同名段插件覆盖"),
        PluginCapability("prompt.context", "注册动态上下文", "ctx.systemPrompt.context({name, order, text})", note = "段文本按 apply 时求值并保存快照，不是每请求回调"),
        PluginCapability("prompt.variable", "注册提示词变量", "ctx.systemPrompt.variable(name, fn|value)", note = "段文本里的 {{name}} 用它替换"),
        PluginCapability("prompt.suppressRuntimeContext", "抑制运行时上下文", "ctx.systemPrompt.suppressRuntimeContext()", note = "启用期间宿主不再注入 runtime context 快照；插件自己的 systemPrompt.context 仍注入"),
        PluginCapability("commands.register", "注册斜杠命令", "ctx.commands.register({name, description, handler})"),
        PluginCapability("services.provide", "提供服务", "ctx.provide(name, value)", note = "值的 JSON 快照进插件清单，其他插件可 inject"),
        PluginCapability("services.inject", "依赖注入", "ctx.inject(['name'], ctx => {})", note = "缺依赖时插件页标红，回调不执行"),
        PluginCapability("lifecycle.effect", "生命周期清理器", "ctx.effect(() => cleanup)", note = "常驻 JS 运行时：插件停用/重载/删除时真的回调 cleanup（异步 cleanup 不阻塞，异常记日志）"),
        PluginCapability("lifecycle.on", "事件订阅", "ctx.on('turn/start'|'session/event'|'*', handler)", note = "常驻 JS 运行时：会话事件 dispatch 给 handler，参数 (event, {name,sessionId,header})；通用 session/event 与 * 都能收"),
        PluginCapability("compose.plugin", "组合子插件", "ctx.plugin(subPlugin, config)", note = "JS 侧内联 apply，不新建插件记录"),
        PluginCapability("notify", "系统通知", "ctx.whale.notify({title, body}) / new Notification()", permission = "notify"),
        PluginCapability("fs.workspace", "读写工作区", "ctx.fs / node:fs（相对路径按工作区）", permission = "fs:workspace"),
        PluginCapability("fs.any", "读写任意路径", "ctx.fs / node:fs（绝对路径）", permission = "fs:any"),
        PluginCapability("net.fetch", "HTTP 请求", "ctx.http / fetch", permission = "net:*"),
        PluginCapability("shell.exec", "执行命令", "ctx.shell / node:child_process", permission = "shell"),
        PluginCapability("device.control", "设备控制", "ctx.device（无障碍/应用/设置）", permission = "device"),
        PluginCapability("ocr", "OCR 文字识别", "ctx.whale.ocr(path)", permission = "device", note = "ML Kit 中文+拉丁（屏幕圈选 OCR 同引擎）；图片路径还要过 fs 权限"),
        PluginCapability("zstd", "zstd 压缩/解压", "ctx.whale.zstd({mode, path, out, level})", permission = "fs:workspace", note = "zstd-jni 流式；路径走 fs 授权"),
        PluginCapability("memory", "记忆库", "ctx.memory", permission = "memory"),
        PluginCapability("ui.route", "注册页面路由", "ctx.ui.registerRoute({id|path, title, description, page})", note = "元数据进插件记录；page:true = 整页插件页面；点开跑插件自己的 dsh.client"),
        PluginCapability("ui.page", "注册整页", "ctx.ui.registerPage({id|path, title, description})", note = "整页由插件 client.js 提供；id 与宿主内置页同名时替换该页（停用插件恢复内置页）"),
        PluginCapability("ui.view", "注册视图（预留项）", "ctx.ui.registerView(...)", permission = "ui", implemented = false, note = "真实 DSH 包（app.asar）里搜不到 ui.registerView/registerView，这不是上游 API；保留为未实现的预留项，要做视图扩展时再定鲸歌自有协议（别假装已实现）"),
        PluginCapability("llm.register", "注册模型供应商", "ctx.llm.register({id, label, protocol, baseUrl, apiKey, model, stream?})", note = "无 stream = 配置式（宿主包装成内置 Anthropic/OpenAI 通道）；带 stream = 插件在常驻运行时里自己产出模型回合：stream(request, emit) 里 emit('片段') 是逐 token 文本，返回 {text, toolCalls, stopReason, usage} 可让模型发起工具调用（宿主折成 tool_use 块，走宿主工具回合与工具卡）"),
        PluginCapability("session.projections", "会话投影", "ctx.sessionProjections.register({key, stateVersion, init, apply, wire})", note = "常驻 JS 运行时：宿主每提交一条会话事件就回调 apply 折叠一次，wire.view 的结果落盘可查；apply/init/view 必须同步"),
        PluginCapability("client.ui", "Web UI 插件（dsh.client）", "package.json → dsh.client", note = "独立 WebView 窗口，插件自己的前端"),
        PluginCapability("ui.loading", "注册启动加载页", "ctx.ui.registerLoading({id,label,tree})", permission = "ui", note = "loading/v1：静态受限 Surface 快照，冷启动不运行 JS；只读 boot.phase/message/elapsed，无输入、网络或宿主动作"),
        PluginCapability("ui.shell", "注册界面壳", "ctx.ui.registerShell({id,label,backend,mount,onState,onAction,onEvent})", permission = "ui", note = "shell/v1：Surface 树或 WebView HTML；启用与选中分开，支持离屏数据验证"),
        PluginCapability("agent.register", "注册回合", "ctx.agent.register({id,label,run})", permission = "agent", note = "agent/v1：纯文本回合；notify/status/abort/tools.names 可用，tools.invoke/llm.stream/storage.append 仍 UNSUPPORTED"),
        PluginCapability("storage.register", "注册只读存储", "ctx.storage.register({id,label,list,load})", permission = "storage", note = "storage/v1 只能浏览；exportList/exportLoad 只读导出内置会话"),
        PluginCapability("prompt.register", "注册提示词实现", "ctx.systemPrompt.register({id,label,assemble})", permission = "prompt", note = "prompt/v1：assemble(req) 返回完整 system，req.base 为内置拼装结果"),
        PluginCapability("capability.provide", "注册能力提供方", "ctx.capability.register({id, label, description, kind, invoke})", permission = "capability", note = "capability/v1：root/NFC/Shizuku/adb-shell 等特权能力只能由插件提供，宿主只登记 + 权限门 + 超时；同 id 插件优先于内置平台能力，停用/缺授权如实报「不可用」（插件页/调试通道调用）"),
    )

    fun byId(id: String): PluginCapability? = all.firstOrNull { it.id == id }

    /** 插件实际用到、但宿主没实现的能力（插件页提示）。 */
    fun unsupported(ids: List<String>): List<PluginCapability> =
        ids.mapNotNull { byId(it) }.filter { !it.implemented }
}

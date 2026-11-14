package xin.dshaa.core.plugins

/**
 * 插件能力（manifest 的 `permissions` 数组，M28 / docs/04 §七）。
 *
 * 设计原则和 docs/04 一致：**危险能力不能静默获得**。插件只能在清单里「申请」，
 * 是否真的生效由 [PluginPermissions] 的授权表（`files/plugins.json` 的 `grantedPermissions`）决定：
 *
 * | 权限 | 范围 | 默认 |
 * |---|---|---|
 * | `fs:workspace` | 当前工作区（路径逃逸会被拒） | 导入即允许 |
 * | `fs:any` | App 能访问的任意路径 | 需在插件页确认 |
 * | `net:<host>` | 指定域名 + 子域名，仅 https | 需确认 |
 * | `net:any` | 任意 https 域名（本机/内网默认拒绝） | 需确认 |
 * | `shell` | 以 App 身份执行 shell | 需确认（红框） |
 * | `device` | 设备信息 / 应用 / 无障碍手势 / 截图 | 需确认（M67 起实现） |
 * | `memory` | 读写鲸歌记忆库（M68） | 需确认 |
 * | `ui` | 注册整壳（`ctx.ui.registerShell`，M85-A 起生效） | 导入即允许 |
 * | `agent` | 注册回合实现（`ctx.agent.register`，M85-D 起生效） | 导入即允许（选中才生效） |
 * | `storage` | 注册存储实现（`ctx.storage.register`，M85-E 起生效；v1 只读） | 导入即允许（选中才生效） |
 * | `prompt` | 注册提示词实现（`ctx.systemPrompt.register`，M85-E 起生效） | 导入即允许（选中才生效） |
 * | `capability` | 注册能力提供方（`ctx.capability.register`，M85-F 起生效；调用于插件页/调试） | 导入即允许（调用才生效） |
 * | `notify` | 发系统通知 | 本版尚未实现 |
 *
 * 认不出的写法一律 [PluginPermissionKind.UNKNOWN]，**不猜、不授予**（宁可不支持，也不放大权限）。
 */
enum class PluginPermissionKind(val title: String) {
    FS_WORKSPACE("文件（工作区）"),
    FS_ANY("文件（任意路径）"),
    NET_HOST("网络（指定域名）"),
    NET_ANY("网络（任意域名）"),
    SHELL("执行命令"),
    UI("界面扩展"),
    AGENT("回合扩展"),
    STORAGE("存储扩展"),
    PROMPT("提示词扩展"),
    CAPABILITY("能力扩展"),
    NOTIFY("通知"),
    DEVICE("设备控制"),
    MEMORY("记忆库"),
    UNKNOWN("未识别"),
}

/** 一条规范化后的权限。 [raw] 是可持久化、可比较的规范写法（如 `net:api.example.com`）。 */
data class PluginPermission(
    val raw: String,
    val kind: PluginPermissionKind,
    val target: String? = null,
) {
    /** 需要用户确认的能力。 */
    val dangerous: Boolean
        get() = when (kind) {
            PluginPermissionKind.FS_ANY,
            PluginPermissionKind.NET_HOST,
            PluginPermissionKind.NET_ANY,
            PluginPermissionKind.SHELL,
            PluginPermissionKind.NOTIFY,
            PluginPermissionKind.DEVICE,
            PluginPermissionKind.MEMORY,
            -> true
            else -> false
        }

    /** 导入时自动允许（docs/04：fs:workspace、ui、agent、storage、prompt 默认允许；真正生效还要用户主动选中）。 */
    val autoGranted: Boolean
        get() = kind == PluginPermissionKind.FS_WORKSPACE ||
            kind == PluginPermissionKind.UI ||
            kind == PluginPermissionKind.AGENT ||
            kind == PluginPermissionKind.STORAGE ||
            kind == PluginPermissionKind.PROMPT ||
            kind == PluginPermissionKind.CAPABILITY

    /** 这个版本真的实现了的能力；其余只展示、不生效，避免「给了权限但什么也没发生」的误解。 */
    val implemented: Boolean
        get() = when (kind) {
            PluginPermissionKind.FS_WORKSPACE,
            PluginPermissionKind.FS_ANY,
            PluginPermissionKind.NET_HOST,
            PluginPermissionKind.NET_ANY,
            PluginPermissionKind.SHELL,
            PluginPermissionKind.DEVICE,
            PluginPermissionKind.MEMORY,
            // M85-A：`ui` 真的生效了——`ctx.ui.registerShell` 的整壳可由用户选为当前界面。
            PluginPermissionKind.UI,
            // M85-D：`agent` 生效——`ctx.agent.register` 的回合实现可被 `dsh_agent` 选中。
            PluginPermissionKind.AGENT,
            // M85-E：`storage` / `prompt` 生效——注册的存储/提示词实现可被选中（v1 存储只读）。
            PluginPermissionKind.STORAGE,
            PluginPermissionKind.PROMPT,
            // M85-F：`capability` 生效——`ctx.capability.register` 的能力可被插件页/调试通道调用
            //（没有提供方时如实报「不可用」）。
            PluginPermissionKind.CAPABILITY,
            -> true
            else -> false
        }

    /** 界面上的短标题（网络权限带上域名）。 */
    val title: String
        get() = if (kind == PluginPermissionKind.NET_HOST) "网络（${target.orEmpty()}）" else kind.title

    /** 红框确认里给用户看的一句话解释（docs/04：红框 + 原文 + 点击确认）。 */
    val explain: String
        get() = when (kind) {
            PluginPermissionKind.FS_WORKSPACE ->
                "读写当前工作区里的文件。相对路径按工作区解析，`..` 和指向外面的软链接会被拒绝。" +
                    "注意：如果当前工作区本身就是共享存储根（/sdcard），这等于整块手机存储。"
            PluginPermissionKind.FS_ANY ->
                "读写 App 能访问的任意路径，包括 DSH 自己的数据目录（会话、设置、令牌文件所在位置）。除非确有必要，不要授权。"
            PluginPermissionKind.NET_HOST ->
                "用 https 访问 ${target.orEmpty()} 及其子域名。插件能把要发送的内容（可能包含工作区文件）发到这个域名。"
            PluginPermissionKind.NET_ANY ->
                "用 https 访问任意域名。本机/内网地址（127.0.0.1、10.x、192.168.x 等）默认仍然拒绝。"
            PluginPermissionKind.SHELL ->
                "以 App 身份执行 shell 命令（不是 root）。命令能读写 App 能访问的一切，风险最高。"
            PluginPermissionKind.UI -> "注册插件自己的整壳（Shell）作为主界面：界面由插件返回的 UI 树渲染，" + "能看到当前会话的消息与状态、能发消息/停止/切会话（M85-A 起生效）。"
            PluginPermissionKind.AGENT ->
                "注册自己的回合实现（agent/v1）：被选中后，每条消息的回合循环由这个插件跑。" +
                    "插件能看到用户输入与会话状态，并通过宿主原语发通知/改状态（M85-D 起生效；" +
                    "选中它需要在回合插件设置里显式切换，停用即回安全提示）。"
            PluginPermissionKind.STORAGE ->
                "注册自己的会话存储（storage/v1，v1 只读导出）：被选中后，会话列表/打开由这个插件提供；" +
                    "插件能读到导出的会话摘要与内容，但不能新建或写入（M85-E 起生效；需显式选中，停用即回安全提示）。"
            PluginPermissionKind.PROMPT ->
                "注册自己的系统提示词实现（prompt/v1）：被选中后，每轮 system 由这个插件拼装/改写；" +
                    "插件能看到内置拼装结果与模型/工具信息（M85-E 起生效；需显式选中，停用即回内置提示词）。"
            PluginPermissionKind.CAPABILITY ->
                "注册自己的能力提供方（capability/v1，例如 NFC / root / Shizuku / ADB shell）：" +
                    "能力只在你显式调用时执行，宿主负责登记、权限门与超时；" +
                    "没有提供方时如实报「不可用」，宿主不写死任何特权能力（M85-F 起生效）。"
            PluginPermissionKind.NOTIFY -> "发系统通知（本版还没实现，授权也不会生效）。"
            PluginPermissionKind.DEVICE ->
                "读设备信息与应用列表、启动/停止应用、读系统设置、用无障碍点按/滑动/截图（M67 起生效）。" +
                    "不会给 root/Shizuku，也不会静默安装应用。"
            PluginPermissionKind.MEMORY ->
                "读写鲸歌记忆库：新增/修改/删除记忆、移动文件夹、建立/修改/删除记忆之间的链接（M68 起生效）。" +
                    "记忆库里有用户自己存的内容，授权前请确认这个插件可信。"
            PluginPermissionKind.UNKNOWN -> "认不出的权限写法，不会被授予。"
        }
}

/** 文件权限档位。 */
enum class PluginFsMode { NONE, WORKSPACE, ANY }

/**
 * 权限解析 / 授权判断。
 *
 * 这个对象刻意不依赖 Android，纯 JVM 单测覆盖所有分支；宿主（[PluginSandbox]）只调它的判断函数。
 */
object PluginPermissions {

    const val FS_WORKSPACE = "fs:workspace"
    const val FS_ANY = "fs:any"
    const val NET_ANY = "net:any"
    const val SHELL = "shell"
    const val UI = "ui"
    const val AGENT = "agent"
    const val STORAGE = "storage"
    const val PROMPT = "prompt"
    const val CAPABILITY = "capability"
    const val NOTIFY = "notify"
    const val DEVICE = "device"
    const val MEMORY = "memory"

    private val HOST_PATTERN =
        Regex("""^[a-z0-9](?:[a-z0-9-]*[a-z0-9])?(?:\.[a-z0-9](?:[a-z0-9-]*[a-z0-9])?)*$""")

    /** 解析一条 manifest 权限；空串/空白返回 null。 */
    fun parse(raw: String?): PluginPermission? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return null
        val lower = text.lowercase()
        return when {
            lower == "fs" || lower == FS_WORKSPACE || lower == "fs:read" ->
                PluginPermission(FS_WORKSPACE, PluginPermissionKind.FS_WORKSPACE)
            lower == FS_ANY || lower == "fs:*" ->
                PluginPermission(FS_ANY, PluginPermissionKind.FS_ANY)
            lower == "ui" || lower == "ui:register" ->
                PluginPermission(UI, PluginPermissionKind.UI)
            // M85-D：回合契约只接受规范的 `agent` 写法（`agent:run` 也认，保持和文档一致）。
            lower == AGENT || lower == "agent:run" ->
                PluginPermission(AGENT, PluginPermissionKind.AGENT)
            // M85-E：存储/提示词角色（v1 存储只读；`storage:read` 与 `storage` 等价）。
            lower == STORAGE || lower == "storage:read" || lower == "storage:rw" || lower == "session" ->
                PluginPermission(STORAGE, PluginPermissionKind.STORAGE)
            lower == PROMPT || lower == "prompt:assemble" || lower == "system-prompt" ->
                PluginPermission(PROMPT, PluginPermissionKind.PROMPT)
            // M85-F：能力提供方（capability/v1）；`capability:provide` 与 `capability` 等价。
            lower == CAPABILITY || lower == "capability:provide" || lower == "capabilities" ->
                PluginPermission(CAPABILITY, PluginPermissionKind.CAPABILITY)
            lower == NOTIFY || lower == "notification" ->
                PluginPermission(NOTIFY, PluginPermissionKind.NOTIFY)
            lower == DEVICE || lower.startsWith("device:") ->
                PluginPermission(DEVICE, PluginPermissionKind.DEVICE)
            lower == MEMORY || lower == "memory:rw" || lower == "memory:write" ->
                PluginPermission(MEMORY, PluginPermissionKind.MEMORY)
            lower == SHELL || lower == "shell:exec" || lower == "exec" ->
                PluginPermission(SHELL, PluginPermissionKind.SHELL)
            lower == NET_ANY || lower == "net:*" ->
                PluginPermission(NET_ANY, PluginPermissionKind.NET_ANY)
            lower.startsWith("net:") -> parseHost(lower.removePrefix("net:"), text)
            else -> PluginPermission(text, PluginPermissionKind.UNKNOWN)
        }
    }

    private fun parseHost(fragment: String, original: String): PluginPermission {
        var host = fragment.trim().trimEnd('.')
        if (host.isEmpty()) return PluginPermission(original, PluginPermissionKind.UNKNOWN)
        // 只接受裸域名；带 scheme/路径/端口/用户名的一律不认（避免「按主机授权」被理解成整条 URL）。
        if (host.contains('/') || host.contains('@') || host.contains(':') || host.contains(' ')) {
            return PluginPermission(original, PluginPermissionKind.UNKNOWN)
        }
        if (!HOST_PATTERN.matches(host) || host.contains("..")) {
            return PluginPermission(original, PluginPermissionKind.UNKNOWN)
        }
        return PluginPermission("net:$host", PluginPermissionKind.NET_HOST, host)
    }

    /** 解析整个数组：去重（按规范写法），保持顺序；未知写法保留给界面展示。 */
    fun parseAll(raws: List<String>): List<PluginPermission> {
        val out = ArrayList<PluginPermission>(raws.size)
        val seen = HashSet<String>()
        raws.forEach { raw ->
            val permission = parse(raw) ?: return@forEach
            if (seen.add(permission.raw)) out += permission
        }
        return out
    }

    /** 导入时自动授予的权限（只有非危险且已实现的能力才会自动给）。 */
    fun autoGrants(permissions: List<PluginPermission>): Set<String> =
        permissions.filter { it.autoGranted && it.implemented }.map { it.raw }.toSet()

    /** 需要用户逐项确认的权限。 */
    fun approvalNeeded(permissions: List<PluginPermission>): List<PluginPermission> =
        permissions.filter { it.dangerous && it.implemented }

    /**
     * 规范化授权表：只保留「清单里申请过 + 已实现」的项；`fs:any` 天然覆盖 `fs:workspace`。
     * 这样即使 `plugins.json` 被手改，也不会有清单外的权限生效。
     */
    fun sanitizeGrants(permissions: List<PluginPermission>, granted: List<String>): Set<String> {
        val requested = permissions.map { it.raw }.toSet()
        val out = LinkedHashSet<String>()
        granted.forEach { raw ->
            val normalized = parse(raw)?.raw ?: return@forEach
            if (normalized !in requested) return@forEach
            val permission = parse(normalized) ?: return@forEach
            if (!permission.implemented) return@forEach
            out += normalized
        }
        if (FS_ANY in out) out += FS_WORKSPACE
        return out
    }

    fun isGranted(permission: PluginPermission, granted: Collection<String>): Boolean {
        if (permission.kind == PluginPermissionKind.UNKNOWN || !permission.implemented) return false
        val set = granted.map { it.trim().lowercase() }.toSet()
        return permission.raw.lowercase() in set ||
            (permission.kind == PluginPermissionKind.FS_WORKSPACE && FS_ANY in set)
    }

    fun fsMode(granted: Collection<String>): PluginFsMode {
        val set = granted.map { it.trim().lowercase() }.toSet()
        return when {
            FS_ANY in set -> PluginFsMode.ANY
            FS_WORKSPACE in set -> PluginFsMode.WORKSPACE
            else -> PluginFsMode.NONE
        }
    }

    fun shellGranted(granted: Collection<String>): Boolean =
        SHELL in granted.map { it.trim().lowercase() }

    fun netAnyGranted(granted: Collection<String>): Boolean =
        NET_ANY in granted.map { it.trim().lowercase() }

    fun memoryGranted(granted: Collection<String>): Boolean =
        MEMORY in granted.map { it.trim().lowercase() }

    /**
     * 工作区是不是「共享存储根 / 整个存储」这种宽边界。
     *
     * 是的话 `fs:workspace` 实际上等于整块手机存储（本机默认工作区就是 `/storage/emulated/0`），
     * 插件页要给用户明确提示，不能让人以为只是一个小项目目录。
     */
    fun isBroadStorageRoot(path: String): Boolean {
        val clean = path.trim().trimEnd('/').lowercase()
        if (clean.isEmpty()) return true
        return clean == "/" ||
            clean == "/sdcard" ||
            clean == "/mnt/sdcard" ||
            clean == "/storage" ||
            clean == "/storage/emulated" ||
            clean == "/storage/self/primary" ||
            Regex("""^/storage/emulated/\d+$""").matches(clean)
    }

    /**
     * 域名是否被允许：`net:<host>` 覆盖该主机和它的子域名（`api.example.com` 也允许
     * `cdn.api.example.com`）；`net:any` 允许所有主机。
     */
    fun netAllows(granted: Collection<String>, host: String): Boolean {
        val target = host.trim().trimEnd('.').lowercase()
        if (target.isEmpty()) return false
        val set = granted.map { it.trim().lowercase() }
        if (NET_ANY in set) return true
        return set.any { raw ->
            if (!raw.startsWith("net:") || raw == NET_ANY) return@any false
            val allowed = raw.removePrefix("net:").trimEnd('.')
            allowed.isNotEmpty() && (target == allowed || target.endsWith(".$allowed"))
        }
    }

    /** 本机 / 链路本地地址：任何权限都不允许（防插件打自己或内网控制面）。 */
    fun isLoopbackOrLocal(host: String): Boolean {
        val h = host.trim().lowercase().removeSurrounding("[", "]")
        if (h == "localhost" || h.endsWith(".localhost")) return true
        if (h == "::1" || h == "0:0:0:0:0:0:0:1" || h == "::" ) return true
        if (h == "0.0.0.0") return true
        val v4 = h.split('.')
        if (v4.size == 4) {
            val a = v4[0].toIntOrNull() ?: return false
            val b = v4[1].toIntOrNull() ?: return false
            if (a == 127 || a == 0) return true
            if (a == 169 && b == 254) return true
        }
        return false
    }

    /** 内网地址：只有 `net:any` 才允许（`net:<host>` 明确写了内网域名/IP 也仍然拦）。 */
    fun isPrivate(host: String): Boolean {
        val h = host.trim().lowercase().removeSurrounding("[", "]")
        val v4 = h.split('.')
        if (v4.size != 4) return false
        val a = v4[0].toIntOrNull() ?: return false
        val b = v4[1].toIntOrNull() ?: return false
        return a == 10 ||
            (a == 172 && b in 16..31) ||
            (a == 192 && b == 168) ||
            (a == 100 && b in 64..127)
    }
}

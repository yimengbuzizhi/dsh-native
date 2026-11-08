package xin.dshaa.core.tools

import kotlinx.serialization.json.JsonObject
import xin.dshaa.core.model.ImageRef
import java.io.File

/**
 * 工具接口：与上游 `dsh-tools` 同形的「名字 + schema + 执行」。
 * schema 不在这里硬编码——由 `:core:prompt` 的版本化 tools.json 提供，
 * 这里只负责实现（契约 #2 的 schema 一致 + 契约 #4 的行为一致）。
 */
interface DshTool {
    val name: String
    suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult
}

/** `ask_user_question` 的一条问题（字段与 tools.json 的 schema 对齐）。 */
data class AskQuestion(
    val id: String,
    val question: String,
    val header: String? = null,
    val options: List<AskOption> = emptyList(),
    val multiSelect: Boolean = false,
    /** 长正文（目前只有计划审批用：完整 plan markdown；`ask_user_question` 不填）。 */
    val detail: String? = null,
)

data class AskOption(val label: String, val description: String? = null)

/** 用户对一条问题的回答：selected 为空且 custom 为空 = 跳过。 */
data class AskAnswer(val id: String, val selected: List<String> = emptyList(), val custom: String = "")

data class ToolContext(
    /**
     * 工具的可写根目录（= 会话 header.cwd = 当前工作区）。
     * 2026-11-07 起是 `var`：手机版支持切换工作区，App 会原地重绑（引擎持有同一个对象，
     * 子代理用 `copy(workspace=…)` 覆盖，互不影响）。
     */
    var workspace: File,
    val timeoutMs: Long = 60_000,
    /** 允许写工作区之外（默认 false，等价于 workspace-write 沙箱）。 */
    val allowOutsideWorkspace: Boolean = false,
    /** 会话内的工具状态（todo 等）。 */
    val state: MutableMap<String, Any?> = mutableMapOf(),
    /** `working_directory` 改的当前目录；相对路径按它解析，但逃逸检查仍以 [workspace] 为根。 */
    var cwd: File = workspace,
    /** `ask_user_question` 的 UI 通道：由 App 层挂上，返回用户选择。 */
    val askUser: (suspend (List<AskQuestion>) -> List<AskAnswer>)? = null,
    /** goal 工具的后端：由 App 层挂到当前会话（null 时 goal 工具报不可用）。 */
    val goalHost: GoalHost? = null,
    /** 会话内提醒（schedule_*）的后端：由 App 层挂到当前会话（null 时工具报不可用）。 */
    val scheduleHost: ScheduleHost? = null,
    /** `web_search` 的后端：由 App 层挂上（null 时工具报不可用）。 */
    val webSearchHost: WebSearchHost? = null,
    /** `read_image` 的附件存储：由 App 层挂上（null 时工具报「no attachment service is mounted」）。 */
    val attachments: AttachmentHost? = null,
    /** `read_image` 的模型能力门（上游 assertImageCapableRoute）；null = 路由无法解析。 */
    var imageRoute: ImageRoute? = null,
    /** 后台任务表（`bash` 的 `run_in_background` 与 `job_*` 三件套）；null = 本部署不支持后台任务。 */
    val jobs: xin.dshaa.core.jobs.JobRegistry? = null,
    /** 当前会话 id（后台任务的 owner：list/kill 只看自己会话的 job；null = 无 owner）。 */
    val jobOwner: () -> String? = { null },
    /** 子代理运行时（`subagent`/`subagent_fork`/`list_agents`/`send_message`/`interrupt_agent`）；null = 未挂载。 */
    val agents: AgentHost? = null,
    /** 计划模式后端（`exit_plan_mode`）；null = 本组合没有计划模式（如子代理会话）。 */
    val planMode: PlanModeHost? = null,
    /** 技能目录/加载后端（`skill` 工具，M19）；null = 本组合没有技能系统。切换工作区时重绑。 */
    var skills: xin.dshaa.core.skill.SkillHost? = null,
    /** `workflow` 的运行时（QuickJS + 子代理 + 后台 job）；null = 本组合不支持 workflow。 */
    val workflowHost: xin.dshaa.core.tools.workflow.WorkflowHost? = null,
)

/**
 * `exit_plan_mode` 的后端：由 App 层绑定当前会话的 `core:engine` 计划模式状态。
 * core:tools 不依赖 core:engine，只暴露工具需要的两个动作。
 */
interface PlanModeHost {
    /** 已落库的计划模式是否激活（上游 `loggedActive`）——工具只在激活时可用。 */
    fun active(): Boolean

    /** 用户批准：请求退出计划模式（在下一个 step 起点落 `plan/mode {active:false}`，不 narrate）。 */
    fun requestExit()
}

/**
 * 子代理工具的后端：由 App 层用 `core:agents` 的 `AgentRegistry` 实现（core:tools 保持纯 JVM）。
 *
 * 模型侧文案逐字对齐上游 `dsh-tool-subagent` / `dsh-tool-subagent-control`；
 * 这里只暴露工具需要的 5 个动作，生命周期/深度/授权都在实现里。
 */
interface AgentHost {
    /** 当前代理的 id（根会话 = 会话 id；子代理 = 子会话 id）。 */
    fun currentAgentId(): String?

    /** 当前代理的委托深度（根 = 0）。 */
    fun currentDepth(): Int

    /** 部署的委托深度上限（上游默认 1）。 */
    fun maxDepth(): Int

    /** 启动一个子代理，立刻返回 id；[fork] = 继承已完成轮次。 */
    suspend fun start(description: String, prompt: String, cwd: String?, fork: Boolean): AgentStart

    /** `children` / `descendants`。 */
    fun list(scope: String): List<AgentEntry>

    /** 给直接子代理或直接父代理发消息。 */
    fun send(agentId: String, message: String): AgentSend

    /** 请求停止（不等待）。 */
    fun interrupt(agentId: String)
}

data class AgentStart(val id: String, val depth: Int)

data class AgentEntry(
    val id: String,
    val label: String,
    val status: String,
    val parentId: String? = null,
    val depth: Int = 0,
    /** 非 null = 损坏/不可用条目（上游 `kind:"diagnostic"`）。 */
    val diagnostic: String? = null,
)

data class AgentSend(val messageId: String, val agentId: String)

/**
 * `read_image` 产出的图片引用：与上游 `ImageAttachmentRef` 同形（模型层定义，见 [xin.dshaa.core.model.ImageRef]）。
 * 附件本体在 App 的附件存储里（内容寻址），会话只记引用（与上游「附件不参与 JSONL」一致）。
 */

/** 附件存储（`core:attachment` 的 `AttachmentStore` 实现；core:tools 保持纯 JVM 可单测）。 */
interface AttachmentHost {
    /**
     * 校验 + 归一化 + 落盘一张图，返回引用。
     * 失败抛 [ImageStoreException]，由工具翻成上游同形文案。
     */
    suspend fun saveImage(data: ByteArray, mediaType: String, name: String?): ImageRef
}

/** 附件归一化/存储的稳定错误码（对上游 `AttachmentError.code`）。 */
class ImageStoreException(
    val code: Code,
    message: String = "",
) : Exception(message) {
    enum class Code {
        /** 字节解不出图（PNG/JPEG/WebP/GIF）。 */
        INVALID_IMAGE,

        /** 归一化后仍超过部署的字节上限。 */
        IMAGE_TOO_LARGE,

        /** 解码后的像素总数超限。 */
        IMAGE_TOO_MANY_PIXELS,

        /** 长边超限。 */
        IMAGE_DIMENSION_TOO_LARGE,

        /** 声明类型与字节实际类型不一致。 */
        IMAGE_TYPE_MISMATCH,

        /** 写盘失败。 */
        WRITE_FAILED,
    }
}

/**
 * `read_image` 的模型能力门：上游只允许「明确声明 image 输入」的模型读图，
 * 否则在文件 IO 之前就拒绝（避免读完才发现模型看不了）。
 */
data class ImageRoute(
    val provider: String?,
    val model: String?,
    val supportsImage: Boolean,
)

/** `web_search` 需要的搜索能力；实现见 `core:llm` 的 `WebSearchClient`。 */
interface WebSearchHost {
    data class Source(
        val url: String,
        val title: String? = null,
        val snippet: String? = null,
        val publishedAt: String? = null,
    )

    data class Outcome(val sources: List<Source>, val answer: String?)

    suspend fun search(query: String): Outcome
}

/** goal 工具需要的持久化能力；实现见 `core:session` 的 `GoalStore`。 */
interface GoalHost {
    fun current(): xin.dshaa.core.model.GoalView?

    fun create(objective: String, maxGoalRounds: Int?): xin.dshaa.core.model.GoalView

    /** 本次调用的授权来源（core:session 的 `GoalStore.authority()`，只看当前 open turn）。 */
    fun authority(): xin.dshaa.core.model.GoalAuthority

    /**
     * [authority] / [minBlockedRounds] 由工具层按上游策略算好再传：
     * - `edit` / `pause` / `resume` / `create` 要求 [xin.dshaa.core.model.GoalAuthority.DirectHuman]；
     * - `complete` / `blocked` 还允许当前 goal 轮；
     * - 三次轮次阈值只管 goal 轮（人类直连传 0）。
     * 实现负责在 goal 轮收尾时附上 `<goal_complete>` / `<goal_blocked>` 通知。
     */
    fun update(
        goalId: String,
        revision: Int,
        action: String,
        objective: String? = null,
        maxGoalRounds: Int? = null,
        blockedReason: String? = null,
        authority: xin.dshaa.core.model.GoalAuthority = xin.dshaa.core.model.GoalAuthority.None,
        minBlockedRounds: Int = 3,
    ): GoalUpdateResult
}

/**
 * goal 工具的结算：更新后的视图 + 可选的收尾通知。
 *
 * 收尾通知对齐上游 `exec.deferContext(renderWrapupContext(...))`：只有
 * **当前 goal 轮**里 `complete` / `blocked` 才产生；宿主把它作为一条
 * `source.kind:"tool-goal"` 的 user 消息追加，模型在同一 turn 里写收尾话。
 */
data class GoalUpdateResult(val view: xin.dshaa.core.model.GoalView, val notice: GoalNotice? = null)

/** 注入给模型的收尾通知：正文（逐字节上游文案）+ 落库/UI 用的单行摘要。 */
data class GoalNotice(val text: String, val summary: String)

/**
 * `schedule_*` 四件套的后端：读写「当前会话」的提醒。
 * 实现见 App 层的适配器（持久化在 `core:session` 的 `ScheduleStore`）。
 */
interface ScheduleHost {
    /** 当前会话 id；null = 不在会话里（工具直接报不可用）。 */
    fun sessionId(): String?
    fun list(): List<xin.dshaa.core.model.ScheduledTask>
    fun create(
        title: String,
        prompt: String,
        timing: xin.dshaa.core.model.ScheduleTiming,
    ): xin.dshaa.core.model.ScheduledTask
    fun update(
        id: String,
        title: String?,
        prompt: String?,
        timing: xin.dshaa.core.model.ScheduleTiming?,
    ): xin.dshaa.core.model.ScheduledTask
    fun delete(id: String): xin.dshaa.core.model.ScheduledTask
}

data class ToolResult(
    val text: String,
    val isError: Boolean = false,
    val meta: Map<String, String> = emptyMap(),
    /** 工具产出的图片（`read_image`）：模型消息里是 image 块，UI 里是缩略图。 */
    val images: List<ImageRef> = emptyList(),
    /**
     * 工具要求追加给模型的 user 通知（`deferContext`）：宿主在 tool/result 之后落一条
     * user/message，再继续同一 turn 的下一次请求。目前只有 goal 收尾用。
     */
    val notice: GoalNotice? = null,
) {
    companion object {
        fun ok(
            text: String,
            meta: Map<String, String> = emptyMap(),
            images: List<ImageRef> = emptyList(),
            notice: GoalNotice? = null,
        ) = ToolResult(text, meta = meta, images = images, notice = notice)

        fun err(text: String, meta: Map<String, String> = emptyMap()) = ToolResult(text, isError = true, meta = meta)
    }
}

/** 路径解析：相对路径按当前目录解析；拒绝 `..` 逃逸工作区（除非显式允许）。 */
fun resolvePath(path: String, ctx: ToolContext): File {
    val raw = File(path)
    val base = if (raw.isAbsolute) raw else File(ctx.cwd, path)
    val canonical = base.canonicalFile
    if (!ctx.allowOutsideWorkspace) {
        val root = ctx.workspace.canonicalFile
        require(canonical.path == root.path || canonical.path.startsWith(root.path + File.separator)) {
            "path escapes workspace: $path"
        }
    }
    return canonical
}

/**
 * 工具表：内置工具在构造时静态注册，插件工具在运行时注册/注销（M27）。
 *
 * 为什么要可变：插件页导入/启用/停用/删除后要**立刻**反映到下一次请求的工具表，
 * 不能等重启。注册表本身线程安全；`apiTools`/`systemPrompt` 每次请求现算（见 `EngineDeps`），
 * 所以只要在插件操作后同步一次，下一次请求就能看到新工具。
 */
class ToolRegistry(initial: List<DshTool>) {
    private val lock = Any()

    /** 名字 → 工具；保持插入顺序（静态工具在前，插件工具在后 = 请求体里的顺序）。 */
    private val byName: LinkedHashMap<String, DshTool> = LinkedHashMap<String, DshTool>().apply {
        initial.forEach { tool -> if (!containsKey(tool.name)) put(tool.name, tool) }
    }

    /** 当前工具表快照（顺序即请求体顺序）。 */
    val tools: List<DshTool> get() = synchronized(lock) { byName.values.toList() }

    val names: List<String> get() = synchronized(lock) { byName.keys.toList() }

    /**
     * 运行时注册一个工具；同名（含内置）拒绝并返回 false。
     * 插件刷新时先 [unregister] 旧名字再注册，避免「删了插件工具还在」。
     */
    fun register(tool: DshTool): Boolean = synchronized(lock) {
        if (byName.containsKey(tool.name)) {
            false
        } else {
            byName[tool.name] = tool
            true
        }
    }

    /** 注销一个运行时工具；内置工具也可以被注销（调用方自己保证只删插件工具）。 */
    fun unregister(name: String): Boolean = synchronized(lock) { byName.remove(name) != null }

    suspend fun execute(name: String, args: JsonObject, ctx: ToolContext): ToolResult {
        val tool = synchronized(lock) { byName[name] } ?: return ToolResult.err("unknown tool: $name")
        return try {
            tool.execute(args, ctx)
        } catch (e: kotlinx.coroutines.CancellationException) {
            // 取消不是工具错误：CANCELLATION 是 IllegalStateException 的子类，被下面 catch(Exception)
            // 吞掉会变成一条 tool_result 然后继续跑下一步（stop/interrupt 失效）。
            throw e
        } catch (e: IllegalArgumentException) {
            ToolResult.err(e.message ?: "invalid arguments")
        } catch (e: Exception) {
            ToolResult.err("${e.javaClass.simpleName}: ${e.message}")
        }
    }
}

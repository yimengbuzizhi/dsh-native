package xin.dshaa.core.primitives

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** 稳定原语 id（App 的 `when(id)` 与测试都用常量，不写字面量）。 */
object PrimitiveIds {
    const val SESSION_LIST = "session.list"
    const val SESSION_OPEN = "session.open"
    const val SESSION_READ = "session.read"
    const val SESSION_FORK = "session.fork"
    const val SESSION_SUMMARIZE = "session.summarize"
    const val TOPIC_SWITCH = "topic.switch"
    const val TOPIC_END = "topic.end"
    const val MODEL_LIST = "model.list"
    const val MODEL_SET = "model.set"
    const val EFFORT_SET = "effort.set"
    const val PERMISSION_SET = "permission.set"
    const val TURN_SEND = "turn.send"
    const val TURN_STOP = "turn.stop"
    const val TURN_STATE = "turn.state"
    const val TOOL_LIST = "tool.list"
    const val TOOL_CALL = "tool.call"
    const val STORE_GET = "store.get"
    const val STORE_SET = "store.set"
    const val STORE_LIST = "store.list"
    const val UI_OPEN_CONTAINER = "ui.openContainer"
}

/**
 * 原语目录（20 个，全部 READY：`ui.openContainer` 在 P3-e 接上三种容器）。
 *
 * 纪律（`docs/185` §4.3）：新需求先问「现有原语能不能组合」；只有缺**原语**才往这里加，
 * 且必须同时改三面（工具/插件/原生由本文件自动生成，不需要各写一份）。
 */
object PrimitiveCatalog {
    /** 契约版本：三面共用的 JSON 形状与参数校验规则。 */
    const val CONTRACT: String = "primitive/v2"

    /** 与 `SettingsStore` 的 reasoningEffort 允许值一致（上游 `off/low/high/max`）。 */
    val EFFORTS: Set<String> = setOf("off", "low", "high", "max")

    /** 审批策略允许值（与 `/permission auto|ask` 一致）。 */
    val APPROVAL_MODES: Set<String> = setOf("auto", "ask")

    val definitions: List<PrimitiveDefinition> = listOf(
        // ---- 会话 / 话题 ----
        PrimitiveDefinition(
            id = PrimitiveIds.SESSION_LIST,
            group = "session",
            title = "列出话题",
            description = "List existing topics (sessions) with their ids, titles and updated-at epoch millis. " +
                "Use this to discover a topic id before session.open / session.read / topic.end. " +
                "Sub-agent sessions are excluded. Set include_trash=true to also list archived topics.",
            parameters = schema(
                required = emptyList(),
                properties = listOf(
                    "limit" to integerParam("Maximum number of topics to return (1-100).", default = 20),
                    "include_trash" to booleanParam("Also list topics in the trash.", default = false),
                ),
            ),
            risk = PrimitiveRisk.READ,
        ),
        PrimitiveDefinition(
            id = PrimitiveIds.SESSION_OPEN,
            group = "session",
            title = "打开话题",
            description = "Open an existing topic in the foreground. This changes what the user sees but does not " +
                "write any session event. Use session.list first to get a topic id.",
            parameters = schema(
                required = listOf("topic_id"),
                properties = listOf(
                    "topic_id" to stringParam("Topic id to open."),
                ),
            ),
            risk = PrimitiveRisk.SESSION,
            validate = { args ->
                if (PrimitiveArgs.string(args, "topic_id")?.trim().isNullOrEmpty()) "topic_id must not be blank" else null
            },
        ),
        PrimitiveDefinition(
            id = PrimitiveIds.SESSION_READ,
            group = "session",
            title = "读话题内容",
            description = "Read the message transcript of the topic that owns this call (the executing turn's topic; " +
                "plugin calls see the foreground topic). Returns role-tagged lines with their event seq, newest " +
                "messages last. Use from_seq/to_seq to page; max_chars caps the returned text. This is the read " +
                "side that feeds session.summarize.",
            parameters = schema(
                required = emptyList(),
                properties = listOf(
                    "topic_id" to stringParam("Topic id; omitted = the topic that owns this call."),
                    "from_seq" to integerParam("First event seq to include (inclusive).", minimum = 0),
                    "to_seq" to integerParam("Last event seq to include (inclusive).", minimum = 0),
                    "max_chars" to integerParam("Maximum characters of transcript text (200-20000).", default = 4000),
                ),
            ),
            risk = PrimitiveRisk.READ,
            validate = { args ->
                val from = PrimitiveArgs.long(args, "from_seq")
                val to = PrimitiveArgs.long(args, "to_seq")
                val max = PrimitiveArgs.long(args, "max_chars")
                when {
                    from != null && from < 0 -> "from_seq must be >= 0"
                    to != null && to < 0 -> "to_seq must be >= 0"
                    from != null && to != null && from > to -> "from_seq must be <= to_seq"
                    max != null && max !in 200..20_000 -> "max_chars must be between 200 and 20000"
                    else -> null
                }
            },
        ),
        PrimitiveDefinition(
            id = PrimitiveIds.SESSION_FORK,
            group = "session",
            title = "分支话题",
            description = "Fork the current topic at a clean turn boundary (default: the last event) into a new topic. " +
                "The new topic copies the prefix up to that point. This is the independent branching primitive; " +
                "handoff uses session.summarize + topic.switch instead.",
            parameters = schema(
                required = emptyList(),
                properties = listOf(
                    "at_event" to integerParam("Fork through this event seq (inclusive); omitted = the last event.", minimum = 0),
                    "title" to stringParam("Title for the forked topic.", maxLength = 200),
                ),
            ),
            risk = PrimitiveRisk.SESSION,
            validate = { args ->
                val at = PrimitiveArgs.long(args, "at_event")
                if (at != null && at < 0) "at_event must be >= 0" else null
            },
        ),
        PrimitiveDefinition(
            id = PrimitiveIds.SESSION_SUMMARIZE,
            group = "session",
            title = "生成交接摘要",
            description = "Condense the topic's completed history into a compact handoff brief with the current " +
                "model. The turn that calls this tool is excluded (it is still running), so an explicit range " +
                "must also end before that turn. This does not write any session event; feed the returned text " +
                "to topic.switch.brief. Omit from_seq/to_seq to summarize all completed turns.",
            parameters = schema(
                required = emptyList(),
                properties = listOf(
                    "from_seq" to integerParam("First event seq to summarize (inclusive).", minimum = 0),
                    "to_seq" to integerParam("Last event seq to summarize (inclusive).", minimum = 0),
                ),
            ),
            risk = PrimitiveRisk.READ,
            validate = { args ->
                val from = PrimitiveArgs.long(args, "from_seq")
                val to = PrimitiveArgs.long(args, "to_seq")
                when {
                    from != null && from < 0 -> "from_seq must be >= 0"
                    to != null && to < 0 -> "to_seq must be >= 0"
                    from != null && to != null && from > to -> "from_seq must be <= to_seq"
                    else -> null
                }
            },
        ),
        PrimitiveDefinition(
            id = PrimitiveIds.TOPIC_SWITCH,
            group = "topic",
            title = "切换/新建话题（可带 brief）",
            description = "Switch to an existing topic, or create a new one when topic_id is omitted. When brief is " +
                "provided it is appended to the target topic as the first user message with source.kind=\"handoff\" " +
                "- this is the only channel for starting a topic with context. The target must not be running.",
            parameters = schema(
                required = emptyList(),
                properties = listOf(
                    "topic_id" to stringParam("Topic to switch to; omitted = create a new topic."),
                    "title" to stringParam("Title for the topic.", maxLength = 200),
                    "brief" to stringParam("Handoff brief to seed as the first user message."),
                ),
            ),
            risk = PrimitiveRisk.SESSION,
        ),
        PrimitiveDefinition(
            id = PrimitiveIds.TOPIC_END,
            group = "topic",
            title = "归档话题",
            description = "Archive a topic into the trash (recoverable, not a permanent delete). Omit topic_id for the " +
                "current topic; when its turn is still running the archive is deferred until the turn finishes.",
            parameters = schema(
                required = emptyList(),
                properties = listOf(
                    "topic_id" to stringParam("Topic to archive; omitted = the current topic."),
                ),
            ),
            risk = PrimitiveRisk.SESSION,
        ),

        // ---- 模型 / 设置 ----
        PrimitiveDefinition(
            id = PrimitiveIds.MODEL_LIST,
            group = "model",
            title = "列模型通道",
            description = "List available model channels and the current model/settings (model, provider, protocol, " +
                "reasoning effort, approval mode). Read-only.",
            parameters = schema(required = emptyList()),
            risk = PrimitiveRisk.READ,
        ),
        PrimitiveDefinition(
            id = PrimitiveIds.MODEL_SET,
            group = "model",
            title = "设置模型",
            description = "Set the model id used by subsequent requests. Returns the previous and new model. " +
                "The id is not validated against a catalog: use model.list first.",
            parameters = schema(
                required = listOf("model"),
                properties = listOf(
                    "model" to stringParam("Model id, e.g. deepseek-flash.", maxLength = 200),
                ),
            ),
            risk = PrimitiveRisk.SETTINGS,
            validate = { args ->
                if (PrimitiveArgs.string(args, "model")?.trim().isNullOrEmpty()) "model must not be blank" else null
            },
        ),
        PrimitiveDefinition(
            id = PrimitiveIds.EFFORT_SET,
            group = "model",
            title = "设置推理强度",
            description = "Set the reasoning effort for subsequent requests. Allowed: off / low / high / max.",
            parameters = schema(
                required = listOf("effort"),
                properties = listOf(
                    "effort" to stringParam("Reasoning effort.", enum = listOf("off", "low", "high", "max")),
                ),
            ),
            risk = PrimitiveRisk.SETTINGS,
        ),
        PrimitiveDefinition(
            id = PrimitiveIds.PERMISSION_SET,
            group = "model",
            title = "设置工具审批",
            description = "Change the tool-approval mode: auto (no prompts) or ask (prompt before sensitive tools). " +
                "Requires user approval because it can weaken the safety gate.",
            parameters = schema(
                required = listOf("mode"),
                properties = listOf(
                    "mode" to stringParam("Approval mode.", enum = listOf("auto", "ask")),
                ),
            ),
            risk = PrimitiveRisk.SETTINGS,
            approval = PrimitiveApproval.ASK,
        ),

        // ---- 回合 ----
        PrimitiveDefinition(
            id = PrimitiveIds.TURN_SEND,
            group = "turn",
            title = "发消息起轮次",
            description = "Send a user message to the current foreground topic and start a turn. Only allowed while " +
                "that topic is idle; use it for scripted follow-ups, not to message yourself mid-turn.",
            parameters = schema(
                required = listOf("text"),
                properties = listOf(
                    "text" to stringParam("Message text.", maxLength = 100_000),
                ),
            ),
            risk = PrimitiveRisk.SESSION,
            validate = { args ->
                val text = PrimitiveArgs.string(args, "text")
                when {
                    text == null || text.isBlank() -> "text must not be blank"
                    text.length > 100_000 -> "text is too long (max 100000 chars)"
                    else -> null
                }
            },
        ),
        PrimitiveDefinition(
            id = PrimitiveIds.TURN_STOP,
            group = "turn",
            title = "停止轮次",
            description = "Stop the running turn of the current foreground topic (same path as the user's stop " +
                "button). A model cannot stop the turn that is executing this call; plugin callers can.",
            parameters = schema(required = emptyList()),
            risk = PrimitiveRisk.SESSION,
        ),
        PrimitiveDefinition(
            id = PrimitiveIds.TURN_STATE,
            group = "turn",
            title = "回合状态",
            description = "Read the turn state of the topic that owns this call (the executing turn's topic; plugin " +
                "calls see the foreground topic). Returns id, title, running/idle/read-only, message count, model, " +
                "reasoning effort and queued prompt count. Read-only.",
            parameters = schema(required = emptyList()),
            risk = PrimitiveRisk.READ,
        ),

        // ---- 工具 ----
        PrimitiveDefinition(
            id = PrimitiveIds.TOOL_LIST,
            group = "tool",
            title = "列工具",
            description = "List registered tools and whether each one is currently offered to the model. Read-only.",
            parameters = schema(required = emptyList()),
            risk = PrimitiveRisk.READ,
        ),
        PrimitiveDefinition(
            id = PrimitiveIds.TOOL_CALL,
            group = "tool",
            title = "调用工具",
            description = "Run a registered non-primitive tool by name with a JSON argument object. The call goes " +
                "through the normal approval gate (a sensitive tool may prompt the user). Primitive ids are " +
                "rejected to prevent recursion. Requires user approval.",
            parameters = schema(
                required = listOf("name"),
                properties = listOf(
                    "name" to stringParam("Tool name, as shown by tool.list."),
                    "arguments" to objectParam("Tool argument object (same shape as the tool's input schema)."),
                ),
            ),
            risk = PrimitiveRisk.DANGEROUS,
            approval = PrimitiveApproval.ASK,
            validate = { args ->
                when {
                    PrimitiveArgs.string(args, "name")?.trim().isNullOrEmpty() -> "name must not be blank"
                    else -> null
                }
            },
        ),

        // ---- 数据 ----
        PrimitiveDefinition(
            id = PrimitiveIds.STORE_GET,
            group = "store",
            title = "读数据",
            description = "Read a namespaced string value stored by primitives (not the workspace filesystem). " +
                "Returns an empty summary when the key does not exist. Read-only.",
            parameters = schema(
                required = listOf("namespace", "key"),
                properties = listOf(
                    "namespace" to stringParam("Namespace (letters/digits/._:-, max 64).", maxLength = 64),
                    "key" to stringParam("Key within the namespace (letters/digits/._:-, max 128).", maxLength = 128),
                ),
            ),
            risk = PrimitiveRisk.READ,
            validate = storeKeyValidator(),
        ),
        PrimitiveDefinition(
            id = PrimitiveIds.STORE_SET,
            group = "store",
            title = "写数据",
            description = "Store a string value under a namespace/key. Values are capped at 64 KiB; this does not " +
                "write the workspace filesystem.",
            parameters = schema(
                required = listOf("namespace", "key", "value"),
                properties = listOf(
                    "namespace" to stringParam("Namespace (letters/digits/._:-, max 64).", maxLength = 64),
                    "key" to stringParam("Key within the namespace (letters/digits/._:-, max 128).", maxLength = 128),
                    "value" to stringParam("String value (max 65536 bytes as UTF-8)."),
                ),
            ),
            risk = PrimitiveRisk.SETTINGS,
            validate = storeKeyValidator(requireValue = true),
        ),
        PrimitiveDefinition(
            id = PrimitiveIds.STORE_LIST,
            group = "store",
            title = "列数据键",
            description = "List the keys stored under a namespace. Read-only.",
            parameters = schema(
                required = listOf("namespace"),
                properties = listOf(
                    "namespace" to stringParam("Namespace (letters/digits/._:-, max 64).", maxLength = 64),
                ),
            ),
            risk = PrimitiveRisk.READ,
            validate = storeNameValidator(),
        ),

        // ---- 界面（P3-e 三种容器就绪；只开三种，不随上游界面贡献增长） ----
        PrimitiveDefinition(
            id = PrimitiveIds.UI_OPEN_CONTAINER,
            group = "ui",
            title = "打开插件容器",
            description = "Open a plugin's own web UI (exports[\"./client\"]) in one of the three host " +
                "containers: fullscreen / floating / sheet. Only those three exist; no slot protocol and " +
                "no in-conversation embedding (docs/187 §9.2 restricted by design).",
            parameters = schema(
                required = listOf("kind"),
                properties = listOf(
                    "kind" to stringParam("Container kind.", enum = listOf("fullscreen", "floating", "sheet")),
                    "plugin_id" to stringParam("Plugin id whose web UI should be mounted."),
                    "payload" to objectParam("Optional container payload."),
                ),
            ),
            risk = PrimitiveRisk.UI,
            // 打开容器会真的占屏/盖住聊天页：要用户点头，不能免审批。
            approval = PrimitiveApproval.ASK,
            availability = PrimitiveAvailability.READY,
        ),
    )

    val byId: Map<String, PrimitiveDefinition> = definitions.associateBy { it.id }

    /** 本版真的能执行的原语（工具面只注册这些）。 */
    val available: List<PrimitiveDefinition> = definitions.filter { it.availability == PrimitiveAvailability.READY }

    /** 原语 → 工具名（`.` 换 `_`，与上游 snake_case 工具一致）。 */
    fun toolName(id: String): String = id.replace('.', '_')

    /** 工具面名字集合（App 用它把原语从子代理工具表摘掉、并做 `READ_ONLY_TOOLS`）。 */
    val toolNames: Set<String> = available.map { toolName(it.id) }.toSet()

    /** 免审批的工具名（`approval = FREE` 且本版可执行）。 */
    val approvalFreeToolNames: Set<String> =
        available.filter { it.approval == PrimitiveApproval.FREE }.map { toolName(it.id) }.toSet()

    /** 插件桥面可见的原语（P1 只读）。 */
    val bridgeVisible: List<PrimitiveDefinition> = definitions.filter { it.bridgeVisible }
}

// ---- JSON Schema 小工具（只在本目录用；三面拿到的是同一份 JsonObject） ----

private fun schema(
    required: List<String>,
    properties: List<Pair<String, JsonObject>> = emptyList(),
): JsonObject = buildJsonObject {
    put("type", "object")
    put(
        "properties",
        buildJsonObject {
            properties.forEach { (name, spec) -> put(name, spec) }
        },
    )
    if (required.isNotEmpty()) {
        put(
            "required",
            buildJsonArray {
                required.forEach { add(JsonPrimitive(it)) }
            },
        )
    }
}

private fun stringParam(
    description: String,
    enum: List<String>? = null,
    maxLength: Int? = null,
): JsonObject = buildJsonObject {
    put("type", "string")
    put("description", description)
    if (enum != null) {
        put(
            "enum",
            buildJsonArray { enum.forEach { add(JsonPrimitive(it)) } },
        )
    }
    if (maxLength != null) put("maxLength", maxLength)
}

private fun integerParam(
    description: String,
    minimum: Long? = null,
    maximum: Long? = null,
    default: Long? = null,
): JsonObject = buildJsonObject {
    put("type", "integer")
    put("description", description)
    if (minimum != null) put("minimum", minimum)
    if (maximum != null) put("maximum", maximum)
    if (default != null) put("default", default)
}

private fun booleanParam(description: String, default: Boolean? = null): JsonObject = buildJsonObject {
    put("type", "boolean")
    put("description", description)
    if (default != null) put("default", default)
}

private fun objectParam(description: String): JsonObject = buildJsonObject {
    put("type", "object")
    put("description", description)
}

private val STORE_NAME = Regex("^[A-Za-z0-9_.:\\-]{1,64}$")
private val STORE_KEY = Regex("^[A-Za-z0-9_.:\\-]{1,128}$")
private const val STORE_VALUE_MAX = 64 * 1024

private fun storeNameValidator(): (JsonObject) -> String? = { args ->
    val namespace = PrimitiveArgs.string(args, "namespace").orEmpty()
    if (!STORE_NAME.matches(namespace)) "namespace must match [A-Za-z0-9_.:-]{1,64}" else null
}

private fun storeKeyValidator(requireValue: Boolean = false): (JsonObject) -> String? = { args ->
    val namespace = PrimitiveArgs.string(args, "namespace").orEmpty()
    val key = PrimitiveArgs.string(args, "key").orEmpty()
    val value = PrimitiveArgs.string(args, "value")
    when {
        !STORE_NAME.matches(namespace) -> "namespace must match [A-Za-z0-9_.:-]{1,64}"
        !STORE_KEY.matches(key) -> "key must match [A-Za-z0-9_.:-]{1,128}"
        requireValue && value == null -> "value is required"
        value != null && value.toByteArray(Charsets.UTF_8).size > STORE_VALUE_MAX ->
            "value is too large (max $STORE_VALUE_MAX bytes)"
        else -> null
    }
}

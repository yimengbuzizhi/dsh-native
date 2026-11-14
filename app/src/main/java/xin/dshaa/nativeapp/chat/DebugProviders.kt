package xin.dshaa.nativeapp.chat

import android.content.SharedPreferences
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import xin.dshaa.core.llm.LlmConfig
import xin.dshaa.core.llm.LlmEvent
import xin.dshaa.core.llm.LlmProvider
import xin.dshaa.core.llm.LlmProviders
import xin.dshaa.core.prompt.PromptPack
import xin.dshaa.core.prompt.PromptProvider
import xin.dshaa.core.prompt.PromptProviders
import xin.dshaa.core.prompt.PromptRequest
import xin.dshaa.core.protocol.ApiRequest

/**
 * M84-C 验收用的内置「假供应商」：不发网络，直接吐一轮合法 SSE 事件。
 *
 * 它走的是和真实供应商完全相同的 [LlmProvider] 接口与 TurnEngine 路径，
 * 用来证明「换一个供应商能真跑一轮」——不是把请求写死回 LlmClient。
 * 只在 debug 包注册；release 里 `install()` 直接返回。
 */
class DebugEchoLlmProvider : LlmProvider {
    override val id: String = ID
    override val label: String = "Debug Echo（不联网）"

    override fun stream(request: ApiRequest, config: LlmConfig): Flow<LlmEvent> = flow {
        emit(LlmEvent.MessageStart(id = "debug-echo", model = config.model, inputTokens = 0))
        emit(LlmEvent.BlockStart(index = 0, type = "text", toolId = null, toolName = null))
        emit(LlmEvent.TextDelta(index = 0, text = REPLY))
        emit(LlmEvent.BlockStop(index = 0))
        emit(LlmEvent.MessageDelta(stopReason = "end_turn", outputTokens = 12))
        emit(LlmEvent.MessageStop)
    }

    companion object {
        const val ID = "debug.echo"
        const val REPLY = "来自 debug 供应商的一轮（M84-C 验收）"
    }
}

/**
 * M84-C2 验收用的「会调工具」的假供应商：第一步吐一个 `bash {"command":"echo hi"}` 的
 * tool_use，第二步（请求里已经带上这次调用的 tool_result）吐最终文本。
 *
 * 用来在真机上**确定性地**跑一轮带工具的回合，验证 `EngineHooks` 的 step/tool 前后顺序；
 * 和 [DebugEchoLlmProvider] 一样走完整 TurnEngine + 真工具表 + 真审批路径，不绕过引擎。
 */
class DebugToolLlmProvider : LlmProvider {
    override val id: String = ID
    override val label: String = "Debug Tool（bash echo hi）"

    override fun stream(request: ApiRequest, config: LlmConfig): Flow<LlmEvent> = flow {
        emit(LlmEvent.MessageStart(id = "debug-tool", model = config.model, inputTokens = 0))
        // 第二步的请求里，最后一条消息就是这次调用的 tool_result（Anthropic 协议放在 user 里）；
        // 新的一轮用户消息进来时不会再命中，所以同一会话里多轮也能重复验收。
        val last = request.messages.lastOrNull()
        val gotResult = last?.content?.any { block ->
            block is xin.dshaa.core.protocol.ContentBlock.ToolResult && block.toolUseId == CALL_ID
        } == true
        if (gotResult) {
            emit(LlmEvent.BlockStart(index = 0, type = "text", toolId = null, toolName = null))
            emit(LlmEvent.TextDelta(index = 0, text = "工具跑完了（debug.tool 验收）"))
            emit(LlmEvent.BlockStop(index = 0))
            emit(LlmEvent.MessageDelta(stopReason = "end_turn", outputTokens = 8))
        } else {
            emit(LlmEvent.BlockStart(index = 0, type = "text", toolId = null, toolName = null))
            emit(LlmEvent.TextDelta(index = 0, text = "用 bash 跑一下 echo hi。"))
            emit(LlmEvent.BlockStop(index = 0))
            emit(LlmEvent.BlockStart(index = 1, type = "tool_use", toolId = CALL_ID, toolName = "bash"))
            emit(LlmEvent.ToolInputDelta(index = 1, partialJson = """{"command":"echo hi"}"""))
            emit(LlmEvent.BlockStop(index = 1))
            emit(LlmEvent.MessageDelta(stopReason = "tool_use", outputTokens = 6))
        }
        emit(LlmEvent.MessageStop)
    }

    companion object {
        const val ID = "debug.tool"
        const val CALL_ID = "debug-tool-call-1"
    }
}

/**
 * M84-D 验收用的「慢速」假供应商：先吐半句，停 2.5 秒再收口。
 * 用来在真机上制造「退到桌面后流式才结束」的窗口，让悬浮球自动弹结果气泡（气泡插口）。
 */
class DebugSlowLlmProvider : LlmProvider {
    override val id: String = ID
    override val label: String = "Debug Slow（2.5s 后收口）"

    override fun stream(request: ApiRequest, config: LlmConfig): Flow<LlmEvent> = flow {
        emit(LlmEvent.MessageStart(id = "debug-slow", model = config.model, inputTokens = 0))
        emit(LlmEvent.BlockStart(index = 0, type = "text", toolId = null, toolName = null))
        emit(LlmEvent.TextDelta(index = 0, text = "慢速回复："))
        delay(2_500)
        emit(LlmEvent.TextDelta(index = 0, text = "悬浮窗气泡插口验收（M84-D）"))
        emit(LlmEvent.BlockStop(index = 0))
        emit(LlmEvent.MessageDelta(stopReason = "end_turn", outputTokens = 6))
        emit(LlmEvent.MessageStop)
    }

    companion object {
        const val ID = "debug.slow"
    }
}

/**
 * M84-C 验收用的提示词替换：先跑默认 [PromptPack.systemFor]（段/变量/角色卡都不变），
 * 再追加一段验收标记——证明 system 真的走了 [PromptProvider]，而不是写死默认实现。
 */
class DebugPromptProvider(private val marker: String) : PromptProvider {
    override val id: String = ID
    override val label: String = "Debug Prompt（默认 + 标记）"

    override fun system(pack: PromptPack, request: PromptRequest): String =
        pack.systemFor(
            model = request.model,
            provider = request.provider,
            tools = request.tools,
            platform = request.platform,
            planMode = request.planMode,
            personaPrefix = request.personaPrefix,
            personaSuffix = request.personaSuffix,
            extraSections = request.extraSections,
            variables = request.variables,
        ) + "\n\n" + marker

    companion object {
        const val ID = "debug.prompt"
    }
}

/** debug 通道的注册/恢复（`--es dsh_llm_provider` / `--es dsh_prompt_override`）。 */
object DebugProviders {
    private const val PREF_LLM = "debug_llm_provider"
    private const val PREF_PROMPT = "debug_prompt_override"

    /** 冷启动时按持久化的调试值恢复（release 不注册）。 */
    fun install(prefs: SharedPreferences, debug: Boolean) {
        if (!debug) return
        LlmProviders.register(DebugEchoLlmProvider())
        LlmProviders.register(DebugToolLlmProvider())
        LlmProviders.register(DebugSlowLlmProvider())
        LlmProviders.activeId = prefs.getString(PREF_LLM, null)?.takeIf { it.isNotBlank() }
        val marker = prefs.getString(PREF_PROMPT, null)?.takeIf { it.isNotBlank() }
        if (marker != null) {
            PromptProviders.register(DebugPromptProvider(marker))
            PromptProviders.activeId = DebugPromptProvider.ID
        }
    }

    /**
     * 热切换（同进程 `am start` 即可）：
     * - `providerId` 非空：`debug.echo` 或 `off`（恢复协议默认）；
     * - `promptMarker` 非空：追加标记或 `off`（恢复默认 PromptPack）。
     */
    fun apply(prefs: SharedPreferences, providerId: String?, promptMarker: String?) {
        if (providerId != null) {
            val id = providerId.trim()
            if (id.equals("off", ignoreCase = true)) {
                LlmProviders.activeId = null
                prefs.edit().remove(PREF_LLM).apply()
            } else if (id.isNotBlank()) {
                LlmProviders.activeId = id
                prefs.edit().putString(PREF_LLM, id).apply()
            }
        }
        if (promptMarker != null) {
            val marker = promptMarker.trim()
            if (marker.equals("off", ignoreCase = true)) {
                PromptProviders.unregister(DebugPromptProvider.ID)
                PromptProviders.activeId = null
                prefs.edit().remove(PREF_PROMPT).apply()
            } else if (marker.isNotBlank()) {
                PromptProviders.register(DebugPromptProvider(marker))
                PromptProviders.activeId = DebugPromptProvider.ID
                prefs.edit().putString(PREF_PROMPT, marker).apply()
            }
        }
    }
}

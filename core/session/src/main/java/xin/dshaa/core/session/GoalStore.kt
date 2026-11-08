package xin.dshaa.core.session

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import xin.dshaa.core.model.GoalActivation
import xin.dshaa.core.model.GoalAuthority
import xin.dshaa.core.model.GoalBlockReason
import xin.dshaa.core.model.GoalException
import xin.dshaa.core.model.GoalPhase
import xin.dshaa.core.model.GoalSnapshot
import xin.dshaa.core.model.GoalView
import xin.dshaa.core.model.SessionEvent
import java.util.UUID

/**
 * goal 的持久化与折叠（对齐上游 `dsh-goal`）。
 *
 * 每条变更写一条 `goal/change`（v4 事件，格式实测自旧会话）：
 * ```json
 * {"type":"goal/change","seq":405,"time":…,"data":{"kind":"goal/change","version":1,
 *   "operation":"create","goal":{"id":"goal-…","revision":1,"objective":"…",
 *   "phase":"active","maxGoalRounds":60},"roundsStarted":0,
 *   "createdAt":…,"updatedAt":…}}
 * ```
 * 读路径 = 扫最后一条 `goal/change`，再把之后 `source.kind:"goal"` 的 user/message 轮次加上
 * （上游 projection 的同一规则）。`activation` 是进程内状态，不落盘。
 */
class GoalStore(
    private val session: SessionSession,
    private val defaultMaxGoalRounds: Int = 256,
    /**
     * 进程内的续轮开关（上游 `activation`）：**不落盘**，新进程/新会话默认 disarmed，
     * `create`/`resume` 置 armed，`pause`/`complete`/`block`/`clear` 置 disarmed。
     * 由调用方持有（AppViewModel 每个打开的会话一个），这样 `current()` 才能读到同一份状态。
     */
    val activation: GoalActivationState = GoalActivationState(),
) {

    fun current(): GoalView? = fold(session.events)

    fun create(objective: String, maxGoalRounds: Int? = null): GoalView {
        val trimmed = objective.trim()
        if (trimmed.isEmpty()) {
            throw GoalException("GOAL_INVALID_OBJECTIVE", "objective must be non-empty")
        }
        val cap = resolveMaxGoalRounds(maxGoalRounds ?: defaultMaxGoalRounds)
        // 上游：未完成的目标必须先 edit/pause/resume/blocked/clear；已完成的目标可以直接替换
        val current = current()
        if (current != null && current.goal.phase != GoalPhase.COMPLETE) {
            throw GoalException("GOAL_ALREADY_EXISTS", "a goal already exists in this session")
        }
        val now = System.currentTimeMillis()
        val snapshot = GoalSnapshot(
            id = "goal-${UUID.randomUUID()}",
            revision = 1,
            objective = trimmed,
            phase = GoalPhase.ACTIVE,
            maxGoalRounds = cap,
        )
        append("create", snapshot, roundsStarted = 0, createdAt = now, updatedAt = now)
        activation.set(GoalActivation.ARMED)
        return requireView()
    }

    fun update(
        goalId: String,
        revision: Int,
        action: String,
        objective: String? = null,
        maxGoalRounds: Int? = null,
        blockedReason: String? = null,
        minBlockedRounds: Int = 3,
    ): GoalView {
        if (goalId.isEmpty() || goalId != goalId.trim() || revision < 1) {
            throw GoalException("GOAL_TOOL_INVALID_UPDATE", "goal_id must be non-empty and revision must be a positive safe integer")
        }
        val view = current() ?: throw GoalException("GOAL_NOT_FOUND", "no current goal")
        val current = view.goal
        if (current.id != goalId) {
            throw GoalException("GOAL_STALE_REVISION", "goal id does not match the current goal")
        }
        if (current.revision != revision) {
            throw GoalException("GOAL_STALE_REVISION", "goal revision $revision is stale; current revision is ${current.revision}")
        }
        val now = System.currentTimeMillis()
        val next: GoalSnapshot = when (action) {
            "edit" -> {
                val newObjective = objective?.takeIf { it.isNotEmpty() }?.trim()
                val newCap = maxGoalRounds?.takeIf { it != 0 }
                if (newObjective == null && newCap == null) {
                    throw GoalException("GOAL_INVALID_EDIT", "goal edit requires objective and/or maxGoalRounds")
                }
                current.copy(
                    revision = current.revision + 1,
                    objective = newObjective ?: current.objective,
                    maxGoalRounds = newCap?.let { resolveMaxGoalRounds(it) } ?: current.maxGoalRounds,
                )
            }
            "pause" -> {
                if (current.phase != GoalPhase.ACTIVE) {
                    throw GoalException("GOAL_INVALID_TRANSITION", "only an active goal can be paused (current: ${current.phase})")
                }
                current.copy(revision = current.revision + 1, phase = GoalPhase.PAUSED)
            }
            "resume" -> {
                when (current.phase) {
                    GoalPhase.PAUSED -> throw GoalException(
                        "GOAL_TOOL_RESUME_PAUSED",
                        "the model cannot resume a paused goal; the user must resume it",
                    )
                    GoalPhase.COMPLETE -> throw GoalException("GOAL_INVALID_TRANSITION", "a completed goal cannot be resumed")
                    // active + disarmed（会话恢复后的常态）可以重新 arm；active + armed 才是真的已在跑
                    GoalPhase.ACTIVE -> if (activation.value == GoalActivation.ARMED) {
                        throw GoalException("GOAL_INVALID_TRANSITION", "goal is already active and armed")
                    }
                }
                if (view.roundsStarted >= current.maxGoalRounds) {
                    throw GoalException(
                        "GOAL_INVALID_TRANSITION",
                        "goal \"${current.id}\" exhausted ${current.maxGoalRounds} goal rounds; increase maxGoalRounds before resuming",
                    )
                }
                current.copy(revision = current.revision + 1, phase = GoalPhase.ACTIVE, blockedReason = null)
            }
            "complete" -> {
                if (current.phase == GoalPhase.COMPLETE) {
                    throw GoalException("GOAL_INVALID_TRANSITION", "goal is already complete")
                }
                current.copy(revision = current.revision + 1, phase = GoalPhase.COMPLETE, blockedReason = null)
            }
            "blocked" -> {
                val reason = blockedReason?.trim().orEmpty()
                if (reason.isEmpty()) {
                    throw GoalException("GOAL_INVALID_BLOCK_REASON", "blocked_reason is required with action blocked")
                }
                if (current.phase != GoalPhase.ACTIVE) {
                    throw GoalException("GOAL_INVALID_TRANSITION", "only an active goal can be blocked (current: ${current.phase})")
                }
                if (view.roundsStarted < minBlockedRounds) {
                    throw GoalException(
                        "GOAL_TOOL_BLOCK_THRESHOLD",
                        "blocked requires at least $minBlockedRounds consecutive goal rounds; current round is ${view.roundsStarted}",
                    )
                }
                current.copy(
                    revision = current.revision + 1,
                    phase = GoalPhase.BLOCKED,
                    blockedReason = GoalBlockReason(code = "model-reported", message = reason),
                )
            }
            else -> throw GoalException("GOAL_INVALID_TRANSITION", "unknown action: $action")
        }
        // 事件里的 operation 必须是真实动作（上游 `goal/changed` 用它区分生命周期），blocked → block
        append(if (action == "blocked") "block" else action, next, view.roundsStarted, view.createdAt, now)
        activation.set(
            when (action) {
                "resume" -> GoalActivation.ARMED
                "pause", "complete", "blocked" -> GoalActivation.DISARMED
                else -> activation.value
            },
        )
        return requireView()
    }

    // ---------------- 用户/宿主侧控制（/goal 面板；模型工具没有这些权限） ----------------

    /** 用户暂停：active → paused，并 disarm。 */
    fun pauseAsUser(goalId: String, revision: Int): GoalView {
        val view = expect(goalId, revision)
        if (view.goal.phase != GoalPhase.ACTIVE) {
            throw GoalException("GOAL_INVALID_TRANSITION", "only an active goal can be paused (current: ${view.goal.phase})")
        }
        val next = view.goal.copy(revision = view.goal.revision + 1, phase = GoalPhase.PAUSED)
        append("pause", next, view.roundsStarted, view.createdAt, nowAfter(view))
        activation.set(GoalActivation.DISARMED)
        return requireView()
    }

    /**
     * 用户恢复：active(disarmed) / paused / blocked → active + armed。
     * 模型工具仍禁止 resume paused（`update` 里拦），这里是用户的唯一恢复通道。
     */
    fun resumeAsUser(goalId: String, revision: Int): GoalView {
        val view = expect(goalId, revision)
        when (view.goal.phase) {
            GoalPhase.ACTIVE -> if (view.activation == GoalActivation.ARMED) {
                throw GoalException("GOAL_INVALID_TRANSITION", "goal is already active and armed")
            }
            GoalPhase.COMPLETE -> throw GoalException("GOAL_INVALID_TRANSITION", "a completed goal cannot be resumed")
            else -> Unit
        }
        if (view.roundsStarted >= view.goal.maxGoalRounds) {
            throw GoalException(
                "GOAL_INVALID_TRANSITION",
                "goal \"${view.goal.id}\" exhausted ${view.goal.maxGoalRounds} goal rounds; increase maxGoalRounds before resuming",
            )
        }
        val next = view.goal.copy(
            revision = view.goal.revision + 1,
            phase = GoalPhase.ACTIVE,
            blockedReason = null,
        )
        append("resume", next, view.roundsStarted, view.createdAt, nowAfter(view))
        activation.set(GoalActivation.ARMED)
        return requireView()
    }

    /** 用户编辑目标/轮次上限；不改阶段与 activation。 */
    fun editAsUser(goalId: String, revision: Int, objective: String?, maxGoalRounds: Int?): GoalView {
        val view = expect(goalId, revision)
        val newObjective = objective?.trim()?.takeIf { it.isNotEmpty() }
        val newCap = maxGoalRounds?.takeIf { it != 0 }?.let { resolveMaxGoalRounds(it) }
        if (newObjective == null && newCap == null) {
            throw GoalException("GOAL_INVALID_EDIT", "goal edit requires objective and/or maxGoalRounds")
        }
        val next = view.goal.copy(
            revision = view.goal.revision + 1,
            objective = newObjective ?: view.goal.objective,
            maxGoalRounds = newCap ?: view.goal.maxGoalRounds,
        )
        append("edit", next, view.roundsStarted, view.createdAt, nowAfter(view))
        return requireView()
    }

    /** 用户清空：写带 revision 的墓碑（历史保留），并 disarm。 */
    fun clearAsUser(goalId: String, revision: Int) {
        val view = expect(goalId, revision)
        val data = buildJsonObject {
            put("kind", "goal/change")
            put("version", 1)
            put("operation", "clear")
            put(
                "cleared",
                buildJsonObject {
                    put("id", view.goal.id)
                    put("revision", view.goal.revision + 1)
                },
            )
            put("clearedAt", nowAfter(view))
        }
        session.appendRaw("goal/change", data)
        activation.set(GoalActivation.DISARMED)
    }

    /** 驱动器的轮次上限收尾（不经过模型工具，不受 3 轮阈值限制）。 */
    fun blockByDriver(goalId: String, revision: Int, code: String, message: String): GoalView {
        val view = expect(goalId, revision)
        if (view.goal.phase != GoalPhase.ACTIVE) {
            throw GoalException("GOAL_INVALID_TRANSITION", "only an active goal can be blocked (current: ${view.goal.phase})")
        }
        val next = view.goal.copy(
            revision = view.goal.revision + 1,
            phase = GoalPhase.BLOCKED,
            blockedReason = GoalBlockReason(code = code, message = message),
        )
        append("block", next, view.roundsStarted, view.createdAt, nowAfter(view))
        activation.set(GoalActivation.DISARMED)
        return requireView()
    }

    /** 只关续轮开关，不改持久状态（上游 `goals.disarm`）。 */
    fun disarm() {
        activation.set(GoalActivation.DISARMED)
    }

    /**
     * 本次工具调用的人类/轮次授权（对齐上游 `dsh-tool-goal/lib/types/authority.js`）。
     *
     * 证据只来自**当前 open turn** 的 user/message source：
     * - 有 `kind:"user"` → 人类直连（手机端只有一个根 agent，`roots()` 恒成立）；
     * - 否则看 goal 轮：`kind:"goal"` 且 goalId/revision 与当前目标一致、round 等于已开始轮次
     *   （= 本 turn 正好是**当前**这一轮，不是旧轮次重放）；
     * - 都没有 → [GoalAuthority.None]。
     */
    fun authority(): GoalAuthority {
        val sources = session.openTurnUserSources()
        if (sources.any { it.str("kind") == "user" }) return GoalAuthority.DirectHuman
        val view = current() ?: return GoalAuthority.None
        val matching = sources.any { source ->
            source.str("kind") == "goal" &&
                source.str("goalId") == view.goal.id &&
                source.intOrNull("revision") == view.goal.revision &&
                source.intOrNull("round") == view.roundsStarted
        }
        return if (matching) {
            GoalAuthority.GoalRound(view.goal.id, view.goal.revision, view.roundsStarted)
        } else {
            GoalAuthority.None
        }
    }

    private fun expect(goalId: String, revision: Int): GoalView {
        if (goalId.isEmpty() || goalId != goalId.trim() || revision < 1) {
            throw GoalException("GOAL_INVALID_UPDATE", "goal_id must be non-empty and revision must be a positive safe integer")
        }
        val view = current() ?: throw GoalException("GOAL_NOT_FOUND", "no current goal")
        if (view.goal.id != goalId) {
            throw GoalException("GOAL_STALE_REVISION", "goal id does not match the current goal")
        }
        if (view.goal.revision != revision) {
            throw GoalException("GOAL_STALE_REVISION", "goal revision $revision is stale; current revision is ${view.goal.revision}")
        }
        return view
    }

    private fun nowAfter(view: GoalView): Long = maxOf(System.currentTimeMillis(), view.updatedAt)

    /** 从事件流折叠当前 goal。`goal/change` 的 clear 墓碑会清空当前 goal。 */
    fun fold(events: List<SessionEvent>): GoalView? {
        var snapshot: GoalSnapshot? = null
        var roundsBase = 0
        var createdAt = 0L
        var updatedAt = 0L
        // 最新一条 goal/change 的 seq：roundsBase 已经包含它之前的 goal 轮，
        // 只统计**之后**的 goal 来源 user/message，避免重复计数。
        var lastChangeSeq = -1L
        for (event in events) {
            if (event.type != "goal/change") continue
            val data = event.data?.let { runCatching { it.jsonObject }.getOrNull() } ?: continue
            when (data["operation"]?.jsonPrimitive?.contentOrNull) {
                "clear" -> {
                    snapshot = null
                    roundsBase = 0
                    lastChangeSeq = event.seq ?: lastChangeSeq
                }
                null -> Unit
                else -> {
                    val goal = data["goal"]?.let { runCatching { it.jsonObject }.getOrNull() } ?: continue
                    snapshot = parseGoal(goal) ?: continue
                    roundsBase = data["roundsStarted"]?.jsonPrimitive?.intOrNull ?: 0
                    createdAt = data["createdAt"]?.jsonPrimitive?.longOrNull ?: event.time ?: 0L
                    updatedAt = data["updatedAt"]?.jsonPrimitive?.longOrNull ?: event.time ?: 0L
                    lastChangeSeq = event.seq ?: lastChangeSeq
                }
            }
        }
        val goal = snapshot ?: return null
        val goalRounds = events.count { event ->
            if (event.type != "user/message") return@count false
            if ((event.seq ?: -1L) <= lastChangeSeq) return@count false
            val source = event.data?.let { runCatching { it.jsonObject }.getOrNull() }
                ?.get("source")?.let { runCatching { it.jsonObject }.getOrNull() }
                ?: return@count false
            (source["kind"]?.jsonPrimitive?.contentOrNull == "goal") &&
                (source["goalId"]?.jsonPrimitive?.contentOrNull == goal.id) &&
                // 上游只把正数轮次计入（round 0 是保留/通知消息）
                ((source["round"]?.jsonPrimitive?.intOrNull ?: 0) > 0)
        }
        val rounds = roundsBase + goalRounds
        // activation 是进程内状态（GoalActivationState），不是从阶段/轮次推出来的：
        // 会话恢复后 active 目标仍然是 disarmed，直到用户显式 resume。
        val armed = if (goal.phase == GoalPhase.ACTIVE) activation.value else GoalActivation.DISARMED
        return GoalView(goal, rounds, createdAt, updatedAt, armed)
    }

    private fun requireView(): GoalView = current() ?: error("goal vanished after mutation")

    private fun append(operation: String, goal: GoalSnapshot, roundsStarted: Int, createdAt: Long, updatedAt: Long) {
        val data = buildJsonObject {
            put("kind", "goal/change")
            put("version", 1)
            put("operation", operation)
            put(
                "goal",
                buildJsonObject {
                    put("id", goal.id)
                    put("revision", goal.revision)
                    put("objective", goal.objective)
                    put("phase", goal.phase)
                    put("maxGoalRounds", goal.maxGoalRounds)
                    goal.blockedReason?.let { reason ->
                        put(
                            "blockedReason",
                            buildJsonObject {
                                put("code", reason.code)
                                put("message", reason.message)
                            },
                        )
                    }
                },
            )
            put("roundsStarted", roundsStarted)
            put("createdAt", createdAt)
            put("updatedAt", updatedAt)
        }
        session.appendRaw("goal/change", data)
    }

    private fun parseGoal(goal: JsonObject): GoalSnapshot? {
        val id = goal["id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: return null
        val revision = goal["revision"]?.jsonPrimitive?.intOrNull ?: return null
        val objective = goal["objective"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val phase = goal["phase"]?.jsonPrimitive?.contentOrNull ?: GoalPhase.ACTIVE
        val maxGoalRounds = goal["maxGoalRounds"]?.jsonPrimitive?.intOrNull ?: 256
        val blocked = goal["blockedReason"]?.let { runCatching { it.jsonObject }.getOrNull() }
        return GoalSnapshot(
            id = id,
            revision = revision,
            objective = objective,
            phase = phase,
            maxGoalRounds = maxGoalRounds,
            blockedReason = blocked?.let { reason ->
                GoalBlockReason(
                    code = reason["code"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    message = reason["message"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                )
            },
        )
    }

    private fun resolveMaxGoalRounds(value: Int): Int {
        if (value < 1) throw GoalException("GOAL_INVALID_MAX_ROUNDS", "maxGoalRounds must be a positive safe integer")
        return value
    }
}

/** 一个会话的进程内续轮开关；与会话文件无关，进程重启即回到 disarmed。 */
class GoalActivationState(initial: String = GoalActivation.DISARMED) {
    var value: String = initial
        private set

    fun set(next: String) {
        value = next
    }
}

package xin.dshaa.nativeapp.ui.shell.native

/**
 * 壳内导航返回栈（docs/190 §3-A）。
 *
 * 0.3.4 只修了**二级页**的来路（H3，docs/182 §H3：`subReturnPage/subReturnSub`），
 * **一级页**（设置 → 记忆库/会话/终端…）还是老逻辑：`go()` 一律把 `backPage` 写成
 * 「设置首页」，且不保存设置页自己的来路。于是：
 *
 * 聊天 → 设置 → 记忆库 → 返回 → 设置（此时 `backPage` 仍是设置）→ 再返回原地不动。
 *
 * 这里把整个返回栈抽成纯状态机，和二级页的 `subReturnPage` 对称：
 * - `backPage/backSub`：**当前页**的来路（返回一次落在这里）；
 * - `pageReturnPage/pageReturnSub`：**来路页自己的来路**（返回后恢复，保证还能再退一层）；
 * - `subReturnPage/subReturnSub`：二级页的来路页自己的来路（H3 已有）。
 *
 * 聊天页（`screen == null`）返回仍交给系统退出 App，不改（用户拍板）。
 */
object NativeNavBackPolicy {

    /**
     * 壳的完整路由状态。`screen == null` 表示聊天；`sub != null` 时壳把 `screen` 渲染成设置首页、
     * 二级页盖在上面。
     */
    data class State(
        val screen: String? = null,
        val sub: String? = null,
        val backPage: String? = null,
        val backSub: String? = null,
        val pageReturnPage: String? = null,
        val pageReturnSub: String? = null,
        /** 二级页的来路页自己的来路（H3，docs/182）。 */
        val subReturnPage: String? = null,
        val subReturnSub: String? = null,
    ) {
        /** 落在聊天落地页：所有返回目标清空。 */
        val isChat: Boolean get() = screen == null && sub == null
    }

    /** 打开二级页时要写入的返回目标；[page] 为 null 表示回聊天（H3）。 */
    data class BackTarget(val page: String?, val sub: String?)

    /** H3 规则：从聊天直进二级页返回聊天，其它来路一律回设置首页。 */
    fun forOpenSub(originPage: String?, settingsPageId: String): BackTarget =
        if (originPage == null) {
            BackTarget(page = null, sub = null)
        } else {
            BackTarget(page = settingsPageId, sub = null)
        }

    /** 稳定聊天态（`goFromChat` / `toChat` / 通知回落地页共用）。 */
    fun chat(): State = State()

    /**
     * 打开一级页（设置首页的「记忆库/包管理/会话…」、抽屉一级入口、调试通道）。
     *
     * [originPage]/[originSub] 是**来路页**（抽屉里点入口时是打开抽屉那一刻快照）；
     * 默认取当前状态。返回目标 = 来路页；同时把来路页自己的返回目标存进
     * `pageReturn*`，等 `onBackFromPrimary` 恢复。
     *
     * 目标就是当前页（例如在设置首页又从抽屉点设置）时不压栈，避免「返回原地不动」。
     */
    fun onPrimaryOpen(
        state: State,
        targetPage: String,
        originPage: String? = state.screen,
        originSub: String? = state.sub,
    ): State {
        if (targetPage == originPage && originSub == null) {
            // 已在目标页：只保证二级页收起（抽屉里点当前一级页语义 = 回到该页首页）。
            return if (state.sub == null) state else state.copy(
                screen = targetPage,
                sub = null,
                backPage = originPage,
                backSub = null,
            )
        }
        return state.copy(
            screen = targetPage,
            sub = null,
            backPage = originPage,
            backSub = originSub,
            pageReturnPage = state.backPage,
            pageReturnSub = state.backSub,
        )
    }

    /** 一级页返回：回 `backPage/backSub`，并把那一页自己的返回目标恢复回来。 */
    fun onBackFromPrimary(state: State): State {
        if (state.sub != null) return state
        // 兜底：历史状态（或调试通道）可能留下「来路 = 自己」，此时直接回聊天，绝不原地卡死。
        if (state.backPage == state.screen) return State()
        return state.copy(
            screen = state.backPage,
            sub = state.backSub,
            backPage = state.pageReturnPage,
            backSub = state.pageReturnSub,
            pageReturnPage = null,
            pageReturnSub = null,
        )
    }

    /**
     * 打开二级页（聊天模型芯片、设置首页各二级入口、抽屉二级入口）。
     *
     * 二级页渲染在设置首页上；来路是聊天时 `backPage = null`（关掉回聊天），否则回设置首页（H3）。
     * `subReturn*` 记的是设置首页自己的来路；同级再开一个二级页时不能把它覆盖掉。
     */
    fun onSubOpen(
        state: State,
        subPage: String,
        settingsPageId: String,
        originPage: String? = state.screen,
        originSub: String? = state.sub,
    ): State {
        if (state.sub == subPage) return state
        val back = forOpenSub(originPage, settingsPageId)
        val sameLevelSwitch = state.sub != null
        return state.copy(
            screen = settingsPageId,
            sub = subPage,
            backPage = back.page,
            backSub = back.sub,
            subReturnPage = if (sameLevelSwitch) state.subReturnPage else state.backPage,
            subReturnSub = if (sameLevelSwitch) state.subReturnSub else state.backSub,
        )
    }

    /** 二级页返回：回它的来路页，并恢复来路页自己的返回目标（H3）。 */
    fun onSubClose(state: State): State {
        if (state.sub == null) return state
        return state.copy(
            screen = state.backPage,
            sub = state.backSub,
            backPage = state.subReturnPage,
            backSub = state.subReturnSub,
        )
    }
}

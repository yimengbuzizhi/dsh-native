package xin.dshaa.nativeapp.remote

import xin.dshaa.ui.shellapi.remote.RemoteChatState

/**
 * Recoverable prompt ownership, independent of HTTP/WS lifetimes. It never sends anything.
 * An acknowledgement or durable source.rpcId only clears the draft this request restored;
 * a later user edit, even to the same text, remains owned by the user.
 */
internal class RemotePromptDrafts {
    private data class Pending(val requestId: String, val text: String, val revision: Long, var restored: Boolean = false)
    private val revisions = mutableMapOf<Pair<String, String>, Long>()
    private val pending = mutableMapOf<Pair<String, String>, Pending>()
    private fun owner(state: RemoteChatState) = state.profileId to state.sessionId

    fun edit(state: RemoteChatState, text: String): RemoteChatState {
        // ChatScreen mirrors the already-cleared draft after accepted submission.
        if (state.draft == text) return state
        val key = owner(state)
        revisions[key] = (revisions[key] ?: 0L) + 1
        return state.copy(draft = text)
    }

    fun begin(state: RemoteChatState, requestId: String, text: String): RemoteChatState {
        val key = owner(state)
        pending[key] = Pending(requestId, text, revisions[key] ?: 0L)
        return state.copy(draft = "", failedPrompt = text)
    }

    fun requestId(state: RemoteChatState): String? = pending[owner(state)]?.requestId

    fun recover(state: RemoteChatState, notice: String): RemoteChatState {
        val key = owner(state)
        val request = pending[key] ?: return state
        val restore = state.draft.isEmpty() && (revisions[key] ?: 0L) == request.revision
        if (restore) request.restored = true
        return state.copy(draft = if (restore) request.text else state.draft, failedPrompt = request.text, notice = notice)
    }

    fun acknowledge(state: RemoteChatState, requestId: String): RemoteChatState {
        val key = owner(state)
        val request = pending[key]?.takeIf { it.requestId == requestId } ?: return state
        pending.remove(key)
        val clearRestored = request.restored && state.draft == request.text && (revisions[key] ?: 0L) == request.revision
        return state.copy(draft = if (clearRestored) "" else state.draft, failedPrompt = null)
    }

    fun clearProfile(profileId: String) {
        revisions.keys.removeAll { it.first == profileId }
        pending.keys.removeAll { it.first == profileId }
    }
}

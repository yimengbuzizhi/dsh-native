package xin.dshaa.nativeapp.remote

import org.junit.Assert.*
import org.junit.Test
import xin.dshaa.ui.shellapi.remote.RemoteChatState

class RemotePromptDraftsTest {
    private fun chat(profile: String = "A", session: String = "same", draft: String = "original") =
        RemoteChatState(session, "Remote", profileId = profile, draft = draft)

    @Test fun acceptedSubmitThenUiEmptyMirrorStillRestoresFailedDraft() {
        val drafts = RemotePromptDrafts()
        val sending = drafts.begin(chat(), "r", "original")
        val mirrored = drafts.edit(sending, "") // ChatScreen onDraftChange after trySend accepted it.
        assertSame(sending, mirrored)
        val failed = drafts.recover(mirrored, "result unknown")
        assertEquals("original", failed.draft); assertEquals("original", failed.failedPrompt)
        assertEquals("result unknown", failed.notice); assertEquals("r", drafts.requestId(failed))
    }

    @Test fun newEditOrDeliberatelyClearedEditNeverGetsOverwrittenByFailure() {
        val drafts = RemotePromptDrafts()
        val sending = drafts.begin(chat(), "r", "original")
        val edited = drafts.edit(sending, "new draft")
        assertEquals("new draft", drafts.recover(edited, "unknown").draft)
        val cleared = drafts.edit(edited, "")
        assertEquals("", drafts.recover(cleared, "unknown").draft)
        assertEquals("original", cleared.failedPrompt)
    }

    @Test fun invalidationKeepsVisibleDraftUntilMatchingDurableEchoWithoutResubmitting() {
        val drafts = RemotePromptDrafts()
        val detached = drafts.recover(drafts.begin(chat(), "r", "original"), "reconnect first")
        assertEquals("original", detached.draft)
        val unrelated = drafts.acknowledge(detached, "unrelated")
        assertSame(detached, unrelated)
        val confirmed = drafts.acknowledge(detached, "r")
        assertEquals("", confirmed.draft); assertNull(confirmed.failedPrompt); assertNull(drafts.requestId(confirmed))
        assertSame(confirmed, drafts.acknowledge(confirmed, "r"))
    }

    @Test fun durableEchoDoesNotClearUserOwnedTextEvenWhenItMatchesTheOldPrompt() {
        val drafts = RemotePromptDrafts()
        val failed = drafts.recover(drafts.begin(chat(), "r", "original"), "unknown")
        val edited = drafts.edit(drafts.edit(failed, "another draft"), "original")
        val confirmed = drafts.acknowledge(edited, "r")
        assertEquals("original", confirmed.draft); assertNull(confirmed.failedPrompt)
    }

    @Test fun sameSessionIdOnDifferentProfilesAndDifferentSessionsStayIndependent() {
        val drafts = RemotePromptDrafts()
        val a = drafts.begin(chat(), "a", "A prompt")
        val b = drafts.begin(chat("B"), "b", "B prompt")
        val other = drafts.begin(chat(session = "other"), "c", "other prompt")
        assertSame(a, drafts.acknowledge(a, "b"))
        assertEquals("A prompt", drafts.recover(a, "unknown").draft)
        assertEquals("B prompt", drafts.recover(b, "unknown").draft)
        assertEquals("other prompt", drafts.recover(other, "unknown").draft)
        drafts.acknowledge(a, "a")
        assertEquals("b", drafts.requestId(b)); assertEquals("c", drafts.requestId(other))
    }

    @Test fun lateEchoForPreviousPromptCannotClearNewRequestOrItsDraft() {
        val drafts = RemotePromptDrafts()
        val old = drafts.begin(chat(), "old", "old prompt")
        val next = drafts.begin(drafts.recover(old, "unknown"), "new", "new prompt")
        val recovered = drafts.recover(next, "unknown")
        assertSame(recovered, drafts.acknowledge(recovered, "old"))
        assertEquals("new prompt", recovered.draft); assertEquals("new", drafts.requestId(recovered))
    }

    @Test fun successfulAcknowledgementBeforeEchoRetiresRecoveryWithoutClearingNewDraft() {
        val drafts = RemotePromptDrafts()
        val pending = drafts.begin(chat(), "r", "original")
        val edited = drafts.edit(pending, "next message")
        val accepted = drafts.acknowledge(edited, "r")
        assertEquals("next message", accepted.draft); assertNull(accepted.failedPrompt)
        assertSame(accepted, drafts.recover(accepted, "switched targets"))
    }

    @Test fun credentialChangeClearsOnlyThatProfilesPendingRequestsAndEditRevisions() {
        val drafts = RemotePromptDrafts()
        val a = drafts.begin(chat(), "a", "A")
        val b = drafts.begin(chat("B"), "b", "B")
        drafts.clearProfile("A")
        assertNull(drafts.requestId(a)); assertEquals("b", drafts.requestId(b))
        assertSame(a, drafts.recover(a, "unknown"))
        assertEquals("B", drafts.recover(b, "unknown").draft)
    }
}

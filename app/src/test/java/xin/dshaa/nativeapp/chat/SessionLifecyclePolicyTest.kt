package xin.dshaa.nativeapp.chat

import org.junit.Assert.assertEquals
import org.junit.Test
import xin.dshaa.nativeapp.chat.SessionLifecyclePolicy.EmptyChatDisposition

/**
 * 两条涉及用户数据的拆除规则（M86 收尾修复）：
 * 1. 新建会话不能把还没落盘的草稿/附件一起丢掉；
 * 2. 删掉一条会话后，绑在它上面的运行时前后台都要作废。
 */
class SessionLifecyclePolicyTest {

    private fun dispose(
        sessionId: String? = null,
        messageCount: Int = 0,
        hasJob: Boolean = false,
        draft: CharSequence? = "",
        images: Int = 0,
        files: Int = 0,
    ) = SessionLifecyclePolicy.disposeOfUnpersistedChat(sessionId, messageCount, hasJob, draft, images, files)

    @Test
    fun trulyEmptyChatIsRecycled() {
        assertEquals(EmptyChatDisposition.RECYCLE, dispose())
        assertEquals(EmptyChatDisposition.RECYCLE, dispose(draft = "   \n"))
        assertEquals(EmptyChatDisposition.RECYCLE, dispose(images = 0, files = 0))
    }

    @Test
    fun typedDraftIsCarriedInsteadOfDropped() {
        assertEquals(EmptyChatDisposition.CARRY_DRAFT, dispose(draft = "半句话"))
        assertEquals(EmptyChatDisposition.CARRY_DRAFT, dispose(images = 1))
        assertEquals(EmptyChatDisposition.CARRY_DRAFT, dispose(files = 2))
        assertEquals(EmptyChatDisposition.CARRY_DRAFT, dispose(draft = "看图", images = 1, files = 1))
    }

    @Test
    fun persistedOrRunningChatIsKept() {
        assertEquals(EmptyChatDisposition.KEEP, dispose(sessionId = "s-1"))
        assertEquals(EmptyChatDisposition.KEEP, dispose(messageCount = 1))
        assertEquals(EmptyChatDisposition.KEEP, dispose(hasJob = true))
        // 已经落盘/在跑的会话即使有草稿也不能被回收或搬走（草稿属于那条会话）
        assertEquals(EmptyChatDisposition.KEEP, dispose(sessionId = "s-1", draft = "草稿", images = 1))
    }

    @Test
    fun everyRuntimeOfTheDeletedSessionIsInvalidated() {
        val entries = mapOf(
            "runtime-foreground" to "session-a",
            "runtime-background" to "session-a",
            "runtime-other" to "session-b",
            "runtime-unsaved" to null,
        )
        assertEquals(
            listOf("runtime-foreground", "runtime-background"),
            SessionLifecyclePolicy.runtimesForDeletedSession(entries, "session-a"),
        )
        assertEquals(
            listOf("runtime-other"),
            SessionLifecyclePolicy.runtimesForDeletedSession(entries, "session-b"),
        )
        assertEquals(emptyList<String>(), SessionLifecyclePolicy.runtimesForDeletedSession(entries, "session-c"))
        assertEquals(
            emptyList<String>(),
            SessionLifecyclePolicy.runtimesForDeletedSession(emptyMap<String, String?>(), "session-a"),
        )
    }
}

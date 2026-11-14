package xin.dshaa.nativeapp.chat

/**
 * 会话拆除时的纯决策（M86-FGH 收尾，2026-11-14）。
 *
 * `AppViewModel` 是 AndroidViewModel，JVM 单测里起不来；把「怎么处理一条还没落盘的旧会话」
 * 和「删掉一条会话后哪些运行时必须作废」这两条涉及用户数据安全的规则放在这里，
 * 单测直接覆盖，调用点只负责执行。
 */
internal object SessionLifecyclePolicy {

    /** 新建会话时，对一条还没有会话文件的旧运行时的处理方式。 */
    enum class EmptyChatDisposition {
        /** 真的是空草稿：直接回收运行时。 */
        RECYCLE,

        /** 还没有会话文件，但输入框/待发附件里有用户内容：搬去新会话，再回收运行时。 */
        CARRY_DRAFT,

        /** 已经落过盘或有轮次在跑：不是空草稿，原样留在后台。 */
        KEEP,
    }

    /**
     * 旧实现只看 sessionId/messages/job，会把「输入框写着字、贴着图、选着文件但还没发」的
     * 草稿跟运行时一起丢掉——这些内容还不在任何会话文件里，丢了就真没了（用户数据）。
     */
    fun disposeOfUnpersistedChat(
        sessionId: String?,
        messageCount: Int,
        hasJob: Boolean,
        draftText: CharSequence?,
        pendingImages: Int,
        pendingFiles: Int,
    ): EmptyChatDisposition = when {
        sessionId != null || messageCount > 0 || hasJob -> EmptyChatDisposition.KEEP
        !draftText.isNullOrBlank() || pendingImages > 0 || pendingFiles > 0 -> EmptyChatDisposition.CARRY_DRAFT
        else -> EmptyChatDisposition.RECYCLE
    }

    /**
     * 存储里删掉 [deletedId] 之后，表里**所有**绑到这个 id 的运行时都必须作废（前台和后台一样）。
     *
     * 旧实现只在删的是当前会话时移除运行时：后台会话 A 被 `discardSessionQueue` 置
     * `acceptingInputs=false` 后仍留在表里，从回收站恢复再打开 A 会复用这条失效运行时，
     * 发送/附件一直被拒，直到进程重建 ViewModel。
     */
    fun <T> runtimesForDeletedSession(entries: Map<T, String?>, deletedId: String): List<T> =
        entries.entries.filter { it.value == deletedId }.map { it.key }
}

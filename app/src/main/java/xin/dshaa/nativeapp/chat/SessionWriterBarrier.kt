package xin.dshaa.nativeapp.chat

import kotlinx.coroutines.Job

/**
 * 一批**已经从会话运行时表里摘掉、但还没真正写完**的会话 writer（M86-FGH 收尾，2026-11-14）。
 *
 * 背景：切工作区时 `AppViewModel.lives` 会立刻清空，但被取消的轮次还要在 `NonCancellable`
 * 收尾里补写 tool/result、step/end、turn/end（`TurnEngine.runTurn` 的 catch/finally）。
 * 如果这期间就把工作区目录（以及它名下的会话目录）移进回收站，这些写入会落到已经移动的
 * 路径上：回收站里的历史缺一段，甚至写坏。
 *
 * 唯一可靠的完成信号是 `Job.join()`——引擎把收尾写在同一个 job 里，join 一定等它写完；
 * 固定 delay、「清空 UI 就算完」都不算。这个类只做「按 key 记账 + 取消 + join」，
 * 不依赖 Android，JVM 单测可以直接验证顺序。
 *
 * 只在主线程访问（生产侧都是 `viewModelScope` 的 Main.immediate；单测单线程）。
 */
internal class SessionWriterBarrier {

    /** key（工作区绝对路径）→ 还没完成的 writer job（按登记顺序）。 */
    private val writers = LinkedHashMap<String, MutableList<Job>>()

    /** 记一个已经脱离 UI 的 writer；已完成的直接忽略（join 没有意义）。 */
    fun track(key: String, job: Job) {
        if (job.isCompleted) return
        // 已经写完的旧 writer 不再需要（join 会立刻返回）：顺手清掉，别让记账随切换次数增长。
        writers.values.forEach { jobs -> jobs.removeAll { it.isCompleted } }
        writers.entries.removeAll { it.value.isEmpty() }
        writers.getOrPut(key) { ArrayList() } += job
    }

    /** 取消并等待 [key] 的全部 writer 真正结束；返回真正等过的数量。 */
    suspend fun cancelAndJoinAll(key: String): Int = drain(key) { it.cancel() }

    /** 只等待（不再 cancel）[key] 的 writer 结束；用于已经取消过的收尾。 */
    suspend fun joinAll(key: String): Int = drain(key) { }

    /** 还没结束的 writer 数（诊断/测试用，不等待）。 */
    fun pending(key: String): Int = writers[key].orEmpty().count { !it.isCompleted }

    private suspend fun drain(key: String, before: (Job) -> Unit): Int {
        val pending = writers.remove(key) ?: return 0
        pending.forEach(before)
        pending.forEach { it.join() }
        return pending.size
    }
}

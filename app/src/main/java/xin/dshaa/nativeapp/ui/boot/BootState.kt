package xin.dshaa.nativeapp.ui.boot

/** Host-owned startup state. A loading plugin only sees the three display bindings. */
enum class BootStatus { LOADING, READY, FAILED }

data class BootState(
    val phase: String = "starting",
    val message: String = "正在准备…",
    val elapsedMs: Long = 0L,
    val status: BootStatus = BootStatus.LOADING,
    val failure: String? = null,
) {
    fun binding(path: String): String = when (path) {
        "boot.phase" -> phase.take(120)
        "boot.message" -> message.take(2000)
        "boot.elapsed" -> "${elapsedMs.coerceAtLeast(0) / 1000}s"
        else -> ""
    }
}

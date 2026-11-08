package xin.dshaa.core.parity

import java.io.File
import kotlin.system.exitProcess

/**
 * 对拍台命令行（门 2）。
 *
 * ```
 * parity replay  --capture <file|dir> [--port 18080] [--real-time] [--strict]
 * parity compare --a <runDir> --b <runDir> [--workdir <path>] [--scenario <id>] [--out report.json]
 * parity inspect --capture <file>
 * ```
 */
fun main(args: Array<String>) {
    when (val cmd = args.firstOrNull()) {
        "replay" -> cmdReplay(Args(args.drop(1)))
        "compare" -> cmdCompare(Args(args.drop(1)))
        "inspect" -> cmdInspect(Args(args.drop(1)))
        else -> {
            usage()
            exitProcess(2)
        }
    }
}

private fun usage() {
    println(
        """
        parity replay  --capture <file|dir> [--port 18080] [--real-time] [--strict]
        parity compare --a <runDir> --b <runDir> [--workdir <path>] [--scenario <id>] [--out report.json]
        parity inspect --capture <file>
        """.trimIndent(),
    )
}

private fun cmdReplay(args: Args) {
    val path = args.str("capture") ?: run {
        usage()
        exitProcess(2)
    }
    val file = File(path)
    val captures = if (file.isDirectory) SseCapture.readAll(file) else listOf(SseCapture.read(file))
    if (captures.isEmpty()) {
        System.err.println("没有捕获：$path")
        exitProcess(2)
    }
    val server = ReplayServer(
        captures = captures,
        port = args.int("port", ReplayServer.DEFAULT_PORT),
        realTime = args.bool("real-time"),
        strictMatch = args.bool("strict"),
        loops = args.int("loops", 1),
    ).start()
    val bytes = captures.sumOf { it.header.byteCount }
    println("PARITY_REPLAY_READY port=${server.actualPort} fixtures=${captures.size} bytes=$bytes")
    System.out.flush()
    Runtime.getRuntime().addShutdownHook(
        Thread {
            println(
                "PARITY_REPLAY_STATS served=${server.requests.size} problems=${server.problems.size}",
            )
            server.problems.forEach { System.err.println("problem: $it") }
        },
    )
    while (true) {
        Thread.sleep(60_000)
    }
}

private fun cmdCompare(args: Args) {
    val dirA = File(args.str("a") ?: run { usage(); exitProcess(2) })
    val dirB = File(args.str("b") ?: run { usage(); exitProcess(2) })
    val report = ParityCompare.compare(dirA, dirB, workdir = args.str("workdir"), scenario = args.str("scenario"))
    val json = report.toJson().toString()
    args.str("out")?.let { out ->
        File(out).apply { parentFile?.mkdirs() }.writeText(json + "\n", Charsets.UTF_8)
    }
    println(report.text())
    println(json)
    exitProcess(if (report.status == "PASS") 0 else 1)
}

private fun cmdInspect(args: Args) {
    val path = args.str("capture") ?: run { usage(); exitProcess(2) }
    val file = File(path)
    val captures = if (file.isDirectory) SseCapture.readAll(file) else listOf(SseCapture.read(file))
    captures.forEachIndexed { i, c ->
        println("[$i] chunks=${c.header.chunkCount} bytes=${c.header.byteCount} requestSha=${c.header.requestSha256?.take(12)} note=${c.header.note}")
        println("    chunkSizes=${c.chunkSizes().take(12)}…")
        val body = c.bytes().toString(Charsets.UTF_8)
        println("    events=${body.split("\n").count { it.startsWith("event:") }} firstLine=${body.lineSequence().firstOrNull()?.take(80)}")
    }
}

/** 极简 `--key value` / `--flag` 解析。 */
private class Args(raw: List<String>) {
    private val values = LinkedHashMap<String, String?>()

    init {
        var i = 0
        while (i < raw.size) {
            val token = raw[i]
            if (!token.startsWith("--")) {
                i++
                continue
            }
            val key = token.removePrefix("--")
            val next = raw.getOrNull(i + 1)
            if (next != null && !next.startsWith("--")) {
                values[key] = next
                i += 2
            } else {
                values[key] = null
                i++
            }
        }
    }

    fun str(key: String): String? = values[key]
    fun bool(key: String): Boolean = values.containsKey(key)
    fun int(key: String, default: Int): Int = values[key]?.toIntOrNull() ?: default
}

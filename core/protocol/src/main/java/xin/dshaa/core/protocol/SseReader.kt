package xin.dshaa.core.protocol

import java.io.BufferedReader

/**
 * SSE（text/event-stream）行解析。
 *
 * 只处理 DeepSeek/OpenAI 用到的子集：
 * - `data: {...}` / `data:{...}`（冒号后空格可有可无）
 * - `data: [DONE]`
 * - `event: ...`、注释行 `:` 忽略
 * - 以空行结束一个 event；多行 data 用 \n 拼接
 */
class SseReader(private val reader: BufferedReader) {

    sealed interface Event {
        data class Data(val payload: String) : Event
        data object Done : Event
        data class Comment(val text: String) : Event
    }

    fun next(): Event? {
        val data = StringBuilder()
        var sawField = false
        while (true) {
            val line = reader.readLine() ?: return if (sawField) finish(data) else null
            if (line.isEmpty()) {
                if (sawField) return finish(data)
                continue
            }
            if (line.startsWith(":")) {
                if (!sawField) return Event.Comment(line)
                continue
            }
            val colon = line.indexOf(':')
            val field = if (colon < 0) line else line.substring(0, colon)
            var value = if (colon < 0) "" else line.substring(colon + 1)
            if (value.startsWith(" ")) value = value.substring(1)
            when (field) {
                "data" -> {
                    if (data.isNotEmpty()) data.append('\n')
                    data.append(value)
                    sawField = true
                }
                "event", "id", "retry" -> sawField = true
                else -> Unit
            }
        }
    }

    private fun finish(data: StringBuilder): Event {
        val s = data.toString()
        return if (s.trim() == "[DONE]") Event.Done else Event.Data(s)
    }
}

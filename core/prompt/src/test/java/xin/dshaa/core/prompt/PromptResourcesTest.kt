package xin.dshaa.core.prompt

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * prompt 资源完整性（M69 修复的回归测试）。
 *
 * 2026-11-10 发现 `tools-android.json` 在 `get_terminal_session_screen` 与 `query_memory`
 * 之间少一个 `},`：`PromptPack.load` 用 `runCatching` 解析，失败就返回空表，
 * 于是 **M56 之后 device/terminal/memory 工具的 schema 一直没发给模型**（registry 里有、
 * 请求 tools 里没有）。这个测试保证 4 份资源都能解析、Android 工具名还在。
 */
class PromptResourcesTest {

    @Test
    fun androidToolSchemasAreLoadable() {
        val names = PromptPack.load().androidToolDefinitions.map { it.name }.toSet()
        assertTrue("device_info" in names, "缺 device_info：tools-android.json 可能又解析失败了")
        assertTrue("query_memory" in names, "缺 query_memory：记忆工具没有发给模型")
        assertTrue("create_terminal_session" in names, "缺终端工具")
        assertTrue(names.size >= 25, "Android 工具数量异常：${names.size}")
    }

    @Test
    fun upstreamToolTableStillParses() {
        val names = PromptPack.load().toolDefinitions.map { it.name }.toSet()
        assertTrue("read" in names)
        assertTrue("bash" !in names && "pwsh" in names, "上游表里应是 pwsh，Android 侧才改名 bash")
        assertTrue(names.size >= 30)
    }
}

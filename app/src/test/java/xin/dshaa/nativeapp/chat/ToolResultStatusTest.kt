package xin.dshaa.nativeapp.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import xin.dshaa.ui.shellapi.chat.toolResultLooksFailed

/**
 * 工具卡失败态识别（M87-A / H1）。
 *
 * 真机验收抓到：max_tokens 截断的 tool_result 正文是英文，旧规则没覆盖，工具卡显示绿色对勾，
 * 与「工具调用没有执行」的提示矛盾。规则已集中到 shell-api，这里守回归。
 */
class ToolResultStatusTest {

    @Test
    fun `max_tokens truncated tool result is a failure`() {
        val text = "Tool call was not executed: the response hit the max_tokens limit, so its arguments may be " +
            "incomplete. Treat this call as failed; do not retry it unless the user asks."
        assertTrue(toolResultLooksFailed(text))
    }

    @Test
    fun `plugin guard and restrict rejections are failures`() {
        assertTrue(toolResultLooksFailed("[插件「m87a-guard」拒绝] M87A-GUARD：bash 被 fixture 拒绝"))
        assertTrue(toolResultLooksFailed("工具 bash 已被插件的 restrict 规则禁用，本次调用未执行。"))
        // docs/190-G：模型可见文案英文化后的同义失败（旧会话的中文仍要识别）。
        assertTrue(toolResultLooksFailed("Tool bash is disabled by a plugin restrict rule; this call was not executed."))
    }

    @Test
    fun `plugin before-append tool veto fallback is a failure`() {
        // M87-E：storage before-append 拒绝原始结果后，宿主补写的 trusted 失败结果。
        val fallback = "工具结果被插件拒绝写入（插件 m87e-storage拒绝写入 tool/result：" +
            "插件「m87e-storage」拒绝写入 tool/result：M87E 工具结果拒绝）。宿主已保留失败状态，原始输出未落盘。"
        assertTrue(toolResultLooksFailed(fallback))
        // docs/190-G：英文版 fallback（TurnEngine.appendToolResultGuarded）。
        assertTrue(
            toolResultLooksFailed(
                "The plugin rejected writing this tool result (denied). The host kept the failure state; " +
                    "the original output was not persisted.",
            ),
        )
    }

    @Test
    fun `tool input errors are failures`() {
        // M88-G：工具输入超限 / 半截 JSON 的显式错误（正文见 core:engine ToolInputGuard）。
        assertTrue(
            toolResultLooksFailed(
                "工具输入过长：65544 字节 > 上限 65536 字节（fake_tool），本次调用未执行。请把内容分段/分批重试。",
            ),
        )
        assertTrue(toolResultLooksFailed("工具参数不是合法 JSON（可能被截断），本次调用未执行。请拆成多次调用。"))
        assertTrue(toolResultLooksFailed("参数被截断"))
        // docs/190-G：英文版 ToolInputGuard 文案。
        assertTrue(
            toolResultLooksFailed(
                "Tool input too long: 65544 bytes > limit 65536 bytes (fake_tool); this call was not executed.",
            ),
        )
        assertTrue(toolResultLooksFailed("Tool arguments are not valid JSON (truncated or malformed), so this call was not executed."))
        assertTrue(toolResultLooksFailed("The arguments were truncated; split the content into chunks."))
    }

    @Test
    fun `legacy failure wording still detected`() {
        assertTrue(toolResultLooksFailed("User denied this tool call"))
        assertTrue(toolResultLooksFailed("<error>boom</error>"))
        assertTrue(toolResultLooksFailed("Error: connect failed"))
        assertTrue(toolResultLooksFailed("command not found: foo"))
    }

    @Test
    fun `normal results are not failures`() {
        assertFalse(toolResultLooksFailed("hi"))
        assertFalse(toolResultLooksFailed("工具跑完了（debug.tool 验收）"))
        assertFalse(toolResultLooksFailed("PRE-EXEC\n[M87A-POST]"))
        assertFalse(toolResultLooksFailed(""))
    }
}

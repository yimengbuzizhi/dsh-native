package xin.dshaa.ui.shellapi.chat

/**
 * 工具结果是否该在工具卡上显示为失败（红色）。
 *
 * 引擎和插件目前没有把「isError」结构化带到 UI 模型里，工具卡只能按结果正文识别；
 * 规则集中在这里，避免新增一种失败文案时又漏掉（0.3.5 真机验收：max_tokens 截断的
 * tool_result 正文是英文，旧规则没覆盖，卡片误显示绿色对勾）。
 */
private val NON_ZERO_EXIT_CODE = Regex("""\[exit code:\s*(?!0\s*\])-?\d+\]""")

fun toolResultLooksFailed(text: String): Boolean =
    text.startsWith("User denied") ||
        text.contains("not found", ignoreCase = true) ||
        text.startsWith("<error>") ||
        text.contains("error:", ignoreCase = true) ||
        text.contains("command timed out after ", ignoreCase = true) ||
        text.startsWith("spawn failed:", ignoreCase = true) ||
        text.contains("path escapes workspace", ignoreCase = true) ||
        NON_ZERO_EXIT_CODE.containsMatchIn(text) ||
        // M87-A：插件 guard / restrict 的拒绝（docs/190-G：模型可见文案已英文化，旧会话仍可能是中文）。
        text.contains("拒绝]") ||
        text.contains("restrict 规则禁用") ||
        text.contains("disabled by a plugin restrict rule", ignoreCase = true) ||
        // H1：max_tokens 把 tool_use 截断，引擎补的 error tool_result（正文见 TurnEngine）。
        text.contains("hit the max_tokens limit", ignoreCase = true) ||
        // M87-E：storage:hooks 拒绝原始工具结果后，宿主补写的 trusted 失败结果
        // （正文见 TurnEngine.appendToolResultGuarded；原始输出不落盘）。
        text.contains("工具结果被插件拒绝写入") ||
        text.contains("rejected writing this tool result", ignoreCase = true) ||
        // M88-G：工具输入超限 / 参数半截（正文见 core:engine ToolInputGuard；docs/190-G 英文化）。
        text.contains("工具输入过长") ||
        text.contains("不是合法 JSON") ||
        text.contains("参数被截断") ||
        text.contains("tool input too long", ignoreCase = true) ||
        text.contains("not valid JSON", ignoreCase = true) ||
        text.contains("arguments were truncated", ignoreCase = true)

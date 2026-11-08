package xin.dshaa.core.tools.workspace

import kotlinx.serialization.json.JsonObject
import xin.dshaa.core.tools.DshTool
import xin.dshaa.core.tools.ToolContext
import xin.dshaa.core.tools.ToolResult

/**
 * `load_workspace_dependencies`（工具 30）：查询随包 Python/Node 的绝对路径与发行版版本。
 *
 * Android 版现状（docs/35）：**没有**随包 Python/Node payload（桌面版装在
 * `dsh-runtimes/<runtime>/dependencies/`，Android 包体不携带），所以这里如实报不可用，
 * 不编造路径。工具 schema/description 与上游一致（契约 #2），模型据此改用系统工具/终端。
 * 以后接入 Operit 的 Python 运行时，只要在这里返回真实路径即可。
 */
class LoadWorkspaceDependenciesTool : DshTool {
    override val name = "load_workspace_dependencies"

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult =
        ToolResult.err(
            "workspace dependencies are not bundled in this Android build: no Python/Node.js payload is installed. " +
                "Use the terminal, the system shell, or a self-contained approach instead (this does not change PATH).",
        )
}

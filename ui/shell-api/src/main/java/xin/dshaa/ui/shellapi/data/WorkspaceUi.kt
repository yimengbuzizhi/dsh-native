package xin.dshaa.ui.shellapi.data

/** 设置页/抽屉用的工作区投影（带会话数与当前标记）。 */
data class WorkspaceUi(
    val id: String,
    val name: String,
    val path: String,
    val managed: Boolean,
    val count: Int,
    val current: Boolean,
    /** 目录当前是否存在（旧版/其他 App 的路径可能已经不存在，只能回看，不能写文件）。 */
    val exists: Boolean,
)

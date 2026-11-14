package xin.dshaa.ui.shellapi

/** 页面层级：一级页从聊天/设置进；二级页挂在「设置」下面（M79 的转场深度也按它算）。 */
enum class PageLevel { Primary, Secondary }

/**
 * M85-C2（`page/v1` 雏形）：**壳声明的页面**。
 *
 * 交给宿主的只有 id/标题/层级这类元数据（调试 dump、清单校验用）；页面怎么画、怎么路由
 * 是壳自己的事——[NativeShell.Root] 内部不看宿主的页面清单。
 *
 * M85-C3-2：随 `NativeShell` 一起搬进 `ui:shell-api`。M85-C3-4 起统一注册表
 * [ShellPageRegistry] 也在本模块（壳声明页 + M84-G 插件整页并成一张表）。
 */
data class ShellPage(
    val id: String,
    val title: String,
    val level: PageLevel,
)

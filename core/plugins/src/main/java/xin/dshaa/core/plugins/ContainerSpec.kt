package xin.dshaa.core.plugins

/**
 * P3-e（`docs/187` §9.2 / `docs/185` §九 P3）：**三种插件容器**的纯数据定义。
 *
 * 为什么把「容器」抽成一份不依赖 Android 的数据：容器只有三种，不允许随上游界面贡献增长
 * （`docs/187` §9.2 验收口径）；几何、能力名、标题都在这里定死，原生窗口实现只负责照着摆，
 * JVM 用例就能锁死「不偷偷加第四种」「sheet 一定贴底」「floating 一定可拖」这些约束。
 *
 * **红线**：这里没有 slot 协议、没有会话流内嵌、没有逐插件专属落点（D9）——
 * 容器只是「把插件自己的 `exports["./client"]` 塞进三种壳之一」，插件 id 不进任何分支。
 */
enum class ContainerKind {
    /** 整屏一个插件页面（有原生标题栏与关闭按钮，背后压暗）。 */
    FULLSCREEN,

    /** 现有悬浮窗：300×380 dp，双指拖动、位置按插件落盘、手势可穿透。 */
    FLOATING,

    /** 贴底抽屉：满宽、约 2/3 屏高、圆角、可下滑收起（原生标题栏 + 关闭）。 */
    SHEET;

    /** 稳定字符串（原型参数 / Intent extra / 日志 / 证据 JSON 都用它）。 */
    val wire: String
        get() = when (this) {
            FULLSCREEN -> "fullscreen"
            FLOATING -> "floating"
            SHEET -> "sheet"
        }

    companion object {
        val all: List<ContainerKind> = listOf(FULLSCREEN, FLOATING, SHEET)

        /** 解析 wire 值；未知/空一律 null（调用方报错，不悄悄退化成悬浮窗）。 */
        fun parse(raw: String?): ContainerKind? {
            val text = raw?.trim()?.lowercase().orEmpty()
            if (text.isEmpty()) return null
            return all.firstOrNull { it.wire == text }
                ?: when (text) {
                    "full", "full-screen", "page", "activity" -> FULLSCREEN
                    "float", "overlay", "window" -> FLOATING
                    "bottom", "bottom-sheet", "drawer" -> SHEET
                    else -> null
                }
        }
    }
}

/**
 * 一种容器的窗口几何（dp；`null` 表示「占满可用空间」，由原生按屏幕算）。
 *
 * [anchoredBottom] 的容器从底部往上长；[draggable] 只对悬浮窗为 true；
 * [dimBehind] 是窗口背后的压暗比例（0 = 不压暗、1 = 全黑），
 * 由容器自己决定 —— 悬浮窗不压暗，否则桌面宠物旁边会糊成一片。
 */
data class ContainerGeometry(
    val widthDp: Float?,
    val heightDp: Float?,
    val anchoredBottom: Boolean,
    val draggable: Boolean,
    val dimBehind: Float,
    /** 是否显示原生标题栏（标题 + 关闭）。悬浮窗不显示，免得盖住宠物。 */
    val chrome: Boolean,
)

object ContainerSpec {

    /** 悬浮窗的默认尺寸（与原 `ClientPluginWindowService` 的 M70 行为一致）。 */
    const val FLOATING_WIDTH_DP = 300f
    const val FLOATING_HEIGHT_DP = 380f

    /** sheet 占屏高比例（0.62 ≈ 2/3，给下方留出能看见聊天页的余量）。 */
    const val SHEET_HEIGHT_RATIO = 0.62f

    /** 三种容器，只有三种；顺序 = 设置页/提示里的展示顺序。 */
    val kinds: List<ContainerKind> = ContainerKind.all

    fun geometry(kind: ContainerKind): ContainerGeometry = when (kind) {
        ContainerKind.FULLSCREEN -> ContainerGeometry(
            widthDp = null,
            heightDp = null,
            anchoredBottom = false,
            draggable = false,
            dimBehind = 0f,
            chrome = true,
        )

        ContainerKind.FLOATING -> ContainerGeometry(
            widthDp = FLOATING_WIDTH_DP,
            heightDp = FLOATING_HEIGHT_DP,
            anchoredBottom = false,
            draggable = true,
            dimBehind = 0f,
            chrome = false,
        )

        ContainerKind.SHEET -> ContainerGeometry(
            widthDp = null,
            heightDp = null,
            anchoredBottom = true,
            draggable = false,
            dimBehind = 0.28f,
            chrome = true,
        )
    }

    /** sheet 的实际高度（像素）：满宽 + 屏高比例，夹在 [minPx] 与屏高之间。 */
    fun sheetHeightPx(screenHeightPx: Int, minPx: Int): Int {
        val raw = (screenHeightPx * SHEET_HEIGHT_RATIO).toInt()
        return raw.coerceIn(minPx.coerceAtMost(screenHeightPx), screenHeightPx)
    }

    /**
     * 容器给插件的**全部**能力（`docs/187` §9.2 原文）：关闭自己 / 设标题 / 发消息 / 读主题 /
     * 设备能力走桥。刻意只有这 5 个：
     * - 没有 slot 注册（红线：不做 slot 协议）；
     * - 没有「往会话流里插消息」的入口（红线：不做会话流内嵌）；
     * - 设备能力只经 [DEVICE] 一次问全，不按插件分叉（D9）。
     */
    const val CLOSE_SELF = "closeSelf"
    const val SET_TITLE = "setTitle"
    const val POST_MESSAGE = "postMessage"
    const val THEME = "theme"
    const val DEVICE = "device"

    val capabilities: List<String> = listOf(CLOSE_SELF, SET_TITLE, POST_MESSAGE, THEME, DEVICE)

    fun hasCapability(name: String): Boolean = capabilities.any { it.equals(name.trim(), ignoreCase = true) }

    /** 原生标题栏的默认标题（插件没 setTitle 时用）。 */
    fun defaultTitle(kind: ContainerKind, pluginName: String): String {
        val fallback = pluginName.ifBlank { "插件" }
        return when (kind) {
            ContainerKind.FULLSCREEN -> fallback
            ContainerKind.FLOATING -> fallback
            ContainerKind.SHEET -> "$fallback · 抽屉"
        }
    }
}

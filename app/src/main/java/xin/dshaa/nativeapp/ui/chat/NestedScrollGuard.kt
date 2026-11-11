package xin.dshaa.nativeapp.ui.chat

import androidx.compose.foundation.ScrollState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll

/**
 * 内层滚动到头后，把剩余手势吃掉，不再传给外层的聊天列表。
 *
 * 用户 2026-11-11 反馈：思考过程、引用包/文件的正文（内部滚动）滚到底后，
 * 整个聊天页会跟着继续往下滑。Compose 的嵌套滚动默认会这么链下去，这里在
 * 内层滚动容器的外层加一个连接器，把「内层已经吃不下」的那部分 delta 消费掉。
 *
 * 只在 [ScrollState.maxValue] > 0（内层真的能滚）时拦截：内容没溢出就完全不碰，
 * 否则手指按在一段短文本上会把聊天列表也锁住。
 */
internal fun Modifier.consumeInnerScroll(state: ScrollState): Modifier =
    nestedScroll(
        object : NestedScrollConnection {
            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource,
            ): Offset {
                if (state.maxValue <= 0) return Offset.Zero
                val atTop = !state.canScrollBackward
                val atBottom = !state.canScrollForward
                if (!atTop && !atBottom) return Offset.Zero
                return Offset(0f, available.y)
            }
        },
    )

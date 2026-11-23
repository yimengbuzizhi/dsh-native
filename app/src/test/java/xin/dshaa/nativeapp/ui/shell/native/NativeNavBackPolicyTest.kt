package xin.dshaa.nativeapp.ui.shell.native

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * H3（docs/182）+ docs/190 §3-A：壳内返回栈。
 *
 * 设备离线时先在 JVM 上锁死状态机：一级页（设置 → 记忆库/会话…）和二级页（设置 → 外观…）
 * 都要能连续返回两层；聊天页返回交给系统退出 App（`isChat` 不拦截）。
 */
class NativeNavBackPolicyTest {

    private val settings = "settings"
    private val memory = "memory"
    private val sessions = "sessions"
    private val privacy = "privacy"
    private val appearance = "appearance"
    private val modelConfig = "model-config"

    private fun chat() = NativeNavBackPolicy.chat()

    /** 聊天 → 设置（从聊天进入，设置自己的来路是聊天）。 */
    private fun atSettings(): NativeNavBackPolicy.State =
        NativeNavBackPolicy.onPrimaryOpen(chat(), settings)

    @Test
    fun `chat to settings to memory back back returns to chat`() {
        var s = atSettings()
        assertEquals(settings, s.screen)
        assertNull(s.backPage)

        s = NativeNavBackPolicy.onPrimaryOpen(s, memory)
        assertEquals(memory, s.screen)
        assertEquals(settings, s.backPage)

        // 返回 → 设置，并且恢复「设置自己的来路 = 聊天」
        s = NativeNavBackPolicy.onBackFromPrimary(s)
        assertEquals(settings, s.screen)
        assertNull(s.sub)
        assertNull(s.backPage)
        assertNull(s.pageReturnPage)

        // 再返回 → 聊天（修复前这里原地不动）
        s = NativeNavBackPolicy.onBackFromPrimary(s)
        assertTrue(s.isChat)
    }

    @Test
    fun `settings to sessions back back returns to chat`() {
        var s = NativeNavBackPolicy.onPrimaryOpen(atSettings(), sessions)
        assertEquals(settings, s.backPage)
        s = NativeNavBackPolicy.onBackFromPrimary(s)
        assertEquals(settings, s.screen)
        assertNull(s.backPage)
        s = NativeNavBackPolicy.onBackFromPrimary(s)
        assertTrue(s.isChat)
    }

    @Test
    fun `settings to privacy to sessions keeps two levels`() {
        // 设置 → 隐私（二级页）；隐私页里的「会话」入口 = closeSub() 后 go(Sessions)（见 NativePageDispatch）。
        var s = NativeNavBackPolicy.onSubOpen(atSettings(), privacy, settings)
        assertEquals(settings, s.screen)
        assertEquals(privacy, s.sub)
        assertEquals(settings, s.backPage)
        assertNull(s.pageReturnPage)

        s = NativeNavBackPolicy.onSubClose(s)
        assertEquals(settings, s.screen)
        assertNull(s.sub)
        assertNull(s.backPage)

        s = NativeNavBackPolicy.onPrimaryOpen(s, sessions)
        assertEquals(settings, s.backPage)
        s = NativeNavBackPolicy.onBackFromPrimary(s)
        assertEquals(settings, s.screen)
        assertNull(s.backPage)
        s = NativeNavBackPolicy.onBackFromPrimary(s)
        assertTrue(s.isChat)
    }

    @Test
    fun `same level sub switch does not overwrite settings return target`() {
        // 设置 → 隐私 →（隐私页里的二级入口）模型配置 → 返回 → 设置 → 返回 → 聊天
        var s = NativeNavBackPolicy.onSubOpen(atSettings(), privacy, settings)
        s = NativeNavBackPolicy.onSubOpen(s, modelConfig, settings)
        assertEquals(modelConfig, s.sub)
        s = NativeNavBackPolicy.onSubClose(s)
        assertEquals(settings, s.screen)
        assertNull(s.sub)
        assertNull(s.backPage) // 没有被同级切换污染成 settings（否则会原地卡住）
        s = NativeNavBackPolicy.onBackFromPrimary(s)
        assertTrue(s.isChat)
    }

    @Test
    fun `drawer to settings to memory back returns to chat`() {
        // 抽屉在聊天页打开：来路快照 = (null, null)
        var s = NativeNavBackPolicy.onPrimaryOpen(chat(), settings, originPage = null, originSub = null)
        assertEquals(settings, s.screen)
        assertNull(s.backPage)
        s = NativeNavBackPolicy.onPrimaryOpen(s, memory)
        s = NativeNavBackPolicy.onBackFromPrimary(s)
        assertEquals(settings, s.screen)
        s = NativeNavBackPolicy.onBackFromPrimary(s)
        assertTrue(s.isChat)
    }

    @Test
    fun `drawer entry from a primary page returns to that page then its origin`() {
        // 聊天 → 设置 → 记忆库，在记忆库打开抽屉 → 会话 → 返回 → 记忆库 → 返回 → 设置 → 聊天
        var s = NativeNavBackPolicy.onPrimaryOpen(atSettings(), memory)
        s = NativeNavBackPolicy.onPrimaryOpen(s, sessions, originPage = memory, originSub = null)
        assertEquals(memory, s.backPage)
        s = NativeNavBackPolicy.onBackFromPrimary(s)
        assertEquals(memory, s.screen)
        assertEquals(settings, s.backPage)
        s = NativeNavBackPolicy.onBackFromPrimary(s)
        assertEquals(settings, s.screen)
        s = NativeNavBackPolicy.onBackFromPrimary(s)
        assertTrue(s.isChat)
    }

    @Test
    fun `chat to model config back returns to chat`() {
        var s = NativeNavBackPolicy.onSubOpen(chat(), modelConfig, settings)
        assertEquals(settings, s.screen)
        assertEquals(modelConfig, s.sub)
        assertNull(s.backPage)
        s = NativeNavBackPolicy.onSubClose(s)
        assertTrue(s.isChat)
    }

    @Test
    fun `settings to appearance back back returns to chat`() {
        var s = NativeNavBackPolicy.onSubOpen(atSettings(), appearance, settings)
        s = NativeNavBackPolicy.onSubClose(s)
        assertEquals(settings, s.screen)
        assertNull(s.backPage)
        s = NativeNavBackPolicy.onBackFromPrimary(s)
        assertTrue(s.isChat)
    }

    @Test
    fun `drawer sub page from settings keeps settings return target`() {
        // 设置（来路聊天）→ 抽屉 → 助手配置 → 返回 → 设置 → 返回 → 聊天
        var s = NativeNavBackPolicy.onSubOpen(atSettings(), "assistant", settings, originPage = settings)
        assertEquals(settings, s.backPage)
        s = NativeNavBackPolicy.onSubClose(s)
        assertEquals(settings, s.screen)
        assertNull(s.backPage)
        s = NativeNavBackPolicy.onBackFromPrimary(s)
        assertTrue(s.isChat)
    }

    @Test
    fun `opening current primary page again does not push`() {
        // 设置首页再从抽屉点「设置」：no-op，返回目标保持聊天（不制造原地不动）
        val s = atSettings()
        val again = NativeNavBackPolicy.onPrimaryOpen(s, settings, originPage = settings)
        assertEquals(s, again)
        assertNull(again.backPage)
        assertTrue(NativeNavBackPolicy.onBackFromPrimary(again).isChat)
    }

    @Test
    fun `back from a self referential legacy state falls back to chat`() {
        // 旧版本 rememberSaveable 恢复出来的脏状态（backPage == screen）也不能原地卡死。
        val legacy = NativeNavBackPolicy.State(screen = settings, backPage = settings)
        assertTrue(NativeNavBackPolicy.onBackFromPrimary(legacy).isChat)
    }

    @Test
    fun `H3 chat direct sub path still returns to chat`() {
        val back = NativeNavBackPolicy.forOpenSub(originPage = null, settingsPageId = settings)
        assertNull(back.page)
        assertNull(back.sub)
        assertEquals(settings, NativeNavBackPolicy.forOpenSub(originPage = settings, settingsPageId = settings).page)
        // 其它一级页进来仍回设置首页（0.3.4 语义不变）
        assertEquals("settings", NativeNavBackPolicy.forOpenSub(originPage = "tools", settingsPageId = settings).page)
    }
}

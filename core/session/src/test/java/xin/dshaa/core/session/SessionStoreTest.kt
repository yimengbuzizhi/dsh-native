package xin.dshaa.core.session

import xin.dshaa.core.model.MessageRole
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * M84-C2：会话存储可替换（`docs/115 §C` 最后一块）。
 *
 * 两个实现跑同一套流程：
 * - `file` = 原来的 `SessionRepository`（目录布局不变，`FileSessionRepository` 只做转发）；
 * - `memory` = 事件只进内存（`MemorySession.persist` 空实现），root 目录一个字节都不建。
 * 注册表同 id 覆盖 = 替换，未知 id 回落 file（App 启动不能因为调试值残留而挂）。
 */
class SessionStoreTest {

    private fun tempRoot(prefix: String): File =
        File(Files.createTempDirectory(prefix).toFile(), "sessions") // 故意不 mkdir：文件实现自己建

    @Test
    fun fileStoreIsTheDefaultAndKeepsTheDirectoryLayout() {
        val root = tempRoot("dsh-store-file")
        val store = SessionStores.create(root)
        assertEquals(SessionStores.FILE, store.id)

        val created = store.create(cwd = "/tmp/ws", model = "deepseek-flash")
        created.appendUserMessage("hello")
        assertEquals(1, store.list().size)
        val summary = store.list().single()
        assertEquals(created.header.id, summary.id)
        assertEquals("hello", summary.title)

        val loaded = assertNotNull(store.load(created.header.id))
        assertEquals(
            "hello",
            SessionCodec.messagesOf(loaded.events).single { it.role == MessageRole.USER }.text,
        )
        assertNotNull(store.findFile(created.header.id), "文件实现要给备份/导入一个真实文件")

        assertTrue(store.rename(created.header.id, "改过的名字"))
        assertEquals("改过的名字", store.list().single().title)
        assertTrue(root.isDirectory, "文件实现按原布局落盘")
    }

    @Test
    fun memoryStoreRunsCreateListOpenRenameTrashRestoreForkWithoutWritingDisk() {
        val root = tempRoot("dsh-store-memory")
        val store = SessionStores.create(root, SessionStores.MEMORY)
        assertEquals(SessionStores.MEMORY, store.id)

        // create + 一轮事件（与引擎写的是同一套方法）
        val created = store.create(cwd = "/tmp/ws", model = "deepseek-flash")
        created.appendRequestHeader(provider = "deepseek", model = "deepseek-flash")
        created.startTurn()
        created.startStep()
        created.appendUserMessageTexts(listOf("内存会话"))
        created.appendAssistantMessage("在内存里回复")
        created.endStep()
        created.endTurn()

        // list
        val summary = store.list().single()
        assertEquals(created.header.id, summary.id)
        assertEquals("内存会话", summary.title)
        assertEquals(2, summary.messageCount)
        assertEquals("completed", summary.lastStopReason)
        assertTrue(summary.path.startsWith("memory://"))

        // open：同一对象，事件在
        val loaded = assertNotNull(store.load(created.header.id))
        assertEquals(created.events.size, loaded.events.size)
        assertTrue(loaded.events.any { it.type == "assistant/message" })
        assertNull(store.findFile(created.header.id), "内存实现没有文件")

        // rename
        assertTrue(store.rename(created.header.id, "内存改名"))
        assertEquals("内存改名", store.list().single().title)

        // fork：前缀复制成新会话
        val lastSeq = created.events.last().seq!!
        val child = assertNotNull(store.fork(created.header.id, lastSeq, title = "分支"))
        assertTrue(child.header.id != created.header.id)
        assertEquals(2, store.list().size)
        assertEquals("分支", store.list().first { it.id == child.header.id }.title)

        // trash → 列表少了、回收站有、直接打开拿不到
        assertTrue(store.trash(created.header.id))
        assertEquals(1, store.list().size)
        assertEquals(1, store.listTrash().size)
        assertNull(store.load(created.header.id))

        // restore → 回到列表
        val entry = store.listTrash().single()
        assertTrue(store.restoreTrashed(entry.dirName))
        assertEquals(2, store.list().size)
        assertNotNull(store.load(created.header.id))

        // purge（彻底删除）
        assertTrue(store.purge(created.header.id))
        assertEquals(1, store.list().size)

        // 清空回收站
        assertTrue(store.trash(child.header.id))
        assertEquals(1, store.listTrash().size)
        assertEquals(1, store.purgeAllTrash())
        assertTrue(store.listTrash().isEmpty())

        assertFalse(root.exists(), "内存实现不应在 root 下建任何文件/目录")
    }

    @Test
    fun registrySupportsSameIdReplacementAndFallsBackToFileForUnknownIds() {
        val root = tempRoot("dsh-store-registry")
        val delegate = SessionStores.create(root)
        val custom = object : SessionStore by delegate {
            override val id: String = "custom.memory"
        }
        SessionStores.register(custom.id) { custom }
        try {
            assertEquals("custom.memory", SessionStores.create(root, custom.id).id)
            assertTrue(SessionStores.registeredIds().containsAll(listOf("file", "memory", "custom.memory")))
        } finally {
            SessionStores.unregister(custom.id)
        }
        // 未知 id（插件卸载/调试值残留）回落 file，App 必须还能启动
        assertEquals(SessionStores.FILE, SessionStores.create(root, "gone").id)
    }
}

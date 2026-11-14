package xin.dshaa.core.plugins

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * M84-N：投影 checkpoint 落盘存储。
 *
 * 覆盖：读写往返（state/view/stateVersion/lastSeq）、按会话过滤、停用/删除清行、
 * 坏文件不炸、条目上限按 updatedAt 丢最旧。
 */
class ProjectionCheckpointsTest {

    private fun tempFile(): File =
        File.createTempFile("projection-checkpoints", ".json").also { it.delete() }

    private fun entry(
        plugin: String = "p1",
        session: String = "s1",
        key: String = "turns",
        version: Int = 2,
        seq: Long = 5,
        state: String = """{"turns":3}""",
        view: String = """{"turns":3}""",
        updatedAt: Long = 100,
    ) = ProjectionCheckpoint(plugin, session, key, version, seq, state, view, updatedAt)

    @Test
    fun `round trips through the file`() {
        val file = tempFile()
        val store = ProjectionCheckpoints(file)
        store.put(entry())
        store.put(entry(plugin = "p2", key = "other", state = """{"a":[1,2]}""", view = ""))
        store.save()
        assertTrue(file.isFile, "应写出文件")

        val reloaded = ProjectionCheckpoints(file)
        assertTrue(reloaded.loadedFromDisk)
        assertEquals(2, reloaded.size())
        val loaded = reloaded.get("p1", "s1", "turns")
            ?: error("p1/s1/turns 没读回来")
        assertEquals(2, loaded.stateVersion)
        assertEquals(5L, loaded.lastSeq)
        assertEquals("""{"turns":3}""", loaded.stateJson)
        assertEquals("""{"turns":3}""", loaded.viewJson)
        assertEquals("", reloaded.get("p2", "s1", "other")?.viewJson)
    }

    @Test
    fun `filters by session and removes by plugin`() {
        val file = tempFile()
        val store = ProjectionCheckpoints(file)
        store.put(entry(session = "s1"))
        store.put(entry(session = "s2", key = "turns"))
        store.put(entry(plugin = "p2", session = "s1"))
        assertEquals(2, store.forSession("s1").size)
        assertEquals(1, store.forSession("s2").size)
        assertEquals(2, store.removePlugin("p1"))
        assertNull(store.get("p1", "s1", "turns"))
        assertEquals(1, store.size())
        assertEquals(0, store.removePlugin("p1"), "重复删不报错")
    }

    @Test
    fun `retain keeps only enabled plugins`() {
        val file = tempFile()
        val store = ProjectionCheckpoints(file)
        store.put(entry(plugin = "p1"))
        store.put(entry(plugin = "p2"))
        store.put(entry(plugin = "p3"))
        assertEquals(1, store.retainPlugins(setOf("p1", "p3")))
        assertEquals(2, store.size())
        assertNull(store.get("p2", "s1", "turns"))
    }

    @Test
    fun `trims oldest entries beyond the cap`() {
        val file = tempFile()
        val store = ProjectionCheckpoints(file, maxEntries = 2)
        store.put(entry(key = "old", updatedAt = 1))
        store.put(entry(key = "mid", updatedAt = 2))
        store.put(entry(key = "new", updatedAt = 3))
        store.save()
        assertEquals(2, store.size())
        assertNull(store.get("p1", "s1", "old"), "最旧的应被丢掉")
        assertEquals(3, store.get("p1", "s1", "new")?.updatedAt)
    }

    @Test
    fun `policy rejects version mismatch missing key and negative seq`() {
        val declared = mapOf("turns" to 2, "other" to 1)
        assertTrue(ProjectionCheckpointPolicy.usable(entry(version = 2), "p1", declared))
        assertTrue(!ProjectionCheckpointPolicy.usable(entry(version = 2), "p2", declared), "插件 id 不同")
        assertTrue(!ProjectionCheckpointPolicy.usable(entry(version = 1), "p1", declared), "stateVersion 不一致作废")
        assertTrue(!ProjectionCheckpointPolicy.usable(entry(key = "gone"), "p1", declared), "key 不再声明")
        assertTrue(!ProjectionCheckpointPolicy.usable(entry(seq = -1), "p1", declared), "没折过事件")
        assertEquals(
            listOf("turns"),
            ProjectionCheckpointPolicy.usableKeys(
                listOf(entry(key = "turns", version = 2), entry(key = "turns", version = 9), entry(key = "gone")),
                "p1",
                declared,
            ),
        )
    }

    @Test
    fun `broken file is treated as empty`() {
        val file = tempFile()
        file.writeText("{ 这不是 JSON", Charsets.UTF_8)
        val store = ProjectionCheckpoints(file)
        assertEquals(0, store.size())
        assertTrue(!store.loadedFromDisk)
        // 坏文件之后还能正常写入并重新读回
        store.put(entry())
        store.save()
        assertEquals(1, ProjectionCheckpoints(file).size())
    }
}

package xin.dshaa.core.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * 工作区（= 会话 cwd）级的回收站：设置页删工作区时把 `<sessions>/<slug>/` 整个移到 `.trash/`，
 * 不真删会话（用户对旧数据的要求），并且 `list()` 不能再看到它们。
 */
class WorkspaceTrashTest {

    private fun tempRepo(): SessionRepository {
        val root = Files.createTempDirectory("dsh-ws-trash").toFile()
        return SessionRepository(File(root, "sessions"))
    }

    @Test
    fun trashWorkspaceHidesOnlyThatWorkspacesSessions() {
        val repo = tempRepo()
        val default = repo.create(cwd = "/tmp/default-ws", model = "deepseek-flash")
        val other = repo.create(cwd = "/tmp/other-ws", model = "deepseek-flash")
        default.appendUserMessage("in default")
        other.appendUserMessage("in other")
        assertEquals(2, repo.list().size)
        assertTrue(repo.findFile(default.header.id) != null)
        assertTrue(repo.findFile(other.header.id) != null)

        assertTrue(repo.trashWorkspace("/tmp/default-ws"))

        val left = repo.list()
        assertEquals(1, left.size)
        assertEquals(other.header.id, left.single().id)
        assertEquals("/tmp/other-ws", left.single().cwd)
        assertNull(repo.findFile(default.header.id))
        // 目录移到 root/.trash/<slug>-<ts>，文件还在（可恢复）
        val trash = File(repo.root, ".trash").listFiles().orEmpty()
        assertTrue(trash.isNotEmpty())
        assertTrue(trash.any { File(it, default.header.id).isDirectory })
    }

    @Test
    fun trashWorkspaceWithoutSessionsIsNoOpSuccess() {
        val repo = tempRepo()
        assertTrue(repo.trashWorkspace("/tmp/never-used"))
        assertEquals(0, repo.list().size)
        assertFalse(File(repo.root, ".trash").isDirectory)
    }
}

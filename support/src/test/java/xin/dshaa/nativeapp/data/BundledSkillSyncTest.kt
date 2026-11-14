package xin.dshaa.nativeapp.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 随包技能升级语义：新版 APK 要能更新旧副本，但不能覆盖用户改过的 SKILL.md。
 */
class BundledSkillSyncTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun dir(): File = File(temp.root, "skills/dsh-plugin-adapter")

    @Test
    fun createsMissingCopyAndRecordsTheBundledHash() {
        val dir = dir()
        val bundled = "# skill v1".toByteArray()
        assertEquals(BundledSkillSync.Result.CREATED, BundledSkillSync.sync(dir, bundled))
        assertEquals("# skill v1", File(dir, "SKILL.md").readText())
        assertEquals(BundledSkillSync.sha256(bundled), File(dir, BundledSkillSync.MARKER).readText())
    }

    @Test
    fun sameContentIsIdempotent() {
        val dir = dir()
        val bundled = "# skill v1".toByteArray()
        BundledSkillSync.sync(dir, bundled)
        val before = File(dir, "SKILL.md").readBytes()
        assertEquals(BundledSkillSync.Result.ALREADY_CURRENT, BundledSkillSync.sync(dir, bundled))
        assertTrue(before.contentEquals(File(dir, "SKILL.md").readBytes()))
        assertFalse(File(dir, BundledSkillSync.BACKUP).exists())
    }

    @Test
    fun untouchedCopyIsUpdatedInPlaceWithoutBackup() {
        val dir = dir()
        BundledSkillSync.sync(dir, "# skill v1".toByteArray())
        val updated = "# skill v2: queue/shell/loading".toByteArray()
        assertEquals(BundledSkillSync.Result.UPDATED, BundledSkillSync.sync(dir, updated))
        assertEquals("# skill v2: queue/shell/loading", File(dir, "SKILL.md").readText())
        assertEquals(BundledSkillSync.sha256(updated), File(dir, BundledSkillSync.MARKER).readText())
        assertFalse("自动升级不该生成备份", File(dir, BundledSkillSync.BACKUP).exists())
    }

    @Test
    fun userEditedCopyIsNeverOverwritten() {
        val dir = dir()
        BundledSkillSync.sync(dir, "# skill v1".toByteArray())
        File(dir, "SKILL.md").writeText("# 我自己的说明")
        assertEquals(
            BundledSkillSync.Result.KEPT_USER_EDIT,
            BundledSkillSync.sync(dir, "# skill v2".toByteArray()),
        )
        assertEquals("# 我自己的说明", File(dir, "SKILL.md").readText())
    }

    @Test
    fun legacyInstallWithoutMarkerIsBackedUpBeforeMigration() {
        val dir = dir()
        dir.mkdirs()
        File(dir, "SKILL.md").writeText("# 老版本随包内容")
        assertEquals(
            BundledSkillSync.Result.MIGRATED_WITH_BACKUP,
            BundledSkillSync.sync(dir, "# 新版本随包内容".toByteArray()),
        )
        assertEquals("# 新版本随包内容", File(dir, "SKILL.md").readText())
        assertEquals("# 老版本随包内容", File(dir, BundledSkillSync.BACKUP).readText())
        assertTrue(File(dir, BundledSkillSync.MARKER).isFile)
    }

    @Test
    fun sha256MatchesKnownVector() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            BundledSkillSync.sha256("abc".toByteArray()),
        )
    }
}

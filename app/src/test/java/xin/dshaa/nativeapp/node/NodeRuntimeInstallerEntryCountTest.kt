package xin.dshaa.nativeapp.node

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipFile

/**
 * docs/190-C：Node 运行时解压进度必须按**真实 zip 条目数**算。
 *
 * 旧常量 2184 比实际 2526 少 342 条，百分比会提前冲到 99% 再停很久（用户看到「卡在 99%」）。
 * 这里直接读打包进 APK 的 `assets/node/runtime.zip` 中央目录对账，改 zip 忘改常量会失败。
 */
class NodeRuntimeInstallerEntryCountTest {

    @Test
    fun totalEntriesMatchesPackagedRuntimeZip() {
        val zip = File("src/main/assets/node/runtime.zip")
        assertTrue("找不到 ${zip.absolutePath}（工作目录应为 app 模块）", zip.isFile)
        ZipFile(zip).use { file ->
            assertEquals(
                "NodeRuntimeInstaller.TOTAL_ENTRIES 与 runtime.zip 条目数不一致",
                file.size(),
                NodeRuntimeInstaller.TOTAL_ENTRIES,
            )
            assertNotEquals(2184, NodeRuntimeInstaller.TOTAL_ENTRIES)
        }
    }
}

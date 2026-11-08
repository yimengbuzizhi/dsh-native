package xin.dshaa.core.attachment

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 内容寻址附件存储：同字节只落一份、id 幂等、非法 id 不越界。 */
class AttachmentStoreTest {

    private val root = Files.createTempDirectory("attachment-store").toFile()
    private val store = AttachmentStore(root)

    @AfterTest
    fun cleanup() {
        root.deleteRecursively()
    }

    @Test
    fun contentAddressedAndIdempotent() {
        val bytes = ByteArray(32) { it.toByte() }
        val hex = store.saveNormalized(bytes)
        assertEquals(64, hex.length)
        assertTrue(File(File(File(root, "objects"), hex.substring(0, 2)), hex).isFile)
        assertEquals(hex, store.saveNormalized(bytes))
        assertEquals(bytes.toList(), store.readImage("sha256:$hex")!!.toList())
    }

    @Test
    fun differentBytesDifferentIds() {
        val a = store.saveNormalized(byteArrayOf(1, 2, 3))
        val b = store.saveNormalized(byteArrayOf(1, 2, 4))
        assertTrue(a != b)
    }

    @Test
    fun invalidAttachmentIdsRejected() {
        assertNull(AttachmentStore.hexOf("sha256:xyz"))
        assertNull(AttachmentStore.hexOf("../../etc/passwd"))
        assertNull(store.imageFile("sha256:" + "f".repeat(64)))
        assertNull(store.readImage("sha256:" + "f".repeat(64)))
        assertNull(store.imageFile("/abs/path"))
    }
}

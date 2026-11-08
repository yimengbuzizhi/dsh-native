package xin.dshaa.core.engine

import xin.dshaa.core.model.BlockType
import xin.dshaa.core.model.ChatMessage
import xin.dshaa.core.model.ImageRef
import xin.dshaa.core.model.MessageRole
import xin.dshaa.core.model.markImageOffloaded
import xin.dshaa.core.model.toContentBlock
import xin.dshaa.core.protocol.ApiMessage
import xin.dshaa.core.protocol.ApiRequest
import xin.dshaa.core.protocol.ContentBlock as ApiBlock
import xin.dshaa.core.protocol.ImageSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * M47 图片卸载的纯函数锁定：
 * - 预算算法逐字抄上游 `dsh-llm` 的 `requiredImageOffload()`（张数/字节双预算 + 量子取整）；
 * - 选择顺序抄上游 `offloadOldestImages()`（最老在前、assistant 跳过、已卸的跳过但占下标）；
 * - 占位文本抄上游 `offloadedImageText()`。
 */
class ImageOffloadTest {

    private fun image(length: Int): ApiBlock =
        ApiBlock.Image(ImageSource(mediaType = "image/png", data = "A".repeat(length)))

    private fun request(blocks: List<ApiBlock>): ApiRequest = ApiRequest(
        model = "deepseek-flash",
        messages = listOf(ApiMessage("user", blocks)),
        maxTokens = 1024,
    )

    private fun budget(
        maxImages: Int = ImageOffload.DEFAULT_MAX_IMAGES,
        maxBytes: Int = ImageOffload.DEFAULT_MAX_BYTES,
        byteQuantum: Int = ImageOffload.DEFAULT_BYTE_QUANTUM,
        countQuantum: Int = ImageOffload.DEFAULT_COUNT_QUANTUM,
    ) = ImageOffload.Budget(maxImages, maxBytes, byteQuantum, countQuantum)

    // ---------------- 预算 ----------------

    @Test
    fun requiredCountIsZeroWithoutImagesOrWithinBudget() {
        assertEquals(0, ImageOffload.requiredCount(request(emptyList())))
        // 恰好等于字节预算不算超（上游 > max 才算）
        assertEquals(0, ImageOffload.requiredCount(request(listOf(image(100))), budget(maxBytes = 100)))
        assertEquals(
            0,
            ImageOffload.requiredCount(request(listOf(image(100), image(100))), budget(maxImages = 2)),
        )
    }

    @Test
    fun countBudgetRoundsUpToCountQuantum() {
        // 3 张、上限 2 张：超 1 张，按 20 张量子取整 → 3 张全卸
        val three = request(listOf(image(10), image(10), image(10)))
        assertEquals(3, ImageOffload.requiredCount(three, budget(maxImages = 2)))
        // 量子 1 时只卸超出的 1 张
        assertEquals(1, ImageOffload.requiredCount(three, budget(maxImages = 2, countQuantum = 1)))
        // 21 张、上限 20 张：超 1 张 → 按 20 量子 → 卸 20 张（留 1 张）
        val many = request(List(21) { image(2) })
        assertEquals(
            20,
            ImageOffload.requiredCount(many, budget(maxImages = 20, maxBytes = Int.MAX_VALUE)),
        )
    }

    @Test
    fun byteBudgetRoundsUpToByteQuantumAndRemovesOldestFirst() {
        // 两张 100 字节、预算 150：超 50，量子 1 → 只需卸最老的一张
        assertEquals(
            1,
            ImageOffload.requiredCount(request(listOf(image(100), image(100))), budget(maxBytes = 150, byteQuantum = 1)),
        )
        // 量子 64：超 50 → 需卸 >64 字节 → 第一张 100 已超过 → 1 张
        assertEquals(
            1,
            ImageOffload.requiredCount(request(listOf(image(100), image(100))), budget(maxBytes = 150, byteQuantum = 64)),
        )
        // 三张 100、预算 150：超 150 → 量子 64 取整 192 → 需卸 >192 → 两张 200 够 → 2 张
        assertEquals(
            2,
            ImageOffload.requiredCount(
                request(listOf(image(100), image(100), image(100))),
                budget(maxBytes = 150, byteQuantum = 64),
            ),
        )
    }

    @Test
    fun nestedToolResultImagesCount() {
        val request = ApiRequest(
            model = "deepseek-flash",
            messages = listOf(
                ApiMessage(
                    "user",
                    listOf(ApiBlock.Text("ctx"), ApiBlock.ToolResult("call-1", listOf(image(100), image(100)))),
                ),
            ),
            maxTokens = 1024,
        )
        assertEquals(1, ImageOffload.requiredCount(request, budget(maxBytes = 150, byteQuantum = 1)))
    }

    @Test
    fun placeholderTextCarriesIdentityPathAndDimensions() {
        val ref = ImageRef(
            attachmentId = "sha256:" + "a".repeat(64),
            mediaType = "image/jpeg",
            bytes = 1234,
            width = 720,
            height = 1570,
            name = "shot.jpg",
        )
        val text = ImageOffload.placeholderText(ref, "/data/user/0/xin.dshaa.nativeapp/files/a.jpg")
        assertTrue(text.startsWith("[image omitted to fit request image limits; \"shot.jpg\" (sha256:aaaa"))
        assertTrue(text.contains("Normalized copy (read-only; may be resized or re-encoded): " +
            "\"/data/user/0/xin.dshaa.nativeapp/files/a.jpg\""))
        assertTrue(text.contains("(720x1570px, image/jpeg)."))
        assertTrue(text.endsWith("Copy to a writable path ending in .jpg before editing.]"))

        val noPath = ImageOffload.placeholderText(ref.copy(name = null), null)
        assertTrue(noPath.contains("No local normalized image path is available; ask the user to attach it again if needed."))
        assertTrue(noPath.contains(ref.attachmentId))
    }

    // ---------------- 选择 ----------------

    private fun message(
        seq: Long,
        role: MessageRole,
        images: List<ImageRef>,
        text: String = "m",
    ): ChatMessage = ChatMessage(
        id = "id-$seq",
        role = role,
        content = buildList {
            add(xin.dshaa.core.model.ContentBlock(BlockType.TEXT, text = text))
            images.forEach { add(it.toContentBlock()) }
        },
        seq = seq,
    )

    private fun imageRef(id: Char, offloaded: Boolean = false) = ImageRef(
        attachmentId = "sha256:" + id.toString().repeat(64),
        mediaType = "image/png",
        bytes = 4,
        width = 2,
        height = 2,
        offloaded = offloaded,
    )

    @Test
    fun selectOldestSkipsAssistantAndAlreadyOffloaded() {
        val alreadyOffloaded = imageRef('c').toContentBlock().markImageOffloaded()
        val fresh = imageRef('d').toContentBlock()
        val messages = listOf(
            message(1, MessageRole.USER, listOf(imageRef('a'))),
            message(2, MessageRole.ASSISTANT, listOf(imageRef('b'))),
            message(3, MessageRole.TOOL, emptyList()).copy(
                content = listOf(
                    xin.dshaa.core.model.ContentBlock(BlockType.TEXT, text = "m"),
                    alreadyOffloaded,
                    fresh,
                ),
            ),
        )
        val targets = ImageOffload.selectOldest(messages, 2)
        assertEquals(listOf(1L, 3L), targets.map { it.seq })
        // 第 3 条里第一张已卸（仍占 index 0），选中的是 index 1
        assertEquals(listOf(0), targets[0].imageIndexes)
        assertEquals(listOf(1), targets[1].imageIndexes)
    }

    @Test
    fun selectOldestStopsAtCountAndReturnsEmptyWhenNothingLeft() {
        val messages = listOf(
            message(1, MessageRole.USER, listOf(imageRef('a'), imageRef('b'), imageRef('c'))),
            message(2, MessageRole.USER, listOf(imageRef('d'))),
        )
        assertEquals(listOf(0, 1), ImageOffload.selectOldest(messages, 2).single().imageIndexes)
        assertEquals(emptyList(), ImageOffload.selectOldest(emptyList(), 3))
        assertEquals(
            emptyList(),
            ImageOffload.selectOldest(listOf(message(9, MessageRole.ASSISTANT, listOf(imageRef('e')))), 1),
        )
    }
}

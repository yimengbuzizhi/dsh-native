package xin.dshaa.core.session

import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import xin.dshaa.core.model.BlockType
import xin.dshaa.core.model.ImageRef
import xin.dshaa.core.model.MessageRole
import xin.dshaa.core.model.SessionEvent
import xin.dshaa.core.model.toImageRef
import java.io.File
import java.nio.file.Files

/**
 * 用户附件（聊天输入条图片，docs/42）的 v4 落库形状：
 * `user/message.content = [text…, image{attachment}…]`，附件本体不进 JSONL。
 */
class UserImageMessageTest {

    private val ref = ImageRef(
        attachmentId = "sha256:" + "c".repeat(64),
        mediaType = "image/jpeg",
        bytes = 1234,
        width = 720,
        height = 1570,
        name = "photo.jpg",
        originalWidth = 1440,
        originalHeight = 3140,
    )

    @Test
    fun userMessageCarriesTextThenImageBlock() {
        val root = Files.createTempDirectory("dsh-user-image").toFile()
        val repo = SessionRepository(File(root, "sessions"))
        val session = repo.create(cwd = "/tmp/ws", model = "deepseek-flash")

        session.appendUserMessageContent(listOf("这张图是什么", "第二段"), listOf(ref), source = null)

        val event = session.events.single { it.type == "user/message" }
        val content = event.data!!.jsonObject["content"]!!.jsonArray
        assertEquals(3, content.size)
        assertEquals("text", content[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("text", content[1].jsonObject["type"]!!.jsonPrimitive.content)
        val image = content[2].jsonObject
        assertEquals("image", image["type"]!!.jsonPrimitive.content)
        val attachment = image["attachment"]!!.jsonObject
        assertEquals(ref.attachmentId, attachment["attachmentId"]!!.jsonPrimitive.content)
        assertEquals("image/jpeg", attachment["mediaType"]!!.jsonPrimitive.content)
        assertEquals(1234, attachment["bytes"]!!.jsonPrimitive.content.toInt())
        assertEquals(720, attachment["width"]!!.jsonPrimitive.content.toInt())
        assertEquals(ref.originalWidth, attachment["originalDimensions"]!!.jsonObject["width"]!!.jsonPrimitive.content.toInt())
        assertEquals("photo.jpg", attachment["name"]!!.jsonPrimitive.content)

        // 投影回模型/UI 时能还原引用
        val message = SessionCodec.messagesOf(session.events).single()
        assertEquals(MessageRole.USER, message.role)
        assertEquals(listOf(BlockType.TEXT, BlockType.TEXT, BlockType.IMAGE), message.content.map { it.type })
        assertEquals(ref, message.content.last().toImageRef())
        // 普通用户消息的 source 仍是 {kind:"user"}
        assertEquals("user", event.data!!.jsonObject["source"]!!.jsonObject["kind"]!!.jsonPrimitive.content)
    }

    @Test
    fun imageOnlyMessageHasNoEmptyTextBlock() {
        val root = Files.createTempDirectory("dsh-user-image-only").toFile()
        val repo = SessionRepository(File(root, "sessions"))
        val session = repo.create(cwd = "/tmp/ws", model = "deepseek-flash")

        session.appendUserMessageContent(listOf(""), listOf(ref))

        val content = session.events.single { it.type == "user/message" }
            .data!!.jsonObject["content"]!!.jsonArray
        assertEquals(1, content.size)
        assertEquals("image", content[0].jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun brokenImageBlockProjectsToNull() {
        val message = SessionCodec.messagesOf(
            listOf(
                SessionEvent(
                    type = "user/message",
                    data = buildJsonObject {
                        put(
                            "content",
                            buildJsonArray {
                                add(
                                    buildJsonObject {
                                        put("type", "image")
                                        put("attachment", buildJsonObject { put("bytes", 1) })
                                    },
                                )
                            },
                        )
                    },
                ),
            ),
        )
        // 缺 attachmentId/mediaType：块保留为 IMAGE，但引用解析为 null（UI/请求跳过，不炸整轮）
        assertEquals(BlockType.IMAGE, message.single().content.single().type)
        assertNull(message.single().content.single().toImageRef())
    }
}

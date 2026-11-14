package xin.dshaa.core.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class UsageFixtureTest {
    @Test
    fun `anthropic cache categories are included once in total input`() {
        val start = assertIs<StreamParser.Event.MessageStart>(StreamParser.parse(
            """{"type":"message_start","message":{"id":"m","usage":{"input_tokens":12,"output_tokens":0,"cache_read_input_tokens":80,"cache_creation_input_tokens":8}}}""",
        ))
        assertEquals(TokenUsage(100, 0, 80, 8), start.usage)
        assertEquals(100, start.inputTokens)
        val decoded = ProtocolJson.json.decodeFromString(StreamUsage.serializer(),
            """{"input_tokens":12,"cache_read_input_tokens":80,"cache_creation_input_tokens":8}""")
        assertEquals(100, decoded.normalized().inputTokens)
    }

    @Test
    fun `absent usage and explicit zero remain distinct`() {
        val missing = assertIs<StreamParser.Event.MessageStart>(StreamParser.parse("""{"type":"message_start","message":{}}"""))
        val zero = assertIs<StreamParser.Event.MessageStart>(StreamParser.parse("""{"type":"message_start","message":{"usage":{"input_tokens":0}}}"""))
        assertNull(missing.usage.inputTokens)
        assertEquals(0, zero.usage.inputTokens)
        val stop = assertIs<StreamParser.Event.MessageDelta>(StreamParser.parse("""{"type":"message_delta","delta":{"stop_reason":"end_turn"}}"""))
        assertNull(stop.usage.outputTokens)
    }

    @Test
    fun `cumulative snapshots replace repeated counts and keep missing fields`() {
        var usage = TokenUsage(inputTokens = 100, cacheReadInputTokens = 80)
        for (count in listOf(3, 7, 7, 10)) {
            val event = assertIs<StreamParser.Event.MessageDelta>(StreamParser.parse(
                """{"type":"message_delta","usage":{"output_tokens":$count}}""",
            ))
            usage = usage.merge(event.usage)
        }
        assertEquals(TokenUsage(100, 10, 80), usage)
        assertEquals(110, usage.totalTokens)
        assertEquals(0, usage.merge(TokenUsage(outputTokens = 0)).outputTokens)
        assertNull(TokenUsage(inputTokens = 100).totalTokens)
    }
}

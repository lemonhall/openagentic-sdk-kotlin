package me.lemonhall.openagentic.sdk.providers

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import me.lemonhall.openagentic.sdk.runtime.ProviderStreamEvent

class OpenAIResponsesSseDecoderTest {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    private fun decode(chunks: List<String>): List<ProviderStreamEvent> {
        val parser = SseEventParser()
        val decoder = OpenAIResponsesSseDecoder(json = json)
        val out = mutableListOf<ProviderStreamEvent>()
        for (chunk in chunks) {
            for (ev in parser.feed(chunk)) {
                out.addAll(decoder.onSseEvent(ev))
            }
        }
        for (ev in parser.endOfInput()) {
            out.addAll(decoder.onSseEvent(ev))
        }
        out.addAll(decoder.finish())
        return out
    }

    @Test
    fun offline_provider_stream_parse_half_packet() {
        val sse =
            "data: {\"type\":\"response.output_text.delta\",\"delta\":\"he\"}\n\n" +
                "data: {\"type\":\"response.output_text.delta\",\"delta\":\"llo\"}\n\n" +
                "data: {\"type\":\"response.completed\",\"response\":{\"id\":\"resp_1\",\"output\":[{\"type\":\"message\",\"content\":[{\"type\":\"output_text\",\"text\":\"final\"}]}]}}\n\n" +
                "data: [DONE]\n\n"
        val chunks =
            listOf(
                sse.substring(0, 23),
                sse.substring(23, 77),
                sse.substring(77),
            )

        val events = decode(chunks)
        val deltas = events.filterIsInstance<ProviderStreamEvent.TextDelta>().joinToString("") { it.delta }
        assertEquals("hello", deltas)
        assertTrue(events.any { it is ProviderStreamEvent.Completed })
    }

    @Test
    fun offline_provider_stream_parse_sticky_packets() {
        val sse =
            "data: {\"type\":\"response.output_text.delta\",\"delta\":\"a\"}\n\n" +
                "data: {\"type\":\"response.output_text.delta\",\"delta\":\"b\"}\n\n" +
                "data: {\"type\":\"response.completed\",\"response\":{\"id\":\"resp_1\",\"output\":[]}}\n\n"
        val events = decode(listOf(sse))
        val deltas = events.filterIsInstance<ProviderStreamEvent.TextDelta>().joinToString("") { it.delta }
        assertEquals("ab", deltas)
        assertTrue(events.any { it is ProviderStreamEvent.Completed })
    }

    @Test
    fun offline_provider_stream_data_multiline_join() {
        val sse =
            "data: {\"type\":\"response.output_text.delta\",\n" +
                "data: \"delta\":\"x\"}\n\n" +
                "data: {\"type\":\"response.completed\",\"response\":{\"id\":\"resp_1\",\"output\":[]}}\n\n"
        val events = decode(listOf(sse))
        val delta = assertIs<ProviderStreamEvent.TextDelta>(events.first { it is ProviderStreamEvent.TextDelta })
        assertEquals("x", delta.delta)
    }

    @Test
    fun offline_provider_stream_ends_without_completed_is_failed() {
        val sse =
            "data: {\"type\":\"response.output_text.delta\",\"delta\":\"x\"}\n\n" +
                "data: [DONE]\n\n"
        val events = decode(listOf(sse))
        assertTrue(events.any { it is ProviderStreamEvent.Failed && it.message.contains("response.completed") })
    }

    @Test
    fun offline_provider_stream_error_event_is_failed_and_no_completed() {
        val sse =
            "data: {\"type\":\"error\",\"message\":\"bad\"}\n\n" +
                "data: {\"type\":\"response.completed\",\"response\":{\"id\":\"resp_1\",\"output\":[]}}\n\n"
        val events = decode(listOf(sse))
        assertTrue(events.any { it is ProviderStreamEvent.Failed })
        assertTrue(events.none { it is ProviderStreamEvent.Completed })
    }
}

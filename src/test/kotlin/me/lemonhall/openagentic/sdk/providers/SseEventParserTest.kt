package me.lemonhall.openagentic.sdk.providers

import kotlin.test.Test
import kotlin.test.assertEquals

class SseEventParserTest {
    @Test
    fun sse_parser_emits_on_blank_line() {
        val p = SseEventParser()
        val out = mutableListOf<SseEvent>()
        out.addAll(p.feed("data: a\n"))
        assertEquals(0, out.size)
        out.addAll(p.feed("\n"))
        assertEquals(listOf(SseEvent("a")), out)
    }

    @Test
    fun sse_parser_end_of_input_flushes_last_event() {
        val p = SseEventParser()
        val out = mutableListOf<SseEvent>()
        out.addAll(p.feed("data: a\n"))
        out.addAll(p.endOfInput())
        assertEquals(listOf(SseEvent("a")), out)
    }

    @Test
    fun sse_parser_ignores_comments_and_other_fields() {
        val p = SseEventParser()
        val out = mutableListOf<SseEvent>()
        out.addAll(
            p.feed(
                ": comment\n" +
                    "event: foo\n" +
                    "id: 1\n" +
                    "data: x\n" +
                    "\n",
            ),
        )
        assertEquals(listOf(SseEvent("x")), out)
    }
}


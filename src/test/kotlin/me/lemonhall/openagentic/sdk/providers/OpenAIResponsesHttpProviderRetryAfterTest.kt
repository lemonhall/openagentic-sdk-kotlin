package me.lemonhall.openagentic.sdk.providers

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class OpenAIResponsesHttpProviderRetryAfterTest {
    @Test
    fun parseRetryAfterSecondsAndMs() {
        assertEquals(2_000, parseRetryAfterMs("2", nowEpochMs = 1_700_000_000_000L))
        assertEquals(150, parseRetryAfterMs("150ms", nowEpochMs = 1_700_000_000_000L))
        assertEquals(150, parseRetryAfterMs(" 150 MS ", nowEpochMs = 1_700_000_000_000L))
        assertNull(parseRetryAfterMs("nope", nowEpochMs = 1_700_000_000_000L))
    }

    @Test
    fun parseRetryAfterHttpDate() {
        val now = 1_700_000_000_000L
        val future = now + 10_000
        val futureHeader = DateTimeFormatter.RFC_1123_DATE_TIME.format(Instant.ofEpochMilli(future).atZone(ZoneOffset.UTC))
        assertEquals(10_000, parseRetryAfterMs(futureHeader, nowEpochMs = now))

        val pastHeader = DateTimeFormatter.RFC_1123_DATE_TIME.format(Instant.ofEpochMilli(now - 5_000).atZone(ZoneOffset.UTC))
        assertNull(parseRetryAfterMs(pastHeader, nowEpochMs = now))
    }
}


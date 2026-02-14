package me.lemonhall.openagentic.sdk.providers

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive

class OpenAIResponsesParsingTest {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    @Test
    fun parse_args_valid_json_object_is_returned() {
        val obj = parseArgs("{\"a\":1}", json = json)
        assertEquals("1", obj["a"]?.toString())
    }

    @Test
    fun parse_args_invalid_json_is_wrapped_as_raw() {
        val raw = "{\"a\":"
        val obj = parseArgs(raw, json = json)
        val v = obj["_raw"]
        assertNotNull(v)
        assertEquals(JsonPrimitive(raw), v)
    }
}


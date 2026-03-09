package me.lemonhall.openagentic.sdk.providers

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive

class ResponsesInputAdaptersTest {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    @Test
    fun chatCompletionsAdapter_preservesResponsesInputTextBlocks() {
        val input =
            listOf(
                responsesTextMessage(role = "system", text = "Translate from zh to en."),
                responsesTextMessage(role = "user", text = "Segments to translate: 你好"),
            )

        val messages = responsesInputToChatCompletionsMessages(input, json)

        assertEquals(2, messages.size)
        assertEquals("system", messages[0]["role"]?.jsonPrimitive?.content)
        assertEquals("Translate from zh to en.", messages[0]["content"]?.jsonPrimitive?.content)
        assertEquals("user", messages[1]["role"]?.jsonPrimitive?.content)
        assertTrue(messages[1]["content"]?.jsonPrimitive?.content.orEmpty().contains("Segments to translate"))
    }

    @Test
    fun anthropicAdapter_preservesResponsesInputTextBlocks() {
        val input =
            listOf(
                responsesTextMessage(role = "system", text = "Translate from zh to en."),
                responsesTextMessage(role = "user", text = "Segments to translate: 你好"),
            )

        val (system, messages) = responsesInputToAnthropicMessages(input, json)

        assertEquals("Translate from zh to en.", system)
        assertEquals(1, messages.size)
        assertEquals("user", messages[0]["role"]?.jsonPrimitive?.content)
        assertEquals("Segments to translate: 你好", messages[0]["content"]?.jsonPrimitive?.content)
    }

    private fun responsesTextMessage(
        role: String,
        text: String,
    ) =
        buildJsonObject {
            put("role", JsonPrimitive(role))
            put(
                "content",
                JsonArray(
                    listOf(
                        buildJsonObject {
                            put("type", JsonPrimitive("input_text"))
                            put("text", JsonPrimitive(text))
                        },
                    ),
                ),
            )
        }
}

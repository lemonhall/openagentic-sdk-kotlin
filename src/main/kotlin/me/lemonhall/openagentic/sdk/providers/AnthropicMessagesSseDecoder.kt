package me.lemonhall.openagentic.sdk.providers

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.lemonhall.openagentic.sdk.runtime.ProviderStreamEvent

/**
 * Decodes Anthropic Messages API SSE events into [ProviderStreamEvent]s.
 *
 * Anthropic SSE event types:
 * - `message_start`        → records message id, usage
 * - `content_block_start`  → records block index/type
 * - `content_block_delta`  → text_delta emits TextDelta; input_json_delta accumulates tool args
 * - `content_block_stop`   → finalizes tool_use block if applicable
 * - `message_delta`        → records stop_reason, usage delta
 * - `message_stop`         → triggers finish
 */
internal class AnthropicMessagesSseDecoder(
    private val json: Json,
) {
    private var messageId: String? = null
    private var usage: JsonObject? = null
    private var stopReason: String? = null
    private var failed: ProviderStreamEvent.Failed? = null
    private var done: Boolean = false

    // Content blocks accumulated during streaming.
    private val contentBlocks = mutableListOf<ContentBlock>()
    private var currentBlockIndex = -1

    private sealed class ContentBlock {
        class Text(var text: StringBuilder = StringBuilder()) : ContentBlock()
        class ToolUse(
            val id: String,
            val name: String,
            val inputJson: StringBuilder = StringBuilder(),
        ) : ContentBlock()
    }

    fun onSseEvent(event: SseEvent): List<ProviderStreamEvent> {
        if (failed != null || done) return emptyList()

        val data = event.data.trim()
        if (data.isBlank()) return emptyList()

        val eventType = event.event.trim()

        val obj = try {
            json.parseToJsonElement(data).jsonObject
        } catch (_: Throwable) {
            return emptyList()
        }

        return when (eventType) {
            "message_start" -> {
                val message = obj["message"]?.jsonObject
                messageId = message?.get("id")?.jsonPrimitive?.contentOrNull
                usage = message?.get("usage") as? JsonObject
                emptyList()
            }

            "content_block_start" -> {
                val index = obj["index"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: contentBlocks.size
                val contentBlock = obj["content_block"]?.jsonObject
                val type = contentBlock?.get("type")?.jsonPrimitive?.contentOrNull.orEmpty()
                currentBlockIndex = index
                when (type) {
                    "text" -> {
                        ensureBlockAt(index, ContentBlock.Text())
                    }
                    "tool_use" -> {
                        val id = contentBlock?.get("id")?.jsonPrimitive?.contentOrNull.orEmpty()
                        val name = contentBlock?.get("name")?.jsonPrimitive?.contentOrNull.orEmpty()
                        ensureBlockAt(index, ContentBlock.ToolUse(id = id, name = name))
                    }
                }
                emptyList()
            }

            "content_block_delta" -> {
                val index = obj["index"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: currentBlockIndex
                val delta = obj["delta"]?.jsonObject ?: return emptyList()
                val deltaType = delta["type"]?.jsonPrimitive?.contentOrNull.orEmpty()
                when (deltaType) {
                    "text_delta" -> {
                        val text = delta["text"]?.jsonPrimitive?.contentOrNull.orEmpty()
                        val block = contentBlocks.getOrNull(index) as? ContentBlock.Text
                        block?.text?.append(text)
                        if (text.isNotEmpty()) listOf(ProviderStreamEvent.TextDelta(text))
                        else emptyList()
                    }
                    "input_json_delta" -> {
                        val partial = delta["partial_json"]?.jsonPrimitive?.contentOrNull.orEmpty()
                        val block = contentBlocks.getOrNull(index) as? ContentBlock.ToolUse
                        block?.inputJson?.append(partial)
                        emptyList()
                    }
                    else -> emptyList()
                }
            }

            "content_block_stop" -> {
                emptyList()
            }

            "message_delta" -> {
                val delta = obj["delta"]?.jsonObject
                stopReason = delta?.get("stop_reason")?.jsonPrimitive?.contentOrNull
                val usageDelta = obj["usage"] as? JsonObject
                if (usageDelta != null) usage = usageDelta
                emptyList()
            }

            "message_stop" -> {
                done = true
                emptyList()
            }

            "error" -> {
                val errorObj = obj["error"]?.jsonObject
                val msg = errorObj?.get("message")?.jsonPrimitive?.contentOrNull
                    ?: obj.toString()
                val ev = ProviderStreamEvent.Failed(message = msg, raw = obj)
                failed = ev
                done = true
                listOf(ev)
            }

            else -> emptyList()
        }
    }

    fun finish(): List<ProviderStreamEvent> {
        if (failed != null) return emptyList()

        val blocks = contentBlocks.map { block ->
            when (block) {
                is ContentBlock.Text -> {
                    kotlinx.serialization.json.buildJsonObject {
                        put("type", kotlinx.serialization.json.JsonPrimitive("text"))
                        put("text", kotlinx.serialization.json.JsonPrimitive(block.text.toString()))
                    }
                }
                is ContentBlock.ToolUse -> {
                    val inputObj = try {
                        json.parseToJsonElement(block.inputJson.toString()).jsonObject
                    } catch (_: Throwable) {
                        kotlinx.serialization.json.buildJsonObject { }
                    }
                    kotlinx.serialization.json.buildJsonObject {
                        put("type", kotlinx.serialization.json.JsonPrimitive("tool_use"))
                        put("id", kotlinx.serialization.json.JsonPrimitive(block.id))
                        put("name", kotlinx.serialization.json.JsonPrimitive(block.name))
                        put("input", inputObj)
                    }
                }
            }
        }

        if (blocks.isEmpty() && messageId == null) {
            return listOf(ProviderStreamEvent.Failed(message = "stream ended without message content"))
        }

        val output = anthropicContentToModelOutput(
            content = blocks,
            usage = usage,
            messageId = messageId,
            json = json,
        )
        return listOf(ProviderStreamEvent.Completed(output))
    }

    private fun ensureBlockAt(index: Int, block: ContentBlock) {
        while (contentBlocks.size <= index) contentBlocks.add(ContentBlock.Text())
        contentBlocks[index] = block
    }
}

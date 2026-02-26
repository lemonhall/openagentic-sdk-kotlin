package me.lemonhall.openagentic.sdk.providers

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.lemonhall.openagentic.sdk.runtime.ProviderStreamEvent

/**
 * Decodes OpenAI Chat Completions SSE stream events into [ProviderStreamEvent].
 *
 * Chat Completions SSE format:
 * - `data: {"id":"...","choices":[{"delta":{"role":"assistant"},...}]}`
 * - `data: {"id":"...","choices":[{"delta":{"content":"text"},...}]}`
 * - `data: {"id":"...","choices":[{"delta":{"tool_calls":[...]},...}]}`
 * - `data: {"id":"...","choices":[{"finish_reason":"stop",...}],"usage":{...}}`
 * - `data: [DONE]`
 */
internal class ChatCompletionsSseDecoder(
    private val json: Json,
) {
    private var responseId: String? = null
    private var usage: JsonObject? = null
    private val textParts = mutableListOf<String>()

    // Tool call accumulation: index → (id, name, accumulated arguments)
    private data class ToolCallAcc(var id: String = "", var name: String = "", val args: StringBuilder = StringBuilder())
    private val toolCallAccs = linkedMapOf<Int, ToolCallAcc>()

    fun onSseEvent(event: SseEvent): List<ProviderStreamEvent> {
        val data = event.data.trim()
        if (data == "[DONE]") return emptyList()
        if (data.isEmpty()) return emptyList()

        val root = try {
            json.parseToJsonElement(data).jsonObject
        } catch (_: Throwable) {
            return emptyList()
        }

        responseId = responseId ?: root["id"]?.jsonPrimitive?.contentOrNull
        val rootUsage = root["usage"] as? JsonObject
        if (rootUsage != null) usage = rootUsage

        val choices = (root["choices"] as? JsonArray)
            ?.mapNotNull { it as? JsonObject } ?: emptyList()
        val firstChoice = choices.firstOrNull() ?: return emptyList()
        val delta = firstChoice["delta"]?.jsonObject ?: return emptyList()

        val events = mutableListOf<ProviderStreamEvent>()

        // Text content delta
        val contentDelta = delta["content"]?.jsonPrimitive?.contentOrNull
        if (contentDelta != null && contentDelta.isNotEmpty()) {
            textParts.add(contentDelta)
            events.add(ProviderStreamEvent.TextDelta(delta = contentDelta))
        }

        // Tool calls delta
        val toolCallDeltas = (delta["tool_calls"] as? JsonArray)
            ?.mapNotNull { it as? JsonObject } ?: emptyList()
        for (tc in toolCallDeltas) {
            val idx = tc["index"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0
            val acc = toolCallAccs.getOrPut(idx) { ToolCallAcc() }
            tc["id"]?.jsonPrimitive?.contentOrNull?.let { if (it.isNotEmpty()) acc.id = it }
            val fn = tc["function"]?.jsonObject
            fn?.get("name")?.jsonPrimitive?.contentOrNull?.let { if (it.isNotEmpty()) acc.name = it }
            fn?.get("arguments")?.jsonPrimitive?.contentOrNull?.let { acc.args.append(it) }
        }

        return events
    }

    fun finish(): List<ProviderStreamEvent> {
        val toolCalls = toolCallAccs.values.mapNotNull { acc ->
            if (acc.name.isEmpty()) return@mapNotNull null
            val argsObj = try {
                json.parseToJsonElement(acc.args.toString()).jsonObject
            } catch (_: Throwable) {
                buildJsonObject { }
            }
            ToolCall(toolUseId = acc.id, name = acc.name, arguments = argsObj)
        }

        val output = ModelOutput(
            assistantText = textParts.joinToString("").ifEmpty { null },
            toolCalls = toolCalls,
            usage = usage,
            responseId = responseId,
            providerMetadata = null,
        )
        return listOf(ProviderStreamEvent.Completed(output = output))
    }
}

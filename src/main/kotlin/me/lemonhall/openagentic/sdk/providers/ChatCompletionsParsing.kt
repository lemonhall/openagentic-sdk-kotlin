package me.lemonhall.openagentic.sdk.providers

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Convert SDK Responses-format `input` list into OpenAI Chat Completions format.
 *
 * Returns a list of chat messages suitable for `/v1/chat/completions`.
 *
 * Input item formats (from SDK core loop):
 * - `{ "role": "system", "content": "..." }`
 * - `{ "role": "user", "content": "..." }`
 * - `{ "role": "assistant", "content": "..." }`
 * - `{ "type": "function_call", "call_id": "...", "name": "...", "arguments": "..." }`
 * - `{ "type": "function_call_output", "call_id": "...", "output": "..." }`
 */
internal fun responsesInputToChatCompletionsMessages(
    input: List<JsonObject>,
    json: Json,
): List<JsonObject> {
    val messages = mutableListOf<JsonObject>()

    // We need to merge consecutive function_call items into a single assistant message
    // with a tool_calls array, because that's what Chat Completions expects.
    var pendingToolCalls = mutableListOf<JsonObject>()

    fun flushToolCalls() {
        if (pendingToolCalls.isEmpty()) return
        messages.add(
            buildJsonObject {
                put("role", JsonPrimitive("assistant"))
                put("content", JsonPrimitive(""))
                put("tool_calls", JsonArray(pendingToolCalls))
            },
        )
        pendingToolCalls = mutableListOf()
    }

    for (item in input) {
        val role = (item["role"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
        val type = (item["type"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()

        when {
            type == "function_call" -> {
                val callId = (item["call_id"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                val name = (item["name"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                val argsRaw = (item["arguments"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                pendingToolCalls.add(
                    buildJsonObject {
                        put("id", JsonPrimitive(callId))
                        put("type", JsonPrimitive("function"))
                        put("function", buildJsonObject {
                            put("name", JsonPrimitive(name))
                            put("arguments", JsonPrimitive(argsRaw))
                        })
                    },
                )
            }

            type == "function_call_output" -> {
                flushToolCalls()
                val callId = (item["call_id"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                val output = (item["output"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                messages.add(
                    buildJsonObject {
                        put("role", JsonPrimitive("tool"))
                        put("tool_call_id", JsonPrimitive(callId))
                        put("content", JsonPrimitive(output))
                    },
                )
            }

            role == "system" || role == "user" || role == "assistant" -> {
                flushToolCalls()
                val content = (item["content"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                messages.add(
                    buildJsonObject {
                        put("role", JsonPrimitive(role))
                        put("content", JsonPrimitive(content))
                    },
                )
            }
        }
    }
    flushToolCalls()
    return messages
}

/**
 * Convert SDK Responses-format tool schemas to OpenAI Chat Completions function tool format.
 *
 * Responses format: `{ "type": "function", "name": "...", "description": "...", "parameters": {...} }`
 * Chat Completions format: `{ "type": "function", "function": { "name": "...", "description": "...", "parameters": {...} } }`
 */
internal fun responsesToolsToChatCompletionsTools(tools: List<JsonObject>): List<JsonObject> {
    return tools.mapNotNull { t ->
        val name = (t["name"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        val desc = (t["description"] as? JsonPrimitive)?.contentOrNull
        val params = t["parameters"] as? JsonObject ?: buildJsonObject {
            put("type", JsonPrimitive("object"))
            put("properties", buildJsonObject { })
        }
        buildJsonObject {
            put("type", JsonPrimitive("function"))
            put("function", buildJsonObject {
                put("name", JsonPrimitive(name))
                if (!desc.isNullOrBlank()) put("description", JsonPrimitive(desc))
                put("parameters", params)
            })
        }
    }
}

/**
 * Convert OpenAI Chat Completions response to SDK ModelOutput.
 */
internal fun chatCompletionsResponseToModelOutput(
    root: JsonObject,
    json: Json,
): ModelOutput {
    val id = root["id"]?.jsonPrimitive?.contentOrNull
    val usage = root["usage"] as? JsonObject
    val choices = (root["choices"] as? JsonArray)
        ?.mapNotNull { it as? JsonObject } ?: emptyList()
    val firstChoice = choices.firstOrNull()
    val message = firstChoice?.get("message")?.jsonObject

    val assistantText = message?.get("content")?.jsonPrimitive?.contentOrNull
    val toolCallsRaw = (message?.get("tool_calls") as? JsonArray)
        ?.mapNotNull { it as? JsonObject } ?: emptyList()

    val toolCalls = toolCallsRaw.mapNotNull { tc ->
        val tcId = tc["id"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val fn = tc["function"]?.jsonObject ?: return@mapNotNull null
        val name = fn["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val argsStr = fn["arguments"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val argsObj = try {
            json.parseToJsonElement(argsStr).jsonObject
        } catch (_: Throwable) {
            buildJsonObject { }
        }
        ToolCall(toolUseId = tcId, name = name, arguments = argsObj)
    }

    return ModelOutput(
        assistantText = assistantText?.ifEmpty { null },
        toolCalls = toolCalls,
        usage = usage,
        responseId = id,
        providerMetadata = null,
    )
}

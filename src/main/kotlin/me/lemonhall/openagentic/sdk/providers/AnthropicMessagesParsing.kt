package me.lemonhall.openagentic.sdk.providers

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Convert SDK Responses-format `input` list into Anthropic Messages API format.
 *
 * Returns a Pair of (system prompt string?, messages list).
 *
 * Input item formats (from SDK core loop):
 * - `{ "role": "system", "content": "..." }`
 * - `{ "role": "user", "content": "..." }`
 * - `{ "role": "assistant", "content": "..." }`
 * - `{ "type": "function_call", "call_id": "...", "name": "...", "arguments": "..." }`
 * - `{ "type": "function_call_output", "call_id": "...", "output": "..." }`
 */
internal fun responsesInputToAnthropicMessages(
    input: List<JsonObject>,
    json: Json,
): Pair<String?, List<JsonObject>> {
    val systemParts = mutableListOf<String>()
    // Accumulate messages; merge consecutive same-role content blocks.
    val messages = mutableListOf<JsonObject>()

    // Pending content blocks for the current role being built.
    var pendingRole = ""
    var pendingBlocks = mutableListOf<JsonObject>()

    fun flushPending() {
        if (pendingBlocks.isEmpty()) return
        messages.add(
            buildJsonObject {
                put("role", JsonPrimitive(pendingRole))
                if (pendingBlocks.size == 1 && pendingBlocks[0]["type"]?.jsonPrimitive?.contentOrNull == "text") {
                    // Single text block → use plain string content for simplicity.
                    put("content", pendingBlocks[0]["text"] ?: JsonPrimitive(""))
                } else {
                    put("content", JsonArray(pendingBlocks))
                }
            },
        )
        pendingBlocks = mutableListOf()
        pendingRole = ""
    }

    fun appendBlock(role: String, block: JsonObject) {
        if (role != pendingRole) flushPending()
        pendingRole = role
        pendingBlocks.add(block)
    }

    for (item in input) {
        val role = (item["role"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
        val type = (item["type"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()

        when {
            role == "system" -> {
                flushPending()
                val content = (item["content"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                if (content.isNotBlank()) systemParts.add(content)
            }

            type == "function_call" -> {
                val callId = (item["call_id"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                val name = (item["name"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                val argsRaw = (item["arguments"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                val argsObj = parseArgsToJsonObject(argsRaw, json)
                val block = buildJsonObject {
                    put("type", JsonPrimitive("tool_use"))
                    put("id", JsonPrimitive(callId))
                    put("name", JsonPrimitive(name))
                    put("input", argsObj)
                }
                appendBlock("assistant", block)
            }

            type == "function_call_output" -> {
                val callId = (item["call_id"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                val output = (item["output"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                val block = buildJsonObject {
                    put("type", JsonPrimitive("tool_result"))
                    put("tool_use_id", JsonPrimitive(callId))
                    put("content", JsonPrimitive(output))
                }
                appendBlock("user", block)
            }

            role == "user" || role == "assistant" -> {
                val content = (item["content"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                val block = buildJsonObject {
                    put("type", JsonPrimitive("text"))
                    put("text", JsonPrimitive(content))
                }
                appendBlock(role, block)
            }
        }
    }
    flushPending()

    // Anthropic requires messages to start with "user" role.
    // If first message is "assistant", prepend a synthetic user message.
    if (messages.isNotEmpty()) {
        val firstRole = (messages[0]["role"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        if (firstRole == "assistant") {
            messages.add(
                0,
                buildJsonObject {
                    put("role", JsonPrimitive("user"))
                    put("content", JsonPrimitive("(continue)"))
                },
            )
        }
    }

    val system = systemParts.joinToString("\n\n").trim().ifBlank { null }
    return system to messages
}

/**
 * Convert SDK Responses-format tool schemas to Anthropic tool format.
 *
 * Responses format: `{ "type": "function", "name": "...", "description": "...", "parameters": {...} }`
 * Anthropic format: `{ "name": "...", "description": "...", "input_schema": {...} }`
 */
internal fun responsesToolsToAnthropicTools(tools: List<JsonObject>): List<JsonObject> {
    return tools.mapNotNull { t ->
        val name = (t["name"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        val desc = (t["description"] as? JsonPrimitive)?.contentOrNull
        val params = t["parameters"] as? JsonObject ?: buildJsonObject {
            put("type", JsonPrimitive("object"))
            put("properties", buildJsonObject { })
        }
        buildJsonObject {
            put("name", JsonPrimitive(name))
            if (!desc.isNullOrBlank()) put("description", JsonPrimitive(desc))
            put("input_schema", params)
        }
    }
}

/**
 * Convert Anthropic response content blocks to SDK ModelOutput.
 */
internal fun anthropicContentToModelOutput(
    content: List<JsonObject>,
    usage: JsonObject?,
    messageId: String?,
    json: Json,
): ModelOutput {
    val textParts = mutableListOf<String>()
    val toolCalls = mutableListOf<ToolCall>()

    for (block in content) {
        val type = (block["type"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
        when (type) {
            "text" -> {
                val text = (block["text"] as? JsonPrimitive)?.contentOrNull
                if (!text.isNullOrEmpty()) textParts.add(text)
            }
            "tool_use" -> {
                val id = (block["id"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                val name = (block["name"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                val input = block["input"] as? JsonObject ?: buildJsonObject { }
                toolCalls.add(ToolCall(toolUseId = id, name = name, arguments = input))
            }
        }
    }

    return ModelOutput(
        assistantText = textParts.joinToString("").ifEmpty { null },
        toolCalls = toolCalls,
        usage = usage,
        responseId = messageId,
        providerMetadata = null,
    )
}

private fun parseArgsToJsonObject(raw: String, json: Json): JsonObject {
    val s = raw.trim()
    if (s.isEmpty()) return buildJsonObject { }
    return try {
        json.parseToJsonElement(s).jsonObject
    } catch (_: Throwable) {
        buildJsonObject { }
    }
}

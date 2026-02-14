package me.lemonhall.openagentic.sdk.providers

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive

internal fun parseAssistantText(outputItems: List<JsonObject>): String? {
    val parts = mutableListOf<String>()
    for (item in outputItems) {
        if (item["type"]?.jsonPrimitive?.content != "message") continue
        val content = item["content"] as? JsonArray ?: continue
        for (partEl in content) {
            val part = partEl as? JsonObject ?: continue
            if (part["type"]?.jsonPrimitive?.content != "output_text") continue
            val text = part["text"]?.jsonPrimitive?.contentOrNull
            if (!text.isNullOrEmpty()) parts.add(text)
        }
    }
    if (parts.isEmpty()) return null
    return parts.joinToString("")
}

internal fun parseToolCalls(
    outputItems: List<JsonObject>,
    json: Json,
): List<ToolCall> {
    val out = mutableListOf<ToolCall>()
    for (item in outputItems) {
        if (item["type"]?.jsonPrimitive?.content != "function_call") continue
        val callId = item["call_id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: continue
        val name = item["name"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: continue
        val argsEl = item["arguments"]
        val argsObj =
            when (argsEl) {
                is JsonObject -> argsEl
                is JsonPrimitive -> parseArgs(argsEl.content, json = json)
                null -> buildJsonObject { }
                else -> buildJsonObject { put("_raw", JsonPrimitive(argsEl.toString())) }
            }
        out.add(ToolCall(toolUseId = callId, name = name, arguments = argsObj))
    }
    return out
}

internal fun parseArgs(
    raw: String,
    json: Json,
): JsonObject {
    val s = raw.trim()
    if (s.isEmpty()) return buildJsonObject { }
    return try {
        val el = json.parseToJsonElement(s)
        el as? JsonObject ?: buildJsonObject { put("_raw", JsonPrimitive(raw)) }
    } catch (_: Throwable) {
        buildJsonObject { put("_raw", JsonPrimitive(raw)) }
    }
}

internal val JsonPrimitive.contentOrNull: String?
    get() =
        try {
            this.content
        } catch (_: Throwable) {
            null
        }


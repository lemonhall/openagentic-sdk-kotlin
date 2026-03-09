package me.lemonhall.openagentic.sdk.providers

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal fun responsesContentToPlainText(content: JsonElement?): String {
    return when (content) {
        is JsonPrimitive -> content.contentOrNull.orEmpty()
        is JsonArray -> {
            content.joinToString(separator = "") { item ->
                when (item) {
                    is JsonPrimitive -> item.contentOrNull.orEmpty()
                    is JsonObject -> (item["text"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                    else -> ""
                }
            }
        }
        else -> ""
    }
}

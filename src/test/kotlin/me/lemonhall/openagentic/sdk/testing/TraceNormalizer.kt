package me.lemonhall.openagentic.sdk.testing

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

object TraceNormalizer {
    private val noiseKeys =
        setOf(
            "ts",
            "duration_ms",
            "compacted_ts",
        )

    fun normalize(element: JsonElement): JsonElement {
        return when (element) {
            is JsonObject -> normalizeObject(element)
            is JsonArray -> JsonArray(element.map { normalize(it) })
            is JsonPrimitive -> element
            JsonNull -> JsonNull
            else -> element
        }
    }

    private fun normalizeObject(obj: JsonObject): JsonObject {
        val keys = obj.keys.filterNot { it in noiseKeys }.sorted()
        return buildJsonObject {
            for (k in keys) {
                val v = obj[k] ?: continue
                put(k, normalize(v))
            }
        }
    }
}


package me.lemonhall.openagentic.sdk.json

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

fun JsonElement.asStringOrNull(): String? {
    val p = this as? JsonPrimitive ?: return null
    if (!p.isString) return null
    return p.content
}

fun JsonElement.asIntOrNull(): Int? {
    val p = this as? JsonPrimitive ?: return null
    val raw = p.content.trim()
    return raw.toIntOrNull()
}

fun JsonElement.asBooleanOrNull(): Boolean? {
    val p = this as? JsonPrimitive ?: return null
    val raw = p.content.trim().lowercase()
    return when (raw) {
        "true", "1", "yes", "y" -> true
        "false", "0", "no", "n" -> false
        else -> null
    }
}


package me.lemonhall.openagentic.sdk.hooks

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import me.lemonhall.openagentic.sdk.providers.ModelOutput

typealias HookCallback = suspend (payload: JsonObject) -> HookDecision

data class HookDecision(
    val block: Boolean = false,
    val blockReason: String? = null,
    val overrideToolInput: JsonObject? = null,
    val overrideToolOutput: JsonElement? = null,
    val overrideModelInput: List<JsonObject>? = null,
    val overridePrompt: String? = null,
    val overrideModelOutput: ModelOutput? = null,
    val action: String? = null,
)

data class HookMatcher(
    val name: String,
    val toolNamePattern: String = "*",
    val hook: HookCallback? = null,
    val hooks: List<HookCallback> = emptyList(),
    val timeoutMs: Long? = null,
)


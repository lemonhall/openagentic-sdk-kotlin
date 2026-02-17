package me.lemonhall.openagentic.sdk.hooks

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

object HookEngines {
    fun systemPrompt(
        marker: String,
        systemPrompt: String,
        actionLabel: String? = null,
    ): HookEngine {
        val trimmed = systemPrompt.trim()
        require(trimmed.contains(marker)) { "systemPrompt must include marker to prevent duplicate injection" }
        return HookEngine(
            enableMessageRewriteHooks = true,
            beforeModelCall =
                listOf(
                    HookMatcher(
                        name = "sdk.system_prompt",
                        hook = { payload ->
                            val arr = payload["input"] as? JsonArray
                            val current = arr?.mapNotNull { it as? JsonObject }.orEmpty()
                            val alreadyInjected =
                                current.firstOrNull()?.let { first ->
                                    val role = (first["role"] as? JsonPrimitive)?.content?.trim().orEmpty()
                                    val content = (first["content"] as? JsonPrimitive)?.content?.trim().orEmpty()
                                    role == "system" && content.contains(marker)
                                } == true

                            if (alreadyInjected) {
                                HookDecision(action = buildAction(base = "system prompt already present", label = actionLabel))
                            } else {
                                val sys =
                                    buildJsonObject {
                                        put("role", JsonPrimitive("system"))
                                        put("content", JsonPrimitive(trimmed))
                                    }
                                HookDecision(
                                    overrideModelInput = listOf(sys) + current,
                                    action = buildAction(base = "prepended system prompt", label = actionLabel),
                                )
                            }
                        },
                    ),
                ),
        )
    }

    private fun buildAction(
        base: String,
        label: String?,
    ): String {
        val b = base.trim().ifEmpty { "hook" }
        val l = label?.trim().orEmpty()
        return if (l.isEmpty()) b else "$b ($l)"
    }
}


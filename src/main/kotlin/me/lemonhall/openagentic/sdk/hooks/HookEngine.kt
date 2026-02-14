package me.lemonhall.openagentic.sdk.hooks

import kotlin.system.measureNanoTime
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import me.lemonhall.openagentic.sdk.events.HookEvent
import me.lemonhall.openagentic.sdk.providers.ModelOutput

class HookEngine(
    val preToolUse: List<HookMatcher> = emptyList(),
    val postToolUse: List<HookMatcher> = emptyList(),
    val userPromptSubmit: List<HookMatcher> = emptyList(),
    val sessionCompacting: List<HookMatcher> = emptyList(),
    val beforeModelCall: List<HookMatcher> = emptyList(),
    val afterModelCall: List<HookMatcher> = emptyList(),
    val enableMessageRewriteHooks: Boolean = false,
) {
    suspend fun runUserPromptSubmit(
        prompt: String,
        context: JsonObject,
    ): HookRunString {
        var current = prompt
        val hookEvents = mutableListOf<HookEvent>()
        for (m in userPromptSubmit) {
            val callbacks = callbacks(m)
            val matched = matchName(m.toolNamePattern, "UserPromptSubmit")
            val result =
                if (matched && callbacks.isNotEmpty()) {
                    runCallbacks(m, callbacks) { cb ->
                        val payload =
                            buildJsonObject {
                                put("prompt", JsonPrimitive(current))
                                put("context", context)
                                put("hook_point", JsonPrimitive("UserPromptSubmit"))
                            }
                        cb(payload)
                    }
                } else {
                    CallbackRun(nanos = 0, decision = null)
                }
            hookEvents.add(result.event("UserPromptSubmit", m.name, matched = matched))
            val decision = result.decision
            if (decision != null) {
                if (decision.block) return HookRunString(current, hookEvents, decision)
                if (enableMessageRewriteHooks) {
                    if (!decision.overridePrompt.isNullOrBlank()) current = decision.overridePrompt
                }
            }
        }
        return HookRunString(current, hookEvents, null)
    }

    suspend fun runSessionCompacting(
        output: JsonObject,
        context: JsonObject,
    ): HookRunJsonObject {
        var current = output
        val hookEvents = mutableListOf<HookEvent>()
        for (m in sessionCompacting) {
            val callbacks = callbacks(m)
            val matched = matchName(m.toolNamePattern, "SessionCompacting")
            val result =
                if (matched && callbacks.isNotEmpty()) {
                    runCallbacks(m, callbacks) { cb ->
                        val payload =
                            buildJsonObject {
                                put("output", current)
                                put("context", context)
                                put("hook_point", JsonPrimitive("SessionCompacting"))
                            }
                        cb(payload)
                    }
                } else {
                    CallbackRun(nanos = 0, decision = null)
                }
            hookEvents.add(result.event("SessionCompacting", m.name, matched = matched))
            val decision = result.decision
            if (decision != null) {
                if (decision.block) return HookRunJsonObject(current, hookEvents, decision)
                val override = decision.overrideToolOutput
                if (override is JsonObject) current = override
            }
        }
        return HookRunJsonObject(current, hookEvents, null)
    }

    suspend fun runBeforeModelCall(
        modelInput: List<JsonObject>,
        context: JsonObject,
    ): HookRunModelInput {
        var current = modelInput
        val hookEvents = mutableListOf<HookEvent>()
        for (m in beforeModelCall) {
            val matched = matchName(m.toolNamePattern, "BeforeModelCall")
            val callbacks = callbacks(m)
            val result =
                if (matched && callbacks.isNotEmpty()) {
                    runCallbacks(m, callbacks) { cb ->
                        val payload =
                            buildJsonObject {
                                put("input", JsonArray(current))
                                put("context", context)
                                put("hook_point", JsonPrimitive("BeforeModelCall"))
                            }
                        cb(payload)
                    }
                } else {
                    CallbackRun(nanos = 0, decision = null)
                }
            hookEvents.add(result.event("BeforeModelCall", m.name, matched = matched))
            val decision = result.decision
            if (decision != null) {
                if (decision.block) return HookRunModelInput(current, hookEvents, decision)
                if (enableMessageRewriteHooks) {
                    if (decision.overrideModelInput != null) current = decision.overrideModelInput
                    if (!decision.overridePrompt.isNullOrBlank()) current = rewriteLastUserPrompt(current, decision.overridePrompt)
                }
            }
        }
        return HookRunModelInput(current, hookEvents, null)
    }

    suspend fun runAfterModelCall(
        modelOutput: ModelOutput,
        context: JsonObject,
    ): HookRunModelOutput {
        var current = modelOutput
        val hookEvents = mutableListOf<HookEvent>()
        for (m in afterModelCall) {
            val matched = matchName(m.toolNamePattern, "AfterModelCall")
            val callbacks = callbacks(m)
            val result =
                if (matched && callbacks.isNotEmpty()) {
                    runCallbacks(m, callbacks) { cb ->
                        val payload =
                            buildJsonObject {
                                put("assistant_text", JsonPrimitive(current.assistantText ?: ""))
                                put("context", context)
                                put("hook_point", JsonPrimitive("AfterModelCall"))
                            }
                        cb(payload)
                    }
                } else {
                    CallbackRun(nanos = 0, decision = null)
                }
            hookEvents.add(result.event("AfterModelCall", m.name, matched = matched))
            val decision = result.decision
            if (decision != null) {
                if (decision.block) return HookRunModelOutput(current, hookEvents, decision)
                if (decision.overrideModelOutput != null) current = decision.overrideModelOutput
            }
        }
        return HookRunModelOutput(current, hookEvents, null)
    }

    suspend fun runPreToolUse(
        toolName: String,
        toolInput: JsonObject,
        context: JsonObject,
    ): HookRunToolInput {
        var current = toolInput
        val hookEvents = mutableListOf<HookEvent>()
        for (m in preToolUse) {
            val matched = matchName(m.toolNamePattern, toolName)
            val callbacks = callbacks(m)
            val result =
                if (matched && callbacks.isNotEmpty()) {
                    runCallbacks(m, callbacks) { cb ->
                        val payload =
                            buildJsonObject {
                                put("tool_name", JsonPrimitive(toolName))
                                put("tool_input", current)
                                put("context", context)
                                put("hook_point", JsonPrimitive("PreToolUse"))
                            }
                        cb(payload)
                    }
                } else {
                    CallbackRun(nanos = 0, decision = null)
                }
            hookEvents.add(result.event("PreToolUse", m.name, matched = matched))
            val decision = result.decision
            if (decision != null) {
                if (decision.block) return HookRunToolInput(current, hookEvents, decision)
                if (decision.overrideToolInput != null) current = decision.overrideToolInput
            }
        }
        return HookRunToolInput(current, hookEvents, null)
    }

    suspend fun runPostToolUse(
        toolName: String,
        toolOutput: JsonElement?,
        context: JsonObject,
    ): HookRunToolOutput {
        var current = toolOutput
        val hookEvents = mutableListOf<HookEvent>()
        for (m in postToolUse) {
            val matched = matchName(m.toolNamePattern, toolName)
            val callbacks = callbacks(m)
            val result =
                if (matched && callbacks.isNotEmpty()) {
                    runCallbacks(m, callbacks) { cb ->
                        val payload =
                            buildJsonObject {
                                put("tool_name", JsonPrimitive(toolName))
                                put("tool_output", current ?: JsonNull)
                                put("context", context)
                                put("hook_point", JsonPrimitive("PostToolUse"))
                            }
                        cb(payload)
                    }
                } else {
                    CallbackRun(nanos = 0, decision = null)
                }
            hookEvents.add(result.event("PostToolUse", m.name, matched = matched))
            val decision = result.decision
            if (decision != null) {
                if (decision.block) return HookRunToolOutput(current, hookEvents, decision)
                if (decision.overrideToolOutput != null) current = decision.overrideToolOutput
            }
        }
        return HookRunToolOutput(current, hookEvents, null)
    }

    private fun callbacks(m: HookMatcher): List<HookCallback> {
        val out = mutableListOf<HookCallback>()
        m.hook?.let { out.add(it) }
        out.addAll(m.hooks)
        return out
    }

    private data class CallbackRun(
        val nanos: Long,
        val decision: HookDecision?,
    ) {
        fun event(
            hookPoint: String,
            name: String,
            matched: Boolean,
        ): HookEvent {
            val ms = nanos.toDouble() / 1_000_000.0
            val action = decision?.action ?: if (decision?.block == true) "block" else null
            return HookEvent(
                hookPoint = hookPoint,
                name = name,
                matched = matched,
                durationMs = ms,
                action = action,
            )
        }
    }

    private suspend fun runCallbacks(
        matcher: HookMatcher,
        callbacks: List<HookCallback>,
        payloadFn: suspend (HookCallback) -> HookDecision,
    ): CallbackRun {
        var decision: HookDecision? = null
        val nanos =
            measureNanoTime {
                for (cb in callbacks) {
                    decision =
                        if (matcher.timeoutMs != null) {
                            withTimeout(matcher.timeoutMs) { payloadFn(cb) }
                        } else {
                            payloadFn(cb)
                        }
                    if (decision?.block == true) return@measureNanoTime
                }
            }
        return CallbackRun(nanos = nanos, decision = decision)
    }

    private fun matchName(
        pattern: String,
        name: String,
    ): Boolean {
        return pattern.split("|").any { seg ->
            val s = seg.trim()
            if (s.isEmpty()) false else wildcardMatch(s, name)
        }
    }

    private fun wildcardMatch(
        pattern: String,
        text: String,
    ): Boolean {
        val rx = Regex("^" + pattern
            .replace("\\", "\\\\")
            .replace(".", "\\.")
            .replace("?", ".")
            .replace("*", ".*") + "$")
        return rx.matches(text)
    }

    private fun rewriteLastUserPrompt(
        input: List<JsonObject>,
        prompt: String,
    ): List<JsonObject> {
        val idx = input.indexOfLast { it["role"] == JsonPrimitive("user") && it["content"] is JsonPrimitive }
        if (idx < 0) return input
        val newItem =
            buildJsonObject {
                for ((k, v) in input[idx]) {
                    if (k == "content") put("content", JsonPrimitive(prompt)) else put(k, v)
                }
            }
        return input.mapIndexed { i, it -> if (i == idx) newItem else it }
    }
}

data class HookRunString(
    val value: String,
    val events: List<HookEvent>,
    val decision: HookDecision?,
)

data class HookRunToolInput(
    val input: JsonObject,
    val events: List<HookEvent>,
    val decision: HookDecision?,
)

data class HookRunToolOutput(
    val output: JsonElement?,
    val events: List<HookEvent>,
    val decision: HookDecision?,
)

data class HookRunModelInput(
    val input: List<JsonObject>,
    val events: List<HookEvent>,
    val decision: HookDecision?,
)

data class HookRunModelOutput(
    val output: ModelOutput,
    val events: List<HookEvent>,
    val decision: HookDecision?,
)

data class HookRunJsonObject(
    val output: JsonObject,
    val events: List<HookEvent>,
    val decision: HookDecision?,
)

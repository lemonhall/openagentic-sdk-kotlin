package me.lemonhall.openagentic.sdk.subagents

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import me.lemonhall.openagentic.sdk.hooks.HookEngines
import me.lemonhall.openagentic.sdk.runtime.OpenAgenticOptions
import me.lemonhall.openagentic.sdk.runtime.OpenAgenticSdk
import me.lemonhall.openagentic.sdk.runtime.TaskContext
import me.lemonhall.openagentic.sdk.runtime.TaskRunner

object TaskRunners {
    class UnhandledAgentException(
        val agent: String,
    ) : IllegalStateException("Unhandled agent: $agent")

    fun compose(vararg runners: TaskRunner): TaskRunner {
        return TaskRunner { agent, prompt, context ->
            var lastUnhandled: UnhandledAgentException? = null
            for (r in runners) {
                try {
                    return@TaskRunner r.run(agent = agent, prompt = prompt, context = context)
                } catch (e: UnhandledAgentException) {
                    lastUnhandled = e
                }
            }
            throw lastUnhandled ?: IllegalStateException("No TaskRunner configured")
        }
    }

    fun builtInExplore(
        baseOptions: OpenAgenticOptions,
        maxSteps: Int = 40,
    ): TaskRunner {
        val marker = "OPENAGENTIC_SDK_EXPLORE_PROMPT_V1"
        val systemPrompt = BuiltInSubAgents.exploreSystemPrompt
        val hookEngine = HookEngines.systemPrompt(marker = marker, systemPrompt = systemPrompt, actionLabel = "explore")
        val allowedTools = setOf("Read", "List", "Glob", "Grep")

        return TaskRunner { agent, prompt, context ->
            if (agent != BuiltInSubAgents.EXPLORE_AGENT) throw UnhandledAgentException(agent)

            val subOptions =
                baseOptions.copy(
                    allowedTools = allowedTools,
                    hookEngine = hookEngine,
                    taskRunner = null,
                    taskAgents = emptyList(),
                    resumeSessionId = null,
                    includePartialMessages = false,
                    maxSteps = maxSteps,
                )

            val result = OpenAgenticSdk.run(prompt = prompt.trim(), options = subOptions)
            buildJsonObject {
                put("ok", JsonPrimitive(true))
                put("agent", JsonPrimitive(agent))
                put("parent_session_id", JsonPrimitive(context.sessionId))
                put("parent_tool_use_id", JsonPrimitive(context.toolUseId))
                put("sub_session_id", JsonPrimitive(result.sessionId))
                put("answer", JsonPrimitive(result.finalText.trim()))
            }
        }
    }
}

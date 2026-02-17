package me.lemonhall.openagentic.sdk.subagents

import kotlinx.coroutines.flow.collect
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import me.lemonhall.openagentic.sdk.events.Result
import me.lemonhall.openagentic.sdk.events.RuntimeError
import me.lemonhall.openagentic.sdk.events.ToolResult
import me.lemonhall.openagentic.sdk.events.ToolUse
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

            context.emitProgress?.invoke("子任务(explore)：启动")
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

            var lastResult: Result? = null
            var lastRuntimeError: RuntimeError? = null
            OpenAgenticSdk.query(prompt = prompt.trim(), options = subOptions).collect { ev ->
                when (ev) {
                    is ToolUse -> context.emitProgress?.invoke("子任务(explore)：${humanizeExploreToolUse(ev.name, ev.input)}")
                    is ToolResult -> if (ev.isError) context.emitProgress?.invoke("子任务(explore)：工具失败 ${ev.errorType ?: "error"}")
                    is RuntimeError -> {
                        lastRuntimeError = ev
                        context.emitProgress?.invoke("子任务(explore)：运行错误 ${ev.errorType}")
                    }
                    is Result -> lastResult = ev
                    else -> Unit
                }
            }

            val result =
                lastResult
                    ?: throw IllegalStateException(
                        "Explore task: missing Result. last_error=${lastRuntimeError?.errorType ?: "none"}",
                    )
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

    private fun humanizeExploreToolUse(
        name: String,
        input: JsonObject?,
    ): String {
        fun str(key: String): String = (input?.get(key) as? JsonPrimitive)?.content?.trim().orEmpty()
        return when (name.trim()) {
            "Read" -> str("file_path").takeIf { it.isNotBlank() }?.let { "读取文件：${it.takeLast(60)}" } ?: "读取文件"
            "List" -> str("path").takeIf { it.isNotBlank() }?.let { "列目录：${it.takeLast(60)}" } ?: "列目录"
            "Glob" -> str("pattern").takeIf { it.isNotBlank() }?.let { "匹配文件：${it.take(60)}" } ?: "匹配文件"
            "Grep" -> str("pattern").takeIf { it.isNotBlank() }?.let { "搜索文本：${it.take(40)}" } ?: "搜索文本"
            else -> name.trim().ifBlank { "工具调用" }
        }
    }
}

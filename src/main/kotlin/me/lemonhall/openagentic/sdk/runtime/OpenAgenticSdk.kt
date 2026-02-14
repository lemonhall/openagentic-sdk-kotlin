package me.lemonhall.openagentic.sdk.runtime

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.lemonhall.openagentic.sdk.compaction.COMPACTION_MARKER_QUESTION
import me.lemonhall.openagentic.sdk.compaction.COMPACTION_SYSTEM_PROMPT
import me.lemonhall.openagentic.sdk.compaction.COMPACTION_USER_INSTRUCTION
import me.lemonhall.openagentic.sdk.compaction.TOOL_OUTPUT_PLACEHOLDER
import me.lemonhall.openagentic.sdk.compaction.selectToolOutputsToPrune
import me.lemonhall.openagentic.sdk.compaction.wouldOverflow
import me.lemonhall.openagentic.sdk.events.AssistantDelta
import me.lemonhall.openagentic.sdk.events.AssistantMessage
import me.lemonhall.openagentic.sdk.events.Event
import me.lemonhall.openagentic.sdk.events.HookEvent
import me.lemonhall.openagentic.sdk.events.Result
import me.lemonhall.openagentic.sdk.events.SystemInit
import me.lemonhall.openagentic.sdk.events.ToolOutputCompacted
import me.lemonhall.openagentic.sdk.events.ToolResult
import me.lemonhall.openagentic.sdk.events.ToolUse
import me.lemonhall.openagentic.sdk.events.UserCompaction
import me.lemonhall.openagentic.sdk.events.UserQuestion
import me.lemonhall.openagentic.sdk.events.UserMessage
import me.lemonhall.openagentic.sdk.events.RuntimeError
import me.lemonhall.openagentic.sdk.json.asStringOrNull
import me.lemonhall.openagentic.sdk.permissions.PermissionGate
import me.lemonhall.openagentic.sdk.permissions.PermissionMode
import me.lemonhall.openagentic.sdk.permissions.UserAnswerer
import me.lemonhall.openagentic.sdk.permissions.ToolPermissionContext
import me.lemonhall.openagentic.sdk.providers.LegacyProvider
import me.lemonhall.openagentic.sdk.providers.LegacyRequest
import me.lemonhall.openagentic.sdk.providers.ModelOutput
import me.lemonhall.openagentic.sdk.providers.ProviderException
import me.lemonhall.openagentic.sdk.providers.ProviderProtocol
import me.lemonhall.openagentic.sdk.providers.ProviderRateLimitException
import me.lemonhall.openagentic.sdk.providers.ResponsesProvider
import me.lemonhall.openagentic.sdk.providers.ResponsesRequest
import me.lemonhall.openagentic.sdk.providers.StreamingResponsesProvider
import me.lemonhall.openagentic.sdk.providers.ToolCall
import me.lemonhall.openagentic.sdk.sessions.EventJson
import me.lemonhall.openagentic.sdk.tools.OpenAiToolSchemas
import me.lemonhall.openagentic.sdk.tools.ToolContext
import me.lemonhall.openagentic.sdk.tools.ToolOutput

object OpenAgenticSdk {
    private const val SDK_VERSION: String = "0.0.0"

    fun query(
        prompt: String,
        options: OpenAgenticOptions,
    ): Flow<Event> =
        flow {
            val store = options.sessionStore
            val resumeId = options.resumeSessionId?.trim().orEmpty().ifEmpty { null }
            val sessionId: String
            val events = mutableListOf<Event>()
            var previousResponseId: String? = null

            if (resumeId != null) {
                sessionId = resumeId
                val past = store.readEvents(sessionId)
                events.addAll(past)
                previousResponseId =
                    past
                        .asReversed()
                        .filterIsInstance<Result>()
                        .firstOrNull { !it.responseId.isNullOrBlank() }
                        ?.responseId
            } else {
                sessionId = store.createSession()
                val init =
                    store.appendEvent(
                        sessionId,
                        SystemInit(sessionId = sessionId, cwd = options.cwd.toString(), sdkVersion = SDK_VERSION),
                    )
                emit(init)
                events.add(init)
            }
            val toolCtx = ToolContext(fileSystem = options.fileSystem, cwd = options.cwd, projectDir = options.projectDir)

            val toolNamesAll =
                (options.tools.names() + listOf("AskUserQuestion", "Task")).distinct()
            val toolNamesEnabled =
                options.allowedTools?.let { allowed -> toolNamesAll.filter { allowed.contains(it) } } ?: toolNamesAll
            val protocol = options.providerProtocolOverride ?: options.provider.protocol

            val toolSchemas =
                when (protocol) {
                    ProviderProtocol.RESPONSES -> OpenAiToolSchemas.forResponses(toolNamesEnabled, registry = options.tools, ctx = toolCtx)
                    ProviderProtocol.LEGACY -> OpenAiToolSchemas.forOpenAi(toolNamesEnabled, registry = options.tools, ctx = toolCtx)
                }

            val hookContextBase =
                buildJsonObject {
                    put("session_id", JsonPrimitive(sessionId))
                    put("cwd", JsonPrimitive(options.cwd.toString()))
                    put("project_dir", JsonPrimitive((options.projectDir ?: options.cwd).toString()))
                    put("provider", JsonPrimitive(options.provider.name))
                    put("provider_protocol", JsonPrimitive(protocol.name.lowercase()))
                    put("model", JsonPrimitive(options.model))
                }

            val promptRun = options.hookEngine.runUserPromptSubmit(prompt = prompt, context = hookContextBase)
            for (he in promptRun.events) {
                val stored = store.appendEvent(sessionId, he) as HookEvent
                emit(stored)
                events.add(stored)
            }
            if (promptRun.decision?.block == true) {
                val result =
                    store.appendEvent(
                        sessionId,
                        Result(
                            finalText = promptRun.decision.blockReason.orEmpty(),
                            sessionId = sessionId,
                            stopReason = "hook_blocked",
                            steps = 0,
                        ),
                    )
                emit(result)
                return@flow
            }
            val prompt2 = promptRun.value

            val user = store.appendEvent(sessionId, UserMessage(text = prompt2))
            emit(user)
            events.add(user)

            var lastModelOut: ModelOutput? = null
            var supportsPreviousResponseId = true

            var steps = 0
            try {
                while (steps < options.maxSteps) {
                    steps++
                    val baseInput: List<JsonObject> =
                        when (protocol) {
                            ProviderProtocol.RESPONSES -> buildResponsesInput(trimEventsForResume(events, options))
                            ProviderProtocol.LEGACY -> buildLegacyMessages(trimEventsForResume(events, options))
                        }

                    val beforeModel = options.hookEngine.runBeforeModelCall(modelInput = baseInput, context = hookContextBase)
                    for (he in beforeModel.events) {
                        val stored = store.appendEvent(sessionId, he) as HookEvent
                        emit(stored)
                        events.add(stored)
                    }
                    if (beforeModel.decision?.block == true) {
                        val result =
                            store.appendEvent(
                                sessionId,
                                Result(
                                    finalText = beforeModel.decision.blockReason.orEmpty(),
                                    sessionId = sessionId,
                                    stopReason = "hook_blocked",
                                    steps = steps,
                                ),
                            )
                        emit(result)
                        return@flow
                    }
                    val modelInput = beforeModel.input

                    val modelOut0 =
                        when (protocol) {
                            ProviderProtocol.RESPONSES -> {
                                val provider = options.provider as? ResponsesProvider
                                    ?: throw IllegalArgumentException("providerProtocolOverride=RESPONSES requires a ResponsesProvider")
                                val req0 =
                                    ResponsesRequest(
                                        model = options.model,
                                        input = modelInput,
                                        tools = toolSchemas,
                                        apiKey = options.apiKey,
                                        previousResponseId = if (supportsPreviousResponseId) previousResponseId else null,
                                    )
                                try {
                                    if (options.includePartialMessages && provider is StreamingResponsesProvider) {
                                        var completed: ModelOutput? = null
                                        (provider as StreamingResponsesProvider).stream(req0).collect { sev ->
                                            when (sev) {
                                                is ProviderStreamEvent.TextDelta -> {
                                                    emit(AssistantDelta(textDelta = sev.delta))
                                                }

                                                is ProviderStreamEvent.Completed -> completed = sev.output
                                                is ProviderStreamEvent.Failed -> throw RuntimeException("provider stream failed: ${sev.message}")
                                            }
                                        }
                                        completed ?: throw RuntimeException("provider stream ended without Completed event")
                                    } else {
                                        callWithRateLimitRetry(options) { provider.complete(req0) }
                                    }
                                } catch (t: Throwable) {
                                    if (t is CancellationException) throw t
                                    val msg = (t.message ?: "").lowercase()
                                    val looksLikePrevId = msg.contains("previous_response_id") || msg.contains("previous response") || msg.contains("previous_response")
                                    if (!looksLikePrevId || previousResponseId.isNullOrBlank()) throw t
                                    supportsPreviousResponseId = false
                                    val req1 = req0.copy(previousResponseId = null)
                                    if (options.includePartialMessages && provider is StreamingResponsesProvider) {
                                        var completed: ModelOutput? = null
                                        (provider as StreamingResponsesProvider).stream(req1).collect { sev ->
                                            when (sev) {
                                                is ProviderStreamEvent.TextDelta -> emit(AssistantDelta(textDelta = sev.delta))
                                                is ProviderStreamEvent.Completed -> completed = sev.output
                                                is ProviderStreamEvent.Failed -> throw RuntimeException("provider stream failed: ${sev.message}")
                                            }
                                        }
                                        completed ?: throw RuntimeException("provider stream ended without Completed event")
                                    } else {
                                        callWithRateLimitRetry(options) { provider.complete(req1) }
                                    }
                                }
                            }
                            ProviderProtocol.LEGACY -> {
                                val provider = options.provider as? LegacyProvider
                                    ?: throw IllegalArgumentException("providerProtocolOverride=LEGACY requires a LegacyProvider")
                                val req =
                                    LegacyRequest(
                                        model = options.model,
                                        messages = modelInput,
                                        tools = toolSchemas,
                                        apiKey = options.apiKey,
                                    )
                                callWithRateLimitRetry(options) { provider.complete(req) }
                            }
                        }

                    val afterModel = options.hookEngine.runAfterModelCall(modelOutput = modelOut0, context = hookContextBase)
                    for (he in afterModel.events) {
                        val stored = store.appendEvent(sessionId, he) as HookEvent
                        emit(stored)
                        events.add(stored)
                    }
                    if (afterModel.decision?.block == true) {
                        val result =
                            store.appendEvent(
                                sessionId,
                                Result(
                                    finalText = afterModel.decision.blockReason.orEmpty(),
                                    sessionId = sessionId,
                                    stopReason = "hook_blocked",
                                    steps = steps,
                                ),
                            )
                        emit(result)
                        return@flow
                    }
                    val modelOut = afterModel.output
                    lastModelOut = modelOut
                    if (modelOut.responseId != null) {
                        previousResponseId = modelOut.responseId
                    }

                    if (modelOut.toolCalls.isNotEmpty()) {
                        for (toolCall in modelOut.toolCalls) {
                            for (ev in runToolCall(sessionId, toolCall, options, toolCtx)) {
                                emit(ev)
                                events.add(ev)
                            }
                        }
                        for (ev in maybePruneToolOutputs(sessionId, options, events)) {
                            emit(ev)
                            events.add(ev)
                        }
                        continue
                    }

                    val assistantText = modelOut.assistantText
                    if (!assistantText.isNullOrEmpty()) {
                        val msg = store.appendEvent(sessionId, AssistantMessage(text = assistantText))
                        emit(msg)
                        events.add(msg)
                    }

                    // Auto-compaction (overflow) is only eligible for legacy or for responses providers
                    // that cannot rely on previous_response_id threading.
                    val compactionEligible = options.compaction.auto && (protocol == ProviderProtocol.LEGACY || !supportsPreviousResponseId)
                    if (compactionEligible && wouldOverflow(options.compaction, modelOut.usage)) {
                        val marker = store.appendEvent(sessionId, UserCompaction(auto = true, reason = "overflow"))
                        emit(marker)
                        events.add(marker)

                        for (ev in runCompactionPass(sessionId = sessionId, options = options, protocol = protocol, supportsPreviousResponseId = supportsPreviousResponseId)) {
                            emit(ev)
                            events.add(ev)
                        }

                        // Rebuild input after compaction pivot; previousResponseId can no longer be trusted.
                        previousResponseId = null

                        val cont = "Continue if you have next steps"
                        val contEv = store.appendEvent(sessionId, UserMessage(text = cont))
                        emit(contEv)
                        events.add(contEv)

                        continue
                    }

                    val result =
                        store.appendEvent(
                            sessionId,
                            Result(
                                finalText = assistantText.orEmpty(),
                                sessionId = sessionId,
                                stopReason = "end",
                                steps = steps,
                                usage = modelOut.usage,
                                responseId = previousResponseId,
                                providerMetadata = modelOut.providerMetadata,
                            ),
                        )
                    emit(result)
                    return@flow
                }

                val result =
                    store.appendEvent(
                        sessionId,
                        Result(
                            finalText = lastModelOut?.assistantText.orEmpty(),
                            sessionId = sessionId,
                            stopReason = "max_steps",
                            steps = steps,
                            usage = lastModelOut?.usage,
                            responseId = previousResponseId,
                            providerMetadata = lastModelOut?.providerMetadata,
                        ),
                    )
                emit(result)
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                val phase = if (t is ProviderException) "provider" else "session"
                val err =
                    store.appendEvent(
                        sessionId,
                        RuntimeError(
                            phase = phase,
                            errorType = t::class.simpleName ?: "RuntimeError",
                            errorMessage = t.message?.take(2_000),
                            provider = options.provider.name,
                        ),
                    )
                emit(err)

                val result =
                    store.appendEvent(
                        sessionId,
                        Result(
                            finalText = "",
                            sessionId = sessionId,
                            stopReason = "error",
                            steps = steps,
                            responseId = previousResponseId,
                        ),
                    )
                emit(result)
            }
        }

    private suspend fun <T> callWithRateLimitRetry(
        options: OpenAgenticOptions,
        block: suspend () -> T,
    ): T {
        val retry = options.providerRetry
        val max = retry.maxRetries.coerceAtLeast(0)
        var attempt = 0
        var backoff = retry.initialBackoffMs.coerceAtLeast(0)
        val maxBackoff = retry.maxBackoffMs.coerceAtLeast(0)

        while (true) {
            try {
                return block()
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                val rl = t as? ProviderRateLimitException ?: throw t
                if (attempt >= max) throw t
                val waitMs: Long =
                    (if (retry.useRetryAfterMs) rl.retryAfterMs?.coerceAtLeast(0) else null)
                        ?: backoff
                if (waitMs > 0) delay(waitMs)
                backoff = minOf(backoff * 2, maxBackoff)
                attempt += 1
            }
        }
    }

    private fun effectivePermissionGate(options: OpenAgenticOptions): PermissionGate {
        val mode = options.permissionModeOverride ?: options.sessionPermissionMode ?: options.permissionGate.mode
        if (mode == options.permissionGate.mode) return options.permissionGate
        return gateForMode(mode = mode, userAnswerer = options.permissionGate.userAnswerer)
    }

    private fun gateForMode(
        mode: PermissionMode,
        userAnswerer: UserAnswerer?,
    ): PermissionGate {
        return when (mode) {
            PermissionMode.BYPASS -> PermissionGate.bypass(userAnswerer = userAnswerer)
            PermissionMode.DENY -> PermissionGate.deny(userAnswerer = userAnswerer)
            PermissionMode.PROMPT -> PermissionGate.prompt(userAnswerer = userAnswerer)
            PermissionMode.DEFAULT -> PermissionGate.default(userAnswerer = userAnswerer)
        }
    }

    suspend fun run(
        prompt: String,
        options: OpenAgenticOptions,
    ): RunResult {
        var sessionId = options.resumeSessionId.orEmpty()
        val events = query(prompt = prompt, options = options).toList()
        val initSessionId = (events.firstOrNull { it is SystemInit } as? SystemInit)?.sessionId
        if (!initSessionId.isNullOrBlank()) {
            sessionId = initSessionId
        }
        val finalText = (events.lastOrNull { it is Result } as? Result)?.finalText.orEmpty()
        return RunResult(finalText = finalText, sessionId = sessionId, events = events)
    }

    private fun buildResponsesInput(events: List<Event>): List<JsonObject> {
        val json = EventJson.json
        val items = mutableListOf<JsonObject>()

        val compactedToolIds =
            events.filterIsInstance<ToolOutputCompacted>()
                .map { it.toolUseId }
                .filter { it.isNotBlank() }
                .toSet()

        val seenCallIds = linkedSetOf<String>()
        loop@ for (e in events) {
            when (e) {
                is UserMessage -> {
                    items.add(buildJsonObject { put("role", JsonPrimitive("user")); put("content", JsonPrimitive(e.text)) })
                }

                is UserCompaction -> {
                    items.add(buildJsonObject { put("role", JsonPrimitive("user")); put("content", JsonPrimitive(COMPACTION_MARKER_QUESTION)) })
                }

                is AssistantMessage -> {
                    items.add(buildJsonObject { put("role", JsonPrimitive("assistant")); put("content", JsonPrimitive(e.text)) })
                }

                is ToolUse -> {
                    val args = e.input ?: buildJsonObject { }
                    seenCallIds.add(e.toolUseId)
                    items.add(
                        buildJsonObject {
                            put("type", JsonPrimitive("function_call"))
                            put("call_id", JsonPrimitive(e.toolUseId))
                            put("name", JsonPrimitive(e.name))
                            put("arguments", JsonPrimitive(json.encodeToString(JsonObject.serializer(), args)))
                        },
                    )
                }

                is ToolResult -> {
                    if (!seenCallIds.contains(e.toolUseId)) continue@loop
                    val output: JsonElement =
                        if (compactedToolIds.contains(e.toolUseId)) {
                            JsonPrimitive(TOOL_OUTPUT_PLACEHOLDER)
                        } else {
                            e.output ?: JsonNull
                        }
                    items.add(
                        buildJsonObject {
                            put("type", JsonPrimitive("function_call_output"))
                            put("call_id", JsonPrimitive(e.toolUseId))
                            put("output", JsonPrimitive(json.encodeToString(JsonElement.serializer(), output)))
                        },
                    )
                }

                else -> Unit
            }
        }
        return items
    }

    private fun buildLegacyMessages(events: List<Event>): List<JsonObject> {
        val json = EventJson.json
        val items = mutableListOf<JsonObject>()
        val seenCallIds = linkedSetOf<String>()

        val compactedToolIds =
            events.filterIsInstance<ToolOutputCompacted>()
                .map { it.toolUseId }
                .filter { it.isNotBlank() }
                .toSet()

        loop@ for (e in events) {
            when (e) {
                is UserMessage -> {
                    items.add(buildJsonObject { put("role", JsonPrimitive("user")); put("content", JsonPrimitive(e.text)) })
                }

                is UserCompaction -> {
                    items.add(buildJsonObject { put("role", JsonPrimitive("user")); put("content", JsonPrimitive(COMPACTION_MARKER_QUESTION)) })
                }

                is AssistantMessage -> {
                    items.add(buildJsonObject { put("role", JsonPrimitive("assistant")); put("content", JsonPrimitive(e.text)) })
                }

                is ToolUse -> {
                    val args = e.input ?: buildJsonObject { }
                    seenCallIds.add(e.toolUseId)
                    items.add(
                        buildJsonObject {
                            put("role", JsonPrimitive("assistant"))
                            put("content", JsonPrimitive(""))
                            put(
                                "tool_calls",
                                JsonArray(
                                    listOf(
                                        buildJsonObject {
                                            put("id", JsonPrimitive(e.toolUseId))
                                            put("type", JsonPrimitive("function"))
                                            put(
                                                "function",
                                                buildJsonObject {
                                                    put("name", JsonPrimitive(e.name))
                                                    put("arguments", JsonPrimitive(json.encodeToString(JsonObject.serializer(), args)))
                                                },
                                            )
                                        },
                                    ),
                                ),
                            )
                        },
                    )
                }

                is ToolResult -> {
                    if (!seenCallIds.contains(e.toolUseId)) continue@loop
                    val content =
                        if (compactedToolIds.contains(e.toolUseId)) {
                            TOOL_OUTPUT_PLACEHOLDER
                        } else {
                            json.encodeToString(JsonElement.serializer(), e.output ?: JsonNull)
                        }
                    items.add(
                        buildJsonObject {
                            put("role", JsonPrimitive("tool"))
                            put("tool_call_id", JsonPrimitive(e.toolUseId))
                            put("content", JsonPrimitive(content))
                        },
                    )
                }

                else -> Unit
            }
        }
        return items
    }

    private fun trimEventsForResume(
        events: List<Event>,
        options: OpenAgenticOptions,
    ): List<Event> {
        val maxEvents = options.resumeMaxEvents.coerceAtLeast(0)
        val maxBytes = options.resumeMaxBytes.coerceAtLeast(0)
        if (maxEvents <= 0 && maxBytes <= 0) return events
        val json = EventJson
        val out = mutableListOf<Event>()
        var bytes = 0
        for (e in events.asReversed()) {
            if (maxEvents > 0 && out.size >= maxEvents) break
            val approx = try { json.dumps(e).length } catch (_: Throwable) { 0 }
            if (maxBytes > 0 && (bytes + approx) > maxBytes && out.isNotEmpty()) break
            out.add(e)
            bytes += approx
        }
        return out.asReversed()
    }

    private suspend fun maybePruneToolOutputs(
        sessionId: String,
        options: OpenAgenticOptions,
        currentEvents: List<Event>,
    ): List<Event> {
        if (!options.compaction.prune) return emptyList()
        val store = options.sessionStore
        val toPrune = selectToolOutputsToPrune(events = currentEvents, compaction = options.compaction)
        if (toPrune.isEmpty()) return emptyList()
        val now = (System.currentTimeMillis().toDouble() / 1000.0)
        val out = mutableListOf<Event>()
        for (tid in toPrune) {
            val ev = store.appendEvent(sessionId, ToolOutputCompacted(toolUseId = tid, compactedTs = now))
            out.add(ev)
        }
        return out
    }

    private suspend fun runCompactionPass(
        sessionId: String,
        options: OpenAgenticOptions,
        protocol: ProviderProtocol,
        supportsPreviousResponseId: Boolean,
    ): List<Event> {
        val provider = options.provider
        val completeResponses = (provider as? ResponsesProvider)
        val completeLegacy = (provider as? LegacyProvider)

        val history = buildCompactionTranscript(options.sessionStore.readEvents(sessionId), options)
        val hookContext = buildJsonObject { put("session_id", JsonPrimitive(sessionId)) }

        val compactingDefault =
            buildJsonObject {
                put("context", JsonArray(emptyList()))
                put("prompt", JsonNull)
            }
        val compactingRun = options.hookEngine.runSessionCompacting(output = compactingDefault, context = hookContext)
        val outEvents = mutableListOf<Event>()
        for (he in compactingRun.events) {
            outEvents.add(options.sessionStore.appendEvent(sessionId, he))
        }
        if (compactingRun.decision?.block == true) return outEvents

        val ctxItems = (compactingRun.output["context"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content } ?: emptyList()
        val promptOverride = (compactingRun.output["prompt"] as? JsonPrimitive)?.content?.trim().orEmpty()
        val promptText =
            if (promptOverride.isNotBlank()) promptOverride else (listOf(COMPACTION_USER_INSTRUCTION) + ctxItems).joinToString("\n\n").trim()

        val input =
            buildList {
                add(buildJsonObject { put("role", JsonPrimitive("system")); put("content", JsonPrimitive(COMPACTION_SYSTEM_PROMPT)) })
                addAll(history)
                add(buildJsonObject { put("role", JsonPrimitive("user")); put("content", JsonPrimitive(promptText)) })
            }

        val modelOut =
            when (protocol) {
                ProviderProtocol.LEGACY -> {
                    requireNotNull(completeLegacy) { "compaction pass requires LegacyProvider when protocol=LEGACY" }
                    completeLegacy.complete(
                        LegacyRequest(
                            model = options.model,
                            messages = input,
                            tools = emptyList(),
                            apiKey = options.apiKey,
                        ),
                    )
                }
                ProviderProtocol.RESPONSES -> {
                    requireNotNull(completeResponses) { "compaction pass requires ResponsesProvider when protocol=RESPONSES" }
                    completeResponses.complete(
                        ResponsesRequest(
                            model = options.model,
                            input = input,
                            tools = emptyList(),
                            apiKey = options.apiKey,
                            previousResponseId = null,
                            store = false,
                        ),
                    )
                }
            }

        val summary = modelOut.assistantText?.trim().orEmpty()
        if (summary.isNotBlank()) {
            outEvents.add(options.sessionStore.appendEvent(sessionId, AssistantMessage(text = summary, isSummary = true)))
        }
        return outEvents
    }

    private fun buildCompactionTranscript(
        events: List<Event>,
        options: OpenAgenticOptions,
    ): List<JsonObject> {
        val json = EventJson.json
        val trimmed = trimEventsForResume(events, options)
        val compactedToolIds =
            trimmed.filterIsInstance<ToolOutputCompacted>()
                .map { it.toolUseId }
                .filter { it.isNotBlank() }
                .toSet()

        val out = mutableListOf<JsonObject>()
        for (e in trimmed) {
            when (e) {
                is UserMessage -> out.add(buildJsonObject { put("role", JsonPrimitive("user")); put("content", JsonPrimitive(e.text)) })
                is UserCompaction -> out.add(buildJsonObject { put("role", JsonPrimitive("user")); put("content", JsonPrimitive(COMPACTION_MARKER_QUESTION)) })
                is AssistantMessage -> out.add(buildJsonObject { put("role", JsonPrimitive("assistant")); put("content", JsonPrimitive(e.text)) })
                is ToolUse -> {
                    val args = e.input ?: buildJsonObject { }
                    val argsJson = json.encodeToString(JsonObject.serializer(), args)
                    val txt = "[tool.call ${e.name}] $argsJson".trim()
                    out.add(buildJsonObject { put("role", JsonPrimitive("assistant")); put("content", JsonPrimitive(txt)) })
                }
                is ToolResult -> {
                    val content =
                        if (compactedToolIds.contains(e.toolUseId)) {
                            TOOL_OUTPUT_PLACEHOLDER
                        } else {
                            json.encodeToString(JsonElement.serializer(), e.output ?: JsonNull)
                        }
                    val txt = "[tool.result ${e.toolUseId}] $content".trim()
                    out.add(buildJsonObject { put("role", JsonPrimitive("assistant")); put("content", JsonPrimitive(txt)) })
                }
                else -> Unit
            }
        }
        return out
    }

    private suspend fun runToolCall(
        sessionId: String,
        toolCall: ToolCall,
        options: OpenAgenticOptions,
        toolCtx: ToolContext,
    ): List<Event> {
        val store = options.sessionStore
        val toolName = toolCall.name
        val toolInput0 = toolCall.arguments

        val out = mutableListOf<Event>()

        val hookContext =
            buildJsonObject {
                put("session_id", JsonPrimitive(sessionId))
                put("tool_use_id", JsonPrimitive(toolCall.toolUseId))
            }

        val pre = options.hookEngine.runPreToolUse(toolName = toolName, toolInput = toolInput0, context = hookContext)
        for (he in pre.events) {
            val stored = store.appendEvent(sessionId, he) as HookEvent
            out.add(stored)
        }
        val toolInput = pre.input

        val use =
            store.appendEvent(
                sessionId,
                ToolUse(
                    toolUseId = toolCall.toolUseId,
                    name = toolName,
                    input = toolInput,
                ),
            )
        out.add(use)

        if (pre.decision?.block == true) {
            val denied =
                store.appendEvent(
                    sessionId,
                    ToolResult(
                        toolUseId = toolCall.toolUseId,
                        output = null,
                        isError = true,
                        errorType = "HookBlocked",
                        errorMessage = pre.decision.blockReason ?: "blocked by hook",
                    ),
                )
            out.add(denied)
            return out
        }

        val allowed = options.allowedTools
        if (allowed != null && !allowed.contains(toolName)) {
            val denied =
                store.appendEvent(
                    sessionId,
                    ToolResult(
                        toolUseId = toolCall.toolUseId,
                        output = null,
                        isError = true,
                        errorType = "ToolNotAllowed",
                        errorMessage = "Tool '$toolName' is not allowed",
                    ),
                )
            out.add(denied)
            return out
        }

        val approval =
            effectivePermissionGate(options).approve(
                toolName = toolName,
                toolInput = toolInput,
                context = ToolPermissionContext(sessionId = sessionId, toolUseId = toolCall.toolUseId),
            )
        if (approval.question != null) {
            val q = store.appendEvent(sessionId, approval.question)
            out.add(q)
        }
        if (!approval.allowed) {
            val denied =
                store.appendEvent(
                    sessionId,
                    ToolResult(
                        toolUseId = toolCall.toolUseId,
                        output = null,
                        isError = true,
                        errorType = "PermissionDenied",
                        errorMessage = approval.denyMessage ?: "tool use not approved",
                    ),
                )
            out.add(denied)
            return out
        }
        val toolInput2 = approval.updatedInput ?: toolInput

        if (toolName == "AskUserQuestion") {
            val events2 = handleAskUserQuestion(sessionId, toolCall, toolInput2, options)
            out.addAll(events2)
            return out
        }

        if (toolName == "Task") {
            val events2 = handleTask(sessionId, toolCall, toolInput2, options)
            out.addAll(events2)
            return out
        }

        val tool =
            try {
                options.tools.get(toolName)
            } catch (e: Exception) {
                val err =
                    store.appendEvent(
                        sessionId,
                        ToolResult(
                            toolUseId = toolCall.toolUseId,
                            output = null,
                            isError = true,
                            errorType = e::class.simpleName ?: "UnknownTool",
                            errorMessage = e.message ?: "unknown tool",
                        ),
                    )
                return listOf(use, err)
            }

        val resultEvent: Event =
            try {
                val toolOut =
                    when (val out0 = tool.run(toolInput2, toolCtx)) {
                        is ToolOutput.Json -> out0.value
                    }

                val post = options.hookEngine.runPostToolUse(toolName = toolName, toolOutput = toolOut, context = hookContext)
                for (he in post.events) {
                    val stored = store.appendEvent(sessionId, he) as HookEvent
                    out.add(stored)
                }
                if (post.decision?.block == true) {
                    store.appendEvent(
                        sessionId,
                        ToolResult(
                            toolUseId = toolCall.toolUseId,
                            output = null,
                            isError = true,
                            errorType = "HookBlocked",
                            errorMessage = post.decision.blockReason ?: "blocked by hook",
                        ),
                    )
                } else {
                    store.appendEvent(
                        sessionId,
                        ToolResult(
                            toolUseId = toolCall.toolUseId,
                            output = post.output,
                            isError = false,
                        ),
                    )
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                store.appendEvent(
                    sessionId,
                    ToolResult(
                        toolUseId = toolCall.toolUseId,
                        output = null,
                        isError = true,
                        errorType = e::class.simpleName ?: "ToolError",
                        errorMessage = e.message ?: "tool error",
                    ),
                )
            }

        out.add(resultEvent)
        return out
    }

    private suspend fun handleTask(
        sessionId: String,
        toolCall: ToolCall,
        toolInput: JsonObject,
        options: OpenAgenticOptions,
    ): List<Event> {
        val store = options.sessionStore
        val agent = toolInput["agent"]?.asStringOrNull()?.trim().orEmpty()
        val prompt = toolInput["prompt"]?.asStringOrNull()?.trim().orEmpty()
        if (agent.isEmpty() || prompt.isEmpty()) {
            val err =
                store.appendEvent(
                    sessionId,
                    ToolResult(
                        toolUseId = toolCall.toolUseId,
                        output = null,
                        isError = true,
                        errorType = "InvalidTaskInput",
                        errorMessage = "Task: 'agent' and 'prompt' must be non-empty strings",
                    ),
                )
            return listOf(err)
        }

        val runner = options.taskRunner
        if (runner == null) {
            val err =
                store.appendEvent(
                    sessionId,
                    ToolResult(
                        toolUseId = toolCall.toolUseId,
                        output = null,
                        isError = true,
                        errorType = "NoTaskRunner",
                        errorMessage = "Task: no taskRunner is configured",
                    ),
                )
            return listOf(err)
        }

        return try {
            val output = runner.run(agent = agent, prompt = prompt, context = TaskContext(sessionId = sessionId, toolUseId = toolCall.toolUseId))
            val ok =
                store.appendEvent(
                    sessionId,
                    ToolResult(
                        toolUseId = toolCall.toolUseId,
                        output = output,
                        isError = false,
                    ),
                )
            listOf(ok)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            val err =
                store.appendEvent(
                    sessionId,
                    ToolResult(
                        toolUseId = toolCall.toolUseId,
                        output = null,
                        isError = true,
                        errorType = e::class.simpleName ?: "TaskError",
                        errorMessage = e.message ?: "task error",
                    ),
                )
            listOf(err)
        }
    }

    private suspend fun handleAskUserQuestion(
        sessionId: String,
        toolCall: ToolCall,
        toolInput: JsonObject,
        options: OpenAgenticOptions,
    ): List<Event> {
        val store = options.sessionStore

        val questionsElement = toolInput["questions"]
        val questions: List<JsonObject> =
            when {
                questionsElement is JsonObject -> listOf(questionsElement)
                questionsElement is JsonArray -> questionsElement.mapNotNull { it as? JsonObject }
                else -> {
                    val qText =
                        toolInput["question"]?.asStringOrNull()?.trim().orEmpty()
                            .ifEmpty { toolInput["prompt"]?.asStringOrNull()?.trim().orEmpty() }
                    if (qText.isEmpty()) emptyList()
                    else {
                        val opts = toolInput["options"] ?: toolInput["choices"]
                        val optionLabels = parseOptionLabels(opts)
                        val q =
                            buildJsonObject {
                                put("question", JsonPrimitive(qText))
                                put(
                                    "options",
                                    JsonArray(optionLabels.map { buildJsonObject { put("label", JsonPrimitive(it)) } }),
                                )
                            }
                        listOf(q)
                    }
                }
            }

        if (questions.isEmpty()) {
            val err =
                store.appendEvent(
                    sessionId,
                    ToolResult(
                        toolUseId = toolCall.toolUseId,
                        output = null,
                        isError = true,
                        errorType = "InvalidAskUserQuestionInput",
                        errorMessage = "AskUserQuestion: 'questions' must be a non-empty list",
                    ),
                )
            return listOf(err)
        }

        val answerer = options.permissionGate.userAnswerer
        if (answerer == null) {
            val err =
                store.appendEvent(
                    sessionId,
                    ToolResult(
                        toolUseId = toolCall.toolUseId,
                        output = null,
                        isError = true,
                        errorType = "NoUserAnswerer",
                        errorMessage = "AskUserQuestion: no userAnswerer is configured",
                    ),
                )
            return listOf(err)
        }

        val out = mutableListOf<Event>()
        val answers = linkedMapOf<String, JsonElement>()
        for ((i, q) in questions.withIndex()) {
            val qText = q["question"]?.asStringOrNull()?.trim().orEmpty()
            if (qText.isEmpty()) continue
            val labels = parseOptionLabels(q["options"])
            val uq =
                UserQuestion(
                    questionId = "${toolCall.toolUseId}:$i",
                    prompt = qText,
                    choices = if (labels.isNotEmpty()) labels else listOf("ok"),
                )
            val storedQ = store.appendEvent(sessionId, uq) as Event
            out.add(storedQ)
            val ans = answerer.answer(uq)
            answers[qText] = ans
        }

        val resultOutput =
            buildJsonObject {
                put("questions", JsonArray(questions))
                put("answers", JsonObject(answers))
            }
        val result =
            store.appendEvent(
                sessionId,
                ToolResult(
                    toolUseId = toolCall.toolUseId,
                    output = resultOutput,
                    isError = false,
                ),
            )
        out.add(result)
        return out
    }

    private fun parseOptionLabels(element: JsonElement?): List<String> {
        if (element == null) return emptyList()
        return when (element) {
            is JsonArray -> {
                element.mapNotNull { el ->
                    when (el) {
                        is JsonPrimitive -> el.asStringOrNull()?.trim()?.takeIf { it.isNotEmpty() }
                        is JsonObject -> {
                            el["label"]?.asStringOrNull()?.trim()?.takeIf { it.isNotEmpty() }
                                ?: el["name"]?.asStringOrNull()?.trim()?.takeIf { it.isNotEmpty() }
                                ?: el["value"]?.asStringOrNull()?.trim()?.takeIf { it.isNotEmpty() }
                        }
                        else -> null
                    }
                }
            }
            is JsonObject -> {
                val arr = element["options"] ?: element["choices"]
                if (arr is JsonArray) parseOptionLabels(arr) else emptyList()
            }
            else -> emptyList()
        }
    }
}

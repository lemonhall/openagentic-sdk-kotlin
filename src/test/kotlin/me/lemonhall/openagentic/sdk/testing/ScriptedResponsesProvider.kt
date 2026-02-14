package me.lemonhall.openagentic.sdk.testing

import kotlinx.serialization.json.JsonObject
import me.lemonhall.openagentic.sdk.providers.ModelOutput
import me.lemonhall.openagentic.sdk.providers.ResponsesProvider
import me.lemonhall.openagentic.sdk.providers.ResponsesRequest

data class CapturedResponsesCall(
    val model: String,
    val input: List<JsonObject>,
    val previousResponseId: String?,
)

class ScriptedResponsesProvider(
    override val name: String = "scripted",
    private val script: suspend (step: Int, request: ResponsesRequest) -> ModelOutput,
) : ResponsesProvider {
    val calls = mutableListOf<CapturedResponsesCall>()
    private var step = 0

    override suspend fun complete(request: ResponsesRequest): ModelOutput {
        calls.add(CapturedResponsesCall(model = request.model, input = request.input, previousResponseId = request.previousResponseId))
        step += 1
        return script(step, request)
    }
}


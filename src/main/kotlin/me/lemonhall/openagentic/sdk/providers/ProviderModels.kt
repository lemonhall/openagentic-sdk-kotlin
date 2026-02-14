package me.lemonhall.openagentic.sdk.providers

import kotlinx.serialization.json.JsonObject

data class ToolCall(
    val toolUseId: String,
    val name: String,
    val arguments: JsonObject,
)

data class ModelOutput(
    val assistantText: String?,
    val toolCalls: List<ToolCall>,
    val usage: JsonObject? = null,
    val responseId: String? = null,
    val providerMetadata: JsonObject? = null,
)

enum class ProviderProtocol {
    RESPONSES,
    LEGACY,
}

sealed interface Provider {
    val name: String
    val protocol: ProviderProtocol
}

data class ResponsesRequest(
    val model: String,
    val input: List<JsonObject>,
    val tools: List<JsonObject> = emptyList(),
    val apiKey: String? = null,
    val previousResponseId: String? = null,
    val store: Boolean? = null,
)

data class LegacyRequest(
    val model: String,
    val messages: List<JsonObject>,
    val tools: List<JsonObject> = emptyList(),
    val apiKey: String? = null,
)

interface ResponsesProvider : Provider {
    override val protocol: ProviderProtocol
        get() = ProviderProtocol.RESPONSES

    suspend fun complete(request: ResponsesRequest): ModelOutput
}

interface LegacyProvider : Provider {
    override val protocol: ProviderProtocol
        get() = ProviderProtocol.LEGACY

    suspend fun complete(request: LegacyRequest): ModelOutput
}

interface StreamingResponsesProvider : ResponsesProvider {
    fun stream(request: ResponsesRequest): kotlinx.coroutines.flow.Flow<me.lemonhall.openagentic.sdk.runtime.ProviderStreamEvent>
}

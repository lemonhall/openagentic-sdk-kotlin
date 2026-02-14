package me.lemonhall.openagentic.sdk.providers

import java.net.URI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.lemonhall.openagentic.sdk.runtime.ProviderStreamEvent

class OpenAIResponsesHttpProvider(
    override val name: String = "openai-responses",
    private val baseUrl: String = "https://api.openai.com/v1",
    private val apiKeyHeader: String = "authorization",
    private val timeoutMs: Int = 60_000,
    private val defaultStore: Boolean = true,
) : StreamingResponsesProvider {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    override suspend fun complete(request: ResponsesRequest): ModelOutput {
        val apiKey = request.apiKey?.trim().orEmpty()
        require(apiKey.isNotEmpty()) { "OpenAIResponsesHttpProvider: apiKey is required" }

        val url = "${baseUrl.trimEnd('/')}/responses"
        val headers = buildHeaders(apiKey = apiKey)
        val payload =
            buildJsonObject {
                put("model", JsonPrimitive(request.model))
                put("input", JsonArray(request.input))
                val storeFlag = request.store ?: defaultStore
                put("store", JsonPrimitive(storeFlag))
                if (!request.previousResponseId.isNullOrBlank()) put("previous_response_id", JsonPrimitive(request.previousResponseId))
                if (request.tools.isNotEmpty()) put("tools", JsonArray(request.tools))
            }

        val body = json.encodeToString(JsonObject.serializer(), payload)
        val raw = postJson(url = url, headers = headers, body = body)
        val root = json.parseToJsonElement(raw).jsonObject

        val responseId = root["id"]?.jsonPrimitive?.contentOrNull
        val usage = root["usage"] as? JsonObject
        val outputItems = (root["output"] as? JsonArray)?.mapNotNull { it as? JsonObject } ?: emptyList()

        val assistantText = parseAssistantText(outputItems)
        val toolCalls = parseToolCalls(outputItems)

        return ModelOutput(
            assistantText = assistantText,
            toolCalls = toolCalls,
            usage = usage,
            responseId = responseId,
            providerMetadata = null,
        )
    }

    override fun stream(request: ResponsesRequest): Flow<me.lemonhall.openagentic.sdk.runtime.ProviderStreamEvent> =
        flow {
            val apiKey = request.apiKey?.trim().orEmpty()
            require(apiKey.isNotEmpty()) { "OpenAIResponsesHttpProvider: apiKey is required" }

            val url = "${baseUrl.trimEnd('/')}/responses"
            val headers = buildHeaders(apiKey = apiKey, acceptEventStream = true)
            val payload =
                buildJsonObject {
                    put("model", JsonPrimitive(request.model))
                    put("input", JsonArray(request.input))
                    val storeFlag = request.store ?: defaultStore
                    put("store", JsonPrimitive(storeFlag))
                    put("stream", JsonPrimitive(true))
                    if (!request.previousResponseId.isNullOrBlank()) put("previous_response_id", JsonPrimitive(request.previousResponseId))
                    if (request.tools.isNotEmpty()) put("tools", JsonArray(request.tools))
                }

            val body = json.encodeToString(JsonObject.serializer(), payload)
            var lastResponse: JsonObject? = null
            val conn = (URI(url).toURL().openConnection() as java.net.HttpURLConnection)
            conn.requestMethod = "POST"
            conn.instanceFollowRedirects = false
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.doOutput = true
            for ((k, v) in headers) conn.setRequestProperty(k, v)
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

            val status = conn.responseCode
            val stream =
                try {
                    if (status >= 400) conn.errorStream else conn.inputStream
                } catch (_: Throwable) {
                    null
                }
            if (stream == null) throw RuntimeException("no response stream")
            if (status >= 400) {
                val err = stream.readBytes().toString(Charsets.UTF_8)
                throw RuntimeException("HTTP $status from $url: $err".trim())
            }
            val reader = stream.bufferedReader(Charsets.UTF_8)
            reader.use {
                while (true) {
                    val ln = reader.readLine() ?: break
                    val t = ln.trimEnd()
                    if (!t.startsWith("data:")) continue
                    val data = t.removePrefix("data:").trimStart()
                    if (data.isBlank()) continue
                    if (data == "[DONE]") break
                    val obj =
                        try {
                            json.parseToJsonElement(data) as? JsonObject
                        } catch (_: Throwable) {
                            null
                        } ?: continue
                    val type = obj["type"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                    if (type == "response.output_text.delta") {
                        val delta = obj["delta"]?.jsonPrimitive?.contentOrNull
                        if (!delta.isNullOrEmpty()) emit(ProviderStreamEvent.TextDelta(delta))
                        continue
                    }
                    if (type == "response.completed") {
                        lastResponse = obj["response"] as? JsonObject
                        continue
                    }
                    if (type == "error") {
                        emit(ProviderStreamEvent.Failed(message = obj.toString(), raw = obj))
                        break
                    }
                }
            }

            val resp = lastResponse
            if (resp == null) {
                emit(ProviderStreamEvent.Failed(message = "stream ended without response.completed"))
                return@flow
            }
            val responseId = resp["id"]?.jsonPrimitive?.contentOrNull
            val usage = resp["usage"] as? JsonObject
            val outputItems = (resp["output"] as? JsonArray)?.mapNotNull { it as? JsonObject } ?: emptyList()
            val assistantText = parseAssistantText(outputItems)
            val toolCalls = parseToolCalls(outputItems)
            emit(
                ProviderStreamEvent.Completed(
                    ModelOutput(
                        assistantText = assistantText,
                        toolCalls = toolCalls,
                        usage = usage,
                        responseId = responseId,
                        providerMetadata = null,
                    ),
                ),
            )
        }.flowOn(Dispatchers.IO)

    private fun buildHeaders(
        apiKey: String,
        acceptEventStream: Boolean = false,
    ): Map<String, String> {
        val h = linkedMapOf<String, String>()
        h["content-type"] = "application/json"
        if (acceptEventStream) h["accept"] = "text/event-stream"
        if (apiKeyHeader.lowercase() == "authorization") {
            h["authorization"] = "Bearer $apiKey"
        } else {
            h[apiKeyHeader] = apiKey
        }
        return h
    }

    private suspend fun postJson(
        url: String,
        headers: Map<String, String>,
        body: String,
    ): String {
        return withContext(Dispatchers.IO) {
            val conn = (URI(url).toURL().openConnection() as java.net.HttpURLConnection)
            conn.requestMethod = "POST"
            conn.instanceFollowRedirects = false
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.doOutput = true
            for ((k, v) in headers) conn.setRequestProperty(k, v)
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

            val status = conn.responseCode
            val stream =
                try {
                    if (status >= 400) conn.errorStream else conn.inputStream
                } catch (_: Throwable) {
                    null
                }
            val raw = stream?.readBytes()?.toString(Charsets.UTF_8).orEmpty()
            if (status >= 400) {
                throw RuntimeException("HTTP $status from $url: $raw".trim())
            }
            raw
        }
    }

    // (no separate SSE helper; stream() reads line-by-line and emits as it goes)

    private fun parseAssistantText(outputItems: List<JsonObject>): String? {
        val parts = mutableListOf<String>()
        for (item in outputItems) {
            if (item["type"]?.jsonPrimitive?.content != "message") continue
            val content = item["content"] as? JsonArray ?: continue
            for (partEl in content) {
                val part = partEl as? JsonObject ?: continue
                if (part["type"]?.jsonPrimitive?.content != "output_text") continue
                val text = part["text"]?.jsonPrimitive?.contentOrNull
                if (!text.isNullOrEmpty()) parts.add(text)
            }
        }
        if (parts.isEmpty()) return null
        return parts.joinToString("")
    }

    private fun parseToolCalls(outputItems: List<JsonObject>): List<ToolCall> {
        val out = mutableListOf<ToolCall>()
        for (item in outputItems) {
            if (item["type"]?.jsonPrimitive?.content != "function_call") continue
            val callId = item["call_id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: continue
            val name = item["name"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: continue
            val argsEl = item["arguments"]
            val argsObj =
                when (argsEl) {
                    is JsonObject -> argsEl
                    is JsonPrimitive -> {
                        val raw = argsEl.content
                        if (argsEl.isString) parseArgs(raw) else parseArgs(raw)
                    }
                    null -> buildJsonObject { }
                    else -> buildJsonObject { put("_raw", JsonPrimitive(argsEl.toString())) }
                }
            out.add(ToolCall(toolUseId = callId, name = name, arguments = argsObj))
        }
        return out
    }

    private fun parseArgs(raw: String): JsonObject {
        val s = raw.trim()
        if (s.isEmpty()) return buildJsonObject { }
        return try {
            val el = json.parseToJsonElement(s)
            el as? JsonObject ?: buildJsonObject { put("_raw", JsonPrimitive(raw)) }
        } catch (_: Throwable) {
            buildJsonObject { put("_raw", JsonPrimitive(raw)) }
        }
    }
}

private val JsonPrimitive.contentOrNull: String?
    get() = try {
        this.content
    } catch (_: Throwable) {
        null
    }

package me.lemonhall.openagentic.sdk.providers

import java.io.EOFException
import java.io.InputStreamReader
import java.net.URI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.lemonhall.openagentic.sdk.runtime.ProviderStreamEvent

/**
 * Generic OpenAI Chat Completions-compatible provider.
 *
 * Works with DeepSeek, Groq, Together, Mistral, local Ollama,
 * and any other provider that implements the `/v1/chat/completions` API.
 *
 * Implements [StreamingResponsesProvider] — the SDK core loop sends
 * Responses-format input, and this provider converts it internally
 * to Chat Completions format.
 */
class OpenAIChatCompletionsHttpProvider(
    override val name: String = "openai-chatcompletions",
    private val baseUrl: String = "https://api.openai.com/v1",
    private val timeoutMs: Int = 60_000,
    private val streamReadTimeoutMs: Int = 5 * 60_000,
) : StreamingResponsesProvider {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    override suspend fun complete(request: ResponsesRequest): ModelOutput {
        val apiKey = request.apiKey?.trim().orEmpty()
        require(apiKey.isNotEmpty()) { "OpenAIChatCompletionsHttpProvider: apiKey is required" }

        val url = "${baseUrl.trimEnd('/')}/chat/completions"
        val headers = buildHeaders(apiKey = apiKey)
        val payload = buildPayload(request, stream = false)
        val body = json.encodeToString(JsonObject.serializer(), payload)
        val raw = postJson(url = url, headers = headers, body = body)

        val root = try {
            json.parseToJsonElement(raw).jsonObject
        } catch (t: Throwable) {
            throw ProviderInvalidResponseException(
                "OpenAIChatCompletionsHttpProvider: invalid JSON response",
                raw = raw.take(2_000), cause = t,
            )
        }

        return chatCompletionsResponseToModelOutput(root, json)
    }

    override fun stream(request: ResponsesRequest): Flow<ProviderStreamEvent> =
        flow {
            val apiKey = request.apiKey?.trim().orEmpty()
            require(apiKey.isNotEmpty()) { "OpenAIChatCompletionsHttpProvider: apiKey is required" }

            val url = "${baseUrl.trimEnd('/')}/chat/completions"
            val headers = buildHeaders(apiKey = apiKey, acceptEventStream = true)
            val payload = buildPayload(request, stream = true)
            val body = json.encodeToString(JsonObject.serializer(), payload)

            val sseParser = SseEventParser()
            val decoder = ChatCompletionsSseDecoder(json = json)
            val conn = (URI(url).toURL().openConnection() as java.net.HttpURLConnection)
            try {
                conn.requestMethod = "POST"
                conn.instanceFollowRedirects = false
                conn.connectTimeout = timeoutMs
                conn.readTimeout = streamReadTimeoutMs
                conn.doOutput = true
                for ((k, v) in headers) conn.setRequestProperty(k, v)
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

                val status = try {
                    runInterruptible { conn.responseCode }
                } catch (t: java.net.SocketTimeoutException) {
                    throw ProviderTimeoutException("OpenAIChatCompletionsHttpProvider: timeout", t)
                }
                val stream = try {
                    runInterruptible { if (status >= 400) conn.errorStream else conn.inputStream }
                } catch (_: Throwable) { null }
                if (stream == null) throw RuntimeException("no response stream")
                if (status >= 400) {
                    val err = stream.readBytes().toString(Charsets.UTF_8)
                    if (status == 429) {
                        val retryAfterMs = parseRetryAfterMs(conn.getHeaderField("retry-after"))
                        throw ProviderRateLimitException("HTTP 429 from $url: $err".trim(), retryAfterMs = retryAfterMs)
                    }
                    throw ProviderHttpException(status = status, message = "HTTP $status from $url: $err".trim(), body = err)
                }
                try {
                    InputStreamReader(stream, Charsets.UTF_8).use { reader ->
                        val buf = CharArray(8192)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val n = runInterruptible { reader.read(buf) }
                            if (n < 0) break
                            for (ev in sseParser.feed(String(buf, 0, n))) {
                                for (sev in decoder.onSseEvent(ev)) emit(sev)
                            }
                        }
                        for (ev in sseParser.endOfInput()) {
                            for (sev in decoder.onSseEvent(ev)) emit(sev)
                        }
                    }
                } catch (t: java.net.SocketTimeoutException) {
                    throw ProviderTimeoutException("OpenAIChatCompletionsHttpProvider: stream timeout", t)
                } catch (t: EOFException) {
                    throw ProviderTimeoutException("OpenAIChatCompletionsHttpProvider: stream EOF", t)
                } catch (t: java.net.SocketException) {
                    throw ProviderTimeoutException("OpenAIChatCompletionsHttpProvider: stream socket error", t)
                } catch (t: java.io.IOException) {
                    val msg = (t.message ?: "").lowercase()
                    val looksLikeDisconnect = msg.contains("reset") || msg.contains("broken pipe") || msg.contains("eof")
                    if (looksLikeDisconnect) {
                        throw ProviderTimeoutException("OpenAIChatCompletionsHttpProvider: stream io disconnect", t)
                    }
                    throw t
                }
                for (sev in decoder.finish()) emit(sev)
            } finally {
                try { conn.disconnect() } catch (_: Throwable) { }
            }
        }.flowOn(Dispatchers.IO)

    private fun buildPayload(request: ResponsesRequest, stream: Boolean): JsonObject {
        val messages = responsesInputToChatCompletionsMessages(request.input, json)
        val tools = responsesToolsToChatCompletionsTools(request.tools)

        return buildJsonObject {
            put("model", JsonPrimitive(request.model))
            put("messages", JsonArray(messages))
            if (tools.isNotEmpty()) put("tools", JsonArray(tools))
            if (stream) {
                put("stream", JsonPrimitive(true))
                // Request usage in stream for providers that support it
                put("stream_options", buildJsonObject {
                    put("include_usage", JsonPrimitive(true))
                })
            }
        }
    }

    private fun buildHeaders(
        apiKey: String,
        acceptEventStream: Boolean = false,
    ): Map<String, String> {
        val h = linkedMapOf<String, String>()
        h["content-type"] = "application/json"
        h["authorization"] = "Bearer $apiKey"
        if (acceptEventStream) h["accept"] = "text/event-stream"
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

            val status = try {
                conn.responseCode
            } catch (t: java.net.SocketTimeoutException) {
                throw ProviderTimeoutException("OpenAIChatCompletionsHttpProvider: timeout", t)
            }
            val stream = try {
                if (status >= 400) conn.errorStream else conn.inputStream
            } catch (_: Throwable) { null }
            val raw = stream?.readBytes()?.toString(Charsets.UTF_8).orEmpty()
            if (status >= 400) {
                if (status == 429) {
                    val retryAfterMs = parseRetryAfterMs(conn.getHeaderField("retry-after"))
                    throw ProviderRateLimitException("HTTP 429 from $url: $raw".trim(), retryAfterMs = retryAfterMs)
                }
                throw ProviderHttpException(status = status, message = "HTTP $status from $url: $raw".trim(), body = raw)
            }
            raw
        }
    }
}

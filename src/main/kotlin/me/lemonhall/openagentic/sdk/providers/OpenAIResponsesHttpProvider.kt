package me.lemonhall.openagentic.sdk.providers

import java.net.URI
import java.io.EOFException
import java.io.InputStreamReader
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
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
    // Streaming SSE can legitimately be idle (no deltas) for long periods on some providers/models.
    // Use a larger read timeout for the event stream to avoid spurious SocketTimeoutException.
    private val streamReadTimeoutMs: Int = 5 * 60_000,
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
        val root =
            try {
                json.parseToJsonElement(raw).jsonObject
            } catch (t: Throwable) {
                throw ProviderInvalidResponseException("OpenAIResponsesHttpProvider: invalid JSON response", raw = raw.take(2_000), cause = t)
            }

        val responseId = root["id"]?.jsonPrimitive?.contentOrNull
        val usage = root["usage"] as? JsonObject
        val outputItems = (root["output"] as? JsonArray)?.mapNotNull { it as? JsonObject } ?: emptyList()

        val assistantText = parseAssistantText(outputItems)
        val toolCalls = parseToolCalls(outputItems, json = json)

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
            val sseParser = SseEventParser()
            val decoder = OpenAIResponsesSseDecoder(json = json)
            val conn = (URI(url).toURL().openConnection() as java.net.HttpURLConnection)
            try {
                conn.requestMethod = "POST"
                conn.instanceFollowRedirects = false
                conn.connectTimeout = timeoutMs
                conn.readTimeout = streamReadTimeoutMs
                conn.doOutput = true
                for ((k, v) in headers) conn.setRequestProperty(k, v)
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

                val status =
                    try {
                        runInterruptible { conn.responseCode }
                    } catch (t: java.net.SocketTimeoutException) {
                        throw ProviderTimeoutException("OpenAIResponsesHttpProvider: timeout", t)
                    }
                val stream =
                    try {
                        runInterruptible { if (status >= 400) conn.errorStream else conn.inputStream }
                    } catch (_: Throwable) {
                        null
                    }
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
                    throw ProviderTimeoutException("OpenAIResponsesHttpProvider: stream timeout", t)
                } catch (t: EOFException) {
                    // Treat abrupt stream termination as transient to align with Codex/OpenCode retry semantics.
                    throw ProviderTimeoutException("OpenAIResponsesHttpProvider: stream EOF", t)
                } catch (t: java.net.SocketException) {
                    // e.g. "Connection reset" or other transport-level drops.
                    throw ProviderTimeoutException("OpenAIResponsesHttpProvider: stream socket error", t)
                } catch (t: java.io.IOException) {
                    val msg = (t.message ?: "").lowercase()
                    val looksLikeDisconnect = msg.contains("reset") || msg.contains("broken pipe") || msg.contains("eof")
                    if (looksLikeDisconnect) {
                        throw ProviderTimeoutException("OpenAIResponsesHttpProvider: stream io disconnect", t)
                    }
                    throw t
                }
                for (sev in decoder.finish()) emit(sev)
            } finally {
                try {
                    conn.disconnect()
                } catch (_: Throwable) {
                }
            }
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

            val status =
                try {
                    conn.responseCode
                } catch (t: java.net.SocketTimeoutException) {
                    throw ProviderTimeoutException("OpenAIResponsesHttpProvider: timeout", t)
                }
            val stream =
                try {
                    if (status >= 400) conn.errorStream else conn.inputStream
                } catch (_: Throwable) {
                    null
                }
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

internal fun parseRetryAfterMs(
    header: String?,
    nowEpochMs: Long = System.currentTimeMillis(),
): Long? {
    val raw = header?.trim().orEmpty()
    if (raw.isBlank()) return null

    fun capMs(ms: Long): Long {
        // Avoid overflow / pathological sleeps; align with "32-bit signed" caps.
        return ms.coerceIn(0, Int.MAX_VALUE.toLong())
    }

    val msSuffix = Regex("^([0-9]+)\\s*ms$", RegexOption.IGNORE_CASE).find(raw)
    if (msSuffix != null) {
        val ms = msSuffix.groupValues[1].toLongOrNull() ?: return null
        return capMs(ms)
    }

    val seconds = raw.toLongOrNull()
    if (seconds != null) {
        val ms =
            if (seconds <= Long.MAX_VALUE / 1000L) seconds * 1000L else Long.MAX_VALUE
        return capMs(ms)
    }

    return try {
        val dt = ZonedDateTime.parse(raw, DateTimeFormatter.RFC_1123_DATE_TIME).withZoneSameInstant(ZoneOffset.UTC)
        val targetMs = dt.toInstant().toEpochMilli()
        val delta = targetMs - nowEpochMs
        if (delta <= 0) null else capMs(delta)
    } catch (_: Throwable) {
        null
    }
}

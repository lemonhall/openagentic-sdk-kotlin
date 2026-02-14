package me.lemonhall.openagentic.sdk.lsp

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

internal class JsonRpcConnection(
    private val input: InputStream,
    private val output: OutputStream,
    private val onNotification: (method: String, params: JsonElement?) -> Unit,
) {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val inBuf = BufferedInputStream(input)
    private val outBuf = BufferedOutputStream(output)
    private val nextId = AtomicLong(1)
    private val pending = ConcurrentHashMap<Long, java.util.concurrent.CompletableFuture<JsonElement>>()

    fun startReaderThread(): Thread {
        val t =
            Thread {
                try {
                    while (true) {
                        val msg = readMessage() ?: break
                        val obj = msg as? JsonObject ?: continue
                        val idEl = obj["id"]
                        val method = (obj["method"] as? JsonPrimitive)?.contentOrNull
                        val idNum = (idEl as? JsonPrimitive)?.contentOrNull?.toLongOrNull()
                        if (idNum != null && (obj.containsKey("result") || obj.containsKey("error"))) {
                            val id = idNum
                            val fut = pending.remove(id) ?: continue
                            val err = obj["error"]
                            if (err != null && err !is JsonNull) {
                                fut.completeExceptionally(RuntimeException("LSP error: $err"))
                                continue
                            }
                            fut.complete(obj["result"] ?: JsonNull)
                            continue
                        }
                        if (method != null) {
                            val params = obj["params"]
                            onNotification(method, params)
                            // Respond to server->client requests (best-effort).
                            val idReq = (idEl as? JsonPrimitive)?.contentOrNull?.toLongOrNull()
                            if (idReq != null) {
                                writeResponse(id = idReq, result = JsonNull)
                            }
                        }
                    }
                } catch (_: Throwable) {
                    // Treat as connection closed.
                } finally {
                    for ((id, fut2) in pending) {
                        fut2.completeExceptionally(RuntimeException("LSP connection closed"))
                    }
                    pending.clear()
                }
            }
        t.isDaemon = true
        t.start()
        return t
    }

    fun request(
        method: String,
        params: JsonElement?,
    ): JsonElement {
        val id = nextId.getAndIncrement()
        val fut = java.util.concurrent.CompletableFuture<JsonElement>()
        pending[id] = fut
        writeRequest(id = id, method = method, params = params)
        return fut.get()
    }

    fun notify(
        method: String,
        params: JsonElement?,
    ) {
        writeNotification(method = method, params = params)
    }

    private fun writeRequest(
        id: Long,
        method: String,
        params: JsonElement?,
    ) {
        val obj =
            buildJsonObject {
                put("jsonrpc", JsonPrimitive("2.0"))
                put("id", JsonPrimitive(id))
                put("method", JsonPrimitive(method))
                if (params != null) put("params", params)
            }
        writeFrame(obj)
    }

    private fun writeNotification(
        method: String,
        params: JsonElement?,
    ) {
        val obj =
            buildJsonObject {
                put("jsonrpc", JsonPrimitive("2.0"))
                put("method", JsonPrimitive(method))
                if (params != null) put("params", params)
            }
        writeFrame(obj)
    }

    private fun writeResponse(
        id: Long,
        result: JsonElement,
    ) {
        val obj =
            buildJsonObject {
                put("jsonrpc", JsonPrimitive("2.0"))
                put("id", JsonPrimitive(id))
                put("result", result)
            }
        writeFrame(obj)
    }

    private fun writeFrame(obj: JsonObject) {
        val body = json.encodeToString(JsonObject.serializer(), obj).toByteArray(StandardCharsets.UTF_8)
        val header = "Content-Length: ${body.size}\r\n\r\n".toByteArray(StandardCharsets.ISO_8859_1)
        synchronized(outBuf) {
            outBuf.write(header)
            outBuf.write(body)
            outBuf.flush()
        }
    }

    private fun readMessage(): JsonElement? {
        val headers = readHeaders() ?: return null
        val len = headers["content-length"]?.trim()?.toIntOrNull() ?: return null
        if (len <= 0) return null
        val buf = ByteArray(len)
        var read = 0
        while (read < len) {
            val n = inBuf.read(buf, read, len - read)
            if (n <= 0) return null
            read += n
        }
        val text = buf.toString(StandardCharsets.UTF_8)
        return json.parseToJsonElement(text)
    }

    private fun readHeaders(): Map<String, String>? {
        val baos = ByteArrayOutputStream()
        var last4 = ""
        while (true) {
            val b = inBuf.read()
            if (b < 0) return null
            baos.write(b)
            val c = b.toChar()
            last4 = (last4 + c).takeLast(4)
            if (last4 == "\r\n\r\n") break
            if (baos.size() > 32_768) throw RuntimeException("LSP headers too large")
        }
        val raw = baos.toByteArray().toString(StandardCharsets.ISO_8859_1)
        val lines = raw.split("\r\n").filter { it.isNotBlank() }
        val out = linkedMapOf<String, String>()
        for (ln in lines) {
            val idx = ln.indexOf(":")
            if (idx <= 0) continue
            val k = ln.substring(0, idx).trim().lowercase()
            val v = ln.substring(idx + 1).trim()
            out[k] = v
        }
        return out
    }
}

private val JsonPrimitive.contentOrNull: String?
    get() = try {
        this.content
    } catch (_: Throwable) {
        null
    }

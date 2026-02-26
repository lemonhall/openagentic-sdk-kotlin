package me.lemonhall.openagentic.sdk.providers

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.lemonhall.openagentic.sdk.runtime.ProviderStreamEvent

internal data class SseEvent(
    val event: String = "",
    val data: String,
)

internal class SseEventParser {
    private val buffer = StringBuilder()
    private val dataLines = mutableListOf<String>()
    private var currentEvent = ""

    fun feed(chunk: CharSequence): List<SseEvent> {
        buffer.append(chunk)
        val out = mutableListOf<SseEvent>()
        while (true) {
            val nl = buffer.indexOf("\n")
            if (nl < 0) break
            var line = buffer.substring(0, nl)
            buffer.delete(0, nl + 1)
            if (line.endsWith("\r")) line = line.dropLast(1)
            processLine(line, out)
        }
        return out
    }

    fun endOfInput(): List<SseEvent> {
        val out = mutableListOf<SseEvent>()
        if (buffer.isNotEmpty()) {
            var line = buffer.toString()
            buffer.setLength(0)
            if (line.endsWith("\r")) line = line.dropLast(1)
            processLine(line, out)
        }
        if (dataLines.isNotEmpty()) {
            out.add(SseEvent(event = currentEvent, data = dataLines.joinToString("\n")))
            dataLines.clear()
            currentEvent = ""
        }
        return out
    }

    private fun processLine(
        line: String,
        out: MutableList<SseEvent>,
    ) {
        if (line.isEmpty()) {
            if (dataLines.isNotEmpty()) {
                out.add(SseEvent(event = currentEvent, data = dataLines.joinToString("\n")))
                dataLines.clear()
            }
            currentEvent = ""
            return
        }
        if (line.startsWith(":")) return

        val sep = line.indexOf(':')
        val field = if (sep >= 0) line.substring(0, sep) else line
        var value = if (sep >= 0) line.substring(sep + 1) else ""
        if (value.startsWith(" ")) value = value.drop(1)

        when (field) {
            "data" -> dataLines.add(value)
            "event" -> currentEvent = value
        }
    }
}

internal class OpenAIResponsesSseDecoder(
    private val json: Json,
) {
    private var lastResponse: JsonObject? = null
    private var failed: ProviderStreamEvent.Failed? = null
    private var done: Boolean = false

    fun onSseEvent(event: SseEvent): List<ProviderStreamEvent> {
        if (failed != null || done) return emptyList()

        val data = event.data.trim()
        if (data.isBlank()) return emptyList()
        if (data == "[DONE]") {
            done = true
            return emptyList()
        }

        val obj =
            try {
                json.parseToJsonElement(data).jsonObject
            } catch (_: Throwable) {
                return emptyList()
            }
        val type = obj["type"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        if (type == "response.output_text.delta") {
            val delta = obj["delta"]?.jsonPrimitive?.contentOrNull
            if (!delta.isNullOrEmpty()) return listOf(ProviderStreamEvent.TextDelta(delta))
            return emptyList()
        }
        if (type == "response.completed") {
            lastResponse = obj["response"] as? JsonObject
            return emptyList()
        }
        if (type == "error") {
            val ev = ProviderStreamEvent.Failed(message = obj.toString(), raw = obj)
            failed = ev
            done = true
            return listOf(ev)
        }
        return emptyList()
    }

    fun finish(): List<ProviderStreamEvent> {
        if (failed != null) return emptyList()

        val resp = lastResponse
        if (resp == null) {
            return listOf(ProviderStreamEvent.Failed(message = "stream ended without response.completed"))
        }
        val responseId = resp["id"]?.jsonPrimitive?.contentOrNull
        val usage = resp["usage"] as? JsonObject
        val outputItems = (resp["output"] as? JsonArray)?.mapNotNull { it as? JsonObject } ?: emptyList()
        val assistantText = parseAssistantText(outputItems)
        val toolCalls = parseToolCalls(outputItems, json = json)
        return listOf(
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
    }
}

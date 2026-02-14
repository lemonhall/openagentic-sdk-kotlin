package me.lemonhall.openagentic.sdk.sessions

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.lemonhall.openagentic.sdk.events.AssistantDelta
import me.lemonhall.openagentic.sdk.events.AssistantMessage
import me.lemonhall.openagentic.sdk.events.Event
import me.lemonhall.openagentic.sdk.events.HookEvent
import me.lemonhall.openagentic.sdk.events.UnknownEvent
import me.lemonhall.openagentic.sdk.events.Result
import me.lemonhall.openagentic.sdk.events.SystemInit
import me.lemonhall.openagentic.sdk.events.ToolOutputCompacted
import me.lemonhall.openagentic.sdk.events.ToolResult
import me.lemonhall.openagentic.sdk.events.ToolUse
import me.lemonhall.openagentic.sdk.events.UserCompaction
import me.lemonhall.openagentic.sdk.events.UserQuestion
import me.lemonhall.openagentic.sdk.events.UserMessage

object EventJson {
    val json: Json =
        Json {
            encodeDefaults = true
            ignoreUnknownKeys = true
            explicitNulls = false
        }

    fun dumps(event: Event): String {
        val element = toJsonElement(event)
        return json.encodeToString(JsonElement.serializer(), element)
    }

    fun loads(raw: String): Event {
        val obj = json.decodeFromString(JsonObject.serializer(), raw)
        return fromJsonObject(obj)
    }

    fun toJsonElement(event: Event): JsonElement {
        return when (event) {
            is SystemInit -> json.encodeToJsonElement(SystemInit.serializer(), event)
            is UserMessage -> json.encodeToJsonElement(UserMessage.serializer(), event)
            is UserCompaction -> json.encodeToJsonElement(UserCompaction.serializer(), event)
            is UserQuestion -> json.encodeToJsonElement(UserQuestion.serializer(), event)
            is AssistantDelta -> json.encodeToJsonElement(AssistantDelta.serializer(), event)
            is AssistantMessage -> json.encodeToJsonElement(AssistantMessage.serializer(), event)
            is ToolUse -> json.encodeToJsonElement(ToolUse.serializer(), event)
            is ToolResult -> json.encodeToJsonElement(ToolResult.serializer(), event)
            is ToolOutputCompacted -> json.encodeToJsonElement(ToolOutputCompacted.serializer(), event)
            is HookEvent -> json.encodeToJsonElement(HookEvent.serializer(), event)
            is Result -> json.encodeToJsonElement(Result.serializer(), event)
            is UnknownEvent -> event.raw
            else -> throw IllegalArgumentException("Unknown event class: ${event::class.qualifiedName}")
        }
    }

    fun fromJsonObject(obj: JsonObject): Event {
        val eventType = obj["type"]?.jsonPrimitive?.content ?: throw IllegalArgumentException("event missing valid 'type'")
        return when (eventType) {
            "system.init" -> json.decodeFromJsonElement(SystemInit.serializer(), obj)
            "user.message" -> json.decodeFromJsonElement(UserMessage.serializer(), obj)
            "user.compaction" -> json.decodeFromJsonElement(UserCompaction.serializer(), obj)
            "user.question" -> json.decodeFromJsonElement(UserQuestion.serializer(), obj)
            "assistant.delta" -> json.decodeFromJsonElement(AssistantDelta.serializer(), obj)
            "assistant.message" -> json.decodeFromJsonElement(AssistantMessage.serializer(), obj)
            "tool.use" -> json.decodeFromJsonElement(ToolUse.serializer(), obj)
            "tool.result" -> json.decodeFromJsonElement(ToolResult.serializer(), obj)
            "tool.output_compacted" -> json.decodeFromJsonElement(ToolOutputCompacted.serializer(), obj)
            "hook.event" -> json.decodeFromJsonElement(HookEvent.serializer(), obj)
            "result" -> json.decodeFromJsonElement(Result.serializer(), obj)
            else -> UnknownEvent(type = eventType, raw = obj)
        }
    }
}

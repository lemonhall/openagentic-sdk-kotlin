package me.lemonhall.openagentic.sdk.events

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

sealed interface Event {
    val type: String
    val ts: Double?
    val seq: Int?
}

@Serializable
data class SystemInit(
    override val type: String = "system.init",
    @SerialName("session_id")
    val sessionId: String = "",
    val cwd: String = "",
    @SerialName("sdk_version")
    val sdkVersion: String = "",
    override val ts: Double? = null,
    override val seq: Int? = null,
) : Event

@Serializable
data class UserMessage(
    override val type: String = "user.message",
    val text: String = "",
    override val ts: Double? = null,
    override val seq: Int? = null,
) : Event

@Serializable
data class UserCompaction(
    override val type: String = "user.compaction",
    val auto: Boolean = false,
    val reason: String? = null,
    override val ts: Double? = null,
    override val seq: Int? = null,
) : Event

@Serializable
data class UserQuestion(
    override val type: String = "user.question",
    @SerialName("question_id")
    val questionId: String = "",
    val prompt: String = "",
    val choices: List<String> = emptyList(),
    override val ts: Double? = null,
    override val seq: Int? = null,
) : Event

@Serializable
data class AssistantDelta(
    override val type: String = "assistant.delta",
    @SerialName("text_delta")
    val textDelta: String = "",
    override val ts: Double? = null,
    override val seq: Int? = null,
) : Event

@Serializable
data class AssistantMessage(
    override val type: String = "assistant.message",
    val text: String = "",
    @SerialName("is_summary")
    val isSummary: Boolean = false,
    override val ts: Double? = null,
    override val seq: Int? = null,
) : Event

@Serializable
data class ToolUse(
    override val type: String = "tool.use",
    @SerialName("tool_use_id")
    val toolUseId: String = "",
    val name: String = "",
    val input: JsonObject? = null,
    override val ts: Double? = null,
    override val seq: Int? = null,
) : Event

@Serializable
data class ToolResult(
    override val type: String = "tool.result",
    @SerialName("tool_use_id")
    val toolUseId: String = "",
    val output: JsonElement? = null,
    @SerialName("is_error")
    val isError: Boolean = false,
    @SerialName("error_type")
    val errorType: String? = null,
    @SerialName("error_message")
    val errorMessage: String? = null,
    override val ts: Double? = null,
    override val seq: Int? = null,
) : Event

@Serializable
data class ToolOutputCompacted(
    override val type: String = "tool.output_compacted",
    @SerialName("tool_use_id")
    val toolUseId: String = "",
    @SerialName("compacted_ts")
    val compactedTs: Double? = null,
    override val ts: Double? = null,
    override val seq: Int? = null,
) : Event

@Serializable
data class HookEvent(
    override val type: String = "hook.event",
    @SerialName("hook_point")
    val hookPoint: String = "",
    val name: String = "",
    val matched: Boolean = true,
    @SerialName("duration_ms")
    val durationMs: Double? = null,
    val action: String? = null,
    @SerialName("error_type")
    val errorType: String? = null,
    @SerialName("error_message")
    val errorMessage: String? = null,
    override val ts: Double? = null,
    override val seq: Int? = null,
) : Event

@Serializable
data class Result(
    override val type: String = "result",
    @SerialName("final_text")
    val finalText: String = "",
    @SerialName("session_id")
    val sessionId: String = "",
    @SerialName("stop_reason")
    val stopReason: String? = null,
    val usage: JsonObject? = null,
    @SerialName("response_id")
    val responseId: String? = null,
    @SerialName("provider_metadata")
    val providerMetadata: JsonObject? = null,
    val steps: Int? = null,
    override val ts: Double? = null,
    override val seq: Int? = null,
) : Event

@Serializable
data class RuntimeError(
    override val type: String = "runtime.error",
    val phase: String = "",
    @SerialName("error_type")
    val errorType: String = "",
    @SerialName("error_message")
    val errorMessage: String? = null,
    val provider: String? = null,
    @SerialName("tool_use_id")
    val toolUseId: String? = null,
    override val ts: Double? = null,
    override val seq: Int? = null,
) : Event

data class UnknownEvent(
    override val type: String,
    val raw: JsonObject,
) : Event {
    override val ts: Double?
        get() = raw["ts"]?.jsonPrimitive?.doubleOrNull

    override val seq: Int?
        get() = raw["seq"]?.jsonPrimitive?.intOrNull
}

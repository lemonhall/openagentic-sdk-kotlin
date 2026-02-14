package me.lemonhall.openagentic.sdk.permissions

import kotlin.random.Random
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.lemonhall.openagentic.sdk.events.UserQuestion
import me.lemonhall.openagentic.sdk.json.asBooleanOrNull
import me.lemonhall.openagentic.sdk.json.asIntOrNull
import me.lemonhall.openagentic.sdk.json.asStringOrNull

fun interface UserAnswerer {
    suspend fun answer(question: UserQuestion): JsonElement
}

data class ToolPermissionContext(
    val sessionId: String,
    val toolUseId: String,
)

data class ApprovalResult(
    val allowed: Boolean,
    val question: UserQuestion? = null,
    val updatedInput: JsonObject? = null,
    val denyMessage: String? = null,
)

enum class PermissionMode {
    BYPASS,
    DENY,
    PROMPT,
    DEFAULT,
}

interface PermissionGate {
    val mode: PermissionMode
    val userAnswerer: UserAnswerer?

    suspend fun approve(
        toolName: String,
        toolInput: JsonObject,
        context: ToolPermissionContext,
    ): ApprovalResult

    companion object {
        fun bypass(userAnswerer: UserAnswerer? = null): PermissionGate = SimplePermissionGate(PermissionMode.BYPASS, userAnswerer)

        fun deny(userAnswerer: UserAnswerer? = null): PermissionGate = SimplePermissionGate(PermissionMode.DENY, userAnswerer)

        fun prompt(userAnswerer: UserAnswerer? = null): PermissionGate = SimplePermissionGate(PermissionMode.PROMPT, userAnswerer)

        fun default(userAnswerer: UserAnswerer? = null): PermissionGate = SimplePermissionGate(PermissionMode.DEFAULT, userAnswerer)
    }
}

private class SimplePermissionGate(
    override val mode: PermissionMode,
    override val userAnswerer: UserAnswerer?,
) : PermissionGate {
    override suspend fun approve(
        toolName: String,
        toolInput: JsonObject,
        context: ToolPermissionContext,
    ): ApprovalResult {
        if (toolName == "AskUserQuestion") {
            return ApprovalResult(allowed = true)
        }

        when (mode) {
            PermissionMode.BYPASS -> return ApprovalResult(allowed = true)
            PermissionMode.DENY ->
                return ApprovalResult(
                    allowed = false,
                    denyMessage = "PermissionGate(mode=DENY) denied tool '$toolName'",
                )
            PermissionMode.DEFAULT -> {
                val safe = setOf("Read", "Glob", "Grep", "Skill", "SlashCommand", "AskUserQuestion")
                if (toolName in safe) {
                    if (!safeSchemaOk(toolName = toolName, toolInput = toolInput)) {
                        return ApprovalResult(
                            allowed = false,
                            denyMessage = "PermissionGate(mode=DEFAULT) schema parse failed for tool '$toolName'",
                        )
                    }
                    return ApprovalResult(allowed = true)
                }
                // fallthrough to prompt behavior
            }
            PermissionMode.PROMPT -> Unit
        }

        val qid = context.toolUseId.ifBlank { Random.nextLong().toString(16) }
        val question =
            UserQuestion(
                questionId = qid,
                prompt = "Allow tool $toolName?",
                choices = listOf("yes", "no"),
            )

        val answerer =
            userAnswerer
                ?: return ApprovalResult(
                    allowed = false,
                    question = null,
                    denyMessage = "PermissionGate(mode=$mode) requires userAnswerer, but none is configured for tool '$toolName'",
                )
        val answer = answerer.answer(question)
        val allowed =
            when (answer) {
                is JsonPrimitive -> {
                    if (answer.isString) {
                        answer.content.trim().lowercase() in setOf("y", "yes", "true", "1", "allow", "ok")
                    } else {
                        answer.asBooleanOrNull() == true || answer.asIntOrNull() == 1
                    }
                }
                else -> false
            }
        return ApprovalResult(
            allowed = allowed,
            question = question,
            denyMessage = if (allowed) null else "PermissionGate: user denied tool '$toolName'",
        )
    }

    private fun safeSchemaOk(
        toolName: String,
        toolInput: JsonObject,
    ): Boolean {
        fun nonEmptyString(keys: List<String>): Boolean {
            return keys.any { k -> toolInput[k]?.asStringOrNull()?.trim()?.isNotEmpty() == true }
        }

        return when (toolName) {
            "Read" -> nonEmptyString(listOf("file_path", "filePath"))
            "Glob" -> nonEmptyString(listOf("pattern"))
            "Grep" -> nonEmptyString(listOf("query"))
            else -> true
        }
    }
}

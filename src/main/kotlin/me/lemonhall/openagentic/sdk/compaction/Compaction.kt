package me.lemonhall.openagentic.sdk.compaction

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import me.lemonhall.openagentic.sdk.events.AssistantMessage
import me.lemonhall.openagentic.sdk.events.Event
import me.lemonhall.openagentic.sdk.events.ToolOutputCompacted
import me.lemonhall.openagentic.sdk.events.ToolResult
import me.lemonhall.openagentic.sdk.events.ToolUse
import me.lemonhall.openagentic.sdk.events.UserCompaction
import me.lemonhall.openagentic.sdk.events.UserMessage

val COMPACTION_SYSTEM_PROMPT: String =
    """
You are a helpful AI assistant tasked with summarizing conversations.

When asked to summarize, provide a detailed but concise summary of the conversation.
Focus on information that would be helpful for continuing the conversation, including:
- What was done
- What is currently being worked on
- Which files are being modified
- What needs to be done next
- Key user requests, constraints, or preferences that should persist
- Important technical decisions and why they were made

Your summary should be comprehensive enough to provide context but concise enough to be quickly understood.
""".trimIndent()

const val COMPACTION_MARKER_QUESTION: String = "What did we do so far?"

val COMPACTION_USER_INSTRUCTION: String =
    "Provide a detailed prompt for continuing our conversation above. Focus on information that would be helpful for " +
        "continuing the conversation, including what we did, what we're doing, which files we're working on, and what we're " +
        "going to do next considering new session will not have access to our conversation."

const val TOOL_OUTPUT_PLACEHOLDER: String = "[Old tool result content cleared]"

data class CompactionOptions(
    val auto: Boolean = true,
    val prune: Boolean = true,
    val contextLimit: Int = 0,
    val outputLimit: Int? = null,
    val globalOutputCap: Int = 4096,
    val reserved: Int? = null,
    val inputLimit: Int? = null,
    val protectToolOutputTokens: Int = 40_000,
    val minPruneTokens: Int = 20_000,
)

data class UsageTotals(
    val inputTokens: Int,
    val outputTokens: Int,
    val cacheReadTokens: Int,
    val cacheWriteTokens: Int,
    val totalTokens: Int,
)

fun parseUsageTotals(usage: JsonObject?): UsageTotals? {
    if (usage == null || usage.isEmpty()) return null

    fun asInt(key: String): Int {
        val v = usage[key]
        return when (v) {
            is JsonPrimitive -> v.intOrNullSafe()
            else -> 0
        }
    }

    val inputTokens = (asInt("input_tokens").takeIf { it > 0 } ?: asInt("prompt_tokens")).coerceAtLeast(0)
    val outputTokens = (asInt("output_tokens").takeIf { it > 0 } ?: asInt("completion_tokens")).coerceAtLeast(0)
    val cacheReadTokens = (asInt("cache_read_tokens").takeIf { it > 0 } ?: asInt("cached_tokens")).coerceAtLeast(0)
    val cacheWriteTokens = asInt("cache_write_tokens").coerceAtLeast(0)
    var totalTokens = asInt("total_tokens")
    if (totalTokens <= 0) totalTokens = inputTokens + outputTokens + cacheReadTokens + cacheWriteTokens
    if (totalTokens <= 0) return null

    return UsageTotals(
        inputTokens = inputTokens,
        outputTokens = outputTokens,
        cacheReadTokens = cacheReadTokens,
        cacheWriteTokens = cacheWriteTokens,
        totalTokens = totalTokens.coerceAtLeast(0),
    )
}

fun wouldOverflow(
    compaction: CompactionOptions,
    usage: JsonObject?,
): Boolean {
    val totals = parseUsageTotals(usage) ?: return false

    val contextLimit = compaction.contextLimit.coerceAtLeast(0)
    if (contextLimit <= 0) return false

    val outputCap = compaction.globalOutputCap.coerceAtLeast(0)
    val outputLimit = compaction.outputLimit
    val maxOutputTokens =
        if (outputLimit != null && outputLimit > 0) minOf(outputLimit, outputCap) else outputCap

    var reserved = compaction.reserved
    if (reserved == null || reserved <= 0) reserved = minOf(20_000, maxOutputTokens)

    val effectiveLimit =
        if (compaction.inputLimit != null && compaction.inputLimit > 0) compaction.inputLimit else contextLimit
    val usable = effectiveLimit - reserved.coerceAtLeast(0)
    if (usable <= 0) return true

    // OpenCode parity: overflow triggers at the boundary (>=).
    return totals.totalTokens >= usable
}

fun estimateTokens(text: String): Int {
    if (text.isBlank()) return 0
    return maxOf(1, text.length / 4)
}

private fun safeJsonDumps(el: JsonElement?): String {
    if (el == null || el is JsonNull) return "null"
    return el.toString()
}

private fun filterToLatestSummaryPivot(events: List<Event>): List<Event> {
    val idx = events.indexOfLast { it is AssistantMessage && it.isSummary }
    if (idx < 0) return events
    return events.drop(idx)
}

fun selectToolOutputsToPrune(
    events: List<Event>,
    compaction: CompactionOptions,
): List<String> {
    if (!compaction.prune) return emptyList()

    val events2 = filterToLatestSummaryPivot(events)

    val compactedIds = linkedSetOf<String>()
    val toolNameById = linkedMapOf<String, String>()
    for (e in events2) {
        when (e) {
            is ToolOutputCompacted -> if (e.toolUseId.isNotBlank()) compactedIds.add(e.toolUseId)
            is ToolUse -> if (e.toolUseId.isNotBlank() && e.name.isNotBlank()) toolNameById[e.toolUseId] = e.name
            else -> Unit
        }
    }

    val protect = compaction.protectToolOutputTokens.coerceAtLeast(0)
    val minPrune = compaction.minPruneTokens.coerceAtLeast(0)

    var total = 0
    var prunedTokens = 0
    val toPrune = mutableListOf<Pair<String, Int>>()
    var turns = 0

    // OpenCode: skip pruning until >=2 user turns are present.
    for (e in events2.asReversed()) {
        if (e is UserMessage || e is UserCompaction) {
            turns += 1
            continue
        }
        if (turns < 2) continue
        if (e is AssistantMessage && e.isSummary) break
        if (e !is ToolResult) continue

        val tid = e.toolUseId
        if (tid.isBlank()) continue

        // Idempotence boundary: once we hit an already-compacted tool result, stop scanning older results.
        if (compactedIds.contains(tid)) break

        val toolName = toolNameById[tid].orEmpty()
        if (toolName.equals("skill", ignoreCase = true)) continue

        val cost = estimateTokens(safeJsonDumps(e.output))
        total += cost
        if (total > protect) {
            prunedTokens += cost
            toPrune.add(tid to cost)
        }
    }

    // OpenCode: only apply if prunedTokens > minPruneTokens (strict).
    if (prunedTokens <= minPrune) return emptyList()
    return toPrune.map { it.first }
}

fun toolResultPlaceholderOutput(): JsonElement {
    return buildJsonObject {
        put("output", JsonPrimitive(TOOL_OUTPUT_PLACEHOLDER))
    }
}

private fun JsonPrimitive.intOrNullSafe(): Int {
    return this.content.toIntOrNull() ?: 0
}

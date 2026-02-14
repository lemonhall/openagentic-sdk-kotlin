package me.lemonhall.openagentic.sdk.testing

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import me.lemonhall.openagentic.sdk.events.AssistantDelta
import me.lemonhall.openagentic.sdk.events.Event
import me.lemonhall.openagentic.sdk.events.ToolResult
import me.lemonhall.openagentic.sdk.events.ToolUse

object TraceAsserts {
    fun assertNoAssistantDelta(events: List<Event>) {
        assertFalse(events.any { it is AssistantDelta }, "trace must not include assistant.delta")
    }

    fun assertSeqMonotonic(events: List<Event>) {
        val seqs = events.mapNotNull { it.seq }
        assertTrue(seqs.isNotEmpty(), "expected events to have seq values")
        for (i in 1 until seqs.size) {
            assertTrue(seqs[i] > seqs[i - 1], "seq must be strictly increasing, got $seqs")
        }
    }

    fun assertToolUseResultPairs(events: List<Event>) {
        val uses = events.filterIsInstance<ToolUse>()
        val results = events.filterIsInstance<ToolResult>()
        val byId = results.groupBy { it.toolUseId }
        for (u in uses) {
            val r = byId[u.toolUseId]?.firstOrNull()
            assertNotNull(r, "missing tool.result for tool_use_id=${u.toolUseId}")
        }
        for (r in results) {
            assertTrue(uses.any { it.toolUseId == r.toolUseId }, "orphan tool.result tool_use_id=${r.toolUseId}")
        }
    }

    fun assertSingleToolPair(events: List<Event>) {
        val uses = events.filterIsInstance<ToolUse>()
        val results = events.filterIsInstance<ToolResult>()
        assertEquals(1, uses.size, "expected exactly 1 tool.use")
        assertEquals(1, results.size, "expected exactly 1 tool.result")
        assertEquals(uses.single().toolUseId, results.single().toolUseId, "tool_use_id must match between tool.use and tool.result")
    }
}


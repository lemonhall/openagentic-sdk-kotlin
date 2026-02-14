package me.lemonhall.openagentic.sdk.testing

import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import me.lemonhall.openagentic.sdk.events.AssistantDelta
import me.lemonhall.openagentic.sdk.events.Event
import me.lemonhall.openagentic.sdk.events.SystemInit
import me.lemonhall.openagentic.sdk.events.ToolResult
import me.lemonhall.openagentic.sdk.events.ToolUse

object TraceStrictAsserts {
    fun assertStrict(events: List<Event>) {
        val sessionId = (events.firstOrNull { it is SystemInit } as? SystemInit)?.sessionId ?: "unknown"

        assertFalse(events.any { it is AssistantDelta }, "trace must not include assistant.delta (session_id=$sessionId)")

        val seqs = events.mapNotNull { it.seq }
        assertTrue(seqs.isNotEmpty(), "expected events to have seq values (session_id=$sessionId)")
        for (i in 1 until seqs.size) {
            assertTrue(seqs[i] > seqs[i - 1], "seq must be strictly increasing (session_id=$sessionId seqs=$seqs)")
        }

        val uses = events.filterIsInstance<ToolUse>()
        val results = events.filterIsInstance<ToolResult>()
        val byId = results.groupBy { it.toolUseId }
        for (u in uses) {
            val r = byId[u.toolUseId]?.firstOrNull()
            assertNotNull(
                r,
                "missing tool.result (session_id=$sessionId tool_use_id=${u.toolUseId} seq=${u.seq})",
            )
        }
        for (r in results) {
            assertTrue(
                uses.any { it.toolUseId == r.toolUseId },
                "orphan tool.result (session_id=$sessionId tool_use_id=${r.toolUseId} seq=${r.seq})",
            )
        }
    }

    fun assertNoSecrets(
        rawJsonl: String,
        sessionId: String,
        blacklist: List<String> =
            listOf(
                "authorization",
                "api_key",
                "device_token",
                "Bearer ",
                "sk-",
            ),
    ) {
        val lower = rawJsonl.lowercase()
        for (token in blacklist) {
            val needle = token.lowercase()
            assertFalse(lower.contains(needle), "trace leaked secret token '$token' (session_id=$sessionId)")
        }
    }
}


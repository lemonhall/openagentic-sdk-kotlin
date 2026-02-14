package me.lemonhall.openagentic.sdk.e2e

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.lemonhall.openagentic.sdk.events.HookEvent
import me.lemonhall.openagentic.sdk.events.AssistantDelta
import me.lemonhall.openagentic.sdk.events.SystemInit
import me.lemonhall.openagentic.sdk.events.ToolResult
import me.lemonhall.openagentic.sdk.events.ToolUse
import me.lemonhall.openagentic.sdk.sessions.EventJson
import me.lemonhall.openagentic.sdk.testing.TraceNormalizer
import me.lemonhall.openagentic.sdk.testing.TraceStrictAsserts
import okio.Path.Companion.toPath

class OfflineTraceContractTest {
    private fun tempRoot(): okio.Path {
        val rootNio = Files.createTempDirectory("openagentic-offline-e2e-trace-")
        return rootNio.toString().replace('\\', '/').toPath()
    }

    @Test
    fun offline_trace_normalizer_ignores_ts_and_duration() {
        val e1 = HookEvent(hookPoint = "pre_tool_use", name = "x", matched = true, durationMs = 1.0, ts = 111.0, seq = 1)
        val e2 = HookEvent(hookPoint = "pre_tool_use", name = "x", matched = true, durationMs = 9.0, ts = 999.0, seq = 1)

        val n1 = TraceNormalizer.normalize(EventJson.toJsonElement(e1))
        val n2 = TraceNormalizer.normalize(EventJson.toJsonElement(e2))
        assertEquals(n1, n2)
    }

    @Test
    fun offline_trace_strict_missing_tool_result_fails_with_session_and_seq() {
        val events =
            listOf(
                SystemInit(sessionId = "s", cwd = tempRoot().toString(), sdkVersion = "0", ts = 1.0, seq = 1),
                ToolUse(toolUseId = "call_1", name = "Read", input = JsonObject(emptyMap()), ts = 2.0, seq = 2),
            )

        val e =
            assertFailsWith<AssertionError> {
                TraceStrictAsserts.assertStrict(events)
            }
        val msg = e.message.orEmpty()
        assertTrue(msg.contains("session_id=s"))
        assertTrue(msg.contains("tool_use_id=call_1"))
        assertTrue(msg.contains("seq=2"))
    }

    @Test
    fun offline_trace_strict_orphan_tool_result_fails_with_session_and_seq() {
        val events =
            listOf(
                SystemInit(sessionId = "s", cwd = tempRoot().toString(), sdkVersion = "0", ts = 1.0, seq = 1),
                ToolResult(toolUseId = "call_orphan", output = JsonPrimitive("x"), isError = false, ts = 2.0, seq = 2),
            )

        val e = assertFailsWith<AssertionError> { TraceStrictAsserts.assertStrict(events) }
        val msg = e.message.orEmpty()
        assertTrue(msg.contains("session_id=s"))
        assertTrue(msg.contains("tool_use_id=call_orphan"))
        assertTrue(msg.contains("seq=2"))
    }

    @Test
    fun offline_trace_strict_assistant_delta_is_forbidden() {
        val events =
            listOf(
                SystemInit(sessionId = "s", cwd = tempRoot().toString(), sdkVersion = "0", ts = 1.0, seq = 1),
                AssistantDelta(textDelta = "x", ts = 2.0, seq = 2),
            )
        assertFailsWith<AssertionError> { TraceStrictAsserts.assertStrict(events) }
    }

    @Test
    fun offline_trace_strict_seq_must_be_increasing() {
        val events =
            listOf(
                SystemInit(sessionId = "s", cwd = tempRoot().toString(), sdkVersion = "0", ts = 1.0, seq = 2),
                ToolUse(toolUseId = "call_1", name = "Read", input = JsonObject(emptyMap()), ts = 2.0, seq = 1),
            )
        assertFailsWith<AssertionError> { TraceStrictAsserts.assertStrict(events) }
    }
}

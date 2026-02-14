package me.lemonhall.openagentic.sdk.e2e

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import me.lemonhall.openagentic.sdk.events.SystemInit
import me.lemonhall.openagentic.sdk.events.ToolResult
import me.lemonhall.openagentic.sdk.events.ToolUse
import me.lemonhall.openagentic.sdk.providers.ModelOutput
import me.lemonhall.openagentic.sdk.providers.ToolCall
import me.lemonhall.openagentic.sdk.runtime.OpenAgenticOptions
import me.lemonhall.openagentic.sdk.runtime.OpenAgenticSdk
import me.lemonhall.openagentic.sdk.sessions.EventJson
import me.lemonhall.openagentic.sdk.sessions.FileSessionStore
import me.lemonhall.openagentic.sdk.testing.ScriptedResponsesProvider
import me.lemonhall.openagentic.sdk.tools.Tool
import me.lemonhall.openagentic.sdk.tools.ToolContext
import me.lemonhall.openagentic.sdk.tools.ToolOutput
import me.lemonhall.openagentic.sdk.tools.ToolRegistry
import okio.FileSystem
import okio.Path.Companion.toPath

class OfflineCancellationAndConcurrencyTest {
    private fun tempRoot(): okio.Path {
        val rootNio = Files.createTempDirectory("openagentic-offline-e2e-cancel-")
        return rootNio.toString().replace('\\', '/').toPath()
    }

    private fun singleSessionId(root: okio.Path): String {
        val dirs = FileSystem.SYSTEM.list(root.resolve("sessions"))
        assertEquals(1, dirs.size, "expected exactly 1 session dir, got $dirs")
        return dirs.single().name
    }

    private fun readEventsJsonl(root: okio.Path, sessionId: String): String {
        val path = root.resolve("sessions").resolve(sessionId).resolve("events.jsonl")
        return FileSystem.SYSTEM.read(path) { readUtf8() }
    }

    private fun assertJsonlIntact(raw: String) {
        assertTrue(raw.isNotEmpty(), "events.jsonl must not be empty")
        assertTrue(raw.endsWith("\n"), "events.jsonl must end with newline (no partial last line)")
        for (line in raw.lineSequence()) {
            if (line.isBlank()) continue
            EventJson.loads(line)
        }
    }

    @Test
    fun offline_cancel_during_provider_call_does_not_write_partial_jsonl() =
        runTest {
            // Given
            val root = tempRoot()
            val store = FileSessionStore(fileSystem = FileSystem.SYSTEM, rootDir = root)
            val provider =
                ScriptedResponsesProvider { _, _ ->
                    delay(10_000)
                    ModelOutput(assistantText = "never", toolCalls = emptyList(), responseId = "resp_1")
                }
            val options =
                OpenAgenticOptions(
                    provider = provider,
                    model = "fake",
                    apiKey = "x",
                    fileSystem = FileSystem.SYSTEM,
                    cwd = root,
                    tools = ToolRegistry(),
                    sessionStore = store,
                    includePartialMessages = false,
                    maxSteps = 1,
                )

            // When / Then
            assertFailsWith<TimeoutCancellationException> {
                withTimeout(50) {
                    OpenAgenticSdk.query(prompt = "hi", options = options).toList()
                }
            }

            val sessionId = singleSessionId(root)
            val raw = readEventsJsonl(root, sessionId)
            assertJsonlIntact(raw)
        }

    @Test
    fun offline_cancel_during_tool_run_does_not_write_partial_jsonl() =
        runTest {
            // Given
            val root = tempRoot()
            val store = FileSessionStore(fileSystem = FileSystem.SYSTEM, rootDir = root)
            val started = kotlinx.coroutines.CompletableDeferred<Unit>()
            val slowTool =
                object : Tool {
                    override val name: String = "SlowTool"
                    override val description: String = "slow"

                    override suspend fun run(
                        input: kotlinx.serialization.json.JsonObject,
                        ctx: ToolContext,
                    ): ToolOutput {
                        started.complete(Unit)
                        delay(10_000)
                        return ToolOutput.Json(JsonNull)
                    }
                }
            val provider =
                ScriptedResponsesProvider { step, _ ->
                    if (step == 1) {
                        ModelOutput(
                            assistantText = null,
                            toolCalls = listOf(ToolCall(toolUseId = "call_1", name = "SlowTool", arguments = buildJsonObject { })),
                            responseId = "resp_1",
                        )
                    } else {
                        ModelOutput(assistantText = "done", toolCalls = emptyList(), responseId = "resp_2")
                    }
                }
            val options =
                OpenAgenticOptions(
                    provider = provider,
                    model = "fake",
                    apiKey = "x",
                    fileSystem = FileSystem.SYSTEM,
                    cwd = root,
                    tools = ToolRegistry(listOf(slowTool)),
                    sessionStore = store,
                    includePartialMessages = false,
                    maxSteps = 2,
                )

            // When
            assertFailsWith<TimeoutCancellationException> {
                withTimeout(200) {
                    OpenAgenticSdk.query(prompt = "hi", options = options).collect { ev ->
                        if (ev is ToolUse && ev.toolUseId == "call_1") started.complete(Unit)
                    }
                }
            }
            assertTrue(started.isCompleted, "expected to cancel during tool.run (after tool.use was emitted)")

            // Then
            val sessionId = singleSessionId(root)
            val raw = readEventsJsonl(root, sessionId)
            assertJsonlIntact(raw)

            val events = store.readEvents(sessionId)
            val uses = events.filterIsInstance<ToolUse>().filter { it.toolUseId == "call_1" }
            val results = events.filterIsInstance<ToolResult>().filter { it.toolUseId == "call_1" }
            assertEquals(1, uses.size, "expected tool.use to be persisted before cancellation")
            assertEquals(0, results.size, "cancellation must not be swallowed into a tool.result")
        }

    @Test
    fun offline_cancel_then_resume_can_continue_seq_monotonic() =
        runTest {
            // Given
            val root = tempRoot()
            val store = FileSessionStore(fileSystem = FileSystem.SYSTEM, rootDir = root)
            val slowTool =
                object : Tool {
                    override val name: String = "SlowTool"
                    override val description: String = "slow"

                    override suspend fun run(
                        input: kotlinx.serialization.json.JsonObject,
                        ctx: ToolContext,
                    ): ToolOutput {
                        delay(10_000)
                        return ToolOutput.Json(JsonNull)
                    }
                }
            val provider1 =
                ScriptedResponsesProvider { step, _ ->
                    if (step == 1) {
                        ModelOutput(
                            assistantText = null,
                            toolCalls = listOf(ToolCall(toolUseId = "call_1", name = "SlowTool", arguments = buildJsonObject { })),
                            responseId = "resp_1",
                        )
                    } else {
                        ModelOutput(assistantText = "done", toolCalls = emptyList(), responseId = "resp_2")
                    }
                }
            val options1 =
                OpenAgenticOptions(
                    provider = provider1,
                    model = "fake",
                    apiKey = "x",
                    fileSystem = FileSystem.SYSTEM,
                    cwd = root,
                    tools = ToolRegistry(listOf(slowTool)),
                    sessionStore = store,
                    includePartialMessages = false,
                    maxSteps = 2,
                )

            assertFailsWith<TimeoutCancellationException> {
                withTimeout(200) {
                    OpenAgenticSdk.query(prompt = "hi", options = options1).toList()
                }
            }

            val sessionId = singleSessionId(root)
            val beforeMaxSeq = store.readEvents(sessionId).mapNotNull { it.seq }.maxOrNull() ?: 0

            // When
            val provider2 =
                ScriptedResponsesProvider { _, _ ->
                    ModelOutput(assistantText = "resumed", toolCalls = emptyList(), responseId = "resp_2")
                }
            val options2 =
                OpenAgenticOptions(
                    provider = provider2,
                    model = "fake",
                    apiKey = "x",
                    fileSystem = FileSystem.SYSTEM,
                    cwd = root,
                    tools = ToolRegistry(listOf(slowTool)),
                    sessionStore = store,
                    resumeSessionId = sessionId,
                    includePartialMessages = false,
                    maxSteps = 1,
                )
            val resumedEvents = OpenAgenticSdk.query(prompt = "hi again", options = options2).toList()

            // Then
            val afterMaxSeq = store.readEvents(sessionId).mapNotNull { it.seq }.maxOrNull() ?: 0
            assertTrue(afterMaxSeq > beforeMaxSeq, "expected seq to continue after resume, before=$beforeMaxSeq after=$afterMaxSeq")
            val init = resumedEvents.firstOrNull { it is SystemInit }
            assertEquals(null, init, "resume query must not emit SystemInit again")
        }

    @Test
    fun offline_cancel_during_task_runner_does_not_write_tool_result() =
        runTest {
            // Given
            val root = tempRoot()
            val store = FileSessionStore(fileSystem = FileSystem.SYSTEM, rootDir = root)
            val provider =
                ScriptedResponsesProvider { step, _ ->
                    if (step == 1) {
                        ModelOutput(
                            assistantText = null,
                            toolCalls =
                                listOf(
                                    ToolCall(
                                        toolUseId = "call_task_1",
                                        name = "Task",
                                        arguments = buildJsonObject { put("agent", kotlinx.serialization.json.JsonPrimitive("a")); put("prompt", kotlinx.serialization.json.JsonPrimitive("p")) },
                                    ),
                                ),
                            responseId = "resp_1",
                        )
                    } else {
                        ModelOutput(assistantText = "done", toolCalls = emptyList(), responseId = "resp_2")
                    }
                }
            val options =
                OpenAgenticOptions(
                    provider = provider,
                    model = "fake",
                    apiKey = "x",
                    fileSystem = FileSystem.SYSTEM,
                    cwd = root,
                    tools = ToolRegistry(),
                    sessionStore = store,
                    taskRunner =
                        me.lemonhall.openagentic.sdk.runtime.TaskRunner { _, _, _ ->
                            delay(10_000)
                            JsonNull
                        },
                    includePartialMessages = false,
                    maxSteps = 2,
                )

            // When / Then
            assertFailsWith<TimeoutCancellationException> {
                withTimeout(200) {
                    OpenAgenticSdk.query(prompt = "hi", options = options).toList()
                }
            }

            val sessionId = singleSessionId(root)
            val events = store.readEvents(sessionId)
            val uses = events.filterIsInstance<ToolUse>().filter { it.toolUseId == "call_task_1" }
            val results = events.filterIsInstance<ToolResult>().filter { it.toolUseId == "call_task_1" }
            assertEquals(1, uses.size, "expected Task tool.use to be persisted before cancellation")
            assertEquals(0, results.size, "cancellation must not be swallowed into a Task tool.result")
        }

    @Test
    fun offline_concurrent_sessions_do_not_cross_contaminate() =
        runTest {
            // Given
            val root = tempRoot()
            val store = FileSessionStore(fileSystem = FileSystem.SYSTEM, rootDir = root)

            // When
            val n = 8
            val jobs =
                (1..n).map { i ->
                    async {
                        val provider =
                            ScriptedResponsesProvider { _, _ ->
                                ModelOutput(assistantText = "ok-$i", toolCalls = emptyList(), responseId = "resp_$i")
                            }
                        val options =
                            OpenAgenticOptions(
                                provider = provider,
                                model = "fake",
                                apiKey = "x",
                                fileSystem = FileSystem.SYSTEM,
                                cwd = root,
                                tools = ToolRegistry(),
                                sessionStore = store,
                                includePartialMessages = false,
                                maxSteps = 1,
                            )
                        OpenAgenticSdk.query(prompt = "hi-$i", options = options).toList()
                    }
                }
            jobs.forEach { it.await() }

            // Then
            val sessionDirs = FileSystem.SYSTEM.list(root.resolve("sessions"))
            assertEquals(n, sessionDirs.size, "expected $n session dirs, got $sessionDirs")
            for (dir in sessionDirs) {
                val sessionId = dir.name
                val events = store.readEvents(sessionId)
                val init = events.firstOrNull { it is SystemInit } as? SystemInit
                assertEquals(sessionId, init?.sessionId, "SystemInit.sessionId must match session dir name")
                val raw = readEventsJsonl(root, sessionId)
                assertJsonlIntact(raw)
            }
        }
}

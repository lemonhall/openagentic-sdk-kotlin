package me.lemonhall.openagentic.sdk.e2e

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.lemonhall.openagentic.sdk.events.Event
import me.lemonhall.openagentic.sdk.events.SystemInit
import me.lemonhall.openagentic.sdk.events.ToolResult
import me.lemonhall.openagentic.sdk.events.ToolUse
import me.lemonhall.openagentic.sdk.events.UserMessage
import me.lemonhall.openagentic.sdk.hooks.HookDecision
import me.lemonhall.openagentic.sdk.hooks.HookEngine
import me.lemonhall.openagentic.sdk.hooks.HookMatcher
import me.lemonhall.openagentic.sdk.permissions.PermissionGate
import me.lemonhall.openagentic.sdk.providers.ModelOutput
import me.lemonhall.openagentic.sdk.providers.ToolCall
import me.lemonhall.openagentic.sdk.runtime.OpenAgenticOptions
import me.lemonhall.openagentic.sdk.runtime.OpenAgenticSdk
import me.lemonhall.openagentic.sdk.sessions.EventJson
import me.lemonhall.openagentic.sdk.sessions.FileSessionStore
import me.lemonhall.openagentic.sdk.testing.ScriptedResponsesProvider
import me.lemonhall.openagentic.sdk.testing.TraceAsserts
import me.lemonhall.openagentic.sdk.tools.ReadTool
import me.lemonhall.openagentic.sdk.tools.Tool
import me.lemonhall.openagentic.sdk.tools.ToolContext
import me.lemonhall.openagentic.sdk.tools.ToolInput
import me.lemonhall.openagentic.sdk.tools.ToolOutput
import me.lemonhall.openagentic.sdk.tools.ToolRegistry
import okio.FileSystem
import okio.Path.Companion.toPath
import okio.buffer

class OfflineHardInvariantsTest {
    private fun tempRoot(): okio.Path {
        val rootNio = Files.createTempDirectory("openagentic-offline-e2e-")
        return rootNio.toString().replace('\\', '/').toPath()
    }

    private fun eventsJsonlPath(
        root: okio.Path,
        sessionId: String,
    ): okio.Path {
        return root.resolve("sessions").resolve(sessionId).resolve("events.jsonl")
    }

    private fun extractSessionId(events: List<Event>): String {
        val init = events.firstOrNull { it is SystemInit } as? SystemInit
        assertNotNull(init, "expected SystemInit as first event")
        return init.sessionId
    }

    @Test
    fun offline_events_jsonl_roundtrip_unicode_and_newlines() {
        // Given
        val root = tempRoot()
        val store = FileSessionStore(fileSystem = FileSystem.SYSTEM, rootDir = root)
        val sessionId = store.createSession()
        val text = "你好，柠檬叔\nline2\t✓"

        // When
        store.appendEvent(sessionId, UserMessage(text = text))
        val events = store.readEvents(sessionId)

        // Then
        assertEquals(1, events.size)
        assertEquals("user.message", events.single().type)
        assertEquals(text, (events.single() as UserMessage).text)
        val raw = FileSystem.SYSTEM.read(eventsJsonlPath(root, sessionId)) { readUtf8() }
        val lines = raw.lineSequence().filter { it.isNotBlank() }.toList()
        assertEquals(1, lines.size)
        EventJson.loads(lines.single())
    }

    @Test
    fun offline_events_seq_monotonic_within_session() {
        // Given
        val root = tempRoot()
        val store = FileSessionStore(fileSystem = FileSystem.SYSTEM, rootDir = root)
        val sessionId = store.createSession()

        // When
        val e1 = store.appendEvent(sessionId, UserMessage(text = "a"))
        val e2 = store.appendEvent(sessionId, UserMessage(text = "b"))
        val e3 = store.appendEvent(sessionId, UserMessage(text = "c"))

        // Then
        assertEquals(listOf(1, 2, 3), listOf(e1.seq, e2.seq, e3.seq))
    }

    @Test
    fun offline_events_seq_monotonic_across_store_reinstantiate() {
        // Given
        val root = tempRoot()
        val store1 = FileSessionStore(fileSystem = FileSystem.SYSTEM, rootDir = root)
        val sessionId = store1.createSession()
        store1.appendEvent(sessionId, UserMessage(text = "a"))
        store1.appendEvent(sessionId, UserMessage(text = "b"))

        // When
        val store2 = FileSessionStore(fileSystem = FileSystem.SYSTEM, rootDir = root)
        val e3 = store2.appendEvent(sessionId, UserMessage(text = "c"))

        // Then
        assertEquals(3, e3.seq)
    }

    @Test
    fun offline_session_truncated_last_line_is_ignored_by_readEvents() {
        // Given
        val root = tempRoot()
        val store = FileSessionStore(fileSystem = FileSystem.SYSTEM, rootDir = root)
        val sessionId = store.createSession()
        store.appendEvent(sessionId, UserMessage(text = "ok"))
        val path = eventsJsonlPath(root, sessionId)
        FileSystem.SYSTEM.appendingSink(path, mustExist = true).buffer().use { sink ->
            sink.writeUtf8("{\"type\":\"user.message\"") // truncated JSON, no newline
        }

        // When
        val events = store.readEvents(sessionId)

        // Then
        assertEquals(1, events.size)
        assertEquals("user.message", events.single().type)
    }

    @Test
    fun offline_session_truncated_last_line_does_not_break_inferNextSeq() {
        // Given
        val root = tempRoot()
        val store1 = FileSessionStore(fileSystem = FileSystem.SYSTEM, rootDir = root)
        val sessionId = store1.createSession()
        store1.appendEvent(sessionId, UserMessage(text = "ok"))
        val path = eventsJsonlPath(root, sessionId)
        FileSystem.SYSTEM.appendingSink(path, mustExist = true).buffer().use { sink ->
            sink.writeUtf8("{\"type\":\"user.message\"") // truncated JSON
        }

        // When
        val store2 = FileSessionStore(fileSystem = FileSystem.SYSTEM, rootDir = root)
        val e2 = store2.appendEvent(sessionId, UserMessage(text = "next"))

        // Then
        assertEquals(2, e2.seq)
        val events = store2.readEvents(sessionId)
        assertEquals(listOf(1, 2), events.mapNotNull { it.seq })
    }

    @Test
    fun offline_session_invalid_mid_file_line_fails_fast() {
        // Given
        val root = tempRoot()
        val store = FileSessionStore(fileSystem = FileSystem.SYSTEM, rootDir = root)
        val sessionId = store.createSession()
        store.appendEvent(sessionId, UserMessage(text = "a"))
        val path = eventsJsonlPath(root, sessionId)
        FileSystem.SYSTEM.appendingSink(path, mustExist = true).buffer().use { sink ->
            sink.writeUtf8("\nnot-json\n")
        }
        store.appendEvent(sessionId, UserMessage(text = "b"))

        // When/Then
        assertFailsWith<IllegalStateException> { store.readEvents(sessionId) }
    }

    @Test
    fun offline_hook_pre_tool_use_can_mutate_tool_input() =
        runTest {
            // Given
            val root = tempRoot()
            FileSystem.SYSTEM.write(root.resolve("a.txt")) { writeUtf8("A") }
            FileSystem.SYSTEM.write(root.resolve("b.txt")) { writeUtf8("B") }
            val store = FileSessionStore(fileSystem = FileSystem.SYSTEM, rootDir = root)

            val hooks =
                HookEngine(
                    preToolUse =
                        listOf(
                            HookMatcher(
                                name = "rewrite-path",
                                toolNamePattern = "Read",
                                hook = { payload ->
                                    val current = payload["tool_input"] as JsonObject
                                    val fp = current["file_path"]?.jsonPrimitive?.content ?: ""
                                    assertEquals("a.txt", fp)
                                    HookDecision(
                                        overrideToolInput =
                                            buildJsonObject {
                                                put("file_path", JsonPrimitive("b.txt"))
                                            },
                                        action = "rewrite_file_path",
                                    )
                                },
                            ),
                        ),
                )

            val provider =
                ScriptedResponsesProvider { step, request ->
                    if (step == 1) {
                        assertNull(request.previousResponseId)
                        ModelOutput(
                            assistantText = null,
                            toolCalls =
                                listOf(
                                    ToolCall(
                                        toolUseId = "call_1",
                                        name = "Read",
                                        arguments = buildJsonObject { put("file_path", JsonPrimitive("a.txt")) },
                                    ),
                                ),
                            responseId = "resp_1",
                        )
                    } else {
                        val toolItem = request.input.first { it["type"]?.jsonPrimitive?.content == "function_call_output" }
                        val output = toolItem["output"]?.jsonPrimitive?.content.orEmpty()
                        assertTrue(output.contains("B"), "expected tool output to come from b.txt, got: $output")
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
                    tools = ToolRegistry(listOf(ReadTool())),
                    sessionStore = store,
                    hookEngine = hooks,
                    includePartialMessages = false,
                    maxSteps = 3,
                )

            // When
            val events = OpenAgenticSdk.query(prompt = "read file", options = options).toList()

            // Then
            val use = events.first { it is ToolUse } as ToolUse
            assertEquals("b.txt", use.input?.get("file_path")?.jsonPrimitive?.content)
            TraceAsserts.assertSingleToolPair(events)
            TraceAsserts.assertNoAssistantDelta(events)
        }

    @Test
    fun offline_hook_order_is_stable() =
        runTest {
            // Given
            val root = tempRoot()
            FileSystem.SYSTEM.write(root.resolve("a.txt")) { writeUtf8("A") }
            FileSystem.SYSTEM.write(root.resolve("b.txt")) { writeUtf8("B") }
            FileSystem.SYSTEM.write(root.resolve("c.txt")) { writeUtf8("C") }
            val store = FileSessionStore(fileSystem = FileSystem.SYSTEM, rootDir = root)

            val seen = mutableListOf<String>()
            val hooks =
                HookEngine(
                    preToolUse =
                        listOf(
                            HookMatcher(
                                name = "first",
                                toolNamePattern = "Read",
                                hook = { payload ->
                                    val fp = (payload["tool_input"] as JsonObject)["file_path"]?.jsonPrimitive?.content.orEmpty()
                                    seen.add("first:$fp")
                                    HookDecision(overrideToolInput = buildJsonObject { put("file_path", JsonPrimitive("b.txt")) })
                                },
                            ),
                            HookMatcher(
                                name = "second",
                                toolNamePattern = "Read",
                                hook = { payload ->
                                    val fp = (payload["tool_input"] as JsonObject)["file_path"]?.jsonPrimitive?.content.orEmpty()
                                    seen.add("second:$fp")
                                    HookDecision(overrideToolInput = buildJsonObject { put("file_path", JsonPrimitive("c.txt")) })
                                },
                            ),
                        ),
                )

            val provider =
                ScriptedResponsesProvider { step, _ ->
                    if (step == 1) {
                        ModelOutput(
                            assistantText = null,
                            toolCalls = listOf(ToolCall(toolUseId = "call_1", name = "Read", arguments = buildJsonObject { put("file_path", JsonPrimitive("a.txt")) })),
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
                    tools = ToolRegistry(listOf(ReadTool())),
                    sessionStore = store,
                    hookEngine = hooks,
                    includePartialMessages = false,
                    maxSteps = 3,
                )

            // When
            val events = OpenAgenticSdk.query(prompt = "read file", options = options).toList()

            // Then
            assertEquals(listOf("first:a.txt", "second:b.txt"), seen)
            val use = events.first { it is ToolUse } as ToolUse
            assertEquals("c.txt", use.input?.get("file_path")?.jsonPrimitive?.content)
        }

    @Test
    fun offline_hook_exception_is_isolated_and_recorded() =
        runTest {
            // Given
            val root = tempRoot()
            FileSystem.SYSTEM.write(root.resolve("a.txt")) { writeUtf8("A") }
            FileSystem.SYSTEM.write(root.resolve("b.txt")) { writeUtf8("B") }
            val store = FileSessionStore(fileSystem = FileSystem.SYSTEM, rootDir = root)

            val hooks =
                HookEngine(
                    preToolUse =
                        listOf(
                            HookMatcher(
                                name = "boom",
                                toolNamePattern = "Read",
                                hook = { _ -> error("boom") },
                            ),
                            HookMatcher(
                                name = "rewrite-path",
                                toolNamePattern = "Read",
                                hook = { _ -> HookDecision(overrideToolInput = buildJsonObject { put("file_path", JsonPrimitive("b.txt")) }) },
                            ),
                        ),
                )

            val provider =
                ScriptedResponsesProvider { step, _ ->
                    if (step == 1) {
                        ModelOutput(
                            assistantText = null,
                            toolCalls = listOf(ToolCall(toolUseId = "call_1", name = "Read", arguments = buildJsonObject { put("file_path", JsonPrimitive("a.txt")) })),
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
                    tools = ToolRegistry(listOf(ReadTool())),
                    sessionStore = store,
                    hookEngine = hooks,
                    includePartialMessages = false,
                    maxSteps = 3,
                )

            // When
            val events = OpenAgenticSdk.query(prompt = "read file", options = options).toList()

            // Then
            val hookEvents = events.filter { it.type == "hook.event" }
            assertTrue(hookEvents.isNotEmpty())
            val boomEvent = hookEvents.first { EventJson.dumps(it).contains("\"name\":\"boom\"") }
            val boomRaw = EventJson.dumps(boomEvent)
            assertTrue(boomRaw.contains("error"), "expected hook event to record an error: $boomRaw")
            val use = events.first { it is ToolUse } as ToolUse
            assertEquals("b.txt", use.input?.get("file_path")?.jsonPrimitive?.content)
        }

    @Test
    fun offline_permission_prompt_without_answerer_denies_without_question() =
        runTest {
            // Given
            val root = tempRoot()
            val store = FileSessionStore(fileSystem = FileSystem.SYSTEM, rootDir = root)
            val provider =
                ScriptedResponsesProvider { step, _ ->
                    if (step == 1) {
                        ModelOutput(assistantText = null, toolCalls = listOf(ToolCall(toolUseId = "call_1", name = "Read", arguments = buildJsonObject { })), responseId = "resp_1")
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
                    tools = ToolRegistry(listOf(ReadTool())),
                    permissionGate = PermissionGate.prompt(userAnswerer = null),
                    sessionStore = store,
                    includePartialMessages = false,
                    maxSteps = 2,
                )

            // When
            val events = OpenAgenticSdk.query(prompt = "try", options = options).toList()

            // Then
            assertFalse(events.any { it.type == "user.question" }, "must not emit user.question when no userAnswerer configured")
            val denied = events.first { it is ToolResult } as ToolResult
            assertTrue(denied.isError)
            assertEquals("PermissionDenied", denied.errorType)
            assertTrue(denied.errorMessage.orEmpty().contains("userAnswerer"), "expected auditable deny reason, got: ${denied.errorMessage}")
        }

    @Test
    fun offline_permission_deny_records_reason() =
        runTest {
            // Given
            val root = tempRoot()
            val store = FileSessionStore(fileSystem = FileSystem.SYSTEM, rootDir = root)
            val provider =
                ScriptedResponsesProvider { step, _ ->
                    if (step == 1) {
                        ModelOutput(assistantText = null, toolCalls = listOf(ToolCall(toolUseId = "call_1", name = "Read", arguments = buildJsonObject { })), responseId = "resp_1")
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
                    tools = ToolRegistry(listOf(ReadTool())),
                    permissionGate = PermissionGate.deny(userAnswerer = null),
                    sessionStore = store,
                    includePartialMessages = false,
                    maxSteps = 2,
                )

            // When
            val events = OpenAgenticSdk.query(prompt = "try", options = options).toList()

            // Then
            val denied = events.first { it is ToolResult } as ToolResult
            assertEquals("PermissionDenied", denied.errorType)
            assertTrue(denied.errorMessage.orEmpty().contains("DENY"), "expected auditable deny reason, got: ${denied.errorMessage}")
        }

    @Test
    fun offline_permission_default_allows_safe_tool_without_prompt() =
        runTest {
            // Given
            val root = tempRoot()
            FileSystem.SYSTEM.write(root.resolve("a.txt")) { writeUtf8("hello") }
            val store = FileSessionStore(fileSystem = FileSystem.SYSTEM, rootDir = root)
            val provider =
                ScriptedResponsesProvider { step, _ ->
                    if (step == 1) {
                        ModelOutput(
                            assistantText = null,
                            toolCalls = listOf(ToolCall(toolUseId = "call_1", name = "Read", arguments = buildJsonObject { put("file_path", JsonPrimitive("a.txt")) })),
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
                    tools = ToolRegistry(listOf(ReadTool())),
                    permissionGate = PermissionGate.default(userAnswerer = null),
                    sessionStore = store,
                    includePartialMessages = false,
                    maxSteps = 3,
                )

            // When
            val events = OpenAgenticSdk.query(prompt = "try", options = options).toList()

            // Then
            assertFalse(events.any { it.type == "user.question" }, "safe tools should not prompt in DEFAULT mode")
            val tr = events.first { it is ToolResult } as ToolResult
            assertFalse(tr.isError)
        }

    @Test
    fun offline_permission_default_denies_unsafe_tool_without_answerer() =
        runTest {
            // Given
            val root = tempRoot()
            val store = FileSessionStore(fileSystem = FileSystem.SYSTEM, rootDir = root)

            val unsafeTool =
                object : Tool {
                    override val name: String = "Write"
                    override val description: String = "unsafe"

                    override suspend fun run(input: ToolInput, ctx: ToolContext): ToolOutput {
                        return ToolOutput.Json(buildJsonObject { put("ok", JsonPrimitive(true)) })
                    }
                }

            val provider =
                ScriptedResponsesProvider { step, _ ->
                    if (step == 1) {
                        ModelOutput(assistantText = null, toolCalls = listOf(ToolCall(toolUseId = "call_1", name = "Write", arguments = buildJsonObject { })), responseId = "resp_1")
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
                    tools = ToolRegistry(listOf(unsafeTool)),
                    permissionGate = PermissionGate.default(userAnswerer = null),
                    sessionStore = store,
                    includePartialMessages = false,
                    maxSteps = 2,
                )

            // When
            val events = OpenAgenticSdk.query(prompt = "try", options = options).toList()

            // Then
            assertFalse(events.any { it.type == "user.question" })
            val denied = events.first { it is ToolResult } as ToolResult
            assertTrue(denied.isError)
            assertEquals("PermissionDenied", denied.errorType)
            assertTrue(denied.errorMessage.orEmpty().contains("userAnswerer"))
        }

    @Test
    fun offline_query_persists_no_assistant_delta_to_events_jsonl() =
        runTest {
            // Given
            val root = tempRoot()
            FileSystem.SYSTEM.write(root.resolve("a.txt")) { writeUtf8("hello") }
            val store = FileSessionStore(fileSystem = FileSystem.SYSTEM, rootDir = root)
            val provider =
                ScriptedResponsesProvider { step, _ ->
                    if (step == 1) {
                        ModelOutput(
                            assistantText = null,
                            toolCalls = listOf(ToolCall(toolUseId = "call_1", name = "Read", arguments = buildJsonObject { put("file_path", JsonPrimitive("a.txt")) })),
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
                    tools = ToolRegistry(listOf(ReadTool())),
                    sessionStore = store,
                    includePartialMessages = false,
                    maxSteps = 3,
                )

            // When
            val events = OpenAgenticSdk.query(prompt = "try", options = options).toList()
            val sessionId = extractSessionId(events)

            // Then
            TraceAsserts.assertNoAssistantDelta(events)
            val raw = FileSystem.SYSTEM.read(eventsJsonlPath(root, sessionId)) { readUtf8() }
            assertFalse(raw.contains("assistant.delta"))
        }

    @Test
    fun offline_tool_use_and_result_pair_by_tool_use_id() =
        runTest {
            // Given
            val root = tempRoot()
            FileSystem.SYSTEM.write(root.resolve("a.txt")) { writeUtf8("hello") }
            val store = FileSessionStore(fileSystem = FileSystem.SYSTEM, rootDir = root)
            val provider =
                ScriptedResponsesProvider { step, _ ->
                    if (step == 1) {
                        ModelOutput(
                            assistantText = null,
                            toolCalls = listOf(ToolCall(toolUseId = "call_1", name = "Read", arguments = buildJsonObject { put("file_path", JsonPrimitive("a.txt")) })),
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
                    tools = ToolRegistry(listOf(ReadTool())),
                    sessionStore = store,
                    includePartialMessages = false,
                    maxSteps = 3,
                )

            // When
            val events = OpenAgenticSdk.query(prompt = "try", options = options).toList()

            // Then
            TraceAsserts.assertSingleToolPair(events)
            TraceAsserts.assertToolUseResultPairs(events)
            TraceAsserts.assertSeqMonotonic(events)
        }
}

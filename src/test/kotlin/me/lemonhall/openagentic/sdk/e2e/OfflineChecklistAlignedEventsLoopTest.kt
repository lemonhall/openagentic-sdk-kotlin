package me.lemonhall.openagentic.sdk.e2e

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.lemonhall.openagentic.sdk.events.Result
import me.lemonhall.openagentic.sdk.events.ToolResult
import me.lemonhall.openagentic.sdk.events.ToolUse
import me.lemonhall.openagentic.sdk.permissions.PermissionGate
import me.lemonhall.openagentic.sdk.providers.ModelOutput
import me.lemonhall.openagentic.sdk.providers.ProviderProtocol
import me.lemonhall.openagentic.sdk.providers.ProviderTimeoutException
import me.lemonhall.openagentic.sdk.providers.ResponsesProvider
import me.lemonhall.openagentic.sdk.providers.ResponsesRequest
import me.lemonhall.openagentic.sdk.providers.ToolCall
import me.lemonhall.openagentic.sdk.runtime.EventsJsonlExcludesDeltasTest
import me.lemonhall.openagentic.sdk.runtime.OpenAgenticOptions
import me.lemonhall.openagentic.sdk.runtime.OpenAgenticSdk
import me.lemonhall.openagentic.sdk.runtime.RuntimeToolLoopTest
import me.lemonhall.openagentic.sdk.runtime.ToolOutputPruningPlaceholderTest
import me.lemonhall.openagentic.sdk.sessions.EventJson
import me.lemonhall.openagentic.sdk.sessions.FileSessionStore
import me.lemonhall.openagentic.sdk.testing.ScriptedResponsesProvider
import me.lemonhall.openagentic.sdk.testing.TraceStrictAsserts
import me.lemonhall.openagentic.sdk.tools.ReadTool
import me.lemonhall.openagentic.sdk.tools.Tool
import me.lemonhall.openagentic.sdk.tools.ToolContext
import me.lemonhall.openagentic.sdk.tools.ToolInput
import me.lemonhall.openagentic.sdk.tools.ToolOutput
import me.lemonhall.openagentic.sdk.tools.ToolRegistry
import okio.FileSystem
import okio.Path.Companion.toPath

class OfflineChecklistAlignedEventsLoopTest {
    private fun tempRoot(prefix: String = "openagentic-offline-e2e-v6-"): okio.Path {
        val rootNio = Files.createTempDirectory(prefix)
        return rootNio.toString().replace('\\', '/').toPath()
    }

    @Test
    fun offline_events_jsonl_roundtrip_unicode() {
        OfflineHardInvariantsTest().offline_events_jsonl_roundtrip_unicode_and_newlines()
    }

    @Test
    fun offline_events_no_delta_persistence() {
        EventsJsonlExcludesDeltasTest().assistantDeltasAreNotPersisted()
    }

    @Test
    fun offline_events_call_id_bijection() =
        runTest {
            OfflineHardInvariantsTest().offline_tool_use_and_result_pair_by_tool_use_id()
        }

    @Test
    fun offline_events_strict_required_fields() =
        runTest {
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
            val events = OpenAgenticSdk.query(prompt = "try", options = options).toList()
            TraceStrictAsserts.assertStrict(events)
        }

    @Test
    fun offline_events_redaction_no_secrets() =
        runTest {
            OfflineSecretsTest().offline_trace_does_not_contain_secrets_blacklist()
        }

    @Test
    fun offline_events_unknown_fields_forward_compat() {
        val raw =
            buildJsonObject {
                put("type", JsonPrimitive("user.message"))
                put("seq", JsonPrimitive(1))
                put("ts", JsonPrimitive(1.0))
                put("text", JsonPrimitive("hi"))
                put("new_field", JsonPrimitive("future"))
            }
        val line = EventJson.json.encodeToString(JsonObject.serializer(), raw)
        val ev = EventJson.loads(line)
        assertEquals("user.message", ev.type)
    }

    @Test
    fun offline_events_seq_monotonic() {
        OfflineHardInvariantsTest().offline_events_seq_monotonic_within_session()
    }

    @Test
    fun offline_events_dedup_on_retry() =
        runTest {
            val root = tempRoot()
            val store = FileSessionStore(fileSystem = FileSystem.SYSTEM, rootDir = root)

            class FirstProvider : ResponsesProvider {
                override val name: String = "first"

                override suspend fun complete(request: ResponsesRequest): ModelOutput {
                    return ModelOutput(assistantText = "one", toolCalls = emptyList(), responseId = "resp_1")
                }
            }

            val r1 =
                OpenAgenticSdk.run(
                    prompt = "one",
                    options =
                        OpenAgenticOptions(
                            provider = FirstProvider(),
                            model = "m",
                            apiKey = "x",
                            fileSystem = FileSystem.SYSTEM,
                            cwd = root,
                            tools = ToolRegistry(),
                            sessionStore = store,
                            includePartialMessages = false,
                            maxSteps = 1,
                        ),
                )

            class RetryProvider : ResponsesProvider {
                override val name: String = "retry"
                var calls = 0

                override suspend fun complete(request: ResponsesRequest): ModelOutput {
                    calls++
                    if (calls == 1) {
                        assertNotNull(request.previousResponseId)
                        throw RuntimeException("previous_response_id invalid")
                    }
                    assertEquals(null, request.previousResponseId)
                    return ModelOutput(assistantText = "two", toolCalls = emptyList(), responseId = "resp_2")
                }
            }

            val p2 = RetryProvider()
            val events =
                OpenAgenticSdk.query(
                    prompt = "two",
                    options =
                        OpenAgenticOptions(
                            provider = p2,
                            model = "m",
                            apiKey = "x",
                            fileSystem = FileSystem.SYSTEM,
                            cwd = root,
                            tools = ToolRegistry(),
                            sessionStore = store,
                            resumeSessionId = r1.sessionId,
                            includePartialMessages = false,
                            maxSteps = 1,
                        ),
                ).toList()

            assertEquals(2, p2.calls)
            val userTwos = events.filter { it.type == "user.message" && (it as? me.lemonhall.openagentic.sdk.events.UserMessage)?.text == "two" }
            assertEquals(1, userTwos.size, "expected exactly one user.message for prompt 'two'")
        }

    @Test
    fun offline_loop_zero_tool_calls() =
        runTest {
            val root = tempRoot()
            val store = FileSessionStore(fileSystem = FileSystem.SYSTEM, rootDir = root)
            val provider =
                ScriptedResponsesProvider { _, _ ->
                    ModelOutput(assistantText = "ok", toolCalls = emptyList(), responseId = "resp_1")
                }
            val options =
                OpenAgenticOptions(
                    provider = provider,
                    model = "m",
                    apiKey = "x",
                    fileSystem = FileSystem.SYSTEM,
                    cwd = root,
                    tools = ToolRegistry(),
                    sessionStore = store,
                    includePartialMessages = false,
                    maxSteps = 1,
                )
            val events = OpenAgenticSdk.query(prompt = "hi", options = options).toList()
            val result = events.last { it is Result } as Result
            assertEquals("end", result.stopReason)
        }

    @Test
    fun offline_loop_single_tool_call_success() {
        RuntimeToolLoopTest().runtimeRunsToolAndReturnsResult()
    }

    @Test
    fun offline_loop_multi_tool_calls_serial() =
        runTest {
            val root = tempRoot()
            FileSystem.SYSTEM.write(root.resolve("a.txt")) { writeUtf8("hello") }
            val store = FileSessionStore(fileSystem = FileSystem.SYSTEM, rootDir = root)
            val provider =
                ScriptedResponsesProvider { step, _ ->
                    if (step == 1) {
                        ModelOutput(
                            assistantText = null,
                            toolCalls =
                                listOf(
                                    ToolCall(toolUseId = "call_1", name = "Read", arguments = buildJsonObject { put("file_path", JsonPrimitive("a.txt")) }),
                                    ToolCall(toolUseId = "call_2", name = "Read", arguments = buildJsonObject { put("file_path", JsonPrimitive("a.txt")) }),
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
                    model = "m",
                    apiKey = "x",
                    fileSystem = FileSystem.SYSTEM,
                    cwd = root,
                    tools = ToolRegistry(listOf(ReadTool())),
                    sessionStore = store,
                    includePartialMessages = false,
                    maxSteps = 3,
                )
            val events = OpenAgenticSdk.query(prompt = "go", options = options).toList()
            val uses = events.filterIsInstance<ToolUse>()
            assertEquals(listOf("call_1", "call_2"), uses.map { it.toolUseId })
        }

    @Test
    fun offline_loop_tool_raises_exception() =
        runTest {
            val root = tempRoot()
            val store = FileSessionStore(fileSystem = FileSystem.SYSTEM, rootDir = root)
            val explodingTool =
                object : Tool {
                    override val name: String = "Boom"
                    override val description: String = "boom"

                    override suspend fun run(input: ToolInput, ctx: ToolContext): ToolOutput {
                        throw IllegalStateException("boom")
                    }
                }
            val provider =
                ScriptedResponsesProvider { step, _ ->
                    if (step == 1) {
                        ModelOutput(assistantText = null, toolCalls = listOf(ToolCall(toolUseId = "call_1", name = "Boom", arguments = buildJsonObject { })), responseId = "resp_1")
                    } else {
                        ModelOutput(assistantText = "done", toolCalls = emptyList(), responseId = "resp_2")
                    }
                }
            val options =
                OpenAgenticOptions(
                    provider = provider,
                    model = "m",
                    apiKey = "x",
                    fileSystem = FileSystem.SYSTEM,
                    cwd = root,
                    tools = ToolRegistry(listOf(explodingTool)),
                    sessionStore = store,
                    includePartialMessages = false,
                    maxSteps = 3,
                )
            val events = OpenAgenticSdk.query(prompt = "go", options = options).toList()
            val tr = events.filterIsInstance<ToolResult>().firstOrNull { it.toolUseId == "call_1" }
            assertNotNull(tr)
            assertTrue(tr.isError)
            assertEquals("IllegalStateException", tr.errorType)
        }

    @Test
    fun offline_loop_tool_returns_non_json() =
        runTest {
            val root = tempRoot()
            val store = FileSessionStore(fileSystem = FileSystem.SYSTEM, rootDir = root)
            val plainTool =
                object : Tool {
                    override val name: String = "Plain"
                    override val description: String = "plain"

                    override suspend fun run(input: ToolInput, ctx: ToolContext): ToolOutput {
                        return ToolOutput.Json(JsonPrimitive("plain-string"))
                    }
                }

            class AssertingProvider : ResponsesProvider {
                override val name: String = "assert-non-json"
                var calls = 0

                override suspend fun complete(request: ResponsesRequest): ModelOutput {
                    calls++
                    return if (calls == 1) {
                        ModelOutput(
                            assistantText = null,
                            toolCalls = listOf(ToolCall(toolUseId = "call_1", name = "Plain", arguments = buildJsonObject { })),
                            responseId = "resp_1",
                        )
                    } else {
                        val toolItem = request.input.first { it["type"] == JsonPrimitive("function_call_output") }
                        val output = toolItem["output"] as? JsonPrimitive
                        assertTrue(output?.content?.contains("plain-string") == true)
                        ModelOutput(assistantText = "done", toolCalls = emptyList(), responseId = "resp_2")
                    }
                }
            }

            val p = AssertingProvider()
            val options =
                OpenAgenticOptions(
                    provider = p,
                    model = "m",
                    apiKey = "x",
                    fileSystem = FileSystem.SYSTEM,
                    cwd = root,
                    tools = ToolRegistry(listOf(plainTool)),
                    sessionStore = store,
                    includePartialMessages = false,
                    maxSteps = 3,
                )
            OpenAgenticSdk.query(prompt = "go", options = options).toList()
            assertEquals(2, p.calls)
        }

    @Test
    fun offline_loop_max_tool_calls_fuse() =
        runTest {
            val root = tempRoot()
            val store = FileSessionStore(fileSystem = FileSystem.SYSTEM, rootDir = root)
            val noopTool =
                object : Tool {
                    override val name: String = "Noop"
                    override val description: String = "noop"

                    override suspend fun run(input: ToolInput, ctx: ToolContext): ToolOutput {
                        return ToolOutput.Json(JsonPrimitive("ok"))
                    }
                }
            val provider =
                ScriptedResponsesProvider { _, _ ->
                    ModelOutput(assistantText = null, toolCalls = listOf(ToolCall(toolUseId = "call_1", name = "Noop", arguments = buildJsonObject { })), responseId = null)
                }
            val options =
                OpenAgenticOptions(
                    provider = provider,
                    providerProtocolOverride = ProviderProtocol.RESPONSES,
                    model = "m",
                    apiKey = "x",
                    fileSystem = FileSystem.SYSTEM,
                    cwd = root,
                    tools = ToolRegistry(listOf(noopTool)),
                    sessionStore = store,
                    includePartialMessages = false,
                    maxSteps = 2,
                )
            val events = OpenAgenticSdk.query(prompt = "go", options = options).toList()
            val result = events.last { it is Result } as Result
            assertEquals("max_steps", result.stopReason)
        }

    @Test
    fun offline_loop_cancel_mid_run_no_partial_jsonl() =
        runTest {
            OfflineCancellationAndConcurrencyTest().offline_cancel_during_provider_call_does_not_write_partial_jsonl()
        }

    @Test
    fun offline_loop_timeout_provider_vs_tool_classification() =
        runTest {
            run {
                val root = tempRoot()
                val store = FileSessionStore(fileSystem = FileSystem.SYSTEM, rootDir = root)
                val provider =
                    object : ResponsesProvider {
                        override val name: String = "timeout-fixture"

                        override suspend fun complete(request: ResponsesRequest): ModelOutput {
                            throw ProviderTimeoutException("timeout")
                        }
                    }
                val options =
                    OpenAgenticOptions(
                        provider = provider,
                        model = "m",
                        apiKey = "x",
                        fileSystem = FileSystem.SYSTEM,
                        cwd = root,
                        tools = ToolRegistry(),
                        sessionStore = store,
                        includePartialMessages = false,
                        maxSteps = 1,
                    )
                val events = OpenAgenticSdk.query(prompt = "hi", options = options).toList()
                assertTrue(events.any { it.type == "runtime.error" })
                val result = events.last { it is Result } as Result
                assertEquals("error", result.stopReason)
            }

            val root = tempRoot()
            val store = FileSessionStore(fileSystem = FileSystem.SYSTEM, rootDir = root)
            val timeoutTool =
                object : Tool {
                    override val name: String = "T"
                    override val description: String = "timeout"

                    override suspend fun run(input: ToolInput, ctx: ToolContext): ToolOutput {
                        throw IllegalStateException("timeout")
                    }
                }
            val provider =
                ScriptedResponsesProvider { step, _ ->
                    if (step == 1) ModelOutput(assistantText = null, toolCalls = listOf(ToolCall(toolUseId = "call_t", name = "T", arguments = buildJsonObject { })), responseId = "resp_1")
                    else ModelOutput(assistantText = "done", toolCalls = emptyList(), responseId = "resp_2")
                }
            val options =
                OpenAgenticOptions(
                    provider = provider,
                    model = "m",
                    apiKey = "x",
                    fileSystem = FileSystem.SYSTEM,
                    cwd = root,
                    tools = ToolRegistry(listOf(timeoutTool)),
                    sessionStore = store,
                    includePartialMessages = false,
                    maxSteps = 3,
                )
            val events = OpenAgenticSdk.query(prompt = "go", options = options).toList()
            val tr = events.filterIsInstance<ToolResult>().first { it.toolUseId == "call_t" }
            assertTrue(tr.isError)
            assertEquals("IllegalStateException", tr.errorType)
        }

    @Test
    fun offline_tool_args_missing_field() =
        runTest {
            val root = tempRoot()
            val store = FileSessionStore(fileSystem = FileSystem.SYSTEM, rootDir = root)
            val provider =
                ScriptedResponsesProvider { step, _ ->
                    if (step == 1) ModelOutput(assistantText = null, toolCalls = listOf(ToolCall(toolUseId = "call_1", name = "Read", arguments = buildJsonObject { })), responseId = "resp_1")
                    else ModelOutput(assistantText = "done", toolCalls = emptyList(), responseId = "resp_2")
                }
            val options =
                OpenAgenticOptions(
                    provider = provider,
                    model = "m",
                    apiKey = "x",
                    fileSystem = FileSystem.SYSTEM,
                    cwd = root,
                    tools = ToolRegistry(listOf(ReadTool())),
                    sessionStore = store,
                    permissionGate = PermissionGate.bypass(),
                    includePartialMessages = false,
                    maxSteps = 3,
                )
            val events = OpenAgenticSdk.query(prompt = "go", options = options).toList()
            val tr = events.filterIsInstance<ToolResult>().first { it.toolUseId == "call_1" }
            assertTrue(tr.isError)
        }

    @Test
    fun offline_tool_args_wrong_type() =
        runTest {
            val root = tempRoot()
            val store = FileSessionStore(fileSystem = FileSystem.SYSTEM, rootDir = root)
            val provider =
                ScriptedResponsesProvider { step, _ ->
                    if (step == 1) {
                        ModelOutput(
                            assistantText = null,
                            toolCalls = listOf(ToolCall(toolUseId = "call_1", name = "Read", arguments = buildJsonObject { put("file_path", JsonPrimitive(123)) })),
                            responseId = "resp_1",
                        )
                    } else {
                        ModelOutput(assistantText = "done", toolCalls = emptyList(), responseId = "resp_2")
                    }
                }
            val options =
                OpenAgenticOptions(
                    provider = provider,
                    model = "m",
                    apiKey = "x",
                    fileSystem = FileSystem.SYSTEM,
                    cwd = root,
                    tools = ToolRegistry(listOf(ReadTool())),
                    sessionStore = store,
                    permissionGate = PermissionGate.bypass(),
                    includePartialMessages = false,
                    maxSteps = 3,
                )
            val events = OpenAgenticSdk.query(prompt = "go", options = options).toList()
            val tr = events.filterIsInstance<ToolResult>().first { it.toolUseId == "call_1" }
            assertTrue(tr.isError)
        }

    @Test
    fun offline_tool_args_unknown_properties() =
        runTest {
            val root = tempRoot()
            FileSystem.SYSTEM.write(root.resolve("a.txt")) { writeUtf8("hello") }
            val store = FileSessionStore(fileSystem = FileSystem.SYSTEM, rootDir = root)
            val provider =
                ScriptedResponsesProvider { step, _ ->
                    if (step == 1) {
                        ModelOutput(
                            assistantText = null,
                            toolCalls =
                                listOf(
                                    ToolCall(
                                        toolUseId = "call_1",
                                        name = "Read",
                                        arguments =
                                            buildJsonObject {
                                                put("file_path", JsonPrimitive("a.txt"))
                                                put("unknown", JsonPrimitive("x"))
                                            },
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
                    model = "m",
                    apiKey = "x",
                    fileSystem = FileSystem.SYSTEM,
                    cwd = root,
                    tools = ToolRegistry(listOf(ReadTool())),
                    sessionStore = store,
                    permissionGate = PermissionGate.bypass(),
                    includePartialMessages = false,
                    maxSteps = 3,
                )
            val events = OpenAgenticSdk.query(prompt = "go", options = options).toList()
            val tr = events.filterIsInstance<ToolResult>().first { it.toolUseId == "call_1" }
            assertFalse(tr.isError)
        }

    @Test
    fun offline_tool_args_json_string_instead_of_object() =
        runTest {
            val args = me.lemonhall.openagentic.sdk.providers.parseArgs("\"hello\"", json = EventJson.json)
            assertTrue(args.containsKey("_raw"))

            val root = tempRoot()
            val store = FileSessionStore(fileSystem = FileSystem.SYSTEM, rootDir = root)
            val provider =
                ScriptedResponsesProvider { step, _ ->
                    if (step == 1) ModelOutput(assistantText = null, toolCalls = listOf(ToolCall(toolUseId = "call_1", name = "Read", arguments = args)), responseId = "resp_1")
                    else ModelOutput(assistantText = "done", toolCalls = emptyList(), responseId = "resp_2")
                }
            val options =
                OpenAgenticOptions(
                    provider = provider,
                    model = "m",
                    apiKey = "x",
                    fileSystem = FileSystem.SYSTEM,
                    cwd = root,
                    tools = ToolRegistry(listOf(ReadTool())),
                    sessionStore = store,
                    permissionGate = PermissionGate.bypass(),
                    includePartialMessages = false,
                    maxSteps = 3,
                )
            val events = OpenAgenticSdk.query(prompt = "go", options = options).toList()
            val tr = events.filterIsInstance<ToolResult>().first { it.toolUseId == "call_1" }
            assertTrue(tr.isError)
        }

    @Test
    fun offline_tool_output_large_payload_truncate_or_summarize() {
        ToolOutputPruningPlaceholderTest().toolOutputIsReplacedWithPlaceholderAfterCompactionMark()
    }

    @Test
    fun offline_tool_registry_duplicate_name_policy() {
        val t1 =
            object : Tool {
                override val name: String = "X"
                override val description: String = "one"

                override suspend fun run(input: ToolInput, ctx: ToolContext): ToolOutput = ToolOutput.Json(JsonPrimitive("1"))
            }
        val t2 =
            object : Tool {
                override val name: String = "X"
                override val description: String = "two"

                override suspend fun run(input: ToolInput, ctx: ToolContext): ToolOutput = ToolOutput.Json(JsonPrimitive("2"))
            }
        val reg = ToolRegistry(listOf(t1, t2))
        assertEquals("two", reg.get("X").description)
    }

    @Test
    fun offline_provider_invalid_json_response_is_handled() {
        val args = me.lemonhall.openagentic.sdk.providers.parseArgs("{", json = EventJson.json)
        assertTrue(args.containsKey("_raw"))
    }

    @Test
    fun offline_security_control_chars_do_not_break_jsonl() {
        val root = tempRoot()
        val store = FileSessionStore(fileSystem = FileSystem.SYSTEM, rootDir = root)
        val sessionId = store.createSession()
        store.appendEvent(sessionId, me.lemonhall.openagentic.sdk.events.UserMessage(text = "a\u0000b\u0001c\nline2"))
        val raw = FileSystem.SYSTEM.read(root.resolve("sessions").resolve(sessionId).resolve("events.jsonl")) { readUtf8() }
        for (line in raw.lineSequence()) {
            if (line.isBlank()) continue
            EventJson.loads(line)
        }
    }
}

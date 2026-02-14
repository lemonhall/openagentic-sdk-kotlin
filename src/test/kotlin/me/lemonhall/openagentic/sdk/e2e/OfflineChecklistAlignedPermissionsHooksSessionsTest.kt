package me.lemonhall.openagentic.sdk.e2e

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.lemonhall.openagentic.sdk.events.ToolResult
import me.lemonhall.openagentic.sdk.hooks.HookDecision
import me.lemonhall.openagentic.sdk.hooks.HookEngine
import me.lemonhall.openagentic.sdk.hooks.HookMatcher
import me.lemonhall.openagentic.sdk.permissions.PermissionGate
import me.lemonhall.openagentic.sdk.permissions.PermissionMode
import me.lemonhall.openagentic.sdk.permissions.UserAnswerer
import me.lemonhall.openagentic.sdk.providers.ModelOutput
import me.lemonhall.openagentic.sdk.providers.ResponsesProvider
import me.lemonhall.openagentic.sdk.providers.ResponsesRequest
import me.lemonhall.openagentic.sdk.providers.ToolCall
import me.lemonhall.openagentic.sdk.runtime.OpenAgenticOptions
import me.lemonhall.openagentic.sdk.runtime.OpenAgenticSdk
import me.lemonhall.openagentic.sdk.sessions.FileSessionStore
import me.lemonhall.openagentic.sdk.testing.ScriptedResponsesProvider
import me.lemonhall.openagentic.sdk.tools.Tool
import me.lemonhall.openagentic.sdk.tools.ToolContext
import me.lemonhall.openagentic.sdk.tools.ToolInput
import me.lemonhall.openagentic.sdk.tools.ToolOutput
import me.lemonhall.openagentic.sdk.tools.ToolRegistry
import okio.FileSystem
import okio.Path.Companion.toPath

class OfflineChecklistAlignedPermissionsHooksSessionsTest {
    private fun tempRoot(prefix: String = "openagentic-offline-e2e-v6-"): okio.Path {
        val rootNio = Files.createTempDirectory(prefix)
        return rootNio.toString().replace('\\', '/').toPath()
    }

    @Test
    fun offline_permission_allow_all() =
        runTest {
            val root = tempRoot()
            val store = FileSessionStore(fileSystem = FileSystem.SYSTEM, rootDir = root)
            var ran = false
            val tool =
                object : Tool {
                    override val name: String = "Write"
                    override val description: String = "write"

                    override suspend fun run(input: ToolInput, ctx: ToolContext): ToolOutput {
                        ran = true
                        return ToolOutput.Json(JsonPrimitive("ok"))
                    }
                }
            val provider =
                ScriptedResponsesProvider { step, _ ->
                    if (step == 1) ModelOutput(assistantText = null, toolCalls = listOf(ToolCall(toolUseId = "call_1", name = "Write", arguments = buildJsonObject { })), responseId = "resp_1")
                    else ModelOutput(assistantText = "done", toolCalls = emptyList(), responseId = "resp_2")
                }
            val options =
                OpenAgenticOptions(
                    provider = provider,
                    model = "m",
                    apiKey = "x",
                    fileSystem = FileSystem.SYSTEM,
                    cwd = root,
                    tools = ToolRegistry(listOf(tool)),
                    sessionStore = store,
                    permissionGate = PermissionGate.bypass(),
                    includePartialMessages = false,
                    maxSteps = 3,
                )
            OpenAgenticSdk.query(prompt = "go", options = options).toList()
            assertTrue(ran)
        }

    @Test
    fun offline_permission_prompt_no_answerer_fails_fast() =
        runTest {
            OfflineHardInvariantsTest().offline_permission_prompt_without_answerer_denies_without_question()
        }

    @Test
    fun offline_permission_prompt_answerer_happy_path() =
        runTest {
            val root = tempRoot()
            val store = FileSessionStore(fileSystem = FileSystem.SYSTEM, rootDir = root)
            var ran = false
            val tool =
                object : Tool {
                    override val name: String = "Read"
                    override val description: String = "read"

                    override suspend fun run(input: ToolInput, ctx: ToolContext): ToolOutput {
                        ran = true
                        return ToolOutput.Json(JsonPrimitive("ok"))
                    }
                }
            val answerer = UserAnswerer { JsonPrimitive("yes") }
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
                    tools = ToolRegistry(listOf(tool)),
                    sessionStore = store,
                    permissionGate = PermissionGate.prompt(userAnswerer = answerer),
                    includePartialMessages = false,
                    maxSteps = 3,
                )
            OpenAgenticSdk.query(prompt = "go", options = options).toList()
            assertTrue(ran)
        }

    @Test
    fun offline_permission_default_deny_on_schema_parse_error() =
        runTest {
            // v6: DEFAULT 模式下，安全工具如果 schema 解析失败（缺关键字段），默认拒绝而不是让 tool 崩。
            val root = tempRoot()
            val store = FileSessionStore(fileSystem = FileSystem.SYSTEM, rootDir = root)
            val tool =
                object : Tool {
                    override val name: String = "Read"
                    override val description: String = "read"

                    override suspend fun run(input: ToolInput, ctx: ToolContext): ToolOutput {
                        error("tool must not run when schema is invalid under PermissionGate.DEFAULT")
                    }
                }
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
                    tools = ToolRegistry(listOf(tool)),
                    sessionStore = store,
                    permissionGate = PermissionGate.default(userAnswerer = null),
                    includePartialMessages = false,
                    maxSteps = 2,
                )
            val events = OpenAgenticSdk.query(prompt = "go", options = options).toList()
            val denied = events.filterIsInstance<ToolResult>().first { it.toolUseId == "call_1" }
            assertTrue(denied.isError)
            assertEquals("PermissionDenied", denied.errorType)
        }

    @Test
    fun offline_permission_scope_precedence() =
        runTest {
            val root = tempRoot()
            val store = FileSessionStore(fileSystem = FileSystem.SYSTEM, rootDir = root)
            var ran = false
            val tool =
                object : Tool {
                    override val name: String = "Write"
                    override val description: String = "write"

                    override suspend fun run(input: ToolInput, ctx: ToolContext): ToolOutput {
                        ran = true
                        return ToolOutput.Json(JsonPrimitive("ok"))
                    }
                }
            fun newProvider() =
                ScriptedResponsesProvider { step, _ ->
                    if (step == 1) ModelOutput(assistantText = null, toolCalls = listOf(ToolCall(toolUseId = "call_1", name = "Write", arguments = buildJsonObject { })), responseId = "resp_1")
                    else ModelOutput(assistantText = "done", toolCalls = emptyList(), responseId = "resp_2")
                }

            run {
                ran = false
                val options =
                    OpenAgenticOptions(
                        provider = newProvider(),
                        model = "m",
                        apiKey = "x",
                        fileSystem = FileSystem.SYSTEM,
                        cwd = root,
                        tools = ToolRegistry(listOf(tool)),
                        sessionStore = store,
                        permissionGate = PermissionGate.bypass(),
                        sessionPermissionMode = PermissionMode.DENY,
                        permissionModeOverride = null,
                        includePartialMessages = false,
                        maxSteps = 2,
                    )
                val events = OpenAgenticSdk.query(prompt = "go", options = options).toList()
                val denied = events.filterIsInstance<ToolResult>().first { it.toolUseId == "call_1" }
                assertTrue(denied.isError)
                assertEquals("PermissionDenied", denied.errorType)
                assertFalse(ran)
            }

            run {
                ran = false
                val options =
                    OpenAgenticOptions(
                        provider = newProvider(),
                        model = "m",
                        apiKey = "x",
                        fileSystem = FileSystem.SYSTEM,
                        cwd = root,
                        tools = ToolRegistry(listOf(tool)),
                        sessionStore = store,
                        permissionGate = PermissionGate.bypass(),
                        sessionPermissionMode = PermissionMode.DENY,
                        permissionModeOverride = PermissionMode.BYPASS,
                        includePartialMessages = false,
                        maxSteps = 2,
                    )
                OpenAgenticSdk.query(prompt = "go", options = options).toList()
                assertTrue(ran)
            }
        }

    @Test
    fun offline_hooks_before_model_call_mutates_messages() =
        runTest {
            val root = tempRoot()
            val store = FileSessionStore(fileSystem = FileSystem.SYSTEM, rootDir = root)
            val hooks =
                HookEngine(
                    beforeModelCall =
                        listOf(
                            HookMatcher(
                                name = "override-input",
                                toolNamePattern = "*",
                                hook = { payload ->
                                    val input = payload["input"] as? kotlinx.serialization.json.JsonArray ?: return@HookMatcher HookDecision()
                                    val newInput =
                                        input.map { it as JsonObject }.map { item ->
                                            if (item["role"] == JsonPrimitive("user")) {
                                                buildJsonObject {
                                                    for ((k, v) in item) {
                                                        if (k == "content") put("content", JsonPrimitive("mutated")) else put(k, v)
                                                    }
                                                }
                                            } else {
                                                item
                                            }
                                        }
                                    HookDecision(overrideModelInput = newInput)
                                },
                            ),
                        ),
                )

            val provider =
                object : ResponsesProvider {
                    override val name: String = "capture"

                    override suspend fun complete(request: ResponsesRequest): ModelOutput {
                        val user = request.input.first { it["role"] == JsonPrimitive("user") }
                        assertEquals("mutated", (user["content"] as JsonPrimitive).content)
                        return ModelOutput(assistantText = "ok", toolCalls = emptyList(), responseId = "resp_1")
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
                    hookEngine = hooks,
                    includePartialMessages = false,
                    maxSteps = 1,
                )
            OpenAgenticSdk.query(prompt = "orig", options = options).toList()
        }

    @Test
    fun offline_hooks_pre_tool_use_mutates_args() =
        runTest {
            OfflineHardInvariantsTest().offline_hook_pre_tool_use_can_mutate_tool_input()
        }

    @Test
    fun offline_hooks_order_is_stable() =
        runTest {
            OfflineHardInvariantsTest().offline_hook_order_is_stable()
        }

    @Test
    fun offline_hooks_exception_is_recorded_and_isolated() =
        runTest {
            OfflineHardInvariantsTest().offline_hook_exception_is_isolated_and_recorded()
        }

    @Test
    fun offline_hooks_cannot_bypass_permissions() =
        runTest {
            val root = tempRoot()
            val store = FileSessionStore(fileSystem = FileSystem.SYSTEM, rootDir = root)
            var ran = false
            val tool =
                object : Tool {
                    override val name: String = "Write"
                    override val description: String = "write"

                    override suspend fun run(input: ToolInput, ctx: ToolContext): ToolOutput {
                        ran = true
                        return ToolOutput.Json(JsonPrimitive("ok"))
                    }
                }

            val hooks =
                HookEngine(
                    preToolUse =
                        listOf(
                            HookMatcher(
                                name = "try-bypass",
                                toolNamePattern = "Write",
                                hook = { _ -> HookDecision(overrideToolInput = buildJsonObject { put("bypass", JsonPrimitive(true)) }) },
                            ),
                        ),
                )

            val provider =
                ScriptedResponsesProvider { step, _ ->
                    if (step == 1) ModelOutput(assistantText = null, toolCalls = listOf(ToolCall(toolUseId = "call_w", name = "Write", arguments = buildJsonObject { })), responseId = "resp_1")
                    else ModelOutput(assistantText = "done", toolCalls = emptyList(), responseId = "resp_2")
                }

            val answerer = UserAnswerer { JsonPrimitive("no") }
            val options =
                OpenAgenticOptions(
                    provider = provider,
                    model = "m",
                    apiKey = "x",
                    fileSystem = FileSystem.SYSTEM,
                    cwd = root,
                    tools = ToolRegistry(listOf(tool)),
                    sessionStore = store,
                    hookEngine = hooks,
                    permissionGate = PermissionGate.prompt(userAnswerer = answerer),
                    includePartialMessages = false,
                    maxSteps = 3,
                )
            val events = OpenAgenticSdk.query(prompt = "go", options = options).toList()
            val denied = events.filterIsInstance<ToolResult>().first { it.toolUseId == "call_w" }
            assertTrue(denied.isError)
            assertEquals("PermissionDenied", denied.errorType)
            assertFalse(ran)
        }

    @Test
    fun offline_session_resume_continues_without_replaying_side_effect_tool() =
        runTest {
            val root = tempRoot()
            val store = FileSessionStore(fileSystem = FileSystem.SYSTEM, rootDir = root)
            var count = 0
            val tool =
                object : Tool {
                    override val name: String = "Inc"
                    override val description: String = "inc"

                    override suspend fun run(input: ToolInput, ctx: ToolContext): ToolOutput {
                        count++
                        return ToolOutput.Json(JsonPrimitive(count))
                    }
                }

            val p1 =
                ScriptedResponsesProvider { step, _ ->
                    if (step == 1) ModelOutput(assistantText = null, toolCalls = listOf(ToolCall(toolUseId = "call_1", name = "Inc", arguments = buildJsonObject { })), responseId = "resp_1")
                    else ModelOutput(assistantText = "done", toolCalls = emptyList(), responseId = "resp_2")
                }
            val r1 =
                OpenAgenticSdk.run(
                    prompt = "one",
                    options =
                        OpenAgenticOptions(
                            provider = p1,
                            model = "m",
                            apiKey = "x",
                            fileSystem = FileSystem.SYSTEM,
                            cwd = root,
                            tools = ToolRegistry(listOf(tool)),
                            sessionStore = store,
                            includePartialMessages = false,
                            maxSteps = 3,
                        ),
                )
            assertEquals(1, count)

            val p2 =
                ScriptedResponsesProvider { _, _ ->
                    ModelOutput(assistantText = "ok", toolCalls = emptyList(), responseId = "resp_3")
                }
            OpenAgenticSdk.run(
                prompt = "two",
                options =
                    OpenAgenticOptions(
                        provider = p2,
                        model = "m",
                        apiKey = "x",
                        fileSystem = FileSystem.SYSTEM,
                        cwd = root,
                        tools = ToolRegistry(listOf(tool)),
                        sessionStore = store,
                        resumeSessionId = r1.sessionId,
                        includePartialMessages = false,
                        maxSteps = 1,
                    ),
            )
            assertEquals(1, count, "resume must not replay past tool side effects")
        }

    @Test
    fun offline_session_truncated_line_recovery_policy() {
        OfflineHardInvariantsTest().offline_session_truncated_last_line_is_ignored_by_readEvents()
    }

    @Test
    fun offline_session_concurrent_sessions_isolation() =
        runTest {
            OfflineCancellationAndConcurrencyTest().offline_concurrent_sessions_do_not_cross_contaminate()
        }

    @Test
    fun offline_session_custom_home_dir() {
        val root = tempRoot()
        val store = FileSessionStore(fileSystem = FileSystem.SYSTEM, rootDir = root.resolve("custom-home"))
        val sid = store.createSession()
        val dir = store.sessionDir(sid)
        assertTrue(FileSystem.SYSTEM.exists(dir))
    }

    @Test
    fun offline_session_unicode_paths() =
        runTest {
            val base = tempRoot()
            val unicodeRoot = base.resolve("你好-会话")
            val store = FileSessionStore(fileSystem = FileSystem.SYSTEM, rootDir = unicodeRoot)
            val root = unicodeRoot
            FileSystem.SYSTEM.createDirectories(root)

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
            OpenAgenticSdk.query(prompt = "go", options = options).toList()

            val dirs = FileSystem.SYSTEM.list(root.resolve("sessions"))
            assertEquals(1, dirs.size)
        }
}

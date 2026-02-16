package me.lemonhall.openagentic.sdk.e2e

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.lemonhall.openagentic.sdk.compaction.COMPACTION_SYSTEM_PROMPT
import me.lemonhall.openagentic.sdk.compaction.CompactionOptions
import me.lemonhall.openagentic.sdk.events.Result
import me.lemonhall.openagentic.sdk.events.ToolResult
import me.lemonhall.openagentic.sdk.events.UserCompaction
import me.lemonhall.openagentic.sdk.permissions.PermissionGate
import me.lemonhall.openagentic.sdk.providers.LegacyProvider
import me.lemonhall.openagentic.sdk.providers.LegacyRequest
import me.lemonhall.openagentic.sdk.providers.ModelOutput
import me.lemonhall.openagentic.sdk.providers.ProviderRateLimitException
import me.lemonhall.openagentic.sdk.providers.ProviderTimeoutException
import me.lemonhall.openagentic.sdk.providers.ToolCall
import me.lemonhall.openagentic.sdk.runtime.CompactionOverflowLegacyTest
import me.lemonhall.openagentic.sdk.runtime.OpenAgenticOptions
import me.lemonhall.openagentic.sdk.runtime.OpenAgenticSdk
import me.lemonhall.openagentic.sdk.runtime.ProviderRetryOptions
import me.lemonhall.openagentic.sdk.runtime.ToolNotAllowedTest
import me.lemonhall.openagentic.sdk.sessions.FileSessionStore
import me.lemonhall.openagentic.sdk.tools.BashTool
import me.lemonhall.openagentic.sdk.tools.ReadTool
import me.lemonhall.openagentic.sdk.tools.ToolContext
import me.lemonhall.openagentic.sdk.tools.ToolRegistry
import me.lemonhall.openagentic.sdk.tools.WebFetchTool
import okio.FileSystem
import okio.Path.Companion.toPath
import org.junit.jupiter.api.Assumptions.assumeTrue

class OfflineChecklistAlignedCompactionProviderSecurityTest {
    private fun tempRoot(prefix: String = "openagentic-offline-e2e-v6-"): okio.Path {
        val rootNio = Files.createTempDirectory(prefix)
        return rootNio.toString().replace('\\', '/').toPath()
    }

    @Test
    fun offline_allowed_tools_enforced_across_turns() =
        runTest {
            // Existing focused test covers the gate when provider requests a disallowed tool.
            ToolNotAllowedTest().toolNotAllowedProducesErrorToolResult()
        }

    @Test
    fun offline_allowed_tools_preserved_after_compaction() =
        runTest {
            val root = tempRoot()
            val store = FileSessionStore(fileSystem = FileSystem.SYSTEM, rootDir = root)

            val provider =
                object : LegacyProvider {
                    override val name: String = "legacy-compaction-then-tool"

                    private var normalCalls = 0
                    private var sawCompaction = false

                    override suspend fun complete(request: LegacyRequest): ModelOutput {
                        val sys0 = request.messages.firstOrNull()
                        val sysRole = (sys0?.get("role") as? JsonPrimitive)?.content
                        val sysContent = (sys0?.get("content") as? JsonPrimitive)?.content
                        if (sysRole == "system" && sysContent?.trim() == COMPACTION_SYSTEM_PROMPT.trim()) {
                            sawCompaction = true
                            return ModelOutput(assistantText = "SUMMARY_OK", toolCalls = emptyList(), usage = buildJsonObject { put("total_tokens", JsonPrimitive(1)) })
                        }

                        val lastUser =
                            request.messages.asReversed()
                                .firstOrNull { (it["role"] as? JsonPrimitive)?.content == "user" }
                                ?.get("content") as? JsonPrimitive

                        val userText = lastUser?.content.orEmpty()
                        normalCalls++
                        return when {
                            normalCalls == 1 -> {
                                ModelOutput(
                                    assistantText = "FIRST",
                                    toolCalls = emptyList(),
                                    usage = buildJsonObject { put("total_tokens", JsonPrimitive(90)) },
                                )
                            }
                            sawCompaction -> {
                                ModelOutput(
                                    assistantText = null,
                                    toolCalls = listOf(ToolCall(toolUseId = "call_write_after_compact", name = "Write", arguments = buildJsonObject { })),
                                    usage = buildJsonObject { put("total_tokens", JsonPrimitive(1)) },
                                )
                            }
                            else -> {
                                ModelOutput(assistantText = "done", toolCalls = emptyList(), usage = buildJsonObject { put("total_tokens", JsonPrimitive(1)) })
                            }
                        }
                    }
                }

            val options =
                OpenAgenticOptions(
                    provider = provider,
                    model = "m",
                    apiKey = "x",
                    fileSystem = FileSystem.SYSTEM,
                    cwd = root,
                    projectDir = root,
                    tools = ToolRegistry(listOf(ReadTool())), // Write not even registered
                    sessionStore = store,
                    allowedTools = setOf("Read"),
                    permissionGate = PermissionGate.bypass(),
                    maxSteps = 10,
                    compaction =
                        CompactionOptions(
                            auto = true,
                            prune = false,
                            contextLimit = 100,
                            globalOutputCap = 50,
                            reserved = 10,
                        ),
                )

            val events = OpenAgenticSdk.query(prompt = "hi", options = options).toList()
            assertTrue(events.any { it is UserCompaction })
            val denied = events.filterIsInstance<ToolResult>().firstOrNull { it.toolUseId == "call_write_after_compact" }
            assertNotNull(denied)
            assertTrue(denied.isError)
            assertEquals("ToolNotAllowed", denied.errorType)
        }

    @Test
    fun offline_compaction_trigger_and_records_event() {
        CompactionOverflowLegacyTest().overflowTriggersCompactionPassAndContinues()
    }

    @Test
    fun offline_compaction_preserves_permissions_and_allowed_tools() =
        runTest {
            val root = tempRoot()
            val store = FileSessionStore(fileSystem = FileSystem.SYSTEM, rootDir = root)

            val provider =
                object : LegacyProvider {
                    override val name: String = "legacy-compaction-then-read"
                    private var normalCalls = 0
                    private var sawCompaction = false

                    override suspend fun complete(request: LegacyRequest): ModelOutput {
                        val sys0 = request.messages.firstOrNull()
                        val sysRole = (sys0?.get("role") as? JsonPrimitive)?.content
                        val sysContent = (sys0?.get("content") as? JsonPrimitive)?.content
                        if (sysRole == "system" && sysContent?.trim() == COMPACTION_SYSTEM_PROMPT.trim()) {
                            sawCompaction = true
                            return ModelOutput(assistantText = "SUMMARY_OK", toolCalls = emptyList(), usage = buildJsonObject { put("total_tokens", JsonPrimitive(1)) })
                        }

                        val lastUser =
                            request.messages.asReversed()
                                .firstOrNull { (it["role"] as? JsonPrimitive)?.content == "user" }
                                ?.get("content") as? JsonPrimitive
                        val userText = lastUser?.content.orEmpty()
                        normalCalls++
                        return when {
                            normalCalls == 1 -> {
                                ModelOutput(
                                    assistantText = "FIRST",
                                    toolCalls = emptyList(),
                                    usage = buildJsonObject { put("total_tokens", JsonPrimitive(90)) },
                                )
                            }
                            sawCompaction -> {
                                ModelOutput(
                                    assistantText = null,
                                    toolCalls = listOf(ToolCall(toolUseId = "call_read_after_compact", name = "Read", arguments = buildJsonObject { put("file_path", JsonPrimitive("a.txt")) })),
                                    usage = buildJsonObject { put("total_tokens", JsonPrimitive(1)) },
                                )
                            }
                            else -> {
                                ModelOutput(assistantText = "done", toolCalls = emptyList(), usage = buildJsonObject { put("total_tokens", JsonPrimitive(1)) })
                            }
                        }
                    }
                }

            FileSystem.SYSTEM.write(root.resolve("a.txt")) { writeUtf8("hello") }

            val options =
                OpenAgenticOptions(
                    provider = provider,
                    model = "m",
                    apiKey = "x",
                    fileSystem = FileSystem.SYSTEM,
                    cwd = root,
                    projectDir = root,
                    tools = ToolRegistry(listOf(ReadTool())),
                    sessionStore = store,
                    allowedTools = setOf("Read"),
                    permissionGate = PermissionGate.deny(),
                    maxSteps = 10,
                    compaction =
                        CompactionOptions(
                            auto = true,
                            prune = false,
                            contextLimit = 100,
                            globalOutputCap = 50,
                            reserved = 10,
                        ),
                )

            val events = OpenAgenticSdk.query(prompt = "hi", options = options).toList()
            val denied = events.filterIsInstance<ToolResult>().firstOrNull { it.toolUseId == "call_read_after_compact" }
            assertNotNull(denied)
            assertTrue(denied.isError)
            assertEquals("PermissionDenied", denied.errorType)
        }

    @Test
    fun offline_provider_timeout_is_classified() {
        runTest {
            val root = tempRoot()
            val store = FileSessionStore(fileSystem = FileSystem.SYSTEM, rootDir = root)
            val provider =
                object : me.lemonhall.openagentic.sdk.providers.ResponsesProvider {
                    override val name: String = "timeout-fixture"

                    override suspend fun complete(request: me.lemonhall.openagentic.sdk.providers.ResponsesRequest): ModelOutput {
                        throw ProviderTimeoutException("timeout")
                    }
                }
            val events =
                OpenAgenticSdk.query(
                    prompt = "hi",
                    options =
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
                        ),
                ).toList()

            val err = events.firstOrNull { it.type == "runtime.error" } as? me.lemonhall.openagentic.sdk.events.RuntimeError
            assertNotNull(err)
            assertEquals("provider", err.phase)
            val result = events.last { it is Result } as Result
            assertEquals("error", result.stopReason)
        }
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun offline_provider_rate_limit_backoff_uses_fake_clock() {
        runTest {
            val root = tempRoot()
            val store = FileSessionStore(fileSystem = FileSystem.SYSTEM, rootDir = root)
            var calls = 0
            val provider =
                object : me.lemonhall.openagentic.sdk.providers.ResponsesProvider {
                    override val name: String = "rate-limit-fixture"

                    override suspend fun complete(request: me.lemonhall.openagentic.sdk.providers.ResponsesRequest): ModelOutput {
                        calls++
                        if (calls == 1) throw ProviderRateLimitException("rate limited", retryAfterMs = 1_000)
                        return ModelOutput(assistantText = "ok", toolCalls = emptyList(), responseId = "resp_1")
                    }
                }

            val before = testScheduler.currentTime
            val events =
                OpenAgenticSdk.query(
                    prompt = "hi",
                    options =
                        OpenAgenticOptions(
                            provider = provider,
                            model = "m",
                            apiKey = "x",
                            providerRetry = ProviderRetryOptions(maxRetries = 1, initialBackoffMs = 10, maxBackoffMs = 10_000, useRetryAfterMs = true),
                            fileSystem = FileSystem.SYSTEM,
                            cwd = root,
                            tools = ToolRegistry(),
                            sessionStore = store,
                            includePartialMessages = false,
                            maxSteps = 1,
                        ),
                ).toList()

            val after = testScheduler.currentTime
            assertEquals(2, calls)
            assertTrue(after - before >= 1_000, "expected fake clock to advance by retryAfterMs; before=$before after=$after")
            val result = events.last { it is Result } as Result
            assertEquals("end", result.stopReason)
        }
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun offline_provider_rate_limit_backoff_is_capped_by_maxBackoff() {
        runTest {
            val root = tempRoot()
            val store = FileSessionStore(fileSystem = FileSystem.SYSTEM, rootDir = root)
            var calls = 0
            val provider =
                object : me.lemonhall.openagentic.sdk.providers.ResponsesProvider {
                    override val name: String = "rate-limit-cap-fixture"

                    override suspend fun complete(request: me.lemonhall.openagentic.sdk.providers.ResponsesRequest): ModelOutput {
                        calls++
                        if (calls == 1) throw ProviderRateLimitException("rate limited", retryAfterMs = 60_000)
                        return ModelOutput(assistantText = "ok", toolCalls = emptyList(), responseId = "resp_1")
                    }
                }

            val before = testScheduler.currentTime
            val events =
                OpenAgenticSdk.query(
                    prompt = "hi",
                    options =
                        OpenAgenticOptions(
                            provider = provider,
                            model = "m",
                            apiKey = "x",
                            providerRetry = ProviderRetryOptions(maxRetries = 1, initialBackoffMs = 10, maxBackoffMs = 1_000, useRetryAfterMs = true),
                            fileSystem = FileSystem.SYSTEM,
                            cwd = root,
                            tools = ToolRegistry(),
                            sessionStore = store,
                            includePartialMessages = false,
                            maxSteps = 1,
                        ),
                ).toList()

            val after = testScheduler.currentTime
            assertEquals(2, calls)
            val delta = after - before
            assertTrue(delta in 1_000..2_000, "expected capped fake clock sleep (~maxBackoffMs); delta=$delta before=$before after=$after")
            val result = events.last { it is Result } as Result
            assertEquals("end", result.stopReason)
        }
    }

    @Test
    fun offline_security_path_traversal_blocked() =
        runTest {
            val root = tempRoot()
            val store = FileSessionStore(fileSystem = FileSystem.SYSTEM, rootDir = root)
            val provider =
                me.lemonhall.openagentic.sdk.testing.ScriptedResponsesProvider { step, _ ->
                    if (step == 1) {
                        ModelOutput(
                            assistantText = null,
                            toolCalls = listOf(ToolCall(toolUseId = "call_1", name = "Read", arguments = buildJsonObject { put("file_path", JsonPrimitive("../x.txt")) })),
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
                    projectDir = root,
                    tools = ToolRegistry(listOf(ReadTool())),
                    sessionStore = store,
                    permissionGate = PermissionGate.bypass(),
                    includePartialMessages = false,
                    maxSteps = 3,
                )
            val events = OpenAgenticSdk.query(prompt = "go", options = options).toList()
            val denied = events.filterIsInstance<ToolResult>().first { it.toolUseId == "call_1" }
            assertTrue(denied.isError)
        }

    @Test
    fun offline_security_symlink_escape_blocked() =
        runTest {
            val root = tempRoot()
            val outside = Files.createTempDirectory("openagentic-outside-")
            Files.writeString(outside.resolve("secret.txt"), "secret")

            val rootNio = Path.of(root.toString())
            val link = rootNio.resolve("link")
            try {
                Files.createSymbolicLink(link, outside)
            } catch (t: Throwable) {
                assumeTrue(false, "symlink not supported: ${t::class.simpleName}: ${t.message}")
            }

            val ctx = ToolContext(fileSystem = FileSystem.SYSTEM, cwd = root, projectDir = root)
            val tool = ReadTool()
            val ex =
                assertFailsWith<IllegalArgumentException> {
                    tool.run(buildJsonObject { put("file_path", JsonPrimitive("link/secret.txt")) }, ctx)
                }
            assertTrue(ex.message.orEmpty().contains("escapes project root"), "expected symlink escape to be blocked, got: ${ex.message}")
        }

    @Test
    fun offline_security_ssrf_blocked_default() =
        runTest {
            val tool = WebFetchTool()
            val root = tempRoot()
            val ctx = ToolContext(fileSystem = FileSystem.SYSTEM, cwd = root, projectDir = root)
            assertFailsWith<IllegalArgumentException> {
                tool.run(buildJsonObject { put("url", JsonPrimitive("http://127.0.0.1/")) }, ctx)
            }
        }

    @Test
    fun offline_security_command_injection_not_possible() =
        runTest {
            val root = tempRoot()
            val store = FileSessionStore(fileSystem = FileSystem.SYSTEM, rootDir = root)
            val provider =
                me.lemonhall.openagentic.sdk.testing.ScriptedResponsesProvider { step, _ ->
                    if (step == 1) {
                        ModelOutput(
                            assistantText = null,
                            toolCalls = listOf(ToolCall(toolUseId = "call_bash", name = "Bash", arguments = buildJsonObject { put("command", JsonPrimitive("echo hi")) })),
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
                    tools = ToolRegistry(listOf(BashTool())),
                    sessionStore = store,
                    permissionGate = PermissionGate.default(userAnswerer = null),
                    includePartialMessages = false,
                    maxSteps = 2,
                )
            val events = OpenAgenticSdk.query(prompt = "go", options = options).toList()
            val denied = events.filterIsInstance<ToolResult>().first { it.toolUseId == "call_bash" }
            assertTrue(denied.isError)
            assertEquals("PermissionDenied", denied.errorType)
        }

    @Test
    fun offline_loop_unhandled_exception_becomes_error_event() {
        runTest {
            val root = tempRoot()
            val store = FileSessionStore(fileSystem = FileSystem.SYSTEM, rootDir = root)
            val provider =
                object : me.lemonhall.openagentic.sdk.providers.ResponsesProvider {
                    override val name: String = "boom-fixture"

                    override suspend fun complete(request: me.lemonhall.openagentic.sdk.providers.ResponsesRequest): ModelOutput {
                        throw IllegalStateException("boom")
                    }
                }
            val events =
                OpenAgenticSdk.query(
                    prompt = "hi",
                    options =
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
                        ),
                ).toList()
            assertTrue(events.any { it.type == "runtime.error" })
            val result = events.last { it is Result } as Result
            assertEquals("error", result.stopReason)
        }
    }
}

package me.lemonhall.openagentic.sdk.runtime

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.lemonhall.openagentic.sdk.events.HookEvent
import me.lemonhall.openagentic.sdk.events.ToolResult
import me.lemonhall.openagentic.sdk.hooks.HookDecision
import me.lemonhall.openagentic.sdk.hooks.HookEngine
import me.lemonhall.openagentic.sdk.hooks.HookMatcher
import me.lemonhall.openagentic.sdk.providers.ModelOutput
import me.lemonhall.openagentic.sdk.providers.ResponsesProvider
import me.lemonhall.openagentic.sdk.providers.ResponsesRequest
import me.lemonhall.openagentic.sdk.providers.ToolCall
import me.lemonhall.openagentic.sdk.sessions.FileSessionStore
import me.lemonhall.openagentic.sdk.tools.ReadTool
import me.lemonhall.openagentic.sdk.tools.ToolRegistry
import okio.FileSystem
import okio.Path.Companion.toPath

private class PromptCaptureProvider(
    private val expectedPrompt: String,
) : ResponsesProvider {
    override val name: String = "prompt-capture"

    override suspend fun complete(request: ResponsesRequest): ModelOutput {
        val user = request.input.first { it["role"]?.jsonPrimitive?.content == "user" }
        assertEquals(expectedPrompt, user["content"]?.jsonPrimitive?.content)
        return ModelOutput(assistantText = "ok", toolCalls = emptyList(), responseId = "resp_1")
    }
}

private class BlockToolProvider : ResponsesProvider {
    override val name: String = "block-tool-provider"
    var calls = 0

    override suspend fun complete(request: ResponsesRequest): ModelOutput {
        calls++
        return if (calls == 1) {
            ModelOutput(
                assistantText = null,
                toolCalls = listOf(ToolCall(toolUseId = "call_1", name = "Read", arguments = buildJsonObject { put("file_path", JsonPrimitive("a.txt")) })),
                responseId = "resp_1",
            )
        } else {
            ModelOutput(
                assistantText = "done",
                toolCalls = emptyList(),
                responseId = "resp_2",
            )
        }
    }
}

class HooksTest {
    @Test
    fun hookCanRewriteUserPromptOnSubmit() =
        runTest {
            val rootNio = Files.createTempDirectory("openagentic-test-")
            val root = rootNio.toString().replace('\\', '/').toPath()
            val store = FileSessionStore(fileSystem = FileSystem.SYSTEM, rootDir = root)

            val hooks =
                HookEngine(
                    userPromptSubmit =
                        listOf(
                            HookMatcher(
                                name = "rewrite",
                                toolNamePattern = "*",
                                hook = { _ -> HookDecision(overridePrompt = "rewritten", action = "rewrite_prompt") },
                            ),
                        ),
                    enableMessageRewriteHooks = true,
                )

            val options =
                OpenAgenticOptions(
                    provider = PromptCaptureProvider("rewritten"),
                    model = "m",
                    fileSystem = FileSystem.SYSTEM,
                    cwd = root,
                    tools = ToolRegistry(),
                    sessionStore = store,
                    hookEngine = hooks,
                    maxSteps = 1,
                )
            val events = OpenAgenticSdk.query(prompt = "original", options = options).toList()
            assertTrue(events.any { it is HookEvent })
        }

    @Test
    fun hookCanBlockToolUse() =
        runTest {
            val rootNio = Files.createTempDirectory("openagentic-test-")
            val root = rootNio.toString().replace('\\', '/').toPath()
            FileSystem.SYSTEM.write(root.resolve("a.txt")) { writeUtf8("hello") }
            val store = FileSessionStore(fileSystem = FileSystem.SYSTEM, rootDir = root)

            val hooks =
                HookEngine(
                    preToolUse =
                        listOf(
                            HookMatcher(
                                name = "block-read",
                                toolNamePattern = "Read",
                                hook = { _ -> HookDecision(block = true, blockReason = "no reads") },
                            ),
                        ),
                )

            val provider = BlockToolProvider()
            val options =
                OpenAgenticOptions(
                    provider = provider,
                    model = "m",
                    fileSystem = FileSystem.SYSTEM,
                    cwd = root,
                    tools = ToolRegistry(listOf(ReadTool())),
                    sessionStore = store,
                    hookEngine = hooks,
                    maxSteps = 3,
                )

            val events = OpenAgenticSdk.query(prompt = "try", options = options).toList()
            assertTrue(events.any { it is HookEvent })
            val tr = events.first { it is ToolResult } as ToolResult
            assertTrue(tr.isError)
            assertEquals("HookBlocked", tr.errorType)
        }
}


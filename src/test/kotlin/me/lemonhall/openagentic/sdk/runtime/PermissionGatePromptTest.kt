package me.lemonhall.openagentic.sdk.runtime

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import me.lemonhall.openagentic.sdk.events.ToolResult
import me.lemonhall.openagentic.sdk.events.UserQuestion
import me.lemonhall.openagentic.sdk.permissions.PermissionGate
import me.lemonhall.openagentic.sdk.permissions.UserAnswerer
import me.lemonhall.openagentic.sdk.providers.ModelOutput
import me.lemonhall.openagentic.sdk.providers.ResponsesProvider
import me.lemonhall.openagentic.sdk.providers.ResponsesRequest
import me.lemonhall.openagentic.sdk.providers.ToolCall
import me.lemonhall.openagentic.sdk.sessions.FileSessionStore
import me.lemonhall.openagentic.sdk.tools.Tool
import me.lemonhall.openagentic.sdk.tools.ToolContext
import me.lemonhall.openagentic.sdk.tools.ToolInput
import me.lemonhall.openagentic.sdk.tools.ToolOutput
import me.lemonhall.openagentic.sdk.tools.ToolRegistry
import okio.FileSystem
import okio.Path.Companion.toPath

private class PromptProvider : ResponsesProvider {
    override val name: String = "prompt-provider"
    var calls = 0

    override suspend fun complete(request: ResponsesRequest): ModelOutput {
        calls++
        return if (calls == 1) {
            ModelOutput(
                assistantText = null,
                toolCalls = listOf(ToolCall(toolUseId = "call_1", name = "Read", arguments = buildJsonObject { })),
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

class PermissionGatePromptTest {
    @Test
    fun promptDenyProducesUserQuestionAndPermissionDenied() =
        runTest {
            val rootNio = Files.createTempDirectory("openagentic-test-")
            val root = rootNio.toString().replace('\\', '/').toPath()
            val store = FileSessionStore(fileSystem = FileSystem.SYSTEM, rootDir = root)

            val tool =
                object : Tool {
                    override val name: String = "Read"
                    override val description: String = "read"

                    override suspend fun run(input: ToolInput, ctx: ToolContext): ToolOutput {
                        throw IllegalStateException("tool should not run when permission denied")
                    }
                }

            val answerer = UserAnswerer { JsonPrimitive("no") }
            val gate = PermissionGate.prompt(userAnswerer = answerer)

            val provider = PromptProvider()
            val options =
                OpenAgenticOptions(
                    provider = provider,
                    model = "m",
                    fileSystem = FileSystem.SYSTEM,
                    cwd = root,
                    tools = ToolRegistry(listOf(tool)),
                    permissionGate = gate,
                    sessionStore = store,
                    maxSteps = 3,
                )

            val events = OpenAgenticSdk.query(prompt = "try", options = options).toList()

            assertEquals(2, provider.calls)
            assertTrue(events.any { it is UserQuestion })
            val denied = events.first { it is ToolResult } as ToolResult
            assertTrue(denied.isError)
            assertEquals("PermissionDenied", denied.errorType)
        }
}

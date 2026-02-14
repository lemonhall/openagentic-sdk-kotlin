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
import me.lemonhall.openagentic.sdk.providers.ModelOutput
import me.lemonhall.openagentic.sdk.providers.ResponsesProvider
import me.lemonhall.openagentic.sdk.providers.ResponsesRequest
import me.lemonhall.openagentic.sdk.providers.ToolCall
import me.lemonhall.openagentic.sdk.sessions.FileSessionStore
import me.lemonhall.openagentic.sdk.tools.ToolRegistry
import okio.FileSystem
import okio.Path.Companion.toPath

private class ToolNotAllowedProvider : ResponsesProvider {
    override val name: String = "tool-not-allowed"

    override suspend fun complete(request: ResponsesRequest): ModelOutput {
        if (request.previousResponseId == null) {
            return ModelOutput(
                assistantText = null,
                toolCalls = listOf(ToolCall(toolUseId = "call_write_1", name = "Write", arguments = buildJsonObject { put("file_path", JsonPrimitive("a.txt")) })),
                usage = null,
                responseId = "resp_1",
                providerMetadata = null,
            )
        }
        return ModelOutput(
            assistantText = "done",
            toolCalls = emptyList(),
            usage = null,
            responseId = "resp_2",
            providerMetadata = null,
        )
    }
}

class ToolNotAllowedTest {
    @Test
    fun toolNotAllowedProducesErrorToolResult() =
        runTest {
            val rootNio = Files.createTempDirectory("openagentic-test-")
            val root = rootNio.toString().replace('\\', '/').toPath()
            val store = FileSessionStore(fileSystem = FileSystem.SYSTEM, rootDir = root)

            val options =
                OpenAgenticOptions(
                    provider = ToolNotAllowedProvider(),
                    model = "fake",
                    apiKey = "x",
                    fileSystem = FileSystem.SYSTEM,
                    cwd = root,
                    tools = ToolRegistry(),
                    sessionStore = store,
                    allowedTools = setOf("Read"),
                    includePartialMessages = false,
                    maxSteps = 2,
                )

            val events = OpenAgenticSdk.query(prompt = "try write", options = options).toList()
            val denied = events.first { it is ToolResult } as ToolResult
            assertTrue(denied.isError)
            assertEquals("ToolNotAllowed", denied.errorType)
        }
}

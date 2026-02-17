package me.lemonhall.openagentic.sdk.runtime

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.lemonhall.openagentic.sdk.providers.ModelOutput
import me.lemonhall.openagentic.sdk.providers.ResponsesProvider
import me.lemonhall.openagentic.sdk.providers.ResponsesRequest
import me.lemonhall.openagentic.sdk.providers.ToolCall
import me.lemonhall.openagentic.sdk.sessions.FileSessionStore
import me.lemonhall.openagentic.sdk.tools.ReadTool
import me.lemonhall.openagentic.sdk.tools.ToolRegistry
import okio.FileSystem
import okio.Path.Companion.toPath

private class TruncationAssertingProvider : ResponsesProvider {
    override val name: String = "fake"

    override suspend fun complete(request: ResponsesRequest): ModelOutput {
        if (request.previousResponseId == null) {
            return ModelOutput(
                assistantText = null,
                toolCalls =
                    listOf(
                        ToolCall(
                            toolUseId = "call_1",
                            name = "Read",
                            arguments = buildJsonObject { put("file_path", JsonPrimitive("a.txt")) },
                        ),
                    ),
                usage = buildJsonObject { put("total_tokens", JsonPrimitive(1)) },
                responseId = "resp_1",
                providerMetadata = null,
            )
        }

        val toolItem =
            request.input.first { it["type"]?.jsonPrimitive?.content == "function_call_output" }
        val outputStr = toolItem["output"]?.jsonPrimitive?.content.orEmpty()
        assertTrue(outputStr.isNotEmpty())
        assertTrue(outputStr.length <= 8_000, "tool output for model should be bounded (got ${outputStr.length} chars)")

        val parsed: JsonElement = Json.parseToJsonElement(outputStr)
        val obj = (parsed as? JsonObject) ?: error("expected tool output json object")
        val isWrapper = obj["_openagentic_truncated"]?.jsonPrimitive?.content == "true"
        if (isWrapper) {
            val preview = obj["preview"]?.jsonPrimitive?.content.orEmpty()
            assertTrue(preview.contains("chars truncated"), "wrapper preview should contain truncation marker")
        } else {
            val content = obj["content"]?.jsonPrimitive?.content.orEmpty()
            assertTrue(content.isNotEmpty())
            assertTrue(content.length <= 4_000, "string fields should be truncated (got ${content.length} chars)")
            assertTrue(content.contains("chars truncated"), "content should contain truncation marker")
        }

        return ModelOutput(
            assistantText = "OK",
            toolCalls = emptyList(),
            usage = buildJsonObject { put("total_tokens", JsonPrimitive(2)) },
            responseId = "resp_2",
            providerMetadata = null,
        )
    }
}

class ToolOutputTruncationTest {
    @Test
    fun runtimeBoundsToolOutputBeforeSendingToProvider() =
        runTest {
            val rootNio = Files.createTempDirectory("openagentic-test-")
            val root = rootNio.toString().replace('\\', '/').toPath()
            val large = "x".repeat(20_000)
            FileSystem.SYSTEM.write(root.resolve("a.txt")) { writeUtf8(large) }

            val store = FileSessionStore(fileSystem = FileSystem.SYSTEM, rootDir = root)
            val tools = ToolRegistry(listOf(ReadTool()))
            val provider = TruncationAssertingProvider()
            val options =
                OpenAgenticOptions(
                    provider = provider,
                    model = "fake",
                    apiKey = "x",
                    fileSystem = FileSystem.SYSTEM,
                    cwd = root,
                    tools = tools,
                    sessionStore = store,
                    includePartialMessages = false,
                )

            val events = OpenAgenticSdk.query(prompt = "read file", options = options).toList()
            assertTrue(events.any { it.type == "result" })
        }
}


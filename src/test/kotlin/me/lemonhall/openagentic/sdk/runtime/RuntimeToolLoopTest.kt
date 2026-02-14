package me.lemonhall.openagentic.sdk.runtime

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.lemonhall.openagentic.sdk.events.Event
import me.lemonhall.openagentic.sdk.events.Result
import me.lemonhall.openagentic.sdk.providers.ModelOutput
import me.lemonhall.openagentic.sdk.providers.ResponsesProvider
import me.lemonhall.openagentic.sdk.providers.ResponsesRequest
import me.lemonhall.openagentic.sdk.providers.ToolCall
import me.lemonhall.openagentic.sdk.sessions.FileSessionStore
import me.lemonhall.openagentic.sdk.tools.ReadTool
import me.lemonhall.openagentic.sdk.tools.ToolRegistry
import okio.FileSystem
import okio.Path.Companion.toPath

private class FakeProvider : ResponsesProvider {
    override val name: String = "fake"
    val calls = mutableListOf<ProviderCall>()

    override suspend fun complete(request: ResponsesRequest): ModelOutput {
        calls.add(ProviderCall(model = request.model, input = request.input, previousResponseId = request.previousResponseId))

        if (request.previousResponseId == null) {
            return ModelOutput(
                assistantText = null,
                toolCalls = listOf(ToolCall(toolUseId = "call_1", name = "Read", arguments = buildJsonObject { put("file_path", JsonPrimitive("a.txt")) })),
                usage = buildJsonObject { put("total_tokens", JsonPrimitive(1)) },
                responseId = "resp_1",
                providerMetadata = null,
            )
        }

        val toolItem =
            request.input.first { it["type"]?.jsonPrimitive?.content == "function_call_output" }
        assertEquals("call_1", toolItem["call_id"]?.jsonPrimitive?.content)
        val output = toolItem["output"]?.jsonPrimitive?.content ?: "{}"
        val data = Json.decodeFromString(JsonObject.serializer(), output)
        val content = data["content"]?.jsonPrimitive?.content ?: ""

        return ModelOutput(
            assistantText = "OK: $content",
            toolCalls = emptyList(),
            usage = buildJsonObject { put("total_tokens", JsonPrimitive(2)) },
            responseId = "resp_2",
            providerMetadata = null,
        )
    }
}

private data class ProviderCall(
    val model: String,
    val input: List<JsonObject>,
    val previousResponseId: String?,
)

class RuntimeToolLoopTest {
    @Test
    fun runtimeRunsToolAndReturnsResult() =
        runTest {
            val rootNio = Files.createTempDirectory("openagentic-test-")
            val root = rootNio.toString().replace('\\', '/').toPath()
            FileSystem.SYSTEM.write(root.resolve("a.txt")) {
                writeUtf8("hello")
            }

            val store = FileSessionStore(fileSystem = FileSystem.SYSTEM, rootDir = root)
            val tools = ToolRegistry(listOf(ReadTool()))
            val provider = FakeProvider()
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

            val events: List<Event> = OpenAgenticSdk.query(prompt = "read file", options = options).toList()

            assertEquals(2, provider.calls.size)
            assertNull(provider.calls[0].previousResponseId)
            assertEquals("resp_1", provider.calls[1].previousResponseId)

            val types = events.map { it.type }.toSet()
            assertTrue("tool.use" in types)
            assertTrue("tool.result" in types)
            assertTrue("result" in types)

            val result = events.last { it is Result } as Result
            assertTrue(result.finalText.startsWith("OK:"))
        }
}

package me.lemonhall.openagentic.sdk.runtime

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.lemonhall.openagentic.sdk.events.Event
import me.lemonhall.openagentic.sdk.events.Result
import me.lemonhall.openagentic.sdk.providers.LegacyProvider
import me.lemonhall.openagentic.sdk.providers.LegacyRequest
import me.lemonhall.openagentic.sdk.providers.ModelOutput
import me.lemonhall.openagentic.sdk.providers.ToolCall
import me.lemonhall.openagentic.sdk.sessions.FileSessionStore
import me.lemonhall.openagentic.sdk.tools.ReadTool
import me.lemonhall.openagentic.sdk.tools.ToolRegistry
import okio.FileSystem
import okio.Path.Companion.toPath

private class FakeLegacyProvider : LegacyProvider {
    override val name: String = "fake-legacy"
    val calls = mutableListOf<LegacyRequest>()
    private var n = 0

    override suspend fun complete(request: LegacyRequest): ModelOutput {
        calls.add(request)
        n += 1
        if (n == 1) {
            return ModelOutput(
                assistantText = null,
                toolCalls = listOf(ToolCall(toolUseId = "call_1", name = "Read", arguments = buildJsonObject { put("file_path", JsonPrimitive("a.txt")) })),
                responseId = null,
            )
        }

        val toolMsg = request.messages.first { it["role"]?.jsonPrimitive?.content == "tool" }
        assertEquals("call_1", toolMsg["tool_call_id"]?.jsonPrimitive?.content)
        val content = toolMsg["content"]?.jsonPrimitive?.content ?: "{}"
        val obj = Json.decodeFromString(JsonObject.serializer(), content)
        val readContent = obj["content"]?.jsonPrimitive?.content.orEmpty()
        return ModelOutput(
            assistantText = "OK: $readContent",
            toolCalls = emptyList(),
            responseId = null,
        )
    }
}

class LegacyProviderToolLoopTest {
    @Test
    fun runtimeRunsToolLoopWithLegacyProvider() =
        runTest {
            val rootNio = Files.createTempDirectory("openagentic-test-")
            val root = rootNio.toString().replace('\\', '/').toPath()
            FileSystem.SYSTEM.write(root.resolve("a.txt")) { writeUtf8("hello") }

            val store = FileSessionStore(fileSystem = FileSystem.SYSTEM, rootDir = root)
            val tools = ToolRegistry(listOf(ReadTool()))
            val provider = FakeLegacyProvider()
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
                    maxSteps = 5,
                )

            val events: List<Event> = OpenAgenticSdk.query(prompt = "read file", options = options).toList()
            assertEquals(2, provider.calls.size)
            assertTrue(provider.calls[0].tools.isNotEmpty())
            val result = events.last { it is Result } as Result
            assertTrue(result.finalText.startsWith("OK:"))
        }
}


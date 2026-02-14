package me.lemonhall.openagentic.sdk.runtime

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.lemonhall.openagentic.sdk.events.ToolResult
import me.lemonhall.openagentic.sdk.events.UserQuestion
import me.lemonhall.openagentic.sdk.permissions.PermissionGate
import me.lemonhall.openagentic.sdk.permissions.UserAnswerer
import me.lemonhall.openagentic.sdk.providers.ModelOutput
import me.lemonhall.openagentic.sdk.providers.ResponsesProvider
import me.lemonhall.openagentic.sdk.providers.ResponsesRequest
import me.lemonhall.openagentic.sdk.providers.ToolCall
import me.lemonhall.openagentic.sdk.sessions.FileSessionStore
import me.lemonhall.openagentic.sdk.tools.ToolRegistry
import okio.FileSystem
import okio.Path.Companion.toPath

private class AskProvider : ResponsesProvider {
    override val name: String = "ask-provider"
    var calls = 0

    override suspend fun complete(request: ResponsesRequest): ModelOutput {
        calls++
        return if (calls == 1) {
            ModelOutput(
                assistantText = null,
                toolCalls =
                    listOf(
                        ToolCall(
                            toolUseId = "call_ask_1",
                            name = "AskUserQuestion",
                            arguments =
                                buildJsonObject {
                                    put("question", JsonPrimitive("Pick one"))
                                    put("choices", kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("a"), JsonPrimitive("b"))))
                                },
                        ),
                    ),
                responseId = "resp_1",
            )
        } else {
            ModelOutput(
                assistantText = "ok",
                toolCalls = emptyList(),
                responseId = "resp_2",
            )
        }
    }
}

class AskUserQuestionToolTest {
    @Test
    fun askUserQuestionEmitsUserQuestionAndToolResult() =
        runTest {
            val rootNio = Files.createTempDirectory("openagentic-test-")
            val root = rootNio.toString().replace('\\', '/').toPath()
            val store = FileSessionStore(fileSystem = FileSystem.SYSTEM, rootDir = root)

            val answerer = UserAnswerer { JsonPrimitive("a") }
            val gate = PermissionGate.bypass(userAnswerer = answerer)

            val provider = AskProvider()
            val options =
                OpenAgenticOptions(
                    provider = provider,
                    model = "m",
                    fileSystem = FileSystem.SYSTEM,
                    cwd = root,
                    tools = ToolRegistry(),
                    permissionGate = gate,
                    sessionStore = store,
                    maxSteps = 3,
                )

            val events = OpenAgenticSdk.query(prompt = "ask", options = options).toList()

            assertEquals(2, provider.calls)
            assertTrue(events.any { it is UserQuestion })
            val tr = events.first { it is ToolResult } as ToolResult
            assertTrue(!tr.isError)

            val out = tr.output?.jsonObject
            assertNotNull(out)
            val answers = out["answers"]?.jsonObject
            assertNotNull(answers)
            assertEquals("a", answers["Pick one"]?.jsonPrimitive?.content)
        }
}

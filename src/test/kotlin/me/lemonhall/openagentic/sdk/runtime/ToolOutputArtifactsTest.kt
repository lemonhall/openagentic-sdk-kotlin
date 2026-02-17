package me.lemonhall.openagentic.sdk.runtime

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
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
import me.lemonhall.openagentic.sdk.tools.Tool
import me.lemonhall.openagentic.sdk.tools.ToolContext
import me.lemonhall.openagentic.sdk.tools.ToolInput
import me.lemonhall.openagentic.sdk.tools.ToolOutput
import me.lemonhall.openagentic.sdk.tools.ToolRegistry
import okio.FileSystem
import okio.Path.Companion.toPath

private class BigOutputTool2 : Tool {
    override val name: String = "BigOut"
    override val description: String = "Return a very large output."

    override suspend fun run(input: ToolInput, ctx: ToolContext): ToolOutput {
        return ToolOutput.Json(JsonPrimitive("x".repeat(120_000)))
    }
}

private class ArtifactAssertingProvider(
    private val fs: FileSystem,
) : ResponsesProvider {
    override val name: String = "fake-artifacts"

    override suspend fun complete(request: ResponsesRequest): ModelOutput {
        if (request.previousResponseId == null) {
            return ModelOutput(
                assistantText = null,
                toolCalls = listOf(ToolCall(toolUseId = "call_1", name = "BigOut", arguments = buildJsonObject { })),
                usage = buildJsonObject { put("total_tokens", JsonPrimitive(1)) },
                responseId = "resp_1",
                providerMetadata = null,
            )
        }

        val toolItem = request.input.first { it["type"]?.jsonPrimitive?.content == "function_call_output" }
        val outputStr = toolItem["output"]?.jsonPrimitive?.content.orEmpty()
        val parsed = Json.parseToJsonElement(outputStr).jsonObject

        assertTrue(parsed["_openagentic_truncated"]?.jsonPrimitive?.content == "true")
        val artifactPath = parsed["artifact_path"]?.jsonPrimitive?.content.orEmpty()
        assertTrue(artifactPath.isNotBlank(), "expected artifact_path to be present")

        val p = artifactPath.toPath()
        assertTrue(fs.exists(p), "artifact file must exist: $artifactPath")

        val full = fs.read(p) { readUtf8() }
        assertTrue(full.length > 100_000, "artifact should contain full output (got ${full.length} chars)")

        return ModelOutput(
            assistantText = "OK",
            toolCalls = emptyList(),
            usage = buildJsonObject { put("total_tokens", JsonPrimitive(2)) },
            responseId = "resp_2",
            providerMetadata = null,
        )
    }
}

class ToolOutputArtifactsTest {
    @Test
    fun largeToolOutputIsExternalizedToToolOutputDir() =
        runTest {
            val rootNio = Files.createTempDirectory("openagentic-test-")
            val root = rootNio.toString().replace('\\', '/').toPath()

            val store = FileSessionStore(fileSystem = FileSystem.SYSTEM, rootDir = root)
            val tools = ToolRegistry(listOf(BigOutputTool2()))
            val provider = ArtifactAssertingProvider(fs = FileSystem.SYSTEM)
            val options =
                OpenAgenticOptions(
                    provider = provider,
                    model = "fake",
                    apiKey = "x",
                    fileSystem = FileSystem.SYSTEM,
                    cwd = root,
                    projectDir = root,
                    tools = tools,
                    sessionStore = store,
                    toolOutputArtifacts = ToolOutputArtifactsOptions(enabled = true, maxBytes = 1024),
                    includePartialMessages = false,
                    maxSteps = 5,
                )

            val events = OpenAgenticSdk.query(prompt = "go", options = options).toList()
            assertTrue(events.any { it.type == "result" })

            val dir = root.resolve("tool-output")
            assertTrue(FileSystem.SYSTEM.exists(dir), "tool-output directory must exist")
            val entries = FileSystem.SYSTEM.list(dir)
            assertTrue(entries.isNotEmpty(), "tool-output should contain at least one artifact file")
        }
}


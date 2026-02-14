package me.lemonhall.openagentic.sdk.runtime

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import me.lemonhall.openagentic.sdk.compaction.CompactionOptions
import me.lemonhall.openagentic.sdk.compaction.TOOL_OUTPUT_PLACEHOLDER
import me.lemonhall.openagentic.sdk.permissions.PermissionGate
import me.lemonhall.openagentic.sdk.providers.LegacyProvider
import me.lemonhall.openagentic.sdk.providers.LegacyRequest
import me.lemonhall.openagentic.sdk.providers.ModelOutput
import me.lemonhall.openagentic.sdk.providers.ToolCall
import me.lemonhall.openagentic.sdk.sessions.FileSessionStore
import me.lemonhall.openagentic.sdk.tools.Tool
import me.lemonhall.openagentic.sdk.tools.ToolContext
import me.lemonhall.openagentic.sdk.tools.ToolInput
import me.lemonhall.openagentic.sdk.tools.ToolOutput
import me.lemonhall.openagentic.sdk.tools.ToolRegistry
import okio.FileSystem
import okio.Path.Companion.toPath

private class BigOutputTool : Tool {
    override val name: String = "Big"
    override val description: String = "Return a big output."

    override suspend fun run(input: ToolInput, ctx: ToolContext): ToolOutput {
        return ToolOutput.Json(JsonPrimitive("x".repeat(50_000)))
    }
}

private class PruneAssertingLegacyProvider : LegacyProvider {
    override val name: String = "legacy-prune-assert"

    private var turn3SecondCallChecked = false

    override suspend fun complete(request: LegacyRequest): ModelOutput {
        val userText =
            request.messages.asReversed()
                .firstOrNull { (it["role"] as? JsonPrimitive)?.content == "user" }
                ?.get("content")
                ?.let { (it as? JsonPrimitive)?.content }
                .orEmpty()

        fun hasToolResult(id: String): Boolean {
            return request.messages.any {
                (it["role"] as? JsonPrimitive)?.content == "tool" &&
                    (it["tool_call_id"] as? JsonPrimitive)?.content == id
            }
        }

        fun toolResultContent(id: String): String? {
            return request.messages.firstOrNull {
                (it["role"] as? JsonPrimitive)?.content == "tool" &&
                    (it["tool_call_id"] as? JsonPrimitive)?.content == id
            }?.get("content")?.let { (it as? JsonPrimitive)?.content }
        }

        if (userText.contains("turn1")) {
            if (!hasToolResult("1")) {
                return ModelOutput(
                    assistantText = null,
                    toolCalls = listOf(ToolCall(toolUseId = "1", name = "Big", arguments = buildJsonObject { })),
                    usage = null,
                    responseId = null,
                    providerMetadata = null,
                )
            }
            return ModelOutput(assistantText = "ok1", toolCalls = emptyList(), usage = null)
        }

        if (userText.contains("turn2")) {
            if (!hasToolResult("2")) {
                return ModelOutput(
                    assistantText = null,
                    toolCalls = listOf(ToolCall(toolUseId = "2", name = "Big", arguments = buildJsonObject { })),
                    usage = null,
                    responseId = null,
                    providerMetadata = null,
                )
            }
            return ModelOutput(assistantText = "ok2", toolCalls = emptyList(), usage = null)
        }

        if (userText.contains("turn3")) {
            if (!hasToolResult("3")) {
                return ModelOutput(
                    assistantText = null,
                    toolCalls = listOf(ToolCall(toolUseId = "3", name = "Big", arguments = buildJsonObject { })),
                    usage = null,
                    responseId = null,
                    providerMetadata = null,
                )
            }
            if (!turn3SecondCallChecked) {
                turn3SecondCallChecked = true
                val c1 = toolResultContent("1").orEmpty()
                assertTrue(c1 == TOOL_OUTPUT_PLACEHOLDER, "expected tool.result(1) placeholder after pruning")
            }
            return ModelOutput(assistantText = "ok3", toolCalls = emptyList(), usage = null)
        }

        return ModelOutput(assistantText = "noop", toolCalls = emptyList(), usage = null)
    }
}

class ToolOutputPruningPlaceholderTest {
    @Test
    fun toolOutputIsReplacedWithPlaceholderAfterCompactionMark() =
        runTest {
            val rootNio = Files.createTempDirectory("openagentic-test-")
            val root = rootNio.toString().replace('\\', '/').toPath()
            val store = FileSessionStore(fileSystem = FileSystem.SYSTEM, rootDir = root)

            val provider = PruneAssertingLegacyProvider()
            val tools = ToolRegistry(listOf(BigOutputTool()))

            val baseOptions =
                OpenAgenticOptions(
                    provider = provider,
                    model = "m",
                    apiKey = "x",
                    fileSystem = FileSystem.SYSTEM,
                    cwd = root,
                    projectDir = root,
                    tools = tools,
                    sessionStore = store,
                    permissionGate = PermissionGate.bypass(),
                    maxSteps = 10,
                    compaction =
                        CompactionOptions(
                            auto = false,
                            prune = true,
                            protectToolOutputTokens = 0,
                            minPruneTokens = 0,
                        ),
                )

            val r1 = OpenAgenticSdk.run(prompt = "turn1", options = baseOptions)
            val r2 = OpenAgenticSdk.run(prompt = "turn2", options = baseOptions.copy(resumeSessionId = r1.sessionId))
            val r3 = OpenAgenticSdk.run(prompt = "turn3", options = baseOptions.copy(resumeSessionId = r1.sessionId))
            assertTrue(r3.finalText.contains("ok3"))
        }
}

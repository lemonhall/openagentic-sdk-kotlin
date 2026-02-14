package me.lemonhall.openagentic.sdk.runtime

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import me.lemonhall.openagentic.sdk.compaction.COMPACTION_SYSTEM_PROMPT
import me.lemonhall.openagentic.sdk.compaction.CompactionOptions
import me.lemonhall.openagentic.sdk.events.AssistantMessage
import me.lemonhall.openagentic.sdk.events.UserCompaction
import me.lemonhall.openagentic.sdk.providers.LegacyProvider
import me.lemonhall.openagentic.sdk.providers.LegacyRequest
import me.lemonhall.openagentic.sdk.providers.ModelOutput
import me.lemonhall.openagentic.sdk.sessions.FileSessionStore
import me.lemonhall.openagentic.sdk.tools.ToolRegistry
import okio.FileSystem
import okio.Path.Companion.toPath

private class LegacyCompactionFixtureProvider : LegacyProvider {
    override val name: String = "legacy-compaction-fixture"

    private var normalCalls = 0

    override suspend fun complete(request: LegacyRequest): ModelOutput {
        val sys0 = request.messages.firstOrNull()
        val sysRole = (sys0?.get("role") as? JsonPrimitive)?.content
        val sysContent = (sys0?.get("content") as? JsonPrimitive)?.content
        if (sysRole == "system" && sysContent?.trim() == COMPACTION_SYSTEM_PROMPT.trim()) {
            return ModelOutput(
                assistantText = "SUMMARY_OK",
                toolCalls = emptyList(),
                usage = buildJsonObject { put("total_tokens", JsonPrimitive(1)) },
                responseId = null,
                providerMetadata = null,
            )
        }

        normalCalls += 1
        return when (normalCalls) {
            1 -> {
                // Trigger overflow at boundary.
                ModelOutput(
                    assistantText = "FIRST",
                    toolCalls = emptyList(),
                    usage = buildJsonObject { put("total_tokens", JsonPrimitive(90)) },
                    responseId = null,
                    providerMetadata = null,
                )
            }
            else -> {
                ModelOutput(
                    assistantText = "AFTER_COMPACTION",
                    toolCalls = emptyList(),
                    usage = buildJsonObject { put("total_tokens", JsonPrimitive(1)) },
                    responseId = null,
                    providerMetadata = null,
                )
            }
        }
    }
}

class CompactionOverflowLegacyTest {
    @Test
    fun overflowTriggersCompactionPassAndContinues() =
        runTest {
            val rootNio = Files.createTempDirectory("openagentic-test-")
            val root = rootNio.toString().replace('\\', '/').toPath()
            val store = FileSessionStore(fileSystem = FileSystem.SYSTEM, rootDir = root)

            val options =
                OpenAgenticOptions(
                    provider = LegacyCompactionFixtureProvider(),
                    model = "m",
                    apiKey = "x",
                    fileSystem = FileSystem.SYSTEM,
                    cwd = root,
                    projectDir = root,
                    tools = ToolRegistry(),
                    sessionStore = store,
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
            assertTrue(events.any { it is AssistantMessage && it.isSummary && it.text.contains("SUMMARY_OK") })
            val lastAssistant = events.filterIsInstance<AssistantMessage>().last()
            assertEquals("AFTER_COMPACTION", lastAssistant.text)
        }
}


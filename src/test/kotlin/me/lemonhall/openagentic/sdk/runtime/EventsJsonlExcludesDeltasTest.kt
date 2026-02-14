package me.lemonhall.openagentic.sdk.runtime

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import me.lemonhall.openagentic.sdk.providers.ModelOutput
import me.lemonhall.openagentic.sdk.providers.ResponsesRequest
import me.lemonhall.openagentic.sdk.providers.StreamingResponsesProvider
import me.lemonhall.openagentic.sdk.sessions.FileSessionStore
import okio.FileSystem
import okio.Path.Companion.toPath

private class StreamingFixtureProvider : StreamingResponsesProvider {
    override val name: String = "streaming-fixture"

    override suspend fun complete(request: ResponsesRequest): ModelOutput {
        return ModelOutput(
            assistantText = null,
            toolCalls = emptyList(),
            usage = null,
            responseId = "resp_1",
            providerMetadata = null,
        )
    }

    override fun stream(request: ResponsesRequest): Flow<ProviderStreamEvent> =
        flow {
            repeat(50) {
                emit(ProviderStreamEvent.TextDelta("x"))
            }
            emit(
                ProviderStreamEvent.Completed(
                    ModelOutput(
                        assistantText = "ok",
                        toolCalls = emptyList(),
                        usage = null,
                        responseId = "resp_1",
                        providerMetadata = null,
                    ),
                ),
            )
        }
}

class EventsJsonlExcludesDeltasTest {
    @Test
    fun assistantDeltasAreNotPersisted() =
        runTest {
            val rootNio = Files.createTempDirectory("openagentic-test-")
            val root = rootNio.toString().replace('\\', '/').toPath()
            val store = FileSessionStore(fileSystem = FileSystem.SYSTEM, rootDir = root)
            val options =
                OpenAgenticOptions(
                    provider = StreamingFixtureProvider(),
                    model = "m",
                    apiKey = "x",
                    fileSystem = FileSystem.SYSTEM,
                    cwd = root,
                    tools = me.lemonhall.openagentic.sdk.tools.ToolRegistry(),
                    sessionStore = store,
                    includePartialMessages = true,
                    maxSteps = 2,
                )

            val r = OpenAgenticSdk.run(prompt = "hi", options = options)
            assertNotNull(r.sessionId)

            val eventsPath = root.resolve("sessions").resolve(r.sessionId).resolve("events.jsonl")
            val text = FileSystem.SYSTEM.read(eventsPath) { readUtf8() }
            assertTrue(!text.contains("\"type\":\"assistant.delta\""))
        }
}

package me.lemonhall.openagentic.sdk.runtime

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import me.lemonhall.openagentic.sdk.providers.ModelOutput
import me.lemonhall.openagentic.sdk.providers.ResponsesProvider
import me.lemonhall.openagentic.sdk.providers.ResponsesRequest
import me.lemonhall.openagentic.sdk.sessions.FileSessionStore
import me.lemonhall.openagentic.sdk.tools.ToolRegistry
import okio.FileSystem
import okio.Path.Companion.toPath

private class FirstRunProvider : ResponsesProvider {
    override val name: String = "first-run"

    override suspend fun complete(request: ResponsesRequest): ModelOutput {
        return ModelOutput(
            assistantText = "hi",
            toolCalls = emptyList(),
            responseId = "resp_1",
        )
    }
}

private class ResumeProvider : ResponsesProvider {
    override val name: String = "resume-run"
    var seenPrevious: String? = null

    override suspend fun complete(request: ResponsesRequest): ModelOutput {
        seenPrevious = request.previousResponseId
        return ModelOutput(
            assistantText = "hi2",
            toolCalls = emptyList(),
            responseId = "resp_2",
        )
    }
}

class SessionsResumeTest {
    @Test
    fun resumeUsesPreviousResponseIdAndAppendsEvents() =
        runTest {
            val rootNio = Files.createTempDirectory("openagentic-test-")
            val root = rootNio.toString().replace('\\', '/').toPath()
            val store = FileSessionStore(fileSystem = FileSystem.SYSTEM, rootDir = root)

            val options1 =
                OpenAgenticOptions(
                    provider = FirstRunProvider(),
                    model = "m",
                    fileSystem = FileSystem.SYSTEM,
                    cwd = root,
                    tools = ToolRegistry(),
                    sessionStore = store,
                    maxSteps = 1,
                )
            val r1 = OpenAgenticSdk.run(prompt = "one", options = options1)
            val sid = r1.sessionId
            assertTrue(sid.isNotBlank())

            val eventsPath = root.resolve("sessions").resolve(sid).resolve("events.jsonl")
            val beforeLines = FileSystem.SYSTEM.read(eventsPath) { readUtf8() }.lineSequence().filter { it.isNotBlank() }.count()

            val resumeProvider = ResumeProvider()
            val options2 =
                OpenAgenticOptions(
                    provider = resumeProvider,
                    model = "m",
                    fileSystem = FileSystem.SYSTEM,
                    cwd = root,
                    tools = ToolRegistry(),
                    sessionStore = store,
                    resumeSessionId = sid,
                    maxSteps = 1,
                )
            val r2 = OpenAgenticSdk.run(prompt = "two", options = options2)
            assertTrue(r2.sessionId == sid)

            assertEquals("resp_1", resumeProvider.seenPrevious)

            val afterLines = FileSystem.SYSTEM.read(eventsPath) { readUtf8() }.lineSequence().filter { it.isNotBlank() }.count()
            assertTrue(afterLines > beforeLines)
        }
}

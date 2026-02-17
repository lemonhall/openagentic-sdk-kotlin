package me.lemonhall.openagentic.sdk.sessions

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.lemonhall.openagentic.sdk.providers.ModelOutput
import me.lemonhall.openagentic.sdk.providers.ResponsesProvider
import me.lemonhall.openagentic.sdk.providers.ResponsesRequest
import me.lemonhall.openagentic.sdk.runtime.OpenAgenticOptions
import me.lemonhall.openagentic.sdk.runtime.OpenAgenticSdk
import me.lemonhall.openagentic.sdk.tools.ToolRegistry
import okio.FileSystem
import okio.Path.Companion.toPath

private class MetadataTestProvider : ResponsesProvider {
    override val name: String = "metadata-test"

    override suspend fun complete(request: ResponsesRequest): ModelOutput {
        return ModelOutput(
            assistantText = "ok",
            toolCalls = emptyList(),
            responseId = "resp_1",
        )
    }
}

class SessionMetadataTest {
    @Test
    fun createSessionMetadataIsWrittenToMetaJson() =
        runTest {
            val rootNio = Files.createTempDirectory("openagentic-test-")
            val root = rootNio.toString().replace('\\', '/').toPath()
            val store = FileSessionStore(fileSystem = FileSystem.SYSTEM, rootDir = root)

            val options =
                OpenAgenticOptions(
                    provider = MetadataTestProvider(),
                    model = "m",
                    fileSystem = FileSystem.SYSTEM,
                    cwd = root,
                    tools = ToolRegistry(),
                    sessionStore = store,
                    createSessionMetadata = mapOf("kind" to "primary", "x" to "y"),
                    maxSteps = 1,
                )

            val r = OpenAgenticSdk.run(prompt = "hi", options = options)
            val sid = r.sessionId

            val metaPath = root.resolve("sessions").resolve(sid).resolve("meta.json")
            val raw = FileSystem.SYSTEM.read(metaPath) { readUtf8() }.trim()
            val obj = EventJson.json.decodeFromString(JsonObject.serializer(), raw).jsonObject
            val meta = obj["metadata"]?.jsonObject
            assertNotNull(meta)
            assertEquals("primary", meta["kind"]?.jsonPrimitive?.content)
            assertEquals("y", meta["x"]?.jsonPrimitive?.content)
        }
}


package me.lemonhall.openagentic.sdk.tools

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okio.FileSystem
import okio.Path.Companion.toPath

class WebFetchToolTest {
    @Test
    fun webFetchFollowsRedirectsWithValidationPerHop() =
        runTest {
            val rootNio = Files.createTempDirectory("openagentic-test-")
            val root = rootNio.toString().replace('\\', '/').toPath()
            val ctx = ToolContext(fileSystem = FileSystem.SYSTEM, cwd = root, projectDir = root)

            val transport =
                WebFetchTransport { url, _ ->
                    when (url) {
                        "https://example.com/a" -> WebFetchResponse(302, mapOf("location" to "/b"), ByteArray(0))
                        "https://example.com/b" -> WebFetchResponse(200, mapOf("content-type" to "text/plain"), "ok".encodeToByteArray())
                        else -> WebFetchResponse(404, emptyMap(), ByteArray(0))
                    }
                }
            val tool = WebFetchTool(transport = transport)
            val out =
                tool.run(
                    buildJsonObject { put("url", JsonPrimitive("https://example.com/a")) },
                    ctx,
                ) as ToolOutput.Json

            val obj = out.value?.jsonObject
            assertNotNull(obj)
            assertEquals(200, obj["status"]!!.jsonPrimitive.content.toInt())
            val chain = obj["redirect_chain"]!!.jsonArray
            assertEquals(2, chain.size)
        }

    @Test
    fun webFetchBlocksPrivateHostsByDefault() =
        runTest {
            val rootNio = Files.createTempDirectory("openagentic-test-")
            val root = rootNio.toString().replace('\\', '/').toPath()
            val ctx = ToolContext(fileSystem = FileSystem.SYSTEM, cwd = root, projectDir = root)
            val tool = WebFetchTool(transport = WebFetchTransport { _, _ -> WebFetchResponse(200, emptyMap(), ByteArray(0)) })
            assertFailsWith<IllegalArgumentException> {
                tool.run(buildJsonObject { put("url", JsonPrimitive("http://127.0.0.1/")) }, ctx)
            }
        }
}

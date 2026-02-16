package me.lemonhall.openagentic.sdk.tools

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
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

    @Test
    fun webFetchSanitizesHtmlAndCapsOutput() =
        runTest {
            val rootNio = Files.createTempDirectory("openagentic-test-")
            val root = rootNio.toString().replace('\\', '/').toPath()
            val ctx = ToolContext(fileSystem = FileSystem.SYSTEM, cwd = root, projectDir = root)

            val html =
                """
                <html>
                  <head><script>alert(1)</script><style>body{}</style></head>
                  <body>
                    <h1>Title</h1>
                    <p>Hello <strong>world</strong></p>
                    <a href="/x">link</a>
                    <div>${"x".repeat(10_000)}</div>
                  </body>
                </html>
                """.trimIndent()

            val transport =
                WebFetchTransport { url, _ ->
                    WebFetchResponse(200, mapOf("content-type" to "text/html"), html.encodeToByteArray())
                }

            val tool = WebFetchTool(transport = transport)
            val out =
                tool.run(
                    buildJsonObject {
                        put("url", JsonPrimitive("https://example.com/page"))
                        put("mode", JsonPrimitive("clean_html"))
                        put("max_chars", JsonPrimitive(2000))
                    },
                    ctx,
                ) as ToolOutput.Json

            val obj = out.value?.jsonObject
            assertNotNull(obj)
            val text = obj["text"]!!.jsonPrimitive.content
            assertTrue(text.length <= 2000)
            assertTrue(!text.contains("<script", ignoreCase = true))
            assertTrue(text.contains("<a"))
            assertTrue(text.contains("https://example.com/x"))
        }

    @Test
    fun webFetchMarkdownIsContentFocused() =
        runTest {
            val rootNio = Files.createTempDirectory("openagentic-test-")
            val root = rootNio.toString().replace('\\', '/').toPath()
            val ctx = ToolContext(fileSystem = FileSystem.SYSTEM, cwd = root, projectDir = root)

            val html =
                """
                <html>
                  <head><title>T</title><script>alert(1)</script></head>
                  <body>
                    <header>nav</header>
                    <main>
                      <h1>Title</h1>
                      <div><div></div></div>
                      <p>Hello <strong>world</strong></p>
                      <a href="/x">link</a>
                    </main>
                    <footer>f</footer>
                  </body>
                </html>
                """.trimIndent()

            val transport =
                WebFetchTransport { _, _ ->
                    WebFetchResponse(200, mapOf("content-type" to "text/html"), html.encodeToByteArray())
                }

            val tool = WebFetchTool(transport = transport)
            val out =
                tool.run(
                    buildJsonObject {
                        put("url", JsonPrimitive("https://example.com/page"))
                        put("mode", JsonPrimitive("markdown"))
                        put("max_chars", JsonPrimitive(4000))
                    },
                    ctx,
                ) as ToolOutput.Json

            val obj = out.value?.jsonObject
            assertNotNull(obj)
            val text = obj["text"]!!.jsonPrimitive.content
            assertTrue(!text.contains("<div", ignoreCase = true))
            assertTrue(text.contains("Title"))
            assertTrue(text.contains("Hello"))
            assertTrue(text.contains("world"))
            assertTrue(text.contains("https://example.com/x"))
        }
}

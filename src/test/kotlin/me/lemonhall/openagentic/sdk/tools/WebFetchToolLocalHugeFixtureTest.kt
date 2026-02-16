package me.lemonhall.openagentic.sdk.tools

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okio.FileSystem
import okio.Path.Companion.toPath
import org.junit.jupiter.api.Assumptions.assumeTrue

class WebFetchToolLocalHugeFixtureTest {
    @Test
    fun localHugeHtmlFixtureCanBeSanitizedAndSaved() =
        runTest {
            val fixturePath = System.getenv("WEBFETCH_FIXTURE_HTML")?.trim().orEmpty()
            assumeTrue(fixturePath.isNotBlank(), "set WEBFETCH_FIXTURE_HTML to a local HTML file path to enable this test")
            val inFile = Path.of(fixturePath)
            assumeTrue(Files.exists(inFile), "fixture file missing: $inFile")

            val outPath = System.getenv("WEBFETCH_FIXTURE_OUT")?.trim().orEmpty()
            val outFile =
                if (outPath.isNotBlank()) {
                    Path.of(outPath)
                } else {
                    // default: alongside fixture
                    inFile.parent.resolve("webfetch87-clean.html")
                }

            val baseUrl = System.getenv("WEBFETCH_FIXTURE_BASE_URL")?.trim().orEmpty().ifBlank { "https://example.com/" }
            val maxChars = (System.getenv("WEBFETCH_FIXTURE_MAX_CHARS")?.trim()?.toIntOrNull() ?: 80_000).coerceIn(1_000, 80_000)

            val html = Files.readString(inFile)

            val rootNio = Files.createTempDirectory("openagentic-test-")
            val root = rootNio.toString().replace('\\', '/').toPath()
            val ctx = ToolContext(fileSystem = FileSystem.SYSTEM, cwd = root, projectDir = root)

            val transport =
                WebFetchTransport { _, _ ->
                    WebFetchResponse(
                        status = 200,
                        headers = mapOf("content-type" to "text/html; charset=utf-8"),
                        body = html.encodeToByteArray(),
                    )
                }

            val tool = WebFetchTool(transport = transport)
            val out =
                tool.run(
                    buildJsonObject {
                        put("url", JsonPrimitive(baseUrl))
                        put("mode", JsonPrimitive("clean_html"))
                        put("max_chars", JsonPrimitive(maxChars))
                    },
                    ctx,
                ) as ToolOutput.Json

            val obj = out.value?.jsonObject
            assertNotNull(obj)
            val cleaned = obj["text"]!!.jsonPrimitive.content
            assertTrue(cleaned.length <= maxChars)
            assertTrue(!cleaned.contains("<script", ignoreCase = true))

            Files.createDirectories(outFile.parent)
            Files.writeString(outFile, cleaned)
        }
}


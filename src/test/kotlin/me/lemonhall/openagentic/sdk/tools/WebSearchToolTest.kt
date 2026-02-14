package me.lemonhall.openagentic.sdk.tools

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import okio.FileSystem
import okio.Path.Companion.toPath

class WebSearchToolTest {
    @Test
    fun domainFiltersApplyToTransportResults() =
        runTest {
            val tool =
                WebSearchTool(
                    transport = { _, _, _ ->
                        buildJsonObject {
                            put(
                                "results",
                                JsonArray(
                                    listOf(
                                        buildJsonObject { put("title", JsonPrimitive("a")); put("url", JsonPrimitive("https://example.com/a")) },
                                        buildJsonObject { put("title", JsonPrimitive("b")); put("url", JsonPrimitive("https://evil.com/b")) },
                                    ),
                                ),
                            )
                        }
                    },
                    apiKeyProvider = { "tavily_test_key" },
                )

            val out =
                tool.run(
                    input =
                        buildJsonObject {
                            put("query", JsonPrimitive("x"))
                            put("allowed_domains", JsonArray(listOf(JsonPrimitive("example.com"))))
                        },
                    ctx = ToolContext(fileSystem = FileSystem.SYSTEM, cwd = ".".toPath(), projectDir = ".".toPath()),
                ) as ToolOutput.Json

            val obj = out.value as JsonObject
            assertEquals("x", (obj["query"] as JsonPrimitive).content)
            val results = obj["results"] as JsonArray
            assertEquals(1, results.size)
            val url = (results[0] as JsonObject)["url"] as JsonPrimitive
            assertEquals("https://example.com/a", url.content)
        }
}

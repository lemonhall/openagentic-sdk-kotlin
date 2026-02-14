package me.lemonhall.openagentic.sdk.tools

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okio.FileSystem
import okio.Path.Companion.toPath

class TodoWriteToolTest {
    @Test
    fun todoWriteValidatesAndReturnsStats() =
        runTest {
            val rootNio = Files.createTempDirectory("openagentic-test-")
            val root = rootNio.toString().replace('\\', '/').toPath()
            val ctx = ToolContext(fileSystem = FileSystem.SYSTEM, cwd = root, projectDir = root)

            val tool = TodoWriteTool()
            val out =
                tool.run(
                    buildJsonObject {
                        put(
                            "todos",
                            JsonArray(
                                listOf(
                                    buildJsonObject {
                                        put("content", JsonPrimitive("a"))
                                        put("status", JsonPrimitive("pending"))
                                        put("activeForm", JsonPrimitive("do a"))
                                    },
                                    buildJsonObject {
                                        put("content", JsonPrimitive("b"))
                                        put("status", JsonPrimitive("completed"))
                                        put("activeForm", JsonPrimitive("did b"))
                                    },
                                ),
                            ),
                        )
                    },
                    ctx,
                ) as ToolOutput.Json

            val obj = out.value?.jsonObject
            assertNotNull(obj)
            val stats = obj["stats"]!!.jsonObject
            assertEquals(2, stats["total"]!!.jsonPrimitive.content.toInt())
            assertEquals(1, stats["pending"]!!.jsonPrimitive.content.toInt())
            assertEquals(1, stats["completed"]!!.jsonPrimitive.content.toInt())
        }
}


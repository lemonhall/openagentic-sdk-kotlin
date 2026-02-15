package me.lemonhall.openagentic.sdk.tools

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okio.FileSystem
import okio.Path.Companion.toPath

class ListToolTest {
    @Test
    fun listSkipsIgnoredDirectories() =
        runTest {
            val rootNio = Files.createTempDirectory("openagentic-test-")
            val root = rootNio.toString().replace('\\', '/').toPath()
            val fs = FileSystem.SYSTEM
            val proj = root.resolve("proj")
            fs.createDirectories(proj.resolve(".git"))
            fs.createDirectories(proj.resolve("src"))
            fs.write(proj.resolve("src").resolve("a.txt")) { writeUtf8("hi") }
            fs.write(proj.resolve(".git").resolve("config")) { writeUtf8("x") }

            val tool = ListTool(limit = 100)
            val out =
                tool.run(
                    input = buildJsonObject { put("path", JsonPrimitive(proj.toString())) },
                    ctx = ToolContext(fileSystem = fs, cwd = proj, projectDir = proj),
                ) as ToolOutput.Json

            val output = (out.value as kotlinx.serialization.json.JsonObject)["output"]?.let { (it as JsonPrimitive).content }.orEmpty()
            assertTrue(output.contains("src/"))
            assertTrue(output.contains("a.txt"))
            assertFalse(output.contains(".git/"))
        }

    @Test
    fun listTruncatedFalseWhenExactlyLimitAndNoMore() =
        runTest {
            val rootNio = Files.createTempDirectory("openagentic-test-")
            val root = rootNio.toString().replace('\\', '/').toPath()
            val fs = FileSystem.SYSTEM
            fs.write(root.resolve("a.txt")) { writeUtf8("a") }
            fs.write(root.resolve("b.txt")) { writeUtf8("b") }

            val tool = ListTool(limit = 2)
            val out =
                tool.run(
                    input = buildJsonObject { put("path", JsonPrimitive(root.toString())) },
                    ctx = ToolContext(fileSystem = fs, cwd = root, projectDir = root),
                ) as ToolOutput.Json

            val obj = out.value?.jsonObject
            assertNotNull(obj)
            assertEquals(2, obj["count"]?.jsonPrimitive?.content?.toInt())
            assertEquals("false", obj["truncated"]?.jsonPrimitive?.content)
        }

    @Test
    fun listTruncatedTrueWhenMoreThanLimit() =
        runTest {
            val rootNio = Files.createTempDirectory("openagentic-test-")
            val root = rootNio.toString().replace('\\', '/').toPath()
            val fs = FileSystem.SYSTEM
            fs.write(root.resolve("a.txt")) { writeUtf8("a") }
            fs.write(root.resolve("b.txt")) { writeUtf8("b") }
            fs.write(root.resolve("c.txt")) { writeUtf8("c") }

            val tool = ListTool(limit = 2)
            val out =
                tool.run(
                    input = buildJsonObject { put("path", JsonPrimitive(root.toString())) },
                    ctx = ToolContext(fileSystem = fs, cwd = root, projectDir = root),
                ) as ToolOutput.Json

            val obj = out.value?.jsonObject
            assertNotNull(obj)
            assertEquals(2, obj["count"]?.jsonPrimitive?.content?.toInt())
            assertEquals("true", obj["truncated"]?.jsonPrimitive?.content)
        }
}

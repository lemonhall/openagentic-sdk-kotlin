package me.lemonhall.openagentic.sdk.tools

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okio.FileSystem
import okio.Path.Companion.toPath

class ReadToolTest {
    @Test
    fun readSupportsOffsetAndLimitWithLineNumbers() =
        runTest {
            val rootNio = Files.createTempDirectory("openagentic-test-")
            val root = rootNio.toString().replace('\\', '/').toPath()
            FileSystem.SYSTEM.write(root.resolve("a.txt")) {
                writeUtf8("one\ntwo\nthree\n")
            }

            val tool = ReadTool()
            val ctx = ToolContext(fileSystem = FileSystem.SYSTEM, cwd = root, projectDir = root)
            val out =
                tool.run(
                    buildJsonObject {
                        put("file_path", JsonPrimitive("a.txt"))
                        put("offset", JsonPrimitive(2))
                        put("limit", JsonPrimitive(2))
                    },
                    ctx,
                ) as ToolOutput.Json

            val obj = out.value?.jsonObject
            assertNotNull(obj)
            assertEquals(3, obj["total_lines"]?.jsonPrimitive?.content?.toInt())
            assertEquals(2, obj["lines_returned"]?.jsonPrimitive?.content?.toInt())
            val content = obj["content"]?.jsonPrimitive?.content.orEmpty()
            assertTrue(content.contains("2: two"))
            assertTrue(content.contains("3: three"))
        }

    @Test
    fun readReturnsBase64ForPngLikeFiles() =
        runTest {
            val rootNio = Files.createTempDirectory("openagentic-test-")
            val root = rootNio.toString().replace('\\', '/').toPath()
            val bytes = byteArrayOf(0x01, 0x02, 0x03, 0x04)
            FileSystem.SYSTEM.write(root.resolve("img.png")) {
                write(bytes)
            }

            val tool = ReadTool()
            val ctx = ToolContext(fileSystem = FileSystem.SYSTEM, cwd = root, projectDir = root)
            val out =
                tool.run(
                    buildJsonObject { put("file_path", JsonPrimitive("img.png")) },
                    ctx,
                ) as ToolOutput.Json

            val obj = out.value?.jsonObject
            assertNotNull(obj)
            assertEquals("image/png", obj["mime_type"]?.jsonPrimitive?.content)
            assertEquals(bytes.size, obj["file_size"]?.jsonPrimitive?.content?.toInt())
            assertTrue(obj["image"]?.jsonPrimitive?.content.orEmpty().isNotBlank())
        }
}


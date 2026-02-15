package me.lemonhall.openagentic.sdk.tools

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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

    @Test
    fun readOffsetOutOfRangeFailsWithMessage() =
        runTest {
            val rootNio = Files.createTempDirectory("openagentic-test-")
            val root = rootNio.toString().replace('\\', '/').toPath()
            FileSystem.SYSTEM.write(root.resolve("a.txt")) { writeUtf8("one\ntwo\n") }

            val tool = ReadTool()
            val ctx = ToolContext(fileSystem = FileSystem.SYSTEM, cwd = root, projectDir = root)
            val err =
                assertFailsWith<IllegalArgumentException> {
                    tool.run(
                        buildJsonObject {
                            put("file_path", JsonPrimitive("a.txt"))
                            put("offset", JsonPrimitive(10))
                            put("limit", JsonPrimitive(1))
                        },
                        ctx,
                    )
                }
            assertTrue(err.message.orEmpty().contains("out of range"))
            assertTrue(err.message.orEmpty().contains("total_lines=2"))
        }

    @Test
    fun readMarksTruncationForLargeFile() =
        runTest {
            val rootNio = Files.createTempDirectory("openagentic-test-")
            val root = rootNio.toString().replace('\\', '/').toPath()
            val big = "a".repeat(1024 * 1024 + 64)
            FileSystem.SYSTEM.write(root.resolve("big.txt")) { writeUtf8(big) }

            val tool = ReadTool()
            val ctx = ToolContext(fileSystem = FileSystem.SYSTEM, cwd = root, projectDir = root)
            val out =
                tool.run(
                    buildJsonObject { put("file_path", JsonPrimitive("big.txt")) },
                    ctx,
                ) as ToolOutput.Json
            val obj = out.value?.jsonObject
            assertNotNull(obj)
            val truncated = obj["truncated"]?.jsonPrimitive?.content?.toBoolean() ?: false
            assertTrue(truncated)
            val fileSize = obj["file_size"]?.jsonPrimitive?.content?.toLong() ?: 0
            val bytesReturned = obj["bytes_returned"]?.jsonPrimitive?.content?.toLong() ?: 0
            assertTrue(fileSize > bytesReturned, "expected file_size > bytes_returned; file_size=$fileSize bytes_returned=$bytesReturned")
        }

    @Test
    fun readTruncatesVeryLongSingleLine() =
        runTest {
            val rootNio = Files.createTempDirectory("openagentic-test-")
            val root = rootNio.toString().replace('\\', '/').toPath()
            val longLine = "x".repeat(10_000 + 200)
            FileSystem.SYSTEM.write(root.resolve("long.txt")) { writeUtf8(longLine) }

            val tool = ReadTool()
            val ctx = ToolContext(fileSystem = FileSystem.SYSTEM, cwd = root, projectDir = root)
            val out =
                tool.run(
                    buildJsonObject { put("file_path", JsonPrimitive("long.txt")) },
                    ctx,
                ) as ToolOutput.Json
            val obj = out.value?.jsonObject
            assertNotNull(obj)
            val content = obj["content"]?.jsonPrimitive?.content.orEmpty()
            assertTrue(content.contains("…(truncated)"))
            val truncated = obj["truncated"]?.jsonPrimitive?.content?.toBoolean() ?: false
            assertTrue(truncated)
        }
}

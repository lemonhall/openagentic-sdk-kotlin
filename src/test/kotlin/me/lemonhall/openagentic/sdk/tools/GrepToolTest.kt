package me.lemonhall.openagentic.sdk.tools

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okio.FileSystem
import okio.Path.Companion.toPath

class GrepToolTest {
    @Test
    fun grepFindsMatchesInFiles() =
        runTest {
            val rootNio = Files.createTempDirectory("openagentic-test-")
            val root = rootNio.toString().replace('\\', '/').toPath()
            FileSystem.SYSTEM.createDirectories(root.resolve("sub"))
            FileSystem.SYSTEM.write(root.resolve("a.txt")) { writeUtf8("hello\nworld\n") }
            FileSystem.SYSTEM.write(root.resolve("sub").resolve("b.txt")) { writeUtf8("nope\nhello again\n") }

            val tool = GrepTool()
            val ctx = ToolContext(fileSystem = FileSystem.SYSTEM, cwd = root, projectDir = root)
            val out =
                tool.run(
                    buildJsonObject {
                        put("query", JsonPrimitive("hello"))
                        put("file_glob", JsonPrimitive("**/*.txt"))
                        put("root", JsonPrimitive("."))
                    },
                    ctx,
                ) as ToolOutput.Json

            val obj = out.value?.jsonObject
            assertNotNull(obj)
            val matches = obj["matches"]?.jsonArray ?: error("missing matches")
            assertEquals(2, matches.size)
            val first = matches[0].jsonObject
            assertTrue(first["file_path"]!!.jsonPrimitive.content.contains(".txt"))
        }

    @Test
    fun grepSupportsFilesWithMatchesMode() =
        runTest {
            val rootNio = Files.createTempDirectory("openagentic-test-")
            val root = rootNio.toString().replace('\\', '/').toPath()
            FileSystem.SYSTEM.write(root.resolve("a.txt")) { writeUtf8("hello\n") }
            FileSystem.SYSTEM.write(root.resolve("b.txt")) { writeUtf8("nope\n") }

            val tool = GrepTool()
            val ctx = ToolContext(fileSystem = FileSystem.SYSTEM, cwd = root, projectDir = root)
            val out =
                tool.run(
                    buildJsonObject {
                        put("query", JsonPrimitive("hello"))
                        put("file_glob", JsonPrimitive("**/*.txt"))
                        put("mode", JsonPrimitive("files_with_matches"))
                    },
                    ctx,
                ) as ToolOutput.Json

            val obj = out.value?.jsonObject
            assertNotNull(obj)
            val files = obj["files"]?.jsonArray ?: error("missing files")
            assertEquals(1, files.size)
        }

    @Test
    fun grepHandlesCrlfAndMixedLineEndings() =
        runTest {
            val rootNio = Files.createTempDirectory("openagentic-test-")
            val root = rootNio.toString().replace('\\', '/').toPath()
            FileSystem.SYSTEM.write(root.resolve("a.txt")) { writeUtf8("a\r\nhello\r\nb\nhello2\r\n") }

            val tool = GrepTool()
            val ctx = ToolContext(fileSystem = FileSystem.SYSTEM, cwd = root, projectDir = root)
            val out =
                tool.run(
                    buildJsonObject {
                        put("query", JsonPrimitive("hello"))
                        put("file_glob", JsonPrimitive("**/*.txt"))
                    },
                    ctx,
                ) as ToolOutput.Json

            val obj = out.value?.jsonObject
            assertNotNull(obj)
            val matches = obj["matches"]?.jsonArray ?: error("missing matches")
            assertEquals(2, matches.size)
            val lines = matches.map { it.jsonObject["line"]!!.jsonPrimitive.content.toInt() }.sorted()
            assertEquals(listOf(2, 4), lines)
        }

    @Test
    fun grepCanExcludeHiddenWhenIncludeHiddenFalse() =
        runTest {
            val rootNio = Files.createTempDirectory("openagentic-test-")
            val root = rootNio.toString().replace('\\', '/').toPath()
            FileSystem.SYSTEM.createDirectories(root.resolve(".hidden"))
            FileSystem.SYSTEM.write(root.resolve(".hidden").resolve("a.txt")) { writeUtf8("hello\n") }
            FileSystem.SYSTEM.write(root.resolve("b.txt")) { writeUtf8("hello\n") }

            val tool = GrepTool()
            val ctx = ToolContext(fileSystem = FileSystem.SYSTEM, cwd = root, projectDir = root)
            val out =
                tool.run(
                    buildJsonObject {
                        put("query", JsonPrimitive("hello"))
                        put("file_glob", JsonPrimitive("**/*.txt"))
                        put("include_hidden", JsonPrimitive(false))
                    },
                    ctx,
                ) as ToolOutput.Json

            val obj = out.value?.jsonObject
            assertNotNull(obj)
            val matches = obj["matches"]?.jsonArray ?: error("missing matches")
            assertEquals(1, matches.size)
            val fp = matches[0].jsonObject["file_path"]!!.jsonPrimitive.content
            assertTrue(!fp.contains(".hidden"))
        }

    @Test
    fun grepNoMatchesIsNotError() =
        runTest {
            val rootNio = Files.createTempDirectory("openagentic-test-")
            val root = rootNio.toString().replace('\\', '/').toPath()
            FileSystem.SYSTEM.write(root.resolve("a.txt")) { writeUtf8("nope\n") }

            val tool = GrepTool()
            val ctx = ToolContext(fileSystem = FileSystem.SYSTEM, cwd = root, projectDir = root)
            val out =
                tool.run(
                    buildJsonObject {
                        put("query", JsonPrimitive("hello"))
                        put("file_glob", JsonPrimitive("**/*.txt"))
                    },
                    ctx,
                ) as ToolOutput.Json

            val obj = out.value?.jsonObject
            assertNotNull(obj)
            val matches = obj["matches"]?.jsonArray ?: error("missing matches")
            assertEquals(0, matches.size)
            assertEquals("false", obj["truncated"]?.jsonPrimitive?.content)
            assertEquals(0, obj["total_matches"]?.jsonPrimitive?.content?.toInt())
        }

    @Test
    fun grepAcceptsFileGlobAsExplicitFilePath() =
        runTest {
            val rootNio = Files.createTempDirectory("openagentic-test-")
            val root = rootNio.toString().replace('\\', '/').toPath()
            FileSystem.SYSTEM.createDirectories(root.resolve("workspace").resolve("radios"))
            FileSystem.SYSTEM.write(root.resolve("workspace").resolve("radios").resolve(".countries.index.json")) {
                writeUtf8("{\"countries\":[{\"code\":\"CN\",\"name\":\"China\"}]}\n")
            }

            val tool = GrepTool()
            val ctx = ToolContext(fileSystem = FileSystem.SYSTEM, cwd = root, projectDir = root)
            val out =
                tool.run(
                    buildJsonObject {
                        put("query", JsonPrimitive("\"code\":\"CN\""))
                        put("file_glob", JsonPrimitive("workspace/radios/.countries.index.json"))
                    },
                    ctx,
                ) as ToolOutput.Json

            val obj = out.value?.jsonObject
            assertNotNull(obj)
            val matches = obj["matches"]?.jsonArray ?: error("missing matches")
            assertEquals(1, matches.size)
            val fp = matches[0].jsonObject["file_path"]!!.jsonPrimitive.content
            assertTrue(fp.endsWith("/workspace/radios/.countries.index.json"))
        }
}

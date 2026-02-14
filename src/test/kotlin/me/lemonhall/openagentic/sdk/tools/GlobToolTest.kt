package me.lemonhall.openagentic.sdk.tools

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okio.FileSystem
import okio.Path.Companion.toPath

class GlobToolTest {
    @Test
    fun globFindsMatchesUnderRoot() =
        runTest {
            val rootNio = Files.createTempDirectory("openagentic-test-")
            val root = rootNio.toString().replace('\\', '/').toPath()
            FileSystem.SYSTEM.createDirectories(root.resolve("sub"))
            FileSystem.SYSTEM.write(root.resolve("a.txt")) { writeUtf8("a") }
            FileSystem.SYSTEM.write(root.resolve("b.md")) { writeUtf8("b") }
            FileSystem.SYSTEM.write(root.resolve("sub").resolve("c.txt")) { writeUtf8("c") }

            val tool = GlobTool()
            val ctx = ToolContext(fileSystem = FileSystem.SYSTEM, cwd = root, projectDir = root)
            val out =
                tool.run(
                    buildJsonObject {
                        put("pattern", JsonPrimitive("**/*.txt"))
                        put("root", JsonPrimitive("."))
                    },
                    ctx,
                ) as ToolOutput.Json

            val obj = out.value?.jsonObject
            assertNotNull(obj)
            val matches = obj["matches"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
            assertEquals(2, matches.size)
        }
}

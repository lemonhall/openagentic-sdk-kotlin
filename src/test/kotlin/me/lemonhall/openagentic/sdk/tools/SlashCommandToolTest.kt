package me.lemonhall.openagentic.sdk.tools

import java.nio.file.Files
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

class SlashCommandToolTest {
    @Test
    fun slashCommandLoadsAndRendersArgsAndPath() =
        runTest {
            val rootNio = Files.createTempDirectory("openagentic-test-")
            val root = rootNio.toString().replace('\\', '/').toPath()
            FileSystem.SYSTEM.createDirectories(root.resolve(".git"))
            val cmdDir = root.resolve(".claude").resolve("commands")
            FileSystem.SYSTEM.createDirectories(cmdDir)
            FileSystem.SYSTEM.write(cmdDir.resolve("hello.md")) {
                writeUtf8("Hi \${args} at \${path}")
            }

            val tool = SlashCommandTool()
            val ctx = ToolContext(fileSystem = FileSystem.SYSTEM, cwd = root, projectDir = root)
            val out =
                tool.run(
                    buildJsonObject {
                        put("name", JsonPrimitive("hello"))
                        put("args", JsonPrimitive("there"))
                    },
                    ctx,
                ) as ToolOutput.Json

            val obj = out.value?.jsonObject
            assertNotNull(obj)
            val content = obj["content"]?.jsonPrimitive?.content.orEmpty()
            assertTrue(content.contains("there"))
            assertTrue(content.contains(root.toString()))
        }
}

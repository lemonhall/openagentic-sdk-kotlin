package me.lemonhall.openagentic.sdk.tools

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okio.FileSystem
import okio.Path.Companion.toPath

class OpenAiToolSchemasResponsesTest {
    @Test
    fun responsesSchemasAreFlattened() {
        val rootNio = Files.createTempDirectory("openagentic-test-")
        val root = rootNio.toString().replace('\\', '/').toPath()
        val ctx = ToolContext(fileSystem = FileSystem.SYSTEM, cwd = root, projectDir = root)

        val tools = OpenAiToolSchemas.forResponses(listOf("Read", "Write"), registry = ToolRegistry(), ctx = ctx)
        val read = tools.firstOrNull { (it["name"] as? JsonPrimitive)?.content == "Read" }
        assertNotNull(read)
        assertEquals("function", (read["type"] as JsonPrimitive).content)
        assertNull(read["function"])
        assertNotNull(read["parameters"] as? JsonObject)
    }
}


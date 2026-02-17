package me.lemonhall.openagentic.sdk.tools

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import me.lemonhall.openagentic.sdk.subagents.BuiltInSubAgents
import okio.FileSystem
import okio.Path.Companion.toPath

class TaskToolSchemaAgentsTest {
    @Test
    fun taskToolDescriptionRendersAgentsList() {
        val rootNio = Files.createTempDirectory("openagentic-test-")
        val root = rootNio.toString().replace('\\', '/').toPath()
        val ctx = ToolContext(fileSystem = FileSystem.SYSTEM, cwd = root, projectDir = root)

        val agents =
            listOf(
                BuiltInSubAgents.exploreTaskAgent(),
                TaskAgent(name = "webview", description = "Drive an embedded WebView.", allowedTools = setOf("web_*")),
            )

        val tools = OpenAiToolSchemas.forResponses(toolNames = listOf("Task"), registry = ToolRegistry(), ctx = ctx, taskAgents = agents)
        val task = tools.single()
        val desc = (task["description"] as? JsonPrimitive)?.content.orEmpty()

        assertTrue(desc.contains("- ${BuiltInSubAgents.EXPLORE_AGENT}:"), "expected explore agent in description")
        assertTrue(desc.contains("- webview:"), "expected webview agent in description")
        assertTrue(!desc.contains("{agents}") && !desc.contains("{{agents}}"), "agents placeholder should be rendered")
        assertTrue(desc.contains("Task(agent=\"explore\""), "example should use agent=... prompt=... params")
    }
}


package me.lemonhall.openagentic.sdk.tools

import java.nio.file.Files
import java.util.UUID
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

class SkillToolTest {
    @Test
    fun skillLoadsSkillMarkdownFromClaudeSkills() =
        runTest {
            val rootNio = Files.createTempDirectory("openagentic-test-")
            val root = rootNio.toString().replace('\\', '/').toPath()
            val name = "skill-" + UUID.randomUUID().toString().replace("-", "")

            val skillDir = root.resolve(".claude").resolve("skills").resolve(name)
            FileSystem.SYSTEM.createDirectories(skillDir)
            FileSystem.SYSTEM.write(skillDir.resolve("SKILL.md")) {
                writeUtf8("---\nname: $name\ndescription: d\n---\n\n# $name\n\nhello\n")
            }

            val tool = SkillTool()
            val ctx = ToolContext(fileSystem = FileSystem.SYSTEM, cwd = root, projectDir = root)
            val out =
                tool.run(
                    buildJsonObject { put("name", JsonPrimitive(name)) },
                    ctx,
                ) as ToolOutput.Json

            val obj = out.value?.jsonObject
            assertNotNull(obj)
            kotlin.test.assertEquals(name, obj["name"]?.jsonPrimitive?.content)
            assertTrue(obj["output"]?.jsonPrimitive?.content.orEmpty().contains("hello"))
        }
}

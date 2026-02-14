package me.lemonhall.openagentic.sdk.skills

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SkillIndexTest {
    @Test
    fun indexesClaudeSkills() {
        val root = Files.createTempDirectory("openagentic-test-")
        val agentsHome = root.resolve(".agents")
        val sdkHome = root.resolve(".home")
        val env =
            MapEnvironment(
                mapOf(
                    "OPENAGENTIC_AGENTS_HOME" to agentsHome.toString(),
                    "OPENAGENTIC_SDK_HOME" to sdkHome.toString(),
                ),
            )
        val skillDir = root.resolve(".claude").resolve("skills").resolve("a")
        Files.createDirectories(skillDir)
        Files.writeString(skillDir.resolve("SKILL.md"), "# a\n\nsummary\n")

        val skills = indexSkills(projectDir = root.toString().replace('\\', '/'), env = env)
        assertEquals(1, skills.size)
        assertEquals("a", skills[0].name)
        assertTrue(skills[0].path.endsWith("SKILL.md"))
    }

    @Test
    fun includesGlobalSkills() {
        val root = Files.createTempDirectory("openagentic-test-")
        val agentsHome = root.resolve(".agents")
        val home = root.resolve(".home")
        val env =
            MapEnvironment(
                mapOf(
                    "OPENAGENTIC_AGENTS_HOME" to agentsHome.toString(),
                    "OPENAGENTIC_SDK_HOME" to home.toString(),
                ),
            )

        val g = home.resolve("skills").resolve("g")
        Files.createDirectories(g)
        Files.writeString(
            g.resolve("SKILL.md"),
            "---\nname: global-one\ndescription: gd\n---\n\n# global-one\n",
        )

        val skills = indexSkills(projectDir = root.toString().replace('\\', '/'), env = env)
        val names = skills.map { it.name }
        assertTrue("global-one" in names)
    }

    @Test
    fun projectOverridesGlobalOnNameCollision() {
        val root = Files.createTempDirectory("openagentic-test-")
        val agentsHome = root.resolve(".agents")
        val home = root.resolve(".home")
        val env =
            MapEnvironment(
                mapOf(
                    "OPENAGENTIC_AGENTS_HOME" to agentsHome.toString(),
                    "OPENAGENTIC_SDK_HOME" to home.toString(),
                ),
            )

        val g = home.resolve("skills").resolve("a")
        Files.createDirectories(g)
        Files.writeString(g.resolve("SKILL.md"), "---\nname: a\ndescription: global\n---\n\n# A\n")

        val p = root.resolve(".claude").resolve("skills").resolve("a")
        Files.createDirectories(p)
        Files.writeString(p.resolve("SKILL.md"), "---\nname: a\ndescription: project\n---\n\n# A\n")

        val skills = indexSkills(projectDir = root.toString().replace('\\', '/'), env = env)
        val a = skills.first { it.name == "a" }
        assertEquals("project", a.description)
        assertTrue(a.path.contains(".claude"))
    }
}

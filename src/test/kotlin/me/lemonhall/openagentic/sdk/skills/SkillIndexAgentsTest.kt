package me.lemonhall.openagentic.sdk.skills

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SkillIndexAgentsTest {
    @Test
    fun indexesAgentsSkills() {
        val root = Files.createTempDirectory("openagentic-test-")
        val agentsHome = root.resolve(".agents")
        val env = MapEnvironment(mapOf("OPENAGENTIC_AGENTS_HOME" to agentsHome.toString()))

        val skillDir = agentsHome.resolve("skills").resolve("agent-browser")
        Files.createDirectories(skillDir)
        Files.writeString(skillDir.resolve("SKILL.md"), "---\nname: agent-browser\ndescription: d\n---\n\n# agent-browser\n")

        val skills = indexSkills(projectDir = root.toString().replace('\\', '/'), env = env)
        assertEquals(1, skills.size)
        assertEquals("agent-browser", skills[0].name)
        assertTrue(skills[0].path.endsWith("SKILL.md"))
    }

    @Test
    fun projectOverridesAgentsOnNameCollision() {
        val root = Files.createTempDirectory("openagentic-test-")
        val agentsHome = root.resolve(".agents")
        val env = MapEnvironment(mapOf("OPENAGENTIC_AGENTS_HOME" to agentsHome.toString()))

        val g = agentsHome.resolve("skills").resolve("a")
        Files.createDirectories(g)
        Files.writeString(g.resolve("SKILL.md"), "---\nname: a\ndescription: agents\n---\n\n# A\n")

        val p = root.resolve(".claude").resolve("skills").resolve("a")
        Files.createDirectories(p)
        Files.writeString(p.resolve("SKILL.md"), "---\nname: a\ndescription: project\n---\n\n# A\n")

        val skills = indexSkills(projectDir = root.toString().replace('\\', '/'), env = env)
        val a = skills.first { it.name == "a" }
        assertEquals("project", a.description)
        assertTrue(a.path.contains(".claude"))
    }
}


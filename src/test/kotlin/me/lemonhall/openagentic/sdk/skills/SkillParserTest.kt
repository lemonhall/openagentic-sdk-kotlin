package me.lemonhall.openagentic.sdk.skills

import kotlin.test.Test
import kotlin.test.assertEquals

private const val SKILL_MD =
    """
    # skill-example

    One line summary.

    ## Checklist
    - Do A
    - Do B

    ## Notes
    Use the Read tool first.
    """

class SkillParserTest {
    @Test
    fun parsesNameSummaryChecklist() {
        val s = parseSkillMarkdown(SKILL_MD.trimIndent())
        assertEquals("skill-example", s.name)
        assertEquals("One line summary.", s.summary)
        assertEquals(listOf("Do A", "Do B"), s.checklist)
    }

    @Test
    fun frontmatterNameTakesPrecedence() {
        val md =
            """
            ---
            name: main-process
            description: demo
            ---

            # Main Process

            Summary.
            """.trimIndent()
        val s = parseSkillMarkdown(md)
        assertEquals("main-process", s.name)
        assertEquals("demo", s.description)
    }
}


package me.lemonhall.openagentic.sdk.subagents

import me.lemonhall.openagentic.sdk.tools.TaskAgent

object BuiltInSubAgents {
    const val EXPLORE_AGENT: String = "explore"

    private const val EXPLORE_MARKER: String = "OPENAGENTIC_SDK_EXPLORE_PROMPT_V1"

    val exploreSystemPrompt: String =
        """
        $EXPLORE_MARKER
        You are a file search specialist. You excel at thoroughly navigating and exploring codebases.

        Your strengths:
        - Rapidly finding files using glob patterns
        - Searching code and text with powerful regex patterns
        - Reading and analyzing file contents

        Guidelines:
        - Use Glob for broad file pattern matching
        - Use Grep for searching file contents with regex
        - Use Read when you know the specific file path you need to read
        - Use List to quickly enumerate a directory
        - Return file paths as absolute paths in your final response
        - Do not create or edit files
        - Do not run commands that modify the user's system state

        Complete the user's search request efficiently and report your findings clearly.
        """.trimIndent()

    fun exploreTaskAgent(): TaskAgent {
        return TaskAgent(
            name = EXPLORE_AGENT,
            description = "File/code search specialist. Use for Grep/Read/Glob exploration and summarization.",
            allowedTools = setOf("Read", "List", "Glob", "Grep"),
        )
    }
}


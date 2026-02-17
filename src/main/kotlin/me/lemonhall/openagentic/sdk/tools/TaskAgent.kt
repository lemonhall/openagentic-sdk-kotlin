package me.lemonhall.openagentic.sdk.tools

data class TaskAgent(
    val name: String,
    val description: String,
    val allowedTools: Set<String> = emptySet(),
) {
    fun renderLine(): String {
        val tools = allowedTools.filter { it.isNotBlank() }.joinToString(", ")
        val toolsPart = if (tools.isBlank()) "" else " (tools: $tools)"
        val desc = description.trim().ifEmpty { "No description." }
        return "- $name: $desc$toolsPart"
    }
}

internal fun renderTaskAgents(agents: List<TaskAgent>): String {
    if (agents.isEmpty()) return "  (none configured)"
    return agents.joinToString("\n") { it.renderLine() }
}


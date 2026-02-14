package me.lemonhall.openagentic.sdk.tools

class ToolRegistry(
    tools: Iterable<Tool> = emptyList(),
) {
    private val toolsByName = linkedMapOf<String, Tool>()

    init {
        for (t in tools) {
            register(t)
        }
    }

    fun register(tool: Tool) {
        val name = tool.name
        require(name.isNotBlank()) { "tool must have a non-empty string 'name'" }
        toolsByName[name] = tool
    }

    fun get(name: String): Tool {
        return toolsByName[name] ?: throw NoSuchElementException("unknown tool: $name")
    }

    fun names(): List<String> = toolsByName.keys.sorted()
}


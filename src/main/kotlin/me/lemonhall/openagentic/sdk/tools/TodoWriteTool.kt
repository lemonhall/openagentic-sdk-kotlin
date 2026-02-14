package me.lemonhall.openagentic.sdk.tools

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import me.lemonhall.openagentic.sdk.json.asStringOrNull

class TodoWriteTool : Tool {
    override val name: String = "TodoWrite"
    override val description: String = "Write or update a TODO list for the current session."

    private val statuses = setOf("pending", "in_progress", "completed", "cancelled")
    private val priorities = setOf("low", "medium", "high")

    override suspend fun run(
        input: ToolInput,
        ctx: ToolContext,
    ): ToolOutput {
        // runtime-managed persistence (if any) is out of scope; this tool validates and returns stats.
        val todosEl = input["todos"]
        require(todosEl is JsonArray && todosEl.isNotEmpty()) { "TodoWrite: 'todos' must be a non-empty list" }

        var pending = 0
        var inProgress = 0
        var completed = 0
        var cancelled = 0

        for (t in todosEl) {
            require(t is JsonObject) { "TodoWrite: each todo must be an object" }
            val content = t["content"]?.asStringOrNull()?.trim().orEmpty()
            val activeForm = t["activeForm"]?.asStringOrNull()
            val status = t["status"]?.asStringOrNull()?.trim().orEmpty()
            val priority = t["priority"]?.asStringOrNull()
            val id = t["id"]?.asStringOrNull()

            require(content.isNotEmpty()) { "TodoWrite: todo 'content' must be a non-empty string" }
            require(statuses.contains(status)) {
                "TodoWrite: todo 'status' must be 'pending', 'in_progress', 'completed', or 'cancelled'"
            }
            if (activeForm != null) require(activeForm.trim().isNotEmpty()) { "TodoWrite: todo 'activeForm' must be a non-empty string when provided" }
            if (priority != null) require(priorities.contains(priority.trim())) { "TodoWrite: todo 'priority' must be 'low', 'medium', or 'high' when provided" }
            if (id != null) require(id.trim().isNotEmpty()) { "TodoWrite: todo 'id' must be a non-empty string when provided" }

            when (status) {
                "pending" -> pending++
                "in_progress" -> inProgress++
                "completed" -> completed++
                else -> cancelled++
            }
        }

        val total = todosEl.size
        val out =
            buildJsonObject {
                put("message", JsonPrimitive("Updated todos"))
                put(
                    "stats",
                    buildJsonObject {
                        put("total", JsonPrimitive(total))
                        put("pending", JsonPrimitive(pending))
                        put("in_progress", JsonPrimitive(inProgress))
                        put("completed", JsonPrimitive(completed))
                        put("cancelled", JsonPrimitive(cancelled))
                    },
                )
            }
        return ToolOutput.Json(out)
    }
}

package me.lemonhall.openagentic.sdk.tools

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import me.lemonhall.openagentic.sdk.skills.indexSkills
import me.lemonhall.openagentic.sdk.toolprompts.ToolPrompts

interface OpenAiSchemaTool {
    fun openAiSchema(
        ctx: ToolContext,
        registry: ToolRegistry?,
    ): JsonObject
}

object OpenAiToolSchemas {
    fun forOpenAi(
        toolNames: List<String>,
        registry: ToolRegistry? = null,
        ctx: ToolContext,
        taskAgents: List<TaskAgent> = emptyList(),
    ): List<JsonObject> {
        val directory = ctx.cwd.toString().ifEmpty { "(unknown)" }
        val projectDir = (ctx.projectDir ?: ctx.cwd).toString()

        val promptVars =
            linkedMapOf<String, Any?>(
                "directory" to directory,
                "project_dir" to projectDir,
                "maxBytes" to (1024 * 1024),
                "maxLines" to 2000,
                "agents" to renderTaskAgents(taskAgents),
            )

        val schemasByName = linkedMapOf<String, JsonObject>()
        schemasByName["AskUserQuestion"] = askUserQuestionSchema()
        schemasByName["Read"] = readSchema()
        schemasByName["List"] = listSchema()
        schemasByName["Write"] = writeSchema()
        schemasByName["Edit"] = editSchema()
        schemasByName["Glob"] = globSchema()
        schemasByName["Grep"] = grepSchema()
        schemasByName["Bash"] = bashSchema()
        schemasByName["WebFetch"] = webFetchSchema()
        schemasByName["WebSearch"] = webSearchSchema()
        schemasByName["SlashCommand"] = slashCommandSchema()
        schemasByName["Skill"] = skillSchema()
        schemasByName["NotebookEdit"] = notebookEditSchema()
        schemasByName["lsp"] = lspSchema()
        schemasByName["Task"] = taskSchema()
        schemasByName["TodoWrite"] = todoWriteSchema()

        // Tool prompt injection (OpenCode-style templates).
        schemasByName["AskUserQuestion"] = schemasByName["AskUserQuestion"]!!.withDescription(
            ToolPrompts.render("question", promptVars),
        )
        schemasByName["Read"] = schemasByName["Read"]!!.withDescription(ToolPrompts.render("read", promptVars))
        schemasByName["List"] = schemasByName["List"]!!.withDescription(ToolPrompts.render("list", promptVars))
        schemasByName["Write"] = schemasByName["Write"]!!.withDescription(ToolPrompts.render("write", promptVars))
        schemasByName["Edit"] = schemasByName["Edit"]!!.withDescription(ToolPrompts.render("edit", promptVars))
        schemasByName["Glob"] = schemasByName["Glob"]!!.withDescription(ToolPrompts.render("glob", promptVars))
        schemasByName["Grep"] = schemasByName["Grep"]!!.withDescription(ToolPrompts.render("grep", promptVars))
        schemasByName["Bash"] = schemasByName["Bash"]!!.withDescription(ToolPrompts.render("bash", promptVars))
        schemasByName["WebFetch"] = schemasByName["WebFetch"]!!.withDescription(ToolPrompts.render("webfetch", promptVars))
        schemasByName["WebSearch"] = schemasByName["WebSearch"]!!.withDescription(ToolPrompts.render("websearch", promptVars))
        schemasByName["Task"] = schemasByName["Task"]!!.withDescription(ToolPrompts.render("task", promptVars))
        schemasByName["TodoWrite"] = schemasByName["TodoWrite"]!!.withDescription(ToolPrompts.render("todowrite", promptVars))

        // Skill prompt: include available skills list when possible.
        schemasByName["Skill"] =
            schemasByName["Skill"]!!.let { base ->
                val skills =
                    try {
                        indexSkills(projectDir = projectDir, fileSystem = ctx.fileSystem)
                    } catch (_: Throwable) {
                        emptyList()
                    }
                val availableSkills =
                    if (skills.isEmpty()) {
                        "  (none found)"
                    } else {
                        skills.joinToString("\n") { s ->
                            val desc = if (s.description.isNotBlank()) s.description else ""
                            listOf(
                                "  <skill>",
                                "    <name>${s.name}</name>",
                                if (desc.isNotEmpty()) "    <description>${desc}</description>" else "    <description />",
                                "  </skill>",
                            ).joinToString("\n")
                        }
                    }
                val hint =
                    skills.take(3).joinToString(", ") { "'${it.name}'" }.let { ex ->
                        if (ex.isEmpty()) "" else " (e.g., $ex, ...)"
                    }
                val rendered = ToolPrompts.render("skill", mapOf("available_skills" to availableSkills, "project_dir" to projectDir))
                base.withDescription(rendered).withParameterDescription(
                    toolName = "Skill",
                    paramName = "name",
                    description = "The skill identifier from <available_skills>.$hint",
                )
            }

        val out = mutableListOf<JsonObject>()
        for (name in toolNames) {
            val builtIn = schemasByName[name]
            if (builtIn != null) {
                out.add(builtIn)
                continue
            }
            if (registry != null) {
                val tool =
                    try {
                        registry.get(name)
                    } catch (_: Throwable) {
                        null
                    }
                val schema =
                    when (tool) {
                        is OpenAiSchemaTool -> tool.openAiSchema(ctx, registry)
                        else -> null
                    }
                if (schema != null) out.add(schema)
            }
        }
        return out
    }

    fun forResponses(
        toolNames: List<String>,
        registry: ToolRegistry? = null,
        ctx: ToolContext,
        taskAgents: List<TaskAgent> = emptyList(),
    ): List<JsonObject> {
        val schemas = forOpenAi(toolNames, registry = registry, ctx = ctx, taskAgents = taskAgents)
        val out = mutableListOf<JsonObject>()
        for (t in schemas) {
            val type = (t["type"] as? JsonPrimitive)?.contentOrNullSafe()
            if (type != "function") continue
            val fn = t["function"] as? JsonObject ?: continue
            val name = (fn["name"] as? JsonPrimitive)?.contentOrNullSafe()?.takeIf { it.isNotBlank() } ?: continue
            val desc = (fn["description"] as? JsonPrimitive)?.contentOrNullSafe()
            val params = (fn["parameters"] as? JsonObject) ?: buildJsonObject { put("type", JsonPrimitive("object")); put("properties", buildJsonObject { }) }
            out.add(
                buildJsonObject {
                    put("type", JsonPrimitive("function"))
                    put("name", JsonPrimitive(name))
                    if (!desc.isNullOrBlank()) put("description", JsonPrimitive(desc))
                    put("parameters", params)
                },
            )
        }
        return out
    }

    private fun askUserQuestionSchema(): JsonObject =
        schema(
            name = "AskUserQuestion",
            description = "Ask the user a clarifying question.",
            properties =
                buildJsonObject {
                    put(
                        "questions",
                        buildJsonObject {
                            put("type", JsonPrimitive("array"))
                            put(
                                "items",
                                buildJsonObject {
                                    put("type", JsonPrimitive("object"))
                                    put(
                                        "properties",
                                        buildJsonObject {
                                            put("question", buildJsonObject { put("type", JsonPrimitive("string")) })
                                            put("header", buildJsonObject { put("type", JsonPrimitive("string")) })
                                            put(
                                                "options",
                                                buildJsonObject {
                                                    put("type", JsonPrimitive("array"))
                                                    put(
                                                        "items",
                                                        buildJsonObject {
                                                            put("type", JsonPrimitive("object"))
                                                            put(
                                                                "properties",
                                                                buildJsonObject {
                                                                    put("label", buildJsonObject { put("type", JsonPrimitive("string")) })
                                                                    put("description", buildJsonObject { put("type", JsonPrimitive("string")) })
                                                                },
                                                            )
                                                            put("required", JsonArray(listOf(JsonPrimitive("label"))))
                                                        },
                                                    )
                                                },
                                            )
                                            put("multiple", buildJsonObject { put("type", JsonPrimitive("boolean")) })
                                            put("multiSelect", buildJsonObject { put("type", JsonPrimitive("boolean")) })
                                        },
                                    )
                                    put("required", JsonArray(listOf(JsonPrimitive("question"))))
                                },
                            )
                        },
                    )
                    put("question", buildJsonObject { put("type", JsonPrimitive("string")) })
                    put("options", buildJsonObject { put("type", JsonPrimitive("array")); put("items", buildJsonObject { put("type", JsonPrimitive("string")) }) })
                    put("choices", buildJsonObject { put("type", JsonPrimitive("array")); put("items", buildJsonObject { put("type", JsonPrimitive("string")) }) })
                    put("answers", buildJsonObject { put("type", JsonPrimitive("object")) })
                },
            required = emptyList(),
        )

    private fun readSchema(): JsonObject =
        schema(
            name = "Read",
            description = "Read a file from disk.",
            properties =
                buildJsonObject {
                    put("file_path", buildJsonObject { put("type", JsonPrimitive("string")) })
                    put("filePath", buildJsonObject { put("type", JsonPrimitive("string")) })
                    put("offset", buildJsonObject { put("type", JsonPrimitive("integer")) })
                    put("limit", buildJsonObject { put("type", JsonPrimitive("integer")) })
                },
        )

    private fun listSchema(): JsonObject =
        schema(
            name = "List",
            description = "List files under a directory.",
            properties =
                buildJsonObject {
                    put("path", buildJsonObject { put("type", JsonPrimitive("string")) })
                    put("dir", buildJsonObject { put("type", JsonPrimitive("string")) })
                    put("directory", buildJsonObject { put("type", JsonPrimitive("string")) })
                },
        )

    private fun writeSchema(): JsonObject =
        schema(
            name = "Write",
            description = "Create or overwrite a file.",
            properties =
                buildJsonObject {
                    put("file_path", buildJsonObject { put("type", JsonPrimitive("string")) })
                    put("filePath", buildJsonObject { put("type", JsonPrimitive("string")) })
                    put("content", buildJsonObject { put("type", JsonPrimitive("string")) })
                    put("overwrite", buildJsonObject { put("type", JsonPrimitive("boolean")) })
                },
        )

    private fun editSchema(): JsonObject =
        schema(
            name = "Edit",
            description = "Apply a precise edit (string replace) to a file.",
            properties =
                buildJsonObject {
                    put("file_path", buildJsonObject { put("type", JsonPrimitive("string")) })
                    put("filePath", buildJsonObject { put("type", JsonPrimitive("string")) })
                    put("old", buildJsonObject { put("type", JsonPrimitive("string")) })
                    put("new", buildJsonObject { put("type", JsonPrimitive("string")) })
                    put("old_string", buildJsonObject { put("type", JsonPrimitive("string")) })
                    put("new_string", buildJsonObject { put("type", JsonPrimitive("string")) })
                    put("oldString", buildJsonObject { put("type", JsonPrimitive("string")) })
                    put("newString", buildJsonObject { put("type", JsonPrimitive("string")) })
                    put("count", buildJsonObject { put("type", JsonPrimitive("integer")) })
                    put("replace_all", buildJsonObject { put("type", JsonPrimitive("boolean")) })
                    put("replaceAll", buildJsonObject { put("type", JsonPrimitive("boolean")) })
                    put("before", buildJsonObject { put("type", JsonPrimitive("string")) })
                    put("after", buildJsonObject { put("type", JsonPrimitive("string")) })
                },
        )

    private fun globSchema(): JsonObject =
        schema(
            name = "Glob",
            description = "Find files by glob pattern.",
            properties =
                buildJsonObject {
                    put("pattern", buildJsonObject { put("type", JsonPrimitive("string")) })
                    put("root", buildJsonObject { put("type", JsonPrimitive("string")) })
                },
            required = listOf("pattern"),
        )

    private fun grepSchema(): JsonObject =
        schema(
            name = "Grep",
            description = "Search file contents with a regex.",
            properties =
                buildJsonObject {
                    put("query", buildJsonObject { put("type", JsonPrimitive("string")) })
                    put("file_glob", buildJsonObject { put("type", JsonPrimitive("string")) })
                    put("root", buildJsonObject { put("type", JsonPrimitive("string")) })
                    put("case_sensitive", buildJsonObject { put("type", JsonPrimitive("boolean")) })
                },
            required = listOf("query"),
        )

    private fun bashSchema(): JsonObject =
        schema(
            name = "Bash",
            description = "Run a shell command.",
            properties =
                buildJsonObject {
                    put("command", buildJsonObject { put("type", JsonPrimitive("string")) })
                    put("workdir", buildJsonObject { put("type", JsonPrimitive("string")) })
                    put("timeout", buildJsonObject { put("type", JsonPrimitive("integer")) })
                    put("timeout_s", buildJsonObject { put("type", JsonPrimitive("number")) })
                },
            required = listOf("command"),
        )

    private fun webSearchSchema(): JsonObject =
        schema(
            name = "WebSearch",
            description = "Search the web (Tavily backend; falls back to DuckDuckGo HTML when TAVILY_API_KEY is missing).",
            properties =
                buildJsonObject {
                    put("query", buildJsonObject { put("type", JsonPrimitive("string")) })
                    put("max_results", buildJsonObject { put("type", JsonPrimitive("integer")) })
                    put("allowed_domains", buildJsonObject { put("type", JsonPrimitive("array")); put("items", buildJsonObject { put("type", JsonPrimitive("string")) }) })
                    put("blocked_domains", buildJsonObject { put("type", JsonPrimitive("array")); put("items", buildJsonObject { put("type", JsonPrimitive("string")) }) })
                },
            required = listOf("query"),
        )

    private fun webFetchSchema(): JsonObject =
        schema(
            name = "WebFetch",
            description = "Fetch a URL over HTTP(S).",
            properties =
                buildJsonObject {
                    put("url", buildJsonObject { put("type", JsonPrimitive("string")) })
                    put("headers", buildJsonObject { put("type", JsonPrimitive("object")) })
                    put(
                        "mode",
                        buildJsonObject {
                            put("type", JsonPrimitive("string"))
                            put("enum", JsonArray(listOf("markdown", "clean_html", "text", "raw").map { JsonPrimitive(it) }))
                        },
                    )
                    put("max_chars", buildJsonObject { put("type", JsonPrimitive("integer")); put("minimum", JsonPrimitive(1000)); put("maximum", JsonPrimitive(80000)) })
                    put("prompt", buildJsonObject { put("type", JsonPrimitive("string")) })
                },
            required = listOf("url"),
        )

    private fun slashCommandSchema(): JsonObject =
        schema(
            name = "SlashCommand",
            description = "Load and render a slash command by name (opencode-compatible).",
            properties =
                buildJsonObject {
                    put("name", buildJsonObject { put("type", JsonPrimitive("string")) })
                    put("args", buildJsonObject { put("type", JsonPrimitive("string")) })
                    put("arguments", buildJsonObject { put("type", JsonPrimitive("string")) })
                    put("project_dir", buildJsonObject { put("type", JsonPrimitive("string")) })
                },
            required = listOf("name"),
        )

    private fun skillSchema(): JsonObject =
        schema(
            name = "Skill",
            description = "Load a Skill by name.",
            properties =
                buildJsonObject {
                    put("name", buildJsonObject { put("type", JsonPrimitive("string")) })
                },
            required = listOf("name"),
        )

    private fun notebookEditSchema(): JsonObject =
        schema(
            name = "NotebookEdit",
            description = "Edit a Jupyter notebook (.ipynb).",
            properties =
                buildJsonObject {
                    put("notebook_path", buildJsonObject { put("type", JsonPrimitive("string")) })
                    put("cell_id", buildJsonObject { put("type", JsonPrimitive("string")) })
                    put("new_source", buildJsonObject { put("type", JsonPrimitive("string")) })
                    put(
                        "cell_type",
                        buildJsonObject {
                            put("type", JsonPrimitive("string"))
                            put("enum", JsonArray(listOf(JsonPrimitive("code"), JsonPrimitive("markdown"))))
                        },
                    )
                    put(
                        "edit_mode",
                        buildJsonObject {
                            put("type", JsonPrimitive("string"))
                            put("enum", JsonArray(listOf(JsonPrimitive("replace"), JsonPrimitive("insert"), JsonPrimitive("delete"))))
                        },
                    )
                },
            required = listOf("notebook_path"),
        )

    private fun lspSchema(): JsonObject =
        schema(
            name = "lsp",
            description = "Interact with Language Server Protocol (LSP) servers to get code intelligence features.",
            properties =
                buildJsonObject {
                    put(
                        "operation",
                        buildJsonObject {
                            put("type", JsonPrimitive("string"))
                            put(
                                "enum",
                                JsonArray(
                                    listOf(
                                        "goToDefinition",
                                        "findReferences",
                                        "hover",
                                        "documentSymbol",
                                        "workspaceSymbol",
                                        "goToImplementation",
                                        "prepareCallHierarchy",
                                        "incomingCalls",
                                        "outgoingCalls",
                                    ).map { JsonPrimitive(it) },
                                ),
                            )
                        },
                    )
                    put("filePath", buildJsonObject { put("type", JsonPrimitive("string")) })
                    put("file_path", buildJsonObject { put("type", JsonPrimitive("string")) })
                    put("line", buildJsonObject { put("type", JsonPrimitive("integer")); put("minimum", JsonPrimitive(1)) })
                    put("character", buildJsonObject { put("type", JsonPrimitive("integer")); put("minimum", JsonPrimitive(1)) })
                },
            required = listOf("operation", "filePath", "line", "character"),
        )

    private fun taskSchema(): JsonObject =
        schema(
            name = "Task",
            description = "Run a subagent by name.",
            properties =
                buildJsonObject {
                    put("agent", buildJsonObject { put("type", JsonPrimitive("string")) })
                    put("prompt", buildJsonObject { put("type", JsonPrimitive("string")) })
                },
            required = listOf("agent", "prompt"),
        )

    private fun todoWriteSchema(): JsonObject =
        schema(
            name = "TodoWrite",
            description = "Write or update a TODO list for the current session.",
            properties =
                buildJsonObject {
                    put(
                        "todos",
                        buildJsonObject {
                            put("type", JsonPrimitive("array"))
                            put(
                                "items",
                                buildJsonObject {
                                    put("type", JsonPrimitive("object"))
                                    put(
                                        "properties",
                                        buildJsonObject {
                                            put("content", buildJsonObject { put("type", JsonPrimitive("string")) })
                                            put(
                                                "status",
                                                buildJsonObject {
                                                    put("type", JsonPrimitive("string"))
                                                    put("enum", JsonArray(listOf(JsonPrimitive("pending"), JsonPrimitive("in_progress"), JsonPrimitive("completed"))))
                                                },
                                            )
                                            put("activeForm", buildJsonObject { put("type", JsonPrimitive("string")) })
                                        },
                                    )
                                    put("required", JsonArray(listOf(JsonPrimitive("content"), JsonPrimitive("status"), JsonPrimitive("activeForm"))))
                                },
                            )
                        },
                    )
                },
            required = listOf("todos"),
        )

    private fun schema(
        name: String,
        description: String,
        properties: JsonObject,
        required: List<String> = emptyList(),
    ): JsonObject {
        val params =
            buildJsonObject {
                put("type", JsonPrimitive("object"))
                put("properties", properties)
                if (required.isNotEmpty()) put("required", JsonArray(required.map { JsonPrimitive(it) }))
            }
        return buildJsonObject {
            put("type", JsonPrimitive("function"))
            put(
                "function",
                buildJsonObject {
                    put("name", JsonPrimitive(name))
                    put("description", JsonPrimitive(description))
                    put("parameters", params)
                },
            )
        }
    }
}

private fun JsonObject.withDescription(desc: String): JsonObject {
    val function = this["function"] as? JsonObject ?: return this
    val newFn =
        buildJsonObject {
            for ((k, v) in function) {
                if (k == "description") put(k, JsonPrimitive(desc)) else put(k, v)
            }
            if (!function.containsKey("description")) put("description", JsonPrimitive(desc))
        }
    return buildJsonObject {
        for ((k, v) in this@withDescription) {
            if (k == "function") put(k, newFn) else put(k, v)
        }
    }
}

private fun JsonObject.withParameterDescription(
    toolName: String,
    paramName: String,
    description: String,
): JsonObject {
    val function = this["function"] as? JsonObject ?: return this
    val parameters = function["parameters"] as? JsonObject ?: return this
    val properties = parameters["properties"] as? JsonObject ?: return this
    val prop = properties[paramName] as? JsonObject ?: return this
    val newProp =
        buildJsonObject {
            for ((k, v) in prop) put(k, v)
            put("description", JsonPrimitive(description))
        }
    val newProps =
        buildJsonObject {
            for ((k, v) in properties) {
                if (k == paramName) put(k, newProp) else put(k, v)
            }
        }
    val newParams =
        buildJsonObject {
            for ((k, v) in parameters) {
                if (k == "properties") put(k, newProps) else put(k, v)
            }
        }
    val newFn =
        buildJsonObject {
            for ((k, v) in function) {
                if (k == "parameters") put(k, newParams) else put(k, v)
            }
        }
    return buildJsonObject {
        for ((k, v) in this@withParameterDescription) {
            if (k == "function") put(k, newFn) else put(k, v)
        }
    }
}

private fun JsonPrimitive.contentOrNullSafe(): String? {
    return try {
        this.content
    } catch (_: Throwable) {
        null
    }
}

package me.lemonhall.openagentic.sdk.tools

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import me.lemonhall.openagentic.sdk.lsp.LspManager
import me.lemonhall.openagentic.sdk.lsp.OpenCodeConfig
import me.lemonhall.openagentic.sdk.lsp.parseLspConfig

class LspTool : Tool {
    override val name: String = "lsp"
    override val description: String =
        """
Interact with Language Server Protocol (LSP) servers to get code intelligence features.

Supported operations:
- goToDefinition: Find where a symbol is defined
- findReferences: Find all references to a symbol
- hover: Get hover information (documentation, type info) for a symbol
- documentSymbol: Get all symbols (functions, classes, variables) in a document
- workspaceSymbol: Search for symbols across the entire workspace
- goToImplementation: Find implementations of an interface or abstract method
- prepareCallHierarchy: Get call hierarchy item at a position (functions/methods)
- incomingCalls: Find all functions/methods that call the function at a position
- outgoingCalls: Find all functions/methods called by the function at a position

All operations require:
- filePath: The absolute or relative path to the file
- line: The line number (1-based)
- character: The character offset (1-based)

Note: LSP servers must be configured for the file type via OpenCode-style config (opencode.json / .opencode/opencode.json).
        """.trimIndent()

    @OptIn(ExperimentalSerializationApi::class)
    private val pretty = Json { prettyPrint = true; prettyPrintIndent = "  "; ignoreUnknownKeys = true; explicitNulls = false }

    override suspend fun run(
        input: ToolInput,
        ctx: ToolContext,
    ): ToolOutput {
        val op = input["operation"]?.asString()?.trim().orEmpty()
        require(op.isNotBlank()) { "lsp: 'operation' must be a non-empty string" }

        val filePathRaw = input["filePath"]?.asString()?.trim()
            ?: input["file_path"]?.asString()?.trim()
        require(!filePathRaw.isNullOrBlank()) { "lsp: 'filePath' must be a non-empty string" }

        val p = resolveToolPath(filePathRaw, ctx)
        require(ctx.fileSystem.exists(p)) { "File not found: $p" }

        val line = input["line"]?.asInt() ?: 0
        val character = input["character"]?.asInt() ?: 0
        require(line >= 1) { "lsp: 'line' must be an integer >= 1" }
        require(character >= 1) { "lsp: 'character' must be an integer >= 1" }

        val root = ctx.projectDir ?: ctx.cwd
        val cfg = OpenCodeConfig.loadMerged(fileSystem = ctx.fileSystem, projectRoot = root)
        val lspCfg = parseLspConfig(cfg)
        if (!lspCfg.enabled) throw RuntimeException("lsp: disabled by config")

        val resultObj =
            LspManager(cfg = cfg, projectRoot = root, fileSystem = ctx.fileSystem)
                .init()
                .use { mgr ->
                    mgr.op(operation = op, filePath = p, line0 = line - 1, character0 = character - 1)
                }

        val title = "$op $p:$line:$character"
        val empty = resultObj is JsonNull || (resultObj is kotlinx.serialization.json.JsonArray && resultObj.isEmpty())
        val output =
            if (empty) {
                "No results found for $op"
            } else {
                try {
                    pretty.encodeToString(JsonElement.serializer(), resultObj)
                } catch (_: Throwable) {
                    resultObj.toString()
                }
            }

        val out =
            buildJsonObject {
                put("title", JsonPrimitive(title))
                put("metadata", buildJsonObject { put("result", resultObj) })
                put("output", JsonPrimitive(output))
            }
        return ToolOutput.Json(out)
    }
}

private fun JsonElement.asString(): String? = (this as? JsonPrimitive)?.contentOrNull

private fun JsonElement.asInt(): Int? {
    val p = this as? JsonPrimitive ?: return null
    return try {
        p.contentOrNull?.toIntOrNull()
    } catch (_: Throwable) {
        p.contentOrNull?.toIntOrNull()
    }
}

private val JsonPrimitive.contentOrNull: String?
    get() = try {
        this.content
    } catch (_: Throwable) {
        null
    }

private inline fun <T : AutoCloseable, R> T.use(block: (T) -> R): R {
    try {
        return block(this)
    } finally {
        try {
            this.close()
        } catch (_: Throwable) {
        }
    }
}

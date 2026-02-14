package me.lemonhall.openagentic.sdk.tools

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import me.lemonhall.openagentic.sdk.json.asStringOrNull

class GlobTool : Tool {
    override val name: String = "Glob"
    override val description: String = "Find files by glob pattern."

    override suspend fun run(
        input: ToolInput,
        ctx: ToolContext,
    ): ToolOutput {
        val pattern = input["pattern"]?.asStringOrNull()?.trim().orEmpty()
        require(pattern.isNotEmpty()) { "Glob: 'pattern' must be a non-empty string" }

        val rootRaw =
            input["root"]?.asStringOrNull()?.trim().orEmpty()
                .ifEmpty { input["path"]?.asStringOrNull()?.trim().orEmpty() }

        val base = if (rootRaw.isNotEmpty()) resolveToolPath(rootRaw, ctx) else resolveToolPath(".", ctx)
        val baseNorm = base.normalized()

        val matches = mutableListOf<String>()
        for (p in ctx.fileSystem.listRecursively(baseNorm)) {
            val rel = p.relativeTo(baseNorm).toString()
            if (globMatch(pattern, rel)) matches.add(p.toString())
        }
        matches.sort()

        val out =
            buildJsonObject {
                put("root", JsonPrimitive(baseNorm.toString()))
                put("matches", JsonArray(matches.map { JsonPrimitive(it) }))
                put("search_path", JsonPrimitive(baseNorm.toString()))
                put("pattern", JsonPrimitive(pattern))
                put("count", JsonPrimitive(matches.size))
            }
        return ToolOutput.Json(out)
    }
}


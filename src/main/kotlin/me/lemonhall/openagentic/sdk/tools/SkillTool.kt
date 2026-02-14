package me.lemonhall.openagentic.sdk.tools

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import me.lemonhall.openagentic.sdk.json.asStringOrNull
import me.lemonhall.openagentic.sdk.skills.indexSkills
import me.lemonhall.openagentic.sdk.skills.parseSkillMarkdown
import me.lemonhall.openagentic.sdk.skills.stripFrontmatter
import okio.Path
import okio.Path.Companion.toPath

class SkillTool : Tool {
    override val name: String = "Skill"
    override val description: String = "Load a Skill by name."

    override suspend fun run(
        input: ToolInput,
        ctx: ToolContext,
    ): ToolOutput {
        val nameRaw = input["name"]?.asStringOrNull()?.trim().orEmpty()
        require(nameRaw.isNotEmpty()) { "Skill: 'name' must be a non-empty string" }

        val base =
            input["project_dir"]?.asStringOrNull()?.trim()?.takeIf { it.isNotEmpty() }?.let { resolveToolPath(it, ctx) }
                ?: (ctx.projectDir ?: ctx.cwd)

        val skills = indexSkills(projectDir = base.toString(), fileSystem = ctx.fileSystem)
        val match = skills.firstOrNull { it.name == nameRaw }
        if (match == null) {
            val available = skills.joinToString(", ") { it.name }
            if (available.isEmpty()) {
                throw java.io.FileNotFoundException("Skill: not found: $nameRaw. Available skills: none")
            }
            throw java.io.FileNotFoundException("Skill: not found: $nameRaw. Available skills: $available")
        }

        val path = match.path.normalizeToOkioPath()
        val raw = ctx.fileSystem.read(path) { readUtf8() }
        val doc = parseSkillMarkdown(raw)
        val body = stripFrontmatter(raw).trim()
        val baseDir = path.parent?.toString().orEmpty()
        val output =
            listOf(
                "## Skill: ${match.name}",
                "",
                "**Base directory**: $baseDir",
                "",
                body,
            ).joinToString("\n").trim()

        val out =
            buildJsonObject {
                put("title", JsonPrimitive("Loaded skill: ${match.name}"))
                put("output", JsonPrimitive(output))
                put(
                    "metadata",
                    buildJsonObject {
                        put("name", JsonPrimitive(match.name))
                        put("dir", JsonPrimitive(baseDir))
                    },
                )
                put("name", JsonPrimitive(match.name))
                put("description", JsonPrimitive(doc.description))
                put("summary", JsonPrimitive(doc.summary))
                put("checklist", JsonArray(doc.checklist.map { JsonPrimitive(it) }))
                put("path", JsonPrimitive(match.path))
            }
        return ToolOutput.Json(out)
    }
}

private fun String.normalizeToOkioPath(): Path = this.replace('\\', '/').toPath()


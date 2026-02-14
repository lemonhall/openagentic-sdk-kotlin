package me.lemonhall.openagentic.sdk.tools

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import me.lemonhall.openagentic.sdk.json.asStringOrNull
import okio.Path
import okio.Path.Companion.toPath

data class CommandTemplate(
    val name: String,
    val source: String,
    val content: String,
)

class SlashCommandTool : Tool {
    override val name: String = "SlashCommand"
    override val description: String = "Load and render a slash command by name (opencode-compatible)."

    override suspend fun run(
        input: ToolInput,
        ctx: ToolContext,
    ): ToolOutput {
        val nameRaw = input["name"]?.asStringOrNull()?.trim().orEmpty()
        require(nameRaw.isNotEmpty()) { "SlashCommand: 'name' must be a non-empty string" }

        val args =
            input["args"]?.asStringOrNull()
                ?: input["arguments"]?.asStringOrNull()
                ?: ""

        val base =
            input["project_dir"]?.asStringOrNull()?.trim()?.takeIf { it.isNotEmpty() }?.let { resolveToolPath(it, ctx) }
                ?: (ctx.projectDir ?: ctx.cwd)

        val tpl = loadCommandTemplate(name = nameRaw, base = base, ctx = ctx)
            ?: throw java.io.FileNotFoundException("SlashCommand: not found: $nameRaw")

        val rendered = renderCommandTemplate(tpl.content, args = args, worktreeRoot = findWorktreeRoot(base, ctx))
        val out =
            buildJsonObject {
                put("name", JsonPrimitive(nameRaw))
                put("path", JsonPrimitive(tpl.source))
                put("content", JsonPrimitive(rendered))
            }
        return ToolOutput.Json(out)
    }
}

private fun loadCommandTemplate(
    name: String,
    base: Path,
    ctx: ToolContext,
): CommandTemplate? {
    val candidates =
        listOf(
            base.resolve(".opencode").resolve("commands").resolve("$name.md"),
            base.resolve(".claude").resolve("commands").resolve("$name.md"),
            defaultGlobalOpencodeConfigDir().resolve("commands").resolve("$name.md"),
        )
    for (p in candidates) {
        if (ctx.fileSystem.exists(p) && ctx.fileSystem.metadata(p).isRegularFile) {
            val content = ctx.fileSystem.read(p) { readUtf8() }
            return CommandTemplate(name = name, source = p.toString(), content = content)
        }
    }
    return null
}

private fun defaultGlobalOpencodeConfigDir(): Path {
    val override = System.getenv("OPENCODE_CONFIG_DIR")?.trim().orEmpty()
    if (override.isNotEmpty()) return override.replace('\\', '/').toPath()
    val home = System.getProperty("user.home") ?: "."
    return "$home/.config/opencode".replace('\\', '/').toPath()
}

private fun findWorktreeRoot(
    start: Path,
    ctx: ToolContext,
): String {
    var cur = start.normalized()
    while (true) {
        val git = cur.resolve(".git")
        if (ctx.fileSystem.exists(git)) return cur.toString()
        val parent = cur.parent ?: break
        if (parent == cur) break
        cur = parent
    }
    return start.root?.toString() ?: "/"
}

private fun renderCommandTemplate(
    content: String,
    args: String,
    worktreeRoot: String,
): String {
    return content
        .replace("\${args}", args)
        .replace("\${path}", worktreeRoot)
        .replace("{{args}}", args)
        .replace("{{path}}", worktreeRoot)
}


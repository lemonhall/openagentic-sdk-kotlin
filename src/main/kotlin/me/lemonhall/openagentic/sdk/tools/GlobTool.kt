package me.lemonhall.openagentic.sdk.tools

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.yield
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import me.lemonhall.openagentic.sdk.json.asStringOrNull
import okio.Path

class GlobTool : Tool {
    override val name: String = "Glob"
    override val description: String = "Find files by glob pattern."

    private val maxMatches: Int = 5000

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

        val rx = globToRegex(pattern)
        val scanRoots = resolveScanRoots(baseNorm, pattern, ctx)

        val matches = mutableListOf<String>()
        var scanned = 0
        for (scanRoot in scanRoots) {
            for (p in ctx.fileSystem.listRecursively(scanRoot)) {
                scanned++
                if (scanned % 2048 == 0) {
                    currentCoroutineContext().ensureActive()
                    yield()
                }

                val rel = p.relativeTo(baseNorm).toString().replace('\\', '/')
                if (rx.matches(rel)) matches.add(p.toString())

                if (matches.size >= maxMatches) {
                    matches.sort()
                    val out =
                        buildJsonObject {
                            put("root", JsonPrimitive(baseNorm.toString()))
                            put("matches", JsonArray(matches.map { JsonPrimitive(it) }))
                            put("search_path", JsonPrimitive(baseNorm.toString()))
                            put("pattern", JsonPrimitive(pattern))
                            put("count", JsonPrimitive(matches.size))
                            put("truncated", JsonPrimitive(true))
                        }
                    return ToolOutput.Json(out)
                }
            }
        }
        matches.sort()

        val out =
            buildJsonObject {
                put("root", JsonPrimitive(baseNorm.toString()))
                put("matches", JsonArray(matches.map { JsonPrimitive(it) }))
                put("search_path", JsonPrimitive(baseNorm.toString()))
                put("pattern", JsonPrimitive(pattern))
                put("count", JsonPrimitive(matches.size))
                put("truncated", JsonPrimitive(false))
            }
        return ToolOutput.Json(out)
    }
}

private fun resolveScanRoots(
    baseNorm: Path,
    patternRaw: String,
    ctx: ToolContext,
): List<Path> {
    val pattern = patternRaw.trim().replace('\\', '/').trimStart('/')
    if (pattern.isEmpty()) return listOf(baseNorm)

    val components = pattern.split('/').filter { it.isNotEmpty() }
    val fixedPrefix =
        components.takeWhile { seg ->
            seg != "**" && !seg.contains('*') && !seg.contains('?') && !seg.contains('[')
        }

    if (fixedPrefix.isEmpty()) return listOf(baseNorm)

    val prefixDir = fixedPrefix.joinToString("/")
    val prefixPath = baseNorm.resolve(prefixDir).normalized()
    if (ctx.fileSystem.metadataOrNull(prefixPath)?.isDirectory != true) return emptyList()

    val rest = components.drop(fixedPrefix.size)
    if (rest.size >= 2) {
        val first = rest[0]
        val isDirGlob =
            first != "**" && (first.contains('*') || first.contains('?') || first.contains('['))
        if (isDirGlob) {
            val dirRx = globToRegex(first)
            val expanded =
                ctx.fileSystem
                    .list(prefixPath)
                    .filter { p ->
                        ctx.fileSystem.metadataOrNull(p)?.isDirectory == true && dirRx.matches(p.name)
                    }
                    .toList()
            if (expanded.isNotEmpty()) return expanded
        }
    }

    return listOf(prefixPath)
}


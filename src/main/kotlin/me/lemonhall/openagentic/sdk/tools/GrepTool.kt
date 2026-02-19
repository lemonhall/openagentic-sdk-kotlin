package me.lemonhall.openagentic.sdk.tools

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.yield
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import me.lemonhall.openagentic.sdk.json.asBooleanOrNull
import me.lemonhall.openagentic.sdk.json.asIntOrNull
import me.lemonhall.openagentic.sdk.json.asStringOrNull
import okio.Path

class GrepTool : Tool {
    override val name: String = "Grep"
    override val description: String = "Search file contents with a regex."

    private val maxMatches: Int = 5000
    private val maxFileBytes: Long = 2L * 1024L * 1024L

    override suspend fun run(
        input: ToolInput,
        ctx: ToolContext,
    ): ToolOutput {
        val query = input["query"]?.asStringOrNull()?.trim().orEmpty()
        require(query.isNotEmpty()) { "Grep: 'query' must be a non-empty string" }

        val fileGlob = input["file_glob"]?.asStringOrNull()?.trim().orEmpty().ifEmpty { "**/*" }
        require(fileGlob.isNotEmpty()) { "Grep: 'file_glob' must be a non-empty string" }

        val rootRaw =
            input["root"]?.asStringOrNull()?.trim().orEmpty()
                .ifEmpty { input["path"]?.asStringOrNull()?.trim().orEmpty() }
        val root = if (rootRaw.isNotEmpty()) resolveToolPath(rootRaw, ctx) else resolveToolPath(".", ctx)
        val rootNorm = root.normalized()

        val caseSensitive = input["case_sensitive"]?.asBooleanOrNull() != false
        val rx =
            if (caseSensitive) {
                Regex(query)
            } else {
                Regex(query, setOf(RegexOption.IGNORE_CASE))
            }

        val mode = input["mode"]?.asStringOrNull()?.trim().orEmpty().ifEmpty { "content" }
        require(mode.isNotEmpty()) { "Grep: 'mode' must be a string" }

        val beforeN = input["before_context"]?.asIntOrNull() ?: 0
        val afterN = input["after_context"]?.asIntOrNull() ?: 0
        require(beforeN >= 0) { "Grep: 'before_context' must be a non-negative integer" }
        require(afterN >= 0) { "Grep: 'after_context' must be a non-negative integer" }

        val fileGlobRx = globToRegex(fileGlob)

        val matches = mutableListOf<JsonObject>()
        val filesWithMatches = linkedSetOf<String>()
        val includeHidden = input["include_hidden"]?.asBooleanOrNull() != false

        val explicitFile = resolveExplicitFileOrNull(fileGlob, ctx)
        val scanRoots =
            when {
                explicitFile != null -> listOf(explicitFile)
                else -> resolveScanRoots(rootNorm, fileGlob, ctx)
            }

        var scanned = 0
        for (scanRoot in scanRoots) {
            val candidates =
                if (explicitFile != null) {
                    sequenceOf(scanRoot)
                } else {
                    ctx.fileSystem.listRecursively(scanRoot)
                }

            for (p in candidates) {
                scanned++
                if (scanned % 2048 == 0) {
                    currentCoroutineContext().ensureActive()
                    yield()
                }

                val md = ctx.fileSystem.metadataOrNull(p) ?: continue
                if (!md.isRegularFile) continue
                if (md.size != null && md.size!! > maxFileBytes) continue

                val rel = p.relativeTo(rootNorm).toString().replace('\\', '/')
                if (!fileGlobRx.matches(rel)) continue
            if (!includeHidden) {
                val segs = p.relativeTo(rootNorm).segments
                if (segs.any { it.startsWith(".") }) continue
            }

            val text =
                try {
                    ctx.fileSystem.read(p) { readUtf8() }
                } catch (_: Throwable) {
                    continue
                }
            val lines = text.split('\n').map { it.trimEnd('\r') }
            for (idx0 in lines.indices) {
                val line = lines[idx0]
                if (!rx.containsMatchIn(line)) continue
                filesWithMatches.add(p.toString())
                if (mode == "files_with_matches") continue

                val beforeCtx =
                    if (beforeN > 0) {
                        val start = maxOf(0, idx0 - beforeN)
                        lines.subList(start, idx0)
                    } else {
                        null
                    }
                val afterCtx =
                    if (afterN > 0) {
                        val end = minOf(lines.size, idx0 + 1 + afterN)
                        lines.subList(idx0 + 1, end)
                    } else {
                        null
                    }

                matches.add(
                    buildJsonObject {
                        put("file_path", JsonPrimitive(p.toString()))
                        put("line", JsonPrimitive(idx0 + 1))
                        put("text", JsonPrimitive(line))
                        put(
                            "before_context",
                            if (beforeCtx == null) JsonNull else JsonArray(beforeCtx.map { JsonPrimitive(it) }),
                        )
                        put(
                            "after_context",
                            if (afterCtx == null) JsonNull else JsonArray(afterCtx.map { JsonPrimitive(it) }),
                        )
                    },
                )

                if (matches.size >= maxMatches) {
                    val out =
                        buildJsonObject {
                            put("root", JsonPrimitive(rootNorm.toString()))
                            put("query", JsonPrimitive(query))
                            put("matches", JsonArray(matches))
                            put("truncated", JsonPrimitive(true))
                        }
                    return ToolOutput.Json(out)
                }
            }
        }
        }

        if (mode == "files_with_matches") {
            val files = filesWithMatches.toList().sorted()
            val out =
                buildJsonObject {
                    put("root", JsonPrimitive(rootNorm.toString()))
                    put("query", JsonPrimitive(query))
                    put("files", JsonArray(files.map { JsonPrimitive(it) }))
                    put("count", JsonPrimitive(files.size))
                }
            return ToolOutput.Json(out)
        }

        val out =
            buildJsonObject {
                put("root", JsonPrimitive(rootNorm.toString()))
                put("query", JsonPrimitive(query))
                put("matches", JsonArray(matches))
                put("truncated", JsonPrimitive(false))
                put("total_matches", JsonPrimitive(matches.size))
            }
        return ToolOutput.Json(out)
    }
}

private fun resolveExplicitFileOrNull(
    fileGlobRaw: String,
    ctx: ToolContext,
): Path? {
    val fileGlob = fileGlobRaw.trim()
    if (fileGlob.isEmpty()) return null
    if (fileGlob == "**/*") return null
    if (fileGlob.any { it == '*' || it == '?' || it == '[' }) return null

    val p =
        try {
            resolveToolPath(fileGlob, ctx).normalized()
        } catch (_: Throwable) {
            return null
        }
    return if (ctx.fileSystem.metadataOrNull(p)?.isRegularFile == true) p else null
}

private fun resolveScanRoots(
    rootNorm: Path,
    fileGlobRaw: String,
    ctx: ToolContext,
): List<Path> {
    val fileGlob = fileGlobRaw.trim().replace('\\', '/').trimStart('/')
    if (fileGlob.isEmpty() || fileGlob == "**/*") return listOf(rootNorm)

    val components = fileGlob.split('/').filter { it.isNotEmpty() }
    val fixedPrefix =
        components.takeWhile { seg ->
            seg != "**" && !seg.contains('*') && !seg.contains('?') && !seg.contains('[')
        }
    if (fixedPrefix.isEmpty()) return listOf(rootNorm)

    val prefixDir = fixedPrefix.joinToString("/")
    val prefixPath = rootNorm.resolve(prefixDir).normalized()
    if (ctx.fileSystem.metadataOrNull(prefixPath)?.isDirectory != true) return listOf(rootNorm)

    val rest = components.drop(fixedPrefix.size)
    if (rest.isNotEmpty()) {
        val first = rest[0]
        val isDirGlob = first != "**" && (first.contains('*') || first.contains('?') || first.contains('['))
        if (isDirGlob) {
            val dirRx = globToRegex(first)
            val expanded =
                ctx.fileSystem
                    .list(prefixPath)
                    .filter { p -> ctx.fileSystem.metadataOrNull(p)?.isDirectory == true && dirRx.matches(p.name) }
                    .toList()
            if (expanded.isNotEmpty()) return expanded
        }
    }

    return listOf(prefixPath)
}

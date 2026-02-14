package me.lemonhall.openagentic.sdk.tools

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath

class ListTool(
    private val limit: Int = 100,
) : Tool {
    override val name: String = "List"
    override val description: String = "List files under a directory."

    override suspend fun run(
        input: ToolInput,
        ctx: ToolContext,
    ): ToolOutput {
        val raw =
            coerceNonEmptyString(input["path"]?.toAny())
                ?: coerceNonEmptyString(input["dir"]?.toAny())
                ?: coerceNonEmptyString(input["directory"]?.toAny())
                ?: throw IllegalArgumentException("List: 'path' must be a non-empty string")

        val base = resolveToolPath(raw, ctx)
        val fs = ctx.fileSystem
        val meta = fs.metadataOrNull(base)
            ?: throw IllegalArgumentException("List: not found: $base")
        require(meta.isDirectory) { "List: not a directory: $base" }

        val files = collectFiles(fs = fs, root = base, limit = limit.coerceAtLeast(1))
        val rendered = renderTree(root = base, files = files)

        val obj =
            buildJsonObject {
                put("path", JsonPrimitive(base.toString()))
                put("count", JsonPrimitive(files.size))
                put("truncated", JsonPrimitive(files.size >= limit))
                put("output", JsonPrimitive(rendered))
            }
        return ToolOutput.Json(obj)
    }

    private fun collectFiles(
        fs: FileSystem,
        root: Path,
        limit: Int,
    ): List<Path> {
        val out = mutableListOf<Path>()

        fun walk(dir: Path) {
            if (out.size >= limit) return
            val relDir = safeRelativeTo(path = dir, root = root)
            if (shouldIgnore(relDir?.segments ?: emptyList())) return
            val children =
                try {
                    fs.list(dir).sortedBy { it.name }
                } catch (_: Throwable) {
                    emptyList()
                }
            for (child in children) {
                if (out.size >= limit) return
                val rel = safeRelativeTo(path = child, root = root) ?: continue
                if (shouldIgnore(rel.segments)) continue
                val meta = fs.metadataOrNull(child) ?: continue
                if (meta.isDirectory) {
                    walk(child)
                } else {
                    out.add(rel)
                    if (out.size >= limit) return
                }
            }
        }

        walk(root)
        return out
    }

    private fun renderTree(
        root: Path,
        files: List<Path>,
    ): String {
        val dirs = linkedSetOf<List<String>>()
        val filesByDir = linkedMapOf<List<String>, MutableList<String>>()
        dirs.add(emptyList())

        for (rel in files) {
            val segs = rel.segments
            val dirParts = if (segs.size <= 1) emptyList() else segs.dropLast(1)
            for (i in 0..dirParts.size) dirs.add(dirParts.take(i))
            filesByDir.getOrPut(dirParts) { mutableListOf() }.add(segs.last())
        }

        fun renderDir(prefix: List<String>, depth: Int): String {
            val indent = "  ".repeat(depth)
            val sb = StringBuilder()
            if (depth > 0 && prefix.isNotEmpty()) {
                sb.append(indent).append(prefix.last()).append("/\n")
            }
            val children =
                dirs
                    .filter { it.size == prefix.size + 1 && it.take(prefix.size) == prefix }
                    .sortedWith(compareBy({ it.size }, { it.joinToString("/") }))
            for (child in children) sb.append(renderDir(child, depth + 1))

            val childIndent = "  ".repeat(depth + 1)
            for (fn in filesByDir[prefix].orEmpty().sorted()) {
                sb.append(childIndent).append(fn).append("\n")
            }
            return sb.toString()
        }

        return buildString {
            append(root.toString()).append("/\n")
            append(renderDir(emptyList(), 0))
        }
    }

    private fun safeRelativeTo(
        path: Path,
        root: Path,
    ): Path? {
        val rootSegs = root.normalized().segments
        val segs = path.normalized().segments
        val ok = segs.size >= rootSegs.size && segs.subList(0, rootSegs.size) == rootSegs
        if (!ok) return null
        val relSegs = segs.drop(rootSegs.size)
        return relSegs.joinToString("/", prefix = "").toPathOrNull()
    }

    private fun shouldIgnore(parts: List<String>): Boolean {
        if (parts.isEmpty()) return false
        return parts.any { it in IGNORE_PREFIXES }
    }

    private fun JsonElement.toAny(): Any? {
        return when (this) {
            is JsonPrimitive -> if (this.isString) this.content else this.content
            else -> this.toString()
        }
    }

    private fun String.toPathOrNull(): Path? {
        return try {
            (if (this.isEmpty()) "." else this).replace('\\', '/').toPath()
        } catch (_: Throwable) {
            null
        }
    }

    private companion object {
        private val IGNORE_PREFIXES =
            setOf(
                "node_modules",
                "__pycache__",
                ".git",
                "dist",
                "build",
                "target",
                "vendor",
                ".idea",
                ".vscode",
                ".venv",
                "venv",
                "env",
                ".cache",
                "coverage",
                "tmp",
                "temp",
            )
    }
}

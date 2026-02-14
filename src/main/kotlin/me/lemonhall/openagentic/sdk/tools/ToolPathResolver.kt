package me.lemonhall.openagentic.sdk.tools

import okio.Path
import okio.Path.Companion.toPath
import java.nio.file.Paths

internal fun coerceNonEmptyString(v: Any?): String? {
    val s = (v as? String)?.trim().orEmpty()
    return s.ifEmpty { null }
}

internal fun resolveToolPath(
    filePath: String,
    ctx: ToolContext,
): Path {
    val base = ctx.projectDir ?: ctx.cwd
    val raw = filePath.trim()
    require(raw.isNotEmpty()) { "tool path must be non-empty" }

    // Okio Path is '/'-separated even on Windows; normalize incoming backslashes.
    val normalizedRaw = raw.replace('\\', '/')

    val p = normalizedRaw.toPath()
    val isWindowsAbs = Regex("^[a-zA-Z]:/").containsMatchIn(normalizedRaw)
    val resolved = if (p.isAbsolute || isWindowsAbs) p.normalized() else base.resolve(p).normalized()
    val base2 = base.normalized()

    // Enforce "under base" (purely lexical; does not resolve symlinks).
    val baseSegs = base2.segments
    val segs = resolved.segments
    val ok = segs.size >= baseSegs.size && segs.subList(0, baseSegs.size) == baseSegs
    require(ok) { "Tool path must be under project root: $base2" }

    // Prevent symlink escape: if any existing prefix of the resolved path canonicalizes outside base,
    // reject it. This catches cases like base/link -> /etc and file_path="link/passwd".
    val fs = ctx.fileSystem
    val baseCanon =
        try {
            fs.canonicalize(base2)
        } catch (_: Throwable) {
            base2
        }
    val existingPrefix = findNearestExistingPrefix(fs, resolved)
    if (existingPrefix != null) {
        val prefixCanon =
            try {
                fs.canonicalize(existingPrefix)
            } catch (_: Throwable) {
                existingPrefix.normalized()
            }
        require(isUnderBase(prefixCanon, baseCanon)) {
            "Tool path escapes project root via symlink: base=$baseCanon prefix=$prefixCanon"
        }

        val prefixReal = nioRealPathOrNull(existingPrefix)
        val baseReal = nioRealPathOrNull(base2)
        if (prefixReal != null && baseReal != null) {
            require(prefixReal.startsWith(baseReal)) {
                "Tool path escapes project root via symlink: base=$baseReal prefix=$prefixReal"
            }
        }
    }

    return resolved
}

private fun findNearestExistingPrefix(
    fs: okio.FileSystem,
    p: Path,
): Path? {
    var cur: Path? = p
    while (cur != null) {
        if (fs.exists(cur)) return cur
        cur = cur.parent
    }
    return null
}

private fun isUnderBase(
    child: Path,
    base: Path,
): Boolean {
    fun norm(s: String): String {
        val s2 = s.replace('\\', '/').trimEnd('/')
        val isWinAbs = Regex("^[a-zA-Z]:/").containsMatchIn(s2)
        return if (isWinAbs) s2.lowercase() else s2
    }
    val b = norm(base.toString())
    val c = norm(child.toString())
    return c == b || c.startsWith("$b/")
}

private fun nioRealPathOrNull(p: Path): java.nio.file.Path? {
    return try {
        Paths.get(p.toString()).toRealPath()
    } catch (_: Throwable) {
        null
    }
}

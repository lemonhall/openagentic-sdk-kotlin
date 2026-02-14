package me.lemonhall.openagentic.sdk.tools

import okio.Path
import okio.Path.Companion.toPath

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

    return resolved
}

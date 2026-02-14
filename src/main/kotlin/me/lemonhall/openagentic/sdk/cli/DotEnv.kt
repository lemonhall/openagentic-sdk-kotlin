package me.lemonhall.openagentic.sdk.cli

import okio.FileSystem
import okio.Path

internal fun loadDotEnv(
    fileSystem: FileSystem,
    path: Path,
): Map<String, String> {
    if (!fileSystem.exists(path)) return emptyMap()
    val raw =
        fileSystem.read(path) {
            readUtf8()
        }
    return parseDotEnv(raw)
}

internal fun parseDotEnv(raw: String): Map<String, String> {
    val out = linkedMapOf<String, String>()
    for (line0 in raw.split('\n')) {
        var line = line0.trim()
        if (line.isEmpty()) continue
        if (line.startsWith("#")) continue
        if (line.startsWith("export ")) line = line.removePrefix("export ").trimStart()

        val eq = line.indexOf('=')
        if (eq <= 0) continue

        val key = line.substring(0, eq).trim()
        if (key.isEmpty()) continue

        var value = line.substring(eq + 1).trim()
        if (value.isEmpty()) {
            out[key] = ""
            continue
        }

        val quote = value.firstOrNull()
        if (quote == '"' || quote == '\'') {
            val end = value.lastIndexOf(quote)
            if (end > 0) value = value.substring(1, end)
        } else {
            val hash = value.indexOf('#')
            if (hash >= 0) value = value.substring(0, hash).trimEnd()
        }

        out[key] = value
    }
    return out
}

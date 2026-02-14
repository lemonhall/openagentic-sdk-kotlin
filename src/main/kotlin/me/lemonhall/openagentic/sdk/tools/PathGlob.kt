package me.lemonhall.openagentic.sdk.tools

internal fun globMatch(
    pattern: String,
    path: String,
): Boolean = globToRegex(pattern).matches(path)

internal fun globToRegex(pattern: String): Regex {
    val p = pattern.trim().replace('\\', '/')
    require(p.isNotEmpty()) { "glob pattern must be non-empty" }

    val sb = StringBuilder()
    sb.append('^')

    var i = 0
    while (i < p.length) {
        val ch = p[i]
        when (ch) {
            '*' -> {
                val isDouble = i + 1 < p.length && p[i + 1] == '*'
                if (isDouble) {
                    val followedBySlash = i + 2 < p.length && p[i + 2] == '/'
                    if (followedBySlash) {
                        // **/ matches zero or more directories.
                        sb.append("(?:.*/)?")
                        i += 3
                    } else {
                        // ** matches anything (including '/')
                        sb.append(".*")
                        i += 2
                    }
                } else {
                    sb.append("[^/]*")
                    i += 1
                }
            }
            '?' -> {
                sb.append("[^/]")
                i += 1
            }
            '.', '+', '(', ')', '[', ']', '{', '}', '^', '$', '|', '\\' -> {
                sb.append('\\')
                sb.append(ch)
                i += 1
            }
            else -> {
                sb.append(ch)
                i += 1
            }
        }
    }

    sb.append('$')
    return Regex(sb.toString())
}

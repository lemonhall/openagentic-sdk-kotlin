package me.lemonhall.openagentic.sdk.skills

private fun parseFrontmatter(lines: List<String>): Pair<Map<String, String>, Int> {
    if (lines.isEmpty() || lines[0].trim() != "---") return emptyMap<String, String>() to 0
    val meta = linkedMapOf<String, String>()
    var i = 1
    while (i < lines.size) {
        val line = lines[i].trimEnd()
        if (line.trim() == "---") return meta to (i + 1)
        val idx = line.indexOf(':')
        if (idx >= 0) {
            val key = line.substring(0, idx).trim()
            val value = line.substring(idx + 1).trim().trim('\'', '"')
            if (key.isNotEmpty() && value.isNotEmpty()) {
                meta[key] = value
            }
        }
        i++
    }
    return emptyMap<String, String>() to 0
}

fun stripFrontmatter(text: String): String {
    val lines = text.split('\n')
    val (_, start) = parseFrontmatter(lines)
    if (start <= 0) return text
    val rest = lines.drop(start).joinToString("\n")
    return if (text.endsWith("\n")) "$rest\n" else rest
}

fun parseSkillMarkdown(text: String): SkillDoc {
    val lines = text.split('\n')
    val (meta, _) = parseFrontmatter(lines)

    var name = ""
    var titleIdx: Int? = null
    for ((i, line) in lines.withIndex()) {
        if (line.startsWith("# ")) {
            name = line.drop(2).trim()
            titleIdx = i
            break
        }
    }

    val fmName = meta["name"].orEmpty()
    if (fmName.isNotEmpty()) name = fmName

    var start = (titleIdx?.plus(1)) ?: 0
    while (start < lines.size && lines[start].isBlank()) start++
    val summaryLines = mutableListOf<String>()
    var i = start
    while (i < lines.size) {
        val line = lines[i]
        if (line.isBlank()) break
        if (line.trimStart().startsWith("#")) break
        summaryLines.add(line.trim())
        i++
    }
    val summary = summaryLines.joinToString("\n").trim()

    val checklist = mutableListOf<String>()
    var checklistIdx: Int? = null
    for ((j, line) in lines.withIndex()) {
        if (line.trim().lowercase() == "## checklist") {
            checklistIdx = j + 1
            break
        }
    }
    if (checklistIdx != null) {
        var k = checklistIdx
        while (k < lines.size) {
            val line = lines[k]
            val stripped = line.trim()
            if (stripped.startsWith("## ") || stripped.startsWith("#")) break
            val bullet = stripped.trimStart()
            if (bullet.startsWith("-") || bullet.startsWith("*")) {
                val item = bullet.drop(1).trim()
                if (item.isNotEmpty()) checklist.add(item)
            }
            k++
        }
    }

    return SkillDoc(
        name = name,
        description = meta["description"].orEmpty(),
        summary = summary,
        checklist = checklist,
        raw = text,
    )
}


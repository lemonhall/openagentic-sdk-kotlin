package me.lemonhall.openagentic.sdk.tools

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import me.lemonhall.openagentic.sdk.json.asBooleanOrNull
import me.lemonhall.openagentic.sdk.json.asIntOrNull
import me.lemonhall.openagentic.sdk.json.asStringOrNull

class EditTool : Tool {
    override val name: String = "Edit"
    override val description: String = "Apply a precise edit (string replace) to a file."

    override suspend fun run(
        input: ToolInput,
        ctx: ToolContext,
    ): ToolOutput {
        val filePath = input["file_path"]?.asStringOrNull()?.trim().orEmpty().ifEmpty { input["filePath"]?.asStringOrNull()?.trim().orEmpty() }
        val old = input["old"]?.asStringOrNull() ?: input["old_string"]?.asStringOrNull() ?: input["oldString"]?.asStringOrNull()
        val new = input["new"]?.asStringOrNull() ?: input["new_string"]?.asStringOrNull() ?: input["newString"]?.asStringOrNull()
        val replaceAll = (input["replace_all"] ?: input["replaceAll"])?.asBooleanOrNull() == true
        val countRaw = input["count"]?.asIntOrNull()
        val count = countRaw ?: if (replaceAll) 0 else 1
        val before = input["before"]?.asStringOrNull()?.takeIf { it.isNotBlank() }
        val after = input["after"]?.asStringOrNull()?.takeIf { it.isNotBlank() }

        require(filePath.isNotBlank()) { "Edit: 'file_path' must be a non-empty string" }
        require(!old.isNullOrEmpty()) { "Edit: 'old' must be a non-empty string" }
        require(new != null) { "Edit: 'new' must be a string" }
        require(count >= 0) { "Edit: 'count' must be a non-negative integer" }

        val p = resolveToolPath(filePath, ctx)
        val text = ctx.fileSystem.read(p) { readUtf8() }
        require(text.contains(old)) { "Edit: 'old' text not found in file" }

        if (before != null || after != null) {
            val idxOld = text.indexOf(old)
            val idxBefore = before?.let { text.indexOf(it) } ?: -1
            val idxAfter = after?.let { text.indexOf(it) } ?: -1
            if (before != null && idxBefore < 0) throw IllegalArgumentException("Edit: 'before' anchor not found in file")
            if (after != null && idxAfter < 0) throw IllegalArgumentException("Edit: 'after' anchor not found in file")
            if (before != null && idxBefore >= idxOld) throw IllegalArgumentException("Edit: 'before' must appear before 'old'")
            if (after != null && idxOld >= idxAfter) throw IllegalArgumentException("Edit: 'after' must appear after 'old'")
        }

        val occurrences = text.split(old).size - 1
        val replaced =
            if (count == 0) {
                text.replace(old, new)
            } else {
                replaceN(text, old, new, count)
            }
        ctx.fileSystem.write(p) { writeUtf8(replaced) }
        val replacements = if (count == 0) occurrences else minOf(occurrences, count)

        val out =
            buildJsonObject {
                put("message", JsonPrimitive("Edit applied"))
                put("file_path", JsonPrimitive(p.toString()))
                put("replacements", JsonPrimitive(replacements))
            }
        return ToolOutput.Json(out)
    }
}

private fun replaceN(
    text: String,
    old: String,
    new: String,
    count: Int,
): String {
    var cur = text
    var remaining = count
    while (remaining > 0) {
        val idx = cur.indexOf(old)
        if (idx < 0) break
        cur = cur.substring(0, idx) + new + cur.substring(idx + old.length)
        remaining--
    }
    return cur
}

package me.lemonhall.openagentic.sdk.tools

import java.util.Base64
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.lemonhall.openagentic.sdk.json.asStringOrNull

class ReadTool : Tool {
    override val name: String = "Read"
    override val description: String = "Read a file from disk."

    private val maxBytes: Int = 1024 * 1024

    override suspend fun run(
        input: ToolInput,
        ctx: ToolContext,
    ): ToolOutput {
        val filePath =
            input["file_path"]?.asStringOrNull()?.trim().orEmpty()
                .ifEmpty { input["filePath"]?.asStringOrNull()?.trim().orEmpty() }
        require(filePath.isNotEmpty()) { "Read: 'file_path' must be a non-empty string" }

        val p = resolveToolPath(filePath, ctx)

        val offset = coerceOptionalInt(input["offset"], name = "offset")?.let { if (it == 0) 1 else it }
        val limit = coerceOptionalInt(input["limit"], name = "limit")
        require(offset == null || offset >= 1) { "Read: 'offset' must be a positive integer (1-based)" }
        require(limit == null || limit >= 0) { "Read: 'limit' must be a non-negative integer" }

        var data = ctx.fileSystem.read(p) { readByteArray() }
        if (data.size > maxBytes) data = data.copyOf(maxBytes)

        val suffix = p.name.substringAfterLast('.', missingDelimiterValue = "").lowercase()
        if (suffix in setOf("png", "jpg", "jpeg", "gif", "webp")) {
            val mimeType =
                when (suffix) {
                    "png" -> "image/png"
                    "jpg", "jpeg" -> "image/jpeg"
                    "gif" -> "image/gif"
                    "webp" -> "image/webp"
                    else -> "application/octet-stream"
                }
            val imageB64 = Base64.getEncoder().encodeToString(data)
            val out =
                buildJsonObject {
                    put("file_path", JsonPrimitive(p.toString()))
                    put("image", JsonPrimitive(imageB64))
                    put("mime_type", JsonPrimitive(mimeType))
                    put("file_size", JsonPrimitive(data.size))
                }
            return ToolOutput.Json(out)
        }

        val text = data.toString(Charsets.UTF_8)
        var lines = text.split('\n').map { it.trimEnd('\r') }
        if (text.endsWith("\n") && lines.isNotEmpty() && lines.last().isEmpty()) {
            // Align with Python's splitlines(): drop exactly one trailing empty line caused by the final newline.
            lines = lines.dropLast(1)
        }

        if (offset != null || limit != null) {
            val start = (offset?.minus(1)) ?: 0
            val end = if (limit != null) minOf(lines.size, start + limit) else lines.size
            val slice = if (start in 0..lines.size) lines.subList(start, end) else emptyList()
            val numbered =
                buildString {
                    for ((i, line) in slice.withIndex()) {
                        append(start + i + 1)
                        append(": ")
                        append(line)
                        if (i + 1 < slice.size) append('\n')
                    }
                }
            val out =
                buildJsonObject {
                    put("file_path", JsonPrimitive(p.toString()))
                    put("content", JsonPrimitive(numbered))
                    put("total_lines", JsonPrimitive(lines.size))
                    put("lines_returned", JsonPrimitive(slice.size))
                }
            return ToolOutput.Json(out)
        }

        val out =
            buildJsonObject {
                put("file_path", JsonPrimitive(p.toString()))
                put("content", JsonPrimitive(text))
            }
        return ToolOutput.Json(out)
    }
}

private fun coerceOptionalInt(
    element: JsonElement?,
    name: String,
): Int? {
    if (element == null) return null
    val prim = element as? JsonPrimitive ?: throw IllegalArgumentException("Read: '$name' must be an integer")
    val raw = prim.content.trim()
    if (prim.isString && raw.isEmpty()) return null
    val n = raw.toIntOrNull()
    require(n != null) { "Read: '$name' must be an integer" }
    return n
}

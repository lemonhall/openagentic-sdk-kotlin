package me.lemonhall.openagentic.sdk.tools

import java.util.UUID
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.lemonhall.openagentic.sdk.json.asBooleanOrNull
import me.lemonhall.openagentic.sdk.json.asStringOrNull

class WriteTool : Tool {
    override val name: String = "Write"
    override val description: String = "Create or overwrite a file."

    override suspend fun run(
        input: ToolInput,
        ctx: ToolContext,
    ): ToolOutput {
        val filePath =
            coerceNonEmptyString(input["file_path"]?.asStringOrNull())
                ?: coerceNonEmptyString(input["filePath"]?.asStringOrNull())
        val content = input["content"]?.asStringOrNull()
        val overwrite = input["overwrite"]?.asBooleanOrNull() == true

        require(!filePath.isNullOrBlank()) { "Write: 'file_path' must be a non-empty string" }
        require(content != null) { "Write: 'content' must be a string" }

        val p = resolveToolPath(filePath, ctx)
        ctx.fileSystem.createDirectories(p.parent ?: ctx.cwd)
        if (ctx.fileSystem.exists(p) && !overwrite) {
            throw IllegalStateException("Write: file exists: $p")
        }

        val tmp = (p.parent ?: ctx.cwd).resolve(".${p.name}.${UUID.randomUUID().toString().replace("-", "")}.tmp")
        try {
            ctx.fileSystem.write(tmp) { writeUtf8(content) }
            ctx.fileSystem.atomicMove(tmp, p)
        } finally {
            if (ctx.fileSystem.exists(tmp)) {
                ctx.fileSystem.delete(tmp)
            }
        }

        val bytesWritten = content.encodeToByteArray().size
        val out =
            buildJsonObject {
                put("message", JsonPrimitive("Wrote $bytesWritten bytes"))
                put("file_path", JsonPrimitive(p.toString()))
                put("bytes_written", JsonPrimitive(bytesWritten))
            }
        return ToolOutput.Json(out)
    }
}

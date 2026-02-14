package me.lemonhall.openagentic.sdk.tools

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import okio.FileSystem
import okio.Path

data class ToolContext(
    val fileSystem: FileSystem,
    val cwd: Path,
    val projectDir: Path? = null,
)

typealias ToolInput = JsonObject

sealed interface ToolOutput {
    data class Json(
        val value: JsonElement?,
    ) : ToolOutput
}

interface Tool {
    val name: String
    val description: String

    suspend fun run(
        input: ToolInput,
        ctx: ToolContext,
    ): ToolOutput
}

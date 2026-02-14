package me.lemonhall.openagentic.sdk.tools

import java.util.UUID
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class NotebookEditTool : Tool {
    override val name: String = "NotebookEdit"
    override val description: String = "Edit a Jupyter notebook (.ipynb)."

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    @OptIn(ExperimentalSerializationApi::class)
    private val pretty = Json { prettyPrint = true; prettyPrintIndent = "  "; ignoreUnknownKeys = true; explicitNulls = false }

    override suspend fun run(
        input: ToolInput,
        ctx: ToolContext,
    ): ToolOutput {
        val notebookPath = input["notebook_path"]?.asString()?.trim().orEmpty()
        require(notebookPath.isNotBlank()) { "NotebookEdit: 'notebook_path' must be a non-empty string" }

        val p = resolveToolPath(notebookPath, ctx)
        require(ctx.fileSystem.exists(p)) { "NotebookEdit: not found: $p" }

        val cellId = input["cell_id"]?.asString()?.trim()
        val newSource = input["new_source"]?.asString() ?: ""
        val cellType = input["cell_type"]?.asString()?.trim()
        if (cellType != null && cellType.isNotBlank()) {
            require(cellType == "code" || cellType == "markdown") { "NotebookEdit: 'cell_type' must be 'code' or 'markdown'" }
        }
        val editMode = input["edit_mode"]?.asString()?.trim().orEmpty().ifBlank { "replace" }
        require(editMode in setOf("replace", "insert", "delete")) { "NotebookEdit: 'edit_mode' must be 'replace', 'insert', or 'delete'" }

        val raw = ctx.fileSystem.read(p) { readUtf8() }
        val nb = json.parseToJsonElement(raw) as? JsonObject ?: throw IllegalArgumentException("NotebookEdit: invalid notebook json")
        val cellsEl = nb["cells"] as? JsonArray ?: throw IllegalArgumentException("NotebookEdit: invalid notebook: missing 'cells' list")
        val cells = cellsEl.toMutableList()

        fun findIndex(): Int? {
            if (cellId.isNullOrBlank()) return if (cells.isEmpty()) null else 0
            for ((i, el) in cells.withIndex()) {
                val obj = el as? JsonObject ?: continue
                if (obj["id"]?.asString() == cellId) return i
            }
            return null
        }

        val idx = findIndex()

        if (editMode == "delete") {
            require(idx != null) { "NotebookEdit: cell_id not found" }
            val deleted = cells.removeAt(idx)
            val deletedId = (deleted as? JsonObject)?.get("id")?.asString()
            val nb2 = nb.toMutableMap()
            nb2["cells"] = JsonArray(cells)
            writeNotebook(ctx = ctx, path = p, nb = JsonObject(nb2))
            return ToolOutput.Json(
                buildJsonObject {
                    put("message", JsonPrimitive("Deleted cell"))
                    put("edit_type", JsonPrimitive("deleted"))
                    put("cell_id", if (deletedId != null) JsonPrimitive(deletedId) else JsonNull)
                    put("total_cells", JsonPrimitive(cells.size))
                },
            )
        }

        if (editMode == "insert") {
            val newId = (cellId?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString().replace("-", ""))
            val cell =
                buildJsonObject {
                    put("cell_type", JsonPrimitive(cellType?.takeIf { it.isNotBlank() } ?: "code"))
                    put("metadata", buildJsonObject { })
                    put("source", normalizeSource(newSource))
                    put("id", JsonPrimitive(newId))
                }
            val insertAt = if (idx != null) (idx + 1).coerceAtMost(cells.size) else cells.size
            cells.add(insertAt, cell)
            val nb2 = nb.toMutableMap()
            nb2["cells"] = JsonArray(cells)
            writeNotebook(ctx = ctx, path = p, nb = JsonObject(nb2))
            return ToolOutput.Json(
                buildJsonObject {
                    put("message", JsonPrimitive("Inserted cell"))
                    put("edit_type", JsonPrimitive("inserted"))
                    put("cell_id", JsonPrimitive(newId))
                    put("total_cells", JsonPrimitive(cells.size))
                },
            )
        }

        // replace
        require(idx != null) { "NotebookEdit: cell_id not found" }
        val cell0 = cells[idx] as? JsonObject ?: throw IllegalArgumentException("NotebookEdit: invalid cell")
        val cellMap = cell0.toMutableMap()
        if (cellType != null && cellType.isNotBlank()) cellMap["cell_type"] = JsonPrimitive(cellType)
        cellMap["source"] = normalizeSource(newSource)
        val replacedId = cellMap["id"]?.let { (it as? JsonPrimitive)?.contentOrNull }
        cells[idx] = JsonObject(cellMap)
        val nb2 = nb.toMutableMap()
        nb2["cells"] = JsonArray(cells)
        writeNotebook(ctx = ctx, path = p, nb = JsonObject(nb2))
        return ToolOutput.Json(
            buildJsonObject {
                put("message", JsonPrimitive("Replaced cell"))
                put("edit_type", JsonPrimitive("replaced"))
                put("cell_id", if (replacedId != null) JsonPrimitive(replacedId) else JsonNull)
                put("total_cells", JsonPrimitive(cells.size))
            },
        )
    }

    private fun normalizeSource(newSource: String): JsonArray {
        if (newSource.isEmpty()) return JsonArray(listOf(JsonPrimitive("")))
        return if (newSource.contains("\n")) {
            buildJsonArray {
                for (ln in newSource.split("\n")) add(JsonPrimitive(ln + "\n"))
            }
        } else {
            JsonArray(listOf(JsonPrimitive(newSource)))
        }
    }

    private fun writeNotebook(
        ctx: ToolContext,
        path: okio.Path,
        nb: JsonObject,
    ) {
        val text = pretty.encodeToString(JsonObject.serializer(), nb) + "\n"
        ctx.fileSystem.write(path) { writeUtf8(text) }
    }
}

private fun JsonElement.asString(): String? = (this as? JsonPrimitive)?.contentOrNull

private val JsonPrimitive.contentOrNull: String?
    get() = try {
        this.content
    } catch (_: Throwable) {
        null
    }

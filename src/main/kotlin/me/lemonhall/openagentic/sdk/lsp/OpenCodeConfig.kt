package me.lemonhall.openagentic.sdk.lsp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import okio.FileSystem
import okio.Path

object OpenCodeConfig {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    fun loadMerged(
        fileSystem: FileSystem,
        projectRoot: Path,
    ): JsonObject {
        val a = projectRoot.resolve("opencode.json")
        val b = projectRoot.resolve(".opencode").resolve("opencode.json")
        val base = readJsonObjectOrNull(fileSystem, a) ?: buildJsonObject { }
        val overlay = readJsonObjectOrNull(fileSystem, b) ?: buildJsonObject { }
        return deepMerge(base, overlay)
    }

    private fun readJsonObjectOrNull(
        fileSystem: FileSystem,
        path: Path,
    ): JsonObject? {
        return try {
            if (!fileSystem.exists(path)) return null
            val raw = fileSystem.read(path) { readUtf8() }
            val el = json.parseToJsonElement(raw)
            el as? JsonObject
        } catch (_: Throwable) {
            null
        }
    }

    private fun deepMerge(
        a: JsonObject,
        b: JsonObject,
    ): JsonObject {
        if (b.isEmpty()) return a
        if (a.isEmpty()) return b
        val out = linkedMapOf<String, JsonElement>()
        for ((k, v) in a) out[k] = v
        for ((k, v2) in b) {
            val v1 = out[k]
            out[k] =
                if (v1 is JsonObject && v2 is JsonObject) deepMerge(v1, v2) else v2
        }
        return JsonObject(out)
    }
}


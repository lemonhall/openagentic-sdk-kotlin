package me.lemonhall.openagentic.sdk.lsp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import okio.FileSystem
import okio.Path

class LspManager(
    private val cfg: JsonObject?,
    private val projectRoot: Path,
    private val fileSystem: FileSystem,
) : AutoCloseable {
    private val clients = linkedMapOf<String, StdioLspClient>()
    private val broken = linkedSetOf<String>()
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    private var enabled: Boolean = true
    private var servers: Map<String, LspServerDefinition> = emptyMap()

    fun init(): LspManager {
        // Validate shape (OpenCode parity).
        val parsed = parseLspConfig(cfg)
        val (en, reg) = buildServerRegistry(cfg = cfg ?: JsonObject(emptyMap()), fileSystem = fileSystem, workspaceDir = projectRoot)
        enabled = en
        servers = reg
        // keep parsed to validate; unused otherwise.
        @Suppress("UNUSED_EXPRESSION") parsed
        return this
    }

    override fun close() {
        for (c in clients.values) {
            try {
                c.close()
            } catch (_: Throwable) {
            }
        }
        clients.clear()
        broken.clear()
    }

    private fun fileKey(filePath: Path): String {
        val name = filePath.name
        val idx = name.lastIndexOf(".")
        return if (idx >= 0) name.substring(idx) else name
    }

    private fun matchingServers(filePath: Path): List<LspServerDefinition> {
        if (!enabled) return emptyList()
        val key = fileKey(filePath)
        val out = mutableListOf<LspServerDefinition>()
        for (s in servers.values) {
            if (s.extensions.isNotEmpty() && !s.extensions.contains(key) && !s.extensions.contains(filePath.name)) continue
            out.add(s)
        }
        return out
    }

    private fun getOrSpawn(server: LspServerDefinition, root: Path): StdioLspClient? {
        val key = root.toString() + "\u0000" + server.serverId
        if (broken.contains(key)) return null
        clients[key]?.let { return it }
        val cmd = server.command ?: return null
        return try {
            val c =
                StdioLspClient(
                    fileSystem = fileSystem,
                    command = cmd,
                    cwd = root,
                    env = server.env,
                    initializationOptions = server.initialization,
                    serverId = server.serverId,
                )
            c.ensureInitialized(root)
            clients[key] = c
            c
        } catch (_: Throwable) {
            broken.add(key)
            null
        }
    }

    fun op(
        operation: String,
        filePath: Path,
        line0: Int,
        character0: Int,
    ): JsonElement {
        val pairs = mutableListOf<Pair<StdioLspClient, LspServerDefinition>>()
        for (s in matchingServers(filePath)) {
            val root = s.root.resolve(filePath) ?: continue
            val c = getOrSpawn(s, root) ?: continue
            pairs.add(c to s)
        }
        if (pairs.isEmpty()) throw RuntimeException("No LSP server available for this file type.")

        for ((c, _) in pairs) {
            c.touchFile(filePath, waitForDiagnostics = true)
        }

        val uri = filePath.toFileUri()
        val results =
            pairs.map { (c, _) ->
                try {
                    when (operation) {
                        "goToDefinition" -> c.requestDefinition(uri, line0, character0)
                        "findReferences" -> c.requestReferences(uri, line0, character0)
                        "hover" -> c.requestHover(uri, line0, character0)
                        "documentSymbol" -> c.requestDocumentSymbol(uri)
                        "workspaceSymbol" -> c.requestWorkspaceSymbol("")
                        "goToImplementation" -> c.requestImplementation(uri, line0, character0)
                        "prepareCallHierarchy" -> c.requestPrepareCallHierarchy(uri, line0, character0)
                        "incomingCalls", "outgoingCalls" -> {
                            val items = c.requestPrepareCallHierarchy(uri, line0, character0)
                            val item0 = (items as? JsonArray)?.firstOrNull() as? JsonObject
                            if (item0 == null) JsonArray(emptyList())
                            else {
                                if (operation == "incomingCalls") c.requestIncomingCalls(item0) else c.requestOutgoingCalls(item0)
                            }
                        }
                        else -> throw IllegalArgumentException("Unknown LSP operation: $operation")
                    }
                } catch (t: Throwable) {
                    JsonNull
                }
            }

        if (operation == "hover") {
            return JsonArray(results)
        }
        if (operation == "workspaceSymbol") {
            val kinds = setOf(5, 6, 11, 12, 13, 14, 23, 10)
            val out = mutableListOf<JsonElement>()
            for (r in results) {
                val arr = r as? JsonArray ?: continue
                val filtered =
                    arr.filter { el ->
                        val obj = el as? JsonObject ?: return@filter false
                        val kind =
                            (obj["kind"] as? kotlinx.serialization.json.JsonPrimitive)?.let { p ->
                                val s = try { p.content } catch (_: Throwable) { "" }
                                s.toIntOrNull()
                            }
                        kind != null && kinds.contains(kind)
                    }
                out.addAll(filtered.take(10))
            }
            return JsonArray(out)
        }

        val flat = mutableListOf<JsonElement>()
        for (r in results) {
            if (r is JsonArray) {
                for (el in r) if (el !is JsonNull) flat.add(el)
            } else if (r !is JsonNull) {
                flat.add(r)
            }
        }
        return JsonArray(flat)
    }
}

private fun Path.toFileUri(): String {
    val s = this.toString().replace("\\", "/")
    val prefix = if (s.startsWith("/")) "file://" else "file:///"
    return prefix + s
}

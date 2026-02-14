package me.lemonhall.openagentic.sdk.lsp

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import okio.FileSystem
import okio.Path

internal class StdioLspClient(
    private val fileSystem: FileSystem,
    private val command: List<String>,
    private val cwd: Path,
    private val env: Map<String, String>?,
    private val initializationOptions: JsonObject?,
    private val serverId: String,
) {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val proc: Process
    private val conn: JsonRpcConnection
    private val opened = ConcurrentHashMap<String, Boolean>()
    private val diagLatches = ConcurrentHashMap<String, CountDownLatch>()

    init {
        val pb = ProcessBuilder(command)
        pb.directory(java.io.File(cwd.toString()))
        if (env != null) {
            val penv = pb.environment()
            for ((k, v) in env) penv[k] = v
        }
        proc = pb.start()
        conn =
            JsonRpcConnection(proc.inputStream, proc.outputStream) { method, params ->
                if (method == "textDocument/publishDiagnostics") {
                    val uri = (params as? JsonObject)?.get("uri")?.let { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
                    if (uri.isNotBlank()) {
                        diagLatches.remove(uri)?.countDown()
                    }
                }
            }
        conn.startReaderThread()
    }

    fun ensureInitialized(rootPath: Path) {
        val rootUri = rootPath.toFileUri()
        val initParams =
            buildJsonObject {
                put("processId", JsonNull)
                put("rootUri", JsonPrimitive(rootUri))
                put("capabilities", buildJsonObject { })
                if (initializationOptions != null) put("initializationOptions", initializationOptions)
                put(
                    "clientInfo",
                    buildJsonObject {
                        put("name", JsonPrimitive("openagentic-sdk-kotlin"))
                        put("version", JsonPrimitive("0.1"))
                    },
                )
            }
        conn.request("initialize", initParams)
        conn.notify("initialized", buildJsonObject { })
    }

    fun close() {
        try {
            conn.request("shutdown", buildJsonObject { })
        } catch (_: Throwable) {
        }
        try {
            conn.notify("exit", null)
        } catch (_: Throwable) {
        }
        try {
            proc.destroy()
        } catch (_: Throwable) {
        }
    }

    fun touchFile(
        filePath: Path,
        languageId: String? = null,
        waitForDiagnostics: Boolean = false,
    ) {
        val uri = filePath.toFileUri()
        val text = fileSystem.read(filePath) { readUtf8() }
        val lang = languageId ?: guessLanguageId(filePath)

        val latch =
            if (waitForDiagnostics) {
                val l = CountDownLatch(1)
                diagLatches[uri] = l
                l
            } else {
                null
            }

        if (opened.putIfAbsent(uri, true) == null) {
            val params =
                buildJsonObject {
                    put(
                        "textDocument",
                        buildJsonObject {
                            put("uri", JsonPrimitive(uri))
                            put("languageId", JsonPrimitive(lang))
                            put("version", JsonPrimitive(1))
                            put("text", JsonPrimitive(text))
                        },
                    )
                }
            conn.notify("textDocument/didOpen", params)
        } else {
            val params =
                buildJsonObject {
                    put(
                        "textDocument",
                        buildJsonObject {
                            put("uri", JsonPrimitive(uri))
                            put("version", JsonPrimitive(1))
                        },
                    )
                    put(
                        "contentChanges",
                        JsonArray(
                            listOf(
                                buildJsonObject { put("text", JsonPrimitive(text)) },
                            ),
                        ),
                    )
                }
            conn.notify("textDocument/didChange", params)
        }

        latch?.await(2, TimeUnit.SECONDS)
    }

    fun requestDefinition(
        uri: String,
        line0: Int,
        character0: Int,
    ): JsonElement =
        conn.request(
            "textDocument/definition",
            posParams(uri, line0, character0),
        )

    fun requestReferences(
        uri: String,
        line0: Int,
        character0: Int,
    ): JsonElement =
        conn.request(
            "textDocument/references",
            buildJsonObject {
                put("textDocument", buildJsonObject { put("uri", JsonPrimitive(uri)) })
                put("position", buildJsonObject { put("line", JsonPrimitive(line0)); put("character", JsonPrimitive(character0)) })
                put("context", buildJsonObject { put("includeDeclaration", JsonPrimitive(true)) })
            },
        )

    fun requestHover(
        uri: String,
        line0: Int,
        character0: Int,
    ): JsonElement =
        conn.request(
            "textDocument/hover",
            posParams(uri, line0, character0),
        )

    fun requestDocumentSymbol(uri: String): JsonElement =
        conn.request(
            "textDocument/documentSymbol",
            buildJsonObject { put("textDocument", buildJsonObject { put("uri", JsonPrimitive(uri)) }) },
        )

    fun requestWorkspaceSymbol(query: String): JsonElement =
        conn.request(
            "workspace/symbol",
            buildJsonObject { put("query", JsonPrimitive(query)) },
        )

    fun requestImplementation(
        uri: String,
        line0: Int,
        character0: Int,
    ): JsonElement =
        conn.request(
            "textDocument/implementation",
            posParams(uri, line0, character0),
        )

    fun requestPrepareCallHierarchy(
        uri: String,
        line0: Int,
        character0: Int,
    ): JsonElement =
        conn.request(
            "textDocument/prepareCallHierarchy",
            posParams(uri, line0, character0),
        )

    fun requestIncomingCalls(item: JsonObject): JsonElement =
        conn.request(
            "callHierarchy/incomingCalls",
            buildJsonObject { put("item", item) },
        )

    fun requestOutgoingCalls(item: JsonObject): JsonElement =
        conn.request(
            "callHierarchy/outgoingCalls",
            buildJsonObject { put("item", item) },
        )

    private fun posParams(
        uri: String,
        line0: Int,
        character0: Int,
    ): JsonObject =
        buildJsonObject {
            put("textDocument", buildJsonObject { put("uri", JsonPrimitive(uri)) })
            put("position", buildJsonObject { put("line", JsonPrimitive(line0)); put("character", JsonPrimitive(character0)) })
        }

    private fun guessLanguageId(filePath: Path): String {
        val name = filePath.name.lowercase()
        if (name.endsWith(".kt") || name.endsWith(".kts")) return "kotlin"
        if (name.endsWith(".java")) return "java"
        if (name.endsWith(".py")) return "python"
        if (name.endsWith(".ts")) return "typescript"
        if (name.endsWith(".js")) return "javascript"
        if (name.endsWith(".rs")) return "rust"
        if (name.endsWith(".go")) return "go"
        return "plaintext"
    }
}

private fun Path.toFileUri(): String {
    // Okio Path string is already platform-ish on JVM (e.g., C:/a/b).
    val s = this.toString()
    val normalized = s.replace("\\", "/")
    val prefix = if (normalized.startsWith("/")) "file://" else "file:///"
    return prefix + normalized
}

private val JsonPrimitive.contentOrNull: String?
    get() = try {
        this.content
    } catch (_: Throwable) {
        null
    }

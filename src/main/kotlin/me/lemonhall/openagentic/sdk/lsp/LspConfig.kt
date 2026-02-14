package me.lemonhall.openagentic.sdk.lsp

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

data class LspServerConfig(
    val serverId: String,
    val command: List<String>,
    val extensions: List<String> = emptyList(),
    val disabled: Boolean = false,
    val env: Map<String, String>? = null,
    val initialization: JsonObject? = null,
)

data class LspConfig(
    val enabled: Boolean,
    val servers: List<LspServerConfig> = emptyList(),
)

// Built-in LSP server ids from OpenCode's registry.
val BUILTIN_LSP_SERVER_IDS: Set<String> =
    setOf(
        "deno",
        "typescript",
        "vue",
        "eslint",
        "oxlint",
        "biome",
        "gopls",
        "ruby-lsp",
        "ty",
        "pyright",
        "elixir-ls",
        "zls",
        "csharp",
        "fsharp",
        "sourcekit-lsp",
        "rust",
        "clangd",
        "svelte",
        "astro",
        "jdtls",
        "kotlin-ls",
        "yaml-ls",
        "lua-ls",
        "php intelephense",
        "prisma",
        "dart",
        "ocaml-lsp",
        "bash",
        "terraform",
        "texlab",
        "dockerfile",
        "gleam",
        "clojure-lsp",
        "nixd",
        "tinymist",
        "haskell-language-server",
    )

fun parseLspConfig(cfg: JsonObject?): LspConfig {
    if (cfg == null) return LspConfig(enabled = true, servers = emptyList())
    val raw = cfg["lsp"]
    if (raw is JsonPrimitive && raw.asBooleanOrNull() == false) return LspConfig(enabled = false, servers = emptyList())
    if (raw == null) return LspConfig(enabled = true, servers = emptyList())
    val mapping = raw as? JsonObject ?: return LspConfig(enabled = true, servers = emptyList())

    val servers = mutableListOf<LspServerConfig>()
    for ((sid, specEl) in mapping) {
        if (sid.isBlank()) continue
        val spec = specEl as? JsonObject ?: continue
        val disabled = (spec["disabled"] as? JsonPrimitive)?.asBooleanOrNull() == true
        val cmdEl = spec["command"]
        val cmd = cmdEl.asStringListNonEmpty()
        if (disabled && cmd == null) {
            servers.add(LspServerConfig(serverId = sid, command = emptyList(), disabled = true))
            continue
        }
        require(cmd != null) { "lsp: server '$sid' missing valid command" }

        val extsPresent = spec.containsKey("extensions")
        val exts = if (extsPresent) spec["extensions"].asStringListAllowEmpty() else null
        if (!BUILTIN_LSP_SERVER_IDS.contains(sid) && !disabled && exts == null) {
            throw IllegalArgumentException("lsp: custom server '$sid' requires 'extensions'")
        }

        val env = spec["env"]?.asStringMap()
        val init = spec["initialization"] as? JsonObject

        servers.add(
            LspServerConfig(
                serverId = sid,
                command = cmd,
                extensions = exts ?: emptyList(),
                disabled = disabled,
                env = env,
                initialization = init,
            ),
        )
    }
    return LspConfig(enabled = true, servers = servers)
}

private fun JsonElement?.asStringListNonEmpty(): List<String>? {
    val arr = this as? JsonArray ?: return null
    if (arr.isEmpty()) return null
    val out = mutableListOf<String>()
    for (el in arr) {
        val s = (el as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
        if (s.isEmpty()) return null
        out.add(s)
    }
    return out
}

private fun JsonElement?.asStringListAllowEmpty(): List<String>? {
    val arr = this as? JsonArray ?: return null
    val out = mutableListOf<String>()
    for (el in arr) {
        val s = (el as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
        if (s.isEmpty()) return null
        out.add(s)
    }
    return out
}

private fun JsonElement?.asStringMap(): Map<String, String>? {
    val obj = this as? JsonObject ?: return null
    val out = linkedMapOf<String, String>()
    for ((k, v) in obj) {
        val pv = v as? JsonPrimitive ?: continue
        val s = pv.contentOrNull ?: continue
        out[k] = s
    }
    return out
}

private val JsonPrimitive.contentOrNull: String?
    get() = try {
        this.content
    } catch (_: Throwable) {
        null
    }

private fun JsonPrimitive.asBooleanOrNull(): Boolean? {
    val s = this.contentOrNull?.trim()?.lowercase() ?: return null
    return when (s) {
        "true" -> true
        "false" -> false
        else -> null
    }
}

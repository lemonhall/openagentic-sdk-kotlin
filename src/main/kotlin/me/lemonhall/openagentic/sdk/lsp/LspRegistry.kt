package me.lemonhall.openagentic.sdk.lsp

import kotlinx.serialization.json.JsonObject
import okio.FileSystem
import okio.Path

fun interface RootResolver {
    fun resolve(filePath: Path): Path?
}

data class LspServerDefinition(
    val serverId: String,
    val extensions: List<String>,
    val root: RootResolver,
    val command: List<String>?,
    val env: Map<String, String>? = null,
    val initialization: JsonObject? = null,
)

fun buildServerRegistry(
    cfg: JsonObject,
    fileSystem: FileSystem,
    workspaceDir: Path,
): Pair<Boolean, Map<String, LspServerDefinition>> {
    val lspRaw = cfg["lsp"]
    val disabled =
        (lspRaw as? kotlinx.serialization.json.JsonPrimitive)?.let { p ->
            val s = try { p.content } catch (_: Throwable) { "" }
            s.trim().lowercase() == "false"
        } == true
    if (disabled) return false to emptyMap()

    var servers = builtinServers(fileSystem = fileSystem, workspaceDir = workspaceDir).toMutableMap()
    val lspCfg = parseLspConfig(cfg)

    val experimentalTy = envFlag("OPENCODE_EXPERIMENTAL_LSP_TY")
    if (experimentalTy) servers.remove("pyright") else servers.remove("ty")

    for (s in lspCfg.servers) {
        if (s.disabled && s.command.isEmpty()) {
            servers.remove(s.serverId)
            continue
        }
        val existing = servers[s.serverId]
        val rootFn = existing?.root ?: RootResolver { workspaceDir.normalized() }
        val mergedExts = if (s.extensions.isNotEmpty()) s.extensions else existing?.extensions.orEmpty()
        servers[s.serverId] =
            LspServerDefinition(
                serverId = s.serverId,
                extensions = mergedExts,
                root = rootFn,
                command = s.command,
                env = s.env ?: existing?.env,
                initialization = s.initialization ?: existing?.initialization,
            )
    }

    return true to servers
}

private fun envFlag(name: String): Boolean {
    val v = System.getenv(name) ?: return false
    if (v == "") return true
    return v.trim().lowercase() !in setOf("0", "false", "no", "off")
}

private fun nearestRoot(
    fileSystem: FileSystem,
    workspaceDir: Path,
    filePath: Path,
    include: List<String>,
    exclude: List<String>? = null,
    required: Boolean = false,
): Path? {
    val ws = workspaceDir.normalized()
    val start = filePath.parent?.normalized() ?: return null
    if (!isUnder(start, ws)) return null

    var cur = start
    while (true) {
        if (exclude != null) {
            if (matchesAny(fileSystem, cur, exclude) != null) return null
        }
        val m = matchesAny(fileSystem, cur, include)
        if (m != null) return cur
        if (cur == ws) break
        val parent = cur.parent ?: break
        if (parent == cur) break
        cur = parent
    }
    if (required) return null
    return ws
}

private fun matchesAny(
    fileSystem: FileSystem,
    dirPath: Path,
    patterns: List<String>,
): Path? {
    for (pat in patterns) {
        if (pat.isBlank()) continue
        val hasGlob = pat.any { it in "*?[]" }
        if (hasGlob) {
            val rx = globToRegex(pat)
            val children = try { fileSystem.list(dirPath) } catch (_: Throwable) { emptyList() }
            for (c in children.sortedBy { it.name }) {
                if (rx.matches(c.name)) return c
            }
        } else {
            val p = dirPath.resolve(pat)
            if (fileSystem.exists(p)) return p
        }
    }
    return null
}

private fun globToRegex(pattern: String): Regex {
    val p = pattern.trim().replace('\\', '/')
    if (p.isEmpty()) return Regex("^$")
    val sb = StringBuilder()
    sb.append('^')
    var i = 0
    while (i < p.length) {
        val ch = p[i]
        when (ch) {
            '*' -> {
                sb.append(".*")
                i += 1
            }
            '?' -> {
                sb.append(".")
                i += 1
            }
            '.', '+', '(', ')', '[', ']', '{', '}', '^', '$', '|', '\\' -> {
                sb.append('\\').append(ch)
                i += 1
            }
            else -> {
                sb.append(ch)
                i += 1
            }
        }
    }
    sb.append('$')
    return Regex(sb.toString())
}

private fun isUnder(
    p: Path,
    root: Path,
): Boolean {
    val a = p.normalized().segments
    val b = root.normalized().segments
    return a.size >= b.size && a.subList(0, b.size) == b
}

private fun workspaceRoot(workspaceDir: Path): RootResolver = RootResolver { _ -> workspaceDir.normalized() }

private fun rootDeno(
    fileSystem: FileSystem,
    workspaceDir: Path,
): RootResolver =
    RootResolver { fp ->
        nearestRoot(fileSystem, workspaceDir, fp, include = listOf("deno.json", "deno.jsonc"), required = true)
    }

fun builtinServers(
    fileSystem: FileSystem,
    workspaceDir: Path,
): Map<String, LspServerDefinition> {
    val ws = workspaceDir.normalized()
    val lockfiles = listOf("package-lock.json", "bun.lockb", "bun.lock", "pnpm-lock.yaml", "yarn.lock")

    fun NR(
        include: List<String>,
        exclude: List<String>? = null,
        required: Boolean = false,
    ): RootResolver =
        RootResolver { fp ->
            nearestRoot(fileSystem, ws, fp, include = include, exclude = exclude, required = required)
        }

    val servers = linkedMapOf<String, LspServerDefinition>()

    servers["deno"] = LspServerDefinition("deno", listOf(".ts", ".tsx", ".js", ".jsx", ".mjs"), rootDeno(fileSystem, ws), listOf("deno", "lsp"))
    servers["typescript"] =
        LspServerDefinition(
            "typescript",
            listOf(".ts", ".tsx", ".js", ".jsx", ".mjs", ".cjs", ".mts", ".cts"),
            NR(lockfiles, exclude = listOf("deno.json", "deno.jsonc")),
            listOf("typescript-language-server", "--stdio"),
        )
    servers["vue"] = LspServerDefinition("vue", listOf(".vue"), NR(lockfiles), listOf("vue-language-server", "--stdio"))
    servers["eslint"] =
        LspServerDefinition(
            "eslint",
            listOf(".ts", ".tsx", ".js", ".jsx", ".mjs", ".cjs", ".mts", ".cts", ".vue"),
            NR(lockfiles),
            listOf("vscode-eslint-language-server", "--stdio"),
        )
    servers["oxlint"] =
        LspServerDefinition(
            "oxlint",
            listOf(".ts", ".tsx", ".js", ".jsx", ".mjs", ".cjs", ".mts", ".cts", ".vue", ".astro", ".svelte"),
            NR(listOf(".oxlintrc.json") + lockfiles + listOf("package.json")),
            listOf("oxc_language_server"),
        )
    servers["biome"] =
        LspServerDefinition(
            "biome",
            listOf(".ts", ".tsx", ".js", ".jsx", ".mjs", ".cjs", ".mts", ".cts", ".json", ".jsonc", ".vue", ".astro", ".svelte", ".css", ".graphql", ".gql", ".html"),
            NR(listOf("biome.json", "biome.jsonc") + lockfiles),
            listOf("biome", "lsp-proxy", "--stdio"),
        )
    servers["gopls"] =
        LspServerDefinition(
            "gopls",
            listOf(".go"),
            RootResolver { fp ->
                nearestRoot(fileSystem, ws, fp, include = listOf("go.work"), required = false)
                    ?: nearestRoot(fileSystem, ws, fp, include = listOf("go.mod", "go.sum"), required = false)
            },
            listOf("gopls"),
        )
    servers["ruby-lsp"] = LspServerDefinition("ruby-lsp", listOf(".rb", ".rake", ".gemspec", ".ru"), NR(listOf("Gemfile"), required = true), listOf("rubocop", "--lsp"))
    servers["ty"] =
        LspServerDefinition(
            "ty",
            listOf(".py", ".pyi"),
            NR(listOf("pyproject.toml", "ty.toml", "setup.py", "setup.cfg", "requirements.txt", "Pipfile", "pyrightconfig.json")),
            listOf("ty", "server"),
        )
    servers["pyright"] =
        LspServerDefinition(
            "pyright",
            listOf(".py", ".pyi"),
            NR(listOf("pyproject.toml", "setup.py", "setup.cfg", "requirements.txt", "Pipfile", "pyrightconfig.json")),
            listOf("pyright-langserver", "--stdio"),
        )
    servers["elixir-ls"] = LspServerDefinition("elixir-ls", listOf(".ex", ".exs"), NR(listOf("mix.exs", "mix.lock"), required = true), listOf("elixir-ls"))
    servers["zls"] = LspServerDefinition("zls", listOf(".zig", ".zon"), NR(listOf("build.zig"), required = true), listOf("zls"))
    servers["csharp"] = LspServerDefinition("csharp", listOf(".cs"), NR(listOf(".sln", ".csproj", "global.json")), listOf("csharp-ls"))
    servers["fsharp"] = LspServerDefinition("fsharp", listOf(".fs", ".fsi", ".fsx", ".fsscript"), NR(listOf(".sln", ".fsproj", "global.json")), listOf("fsautocomplete"))
    servers["sourcekit-lsp"] = LspServerDefinition("sourcekit-lsp", listOf(".swift", ".m", ".mm"), NR(listOf("Package.swift", "*.xcodeproj", "*.xcworkspace")), listOf("sourcekit-lsp"))

    servers["rust"] =
        LspServerDefinition(
            "rust",
            listOf(".rs"),
            RootResolver { fp ->
                val crate =
                    nearestRoot(fileSystem, ws, fp, include = listOf("Cargo.toml", "Cargo.lock"), required = true) ?: return@RootResolver null
                var cur = crate
                while (true) {
                    val cargo = cur.resolve("Cargo.toml")
                    try {
                        if (fileSystem.exists(cargo)) {
                            val txt = fileSystem.read(cargo) { readUtf8() }
                            if (txt.contains("[workspace]")) return@RootResolver cur
                        }
                    } catch (_: Throwable) {
                        // ignore
                    }
                    if (cur == ws) break
                    val parent = cur.parent ?: break
                    if (parent == cur) break
                    cur = parent
                }
                crate
            },
            listOf("rust-analyzer"),
        )

    servers["clangd"] =
        LspServerDefinition(
            "clangd",
            listOf(".c", ".cpp", ".cc", ".cxx", ".h", ".hpp", ".hh", ".hxx"),
            NR(listOf("compile_commands.json", "compile_flags.txt", ".clangd", "CMakeLists.txt", "Makefile")),
            listOf("clangd"),
        )

    servers["svelte"] = LspServerDefinition("svelte", listOf(".svelte"), NR(lockfiles), listOf("svelteserver", "--stdio"))
    servers["astro"] = LspServerDefinition("astro", listOf(".astro"), NR(lockfiles), listOf("astro-ls", "--stdio"))

    servers["jdtls"] = LspServerDefinition("jdtls", listOf(".java"), NR(listOf("pom.xml", "build.gradle", "build.gradle.kts", ".project", ".classpath")), listOf("jdtls"))
    servers["kotlin-ls"] =
        LspServerDefinition(
            "kotlin-ls",
            listOf(".kt", ".kts"),
            RootResolver { fp ->
                nearestRoot(fileSystem, ws, fp, include = listOf("settings.gradle.kts", "settings.gradle"), required = true)
                    ?: nearestRoot(fileSystem, ws, fp, include = listOf("gradlew", "gradlew.bat"), required = true)
                    ?: nearestRoot(fileSystem, ws, fp, include = listOf("build.gradle.kts", "build.gradle"), required = true)
                    ?: nearestRoot(fileSystem, ws, fp, include = listOf("pom.xml"), required = true)
            },
            listOf("kotlin-ls", "--stdio"),
        )
    servers["yaml-ls"] = LspServerDefinition("yaml-ls", listOf(".yaml", ".yml"), NR(lockfiles), listOf("yaml-language-server", "--stdio"))
    servers["lua-ls"] =
        LspServerDefinition(
            "lua-ls",
            listOf(".lua"),
            NR(listOf(".luarc.json", ".luarc.jsonc", ".luacheckrc", ".stylua.toml", "stylua.toml", "selene.toml", "selene.yml")),
            listOf("lua-language-server"),
        )
    servers["php intelephense"] = LspServerDefinition("php intelephense", listOf(".php"), NR(listOf("composer.json", "composer.lock", ".php-version")), listOf("intelephense", "--stdio"))
    servers["prisma"] = LspServerDefinition("prisma", listOf(".prisma"), NR(listOf("schema.prisma", "prisma/schema.prisma", "prisma"), exclude = listOf("package.json")), listOf("prisma", "language-server"))
    servers["dart"] = LspServerDefinition("dart", listOf(".dart"), NR(listOf("pubspec.yaml", "analysis_options.yaml")), listOf("dart", "language-server", "--lsp"))
    servers["ocaml-lsp"] = LspServerDefinition("ocaml-lsp", listOf(".ml", ".mli"), NR(listOf("dune-project", "dune-workspace", ".merlin", "opam")), listOf("ocamllsp"))

    servers["bash"] = LspServerDefinition("bash", listOf(".sh", ".bash", ".zsh", ".ksh"), workspaceRoot(ws), listOf("bash-language-server", "start"))
    servers["terraform"] = LspServerDefinition("terraform", listOf(".tf", ".tfvars"), NR(listOf(".terraform.lock.hcl", "terraform.tfstate", "*.tf")), listOf("terraform-ls", "serve"))
    servers["texlab"] = LspServerDefinition("texlab", listOf(".tex", ".bib"), NR(listOf(".latexmkrc", "latexmkrc", ".texlabroot", "texlabroot")), listOf("texlab"))
    servers["dockerfile"] = LspServerDefinition("dockerfile", listOf(".dockerfile", "Dockerfile"), workspaceRoot(ws), listOf("docker-langserver", "--stdio"))

    servers["gleam"] = LspServerDefinition("gleam", listOf(".gleam"), NR(listOf("gleam.toml")), listOf("gleam", "lsp"))
    servers["clojure-lsp"] = LspServerDefinition("clojure-lsp", listOf(".clj", ".cljs", ".cljc", ".edn"), NR(listOf("deps.edn", "project.clj", "shadow-cljs.edn", "bb.edn", "build.boot")), listOf("clojure-lsp", "listen"))
    servers["nixd"] =
        LspServerDefinition(
            "nixd",
            listOf(".nix"),
            RootResolver { fp ->
                nearestRoot(fileSystem, ws, fp, include = listOf("flake.nix"), required = true) ?: ws
            },
            listOf("nixd"),
        )
    servers["tinymist"] = LspServerDefinition("tinymist", listOf(".typ", ".typc"), NR(listOf("typst.toml")), listOf("tinymist"))
    servers["haskell-language-server"] = LspServerDefinition("haskell-language-server", listOf(".hs", ".lhs"), NR(listOf("stack.yaml", "cabal.project", "hie.yaml", "*.cabal")), listOf("haskell-language-server-wrapper", "--lsp"))

    return servers
}

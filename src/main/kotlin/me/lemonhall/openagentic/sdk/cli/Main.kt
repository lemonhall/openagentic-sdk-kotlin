package me.lemonhall.openagentic.sdk.cli

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.lemonhall.openagentic.sdk.compaction.CompactionOptions
import me.lemonhall.openagentic.sdk.events.AssistantDelta
import me.lemonhall.openagentic.sdk.events.AssistantMessage
import me.lemonhall.openagentic.sdk.events.Result
import me.lemonhall.openagentic.sdk.events.SystemInit
import me.lemonhall.openagentic.sdk.events.ToolResult
import me.lemonhall.openagentic.sdk.events.ToolUse
import me.lemonhall.openagentic.sdk.events.UserQuestion
import me.lemonhall.openagentic.sdk.permissions.PermissionGate
import me.lemonhall.openagentic.sdk.permissions.PermissionMode
import me.lemonhall.openagentic.sdk.permissions.UserAnswerer
import me.lemonhall.openagentic.sdk.providers.OpenAIResponsesHttpProvider
import me.lemonhall.openagentic.sdk.runtime.OpenAgenticOptions
import me.lemonhall.openagentic.sdk.runtime.OpenAgenticSdk
import me.lemonhall.openagentic.sdk.sessions.FileSessionStore
import me.lemonhall.openagentic.sdk.skills.OpenAgenticPaths
import me.lemonhall.openagentic.sdk.tools.BashTool
import me.lemonhall.openagentic.sdk.tools.EditTool
import me.lemonhall.openagentic.sdk.tools.GlobTool
import me.lemonhall.openagentic.sdk.tools.GrepTool
import me.lemonhall.openagentic.sdk.tools.ListTool
import me.lemonhall.openagentic.sdk.tools.LspTool
import me.lemonhall.openagentic.sdk.tools.NotebookEditTool
import me.lemonhall.openagentic.sdk.tools.ReadTool
import me.lemonhall.openagentic.sdk.tools.SkillTool
import me.lemonhall.openagentic.sdk.tools.SlashCommandTool
import me.lemonhall.openagentic.sdk.tools.TodoWriteTool
import me.lemonhall.openagentic.sdk.tools.ToolRegistry
import me.lemonhall.openagentic.sdk.tools.WebSearchTool
import me.lemonhall.openagentic.sdk.tools.WebFetchTool
import me.lemonhall.openagentic.sdk.tools.WriteTool
import okio.FileSystem
import okio.Path.Companion.toPath
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    runBlocking {
        val parsed = parseArgs(args.toList())
        if (parsed.help) {
            printUsage()
            return@runBlocking
        }

        val cmd = parsed.command.ifBlank { "chat" }
        when (cmd) {
            "chat" -> runChat(parsed)
            "run" -> runOnce(parsed)
            else -> {
                System.err.println("Unknown command: $cmd")
                printUsage()
            }
        }
    }
}

private data class CliArgs(
    val help: Boolean,
    val command: String,
    val model: String,
    val apiKey: String,
    val baseUrl: String,
    val apiKeyHeader: String,
    val openAiStore: Boolean,
    val projectDir: String,
    val prompt: String,
    val resume: String,
    val stream: Boolean,
    val permissionMode: String,
    val contextLimit: Int,
    val reserved: Int?,
    val inputLimit: Int?,
    val maxSteps: Int,
)

private fun parseArgs(args: List<String>): CliArgs {
    var help = false
    var command = ""
    var model = ""
    var apiKey = ""
    var baseUrl = ""
    var apiKeyHeader = "authorization"
    var openAiStore = true
    var projectDir = ""
    var prompt = ""
    var resume = ""
    var stream = true
    var permissionMode = "default"
    var contextLimit = 0
    var reserved: Int? = null
    var inputLimit: Int? = null
    var maxSteps = 20

    var i = 0
    while (i < args.size) {
        when (val a = args[i]) {
            "-h", "--help" -> help = true
            "chat", "run" -> if (command.isBlank()) command = a
            "--model" -> model = args.getOrNull(++i).orEmpty()
            "--api-key" -> apiKey = args.getOrNull(++i).orEmpty()
            "--base-url" -> baseUrl = args.getOrNull(++i).orEmpty()
            "--api-key-header" -> apiKeyHeader = args.getOrNull(++i).orEmpty()
            "--openai-store" -> openAiStore = (args.getOrNull(++i)?.trim()?.lowercase() ?: "true") !in setOf("0", "false", "no", "off")
            "--no-openai-store" -> openAiStore = false
            "--cwd" -> projectDir = args.getOrNull(++i).orEmpty() // legacy alias; prefer --project-dir
            "--project-dir" -> projectDir = args.getOrNull(++i).orEmpty()
            "--prompt" -> prompt = args.getOrNull(++i).orEmpty()
            "--resume" -> resume = args.getOrNull(++i).orEmpty()
            "--stream" -> stream = true
            "--no-stream" -> stream = false
            "--permission" -> permissionMode = args.getOrNull(++i).orEmpty()
            "--context-limit" -> contextLimit = args.getOrNull(++i)?.toIntOrNull() ?: contextLimit
            "--reserved" -> reserved = args.getOrNull(++i)?.toIntOrNull()
            "--input-limit" -> inputLimit = args.getOrNull(++i)?.toIntOrNull()
            "--max-steps" -> maxSteps = args.getOrNull(++i)?.toIntOrNull() ?: maxSteps
            else -> {
                // Treat remaining args as prompt when not using --prompt.
                prompt = (listOf(a) + args.drop(i + 1)).joinToString(" ")
                break
            }
        }
        i++
    }

    return CliArgs(
        help = help,
        command = command.trim(),
        model = model.trim(),
        apiKey = apiKey.trim(),
        baseUrl = baseUrl.trim(),
        apiKeyHeader = apiKeyHeader.trim().ifBlank { "authorization" },
        openAiStore = openAiStore,
        projectDir = projectDir.trim(),
        prompt = prompt,
        resume = resume.trim(),
        stream = stream,
        permissionMode = permissionMode.trim().ifBlank { "default" },
        contextLimit = contextLimit.coerceAtLeast(0),
        reserved = reserved?.takeIf { it > 0 },
        inputLimit = inputLimit?.takeIf { it > 0 },
        maxSteps = maxSteps.coerceIn(1, 200),
    )
}

private fun printUsage() {
    System.out.println(
        """
        openagentic-sdk-kotlin (v4) CLI

        Usage:
          oa-kotlin chat [options]
          oa-kotlin run  --prompt "hi" [options]

        Defaults:
          - Reads `.env` in project dir (if present)
          - Project dir defaults to current directory
          - Streaming defaults to on

        Supported .env keys:
          OPENAI_API_KEY, OPENAI_BASE_URL, MODEL

        Options:
          --model <model>            Optional. Defaults to MODEL (env/.env).
          --prompt <text>            Used by `run`. In `chat` it seeds the first turn (optional).
          --api-key <key>            Optional. Defaults to OPENAI_API_KEY (env/.env).
          --base-url <url>           Optional. Defaults to OPENAI_BASE_URL (env/.env) or https://api.openai.com/v1
          --api-key-header <header>  Optional. Default: authorization
          --openai-store <bool>      Optional. Default: true (needed for previous_response_id threading).
          --no-openai-store          Optional. Equivalent to --openai-store false
          --project-dir <dir>        Optional. Default: current directory
          --resume <session_id>      Optional. Continue an existing session.
          --no-stream                Optional. Disable streaming assistant text deltas (Responses SSE).
          --permission <mode>        Optional. bypass|deny|prompt|default (default: default)
          --context-limit <n>        Optional. Compaction overflow context window size (tokens). Default: 0 (off)
          --reserved <n>             Optional. Compaction reserved output tokens.
          --input-limit <n>          Optional. Compaction input_limit override.
          --max-steps <n>            Optional. Default: 20
        """.trimIndent(),
    )
}

private suspend fun runOnce(parsed: CliArgs) {
    val (options, apiKey) = buildOptions(parsed) ?: return
    val prompt = parsed.prompt.ifBlank {
        System.err.println("Missing --prompt for 'run'.")
        return
    }
    try {
        val result = OpenAgenticSdk.run(prompt = prompt, options = options)
        System.out.println()
        System.out.println(result.finalText)
        System.out.println()
        System.out.println("session_id: ${result.sessionId}")
    } catch (t: Throwable) {
        System.err.println("Request failed: ${t.message ?: t::class.simpleName}")
        System.err.println("Hint: check `.env` / environment variables: OPENAI_API_KEY, OPENAI_BASE_URL, MODEL")
        exitProcess(1)
    }
}

private suspend fun runChat(parsed: CliArgs) {
    val (baseOptions, _) = buildOptions(parsed) ?: return

    var sessionId = parsed.resume.ifBlank { "" }
    var first = true

    fun promptLine(): String {
        System.out.print("> ")
        val line = readLine()
        if (line == null) {
            System.out.println()
            System.out.println("(stdin closed; exiting)")
            exitProcess(0)
        }
        return line.trimEnd()
    }

    if (parsed.prompt.isNotBlank()) {
        val options = baseOptions.copy(resumeSessionId = sessionId.ifBlank { null })
        val (sid, text) = runTurn(prompt = parsed.prompt, options = options)
        sessionId = sid
        if (text.isNotBlank()) System.out.println("\n$text")
        first = false
    }

    while (true) {
        if (first) {
            System.out.println("Type /help for commands. Ctrl+C to exit.")
            first = false
        }
        val line = promptLine()
        if (line.isBlank()) continue
        if (line == "/exit" || line == "/quit") return
        if (line == "/help") {
            System.out.println(
                """
                Commands:
                  /help            Show this help
                  /exit, /quit     Exit
                  /session         Print current session_id
                """.trimIndent(),
            )
            continue
        }
        if (line == "/session") {
            System.out.println("session_id: " + (sessionId.ifBlank { "(new)" }))
            continue
        }

        val options = baseOptions.copy(resumeSessionId = sessionId.ifBlank { null })
        val (sid, text) = runTurn(prompt = line, options = options)
        sessionId = sid
        if (text.isNotBlank()) System.out.println("\n$text")
        System.out.println()
    }
}

private suspend fun runTurn(
    prompt: String,
    options: OpenAgenticOptions,
): Pair<String, String> {
    var sid = options.resumeSessionId.orEmpty()
    var finalText = ""
    OpenAgenticSdk.query(prompt = prompt, options = options).collect { ev ->
        when (ev) {
            is SystemInit -> {
                sid = ev.sessionId
                System.out.println("session_id: $sid")
            }

            is AssistantDelta -> {
                print(ev.textDelta)
            }

            is AssistantMessage -> {
                if (ev.isSummary) {
                    System.out.println("\n[summary]\n" + ev.text.trim())
                }
            }

            is ToolUse -> {
                System.out.println("\n[tool.use] ${ev.name} (${ev.toolUseId})")
            }

            is ToolResult -> {
                val status = if (ev.isError) "error" else "ok"
                System.out.println("[tool.result:$status] (${ev.toolUseId})")
            }

            is Result -> {
                finalText = ev.finalText
            }

            else -> Unit
        }
    }
    return sid to finalText
}

private suspend fun buildOptions(parsed: CliArgs): Pair<OpenAgenticOptions, String>? {
    val fs = FileSystem.SYSTEM
    val projectDirRaw =
        parsed.projectDir
            .ifBlank { System.getProperty("user.dir") ?: "." }
            .replace('\\', '/')
            .toPath()
    val projectDir =
        try {
            fs.canonicalize(projectDirRaw)
        } catch (_: Throwable) {
            projectDirRaw
        }
    val dotEnv = loadDotEnv(fileSystem = fs, path = projectDir.resolve(".env"))

    fun stripWrappingQuotes(s: String): String {
        val v = s.trim()
        if (v.length >= 2) {
            val first = v.first()
            val last = v.last()
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                return v.substring(1, v.length - 1).trim()
            }
        }
        return v
    }

    fun cfg(key: String): String {
        val fromDot = dotEnv[key]?.let(::stripWrappingQuotes).orEmpty()
        if (fromDot.isNotBlank()) return fromDot
        val fromEnv = System.getenv(key)?.let(::stripWrappingQuotes).orEmpty()
        if (fromEnv.isNotBlank()) return fromEnv
        return ""
    }

    fun firstNonBlank(vararg values: String): String = values.firstOrNull { it.isNotBlank() }.orEmpty()

    val model =
        firstNonBlank(
            parsed.model,
            cfg("OPENAI_MODEL"),
            cfg("MODEL"),
        )
    if (model.isBlank()) {
        System.err.println("Missing model. Provide --model or set MODEL (env/.env).")
        printUsage()
        return null
    }

    val apiKey =
        firstNonBlank(
            parsed.apiKey,
            cfg("OPENAI_API_KEY"),
        )
    if (apiKey.isBlank()) {
        System.err.println("Missing API key. Provide --api-key or set OPENAI_API_KEY (env/.env).")
        return null
    }

    val storeRoot = OpenAgenticPaths.defaultSessionRoot()
    val store = FileSessionStore(fileSystem = FileSystem.SYSTEM, rootDir = storeRoot)

    val tools =
        ToolRegistry(
            listOf(
                ReadTool(),
                ListTool(),
                WriteTool(),
                EditTool(),
                GlobTool(),
                GrepTool(),
                BashTool(),
                WebFetchTool(),
                WebSearchTool(),
                NotebookEditTool(),
                LspTool(),
                SkillTool(),
                SlashCommandTool(),
                TodoWriteTool(),
            ),
        )

    val answerer =
        UserAnswerer { q ->
            withContext(Dispatchers.IO) {
                askUser(q)
            }
        }

    val mode =
        when (parsed.permissionMode.trim().lowercase()) {
            "bypass" -> PermissionMode.BYPASS
            "deny" -> PermissionMode.DENY
            "prompt" -> PermissionMode.PROMPT
            "default" -> PermissionMode.DEFAULT
            else -> PermissionMode.DEFAULT
        }

    val provider =
        OpenAIResponsesHttpProvider(
            baseUrl =
                firstNonBlank(
                    parsed.baseUrl,
                    cfg("OPENAI_BASE_URL"),
                    "https://api.openai.com/v1",
                ),
            apiKeyHeader =
                firstNonBlank(
                    parsed.apiKeyHeader,
                    cfg("OPENAI_API_KEY_HEADER"),
                    "authorization",
                ),
            defaultStore = parsed.openAiStore,
        )

    val compaction =
        CompactionOptions(
            contextLimit = parsed.contextLimit,
            reserved = parsed.reserved,
            inputLimit = parsed.inputLimit,
        )

    val options =
        OpenAgenticOptions(
            provider = provider,
            model = model,
            apiKey = apiKey,
            fileSystem = FileSystem.SYSTEM,
            cwd = projectDir,
            projectDir = projectDir,
            tools = tools,
            permissionGate =
                when (mode) {
                    PermissionMode.BYPASS -> PermissionGate.bypass(userAnswerer = answerer)
                    PermissionMode.DENY -> PermissionGate.deny(userAnswerer = answerer)
                    PermissionMode.PROMPT -> PermissionGate.prompt(userAnswerer = answerer)
                    PermissionMode.DEFAULT -> PermissionGate.default(userAnswerer = answerer)
                },
            sessionStore = store,
            resumeSessionId = parsed.resume.ifBlank { null },
            includePartialMessages = parsed.stream,
            maxSteps = parsed.maxSteps,
            compaction = compaction,
        )

    return options to apiKey
}

private fun askUser(q: UserQuestion): JsonPrimitive {
    System.out.println()
    System.out.println(q.prompt)
    if (q.choices.isNotEmpty()) {
        System.out.println("Choices: " + q.choices.joinToString(", "))
    }
    System.out.print("> ")
    val line = readLine()?.trim().orEmpty()
    return JsonPrimitive(line)
}

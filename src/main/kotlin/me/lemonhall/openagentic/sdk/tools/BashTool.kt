package me.lemonhall.openagentic.sdk.tools

import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.math.min
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import okio.Path

class BashTool(
    private val defaultTimeoutMs: Long = 60_000,
    private val maxOutputBytes: Int = 1024 * 1024,
    private val maxOutputLines: Int = 2000,
) : Tool {
    override val name: String = "Bash"
    override val description: String = "Run a shell command."

    override suspend fun run(
        input: ToolInput,
        ctx: ToolContext,
    ): ToolOutput {
        val command = input["command"]?.asString()?.trim().orEmpty()
        require(command.isNotBlank()) { "Bash: 'command' must be a non-empty string" }

        val workdirRaw = input["workdir"]?.asString()?.trim()
        val runCwd: Path =
            if (workdirRaw.isNullOrBlank()) {
                ctx.cwd
            } else {
                resolveToolPath(workdirRaw, ctx)
            }

        val timeoutMs =
            input["timeout"]?.asLong()
                ?: input["timeout_ms"]?.asLong()
                ?: input["timeout_s"]?.asLong()?.let { it * 1000 }
                ?: defaultTimeoutMs

        val argv = shellArgv(command)
        val pb = ProcessBuilder(argv)
        pb.directory(java.io.File(runCwd.toString()))
        pb.redirectErrorStream(false)

        val proc = pb.start()

        val stdoutBytes = ByteArrayOutputStream()
        val stderrBytes = ByteArrayOutputStream()

        val stdoutThread =
            Thread {
                proc.inputStream.use { ins ->
                    copyWithCap(ins.readBytes(), stdoutBytes, maxBytes = maxOutputBytes + 1)
                }
            }
        val stderrThread =
            Thread {
                proc.errorStream.use { ins ->
                    copyWithCap(ins.readBytes(), stderrBytes, maxBytes = maxOutputBytes + 1)
                }
            }
        stdoutThread.isDaemon = true
        stderrThread.isDaemon = true
        stdoutThread.start()
        stderrThread.start()

        val finished = proc.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        if (!finished) {
            proc.destroyForcibly()
        }
        stdoutThread.join(1_000)
        stderrThread.join(1_000)

        val exitCode = if (finished) proc.exitValue() else 137

        val stdoutFull = stdoutBytes.toByteArray()
        val stderrFull = stderrBytes.toByteArray()
        val stdoutTruncated = stdoutFull.size > maxOutputBytes
        val stderrTruncated = stderrFull.size > maxOutputBytes

        val stdout = stdoutFull.copyOfRange(0, min(stdoutFull.size, maxOutputBytes))
        val stderr = stderrFull.copyOfRange(0, min(stderrFull.size, maxOutputBytes))

        val stdoutText = normalizePosixPathsToWindows(stdout.toString(Charsets.UTF_8))
        val stderrText = normalizePosixPathsToWindows(stderr.toString(Charsets.UTF_8))

        var output = normalizePosixPathsToWindows((stdout + stderr).toString(Charsets.UTF_8))
        val lines = output.split("\n")
        val outputLinesTruncated = lines.size > maxOutputLines
        if (outputLinesTruncated) {
            output = lines.take(maxOutputLines).joinToString("\n")
        }

        val fullOutputPath =
            if ((stdoutTruncated || stderrTruncated || outputLinesTruncated) && ctx.projectDir != null) {
                try {
                    val dir = ctx.projectDir.resolve(".openagentic-sdk").resolve("tool-output")
                    ctx.fileSystem.createDirectories(dir)
                    val p = dir.resolve("bash.${UUID.randomUUID().toString().replace("-", "")}.txt")
                    ctx.fileSystem.write(p) { write(stdoutFull + stderrFull) }
                    p.toString()
                } catch (_: Throwable) {
                    null
                }
            } else {
                null
            }

        val obj =
            buildJsonObject {
                put("command", JsonPrimitive(command))
                put("exit_code", JsonPrimitive(exitCode))
                put("stdout", JsonPrimitive(stdoutText))
                put("stderr", JsonPrimitive(stderrText))
                put("stdout_truncated", JsonPrimitive(stdoutTruncated))
                put("stderr_truncated", JsonPrimitive(stderrTruncated))
                put("output_lines_truncated", JsonPrimitive(outputLinesTruncated))
                put("full_output_file_path", fullOutputPath?.let { JsonPrimitive(it) } ?: JsonNull)

                // CAS-compatible aliases:
                put("output", JsonPrimitive(output))
                put("exitCode", JsonPrimitive(exitCode))
                put("killed", JsonPrimitive(!finished))
                put("shellId", JsonNull)
            }
        return ToolOutput.Json(obj)
    }

    private fun shellArgv(command: String): List<String> {
        // Contract: this tool is "Bash", not PowerShell/cmd. Prefer bash/sh.
        return listOf("bash", "-lc", command)
    }

    private fun normalizePosixPathsToWindows(text: String): String {
        val os = System.getProperty("os.name").lowercase()
        if (!os.contains("windows")) return text
        if (text.isEmpty()) return text

        val wsl = Regex("(^|[\\s'\"(])(/mnt/([a-zA-Z])/(?:[^ \\r\\n\\t'\"()]+))")
        val msys = Regex("(^|[\\s'\"(])(/([a-zA-Z])/(?:[^ \\r\\n\\t'\"()]+))")

        fun repl(prefix: String, path: String): String {
            val mnt = Regex("^/mnt/([a-zA-Z])/(.*)$").find(path)
            if (mnt != null) {
                val drive = mnt.groupValues[1].uppercase()
                val rest = mnt.groupValues[2].replace("/", "\\")
                return prefix + drive + ":\\" + rest
            }
            val ms = Regex("^/([a-zA-Z])/(.*)$").find(path)
            if (ms != null) {
                val drive = ms.groupValues[1].uppercase()
                val rest = ms.groupValues[2].replace("/", "\\")
                return prefix + drive + ":\\" + rest
            }
            return prefix + path
        }

        var out = text
        out =
            wsl.replace(out) { m ->
                val prefix = m.groupValues[1]
                val path = m.groupValues[2]
                repl(prefix, path)
            }
        out =
            msys.replace(out) { m ->
                val prefix = m.groupValues[1]
                val path = m.groupValues[2]
                repl(prefix, path)
            }
        return out
    }

    private fun copyWithCap(
        bytes: ByteArray,
        sink: ByteArrayOutputStream,
        maxBytes: Int,
    ) {
        if (bytes.isEmpty()) return
        val n = min(bytes.size, maxBytes)
        sink.write(bytes, 0, n)
    }
}

private fun JsonElement.asString(): String? = (this as? JsonPrimitive)?.contentOrNull

private fun JsonElement.asLong(): Long? {
    val p = this as? JsonPrimitive ?: return null
    return try {
        p.contentOrNull?.toLongOrNull()
    } catch (_: Throwable) {
        p.contentOrNull?.toLongOrNull()
    }
}

private val JsonPrimitive.contentOrNull: String?
    get() = try {
        this.content
    } catch (_: Throwable) {
        null
    }

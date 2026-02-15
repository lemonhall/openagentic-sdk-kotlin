package me.lemonhall.openagentic.sdk.tools

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import okio.BufferedSink
import okio.buffer
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

        val fullOutputFile =
            if (ctx.projectDir != null) {
                try {
                    val dir = ctx.projectDir.resolve(".openagentic-sdk").resolve("tool-output")
                    ctx.fileSystem.createDirectories(dir)
                    dir.resolve("bash.${UUID.randomUUID().toString().replace("-", "")}.txt")
                } catch (_: Throwable) {
                    null
                }
            } else {
                null
            }
        val fullOutputSink: BufferedSink? =
            if (fullOutputFile != null) {
                try {
                    ctx.fileSystem.sink(fullOutputFile).buffer()
                } catch (_: Throwable) {
                    null
                }
            } else {
                null
            }
        val fullOutputOk = AtomicBoolean(fullOutputSink != null)
        val fileLock = Any()
        val stdoutTotal = longArrayOf(0L)
        val stderrTotal = longArrayOf(0L)

        val stdoutThread =
            Thread {
                proc.inputStream.use { ins ->
                    stdoutTotal[0] =
                        copyWithCap(
                            ins,
                            stdoutBytes,
                            maxBytes = maxOutputBytes + 1,
                            fullSink = fullOutputSink,
                            fullOk = fullOutputOk,
                            lock = fileLock,
                        )
                }
            }
        val stderrThread =
            Thread {
                proc.errorStream.use { ins ->
                    stderrTotal[0] =
                        copyWithCap(
                            ins,
                            stderrBytes,
                            maxBytes = maxOutputBytes + 1,
                            fullSink = fullOutputSink,
                            fullOk = fullOutputOk,
                            lock = fileLock,
                        )
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
        try {
            fullOutputSink?.close()
        } catch (_: Throwable) {
        }

        val exitCode = if (finished) proc.exitValue() else 137

        val stdoutFull = stdoutBytes.toByteArray()
        val stderrFull = stderrBytes.toByteArray()
        val stdoutTruncated = stdoutTotal[0] > maxOutputBytes.toLong()
        val stderrTruncated = stderrTotal[0] > maxOutputBytes.toLong()

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

        val shouldKeepFullOutputFile = stdoutTruncated || stderrTruncated || outputLinesTruncated
        val fullOutputPath =
            if (shouldKeepFullOutputFile && fullOutputOk.get() && fullOutputFile != null && ctx.fileSystem.exists(fullOutputFile)) {
                fullOutputFile.toString()
            } else {
                try {
                    if (fullOutputFile != null) ctx.fileSystem.delete(fullOutputFile)
                } catch (_: Throwable) {
                }
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
        ins: InputStream,
        sink: ByteArrayOutputStream,
        maxBytes: Int,
        fullSink: BufferedSink?,
        fullOk: AtomicBoolean,
        lock: Any,
    ): Long {
        val buf = ByteArray(16 * 1024)
        var total = 0L
        while (true) {
            val n = try { ins.read(buf) } catch (_: Throwable) { -1 }
            if (n <= 0) break
            total += n.toLong()

            if (sink.size() < maxBytes) {
                val want = min(n, maxBytes - sink.size())
                if (want > 0) sink.write(buf, 0, want)
            }

            if (fullSink != null && fullOk.get()) {
                synchronized(lock) {
                    try {
                        fullSink.write(buf, 0, n)
                    } catch (_: Throwable) {
                        fullOk.set(false)
                    }
                }
            }
        }
        return total
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

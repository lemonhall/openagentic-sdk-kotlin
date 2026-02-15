package me.lemonhall.openagentic.sdk.tools

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okio.FileSystem
import okio.Path.Companion.toPath
import org.junit.jupiter.api.Assumptions.assumeTrue

class BashToolTest {
    private fun assumeBashAvailable() {
        try {
            val p = ProcessBuilder(listOf("bash", "-lc", "echo ok")).start()
            val ok = p.waitFor() == 0
            assumeTrue(ok, "bash is not available in PATH")
        } catch (_: Throwable) {
            assumeTrue(false, "bash is not available in PATH")
        }
    }

    @Test
    fun bashCapturesStdoutStderrAndExitCode() =
        runTest {
            assumeBashAvailable()
            val rootNio = Files.createTempDirectory("openagentic-test-")
            val root = rootNio.toString().replace('\\', '/').toPath()

            val tool = BashTool(defaultTimeoutMs = 5_000)
            val ctx = ToolContext(fileSystem = FileSystem.SYSTEM, cwd = root, projectDir = root)
            val out =
                tool.run(
                    buildJsonObject {
                        put("command", JsonPrimitive("printf 'out'; printf 'err' 1>&2; exit 7"))
                    },
                    ctx,
                ) as ToolOutput.Json

            val obj = out.value?.jsonObject
            assertNotNull(obj)
            assertEquals(7, obj["exit_code"]?.jsonPrimitive?.content?.toInt())
            assertEquals("out", obj["stdout"]?.jsonPrimitive?.content)
            assertEquals("err", obj["stderr"]?.jsonPrimitive?.content)
            assertEquals("false", obj["killed"]?.jsonPrimitive?.content)
        }

    @Test
    fun bashTimeoutSetsKilledAndExitCode137() =
        runTest {
            assumeBashAvailable()
            val rootNio = Files.createTempDirectory("openagentic-test-")
            val root = rootNio.toString().replace('\\', '/').toPath()

            val tool = BashTool(defaultTimeoutMs = 50)
            val ctx = ToolContext(fileSystem = FileSystem.SYSTEM, cwd = root, projectDir = root)
            val out =
                tool.run(
                    buildJsonObject {
                        put("command", JsonPrimitive("sleep 2; echo done"))
                        put("timeout_ms", JsonPrimitive(50))
                    },
                    ctx,
                ) as ToolOutput.Json

            val obj = out.value?.jsonObject
            assertNotNull(obj)
            assertEquals(137, obj["exit_code"]?.jsonPrimitive?.content?.toInt())
            assertEquals("true", obj["killed"]?.jsonPrimitive?.content)
        }

    @Test
    fun bashLargeOutputIsTruncatedAndWrittenToFile() =
        runTest {
            assumeBashAvailable()
            val rootNio = Files.createTempDirectory("openagentic-test-")
            val root = rootNio.toString().replace('\\', '/').toPath()

            val tool = BashTool(defaultTimeoutMs = 5_000, maxOutputBytes = 100, maxOutputLines = 2000)
            val ctx = ToolContext(fileSystem = FileSystem.SYSTEM, cwd = root, projectDir = root)
            val out =
                tool.run(
                    buildJsonObject {
                        put("command", JsonPrimitive("printf 'a%.0s' {1..5000}"))
                    },
                    ctx,
                ) as ToolOutput.Json

            val obj = out.value?.jsonObject
            assertNotNull(obj)
            assertEquals("true", obj["stdout_truncated"]?.jsonPrimitive?.content)
            val fullPath = obj["full_output_file_path"]?.jsonPrimitive?.content
            assertTrue(!fullPath.isNullOrBlank(), "expected full_output_file_path to be set")

            val p = fullPath!!.replace('\\', '/').toPath()
            assertTrue(FileSystem.SYSTEM.exists(p))
            val bytes = FileSystem.SYSTEM.read(p) { readByteArray() }
            assertTrue(bytes.size >= 5000, "expected full output to be written; size=${bytes.size}")
        }
}


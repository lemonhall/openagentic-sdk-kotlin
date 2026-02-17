package me.lemonhall.openagentic.sdk.runtime

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import me.lemonhall.openagentic.sdk.events.Result
import me.lemonhall.openagentic.sdk.events.RuntimeError
import me.lemonhall.openagentic.sdk.providers.ModelOutput
import me.lemonhall.openagentic.sdk.providers.ProviderHttpException
import me.lemonhall.openagentic.sdk.providers.ProviderTimeoutException
import me.lemonhall.openagentic.sdk.providers.ResponsesProvider
import me.lemonhall.openagentic.sdk.providers.ResponsesRequest
import me.lemonhall.openagentic.sdk.sessions.FileSessionStore
import okio.FileSystem
import okio.Path.Companion.toPath

private class FlakyProvider(
    private val failures: MutableList<Throwable>,
) : ResponsesProvider {
    override val name: String = "flaky"
    var calls: Int = 0

    override suspend fun complete(request: ResponsesRequest): ModelOutput {
        calls += 1
        if (failures.isNotEmpty()) throw failures.removeAt(0)
        return ModelOutput(
            assistantText = "OK",
            toolCalls = emptyList(),
            usage = buildJsonObject { put("total_tokens", JsonPrimitive(1)) },
            responseId = "resp_ok",
            providerMetadata = null,
        )
    }
}

class RuntimeProviderRetryTest {
    @Test
    fun providerRetryDefaultsAlignWithCodex() {
        assertEquals(6, ProviderRetryOptions().maxRetries)
    }

    @Test
    fun retriesTimeoutThenSucceeds() =
        runTest {
            val rootNio = Files.createTempDirectory("openagentic-test-")
            val root = rootNio.toString().replace('\\', '/').toPath()
            val store = FileSessionStore(fileSystem = FileSystem.SYSTEM, rootDir = root)
            val provider =
                FlakyProvider(
                    failures =
                        mutableListOf(
                            ProviderTimeoutException("timeout"),
                        ),
                )

            val options =
                OpenAgenticOptions(
                    provider = provider,
                    model = "fake",
                    apiKey = "x",
                    fileSystem = FileSystem.SYSTEM,
                    cwd = root,
                    sessionStore = store,
                    providerRetry = ProviderRetryOptions(maxRetries = 1, initialBackoffMs = 0, maxBackoffMs = 0),
                )

            val events = OpenAgenticSdk.query(prompt = "hello", options = options).toList()
            assertEquals(2, provider.calls)
            assertTrue(events.none { it is RuntimeError })
            val result = events.last { it is Result } as Result
            assertEquals("OK", result.finalText.trim())
            assertTrue((result.stopReason ?: "").lowercase() != "error")
        }

    @Test
    fun retriesHttp503ThenSucceeds() =
        runTest {
            val rootNio = Files.createTempDirectory("openagentic-test-")
            val root = rootNio.toString().replace('\\', '/').toPath()
            val store = FileSessionStore(fileSystem = FileSystem.SYSTEM, rootDir = root)
            val provider =
                FlakyProvider(
                    failures =
                        mutableListOf(
                            ProviderHttpException(status = 503, message = "HTTP 503"),
                        ),
                )

            val options =
                OpenAgenticOptions(
                    provider = provider,
                    model = "fake",
                    apiKey = "x",
                    fileSystem = FileSystem.SYSTEM,
                    cwd = root,
                    sessionStore = store,
                    providerRetry = ProviderRetryOptions(maxRetries = 1, initialBackoffMs = 0, maxBackoffMs = 0),
                )

            val events = OpenAgenticSdk.query(prompt = "hello", options = options).toList()
            assertEquals(2, provider.calls)
            assertTrue(events.none { it is RuntimeError })
            val result = events.last { it is Result } as Result
            assertEquals("OK", result.finalText.trim())
        }

    @Test
    fun stopsAfterMaxRetriesAndEmitsRuntimeError() =
        runTest {
            val rootNio = Files.createTempDirectory("openagentic-test-")
            val root = rootNio.toString().replace('\\', '/').toPath()
            val store = FileSessionStore(fileSystem = FileSystem.SYSTEM, rootDir = root)
            val provider =
                FlakyProvider(
                    failures =
                        mutableListOf(
                            ProviderTimeoutException("timeout-1"),
                            ProviderTimeoutException("timeout-2"),
                        ),
                )

            val options =
                OpenAgenticOptions(
                    provider = provider,
                    model = "fake",
                    apiKey = "x",
                    fileSystem = FileSystem.SYSTEM,
                    cwd = root,
                    sessionStore = store,
                    providerRetry = ProviderRetryOptions(maxRetries = 1, initialBackoffMs = 0, maxBackoffMs = 0),
                )

            val events = OpenAgenticSdk.query(prompt = "hello", options = options).toList()
            // With maxRetries=1, total attempts <= 2.
            assertEquals(2, provider.calls)
            assertTrue(events.any { it is RuntimeError })
            val result = events.last { it is Result } as Result
            assertEquals("error", result.stopReason)
        }
}

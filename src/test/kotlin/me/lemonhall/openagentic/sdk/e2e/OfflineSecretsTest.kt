package me.lemonhall.openagentic.sdk.e2e

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import me.lemonhall.openagentic.sdk.providers.ModelOutput
import me.lemonhall.openagentic.sdk.runtime.OpenAgenticOptions
import me.lemonhall.openagentic.sdk.runtime.OpenAgenticSdk
import me.lemonhall.openagentic.sdk.sessions.FileSessionStore
import me.lemonhall.openagentic.sdk.testing.ScriptedResponsesProvider
import me.lemonhall.openagentic.sdk.testing.TraceStrictAsserts
import me.lemonhall.openagentic.sdk.tools.ToolRegistry
import okio.FileSystem
import okio.Path.Companion.toPath

class OfflineSecretsTest {
    private fun tempRoot(): okio.Path {
        val rootNio = Files.createTempDirectory("openagentic-offline-e2e-secrets-")
        return rootNio.toString().replace('\\', '/').toPath()
    }

    @Test
    fun offline_trace_does_not_contain_secrets_blacklist() =
        runTest {
            // Given
            val root = tempRoot()
            val store = FileSessionStore(fileSystem = FileSystem.SYSTEM, rootDir = root)
            val secret = "sk-test-very-secret"
            val provider =
                ScriptedResponsesProvider { _, _ ->
                    ModelOutput(assistantText = "ok", toolCalls = emptyList(), responseId = "resp_1")
                }
            val options =
                OpenAgenticOptions(
                    provider = provider,
                    model = "fake",
                    apiKey = secret,
                    fileSystem = FileSystem.SYSTEM,
                    cwd = root,
                    tools = ToolRegistry(),
                    sessionStore = store,
                    includePartialMessages = false,
                    maxSteps = 1,
                )

            // When
            OpenAgenticSdk.query(prompt = "hi", options = options).toList()

            // Then
            val dirs = FileSystem.SYSTEM.list(root.resolve("sessions"))
            assertEquals(1, dirs.size)
            val sessionId = dirs.single().name
            val raw = FileSystem.SYSTEM.read(root.resolve("sessions").resolve(sessionId).resolve("events.jsonl")) { readUtf8() }
            TraceStrictAsserts.assertNoSecrets(rawJsonl = raw, sessionId = sessionId, blacklist = listOf(secret, "Bearer ", "authorization"))
        }
}


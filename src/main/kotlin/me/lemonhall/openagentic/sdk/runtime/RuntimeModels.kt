package me.lemonhall.openagentic.sdk.runtime

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonElement
import me.lemonhall.openagentic.sdk.compaction.CompactionOptions
import me.lemonhall.openagentic.sdk.hooks.HookEngine
import me.lemonhall.openagentic.sdk.permissions.PermissionGate
import me.lemonhall.openagentic.sdk.providers.Provider
import me.lemonhall.openagentic.sdk.providers.ProviderProtocol
import me.lemonhall.openagentic.sdk.sessions.FileSessionStore
import me.lemonhall.openagentic.sdk.tools.ToolRegistry
import okio.FileSystem
import okio.Path

data class OpenAgenticOptions(
    val provider: Provider,
    val providerProtocolOverride: ProviderProtocol? = null,
    val model: String,
    val apiKey: String? = null,
    val fileSystem: FileSystem = FileSystem.SYSTEM,
    val cwd: Path,
    val projectDir: Path? = null,
    val tools: ToolRegistry = ToolRegistry(),
    val allowedTools: Set<String>? = null,
    val permissionGate: PermissionGate = PermissionGate.bypass(),
    val hookEngine: HookEngine = HookEngine(),
    val taskRunner: TaskRunner? = null,
    val sessionStore: FileSessionStore,
    val resumeSessionId: String? = null,
    val resumeMaxEvents: Int = 1000,
    val resumeMaxBytes: Int = 2_000_000,
    val compaction: CompactionOptions = CompactionOptions(),
    val includePartialMessages: Boolean = false,
    val maxSteps: Int = 20,
)

data class TaskContext(
    val sessionId: String,
    val toolUseId: String,
)

fun interface TaskRunner {
    suspend fun run(
        agent: String,
        prompt: String,
        context: TaskContext,
    ): JsonElement
}

sealed interface ProviderStreamEvent {
    data class TextDelta(
        val delta: String,
    ) : ProviderStreamEvent

    data class Completed(
        val output: me.lemonhall.openagentic.sdk.providers.ModelOutput,
    ) : ProviderStreamEvent

    data class Failed(
        val message: String,
        val raw: JsonObject? = null,
    ) : ProviderStreamEvent
}

data class RunResult(
    val finalText: String,
    val sessionId: String,
    val events: List<me.lemonhall.openagentic.sdk.events.Event>,
)

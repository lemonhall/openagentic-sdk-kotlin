package me.lemonhall.openagentic.sdk.runtime

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonElement
import me.lemonhall.openagentic.sdk.compaction.CompactionOptions
import me.lemonhall.openagentic.sdk.hooks.HookEngine
import me.lemonhall.openagentic.sdk.permissions.PermissionGate
import me.lemonhall.openagentic.sdk.permissions.PermissionMode
import me.lemonhall.openagentic.sdk.providers.Provider
import me.lemonhall.openagentic.sdk.providers.ProviderProtocol
import me.lemonhall.openagentic.sdk.sessions.FileSessionStore
import me.lemonhall.openagentic.sdk.tools.TaskAgent
import me.lemonhall.openagentic.sdk.tools.ToolRegistry
import okio.FileSystem
import okio.Path

data class OpenAgenticOptions(
    val provider: Provider,
    val providerProtocolOverride: ProviderProtocol? = null,
    val model: String,
    val apiKey: String? = null,
    val providerRetry: ProviderRetryOptions = ProviderRetryOptions(),
    val fileSystem: FileSystem = FileSystem.SYSTEM,
    val cwd: Path,
    val projectDir: Path? = null,
    val tools: ToolRegistry = ToolRegistry(),
    val allowedTools: Set<String>? = null,
    val permissionGate: PermissionGate = PermissionGate.bypass(),
    val sessionPermissionMode: PermissionMode? = null,
    val permissionModeOverride: PermissionMode? = null,
    val hookEngine: HookEngine = HookEngine(),
    /**
     * A best-effort progress callback for Task(sub-agent) execution.
     *
     * Notes:
     * - This callback is NOT persisted in session events.
     * - It is intended for UI status lines / "anti-anxiety" progress indicators.
     * - Runners should keep messages short and human-friendly.
     */
    val taskProgressEmitter: ((String) -> Unit)? = null,
    val taskRunner: TaskRunner? = null,
    val taskAgents: List<TaskAgent> = emptyList(),
    val sessionStore: FileSessionStore,
    /**
     * Metadata persisted into `sessions/<session_id>/meta.json` when creating a new session.
     *
     * Notes:
     * - Only used when `resumeSessionId` is null/blank (new session).
     * - Stored as string key/value pairs for product-level session identity.
     */
    val createSessionMetadata: Map<String, String> = emptyMap(),
    val resumeSessionId: String? = null,
    val resumeMaxEvents: Int = 1000,
    val resumeMaxBytes: Int = 2_000_000,
    val compaction: CompactionOptions = CompactionOptions(),
    val toolOutputArtifacts: ToolOutputArtifactsOptions = ToolOutputArtifactsOptions(),
    val includePartialMessages: Boolean = false,
    val maxSteps: Int = 20,
)

data class ToolOutputArtifactsOptions(
    val enabled: Boolean = true,
    val dirName: String = "tool-output",
    val maxBytes: Int = 50 * 1024,
    val previewMaxChars: Int = 2500,
)

data class ProviderRetryOptions(
    // Align with Codex/OpenCode default experience: transient network failures should be retried.
    // `maxRetries=6` means: after the first failed attempt, retry up to 6 more times (<= 7 total attempts).
    val maxRetries: Int = 6,
    val initialBackoffMs: Long = 500,
    val maxBackoffMs: Long = 30_000,
    val useRetryAfterMs: Boolean = true,
)

data class TaskContext(
    val sessionId: String,
    val toolUseId: String,
    val emitProgress: ((String) -> Unit)? = null,
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

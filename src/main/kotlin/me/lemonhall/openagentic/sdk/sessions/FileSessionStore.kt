package me.lemonhall.openagentic.sdk.sessions

import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import me.lemonhall.openagentic.sdk.events.AssistantDelta
import me.lemonhall.openagentic.sdk.events.Event
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.buffer

class FileSessionStore(
    private val fileSystem: FileSystem,
    val rootDir: Path,
) {
    private val seq = ConcurrentHashMap<String, AtomicInteger>()

    companion object {
        fun system(rootDir: String): FileSessionStore = FileSessionStore(FileSystem.SYSTEM, rootDir.toPath())
    }

    fun createSession(metadata: Map<String, String> = emptyMap()): String {
        val sessionId = UUID.randomUUID().toString().replace("-", "")
        val sessionDir = sessionDir(sessionId)
        fileSystem.createDirectories(sessionDir)

        val metaObj =
            buildJsonObject {
                put("session_id", JsonPrimitive(sessionId))
                put("created_at", JsonPrimitive(Instant.now().epochSecond.toDouble()))
                put(
                    "metadata",
                    buildJsonObject {
                        for ((k, v) in metadata) {
                            put(k, JsonPrimitive(v))
                        }
                    },
                )
            }
        val meta = EventJson.json.encodeToString(JsonObject.serializer(), metaObj) + "\n"
        fileSystem.write(sessionDir.resolve("meta.json")) {
            writeUtf8(meta)
        }
        return sessionId
    }

    fun sessionDir(sessionId: String): Path {
        val sid = sessionId.trim()
        val isValid = sid.length == 32 && sid.all { it in "0123456789abcdefABCDEF" }
        require(isValid) { "invalid session_id" }
        return rootDir.resolve("sessions").resolve(sid)
    }

    fun appendEvent(
        sessionId: String,
        event: Event,
    ): Event {
        if (event is AssistantDelta) {
            return event
        }

        val sessionDir = sessionDir(sessionId)
        fileSystem.createDirectories(sessionDir)

        val nextSeq = seq.computeIfAbsent(sessionId) { AtomicInteger(inferNextSeq(sessionId)) }.incrementAndGet()
        val ts = Instant.now().toEpochMilli().toDouble() / 1000.0
        val stored = EventMeta.withMeta(event, seq = nextSeq, ts = ts)

        val path = sessionDir.resolve("events.jsonl")
        val line = EventJson.dumps(stored)
        fileSystem.appendingSink(path, mustExist = false).buffer().use { sink ->
            sink.writeUtf8(line)
            sink.writeUtf8("\n")
        }
        return stored
    }

    fun readEvents(sessionId: String): List<Event> {
        val sessionDir =
            try {
                sessionDir(sessionId)
            } catch (_: IllegalArgumentException) {
                return emptyList()
            }
        val path = sessionDir.resolve("events.jsonl")
        if (!fileSystem.exists(path)) return emptyList()
        val text =
            fileSystem.read(path) {
                readUtf8()
            }
        return text
            .lineSequence()
            .filter { it.isNotBlank() }
            .map { EventJson.loads(it) }
            .toList()
    }

    private fun inferNextSeq(sessionId: String): Int {
        val sessionDir =
            try {
                sessionDir(sessionId)
            } catch (_: IllegalArgumentException) {
                return 0
            }
        val path = sessionDir.resolve("events.jsonl")
        if (!fileSystem.exists(path)) return 0
        val text =
            fileSystem.read(path) {
                readUtf8()
            }
        val lines = text.lineSequence().toList().asReversed()
        for (line in lines.asSequence()) {
            if (line.isBlank()) continue
            val obj = EventJson.json.decodeFromString(kotlinx.serialization.json.JsonObject.serializer(), line)
            val seqValue = obj["seq"]?.jsonPrimitive?.intOrNull
            if (seqValue != null) return seqValue
            break
        }
        return 0
    }
}

private object EventMeta {
    fun withMeta(
        event: Event,
        seq: Int,
        ts: Double,
    ): Event {
        return when (event) {
            is me.lemonhall.openagentic.sdk.events.SystemInit -> event.copy(seq = seq, ts = ts)
            is me.lemonhall.openagentic.sdk.events.UserMessage -> event.copy(seq = seq, ts = ts)
            is me.lemonhall.openagentic.sdk.events.UserCompaction -> event.copy(seq = seq, ts = ts)
            is me.lemonhall.openagentic.sdk.events.UserQuestion -> event.copy(seq = seq, ts = ts)
            is me.lemonhall.openagentic.sdk.events.AssistantDelta -> event.copy(seq = seq, ts = ts)
            is me.lemonhall.openagentic.sdk.events.AssistantMessage -> event.copy(seq = seq, ts = ts)
            is me.lemonhall.openagentic.sdk.events.ToolUse -> event.copy(seq = seq, ts = ts)
            is me.lemonhall.openagentic.sdk.events.ToolResult -> event.copy(seq = seq, ts = ts)
            is me.lemonhall.openagentic.sdk.events.ToolOutputCompacted -> event.copy(seq = seq, ts = ts)
            is me.lemonhall.openagentic.sdk.events.HookEvent -> event.copy(seq = seq, ts = ts)
            is me.lemonhall.openagentic.sdk.events.Result -> event.copy(seq = seq, ts = ts)
            else -> event
        }
    }
}

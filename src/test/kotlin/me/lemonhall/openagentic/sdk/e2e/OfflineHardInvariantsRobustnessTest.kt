package me.lemonhall.openagentic.sdk.e2e

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.lemonhall.openagentic.sdk.events.UnknownEvent
import me.lemonhall.openagentic.sdk.sessions.EventJson
import me.lemonhall.openagentic.sdk.sessions.FileSessionStore
import okio.FileSystem
import okio.Path.Companion.toPath

class OfflineHardInvariantsRobustnessTest {
    private fun tempRoot(): okio.Path {
        val rootNio = Files.createTempDirectory("openagentic-offline-e2e-v5-")
        return rootNio.toString().replace('\\', '/').toPath()
    }

    private fun eventsJsonlPath(
        root: okio.Path,
        sessionId: String,
    ): okio.Path {
        return root.resolve("sessions").resolve(sessionId).resolve("events.jsonl")
    }

    @Test
    fun offline_events_unknown_type_is_parsed_as_unknown_event() {
        // Given
        val root = tempRoot()
        val store = FileSessionStore(fileSystem = FileSystem.SYSTEM, rootDir = root)
        val sessionId = store.createSession()

        val rawObj =
            buildJsonObject {
                put("type", JsonPrimitive("sdk.future_event"))
                put("seq", JsonPrimitive(7))
                put("ts", JsonPrimitive(123.456))
                put("new_field", JsonPrimitive("hello"))
            }
        val rawLine = EventJson.json.encodeToString(JsonObject.serializer(), rawObj) + "\n"
        FileSystem.SYSTEM.write(eventsJsonlPath(root, sessionId)) { writeUtf8(rawLine) }

        // When
        val events = store.readEvents(sessionId)

        // Then
        assertEquals(1, events.size)
        val unknown = assertIs<UnknownEvent>(events.single())
        assertEquals("sdk.future_event", unknown.type)
        assertEquals("hello", unknown.raw["new_field"]?.toString()?.trim('"'))
        assertEquals(7, unknown.seq)
        assertNotNull(unknown.ts)
    }

    @Test
    fun offline_events_unknown_type_roundtrip_preserves_raw_fields() {
        // Given
        val rawObj =
            buildJsonObject {
                put("type", JsonPrimitive("sdk.future_event"))
                put("seq", JsonPrimitive(7))
                put("ts", JsonPrimitive(123.456))
                put("nested", buildJsonObject { put("k", JsonPrimitive("v")) })
            }
        val rawLine = EventJson.json.encodeToString(JsonObject.serializer(), rawObj)

        // When
        val e1 = EventJson.loads(rawLine)
        val e2 = EventJson.loads(EventJson.dumps(e1))

        // Then
        val u1 = assertIs<UnknownEvent>(e1)
        val u2 = assertIs<UnknownEvent>(e2)
        assertEquals(u1.raw, u2.raw)
    }
}


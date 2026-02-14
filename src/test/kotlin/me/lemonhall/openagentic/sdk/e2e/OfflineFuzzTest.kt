package me.lemonhall.openagentic.sdk.e2e

import java.nio.file.Files
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.lemonhall.openagentic.sdk.events.UnknownEvent
import me.lemonhall.openagentic.sdk.events.UserMessage
import me.lemonhall.openagentic.sdk.sessions.EventJson
import me.lemonhall.openagentic.sdk.sessions.FileSessionStore
import okio.FileSystem
import okio.Path.Companion.toPath

class OfflineFuzzTest {
    private fun tempRoot(): okio.Path {
        val rootNio = Files.createTempDirectory("openagentic-offline-e2e-fuzz-")
        return rootNio.toString().replace('\\', '/').toPath()
    }

    @Test
    fun fuzz_events_jsonl_mixed_corruption_policy_is_stable_seeded() {
        val seed = 20260214
        val rnd = Random(seed)
        val cases = 200

        repeat(cases) { caseIdx ->
            val root = tempRoot()
            val store = FileSessionStore(fileSystem = FileSystem.SYSTEM, rootDir = root)
            val sessionId = store.createSession()
            val validCount = rnd.nextInt(1, 6)
            repeat(validCount) { i ->
                store.appendEvent(sessionId, UserMessage(text = "m-$caseIdx-$i"))
            }

            val path = root.resolve("sessions").resolve(sessionId).resolve("events.jsonl")
            fun readNonBlankLines(): MutableList<String> {
                val raw = FileSystem.SYSTEM.read(path) { readUtf8() }
                return raw.lineSequence().filter { it.isNotBlank() }.toMutableList()
            }

            val lines = readNonBlankLines()
            assertTrue(lines.isNotEmpty(), "seed=$seed case=$caseIdx expected non-empty events.jsonl")

            val mode = rnd.nextInt(0, 3)
            when (mode) {
                0 -> {
                    // Mid-file invalid line: must fail-fast with session_id + line_index.
                    if (lines.size < 2) {
                        store.appendEvent(sessionId, UserMessage(text = "pad-$caseIdx"))
                        lines.clear()
                        lines.addAll(readNonBlankLines())
                    }
                    val idx = rnd.nextInt(0, lines.size - 1)
                    lines.add(idx, "{")
                    FileSystem.SYSTEM.write(path) { writeUtf8(lines.joinToString("\n") + "\n") }

                    val ex = assertFailsWith<IllegalStateException> { store.readEvents(sessionId) }
                    val msg = ex.message.orEmpty()
                    assertTrue(msg.contains("session_id=$sessionId"), "seed=$seed case=$caseIdx msg=$msg")
                    assertTrue(msg.contains("line_index=$idx"), "seed=$seed case=$caseIdx msg=$msg")
                }
                1 -> {
                    // Tail truncated line: read should ignore tail; append should repair then append.
                    val last = lines.last()
                    val keep = if (last.length > 1) rnd.nextInt(1, last.length) else 0
                    lines[lines.size - 1] = last.take(keep)
                    FileSystem.SYSTEM.write(path) { writeUtf8(lines.joinToString("\n")) } // no trailing newline

                    store.readEvents(sessionId) // must not throw
                    store.appendEvent(sessionId, UserMessage(text = "after"))
                    val raw2 = FileSystem.SYSTEM.read(path) { readUtf8() }
                    assertTrue(raw2.endsWith("\n"), "seed=$seed case=$caseIdx expected newline after repair+append")
                    for (ln in raw2.lineSequence()) {
                        if (ln.isBlank()) continue
                        EventJson.loads(ln)
                    }
                }
                else -> {
                    // Mid-file unknown type: must not crash (parsed as UnknownEvent).
                    val idx = if (lines.size <= 1) 0 else rnd.nextInt(0, lines.size - 1)
                    val unknown =
                        buildJsonObject {
                            put("type", JsonPrimitive("sdk.future_event"))
                            put("seq", JsonPrimitive(999))
                            put("k", JsonPrimitive("v"))
                        }
                    val unknownLine = EventJson.json.encodeToString(JsonObject.serializer(), unknown)
                    lines.add(idx, unknownLine)
                    FileSystem.SYSTEM.write(path) { writeUtf8(lines.joinToString("\n") + "\n") }

                    val events = store.readEvents(sessionId)
                    assertTrue(events.any { it is UnknownEvent && it.type == "sdk.future_event" }, "seed=$seed case=$caseIdx expected UnknownEvent")
                }
            }
        }
    }
}

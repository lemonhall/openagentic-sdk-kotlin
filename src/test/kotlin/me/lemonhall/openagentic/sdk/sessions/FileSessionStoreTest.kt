package me.lemonhall.openagentic.sdk.sessions

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import me.lemonhall.openagentic.sdk.events.SystemInit
import okio.FileSystem
import okio.Path.Companion.toPath

class FileSessionStoreTest {
    @Test
    fun sessionStoreWritesEvents() {
        val rootNio = Files.createTempDirectory("openagentic-test-")
        val root = rootNio.toString().replace('\\', '/').toPath()
        val store = FileSessionStore(fileSystem = FileSystem.SYSTEM, rootDir = root)
        val sessionId = store.createSession()

        store.appendEvent(
            sessionId,
            SystemInit(sessionId = sessionId, cwd = "/x", sdkVersion = "0.0.0"),
        )

        val eventsPath = root.resolve("sessions").resolve(sessionId).resolve("events.jsonl")
        assertTrue(FileSystem.SYSTEM.exists(eventsPath))
        assertNotEquals("", FileSystem.SYSTEM.read(eventsPath) { readUtf8() }.trim())

        val events = store.readEvents(sessionId)
        assertEquals(1, events.size)
        assertEquals("system.init", events[0].type)
    }
}

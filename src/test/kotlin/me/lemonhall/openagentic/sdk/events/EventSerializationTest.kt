package me.lemonhall.openagentic.sdk.events

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import me.lemonhall.openagentic.sdk.sessions.EventJson
import me.lemonhall.openagentic.sdk.events.Result as SdkResult

class EventSerializationTest {
    @Test
    fun eventRoundtripSystemInit() {
        val e1 = SystemInit(sessionId = "s1", cwd = "/tmp", sdkVersion = "0.0.0")
        val raw = EventJson.dumps(e1)
        val e2 = EventJson.loads(raw)
        assertEquals(e1, e2)
    }

    @Test
    fun eventRoundtripResultWithResponseId() {
        val usage: JsonObject =
            buildJsonObject {
                put("total_tokens", JsonPrimitive(10))
            }
        val providerMetadata: JsonObject =
            buildJsonObject {
                put("service_tier", JsonPrimitive("auto"))
            }
        val e1 =
            SdkResult(
                sessionId = "s1",
                finalText = "ok",
                stopReason = "end",
                steps = 1,
                usage = usage,
                responseId = "resp_1",
                providerMetadata = providerMetadata,
            )
        val raw = EventJson.dumps(e1)
        val e2 = EventJson.loads(raw)
        assertEquals(e1, e2)
    }
}

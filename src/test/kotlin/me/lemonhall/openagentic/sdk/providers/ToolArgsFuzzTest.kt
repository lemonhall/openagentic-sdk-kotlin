package me.lemonhall.openagentic.sdk.providers

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlinx.serialization.json.Json

class ToolArgsFuzzTest {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    @Test
    fun fuzz_tool_args_random_json_does_not_crash_seeded() {
        val seed = 20260214
        val rnd = Random(seed)
        val cases = 500
        repeat(cases) { caseIdx ->
            val raw = randomWeirdString(rnd, maxLen = 256)
            val obj = parseArgs(raw, json = json)
            assertNotNull(obj, "seed=$seed case=$caseIdx expected JsonObject")
        }
    }

    private fun randomWeirdString(
        rnd: Random,
        maxLen: Int,
    ): String {
        val len = rnd.nextInt(0, maxLen + 1)
        val pool =
            charArrayOf(
                '{',
                '}',
                '[',
                ']',
                ':',
                ',',
                '"',
                '\\',
                ' ',
                '\n',
                '\r',
                '\t',
                'a',
                'b',
                'c',
                '0',
                '1',
                '9',
                '你',
                '好',
                '✓',
            )
        val sb = StringBuilder(len)
        repeat(len) {
            sb.append(pool[rnd.nextInt(pool.size)])
        }
        return sb.toString()
    }
}


package me.lemonhall.openagentic.sdk.tools

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ToolRegistryTest {
    @Test
    fun registerRejectsEmptyName() {
        val registry = ToolRegistry()
        val tool =
            object : Tool {
                override val name: String = ""
                override val description: String = "x"
                override suspend fun run(input: ToolInput, ctx: ToolContext) = ToolOutput.Json(null)
            }
        assertFailsWith<IllegalArgumentException> { registry.register(tool) }
    }

    @Test
    fun namesAreSorted() {
        val registry =
            ToolRegistry(
                tools =
                    listOf(
                        object : Tool {
                            override val name = "b"
                            override val description = "b"
                            override suspend fun run(input: ToolInput, ctx: ToolContext) = ToolOutput.Json(null)
                        },
                        object : Tool {
                            override val name = "a"
                            override val description = "a"
                            override suspend fun run(input: ToolInput, ctx: ToolContext) = ToolOutput.Json(null)
                        },
                    ),
            )
        assertEquals(listOf("a", "b"), registry.names())
    }
}


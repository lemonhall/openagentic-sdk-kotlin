package me.lemonhall.openagentic.sdk.tools

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import okio.FileSystem
import okio.Path.Companion.toPath

class NotebookEditToolTest {
    @Test
    fun replaceCellSource() =
        runTest {
            val rootNio = Files.createTempDirectory("openagentic-test-")
            val root = rootNio.toString().replace('\\', '/').toPath()
            val fs = FileSystem.SYSTEM
            val nbPath = root.resolve("a.ipynb")
            fs.write(nbPath) {
                writeUtf8(
                    """
                    {
                      "cells": [
                        { "cell_type": "code", "metadata": {}, "source": ["print(1)\\n"], "id": "c1" }
                      ],
                      "metadata": {},
                      "nbformat": 4,
                      "nbformat_minor": 5
                    }
                    """.trimIndent(),
                )
            }

            val tool = NotebookEditTool()
            tool.run(
                input =
                    buildJsonObject {
                        put("notebook_path", JsonPrimitive(nbPath.toString()))
                        put("cell_id", JsonPrimitive("c1"))
                        put("new_source", JsonPrimitive("print(2)\n"))
                        put("edit_mode", JsonPrimitive("replace"))
                    },
                ctx = ToolContext(fileSystem = fs, cwd = root, projectDir = root),
            )

            val text = fs.read(nbPath) { readUtf8() }
            assertTrue(text.contains("print(2)"))
        }
}


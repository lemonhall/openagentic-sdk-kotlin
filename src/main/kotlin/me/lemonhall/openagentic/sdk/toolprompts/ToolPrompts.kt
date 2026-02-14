package me.lemonhall.openagentic.sdk.toolprompts

import java.time.LocalDate

object ToolPrompts {
    fun render(
        templateName: String,
        variables: Map<String, Any?> = emptyMap(),
    ): String {
        val vars = linkedMapOf<String, Any?>()
        vars["date"] = LocalDate.now().toString()
        vars.putAll(variables)

        val raw = readTemplate("$templateName.txt")
        return substitute(raw, vars).trim()
    }

    private fun readTemplate(filename: String): String {
        val path = "/me/lemonhall/openagentic/sdk/toolprompts/$filename"
        val stream = ToolPrompts::class.java.getResourceAsStream(path)
            ?: throw IllegalArgumentException("Missing tool prompt template resource: $path")
        return stream.use { it.readBytes().toString(Charsets.UTF_8) }
    }

    private fun substitute(
        text: String,
        variables: Map<String, Any?>,
    ): String {
        var out = text
        for ((k, v) in variables) {
            val value = v?.toString() ?: ""
            out = out.replace("\${$k}", value)
            out = out.replace("{{${k}}}", value)
        }
        return out
    }
}


package me.lemonhall.openagentic.sdk.tools

import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

typealias WebSearchTransport = (url: String, headers: Map<String, String>, payload: JsonObject) -> JsonObject

class WebSearchTool(
    private val transport: WebSearchTransport = ::defaultTransport,
    private val endpoint: String = "https://api.tavily.com/search",
    private val apiKeyProvider: () -> String? = { System.getenv("TAVILY_API_KEY") },
) : Tool {
    override val name: String = "WebSearch"
    override val description: String =
        "Search the web (Tavily backend; falls back to DuckDuckGo HTML when TAVILY_API_KEY is missing)."

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    override suspend fun run(
        input: ToolInput,
        ctx: ToolContext,
    ): ToolOutput {
        val query = input["query"]?.asString()?.trim().orEmpty()
        require(query.isNotBlank()) { "WebSearch: 'query' must be a non-empty string" }

        val maxResults = input["max_results"]?.asInt() ?: 5
        require(maxResults > 0) { "WebSearch: 'max_results' must be a positive integer" }

        val allowedSet = input["allowed_domains"].asStringList().map { it.lowercase() }.toSet()
        val blockedSet = input["blocked_domains"].asStringList().map { it.lowercase() }.toSet()

        val tavilyKey = apiKeyProvider.invoke()?.trim().orEmpty()
        if (tavilyKey.isBlank()) {
            val results = duckDuckGoSearch(query = query, maxResults = maxResults, allowedSet = allowedSet, blockedSet = blockedSet)
            return ToolOutput.Json(
                buildJsonObject {
                    put("query", JsonPrimitive(query))
                    put("results", JsonArray(results))
                    put("total_results", JsonPrimitive(results.size))
                },
            )
        }

        val payload =
            buildJsonObject {
                put("api_key", JsonPrimitive(tavilyKey))
                put("query", JsonPrimitive(query))
                put("max_results", JsonPrimitive(maxResults))
            }

        val obj: JsonObject =
            try {
                transport(endpoint, mapOf("content-type" to "application/json"), payload)
            } catch (t: Throwable) {
                val results = duckDuckGoSearch(query = query, maxResults = maxResults, allowedSet = allowedSet, blockedSet = blockedSet)
                return ToolOutput.Json(
                    buildJsonObject {
                        put("query", JsonPrimitive(query))
                        put("results", JsonArray(results))
                        put("total_results", JsonPrimitive(results.size))
                        put(
                            "meta",
                            buildJsonObject {
                                put("primary_source", JsonPrimitive("tavily"))
                                put("fallback_source", JsonPrimitive("duckduckgo"))
                                put("tavily_error", JsonPrimitive(t.message ?: t::class.simpleName ?: "tavily error"))
                            },
                        )
                    },
                )
            }

        val resultsIn = obj["results"]

        val results =
            buildJsonArray {
                if (resultsIn is JsonArray) {
                    for (el in resultsIn) {
                        val r = el as? JsonObject ?: continue
                        val url = r["url"]?.asString()?.takeIf { it.isNotBlank() } ?: continue
                        if (!domainAllowed(url = url, allowedSet = allowedSet, blockedSet = blockedSet)) continue
                        add(
                            buildJsonObject {
                                put("title", r["title"] ?: JsonNull)
                                put("url", JsonPrimitive(url))
                                put("content", (r["content"] ?: r["snippet"]) ?: JsonNull)
                                put("source", JsonPrimitive("tavily"))
                            },
                        )
                    }
                }
            }

        val out =
            buildJsonObject {
                put("query", JsonPrimitive(query))
                put("results", results)
                put("total_results", JsonPrimitive(results.size))
            }
        return ToolOutput.Json(out)
    }

    private fun duckDuckGoSearch(
        query: String,
        maxResults: Int,
        allowedSet: Set<String>,
        blockedSet: Set<String>,
    ): List<JsonObject> {
        val url = "https://html.duckduckgo.com/html/?q=" + encodeQuery(query)
        val raw =
            try {
                httpGet(
                    url = url,
                    headers =
                        mapOf(
                            "user-agent" to "openagentic-sdk-kotlin/0.1 (+https://github.com/openai/openagentic-sdk)",
                            "accept" to "text/html,application/xhtml+xml",
                        ),
                )
            } catch (_: Throwable) {
                ""
            }
        if (raw.isBlank()) return emptyList()

        val pat = Regex("<a[^>]*class=\"result__a\"[^>]*href=\"([^\"]+)\"[^>]*>(.*?)</a>", RegexOption.IGNORE_CASE)
        val tagStrip = Regex("<.*?>")
        val results = mutableListOf<JsonObject>()
        for (m in pat.findAll(raw)) {
            val href = htmlUnescape(m.groupValues.getOrNull(1).orEmpty())
            val href2 = decodeDuckDuckGoRedirect(href)
            if (href2.isBlank()) continue
            if (!domainAllowed(url = href2, allowedSet = allowedSet, blockedSet = blockedSet)) continue
            val titleHtml = m.groupValues.getOrNull(2).orEmpty()
            val title = htmlUnescape(tagStrip.replace(titleHtml, "")).trim()
            results.add(
                buildJsonObject {
                    put("title", JsonPrimitive(title))
                    put("url", JsonPrimitive(href2))
                    put("content", JsonNull)
                    put("source", JsonPrimitive("duckduckgo"))
                },
            )
            if (results.size >= maxResults) break
        }
        return results
    }

    private fun decodeDuckDuckGoRedirect(href: String): String {
        if (href.isBlank()) return href
        return try {
            val uri = URI(href)
            val qs = uri.rawQuery.orEmpty()
            val params = qs.split("&").mapNotNull {
                val idx = it.indexOf("=")
                if (idx <= 0) null else it.substring(0, idx) to it.substring(idx + 1)
            }.toMap()
            val uddg = params["uddg"] ?: return href
            URLDecoder.decode(uddg, StandardCharsets.UTF_8)
        } catch (_: Throwable) {
            href
        }
    }

    private fun domainAllowed(
        url: String,
        allowedSet: Set<String>,
        blockedSet: Set<String>,
    ): Boolean {
        val host =
            try {
                (URI(url).host ?: "").lowercase()
            } catch (_: Throwable) {
                ""
            }
        if (host.isBlank()) return allowedSet.isEmpty()
        if (blockedSet.isNotEmpty() && blockedSet.any { host == it || host.endsWith(".$it") }) return false
        if (allowedSet.isNotEmpty() && allowedSet.none { host == it || host.endsWith(".$it") }) return false
        return true
    }

    private fun encodeQuery(q: String): String {
        return java.net.URLEncoder.encode(q, "UTF-8")
    }

    private fun htmlUnescape(s: String): String {
        // Minimal unescape for &amp; &lt; &gt; &quot; &#39;
        return s
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
    }

    private fun httpGet(
        url: String,
        headers: Map<String, String>,
    ): String {
        val conn = URI(url).toURL().openConnection() as java.net.HttpURLConnection
        conn.requestMethod = "GET"
        conn.instanceFollowRedirects = true
        for ((k, v) in headers) conn.setRequestProperty(k, v)
        val status = conn.responseCode
        val stream = if (status >= 400) conn.errorStream else conn.inputStream
        val raw = stream?.readBytes()?.toString(Charsets.UTF_8).orEmpty()
        if (status >= 400) throw RuntimeException("HTTP $status from $url: $raw".trim())
        return raw
    }

    private companion object {
        fun defaultTransport(
            url: String,
            headers: Map<String, String>,
            payload: JsonObject,
        ): JsonObject {
            val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
            val body = json.encodeToString(JsonObject.serializer(), payload)
            val conn = URI(url).toURL().openConnection() as java.net.HttpURLConnection
            conn.requestMethod = "POST"
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 60_000
            conn.readTimeout = 60_000
            conn.doOutput = true
            for ((k, v) in headers) conn.setRequestProperty(k, v)
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val status = conn.responseCode
            val stream = if (status >= 400) conn.errorStream else conn.inputStream
            val raw = stream?.readBytes()?.toString(Charsets.UTF_8).orEmpty()
            if (status >= 400) throw RuntimeException("HTTP $status from $url: $raw".trim())
            val el = json.parseToJsonElement(raw)
            return el as? JsonObject ?: buildJsonObject { put("_raw", JsonPrimitive(raw)) }
        }
    }
}

private fun JsonElement?.asString(): String? {
    return (this as? JsonPrimitive)?.contentOrNull
}

private fun JsonElement?.asInt(): Int? {
    val p = this as? JsonPrimitive ?: return null
    return try {
        p.contentOrNull?.toIntOrNull()
    } catch (_: Throwable) {
        p.contentOrNull?.toIntOrNull()
    }
}

private fun JsonElement?.asStringList(): List<String> {
    val arr = this as? JsonArray ?: return emptyList()
    return arr.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { s -> s.isNotEmpty() } }
}

private val JsonPrimitive.contentOrNull: String?
    get() = try {
        this.content
    } catch (_: Throwable) {
        null
    }

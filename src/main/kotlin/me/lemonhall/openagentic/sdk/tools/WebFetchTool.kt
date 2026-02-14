package me.lemonhall.openagentic.sdk.tools

import java.net.InetAddress
import java.net.URI
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import me.lemonhall.openagentic.sdk.json.asStringOrNull

fun interface WebFetchTransport {
    fun get(
        url: String,
        headers: Map<String, String>,
    ): WebFetchResponse
}

data class WebFetchResponse(
    val status: Int,
    val headers: Map<String, String>,
    val body: ByteArray,
)

class WebFetchTool(
    private val maxBytes: Int = 1024 * 1024,
    private val maxRedirects: Int = 5,
    private val allowPrivateNetworks: Boolean = false,
    private val transport: WebFetchTransport = DefaultWebFetchTransport(),
) : Tool {
    override val name: String = "WebFetch"
    override val description: String = "Fetch a URL over HTTP(S)."

    override suspend fun run(
        input: ToolInput,
        ctx: ToolContext,
    ): ToolOutput {
        val url = input["url"]?.asStringOrNull()?.trim().orEmpty()
        require(url.isNotEmpty()) { "WebFetch: 'url' must be a non-empty string" }

        val requestedUrl = url
        validateUrl(requestedUrl)

        val headers = input["headers"]
        val headersObj: Map<String, String> =
            when (headers) {
                null -> emptyMap()
                is JsonObject -> headers.entries.associate { (k, v) -> k.lowercase() to (v.asStringOrNull() ?: v.toString()) }
                else -> throw IllegalArgumentException("WebFetch: 'headers' must be an object")
            }

        val result = fetchFollowingRedirects(url = requestedUrl, headers = headersObj)

        val body2 = if (result.body.size > maxBytes) result.body.copyOf(maxBytes) else result.body
        val contentType = result.headers["content-type"]
        val text = body2.toString(Charsets.UTF_8)

        val out =
            buildJsonObject {
                put("requested_url", JsonPrimitive(requestedUrl))
                put("url", JsonPrimitive(result.finalUrl))
                put("final_url", JsonPrimitive(result.finalUrl))
                put("redirect_chain", JsonArray(result.redirectChain.map { JsonPrimitive(it) }))
                put("status", JsonPrimitive(result.status))
                if (contentType != null) put("content_type", JsonPrimitive(contentType))
                put("text", JsonPrimitive(text))
            }
        return ToolOutput.Json(out)
    }

    private fun validateUrl(url: String) {
        val u = URI(url)
        val scheme = (u.scheme ?: "").lowercase()
        require(scheme == "http" || scheme == "https") { "WebFetch: only http/https URLs are allowed" }
        val host = u.host?.trim().orEmpty()
        require(host.isNotEmpty()) { "WebFetch: URL must include a hostname" }
        if (!allowPrivateNetworks) require(!isBlockedHost(host)) { "WebFetch: blocked hostname" }
    }

    private fun isBlockedHost(host: String): Boolean {
        val h = host.lowercase()
        if (h == "localhost" || h.endsWith(".localhost")) return true

        fun isBlockedIp(ip: InetAddress): Boolean {
            return ip.isAnyLocalAddress || ip.isLoopbackAddress || ip.isLinkLocalAddress || ip.isSiteLocalAddress
        }

        return try {
            InetAddress.getAllByName(host).any { isBlockedIp(it) }
        } catch (_: Throwable) {
            false
        }
    }

    private fun fetchFollowingRedirects(
        url: String,
        headers: Map<String, String>,
    ): FetchResult {
        val chain = mutableListOf<String>()
        var currentUrl = url
        chain.add(currentUrl)

        val redirectsAllowed = maxOf(0, maxRedirects)
        for (i in 0..redirectsAllowed) {
            val resp = transport.get(currentUrl, headers)
            val hdrs = resp.headers.mapKeys { it.key.lowercase() }
            val status = resp.status
            if (status in setOf(301, 302, 303, 307, 308)) {
                val location = hdrs["location"]?.trim().orEmpty()
                if (location.isEmpty()) return FetchResult(currentUrl, status, hdrs, resp.body, chain)
                val nextUrl = URI(currentUrl).resolve(location).toString()
                validateUrl(nextUrl)
                currentUrl = nextUrl
                chain.add(currentUrl)
                continue
            }
            return FetchResult(currentUrl, status, hdrs, resp.body, chain)
        }
        throw IllegalArgumentException("WebFetch: too many redirects (>$maxRedirects)")
    }
}

private data class FetchResult(
    val finalUrl: String,
    val status: Int,
    val headers: Map<String, String>,
    val body: ByteArray,
    val redirectChain: List<String>,
)

private class DefaultWebFetchTransport : WebFetchTransport {
    override fun get(
        url: String,
        headers: Map<String, String>,
    ): WebFetchResponse {
        val conn = (URI(url).toURL().openConnection() as java.net.HttpURLConnection)
        conn.instanceFollowRedirects = false
        conn.requestMethod = "GET"
        conn.connectTimeout = 60_000
        conn.readTimeout = 60_000
        for ((k, v) in headers) {
            conn.setRequestProperty(k, v)
        }

        val status = conn.responseCode
        val hdrs =
            conn.headerFields
                .filterKeys { it != null }
                .mapKeys { (k, _) -> k!!.lowercase() }
                .mapValues { (_, v) -> v?.joinToString(", ").orEmpty() }
        val stream =
            try {
                if (status >= 400) conn.errorStream else conn.inputStream
            } catch (_: Throwable) {
                null
            }
        val body = stream?.readBytes() ?: ByteArray(0)
        return WebFetchResponse(status = status, headers = hdrs, body = body)
    }
}

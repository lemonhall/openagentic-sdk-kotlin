package me.lemonhall.openagentic.sdk.tools

import java.net.InetAddress
import java.net.URI
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import me.lemonhall.openagentic.sdk.json.asIntOrNull
import me.lemonhall.openagentic.sdk.json.asStringOrNull
import com.vladsch.flexmark.html2md.converter.FlexmarkHtmlConverter
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.safety.Safelist

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
    override val description: String = "Fetch a URL over HTTP(S) and return a size-bounded, sanitized representation."

    override suspend fun run(
        input: ToolInput,
        ctx: ToolContext,
    ): ToolOutput {
        val url = input["url"]?.asStringOrNull()?.trim().orEmpty()
        require(url.isNotEmpty()) { "WebFetch: 'url' must be a non-empty string" }

        val mode = input["mode"]?.asStringOrNull()?.trim()?.lowercase().orEmpty().ifBlank { "markdown" }
        val requestedMaxChars = input["max_chars"]?.asIntOrNull()
        val maxChars = (requestedMaxChars ?: 24_000).coerceIn(1_000, 80_000)

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
        val rawText = body2.toString(Charsets.UTF_8)
        val outText =
            when (mode) {
                "raw" -> rawText
                "text" -> sanitizeToText(rawText, baseUrl = result.finalUrl)
                "clean_html" -> sanitizeToCleanHtml(rawText, baseUrl = result.finalUrl)
                "markdown" -> sanitizeToMarkdown(rawText, baseUrl = result.finalUrl)
                else -> sanitizeToCleanHtml(rawText, baseUrl = result.finalUrl)
            }

        val truncated = outText.length > maxChars
        val limited = if (truncated) outText.take(maxChars) else outText

        val title =
            try {
                parseHtmlBestEffort(rawText, result.finalUrl).title().trim()
            } catch (_: Throwable) {
                ""
            }

        val out =
            buildJsonObject {
                put("requested_url", JsonPrimitive(requestedUrl))
                put("url", JsonPrimitive(result.finalUrl))
                put("final_url", JsonPrimitive(result.finalUrl))
                put("redirect_chain", JsonArray(result.redirectChain.map { JsonPrimitive(it) }))
                put("status", JsonPrimitive(result.status))
                if (contentType != null) put("content_type", JsonPrimitive(contentType))
                put("title", JsonPrimitive(title))
                put("mode", JsonPrimitive(mode))
                put("max_chars", JsonPrimitive(maxChars))
                put("truncated", JsonPrimitive(truncated))
                put("text", JsonPrimitive(limited))
            }
        return ToolOutput.Json(out)
    }

    private fun sanitizeToText(
        raw: String,
        baseUrl: String,
    ): String {
        val doc = parseHtmlBestEffort(raw, baseUrl)
        return doc.text().trim()
    }

    private fun sanitizeToCleanHtml(
        raw: String,
        baseUrl: String,
    ): String {
        val doc = parseHtmlBestEffort(raw, baseUrl)
        stripBoilerplate(doc)
        absolutizeLinks(doc)
        val content = selectMainContent(doc)
        pruneNonContentBlocks(content)

        val safelist =
            Safelist.none()
                .addTags(
                    "a",
                    "p",
                    "br",
                    "ul",
                    "ol",
                    "li",
                    "table",
                    "thead",
                    "tbody",
                    "tr",
                    "td",
                    "th",
                    "h1",
                    "h2",
                    "h3",
                    "h4",
                    "h5",
                    "h6",
                    "pre",
                    "code",
                    "blockquote",
                    "em",
                    "strong",
                ).addAttributes("a", "href", "title")

        val cleanedHtml = Jsoup.clean(content.outerHtml(), baseUrl, safelist)
        val cleanedDoc = Jsoup.parse(cleanedHtml, baseUrl)
        pruneEmptyNodes(cleanedDoc.body())
        return cleanedDoc.body().html().trim()
    }

    private fun sanitizeToMarkdown(
        raw: String,
        baseUrl: String,
    ): String {
        val html = sanitizeToCleanHtml(raw, baseUrl)
        val md =
            try {
                FlexmarkHtmlConverter.builder().build().convert(html)
            } catch (_: Throwable) {
                // Fallback: plain text (still bounded by max_chars upstream).
                sanitizeToText(raw, baseUrl)
            }
        return normalizeMarkdown(md)
    }

    private fun parseHtmlBestEffort(
        raw: String,
        baseUrl: String,
    ): Document {
        // Use html parser even when content-type is unknown. For non-html, this will still produce a text-only DOM.
        return Jsoup.parse(raw, baseUrl)
    }

    private fun stripBoilerplate(doc: Document) {
        // Remove common non-content containers early to improve main content selection.
        doc.select("script,style,noscript,svg,canvas,iframe,video,audio,picture,source").remove()
        doc.select("header,footer,nav,aside,form,button").remove()
        doc.select("[role=banner],[role=navigation],[role=contentinfo],[role=complementary]").remove()
        // Remove elements that are very likely tracking/ads.
        doc.select("[class*=cookie],[id*=cookie],[class*=consent],[id*=consent]").remove()
        doc.select("[class*=advert],[id*=advert],[class*=ad-],[id*=ad-],[class*=ads],[id*=ads]").remove()
    }

    private fun selectMainContent(doc: Document): Element {
        doc.selectFirst("article")?.let { return it }
        doc.selectFirst("main")?.let { return it }

        val body = doc.body()
        // Jsoup always has a body for HTML documents; keep fallback for malformed inputs.
        if (body == null) return doc

        var best: Element = body
        var bestScore = -1.0

        val candidates = body.select("div,section,article,main")
        val limited = if (candidates.size > 120) candidates.subList(0, 120) else candidates
        for (el in limited) {
            val text = el.text()
            val textLen = text.length
            if (textLen < 400) continue

            // Skip "big text blobs" that don't look like readable content containers.
            val pCount = el.select("p").size
            val hCount = el.select("h1,h2,h3").size
            val liCount = el.select("li").size
            val tableCount = el.select("table").size
            val codeCount = el.select("pre,code").size
            val contentSignals = pCount + hCount + liCount + tableCount + codeCount
            if (contentSignals == 0) continue

            val linkTextLen =
                el.select("a")
                    .joinToString("") { it.text() }
                    .length
            val linkDensity = if (textLen <= 0) 1.0 else (linkTextLen.toDouble() / textLen.toDouble()).coerceIn(0.0, 1.0)
            val tagPenalty = el.select("li,nav,aside,footer,header").size * 40

            val score =
                (textLen.toDouble() * (1.0 - linkDensity)) +
                    (pCount * 200.0) +
                    (hCount * 240.0) +
                    (tableCount * 300.0) +
                    (codeCount * 120.0) -
                    tagPenalty
            if (score > bestScore) {
                bestScore = score
                best = el
            }
        }
        return best
    }

    private fun pruneEmptyNodes(root: Element?) {
        if (root == null) return

        // Remove tracking pixels if any survived cleaning due to escaping or malformed HTML.
        root.select("img").remove()

        // Iteratively remove empty block nodes.
        val removableTags = setOf("div", "span", "p", "section", "article", "main", "blockquote", "ul", "ol", "li", "table", "thead", "tbody", "tr", "td", "th")
        var changed = true
        var passes = 0
        while (changed && passes < 10) {
            passes++
            changed = false
            val nodes = root.getAllElements().asReversed()
            for (el in nodes) {
                if (el == root) continue
                if (!removableTags.contains(el.tagName())) continue
                if (el.select("a,pre,code,table,td,th,li,br").isNotEmpty()) continue
                if (el.text().trim().isNotEmpty()) continue
                el.remove()
                changed = true
            }
        }
    }

    private fun pruneNonContentBlocks(root: Element) {
        // Remove common non-content widgets inside the main container.
        root.select(
            "[class*=breadcrumb],[id*=breadcrumb]," +
                "[class*=share],[id*=share]," +
                "[class*=social],[id*=social]," +
                "[class*=comment],[id*=comment]," +
                "[class*=author],[id*=author]," +
                "[class*=byline],[id*=byline]," +
                "[class*=newsletter],[id*=newsletter]," +
                "[class*=subscribe],[id*=subscribe]," +
                "[class*=promo],[id*=promo]",
        ).remove()

        // Drop high-link-density blocks (nav-like). Keep conservative thresholds to avoid deleting real article bodies.
        val blocks = root.select("nav,ul,ol,div,section,aside")
        for (el in blocks) {
            val text = el.text().trim()
            val textLen = text.length
            if (textLen <= 0) continue
            val links = el.select("a")
            if (links.size < 4) continue
            val linkTextLen = links.joinToString("") { it.text() }.length
            val density = (linkTextLen.toDouble() / textLen.toDouble()).coerceIn(0.0, 1.0)
            if (density >= 0.70 && textLen < 1200) {
                el.remove()
            }
        }
    }

    private fun normalizeMarkdown(md: String): String {
        val s = md.replace("\r\n", "\n")
        // Collapse excessive blank lines.
        return s
            .lines()
            .joinToString("\n") { it.trimEnd() }
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
    }

    private fun absolutizeLinks(doc: Document) {
        doc.select("a[href]").forEach { el ->
            val abs = el.absUrl("href").trim()
            if (abs.isNotEmpty()) el.attr("href", abs)
        }
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

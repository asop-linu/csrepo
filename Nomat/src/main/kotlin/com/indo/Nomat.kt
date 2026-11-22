package com.indo

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element
import java.net.URLEncoder
import java.util.Base64

class Nomat : MainAPI() {
    override var mainUrl = "https://nomat.world"
    override var name = "Nomat"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie)

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Rekomendasi",
        "$mainUrl/slug/film-terbaru/" to "Film Terbaru",
        "$mainUrl/slug/film-box-office/" to "Box Office",
        "$mainUrl/slug/semua-film/" to "Semua Film"
    )

    private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36"

    private fun headers(ref: String = "$mainUrl/") = mapOf(
        "User-Agent" to userAgent,
        "Referer" to ref
    )

    private fun posterFrom(style: String?): String? {
        if (style.isNullOrBlank()) return null
        return Regex("""background\s*:\s*url\(['"]?([^'")]+)""")
            .find(style)?.groupValues?.getOrNull(1)?.ifBlank { null }
    }

    private fun toSearchResponse(el: Element): SearchResponse? {
        val link = el.selectFirst("a[href*='/play/']") ?: return null
        val href = fixUrlNull(link.attr("href").ifBlank { null }) ?: return null
        val title = el.selectFirst("div.title, .item .title")?.text()?.trim()?.ifBlank { null }
            ?: link.attr("title").trim().ifBlank { null }
            ?: return null
        val poster = posterFrom(el.selectFirst("div.poster")?.attr("style") ?: el.html())
        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = poster
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val base = request.data.trimEnd('/')
        val url = if (page <= 1) "$base/" else "$base/$page/"
        val doc = app.get(url, headers = headers()).document
        val items = doc.select("a[href*='/play/']")
            .filter { it.selectFirst("div.item, div.item-content") != null }
            .mapNotNull(::toSearchResponse)
            .distinctBy { it.url }
        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = URLEncoder.encode(query.trim(), "UTF-8")
        val doc = app.get("$mainUrl/search/?searchval=$q", headers = headers()).document
        return doc.select("a[href*='/play/']")
            .filter { it.selectFirst("div.item, div.item-content") != null }
            .mapNotNull(::toSearchResponse)
            .distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, headers = headers(url)).document
        val title = doc.selectFirst("div.video-title h1")?.text()?.trim()?.ifBlank { null }
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()?.ifBlank { null }
            ?: throw ErrorLoadingException("Title not found")
        val poster = posterFrom(doc.selectFirst("div.video-poster")?.attr("style"))
            ?: doc.selectFirst("meta[property=og:image]")?.attr("content")?.ifBlank { null }
        val plot = doc.selectFirst("div.video-synopsis")?.text()?.trim()
            ?.removePrefix("Sinopsis:")?.trim()?.ifBlank { null }
        val year = doc.selectFirst("a[href*='/category/year/']")?.text()?.trim()?.toIntOrNull()
            ?: Regex("\\b(19\\d{2}|20\\d{2})\\b").find(title)?.groupValues?.get(1)?.toIntOrNull()
        val tags = doc.select("div.video-genre a").map { it.text().trim() }
            .filter { it.isNotBlank() }
            .distinct()

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.plot = plot
            this.year = year
            this.tags = tags
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data, headers = headers(data)).document
        val playerUrl = fixUrlNull(
            doc.selectFirst("a[href*='nontonhemat.link']")?.attr("href")?.ifBlank { null }
        ) ?: return false

        val html = app.get(playerUrl, headers = headers(data)).text
        if (html.contains("Invalid Credentials", ignoreCase = true)) return false

        val embeds = Regex("""data-url=["']([^"']+)["']""").findAll(html)
            .mapNotNull { match ->
                val encoded = match.groupValues[1]
                try {
                    String(Base64.getDecoder().decode(encoded), Charsets.UTF_8)
                } catch (_: Exception) {
                    fixUrlNull(encoded)
                }
            }
            .filter { it.startsWith("http") }
            .distinct()

        var found = false
        for (embed in embeds) {
            try {
                if (loadExtractor(embed, playerUrl, subtitleCallback, callback)) found = true
            } catch (_: Exception) {
            }
        }
        return found
    }
}

package com.indo

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Element

/**
 * Rebahin — situs utama https://rebahin.cam (sebelumnya memakai API privat di
 * IP DigitalOcean yang mati 139.59.197.199).
 *
 * Struktur situs:
 *  - Kartu film: <article class="kartu"> a.judul (judul+href), .poster img (TMDB poster),
 *    .nilai (skor "5,7" koma desimal), .kartu-kaki (tahun · negara)
 *  - Daftar: /film/ (paginasi /film/2/), /bioskop/ (paginasi /bioskop/2/)
 *  - Detail: /film/<tmdb>-<slug>/ ; Nonton: /nonton/<tmdb>-<slug>/ (iframe plyr.freeon.site)
 */
class Rebahin : MainAPI() {
    override var mainUrl = "https://rebahin.cam"
    override var name = "Rebahin"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie)

    override val mainPage = mainPageOf(
        "$mainUrl/film/" to "Film Terbaru",
        "$mainUrl/bioskop/" to "Bioskop",
    )

    private fun parseCard(card: Element): SearchResponse? {
        val a = card.selectFirst("a.judul") ?: return null
        val href = fixUrl(a.attr("href").trim())
        val title = a.text().trim()
        if (title.isBlank() || href.isBlank()) return null

        val poster = card.selectFirst(".poster img")?.attr("src")?.ifBlank { null }
        val scoreText = card.selectFirst(".nilai")?.text()?.trim()
        val score = scoreText?.replace(",", ".")?.toDoubleOrNull()
        val footer = card.selectFirst(".kartu-kaki")?.text()?.trim()
        val year = Regex("(\\d{4})").find(footer ?: "")?.groupValues?.getOrNull(1)?.toIntOrNull()

        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = poster
            this.year = year
            if (score != null && score in 0.0..10.0) this.score = Score.from10(score)
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) request.data else "${request.data}$page/"
        val doc = app.get(url).document
        val items = doc.select("article.kartu").mapNotNull { parseCard(it) }
        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = java.net.URLEncoder.encode(query.trim(), "UTF-8")
        val doc = app.get("$mainUrl/?s=$encoded").document
        return doc.select("article.kartu").mapNotNull { parseCard(it) }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document

        val title = (doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?: doc.selectFirst("h1")?.text()?.trim()
            ?: throw ErrorLoadingException("Title not found"))
            .replace(Regex("\\s*- REBAHIN\\s*$", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s*\\(20\\d{2}\\)\\s*$"), "")
            .trim()

        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")?.ifBlank { null }
        val plot = doc.selectFirst("p.sinopsis")?.text()?.trim()?.ifBlank { null }
        val tags = doc.select("a[href*=/genre/]").map { it.text().trim() }.distinct().filter { it.isNotBlank() }
        val year = doc.selectFirst("h1 small")?.text()?.trim()?.toIntOrNull()
            ?: Regex("(\\b20\\d{2}\\b)").find(url)?.groupValues?.getOrNull(1)?.toIntOrNull()
        val score = doc.selectFirst("span.bintang")?.text()?.replace(Regex("[^0-9.]"), "")?.toDoubleOrNull()

        // Halaman tonton (pemain iframe) untuk loadData
        val watchUrl = url.replace("/film/", "/nonton/")

        return newMovieLoadResponse(title, url, TvType.Movie, watchUrl) {
            this.posterUrl = poster
            this.plot = plot
            this.tags = tags
            this.year = year
            if (score != null && score in 0.0..10.0) this.score = Score.from10(score)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data).document
        val iframes = doc.select("div.pemutar iframe[src], iframe[src]")
        var found = false
        for (iframe in iframes) {
            val src = iframe.attr("src").trim()
            if (src.isBlank() || !src.startsWith("http")) continue
            val handled = loadExtractor(src, mainUrl, subtitleCallback, callback)
            if (!handled) {
                callback(
                    newExtractorLink(
                        name,
                        name,
                        src,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = "$mainUrl/"
                    }
                )
            }
            found = true
        }
        return found
    }

    private fun fixUrl(url: String): String {
        if (url.startsWith("http")) return url
        return "$mainUrl/${url.removePrefix("/")}"
    }
}

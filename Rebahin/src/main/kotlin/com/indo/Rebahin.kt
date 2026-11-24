package com.indo

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.nicehttp.JsonAsString
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/**
 * Rebahin — situs utama https://rebahin.cam.
 *
 * Struktur situs:
 *  - Kartu film: <article class="kartu"> a.judul (judul+href), .poster img (TMDB poster),
 *    .nilai (skor "5,7" koma desimal), .kartu-kaki (tahun · negara)
 *  - Daftar: /film/ (paginasi /film/2/), /bioskop/ (paginasi /bioskop/2/)
 *  - Detail: /film/<tmdb>-<slug>/ ; Nonton: /nonton/<tmdb>-<slug>/ (iframe player abyss/freeon)
 *
 * Catatan penting: situs ini hanya menyediakan pemutar untuk sebagian judul (rilis baru).
 * Daftar judul yang punya pemutar ada di sitemap /sitemap-nonton.xml, jadi katalog
 * diprioritaskan ke judul tersebut supaya pengguna tidak berakhir di "0 link".
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

    private var playableSlugs: Set<String> = emptySet()
    private var playableFetchedAt = 0L

    private fun slugOf(url: String): String = url.trim().trimEnd('/').substringAfterLast('/')

    /**
     * Ambil daftar slug judul yang punya halaman pemutar dari /sitemap-nonton.xml.
     * Di-cache 30 menit; kalau gagal, kembalikan cache lama / set kosong (tanpa filter).
     */
    private suspend fun getPlayableSlugs(): Set<String> {
        val now = System.currentTimeMillis()
        if (playableSlugs.isNotEmpty() && now - playableFetchedAt < 30 * 60_000L) return playableSlugs

        val slugs = mutableSetOf<String>()
        try {
            for (page in 1..5) {
                val url = if (page == 1) "$mainUrl/sitemap-nonton.xml" else "$mainUrl/sitemap-nonton.xml?hal=$page"
                val body = app.get(url).text
                val found = Regex("""<loc>\s*([^<\s]+)\s*</loc>""").findAll(body)
                    .map { it.groupValues[1] }
                    .filter { it.contains("/nonton/") }
                    .map { slugOf(it) }
                    .filter { it.isNotBlank() }
                    .toList()
                if (found.isEmpty()) break
                slugs += found
            }
        } catch (_: Exception) {
            return playableSlugs
        }

        if (slugs.isNotEmpty()) {
            playableSlugs = slugs
            playableFetchedAt = now
        }
        return playableSlugs
    }

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

        val playable = getPlayableSlugs()
        val filtered = if (playable.isEmpty()) items else items.filter { playable.contains(slugOf(it.url)) }

        return newHomePageResponse(request.name, filtered.ifEmpty { items })
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = java.net.URLEncoder.encode(query.trim(), "UTF-8")
        val doc = app.get("$mainUrl/?s=$encoded").document
        val items = doc.select("article.kartu").mapNotNull { parseCard(it) }

        // Judul yang punya pemutar ditaruh di depan, sisanya tetap ditampilkan.
        val playable = getPlayableSlugs()
        if (playable.isEmpty()) return items
        return items.sortedByDescending { if (playable.contains(slugOf(it.url))) 1 else 0 }
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

        // Pakai tautan "Watch Now" asli bila ada; fallback ke pola /nonton/.
        val watchHref = doc.selectFirst("a[href*=/nonton/]")?.attr("href")?.trim()
        val watchUrl = if (!watchHref.isNullOrBlank()) fixUrl(watchHref) else url.replace("/film/", "/nonton/")

        return newMovieLoadResponse(title, url, TvType.Movie, watchUrl) {
            this.posterUrl = poster
            this.plot = plot
            this.tags = tags
            this.year = year
            if (score != null && score in 0.0..10.0) this.score = Score.from10(score)
        }
    }

    private val abyssDatas = Regex("""datas\s*=\s*["']([A-Za-z0-9+/=_\-]+)["']""")

    private suspend fun extractAbyss(embedUrl: String, callback: (ExtractorLink) -> Unit): Boolean {
        val html = app.get(embedUrl, referer = "$mainUrl/").text
        val enc = abyssDatas.find(html)?.groupValues?.getOrNull(1) ?: return false

        val payload = JsonAsString(JSONObject().put("text", enc).toString())
        var sources: JSONArray? = null
        for (attempt in 1..2) {
            try {
                val text = app.post(
                    "https://enc-dec.app/api/dec-abyss",
                    headers = mapOf(
                        "Content-Type" to "application/json",
                        "Accept" to "application/json",
                        "Origin" to "https://abyssplayer.com",
                        "Referer" to "https://abyssplayer.com/"
                    ),
                    json = payload
                ).text
                val json = JSONObject(text)
                val arr = json.optJSONObject("result")?.optJSONArray("sources")
                if (json.optInt("status", 200) == 200 && arr != null && arr.length() > 0) {
                    sources = arr
                    break
                }
            } catch (_: Exception) {
                // coba lagi
            }
            if (attempt < 2) delay(500)
        }

        val list = sources ?: return false
        val emitted = mutableSetOf<String>()
        var found = false
        for (i in 0 until list.length()) {
            val item = list.optJSONObject(i) ?: continue
            val url = item.optString("url").trim()
            if (!url.startsWith("http") || !emitted.add(url)) continue

            val type = item.optString("type")
            val quality = when {
                type.contains("1080") -> Qualities.P1080.value
                type.contains("720") -> Qualities.P720.value
                type.contains("480") -> Qualities.P480.value
                type.contains("360") -> Qualities.P360.value
                else -> Qualities.Unknown.value
            }
            callback(newExtractorLink("Abyss", "Abyss $type", url, type = ExtractorLinkType.VIDEO) {
                this.quality = quality
                this.referer = "https://abyssplayer.com/"
                this.headers = mapOf("Referer" to "https://abyssplayer.com/")
            })
            found = true
        }
        return found
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val watchUrl = when {
            data.contains("/nonton/") -> data
            data.contains("/film/") -> data.replace("/film/", "/nonton/")
            else -> data
        }

        val page = app.get(watchUrl, referer = "$mainUrl/").text
        val doc = Jsoup.parse(page, watchUrl)

        val candidates = linkedSetOf<String>()
        doc.select("iframe[src], iframe[data-src], iframe[data-lazy-src]").forEach { el ->
            for (attr in listOf("src", "data-src", "data-lazy-src")) {
                val value = el.attr(attr).trim()
                if (value.startsWith("http")) candidates += value
            }
        }
        // Cadangan bila iframe dirakit lewat script.
        Regex("""https?://(?:player\.)?abyssplayer\.com/[A-Za-z0-9_-]+""").findAll(page)
            .forEach { candidates += it.value }

        if (candidates.isEmpty()) {
            throw ErrorLoadingException(
                "Rebahin: pemutar untuk judul ini belum tersedia di situs. Coba judul rilis terbaru."
            )
        }

        var found = false
        for (src in candidates) {
            try {
                val handled = when {
                    src.contains("abyssplayer") -> extractAbyss(src, callback)
                    else -> loadExtractor(src, mainUrl, subtitleCallback, callback)
                }
                if (handled) found = true
            } catch (_: Exception) {
            }
        }

        if (!found) {
            throw ErrorLoadingException("Rebahin: gagal mengekstrak pemutar untuk judul ini.")
        }
        return true
    }

    private fun fixUrl(url: String): String {
        if (url.startsWith("http")) return url
        return "$mainUrl/${url.removePrefix("/")}"
    }
}

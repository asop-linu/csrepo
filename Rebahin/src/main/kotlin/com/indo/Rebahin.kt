package com.indo

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.nicehttp.JsonAsString
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
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
 *  - Beranda: blok section.blok dengan judul h1/h2.judul-blok; kartu biasa article.kartu,
 *    kartu "Trending Now" memakai a.tren-kartu bergperingkat.
 *  - Detail: /film/<tmdb>-<slug>/ ; Nonton: /nonton/<tmdb>-<slug>/ (iframe player abyss/freeon)
 *
 * Catatan penting: situs ini hanya menyediakan pemutar untuk sebagian judul (rilis baru).
 * Daftar judul yang punya pemutar ada di sitemap /sitemap-nonton.xml, jadi judul tanpa
 * pemutar disembunyikan dari daftar maupun pencarian supaya tidak berakhir "0 link".
 * Beranda tidak punya paginasi; blok kategori (/terbaru/, /bioskop/, dst.) tidak dipakai
 * karena isinya berbeda dari yang ditampilkan di halaman depan.
 */
class Rebahin : MainAPI() {
    override var mainUrl = "https://rebahin.cam"
    override var name = "Rebahin"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie)

    // Label persis seperti blok beranda rebahin.cam; marker hanya pembeda request.
    override val mainPage = mainPageOf(
        "$mainUrl/?bagian=film-terbaru" to "Film Terbaru",
        "$mainUrl/?bagian=bioskop" to "Sedang Tayang di Bioskop",
        "$mainUrl/?bagian=trending" to "Trending Now",
        "$mainUrl/?bagian=rating" to "Rating Tertinggi",
    )

    private var playableSlugs: Set<String> = emptySet()
    private var playableFetchedAt = 0L
    private val playableMutex = Mutex()

    companion object {
        private const val PLAYABLE_CACHE_MS = 30 * 60_000L
        private const val PAGE_TIMEOUT_MS = 4_000L
        private const val EMBED_TIMEOUT_MS = 4_000L
        private const val DEC_TIMEOUT_MS = 4_000L
        private const val EXTRACT_TIMEOUT_MS = 5_000L
        private const val DEC_RETRY_DELAY_MS = 250L
        private const val MAX_PLAYABLE_SITEMAP_PAGES = 5
        private const val MAX_ABYSS_CANDIDATES = 2
    }

    private fun slugOf(url: String): String = url.trim().trimEnd('/').substringAfterLast('/')

    /**
     * Ambil daftar slug judul yang punya halaman pemutar dari /sitemap-nonton.xml.
     * Di-cache 30 menit; single-flight supaya 4 section tidak fetch sitemap bersamaan.
     * Kalau refresh gagal pakai cache lama; kalau tidak ada cache, return kosong (fail closed).
     */
    private suspend fun getPlayableSlugs(): Set<String> = playableMutex.withLock {
        val now = System.currentTimeMillis()
        if (playableSlugs.isNotEmpty() && now - playableFetchedAt < PLAYABLE_CACHE_MS) {
            return@withLock playableSlugs
        }

        val fetched = try {
            fetchPlayableSlugs()
        } catch (_: Exception) {
            emptySet()
        }

        if (fetched.isNotEmpty()) {
            playableSlugs = fetched
            playableFetchedAt = now
            fetched
        } else {
            playableSlugs
        }
    }

    private suspend fun fetchPlayableSlugs(): Set<String> = supervisorScope {
        val slugRegex = Regex("""<loc>\s*([^<\s]+)\s*</loc>""")

        fun parse(body: String): List<String> = slugRegex.findAll(body)
            .map { it.groupValues[1] }
            .filter { it.contains("/nonton/") }
            .map { slugOf(it) }
            .filter { it.isNotBlank() }
            .toList()

        suspend fun fetchPage(page: Int): List<String> {
            val url = if (page == 1) "$mainUrl/sitemap-nonton.xml" else "$mainUrl/sitemap-nonton.xml?hal=$page"
            val body = withTimeoutOrNull(PAGE_TIMEOUT_MS) { app.get(url).text } ?: return emptyList()
            return parse(body)
        }

        val first = fetchPage(1)
        val rest = (2..MAX_PLAYABLE_SITEMAP_PAGES)
            .map { page -> async { fetchPage(page) } }
            .awaitAll()
            .flatten()

        (first + rest).toSet()
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

    private fun parseTrendingCard(card: Element): SearchResponse? {
        val href = card.attr("href").trim()
        val title = card.selectFirst("span.sembunyi")?.text()?.trim()
            ?.replace(Regex("""^Peringkat\s+\d+\s*:\s*""", RegexOption.IGNORE_CASE), "")
            ?.trim()
        if (title.isNullOrBlank() || href.isBlank()) return null

        val poster = card.selectFirst("span.tren-gbr img")?.attr("src")?.ifBlank { null }
        return newMovieSearchResponse(title, fixUrl(href), TvType.Movie) {
            this.posterUrl = poster
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // Beranda tidak punya paginasi; query marker diabaikan situs.
        if (page > 1) return newHomePageResponse(request.name, emptyList(), hasNext = false)

        val doc = withTimeoutOrNull(PAGE_TIMEOUT_MS) { app.get(mainUrl).document }
            ?: return newHomePageResponse(request.name, emptyList(), hasNext = false)
        val block = doc.select("section.blok").firstOrNull { section ->
            section.selectFirst("h1.judul-blok, h2.judul-blok")?.ownText()?.trim() == request.name
        } ?: return newHomePageResponse(request.name, emptyList(), hasNext = false)

        val items = if (request.name == "Trending Now") {
            block.select("a.tren-kartu").mapNotNull { parseTrendingCard(it) }
        } else {
            block.select("article.kartu").mapNotNull { parseCard(it) }
        }

        val playable = getPlayableSlugs()
        // Sembunyikan judul yang belum punya pemutar agar tidak berakhir "0 link".
        // Fail closed: kalau sitemap playable tidak bisa diverifikasi, section kosong, bukan menampilkan no-playable.
        val filtered = if (playable.isEmpty()) emptyList() else items.filter { playable.contains(slugOf(it.url)) }

        return newHomePageResponse(request.name, filtered, hasNext = false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = java.net.URLEncoder.encode(query.trim(), "UTF-8")
        val doc = withTimeoutOrNull(PAGE_TIMEOUT_MS) { app.get("$mainUrl/?s=$encoded").document }
            ?: return emptyList()
        val items = doc.select("article.kartu").mapNotNull { parseCard(it) }

        val playable = getPlayableSlugs()
        // Search juga fail closed: jangan tampilkan kalau no-playable tidak bisa diverifikasi.
        if (playable.isEmpty()) return emptyList()
        return items.filter { playable.contains(slugOf(it.url)) }
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

    private suspend fun extractAbyss(embedUrl: String): List<ExtractorLink> {
        val html = withTimeoutOrNull(EMBED_TIMEOUT_MS) {
            app.get(embedUrl, referer = "$mainUrl/").text
        } ?: return emptyList()

        val enc = abyssDatas.find(html)?.groupValues?.getOrNull(1) ?: return emptyList()
        val payload = JsonAsString(JSONObject().put("text", enc).toString())
        var sources: JSONArray? = null

        // Retry maks 1 untuk transient; tidak retry 4xx. Total per candidate tetap bounded.
        for (attempt in 1..2) {
            val parsed = try {
                withTimeoutOrNull(DEC_TIMEOUT_MS) {
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
                    JSONObject(text)
                }
            } catch (_: Exception) {
                null
            }

            val arr = parsed?.optJSONObject("result")?.optJSONArray("sources")
            if (parsed?.optInt("status", 200) == 200 && arr != null && arr.length() > 0) {
                sources = arr
                break
            }
            if (attempt < 2) delay(DEC_RETRY_DELAY_MS)
        }

        val list = sources ?: return emptyList()
        val emitted = mutableSetOf<String>()
        val links = mutableListOf<ExtractorLink>()

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
            links += newExtractorLink("Abyss", "Abyss $type", url, type = ExtractorLinkType.VIDEO) {
                this.quality = quality
                this.referer = "https://abyssplayer.com/"
                this.headers = mapOf("Referer" to "https://abyssplayer.com/")
            }
        }
        return links.distinctBy { it.url }
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

        val page = withTimeoutOrNull(PAGE_TIMEOUT_MS) {
            app.get(watchUrl, referer = "$mainUrl/").text
        } ?: return false

        val doc = Jsoup.parse(page, watchUrl)
        val candidates = linkedSetOf<String>()
        doc.select("iframe[src], iframe[data-src], iframe[data-lazy-src]").forEach { el ->
            for (attr in listOf("src", "data-src", "data-lazy-src")) {
                val value = el.attr(attr).trim()
                if (value.startsWith("http") && isPlayerCandidate(value)) candidates += value
            }
        }
        // Cadangan bila iframe dirakit lewat script.
        Regex("""https?://(?:player\.)?abyssplayer\.com/[A-Za-z0-9_-]+""").findAll(page)
            .forEach { candidates += it.value }

        if (candidates.isEmpty()) return false

        // Fast path Abyss; Rebahin biasanya satu iframe. Bounded + timeout.
        val abyssCandidates = candidates.filter { it.contains("abyssplayer.com") }
            .take(MAX_ABYSS_CANDIDATES)
        val links = if (abyssCandidates.isNotEmpty()) {
            supervisorScope {
                abyssCandidates.map { src ->
                    async {
                        withTimeoutOrNull(EXTRACT_TIMEOUT_MS) { extractAbyss(src) } ?: emptyList()
                    }
                }.awaitAll().flatten()
            }
        } else {
            val collected = mutableListOf<ExtractorLink>()
            val nonAbyss = candidates.filterNot { it.contains("abyssplayer.com") }.take(MAX_ABYSS_CANDIDATES)
            for (src in nonAbyss) {
                try {
                    withTimeoutOrNull(EXTRACT_TIMEOUT_MS) {
                        loadExtractor(src, mainUrl, subtitleCallback) { link -> collected += link }
                    }
                } catch (_: Exception) {
                }
            }
            collected
        }.distinctBy { it.url }

        if (links.isEmpty()) return false

        // Emit sequential; callback tidak concurrent.
        links.forEach(callback)
        return true
    }

    private fun isPlayerCandidate(url: String): Boolean {
        val lower = url.lowercase()
        if (lower.contains("googletagmanager")) return false
        if (lower.contains("google-analytics")) return false
        if (lower.contains("doubleclick")) return false
        if (lower.contains("facebook.com")) return false
        if (lower.contains("youtube.com") || lower.contains("youtu.be")) return false
        if (lower.startsWith("javascript:") || lower.startsWith("#")) return false
        return lower.contains("abyssplayer.com") ||
            lower.contains("freeon") ||
            lower.contains("player.") ||
            lower.contains("embed") ||
            lower.contains("m3u8")
    }

    private fun fixUrl(url: String): String {
        if (url.startsWith("http")) return url
        return "$mainUrl/${url.removePrefix("/")}"
    }
}

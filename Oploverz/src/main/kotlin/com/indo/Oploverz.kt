package com.indo

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.utils.*
import com.lagradost.nicehttp.JsonAsString
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/**
 * Oploverz — situs utama ditentukan oleh portal resmi https://oploverz.mov/.
 *
 * Portal menyimpan alamat aktif di tombol "Masuk ke Website Utama"
 * (a#main-link / tombol data-copy). Domain situs bisa berubah, jadi selalu
 * diambil ulang dari portal (cache 30 menit) sebelum request.
 *
 * Situs baru (SvelteKit) memakai backend JSON https://backapi.oploverz.ac:
 *  - /api/episodes?page=&pageSize=&sort=latest  -> update episode
 *  - /api/series?page=&pageSize=&sort=latest    -> daftar anime
 *  - /api/series?q=<query>                      -> pencarian
 *  - /api/series/<slug>                         -> detail series
 *  - /api/series/<slug>/episodes?page=&pageSize= -> daftar episode
 *  - /api/episodes/<id>                         -> streamUrl + downloadUrl
 *
 * Semua stream memakai player.abyssplayer.com. Halaman player menyimpan
 * payload terenkripsi di `datas`, didekripsi lewat https://enc-dec.app/api/dec-abyss
 * (butuh Referer https://abyssplayer.com/).
 */
class Oploverz : MainAPI() {
    override var mainUrl = "https://oploverz.diy"
    override var name = "Oploverz"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    private var apiUrl = "https://backapi.oploverz.ac"
    private var lastResolve = 0L
    private val portalUrl = "https://oploverz.mov/"

    companion object {
        private const val RESOLVE_INTERVAL = 30 * 60 * 1000L
        private const val PAGE_SIZE = 30
        private const val EPISODE_PAGE_SIZE = 500

        fun getStatus(t: String?): ShowStatus {
            return when {
                t?.contains("Completed", true) == true || t?.contains("Tamat", true) == true -> ShowStatus.Completed
                t?.contains("Ongoing", true) == true -> ShowStatus.Ongoing
                else -> ShowStatus.Completed
            }
        }
    }

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36",
        "Referer" to "$mainUrl/"
    )

    override val mainPage = mainPageOf(
        "latest-episodes" to "Update Terbaru",
        "all-series" to "Semua Anime",
    )

    /**
     * Ambil domain aktif dari portal oploverz.mov, lalu turunkan base API dari
     * HTML situs (biasanya https://backapi.oploverz.ac).
     */
    private suspend fun ensureResolved() {
        val now = System.currentTimeMillis()
        if (now - lastResolve < RESOLVE_INTERVAL && lastResolve != 0L) return

        try {
            val doc = app.get(portalUrl, headers = headers).document
            val href = doc.selectFirst("a#main-link[href]")?.attr("href")?.ifBlank { null }
                ?: doc.selectFirst("button[data-copy^=https://oploverz.]")?.attr("data-copy")?.ifBlank { null }
            val origin = href?.let { normalizeOrigin(it) }
            if (origin != null) mainUrl = origin
        } catch (_: Exception) {
            // Portal tidak bisa diakses: pakai domain terakhir / default.
        }

        try {
            val html = app.get("$mainUrl/", headers = headers).text
            Regex("""https://backapi\.[a-zA-Z0-9.-]+""").find(html)?.value?.let { apiUrl = it }
        } catch (_: Exception) {
            // API default dipertahankan.
        }

        lastResolve = now
    }

    private fun normalizeOrigin(url: String): String? {
        return try {
            val uri = java.net.URI(url)
            if (uri.scheme != "http" && uri.scheme != "https") return null
            val host = uri.host ?: return null
            "${uri.scheme}://$host"
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun apiGet(url: String): JSONObject {
        return JSONObject(app.get(url, headers = headers).text)
    }

    private fun buildUrl(base: String, params: Map<String, String>): String {
        if (params.isEmpty()) return base
        val query = params.entries.joinToString("&") { (k, v) ->
            "$k=${URLEncoder.encode(v, "UTF-8")}"
        }
        return "$base?$query"
    }

    /** Judul tanpa episode tidak bisa diputar, jangan tampilkan di daftar. */
    private fun isPlayable(series: JSONObject): Boolean {
        return series.optInt("totalEpisodes", 1) > 0
    }

    private fun toSearchResponse(series: JSONObject): SearchResponse? {
        if (!isPlayable(series)) return null
        val title = series.optString("title").ifBlank { return null }
        val slug = series.optString("slug").ifBlank { return null }
        val poster = series.optString("poster").ifBlank { null }
        val releaseType = series.optString("releaseType")
        val type = if (releaseType.equals("Movie", true)) TvType.AnimeMovie else TvType.Anime

        return newAnimeSearchResponse(title, "$mainUrl/series/$slug", type) {
            this.posterUrl = poster
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        ensureResolved()
        val items = if (request.data == "latest-episodes") {
            latestEpisodes(page)
        } else {
            seriesPage(page)
        }
        return newHomePageResponse(request.name, items)
    }

    private suspend fun latestEpisodes(page: Int): List<SearchResponse> {
        val url = buildUrl(
            "$apiUrl/api/episodes",
            mapOf(
                "page" to page.toString(),
                "pageSize" to PAGE_SIZE.toString(),
                "sort" to "latest",
            )
        )
        val arr = apiGet(url).optJSONArray("data") ?: return emptyList()
        val seen = mutableSetOf<String>()
        val result = mutableListOf<SearchResponse>()
        for (i in 0 until arr.length()) {
            val series = arr.optJSONObject(i)?.optJSONObject("series") ?: continue
            val slug = series.optString("slug")
            if (slug.isBlank() || !seen.add(slug)) continue
            toSearchResponse(series)?.let { result.add(it) }
        }
        return result
    }

    private suspend fun seriesPage(page: Int): List<SearchResponse> {
        val url = buildUrl(
            "$apiUrl/api/series",
            mapOf(
                "page" to page.toString(),
                "pageSize" to PAGE_SIZE.toString(),
                "sort" to "latest",
            )
        )
        val arr = apiGet(url).optJSONArray("data") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            arr.optJSONObject(i)?.let { toSearchResponse(it) }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        ensureResolved()
        val url = buildUrl(
            "$apiUrl/api/series",
            mapOf(
                "q" to query.trim(),
                "pageSize" to "50",
            )
        )
        val arr = apiGet(url).optJSONArray("data") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            arr.optJSONObject(i)?.let { toSearchResponse(it) }
        }
    }

    private fun slugOf(url: String): String? {
        Regex("""/(?:series|movie)/([^/?#]+)""").find(url)?.groupValues?.getOrNull(1)
            ?.takeIf { it.isNotBlank() }?.let { return it }
        return url.trimEnd('/').substringAfterLast('/').substringBefore('?').ifBlank { null }
    }

    private suspend fun allEpisodes(slug: String): List<JSONObject> {
        val episodes = mutableListOf<JSONObject>()
        var page = 1
        while (true) {
            val url = buildUrl(
                "$apiUrl/api/series/$slug/episodes",
                mapOf(
                    "page" to page.toString(),
                    "pageSize" to EPISODE_PAGE_SIZE.toString(),
                )
            )
            val json = try {
                apiGet(url)
            } catch (_: Exception) {
                break
            }
            val arr = json.optJSONArray("data") ?: break
            for (i in 0 until arr.length()) {
                arr.optJSONObject(i)?.let { episodes.add(it) }
            }
            val lastPage = json.optJSONObject("meta")?.optInt("lastPage", page) ?: page
            if (page >= lastPage || arr.length() == 0) break
            page++
        }
        return episodes
    }

    override suspend fun load(url: String): LoadResponse {
        ensureResolved()
        val slug = slugOf(url) ?: throw ErrorLoadingException("Slug tidak ditemukan")
        val series = apiGet("$apiUrl/api/series/$slug").optJSONObject("data")
            ?: throw ErrorLoadingException("Anime tidak ditemukan")

        val title = series.optString("title").ifBlank { throw ErrorLoadingException("Judul tidak ditemukan") }
        val poster = series.optString("poster").ifBlank { null }
        val description = series.optString("description").ifBlank { null }
        val status = getStatus(series.optString("status"))
        val year = Regex("\\b(19\\d{2}|20\\d{2})\\b")
            .find(series.optString("releaseDate"))?.groupValues?.getOrNull(1)?.toIntOrNull()
        val genres = mutableListOf<String>()
        series.optJSONArray("genres")?.let { arr ->
            for (i in 0 until arr.length()) {
                arr.optJSONObject(i)?.optString("name")?.takeIf { it.isNotBlank() }?.let { genres.add(it) }
            }
        }
        val isMovie = series.optString("releaseType").equals("Movie", true)
        val episodes = allEpisodes(slug)
        if (episodes.isEmpty()) throw ErrorLoadingException("Belum ada episode yang bisa diputar")
        val score = series.optDouble("score").takeIf { !it.isNaN() && it in 0.0..10.0 }

        val tracker = try {
            APIHolder.getTracker(listOf(title), TrackerType.getTypes(TvType.Anime), year, true)
        } catch (_: Exception) {
            null
        }

        if (isMovie) {
            val epId = episodes.firstOrNull()?.optInt("id") ?: 0
            val data = if (epId > 0) "$apiUrl/api/episodes/$epId" else "$apiUrl/api/series/$slug/episodes"
            return newMovieLoadResponse(title, url, TvType.AnimeMovie, data) {
                this.posterUrl = tracker?.image ?: poster
                this.year = year
                this.plot = description
                this.tags = genres
                if (score != null) this.score = Score.from10(score)
            }
        }

        val episodeList = episodes.mapNotNull { ep ->
            val id = ep.optInt("id", 0)
            if (id <= 0) return@mapNotNull null
            val num = ep.optString("episodeNumber").toIntOrNull()
            val epTitle = ep.optString("title").ifBlank { null } ?: "Episode ${num ?: ""}"
            newEpisode("$apiUrl/api/episodes/$id") {
                this.name = epTitle.trim()
                this.episode = num
            }
        }.sortedBy { it.episode ?: Int.MAX_VALUE }

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            engName = title
            posterUrl = tracker?.image ?: poster
            backgroundPosterUrl = tracker?.cover ?: poster
            this.year = year
            addEpisodes(DubStatus.Subbed, episodeList)
            showStatus = status
            plot = description
            this.tags = genres
            if (score != null) this.score = Score.from10(score)
            addMalId(tracker?.malId)
            addAniListId(tracker?.aniId?.toIntOrNull())
        }
    }

    /** Cari data episode dari URL API baru, URL halaman baru, atau URL lama. */
    private suspend fun resolveEpisode(data: String): JSONObject? {
        if (data.contains("/api/episodes/")) {
            return try {
                apiGet(data).optJSONObject("data")
            } catch (_: Exception) {
                null
            }
        }

        if (data.contains("/api/series/") && data.contains("/episodes")) {
            val arr = try {
                apiGet(data).optJSONArray("data")
            } catch (_: Exception) {
                null
            }
            return arr?.optJSONObject(0)
        }

        val newFormat = Regex("""/series/([^/?#]+)/episode/(\d+)""").find(data)
        val oldFormat = Regex("""/([^/?#]+?)-episode-(\d+)""").find(data)
        val match = newFormat ?: oldFormat
        if (match != null) {
            val slug = match.groupValues[1]
            val number = match.groupValues[2]
            val list = allEpisodes(slug)
            return list.firstOrNull { it.optString("episodeNumber") == number }
                ?: list.firstOrNull()
        }

        return null
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        ensureResolved()
        val episode = resolveEpisode(data) ?: return false
        val streams = episode.optJSONArray("streamUrl") ?: return false
        if (streams.length() == 0) return false

        var found = false
        val emittedQualities = mutableSetOf<Int>()
        for (i in 0 until streams.length()) {
            val stream = streams.optJSONObject(i) ?: continue
            val url = stream.optString("url").ifBlank { null } ?: continue
            if (!url.startsWith("http")) continue
            val source = stream.optString("source")

            val ok = when {
                url.contains("abyssplayer", ignoreCase = true) ->
                    extractAbyss(url, source, emittedQualities, subtitleCallback, callback)

                else -> {
                    try {
                        loadExtractor(url, "$mainUrl/", subtitleCallback, callback)
                    } catch (_: Exception) {
                        false
                    }
                }
            }
            if (ok) found = true
        }
        return found
    }

    private val abyssDatas = Regex("""datas\s*=\s*["']([A-Za-z0-9+/=_\-]+)["']""")

    private suspend fun extractAbyss(
        embedUrl: String,
        source: String,
        emittedQualities: MutableSet<Int>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val html = try {
            app.get(embedUrl, referer = "$mainUrl/").text
        } catch (_: Exception) {
            return false
        }
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
            val label = when {
                type.isNotBlank() -> type
                source.isNotBlank() -> source
                else -> "Abyss"
            }
            val quality = when {
                type.contains("1080") || source.contains("1080") -> Qualities.P1080.value
                type.contains("720") || source.contains("720") -> Qualities.P720.value
                type.contains("480") || source.contains("480") -> Qualities.P480.value
                type.contains("360") || source.contains("360") -> Qualities.P360.value
                else -> Qualities.Unknown.value
            }
            // Satu episode bisa punya beberapa streamUrl (480p/720p/1080p) yang
            // setelah didekripsi memuat kualitas yang sama. Cukup satu per kualitas.
            if (quality != Qualities.Unknown.value && !emittedQualities.add(quality)) continue
            callback(newExtractorLink("Abyss", "Abyss $label", url, type = ExtractorLinkType.VIDEO) {
                this.quality = quality
                this.referer = "https://abyssplayer.com/"
                this.headers = mapOf("Referer" to "https://abyssplayer.com/")
            })
            found = true
        }
        return found
    }
}

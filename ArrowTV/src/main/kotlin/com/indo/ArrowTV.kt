package com.indo

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.net.URLEncoder
import org.json.JSONArray
import org.json.JSONObject

/**
 * ArrowTV (arrowtv.net) — katalog TMDB + player iframe player.cinezo.live.
 *
 * Sumber stream diambil dari endpoint JSON milik player:
 *  - film : https://punkflix.net/movie/{tmdbId}.json
 *  - serial: https://punkflix.net/tv/{tmdbId}/{season}/{episode}.json
 * Keduanya mengembalikan master HLS di media.punkflix.net yang hanya mau
 * dilayani bila Referer-nya player.cinezo.live.
 */
class ArrowTV : MainAPI() {
    override var mainUrl = "https://arrowtv.net"
    override var name = "ArrowTV"
    override val hasMainPage = true
    override var lang = "en"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)

    private val ua = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "application/json, text/plain, */*",
        "Referer" to "$mainUrl/"
    )

    private val streamHeaders = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "*/*",
        "Origin" to PLAYER_URL,
        "Referer" to "$PLAYER_URL/"
    )

    override val mainPage = mainPageOf(
        "movie" to "Film Terbaru",
        "tv" to "Serial TV",
        "anime" to "Anime"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val api = when (request.data) {
            "movie" -> "$mainUrl/api/categories?genre=&type=movie&page=$page"
            "tv" -> "$mainUrl/api/categories?genre=&type=tv&page=$page"
            // Tidak ada type "anime" di API; anime = serial bergenre Animation (16).
            else -> "$mainUrl/api/categories?genre=16&type=tv&page=$page"
        }
        val json = runCatching { JSONObject(app.get(api, headers = ua).text) }.getOrNull()
        val results = json?.optJSONArray("results") ?: JSONArray()
        val totalPages = json?.optInt("total_pages", 0) ?: 0
        val items = (0 until results.length()).mapNotNull { i ->
            results.optJSONObject(i)?.let { toSearchResponse(it, request.data) }
        }
        return newHomePageResponse(request, items, page < totalPages)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val api = "$mainUrl/api/search?q=${URLEncoder.encode(query, "UTF-8")}"
        val arr = runCatching { JSONArray(app.get(api, headers = ua).text) }.getOrNull()
            ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            arr.optJSONObject(i)?.let { toSearchResponse(it, null) }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val isTv = url.contains("/tv/")
        val id = Regex("/(?:movie|tv)/(\\d+)").find(url)?.groupValues?.get(1)

        val doc = app.get(url, headers = ua).document
        val raw = doc.html().replace("\\\"", "\"").replace("\\u0026", "&")

        val title = doc.title().substringBefore(" — ").trim().ifBlank { "ArrowTV $id" }
        val poster = tmdbImage(Regex("\"poster\":\"(/[^\"]+)\"").find(raw)?.groupValues?.get(1))
        val genres = Regex("\"genres\":\\[([^\\]]*)\\]").find(raw)?.groupValues?.get(1)
            ?.let { group -> Regex("\"([^\"]+)\"").findAll(group).map { it.groupValues[1] }.toList() }
            ?: emptyList()
        val year = Regex("\"years\":\"(\\d{4})").find(raw)?.groupValues?.get(1)?.toIntOrNull()
        val vote = Regex("\"vote\":([0-9.]+)").find(raw)?.groupValues?.get(1)?.toDoubleOrNull()
        val voteIndex = raw.indexOf("\"vote\":")
        val plot = if (voteIndex >= 0) {
            Regex("\"overview\":\"((?:[^\"\\\\]|\\\\.)*)\"")
                .find(raw.substring(voteIndex))?.groupValues?.get(1)
                ?.replace("\\n", "\n")
                ?.replace("\\\"", "\"")
        } else {
            null
        }

        if (isTv) {
            val seasonsCount = Regex("\"seasonsCount\":(\\d+)").find(raw)
                ?.groupValues?.get(1)?.toIntOrNull() ?: 1
            val episodes = mutableListOf<Episode>()
            for (season in 1..seasonsCount.coerceAtLeast(1)) {
                episodes.addAll(fetchEpisodes(id.orEmpty(), season))
            }
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = plot
                this.tags = genres
                if (vote != null && vote in 0.1..10.0) this.score = Score.from10(vote)
            }
        }

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.year = year
            this.plot = plot
            this.tags = genres
            if (vote != null && vote in 0.1..10.0) this.score = Score.from10(vote)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val isTv = data.contains("/tv/")
        val id = Regex("/(?:movie|tv)/(\\d+)").find(data)?.groupValues?.get(1) ?: return false

        val jsonUrl = if (isTv) {
            val season = Regex("[?&]season=(\\d+)").find(data)?.groupValues?.get(1) ?: "1"
            val episode = Regex("[?&]episode=(\\d+)").find(data)?.groupValues?.get(1) ?: "1"
            "$PLAYER_API/tv/$id/$season/$episode.json"
        } else {
            "$PLAYER_API/movie/$id.json"
        }

        val body = runCatching {
            app.get(jsonUrl, headers = streamHeaders, referer = "$PLAYER_URL/").text
        }.getOrNull() ?: return false

        val json = runCatching { JSONObject(body) }.getOrNull() ?: return false
        val stream = json.optJSONObject("stream") ?: return false
        val streamUrl = stream.optString("hls").ifBlank { stream.optString("original") }
        if (streamUrl.isBlank()) return false

        val resolution = json.optJSONObject("quality")?.optString("resolution").orEmpty()
        val height = resolution.substringAfter('x', "").toIntOrNull() ?: 0

        callback(
            newExtractorLink(
                name,
                if (height > 0) "$name ${height}p" else name,
                streamUrl,
                type = ExtractorLinkType.M3U8
            ) {
                this.referer = "$PLAYER_URL/"
                this.headers = streamHeaders
                if (height > 0) this.quality = height
            }
        )

        val subtitles = json.optJSONArray("subtitles")
        if (subtitles != null) {
            for (i in 0 until subtitles.length()) {
                val sub = subtitles.optJSONObject(i) ?: continue
                val subUrl = sub.optString("url")
                    .ifBlank { sub.optString("src") }
                    .ifBlank { sub.optString("path") }
                if (subUrl.isBlank()) continue
                val label = sub.optString("label")
                    .ifBlank { sub.optString("language") }
                    .ifBlank { sub.optString("lang") }
                subtitleCallback(newSubtitleFile(label.ifBlank { "Subtitle" }, subUrl))
            }
        }
        return true
    }

    private suspend fun fetchEpisodes(id: String, season: Int): List<Episode> {
        if (id.isBlank()) return emptyList()
        val api = "$mainUrl/api/tv-episodes?id=$id&season=$season"
        val json = runCatching { JSONObject(app.get(api, headers = ua).text) }.getOrNull()
            ?: return emptyList()
        val episodes = json.optJSONArray("episodes") ?: return emptyList()
        return (0 until episodes.length()).mapNotNull { i ->
            val ep = episodes.optJSONObject(i) ?: return@mapNotNull null
            val number = ep.optInt("episode_number", i + 1)
            newEpisode("$mainUrl/tv/$id?season=$season&episode=$number") {
                this.name = ep.optString("name").ifBlank { "Episode $number" }
                this.season = season
                this.episode = number
                this.posterUrl = tmdbImage(ep.optString("still_path"))
                this.description = ep.optString("overview").ifBlank { null }
                this.runTime = ep.optInt("runtime", 0).takeIf { it > 0 }
            }
        }
    }

    private fun toSearchResponse(obj: JSONObject, kind: String?): SearchResponse? {
        val id = obj.optInt("id", 0)
        if (id <= 0) return null
        val mediaType = obj.optString("media_type").ifBlank {
            when {
                kind == "movie" -> "movie"
                obj.has("first_air_date") -> "tv"
                obj.has("release_date") -> "movie"
                else -> "tv"
            }
        }
        val isTv = mediaType == "tv"
        val title = obj.optString("title").ifBlank { obj.optString("name") }
        if (title.isBlank()) return null
        val href = "$mainUrl/${if (isTv) "tv" else "movie"}/$id"
        val date = obj.optString("release_date").ifBlank { obj.optString("first_air_date") }
        val vote = obj.optDouble("vote_average", 0.0)
        return newMovieSearchResponse(title, href, if (isTv) TvType.TvSeries else TvType.Movie) {
            this.posterUrl = tmdbImage(obj.optString("poster_path"))
            this.year = date.take(4).toIntOrNull()
            if (vote in 0.1..10.0) this.score = Score.from10(vote)
        }
    }

    private fun tmdbImage(path: String?, size: String = "w500"): String? {
        if (path.isNullOrBlank()) return null
        if (path.startsWith("http")) return path
        return "https://image.tmdb.org/t/p/$size/${path.trimStart('/')}"
    }

    companion object {
        private const val PLAYER_URL = "https://player.cinezo.live"
        private const val PLAYER_API = "https://punkflix.net"
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
    }
}

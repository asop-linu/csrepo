package com.indo

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.nicehttp.JsonAsString
import java.net.URLEncoder
import kotlinx.coroutines.delay
import org.json.JSONObject

class Idlix : MainAPI() {
    override var mainUrl = "https://z3.idlixku.com"
    override var name = "Idlix"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private val cfKiller = CloudflareKiller()

    private val apiHeaders = mapOf(
        "Accept" to "application/json",
        "Referer" to "$mainUrl/"
    )

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Home"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val sections = listOfNotNull(
            getApiSection("Movies", page, "api/movies"),
            getApiSection("TV Series", page, "api/series"),
        )
        return newHomePageResponse(sections)
    }

    private suspend fun getApiSection(name: String, page: Int, apiPath: String): HomePageList? {
        val resp = app.get("$mainUrl/$apiPath?page=$page&limit=24", headers = apiHeaders, interceptor = cfKiller)
        val text = resp.text ?: return null
        val data = try { JSONObject(text).optJSONArray("data") } catch (e: Exception) { return null }
        if (data == null) return null
        val items = (0 until data.length()).mapNotNull { i ->
            val item = data.optJSONObject(i) ?: return@mapNotNull null
            val slug = item.optString("slug", "")
            val title = item.optString("title", "")
            if (slug.isBlank() || title.isBlank()) return@mapNotNull null
            val posterPath = item.optString("posterPath", "")
            val poster = if (posterPath.isNotBlank()) "https://image.tmdb.org/t/p/w500$posterPath" else null
            val voteAvg = item.opt("voteAverage").let { v ->
                when (v) {
                    is Number -> v.toDouble()
                    is String -> v.toDoubleOrNull()
                    else -> null
                }
            }
            val isSeries = apiPath.contains("series")
            val href = if (isSeries) "/series/$slug" else "/movie/$slug"
            if (isSeries)
                newTvSeriesSearchResponse(title, fixUrl(href), TvType.TvSeries) {
                    this.posterUrl = poster; this.score = Score.from10(voteAvg)
                }
            else
                newMovieSearchResponse(title, fixUrl(href), TvType.Movie) {
                    this.posterUrl = poster; this.score = Score.from10(voteAvg)
                }
        }
        return if (items.isEmpty()) null else HomePageList(name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val resp = app.get("$mainUrl/api/search?q=${URLEncoder.encode(query, "UTF-8")}", headers = apiHeaders, interceptor = cfKiller)
        val text = resp.text ?: return emptyList()
        val data = try { JSONObject(text).optJSONArray("results") } catch (e: Exception) { return emptyList() }
        if (data == null) return emptyList()
        return (0 until data.length()).mapNotNull { i ->
            val item = data.optJSONObject(i) ?: return@mapNotNull null
            val slug = item.optString("slug", "")
            val title = item.optString("title", "")
            if (slug.isBlank() || title.isBlank()) return@mapNotNull null
            val posterPath = item.optString("posterPath", "")
            val poster = if (posterPath.isNotBlank()) "https://image.tmdb.org/t/p/w500$posterPath" else null
            val contentType = item.optString("contentType", "movie")
            val voteAvg = item.opt("voteAverage").let { v ->
                when (v) {
                    is Number -> v.toDouble()
                    is String -> v.toDoubleOrNull()
                    else -> null
                }
            }
            val href = if (contentType == "tv_series") "/series/$slug" else "/movie/$slug"
            if (contentType == "tv_series")
                newTvSeriesSearchResponse(title, fixUrl(href), TvType.TvSeries) {
                    this.posterUrl = poster; this.score = Score.from10(voteAvg)
                }
            else
                newMovieSearchResponse(title, fixUrl(href), TvType.Movie) {
                    this.posterUrl = poster; this.score = Score.from10(voteAvg)
                }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val resp = app.get(url, interceptor = cfKiller)
        val raw = resp.text ?: throw ErrorLoadingException("No response")
        val html = raw.replace("\\\"", "\"")

        val isSeries = url.contains("/series/")
        val slug = if (isSeries)
            url.substringAfter("/series/").substringBefore("/").substringBefore("?")
        else
            url.substringAfter("/movie/").substringBefore("/").substringBefore("?")

        val detail = if (slug.isNotBlank()) fetchDetail(slug, isSeries) else null

        val title = detail?.optString("title", "")?.ifBlank { null }
            ?: metaContent(html, "og:title")?.let { cleanTitle(it) }
            ?: Regex("<title>([^<]+)</title>").find(html)?.groupValues?.getOrNull(1)?.let { cleanTitle(it) }
            ?: throw ErrorLoadingException("Title not found")

        val posterPath = detail?.optString("posterPath", "")?.ifBlank { null }
        val poster = posterPath?.let { "https://image.tmdb.org/t/p/w500$it" }
            ?: metaContent(html, "og:image")?.ifBlank { null }

        val description = detail?.optString("overview", "")?.ifBlank { null }
            ?: metaContent(html, "og:description")?.ifBlank { null }

        val year = detail?.optString("releaseDate", "")?.take(4)?.toIntOrNull()
            ?: detail?.optString("firstAirDate", "")?.take(4)?.toIntOrNull()
            ?: metaContent(html, "video:release_date")?.take(4)?.toIntOrNull()
            ?: Regex("\\b(19\\d{2}|20\\d{2})\\b").find(title)?.groupValues?.getOrNull(1)?.toIntOrNull()

        val tags = detail?.optJSONArray("genres")?.let { genres ->
            (0 until genres.length()).mapNotNull { i ->
                genres.optJSONObject(i)?.optString("name", "")?.ifBlank { null }
            }
        }?.takeIf { it.isNotEmpty() }
            ?: Regex("\"genres\":\\[([^\\]]+)\\]").find(html)?.let { m ->
                Regex("\"name\":\"([^\"]+)\"").findAll(m.value).map { it.groupValues[1] }.toList()
            }
            ?: emptyList()

        val voteAvg = detail?.opt("voteAverage").let { v ->
            when (v) {
                is Number -> v.toDouble()
                is String -> v.toDoubleOrNull()
                else -> null
            }
        } ?: Regex("\"voteAverage\":\"?([0-9.]+)\"?").find(html)?.groupValues?.getOrNull(1)?.toDoubleOrNull()
        val score = Score.from10(voteAvg)

        if (!isSeries) {
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = description
                this.tags = tags
                this.year = year
                this.score = score
            }
        }

        val episodeUrls = mutableListOf<Episode>()
        val seasons = mutableListOf<JSONObject>()
        detail?.optJSONObject("defaultSeason")?.let { seasons.add(it) }
        if (seasons.isEmpty()) {
            detail?.optJSONArray("seasons")?.let { arr ->
                for (i in 0 until arr.length()) {
                    arr.optJSONObject(i)?.let { seasons.add(it) }
                }
            }
        }
        for (season in seasons) {
            val seasonNum = season.optInt("seasonNumber", 1)
            val episodes = season.optJSONArray("episodes") ?: continue
            for (i in 0 until episodes.length()) {
                val ep = episodes.optJSONObject(i) ?: continue
                val epNum = ep.optInt("episodeNumber", 0)
                if (epNum <= 0) continue
                val epName = ep.optString("name", "").ifBlank {
                    ep.optString("title", "").ifBlank { "S${seasonNum}E$epNum" }
                }
                val epUrl = fixUrl("/series/$slug/season/$seasonNum/episode/$epNum")
                episodeUrls.add(newEpisode(epUrl) {
                    this.name = epName
                    this.episode = epNum
                    this.season = seasonNum
                })
            }
        }
        if (episodeUrls.isEmpty()) {
            Regex("""/series/[^/]+/season/(\d+)/episode/(\d+)""").findAll(html).forEach { m ->
                val seasonNum = m.groupValues[1].toIntOrNull() ?: 1
                val epNum = m.groupValues[2].toIntOrNull() ?: return@forEach
                episodeUrls.add(newEpisode(fixUrl(m.value)) {
                    this.name = "S${seasonNum}E$epNum"
                    this.episode = epNum
                    this.season = seasonNum
                })
            }
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodeUrls) {
            this.posterUrl = poster
            this.plot = description
            this.tags = tags
            this.year = year
            this.score = score
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (tryLoadFromApi(data, subtitleCallback, callback)) return true

        val resp = app.get(data, interceptor = cfKiller)
        val raw = resp.text ?: return true
        val html = raw.replace("\\\"", "\"")

        val sources = listOf(
            Regex("""sources":\[([^\]]+)\]"""),
            Regex("""playlist":\[([^\]]+)\]"""),
            Regex("""playerSources":\[([^\]]+)\]"""),
        )

        for (pattern in sources) {
            val match = pattern.find(html) ?: continue
            val content = match.groupValues[1]
            Regex("""https?://[^\s"',]+\.(?:m3u8|mp4)[^\s"',]*""").findAll(content).forEach { m ->
                callback(newExtractorLink("Idlix", "Idlix", m.value) {
                    this.referer = "$mainUrl/"
                })
            }
            Regex("""playbackUrl":"([^"]+)""").findAll(content).forEach { m ->
                val quality = Regex("""quality":"([^"]+)""").find(content)?.groupValues?.getOrNull(1) ?: "FHD"
                callback(newExtractorLink("Idlix", "Idlix - $quality", m.groupValues[1]) {
                    this.quality = parseQuality(quality)
                    this.referer = "$mainUrl/"
                })
            }
        }

        return true
    }

    private suspend fun tryLoadFromApi(
        data: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val slug: String
        val contentType: String
        val extra: Map<String, String>

        val episodeMatch = Regex("/series/([^/]+)/season/(\\d+)/episode/(\\d+)").find(data)
        if (episodeMatch != null) {
            slug = episodeMatch.groupValues[1]
            contentType = "tv_series"
            extra = mapOf("season" to episodeMatch.groupValues[2], "episode" to episodeMatch.groupValues[3])
        } else if (data.contains("/series/")) {
            slug = data.substringAfter("/series/").substringBefore("/").substringBefore("?")
            contentType = "tv_series"
            extra = emptyMap()
        } else {
            slug = data.substringAfter("/movie/").substringBefore("/").substringBefore("?")
            contentType = "movie"
            extra = emptyMap()
        }
        if (slug.isBlank()) return false

        val pageUrl = if (contentType == "movie") "$mainUrl/movie/$slug"
        else if (extra.containsKey("season")) "$mainUrl/series/$slug/season/${extra["season"]}/episode/${extra["episode"]}"
        else "$mainUrl/series/$slug"

        var cookieHeader = emptyMap<String, String>()
        try {
            val pageResp = app.get(pageUrl, interceptor = cfKiller)
            val setCookie = pageResp.headers["set-cookie"].orEmpty()
            val did = Regex("""\bdid=([^;]+)""").find(setCookie)?.groupValues?.getOrNull(1)
            if (!did.isNullOrBlank()) {
                cookieHeader = mapOf("Cookie" to "did=$did")
            }
        } catch (_: Exception) {}

        val contentId: String
        try {
            val apiPath = if (contentType == "movie") "api/movies/$slug"
            else if (extra.containsKey("season")) "api/series/$slug/season/${extra["season"]}/episode/${extra["episode"]}"
            else "api/series/$slug"
            val detailResp = app.get("$mainUrl/$apiPath", headers = apiHeaders + mapOf("Referer" to "$pageUrl/") + cookieHeader, interceptor = cfKiller)
            val detailJson = JSONObject(detailResp.text ?: return false)
            contentId = detailJson.optJSONObject("episode")?.optString("id", "")?.ifBlank { null }
                ?: detailJson.optString("id", "")
            if (contentId.isBlank()) return false
        } catch (e: Exception) {
            return false
        }

        var streamUrl: String? = null
        var subtitles: List<Pair<String, String>> = emptyList()
        try {
            val playInfoType = if (extra.containsKey("season")) "episode" else contentType
            val playInfoResp = app.get(
                "$mainUrl/api/watch/play-info/$playInfoType/$contentId",
                headers = apiHeaders + mapOf("Referer" to "$pageUrl/") + cookieHeader,
                interceptor = cfKiller
            )
            val playInfo = JSONObject(playInfoResp.text ?: return false)
            val gateToken = playInfo.optString("gateToken", "")
            if (gateToken.isBlank()) return false

            val kind = playInfo.optString("kind", "")
            if (kind == "gate") {
                val unlockAt = playInfo.optLong("unlockAt", 0L)
                val serverNow = playInfo.optLong("serverNow", 0L)
                val waitMs = if (unlockAt > 0 && serverNow > 0) unlockAt - serverNow else 15000L
                if (waitMs > 0) delay(waitMs + 2000)
            }

            var claimJson = postClaim(gateToken, pageUrl, cookieHeader)

            if (claimJson.optString("kind") == "pending") {
                val remainingMs = claimJson.optLong("remainingMs", 0)
                if (remainingMs > 0) delay(remainingMs + 2000)
                claimJson = postClaim(gateToken, pageUrl, cookieHeader)
            }

            val redeemUrl = claimJson.optString("redeemUrl", "")
            val claim = claimJson.optString("claim", "")
            val initialMaster = claimJson.optString("initialMasterUrl", "")

            if (redeemUrl.isNotBlank() && claim.isNotBlank()) {
                try {
                    val pentosResp = app.post(
                        redeemUrl,
                        headers = mapOf(
                            "Origin" to mainUrl,
                            "Referer" to "$mainUrl/",
                            "Content-Type" to "application/json"
                        ),
                        json = JsonAsString("""{"claim":"$claim"}""")
                    )
                    val pentosText = pentosResp.text ?: ""
                    val pentosJson = JSONObject(pentosText)
                    streamUrl = pentosJson.optString("url", "").ifBlank { null }
                    val subsArray = pentosJson.optJSONArray("subtitles")
                    subtitles = if (subsArray != null) {
                        (0 until subsArray.length()).mapNotNull { i ->
                            val sub = subsArray.optJSONObject(i) ?: return@mapNotNull null
                            val lang = sub.optString("label").ifBlank { sub.optString("lang", "") }
                            val path = sub.optString("path", "")
                            if (lang.isNotBlank() && path.isNotBlank()) Pair(lang, path) else null
                        }
                    } else emptyList()
                } catch (_: Exception) {}
                if (streamUrl != null) {
                    subtitles.forEach { (lang, path) ->
                        subtitleCallback(newSubtitleFile(lang, path))
                    }
                    callback(newExtractorLink("Idlix", "Idlix", streamUrl, type = ExtractorLinkType.M3U8) {
                        this.referer = "$mainUrl/"
                    })
                    return true
                }
            }

            if (initialMaster.isNotBlank()) {
                callback(newExtractorLink("Idlix", "Idlix", initialMaster, type = ExtractorLinkType.M3U8) {
                    this.referer = "$mainUrl/"
                })
                return true
            }
        } catch (e: Exception) {
            return false
        }

        return false
    }

    private suspend fun postClaim(gateToken: String, pageUrl: String, cookieHeader: Map<String, String>): JSONObject {
        val claimResp = app.post(
            "$mainUrl/api/watch/session/claim",
            headers = apiHeaders + mapOf(
                "Origin" to mainUrl,
                "Referer" to "$pageUrl/",
                "Content-Type" to "application/json"
            ) + cookieHeader,
            json = JsonAsString("""{"gateToken":"$gateToken"}"""),
            interceptor = cfKiller
        )
        return JSONObject(claimResp.text ?: "{}")
    }

    private fun parseQuality(q: String): Int {
        return when {
            q.contains("4K", true) || q.contains("2160", true) -> 4
            q.contains("1080", true) -> 3
            q.contains("720", true) -> 2
            q.contains("480", true) || q.contains("360", true) -> 1
            else -> 3
        }
    }

    private suspend fun fetchDetail(slug: String, isSeries: Boolean): JSONObject? {
        return try {
            val path = if (isSeries) "api/series/$slug" else "api/movies/$slug"
            val resp = app.get("$mainUrl/$path", headers = apiHeaders, interceptor = cfKiller)
            JSONObject(resp.text ?: return null)
        } catch (_: Exception) {
            null
        }
    }

    private fun metaContent(html: String, key: String): String? {
        Regex("""<meta[^>]+(?:property|name)="$key"[^>]+content="([^"]*)"[^>]*>""").find(html)
            ?.groupValues?.getOrNull(1)?.let { return it.ifBlank { null } }
        Regex("""<meta[^>]+content="([^"]*)"[^>]+(?:property|name)="$key"[^>]*>""").find(html)
            ?.groupValues?.getOrNull(1)?.let { return it.ifBlank { null } }
        return null
    }

    private fun cleanTitle(title: String): String {
        return title.removePrefix("Nonton Serial ")
            .removePrefix("Nonton ")
            .substringBefore(" / IDLIX")
            .trim()
    }

    private fun fixUrl(url: String): String {
        if (url.startsWith("http")) return url
        return "$mainUrl$url"
    }
}

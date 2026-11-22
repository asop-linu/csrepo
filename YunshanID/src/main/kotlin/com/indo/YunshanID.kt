package com.indo

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink

class YunshanID : MainAPI() {
    override var mainUrl = "https://yunshanid.site"
    override var name = "YunshanID"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie)

    override val mainPage = mainPageOf("$mainUrl/" to "Donghua Terbaru")

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36",
        "Referer" to "$mainUrl/"
    )

    private data class CatalogItem(
        val id: String,
        val title: String,
        val poster: String?,
        val latestEp: Int,
        val type: String
    )

    private fun unescape(value: String): String = value
        .replace("&quot;", "\"")
        .replace("&amp;", "&")
        .replace("\\n", "\n")
        .replace("\\\"", "\"")
        .replace("\\\\", "\\")

    private fun islandProps(html: String, component: String): String? {
        val regex = Regex(
            "<astro-island[^>]*component-url=\"[^\"]*$component[^\"]*\"[^>]*props=\"([^\"]+)\""
        )
        return regex.find(html)?.groupValues?.getOrNull(1)?.let(::unescape)
    }

    private val catalogRegex = Regex(
        "\\{\"id\":\\[0,(\\d+)\\],\"title\":\\[0,\"((?:\\\\.|[^\"])*)\"],\"poster_url\":\\[0,\"((?:\\\\.|[^\"])*)\"],\"latest_ep\":\\[0,(\\d+)\\],\"view_count\":\\[0,\\d+\\],\"status\":\\[0,\"[^\"]*\"],\"type\":\\[0,\"([^\"]+)\"]"
    )

    private fun parseCatalog(html: String): List<CatalogItem> {
        val props = islandProps(html, "HomeGrid") ?: return emptyList()
        return catalogRegex.findAll(props).map { match ->
            CatalogItem(
                id = match.groupValues[1],
                title = match.groupValues[2].trim(),
                poster = match.groupValues[3].ifBlank { null },
                latestEp = match.groupValues[4].toIntOrNull() ?: 0,
                type = match.groupValues[5]
            )
        }.distinctBy { it.id }.toList()
    }

    private fun toSearchResponse(item: CatalogItem): SearchResponse {
        val url = "$mainUrl/synopsis/${item.id}"
        val type = if (item.type.equals("movie", ignoreCase = true)) TvType.AnimeMovie else TvType.Anime
        return newAnimeSearchResponse(item.title, url, type) {
            this.posterUrl = item.poster
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val html = app.get(request.data, headers = headers).text
        val items = parseCatalog(html)
        val offset = (page - 1) * 24
        val paged = if (offset >= items.size) emptyList() else items.drop(offset).take(24)
        return newHomePageResponse(request.name, paged.map(::toSearchResponse))
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val html = app.get(mainUrl, headers = headers).text
        return parseCatalog(html)
            .filter { it.title.contains(query.trim(), ignoreCase = true) }
            .take(50)
            .map(::toSearchResponse)
    }

    override suspend fun load(url: String): LoadResponse {
        val id = Regex("/synopsis/(\\d+)").find(url)?.groupValues?.get(1)
            ?: throw ErrorLoadingException("Invalid YunshanID URL")
        val response = app.get(url, headers = headers)
        val html = response.text
        val props = islandProps(html, "SynopsisActions")
        val item = parseCatalog(app.get(mainUrl, headers = headers).text).firstOrNull { it.id == id }

        val title = Regex("\"title\":\\[0,\"((?:\\\\.|[^\"])*)\"\\]")
            .find(props ?: "")?.groupValues?.getOrNull(1)?.let(::unescape)?.trim()
            ?: item?.title
            ?: response.document.selectFirst("h1")?.text()?.trim()
            ?: throw ErrorLoadingException("Title not found")
        val poster = Regex("\"poster_url\":\\[0,\"((?:\\\\.|[^\"])*)\"\\]")
            .find(props ?: "")?.groupValues?.getOrNull(1)?.let(::unescape)?.ifBlank { null }
            ?: item?.poster
        val plot = response.document.selectFirst("#synopsis-full")?.text()?.trim()
            ?: response.document.selectFirst("meta[name=description]")?.attr("content")?.ifBlank { null }
        val isMovie = (item?.type ?: "").equals("movie", ignoreCase = true)

        val episodeProps = islandProps(html, "EpisodeGrid")
        val episodes = Regex("\\{\"id\":\\[0,(\\d+)\\],\"ep_number\":\\[0,(\\d+)\\]\\}")
            .findAll(episodeProps ?: "").map { match ->
                val number = match.groupValues[2].toIntOrNull() ?: 0
                newEpisode("$mainUrl/episode/$id/$number") {
                    this.name = "Episode $number"
                    this.episode = number
                }
            }.distinctBy { it.data }.sortedBy { it.episode }.toList()

        if (isMovie || episodes.size <= 1) {
            val watchUrl = "$mainUrl/episode/$id/${episodes.firstOrNull()?.episode ?: 1}"
            return newMovieLoadResponse(title, url, TvType.AnimeMovie, watchUrl) {
                this.posterUrl = poster
                this.plot = plot
            }
        }

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.posterUrl = poster
            this.backgroundPosterUrl = poster
            this.plot = plot
            addEpisodes(DubStatus.Subbed, episodes)
        }
    }

    private suspend fun extractDailymotion(videoId: String, callback: (ExtractorLink) -> Unit) {
        try {
            val metaText = app.get(
                "https://www.dailymotion.com/player/metadata/video/$videoId",
                headers = mapOf(
                    "User-Agent" to headers.getValue("User-Agent"),
                    "Referer" to "https://geo.dailymotion.com/"
                )
            ).text
            val m3u8 = Regex(""""type":\s*"application/x-mpegURL",\s*"url":\s*"([^"]+)"""")
                .find(metaText)?.groupValues?.getOrNull(1)
                ?: Regex(""""url":\s*"([^"]+\.m3u8[^"]*)"""")
                    .find(metaText)?.groupValues?.getOrNull(1)
                ?: return
            val links = M3u8Helper.generateM3u8(
                source = "Dailymotion",
                streamUrl = m3u8,
                referer = "https://www.dailymotion.com/",
                headers = mapOf("Referer" to "https://www.dailymotion.com/")
            )
            if (links.isNotEmpty()) {
                links.forEach(callback)
            } else {
                callback(newExtractorLink("Dailymotion", "Dailymotion", m3u8) {
                    this.referer = "https://www.dailymotion.com/"
                    this.type = ExtractorLinkType.M3U8
                })
            }
        } catch (_: Exception) { }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val html = app.get(data, headers = headers).text
        val props = islandProps(html, "WatchClient") ?: return false
        val serverRegex = Regex(
            "\\{\"id\":\\[0,\\d+\\],\"episode_id\":\\[0,\\d+\\],\"name\":\\[0,\"((?:\\\\.|[^\"])*)\"],\"embed_url\":\\[0,\"((?:\\\\.|[^\"])*)\"\\]}"
        )
        var found = false
        serverRegex.findAll(props).forEach { match ->
            val embed = fixUrl(unescape(match.groupValues[2]))
            val dmId = Regex("""(?:dailymotion\.com/(?:video/|player/[^?]+\.html\?video=)|dai\.ly/|geo\.dailymotion\.com/player/[^?]+\.html\?video=)([a-zA-Z0-9]+)""")
                .find(embed)?.groupValues?.getOrNull(1)
            if (dmId != null) {
                extractDailymotion(dmId, callback)
                found = true
            } else if (loadExtractor(embed, data, subtitleCallback, callback)) {
                found = true
            }
        }
        return found
    }
}

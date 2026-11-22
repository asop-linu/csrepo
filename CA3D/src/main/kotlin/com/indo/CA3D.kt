package com.indo

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.net.URLEncoder

class CA3D : MainAPI() {
    override var mainUrl = "https://ca3d.site"
    override var name = "CA3D"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie)

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Terbaru",
        "$mainUrl/category/series" to "Series",
        "$mainUrl/category/movie" to "Movie"
    )

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36",
        "Referer" to "$mainUrl/"
    )

    private data class CatalogItem(
        val id: String,
        val title: String,
        val isMovie: Boolean,
        val image: String?
    )

    private fun unescapeJs(value: String): String = value
        .replace("\\n", "\n")
        .replace("\\\"", "\"")
        .replace("\\\\", "\\")

    // Katalog CA3D disisipkan SvelteKit sebagai objek JS: {id:"...",title:"...",type:"...",image:"..."}
    private val catalogRegex = Regex(
        "\\{id:\"([^\"]+)\",title:\"((?:\\\\.|[^\"])*)\",type:\"([^\"]+)\",image:\"((?:\\\\.|[^\"])*)\""
    )
    private val episodeRegex = Regex("ep:\"(\\d+)\"")

    private fun parseCatalog(html: String): List<CatalogItem> = catalogRegex.findAll(html).map { match ->
        CatalogItem(
            id = match.groupValues[1],
            title = unescapeJs(match.groupValues[2]),
            isMovie = match.groupValues[3].equals("movie", ignoreCase = true),
            image = match.groupValues[4].ifBlank { null }
        )
    }.distinctBy { it.id }.toList()

    private fun toSearchResponse(item: CatalogItem): SearchResponse {
        val url = "$mainUrl/synopsis/${item.id}"
        val type = if (item.isMovie) TvType.AnimeMovie else TvType.Anime
        return newAnimeSearchResponse(item.title, url, type) {
            this.posterUrl = item.image
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val response = app.get(request.data, headers = headers)
        val items = parseCatalog(response.text)
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
            ?: throw ErrorLoadingException("Invalid CA3D URL")
        val response = app.get(url, headers = headers)
        val html = response.text
        val item = parseCatalog(html).firstOrNull { it.id == id }
            ?: parseCatalog(app.get(mainUrl, headers = headers).text).firstOrNull { it.id == id }

        val title = item?.title
            ?: response.document.selectFirst("h1")?.text()?.trim()
            ?: throw ErrorLoadingException("Title not found")
        val poster = item?.image
            ?: response.document.selectFirst("meta[property=og:image]")?.attr("content")?.ifBlank { null }
        val plot = response.document.selectFirst("section.panel div.panel-body p")?.text()?.trim()
            ?: response.document.selectFirst("meta[name=description]")?.attr("content")?.ifBlank { null }
        val isMovie = item?.isMovie ?: false

        val objectStart = html.indexOf("{id:\"$id\",title:")
        val segment = if (objectStart >= 0) {
            val next = html.indexOf("{id:\"", objectStart + 10)
            html.substring(objectStart, if (next > objectStart) next else html.length)
        } else html

        val episodes = episodeRegex.findAll(segment).map { match ->
            val number = match.groupValues[1].toIntOrNull() ?: 0
            newEpisode("$mainUrl/watch/$id/${match.groupValues[1]}") {
                this.name = "Episode ${match.groupValues[1]}"
                this.episode = number
            }
        }.distinctBy { it.data }.sortedBy { it.episode }.toList()

        if (isMovie || episodes.size <= 1) {
            val watchUrl = "$mainUrl/watch/$id/${episodes.firstOrNull()?.data?.substringAfterLast('/') ?: "01"}"
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
                    this.type = com.lagradost.cloudstream3.utils.ExtractorLinkType.M3U8
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
        val doc = app.get(data, headers = headers).document
        var found = false

        val iframes = doc.select(".player-frame iframe[src], iframe[src]")
            .mapNotNull { fixUrlNull(it.attr("src").ifBlank { null }) }
            .distinct()

        for (iframe in iframes) {
            if (iframe.contains("vidmingo", ignoreCase = true)) continue
            val dmId = Regex("""(?:dailymotion\.com/(?:video/|player/[^?]+\.html\?video=)|dai\.ly/|geo\.dailymotion\.com/player/[^?]+\.html\?video=)([a-zA-Z0-9]+)""")
                .find(iframe)?.groupValues?.getOrNull(1)
            if (dmId != null) {
                extractDailymotion(dmId, callback)
                found = true
                continue
            }
            if (loadExtractor(iframe, data, subtitleCallback, callback)) found = true
        }

        return found
    }
}

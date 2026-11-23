package com.indo

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.extractors.helper.AesHelper
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.nicehttp.RequestBodyTypes
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URI
import java.util.regex.PatternSyntaxException

class Kuronime : MainAPI() {
    override var mainUrl = "https://kuronime.sbs"
    override var name = "Kuronime"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    private var animekuUrl = "https://animeku.org"

    companion object {
        const val KEY = "3&!Z0M,VIZ;dZW=="
    }

    // Homepage with paginated new episodes
    override val mainPage = mainPageOf(
        "$mainUrl/page/" to "New Episodes"
    )

    private fun episodeToAnimeUrl(url: String): String {
        val path = url.trimEnd('/').substringAfterLast("/")
        val withoutPrefix = if (path.startsWith("nonton-")) path.removePrefix("nonton-") else path
        val slug = try {
            Regex("-episode-\\d+.*$", RegexOption.IGNORE_CASE).replace(withoutPrefix, "")
        } catch (e: PatternSyntaxException) {
            val idx = withoutPrefix.indexOf("-episode-", ignoreCase = true)
            if (idx != -1) withoutPrefix.substring(0, idx) else withoutPrefix
        }
        return "$mainUrl/anime/$slug/"
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(request.data + page).document

        // Items in bixbox > listupd > excstf > article.bsu
        val items = document.select("article.bsu").mapNotNull { article ->
            val bsux = article.selectFirst("div.bsux") ?: return@mapNotNull null
            val a = bsux.selectFirst("a[href]") ?: return@mapNotNull null
            val href = a.attr("href").ifBlank { null } ?: return@mapNotNull null

            // Title from div.bsuxtt > h2 (clean title without episode info)
            val title = bsux.selectFirst("div.bsuxtt h2")?.text()?.trim()
                ?: return@mapNotNull null

            // Poster from img[itemprop=image] inside div.limit
            val poster = a.selectFirst("div.limit img[itemprop=image]")?.attr("src")?.ifBlank { null }
                ?: a.selectFirst("div.limit img")?.attr("src")?.ifBlank { null }

            // Episode number from div.bt > div.ep
            val epText = a.selectFirst("div.bt div.ep")?.text()?.trim() ?: ""
            val epNum = Regex("(\\d+)").find(epText)?.groupValues?.getOrNull(1)?.toIntOrNull()

            val animeUrl = episodeToAnimeUrl(href)

            newAnimeSearchResponse(title, animeUrl, TvType.Anime) {
                this.posterUrl = poster
                addSub(epNum)
            }
        }.distinctBy { it.url }

        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/?s=$query").document
        return document.select("article.bs, article.bsu").mapNotNull { article ->
            val bsux = article.selectFirst("div.bsx, div.bsux") ?: article
            val a = bsux.selectFirst("a[href]") ?: return@mapNotNull null
            val href = a.attr("href").ifBlank { null } ?: return@mapNotNull null

            val title = a.attr("title").trim().ifBlank { null }
                ?: bsux.selectFirst("div.bsuxtt h2, div.tt h2, h2")?.text()?.trim()?.ifBlank { null }
                ?: return@mapNotNull null

            val poster = a.selectFirst("img[itemprop=image]")?.let { it.attr("src").ifBlank { it.attr("data-src") } }
                ?: a.selectFirst("img:not([src*=controls-play])")?.let { it.attr("src").ifBlank { it.attr("data-src") } }
                ?: a.selectFirst("div.limit img")?.attr("src")?.ifBlank { null }

            val epText = a.selectFirst("div.bt div.ep")?.text()?.trim() ?: ""
            val epNum = Regex("(\\d+)").find(epText)?.groupValues?.getOrNull(1)?.toIntOrNull()

            val animeUrl = if (href.contains("/anime/")) href else episodeToAnimeUrl(href)

            newAnimeSearchResponse(title, animeUrl, TvType.Anime) {
                this.posterUrl = poster
                addSub(epNum)
            }
        }.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val animeUrl = if (url.contains("/anime/")) url else episodeToAnimeUrl(url)
        val document = app.get(animeUrl).document

        val title = document.selectFirst("h1.entry-title")?.text()?.trim()
            ?: document.selectFirst("h1")?.text()?.trim()
            ?: throw ErrorLoadingException("Title not found")

        val cleanTitle = title
            .replace(Regex("\\s*Subtitle Indonesia.*", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s*Sub\\s*Indo.*", RegexOption.IGNORE_CASE), "")
            .trim()

        val poster = document.selectFirst("div.ime img, div.thumb img")?.let { img ->
            img.attr("data-src").ifBlank { null } ?: img.attr("src").ifBlank { null }
        } ?: document.selectFirst("meta[property=og:image]")?.attr("content")?.ifBlank { null }

        val description = document.selectFirst("div.synp p, div.entry-content p")?.text()?.trim()
            ?: document.selectFirst("meta[name=description]")?.attr("content")

        val genres = document.select("div.genxed a, a[href*=/genres/]")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }

        // Episodes: li elements containing a[href] with /nonton-...-episode-N/ URLs
        val episodes = document.select("div.bixbox ul li").mapNotNull { li ->
            val a = li.selectFirst("a[href*=nonton-]") ?: return@mapNotNull null
            val href = a.attr("href").ifBlank { null } ?: return@mapNotNull null
            val epText = a.text().trim().ifBlank { null } ?: return@mapNotNull null
            val epNum = Regex("(\\d+)").find(
                epText.replace(Regex("Episode\\s*", RegexOption.IGNORE_CASE), "")
            )?.groupValues?.getOrNull(1)?.toIntOrNull()
                ?: Regex("episode-(\\d+)", RegexOption.IGNORE_CASE).find(href)
                    ?.groupValues?.getOrNull(1)?.toIntOrNull()
            newEpisode(fixUrl(href)) {
                this.name = "Episode ${epNum ?: epText}"
                this.episode = epNum
            }
        }.distinctBy { it.data }.sortedBy { it.episode }

        return newAnimeLoadResponse(cleanTitle, animeUrl, TvType.Anime) {
            engName = cleanTitle
            posterUrl = poster
            backgroundPosterUrl = poster
            plot = description
            this.tags = genres.distinct()
            addEpisodes(DubStatus.Subbed, episodes)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val req = app.get(data)
        val document = req.document
        val currentBaseUrl = getBaseUrl(req.url)

        // Situs memuat player lewat API animeku; id episode disimpan di variabel script ini.
        val scriptData = document.select("script").map { it.data() }
            .firstOrNull { it.contains("_0xa100d42aa") }
            ?: return false

        val id = scriptData.substringAfter("_0xa100d42aa = \"").substringBefore("\";")
        if (id.isBlank()) return false

        val servers = app.post(
            "$animekuUrl/api/v9/sources",
            requestBody = """{"id":"$id"}""".toRequestBody(RequestBodyTypes.JSON.toMediaTypeOrNull()),
            referer = "$currentBaseUrl/"
        ).parsedSafe<Servers>() ?: return false

        var found = false

        // Sumber utama: HLS terenkripsi AES-CBC (passphrase KEY).
        try {
            val srcEnc = servers.src
            if (!srcEnc.isNullOrBlank()) {
                val decrypt = AesHelper.cryptoAESHandler(
                    base64Decode(srcEnc), KEY.toByteArray(), false, "AES/CBC/NoPadding"
                )
                val source = tryParseJson<Sources>(decrypt?.toJsonFormat())?.src?.replace("\\", "")
                if (!source.isNullOrBlank()) {
                    M3u8Helper.generateM3u8(
                        this.name, source, "$animekuUrl/", headers = mapOf("Origin" to animekuUrl)
                    ).forEach { callback(it); found = true }
                }
            }
        } catch (_: Exception) { }

        // Mirror: daftar embed per kualitas (doodstream, mp4upload, dll).
        try {
            val mirrorEnc = servers.mirror
            if (!mirrorEnc.isNullOrBlank()) {
                val decrypt = AesHelper.cryptoAESHandler(
                    base64Decode(mirrorEnc), KEY.toByteArray(), false, "AES/CBC/NoPadding"
                )
                tryParseJson<Mirrors>(decrypt)?.embed?.forEach { (quality, entries) ->
                    entries.forEach { (_, embed) ->
                        loadFixedExtractor(embed, quality.removePrefix("v"), "$currentBaseUrl/", subtitleCallback, callback)
                        found = true
                    }
                }
            }
        } catch (_: Exception) { }

        return found
    }

    private suspend fun loadFixedExtractor(
        url: String?,
        quality: String?,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        loadExtractor(url ?: return, referer, subtitleCallback) { link ->
            runBlocking {
                callback.invoke(
                    newExtractorLink(link.name, link.name, link.url, link.type) {
                        this.referer = link.referer
                        this.headers = link.headers
                        this.extractorData = link.extractorData
                        this.quality = getQualityFromName(quality)
                    }
                )
            }
        }
    }

    private fun String.toJsonFormat(): String {
        return if (this.startsWith("\"")) {
            this.substringAfter("\"").substringBeforeLast("\"").replace("\\\"", "\"")
        } else this
    }

    private fun getBaseUrl(url: String): String {
        return URI(url).let { "${it.scheme}://${it.host}" }
    }

    data class Servers(
        @JsonProperty("src") val src: String? = null,
        @JsonProperty("mirror") val mirror: String? = null,
    )

    data class Sources(
        @JsonProperty("src") var src: String? = null,
    )

    data class Mirrors(
        @JsonProperty("embed") val embed: Map<String, Map<String, String>> = emptyMap(),
    )
}

package com.indo

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.getAndUnpack
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Element
import java.net.URLEncoder

open class SontolFilmBase : MainAPI() {
    override var mainUrl = "https://sontolfilm.com"
    override var name = "SontolFilm"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie)

    open val dubOnly = false

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Film Terbaru",
        "$mainUrl/quality/dubbing/" to "Dubbing Indonesia"
    )

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36",
        "Referer" to "$mainUrl/"
    )

    private fun toSearchResponse(element: Element): SearchResponse? {
        val link = element.selectFirst("h2.entry-title a, a[itemprop=url], div.content-thumbnail a[href]")
            ?: return null
        val href = fixUrlNull(link.attr("href").ifBlank { null }) ?: return null
        if (!href.contains("sontolfilm.com") || href.contains("/page/")) return null
        if (dubOnly && element.selectFirst("div.gmr-quality-item.dubbing, a[href*='/quality/dubbing/']") == null) return null

        val title = link.text().trim().ifBlank { link.attr("title").removePrefix("Permalink to: ").trim() }
            .ifBlank { return null }
        val poster = element.selectFirst("img[itemprop=image], div.content-thumbnail img")
            ?.let { it.attr("src").ifBlank { it.attr("data-src") } }
            ?.ifBlank { null }

        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = poster
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val base = request.data.trimEnd('/')
        val url = if (page <= 1) "$base/" else "$base/page/$page/"
        val doc = app.get(url, headers = headers).document
        val items = doc.select("article.item, article.item-infinite").mapNotNull(::toSearchResponse)
        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = URLEncoder.encode(query.trim(), "UTF-8")
        val doc = app.get("$mainUrl/?s=$q", headers = headers).document
        return doc.select("article.item, article.item-infinite").mapNotNull(::toSearchResponse)
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, headers = headers).document
        val title = doc.selectFirst("h1.entry-title")?.text()?.trim()
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")
                ?.replace(Regex("\\s*-\\s*Sontol Film$", RegexOption.IGNORE_CASE), "")
            ?: throw ErrorLoadingException("Title not found")
        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")?.ifBlank { null }
            ?: doc.selectFirst("div.gmr-movie-data img")?.attr("src")?.ifBlank { null }
        val plot = doc.selectFirst("div.entry-content p")?.text()?.trim()
            ?: doc.selectFirst("meta[name=description]")?.attr("content")?.ifBlank { null }
        val year = doc.selectFirst("a[href*='/year/']")?.text()?.trim()?.toIntOrNull()
            ?: Regex("\\b(19\\d{2}|20\\d{2})\\b").find(title)?.groupValues?.get(1)?.toIntOrNull()
        val tags = doc.select("div.gmr-moviedata a[href*='/category/']")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
            .distinct()

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.plot = plot
            this.year = year
            this.tags = tags
        }
    }

    // Skrip player VidHide/Morencius dibungkus eval(packed array).
    // Unpacker ini mereproduksi fungsi standar P.A.C.K.E.R tanpa mengeksekusi JS.
    private fun unpackPackedJs(html: String): String? {
        val start = html.indexOf("eval(function(p,a,c,k,e,d)")
        if (start < 0) return null
        val regex = Regex(
            """\}\('((?:[^'\\]|\\.)*)',(\d+),(\d+),'((?:[^'\\]|\\.)*)'\.split\('\|'\)"""
        )
        val match = regex.find(html, start) ?: return null
        val packed = match.groupValues[1]
            .replace("\\'", "'")
            .replace("\\\"", "\"")
            .replace("\\\\", "\\")
            .replace("\\n", "\n")
        val radix = match.groupValues[2].toIntOrNull() ?: return null
        val count = match.groupValues[3].toIntOrNull() ?: return null
        val words = match.groupValues[4].split("|")
        var result = packed
        for (index in count - 1 downTo 0) {
            val word = words.getOrNull(index) ?: continue
            if (word.isEmpty()) continue
            val token = index.toString(radix)
            result = Regex("\\b$token\\b").replace(result) { word }
        }
        return result
    }

    private suspend fun extractMorencius(
        embedUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val html = app.get(embedUrl, headers = headers).text
        if (html.contains("embed restricted", ignoreCase = true)) return false
        val unpacked = unpackPackedJs(html) ?: getAndUnpack(html)

        Regex("""\{file:"([^"]+\.vtt)",label:"([^"]+)",kind:"captions"\}""")
            .findAll(unpacked).forEach { match ->
                subtitleCallback(newSubtitleFile(match.groupValues[2], match.groupValues[1]))
            }

        val hls = listOf("hls4", "hls3", "hls2")
            .firstNotNullOfOrNull { key ->
                Regex("\"$key\":\"(https?:[^\"]+?\\.m3u8[^\"]*)\"").find(unpacked)?.groupValues?.getOrNull(1)
            } ?: return false

        callback(newExtractorLink("Morencius", "Morencius HLS", hls) {
            this.referer = "https://morencius.com/"
            this.headers = mapOf(
                "Referer" to "https://morencius.com/",
                "Origin" to "https://morencius.com"
            )
            this.type = ExtractorLinkType.M3U8
        })
        return true
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data, headers = headers).document
        val postId = doc.selectFirst(".muvipro_player_content[data-id]")?.attr("data-id")?.ifBlank { null }
            ?: return false

        val playerHtml = app.post(
            "$mainUrl/wp-admin/admin-ajax.php",
            headers = headers + mapOf(
                "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
                "Referer" to data
            ),
            data = mapOf(
                "action" to "muvipro_player_content",
                "tab" to "p1",
                "post_id" to postId
            )
        ).text

        val embed = Regex("""<IFRAME[^>]+SRC=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            .find(playerHtml)?.groupValues?.getOrNull(1)
            ?.let { fixUrlNull(it) }
            ?: return false

        return if (embed.contains("morencius", ignoreCase = true)) {
            extractMorencius(embed, subtitleCallback, callback)
        } else {
            loadExtractor(embed, data, subtitleCallback, callback)
        }
    }
}

class SontolFilm : SontolFilmBase()

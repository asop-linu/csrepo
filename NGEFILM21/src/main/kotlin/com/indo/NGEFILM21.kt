package com.indo

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.nicehttp.JsonAsString
import org.json.JSONObject
import org.jsoup.nodes.Element
import java.net.URLEncoder

open class MuviproBase : MainAPI() {
    override var mainUrl = "https://new39.ngefilm.site"
    override var name = "NGEFILM21"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie)

    private val siteUrl = "https://new39.ngefilm.site"

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Film Terbaru",
        "$mainUrl/country/indonesia/" to "Indonesia",
        "$mainUrl/country/korea/" to "Korea"
    )

    private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36"

    private fun headers(ref: String = "$siteUrl/") = mapOf(
        "User-Agent" to userAgent,
        "Referer" to ref
    )

    private fun isArticle(el: Element): Boolean =
        el.selectFirst("h2.entry-title a, a.gmr-slide-titlelink, a[itemprop=url], div.gmr-item-modulepost a[href]") != null

    private fun toSearchResponse(el: Element): SearchResponse? {
        val link = el.selectFirst("h2.entry-title a, a.gmr-slide-titlelink, a[itemprop=url], div.gmr-item-modulepost a[href]")
            ?: return null
        val href = fixUrlNull(link.attr("href").ifBlank { null }) ?: return null
        if (href.contains("/page/") || href.contains("/category/") || href.contains("/year/") ||
            href.contains("/quality/") || href.contains("/country/") || href.contains("/tag/") ||
            href.contains("wp-content") || href.contains("/feed") || href.contains("/author/")) return null

        val title = el.selectFirst("h2.entry-title")?.text()?.trim()
            ?.ifBlank { null }
            ?: el.selectFirst("div.gmr-slide-title a")?.text()?.trim()?.ifBlank { null }
            ?: link.text().trim().ifBlank { null }
            ?: link.attr("title").removePrefix("Permalink to: ").removePrefix("Permalink ke: ").trim().ifBlank { null }
            ?: return null

        val poster = el.selectFirst("img[data-src], img[data-lazy-src], img[src]")?.let { img ->
            img.attr("data-src").ifBlank { img.attr("data-lazy-src") }.ifBlank { img.attr("src") }
        }?.ifBlank { null }

        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = poster
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val base = request.data.trimEnd('/')
        val url = if (page <= 1) "$base/" else "$base/page/$page/"
        val doc = app.get(url, headers = headers()).document
        val items = doc.select("article.item, article.item-infinite, div.gmr-item-modulepost, div.gmr-slider-content")
            .filter(::isArticle)
            .mapNotNull(::toSearchResponse)
            .distinctBy { it.url }
        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = URLEncoder.encode(query.trim(), "UTF-8")
        val doc = app.get("$siteUrl/?s=$q", headers = headers()).document
        return doc.select("article.item, article.item-infinite, div.gmr-item-modulepost, div.gmr-slider-content")
            .filter(::isArticle)
            .mapNotNull(::toSearchResponse)
            .distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, headers = headers(url)).document
        val title = doc.selectFirst("h1.entry-title")?.text()?.trim()?.ifBlank { null }
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()?.ifBlank { null }
            ?: throw ErrorLoadingException("Title not found")
        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")?.ifBlank { null }
            ?: doc.selectFirst("div.gmr-movie-data img[data-src], div.gmr-movie-data img[src]")?.let { img ->
                img.attr("data-src").ifBlank { img.attr("src") }
            }?.ifBlank { null }
        val plot = doc.selectFirst("div.entry-content > p")?.text()?.trim()?.ifBlank { null }
            ?: doc.selectFirst("meta[name=description]")?.attr("content")?.ifBlank { null }
        val year = doc.selectFirst("a[href*='/year/']")?.text()?.trim()?.toIntOrNull()
            ?: Regex("\\b(19\\d{2}|20\\d{2})\\b").find(title)?.groupValues?.get(1)?.toIntOrNull()
        val tags = (doc.select("div.gmr-moviedata a[href*='/category/'], div.gmr-movie-data a[href*='/category/']")
            .map { it.text().trim() } + doc.select("a[href*='/country/']").map { it.text().trim() })
            .filter { it.isNotBlank() }
            .distinct()
            .take(12)

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.plot = plot
            this.year = year
            this.tags = tags
        }
    }

    // P.A.C.K.E.R unpacker (deterministik, tanpa mengeksekusi JS).
    private fun unpackPackedJs(html: String): String? {
        val start = html.indexOf("eval(function(p,a,c,k,e,d)")
        if (start < 0) return null
        val match = Regex(
            """\}\('((?:[^'\\]|\\.)*)',(\d+),(\d+),'((?:[^'\\]|\\.)*)'\.split\('\|'\)"""
        ).find(html, start) ?: return null
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
            result = Regex("\\b${index.toString(radix)}\\b").replace(result) { word }
        }
        return result
    }

    private suspend fun extractAbyss(
        embedUrl: String,
        pageUrl: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val html = app.get(embedUrl, headers = headers(pageUrl)).text
        val enc = Regex("""datas\s*=\s*"([^"]+)"""").find(html)?.groupValues?.getOrNull(1) ?: return false
        val response = app.post(
            "https://enc-dec.app/api/dec-abyss",
            headers = mapOf(
                "User-Agent" to userAgent,
                "Content-Type" to "application/json",
                "Origin" to "https://abyssplayer.com",
                "Referer" to "https://abyssplayer.com/"
            ),
            json = JsonAsString(JSONObject().put("text", enc).toString())
        ).text
        val sources = JSONObject(response).optJSONObject("result")?.optJSONArray("sources") ?: return false
        var found = false
        for (i in 0 until sources.length()) {
            val item = sources.optJSONObject(i) ?: continue
            val url = item.optString("url")
            if (!url.startsWith("http")) continue
            val type = item.optString("type")
            val quality = when {
                type.contains("2160") || type.contains("4k") -> Qualities.P1080.value
                type.contains("1080") -> Qualities.P1080.value
                type.contains("720") -> Qualities.P720.value
                type.contains("480") -> Qualities.P480.value
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

    private suspend fun extractStreamHg(
        embedUrl: String,
        pageUrl: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val first = app.get(embedUrl, headers = headers(pageUrl)).text
        val id = embedUrl.substringAfterLast("/").substringBefore("?")
        val target = if (embedUrl.contains("hgcloud") && first.length < 3000) {
            "https://audinifer.com/e/$id"
        } else embedUrl
        val html = if (target == embedUrl) first else app.get(target, headers = headers(embedUrl)).text
        val unpacked = unpackPackedJs(html) ?: return false
        val raw = listOf("hls4", "hls3", "hls2").firstNotNullOfOrNull { key ->
            Regex("""["']$key["']\s*:\s*["']([^"']+)["']""").find(unpacked)?.groupValues?.getOrNull(1)
        } ?: return false
        val hls = if (raw.startsWith("http")) raw else {
            "https://" + target.substringAfter("://").substringBefore("/") + raw
        }
        callback(newExtractorLink("StreamHG", "StreamHG HLS", hls) {
            this.referer = target
            this.headers = mapOf("Referer" to target)
            this.type = ExtractorLinkType.M3U8
        })
        return true
    }

    private suspend fun extractStreamOrigin(
        embedUrl: String,
        pageUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val id = embedUrl.substringAfterLast("/").substringBefore("?")
        val api = "https://embedorigin.cc/app/v1/play/$id?embed=1"
        val json = JSONObject(app.get(api, headers = headers(pageUrl)).text)
        val origin = "https://embedorigin.cc"
        var found = false
        val hls = json.optString("hls")
        if (hls.startsWith("http")) {
            callback(newExtractorLink("Stream Origin", "Stream Origin HLS", hls) {
                this.referer = origin
                this.headers = mapOf("Referer" to origin)
                this.type = ExtractorLinkType.M3U8
            })
            found = true
        }
        val mp4 = json.optString("mp4")
        if (mp4.startsWith("http")) {
            callback(newExtractorLink("Stream Origin", "Stream Origin", mp4, type = ExtractorLinkType.VIDEO) {
                this.referer = origin
                this.headers = mapOf("Referer" to origin)
            })
            found = true
        }
        val subs = json.optJSONArray("subtitles")
        if (subs != null) {
            for (i in 0 until subs.length()) {
                val sub = subs.optJSONObject(i) ?: continue
                val rawSub = sub.optString("url").ifBlank { null } ?: continue
                val subUrl = if (rawSub.startsWith("http")) rawSub else "$origin$rawSub"
                subtitleCallback(newSubtitleFile(
                    sub.optString("label").ifBlank { "Indonesia" },
                    subUrl
                ))
            }
        }
        return found
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data, headers = headers(data)).document
        val embeds = mutableListOf<String>()

        doc.selectFirst(".muvipro_player_content[data-id]")?.attr("data-id")?.let { postId ->
            val host = "https://" + data.substringAfter("://").substringBefore("/")
            val ajax = app.post(
                "$host/wp-admin/admin-ajax.php",
                headers = headers(data) + mapOf(
                    "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
                    "X-Requested-With" to "XMLHttpRequest",
                    "Origin" to host
                ),
                data = mapOf(
                    "action" to "muvipro_player_content",
                    "tab" to "p1",
                    "post_id" to postId
                )
            ).text
            Regex("""<iframe[^>]+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                .findAll(ajax)
                .forEach { match -> fixUrlNull(match.groupValues[1])?.let { embeds.add(it) } }
        }

        doc.select("iframe[src]").forEach { iframe ->
            val src = iframe.attr("src")
            if (src.startsWith("http") && !src.contains("googletagmanager") && !src.contains("facebook")) {
                embeds.add(src)
            }
        }

        var found = false
        for (embed in embeds.distinct()) {
            val ok = try {
                when {
                    embed.contains("abyssplayer") || embed.contains("playhydrax") ||
                        embed.contains("abysscdn") -> extractAbyss(embed, data, callback)
                    embed.contains("hgcloud") || embed.contains("audinifer") ||
                        embed.contains("streamhg") -> extractStreamHg(embed, data, callback)
                    embed.contains("embedorigin") || embed.contains("streamorigin") ->
                        extractStreamOrigin(embed, data, subtitleCallback, callback)
                    else -> loadExtractor(embed, data, subtitleCallback, callback)
                }
            } catch (_: Exception) {
                false
            }
            if (ok) found = true
        }
        return found
    }
}

class NGEFILM21 : MuviproBase()

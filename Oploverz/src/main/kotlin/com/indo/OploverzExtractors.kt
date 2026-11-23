package com.indo

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.getAndUnpack
import com.lagradost.cloudstream3.utils.getPacked

/**
 * UpBolt (upbolt.to) — player XFileSharing. Halaman embed dibungkus Cloudflare,
 * tetapi endpoint /dl (op=embed&file_code=...) tetap bisa diakses langsung dan
 * mengembalikan sources HLS + subtitle .vtt.
 */
open class UpBolt : ExtractorApi() {
    override val name = "UpBolt"
    override val mainUrl = "https://upbolt.to"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val id = Regex("""/(?:e|d|f)/([a-zA-Z0-9]+)""").find(url)?.groupValues?.getOrNull(1)
            ?: url.substringAfterLast('/').substringBefore('?').substringBefore('.')
        if (id.isBlank()) return

        val response = app.post(
            "$mainUrl/dl",
            data = mapOf(
                "op" to "embed",
                "file_code" to id,
                "auto" to "1",
                "referer" to ""
            ),
            referer = referer
        )
        val script = if (!getPacked(response.text).isNullOrEmpty()) {
            getAndUnpack(response.text)
        } else {
            response.text
        }

        Regex("""(https?://[^"'\s]+\.vtt)""").findAll(script).forEach { match ->
            subtitleCallback(newSubtitleFile("Subtitle", match.groupValues[1]))
        }

        val m3u8 = Regex("""file:\s*"(.*?m3u8.*?)"""").find(script)?.groupValues?.getOrNull(1) ?: return
        M3u8Helper.generateM3u8(name, m3u8, "$mainUrl/").forEach(callback)
    }
}
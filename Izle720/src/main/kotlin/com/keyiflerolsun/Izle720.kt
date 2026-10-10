package com.keyiflerolsun

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import org.jsoup.nodes.Element
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class Izle720 : MainAPI() {
    override var mainUrl              = "https://720izle.net"
    override var name                 = "720izle"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = true
    override val supportedTypes       = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "${mainUrl}/filmler11/page/SAYFA/"                  to "Son Filmler",
        "${mainUrl}/tur/turkce-dublaj-filmler/page/SAYFA/"   to "Türkçe Dublaj",
        "${mainUrl}/tur/turkce-altyazili-filmler/page/SAYFA/" to "Türkçe Altyazılı",
        "${mainUrl}/tur/yerli-filmler/page/SAYFA/"           to "Yerli Filmler",
        "${mainUrl}/diziler2/page/SAYFA/"                    to "Diziler"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) {
            request.data.replace("/page/SAYFA/", "/")
        } else {
            request.data.replace("SAYFA", "$page")
        }

        val document = app.get(url).document
        val home = document.select("div.movie-preview-content").mapNotNull {
            it.toSearchResult()
        }

        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val aEl = this.selectFirst("div.movie-poster a") ?: this.selectFirst("a") ?: return null
        val href = fixUrlNull(aEl.attr("href")) ?: return null
        val imgEl = this.selectFirst("img")
        val title = imgEl?.attr("alt")?.trim() ?: aEl.text().trim()
        if (title.isBlank()) return null

        val poster = fixUrlNull(
            imgEl?.attr("data-src")?.takeIf { it.isNotBlank() }
                ?: imgEl?.attr("src")?.takeIf { !it.startsWith("data:") }
        )

        val isTv = href.contains("/diziler2/") || href.contains("/dizi/")
        val tvType = if (isTv) TvType.TvSeries else TvType.Movie

        return if (isTv) {
            newTvSeriesSearchResponse(title, href, tvType) {
                this.posterUrl = poster
            }
        } else {
            newMovieSearchResponse(title, href, tvType) {
                this.posterUrl = poster
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/?s=${query.trim()}"
        val document = app.get(searchUrl).document
        return document.select("div.movie-preview-content").mapNotNull {
            it.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title = document.selectFirst("h1.entry-title, h1, .single-header h1")?.text()?.trim()
            ?: return null
        val poster = fixUrlNull(
            document.selectFirst("div.movie-poster img, .single-poster img")?.let {
                it.attr("data-src").takeIf { s -> s.isNotBlank() } ?: it.attr("src")
            }
        )

        val plot = document.selectFirst("div.entry-content, div.movie-excerpt, p.description")?.text()?.trim()
        val year = document.selectFirst("span.year, a[href*='/yil/']")?.text()?.filter { it.isDigit() }?.toIntOrNull()
        val rating = document.selectFirst("span.imdb-rating, .rating-star b, span.rating")?.text()?.trim()

        val isTv = url.contains("/diziler2/") || url.contains("/dizi/")

        if (isTv) {
            val episodes = mutableListOf<Episode>()
            val epLinks = document.select("div.episodes a, div.keremiya_part a, .part-list a")
            if (epLinks.isNotEmpty()) {
                epLinks.forEachIndexed { index, epEl ->
                    val epHref = fixUrlNull(epEl.attr("href")) ?: return@forEachIndexed
                    val epName = epEl.text().trim().takeIf { it.isNotBlank() } ?: "${index + 1}. Bölüm"
                    val epNum = Regex("""\d+""").find(epName)?.value?.toIntOrNull() ?: (index + 1)
                    episodes.add(
                        newEpisode(epHref) {
                            this.name = epName
                            this.episode = epNum
                            this.season = 1
                        }
                    )
                }
            } else {
                episodes.add(newEpisode(url) {
                    this.name = "1. Bölüm"
                    this.episode = 1
                    this.season = 1
                })
            }

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot      = plot
                this.year      = year
                this.score     = Score.from10(rating)
            }
        }

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.plot      = plot
            this.year      = year
            this.score     = Score.from10(rating)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document

        // Collect all parts (Dublaj / Altyazılı)
        val pageUrls = mutableListOf(data)
        document.select("div.parts-middle a.post-page-numbers, div.part-links a").forEach {
            val h = fixUrlNull(it.attr("href"))
            if (h != null && !pageUrls.contains(h)) {
                pageUrls.add(h)
            }
        }

        var foundAny = false

        for (pageUrl in pageUrls) {
            val pageDoc = if (pageUrl == data) document else app.get(pageUrl).document
            val iframeEl = pageDoc.selectFirst("div.video-content iframe, iframe[data-litespeed-src], iframe[src]")
            val src = iframeEl?.let {
                it.attr("data-litespeed-src").takeIf { s -> s.isNotBlank() }
                    ?: it.attr("src").takeIf { s -> s.isNotBlank() && !s.startsWith("about:") }
            } ?: continue

            val cleanSrc = fixUrl(src)

            if (cleanSrc.contains("hotstream.club/embed/")) {
                if (extractHotstream(cleanSrc, data, callback)) {
                    foundAny = true
                }
            } else {
                loadExtractor(cleanSrc, data, subtitleCallback, callback)
                foundAny = true
            }
        }

        return foundAny
    }

    private suspend fun extractHotstream(
        embedUrl: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            val html = app.get(embedUrl, referer = referer).text
            val match = Regex("""bePlayer\(\s*'([^']+)'\s*,\s*'(\{.+?\})'\s*\)""").find(html) ?: return false

            val passphrase = match.groupValues[1]
            val jsonPayload = match.groupValues[2].replace("\\/", "/")
            val payloadMap = tryParseJson<Map<String, Any>>(jsonPayload) ?: return false

            val saltHex = payloadMap["s"] as? String ?: return false
            val ivHex = payloadMap["iv"] as? String ?: return false
            val ctB64 = payloadMap["ct"] as? String ?: return false

            val decryptedJson = decryptAes(passphrase, saltHex, ivHex, ctB64) ?: return false
            val decryptedData = tryParseJson<Map<String, Any>>(decryptedJson) ?: return false

            val videoLocation = decryptedData["video_location"] as? String ?: return false

            val masterM3u8 = app.get(videoLocation, referer = "https://hotstream.club/").text
            if (masterM3u8.contains("#EXTM3U")) {
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name   = "$name Hotstream",
                        url    = videoLocation,
                        type   = ExtractorLinkType.M3U8
                    ) {
                        this.headers = mapOf("Referer" to "https://hotstream.club/")
                        this.quality = Qualities.P1080.value
                    }
                )
                return true
            }
        } catch (_: Exception) {}
        return false
    }

    private fun decryptAes(passphrase: String, saltHex: String, ivHex: String, ctB64: String): String? {
        return try {
            val salt = saltHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            val iv = ivHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            val ct = Base64.decode(ctB64, Base64.DEFAULT)

            val passwordBytes = passphrase.toByteArray(Charsets.UTF_8)
            val md = MessageDigest.getInstance("MD5")
            val d = ByteArray(48)
            var dI = ByteArray(0)
            var offset = 0
            while (offset < 48) {
                md.reset()
                md.update(dI)
                md.update(passwordBytes)
                md.update(salt)
                dI = md.digest()
                val toCopy = minOf(dI.size, 48 - offset)
                System.arraycopy(dI, 0, d, offset, toCopy)
                offset += toCopy
            }
            val key = d.copyOfRange(0, 32)
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            val decrypted = cipher.doFinal(ct)
            String(decrypted, Charsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }
}

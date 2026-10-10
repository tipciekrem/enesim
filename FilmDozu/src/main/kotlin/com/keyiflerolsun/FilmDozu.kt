package com.keyiflerolsun

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import org.jsoup.nodes.Element

class FilmDozu : MainAPI() {
    override var mainUrl              = "https://filmdozu.com"
    override var name                 = "FilmDozu"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = true
    override val supportedTypes       = setOf(TvType.Movie)

    override val mainPage = mainPageOf(
        "${mainUrl}/kesfet/page/SAYFA/"                to "Keşfet",
        "${mainUrl}/trend/page/SAYFA/"                 to "Trend Filmler",
        "${mainUrl}/tur/turkce-dublaj/page/SAYFA/"     to "Türkçe Dublaj",
        "${mainUrl}/tur/turkce-altyazi/page/SAYFA/"    to "Türkçe Altyazılı",
        "${mainUrl}/kategori/aksiyon/page/SAYFA/"      to "Aksiyon",
        "${mainUrl}/kategori/bilim-kurgu/page/SAYFA/"  to "Bilim Kurgu",
        "${mainUrl}/kategori/komedi/page/SAYFA/"       to "Komedi",
        "${mainUrl}/kategori/gerilim/page/SAYFA/"      to "Gerilim",
        "${mainUrl}/kategori/korku/page/SAYFA/"        to "Korku",
        "${mainUrl}/kategori/animasyon/page/SAYFA/"    to "Animasyon"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) {
            request.data.replace("/page/SAYFA/", "/")
        } else {
            request.data.replace("SAYFA", "$page")
        }

        val document = app.get(url).document
        val home = document.select("article, .film-kutu, .movie-item, div.poster-item, div.item").mapNotNull {
            it.toSearchResult()
        }

        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val aEl = this.selectFirst("a[href*='/film/']") ?: this.selectFirst("a") ?: return null
        val href = fixUrlNull(aEl.attr("href")) ?: return null
        if (!href.contains("/film/")) return null

        val imgEl = this.selectFirst("img")
        val title = (imgEl?.attr("alt")?.takeIf { it.isNotBlank() }
            ?: this.selectFirst("h2, h3, .title, .film-title")?.text()?.takeIf { it.isNotBlank() }
            ?: aEl.attr("title")?.takeIf { it.isNotBlank() }
            ?: aEl.text()).trim()

        if (title.isBlank()) return null

        val poster = fixUrlNull(
            imgEl?.attr("data-src")?.takeIf { it.isNotBlank() }
                ?: imgEl?.attr("src")?.takeIf { !it.startsWith("data:") }
        )

        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = poster
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/ara?q=${query.trim()}"
        val document = app.get(searchUrl).document
        return document.select("article, .film-kutu, .movie-item, div.poster-item, div.item, a[href*='/film/']").mapNotNull {
            it.toSearchResult()
        }.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        val rawTitle = document.selectFirst("h1, .film-title, .title")?.text()?.trim()
            ?: document.title().substringBefore(" izle").trim()

        val title = rawTitle.substringBefore(" izle").trim()

        val poster = fixUrlNull(
            document.selectFirst("meta[property='og:image']")?.attr("content")
                ?: document.selectFirst("img.poster, div.poster img")?.attr("src")
        )

        val plot = document.selectFirst("meta[property='og:description']")?.attr("content")
            ?: document.selectFirst("div.film-plot, div.overview, p.description")?.text()?.trim()

        val year = Regex("""\b(19\d\d|20\d\d)\b""").find(document.title() + " " + rawTitle)?.value?.toIntOrNull()
        val tags = document.select("a[href*='/kategori/']").map { it.text().trim() }.distinct()

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.plot      = plot
            this.year      = year
            this.tags      = tags
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        val html     = document.html()

        val embedUrls = mutableSetOf<String>()

        // 1. Iframes (data-src and src)
        document.select("iframe").forEach {
            val src = it.attr("data-src").ifBlank { it.attr("src") }
            if (src.isNotBlank() && !src.startsWith("about:") && !src.contains("youtube.com")) {
                embedUrls.add(fixUrl(src))
            }
        }

        // 2. Buttons / tabs data-src
        document.select("[data-src], [data-video], [data-embed], [data-url]").forEach {
            val src = it.attr("data-src").ifBlank {
                it.attr("data-video").ifBlank {
                    it.attr("data-embed").ifBlank { it.attr("data-url") }
                }
            }
            if (src.isNotBlank() && !src.startsWith("about:") && !src.contains("youtube.com")) {
                embedUrls.add(fixUrl(src))
            }
        }

        // 3. Regex matches for vidmoly and ok.ru in full html
        val regex = Regex("""https?:[\\/]+(?:vidmoly\.[a-z]+|ok\.ru)[\\/][a-zA-Z0-9_\-\.\?&=;]+""")
        regex.findAll(html).forEach {
            val cleaned = it.value.replace("\\/", "/").replace("&amp;", "&")
            embedUrls.add(cleaned)
        }

        var foundAny = false

        for (embed in embedUrls) {
            when {
                embed.contains("vidmoly") -> {
                    if (extractVidmoly(embed, data, callback)) {
                        foundAny = true
                    }
                }
                embed.contains("ok.ru") -> {
                    if (extractOkRu(embed, callback)) {
                        foundAny = true
                    }
                }
                else -> {
                    try {
                        if (loadExtractor(embed, "$mainUrl/", subtitleCallback, callback)) {
                            foundAny = true
                        }
                    } catch (_: Exception) {}
                }
            }
        }

        return foundAny
    }

    private suspend fun extractVidmoly(
        embedUrl: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
                "Referer"    to referer
            )
            val html = app.get(embedUrl, headers = headers).text
            val m3uLink = Regex("""file\s*:\s*["']([^"']+\.m3u8[^"']*)["']""").find(html)?.groupValues?.get(1)
                ?: Regex("""["'](https?:[\\/]+[^"']+\.m3u8[^"']*)["']""").find(html)?.groupValues?.get(1)?.replace("\\/", "/")

            if (m3uLink != null) {
                callback.invoke(
                    newExtractorLink(
                        source  = name,
                        name    = "VidMoly 1080p",
                        url     = m3uLink,
                        type    = ExtractorLinkType.M3U8
                    ) {
                        this.headers = mapOf("Referer" to embedUrl)
                        this.quality = Qualities.P1080.value
                    }
                )
                true
            } else {
                false
            }
        } catch (_: Exception) {
            false
        }
    }

    private suspend fun extractOkRu(
        embedUrl: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val html = app.get(embedUrl).text
            val optionsMatch = Regex("""data-options="([^"]+)"""").find(html)?.groupValues?.get(1) ?: return false
            val unescaped = org.jsoup.parser.Parser.unescapeEntities(optionsMatch, false)
            val root = tryParseJson<Map<String, Any>>(unescaped) ?: return false
            val flashvars = root["flashvars"] as? Map<*, *>
            val metadataRaw = flashvars?.get("metadata")
            val metadata = when (metadataRaw) {
                is String -> tryParseJson<Map<String, Any>>(metadataRaw)
                is Map<*, *> -> metadataRaw as? Map<String, Any>
                else -> null
            } ?: return false

            var found = false
            val videos = metadata["videos"] as? List<Map<String, Any>>
            videos?.forEach { v ->
                val qName = v["name"] as? String ?: "SD"
                val vUrl = v["url"] as? String ?: return@forEach
                val quality = when (qName.lowercase()) {
                    "full", "1080" -> Qualities.P1080.value
                    "hd", "720"    -> Qualities.P720.value
                    "sd", "480"    -> Qualities.P480.value
                    else           -> Qualities.Unknown.value
                }
                callback.invoke(
                    newExtractorLink(
                        source  = name,
                        name    = "Ok.ru $qName",
                        url     = vUrl,
                        type    = ExtractorLinkType.VIDEO
                    ) {
                        this.quality = quality
                    }
                )
                found = true
            }

            val hlsUrl = metadata["hlsManifestUrl"] as? String
            if (hlsUrl != null) {
                callback.invoke(
                    newExtractorLink(
                        source  = name,
                        name    = "Ok.ru HLS",
                        url     = hlsUrl,
                        type    = ExtractorLinkType.M3U8
                    ) {
                        this.quality = Qualities.P1080.value
                    }
                )
                found = true
            }

            found
        } catch (_: Exception) {
            false
        }
    }
}

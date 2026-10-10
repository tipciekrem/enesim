package com.keyiflerolsun

import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class FilmDozu : MainAPI() {
    override var mainUrl              = "https://filmdozu.com"
    override var name                 = "FilmDozu"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = false
    override val supportedTypes       = setOf(TvType.Movie)

    override val mainPage = mainPageOf(
        "${mainUrl}/kesfet"          to "Keşfet (Son Eklenenler)",
        "${mainUrl}/trend"           to "Trendler",
        "${mainUrl}/dublaj"          to "Türkçe Dublaj",
        "${mainUrl}/altyazili"       to "Türkçe Altyazılı",
        "${mainUrl}/kategori/aksiyon"      to "Aksiyon",
        "${mainUrl}/kategori/animasyon"    to "Animasyon",
        "${mainUrl}/kategori/bilim-kurgu"  to "Bilim Kurgu",
        "${mainUrl}/kategori/dram"         to "Dram",
        "${mainUrl}/kategori/komedi"       to "Komedi",
        "${mainUrl}/kategori/korku"        to "Korku",
        "${mainUrl}/kategori/macera"       to "Macera",
        "${mainUrl}/kategori/gerilim"      to "Gerilim",
        "${mainUrl}/kategori/romantik"     to "Romantik",
        "${mainUrl}/kategori/suc"          to "Suç",
        "${mainUrl}/kategori/fantastik"    to "Fantastik",
        "${mainUrl}/kategori/gizem"        to "Gizem",
        "${mainUrl}/kategori/aile"         to "Aile",
        "${mainUrl}/kategori/savas"        to "Savaş",
        "${mainUrl}/kategori/tarih"        to "Tarih"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val targetUrl = if (page > 1) "${request.data}?page=$page" else request.data
        val document  = app.get(targetUrl).document
        val items     = document.select("a[href*='/film/']").mapNotNull { it.toSearchResult() }.distinctBy { it.url }

        return newHomePageResponse(request.name, items, items.size >= 12)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val href = this.attr("href")
        if (!href.contains("/film/")) return null
        val fullUrl = fixUrl(href)

        val title = this.selectFirst("h3.card-title")?.text()?.trim()
            ?: this.selectFirst("img")?.attr("alt")?.trim()
            ?: return null

        val imgEl = this.selectFirst("img")
        val rawImg = imgEl?.attr("src")?.ifBlank { null } ?: imgEl?.attr("data-src")
        val poster = fixUrlNull(rawImg)

        val year = this.selectFirst("div.card-meta span")?.text()?.filter { it.isDigit() }?.take(4)?.toIntOrNull()
        val score = this.selectFirst("span.rating")?.text()?.filter { it.isDigit() || it == '.' }?.toDoubleOrNull()

        return newMovieSearchResponse(title, fullUrl, TvType.Movie) {
            this.posterUrl = poster
            this.year      = year
            this.score     = Score.from10(score)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("${mainUrl}/ara?q=${query}").document
        return document.select("a[href*='/film/']").mapNotNull { it.toSearchResult() }.distinctBy { it.url }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val rawTitle = document.selectFirst("h1")?.text()?.trim()
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
        document.select("[data-src], [data-video], [data-embed]").forEach {
            val src = it.attr("data-src").ifBlank { it.attr("data-video").ifBlank { it.attr("data-embed") } }
            if (src.isNotBlank() && !src.startsWith("about:") && !src.contains("youtube.com")) {
                embedUrls.add(fixUrl(src))
            }
        }

        // 3. Regex matches for vidmoly and ok.ru
        val regex = Regex("""https?:[\\/]+(?:vidmoly\.net|ok\.ru)[\\/][a-zA-Z0-9_\-\.\?&=;]+""")
        regex.findAll(html).forEach {
            val cleaned = it.value.replace("\\/", "/").replace("&amp;", "&")
            embedUrls.add(cleaned)
        }

        var foundAny = false
        for (embed in embedUrls) {
            try {
                if (loadExtractor(embed, "$mainUrl/", subtitleCallback, callback)) {
                    foundAny = true
                }
            } catch (_: Exception) {}
        }

        return foundAny
    }
}

package com.keyiflerolsun

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import org.jsoup.Jsoup

class PuhuTV : MainAPI() {
    override var mainUrl              = "https://puhutv.com"
    override var name                 = "PuhuTV"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = true
    override val supportedTypes       = setOf(TvType.TvSeries, TvType.Movie)

    private val searchApi = "https://appservice.puhutv.com/search/search"
    private val dygSecret = "NtvApiSecret2014*"
    private val publisherId = "29"

    override val mainPage = mainPageOf(
        "Öne Çıkanlar"             to "Öne Çıkanlar",
        "PuhuTV Top 15"            to "PuhuTV Top 15",
        "Yeni Bölümler"            to "Yeni Bölümler",
        "Orijinal"                 to "Orijinal",
        "Hafızalarda Yer Edinenler" to "Hafızalarda Yer Edinenler"
    )

    private fun extractNextData(html: String): String? {
        val regex = Regex("""<script id="__NEXT_DATA__" type="application/json">(.*?)</script>""")
        return regex.find(html)?.groupValues?.get(1)
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val html = app.get(mainUrl).text
        val json = extractNextData(html) ?: return newHomePageResponse(emptyList(), false)

        val rootNode = tryParseJson<Map<String, Any>>(json) ?: return newHomePageResponse(emptyList(), false)
        val props = rootNode["props"] as? Map<*, *>
        val pageProps = props?.get("pageProps") as? Map<*, *>
        val dataObj = pageProps?.get("data") as? Map<*, *>
        val innerData = dataObj?.get("data") as? Map<*, *>
        val containers = innerData?.get("container_items") as? List<Map<String, Any>> ?: emptyList()

        val targetTitle = request.name.lowercase()
        val matchedContainer = containers.firstOrNull { container ->
            val title = (container["title"] as? String ?: container["display_name"] as? String ?: "").lowercase()
            title.contains(targetTitle) || targetTitle.contains(title)
        } ?: containers.firstOrNull { it["items"] is List<*> && (it["items"] as List<*>).isNotEmpty() }

        val items = matchedContainer?.get("items") as? List<Map<String, Any>> ?: emptyList()
        val results = items.mapNotNull { item ->
            val title = item["name"] as? String ?: return@mapNotNull null
            val meta = item["meta"] as? Map<*, *>
            val slug = meta?.get("slug") as? String ?: item["slug"] as? String ?: return@mapNotNull null
            val url = if (slug.startsWith("http")) slug else "$mainUrl/$slug"
            val poster = item["image_vertical_mobile"] as? String ?: item["image"] as? String
            val typeStr = item["type"] as? String ?: ""
            val isMovie = typeStr.contains("movie", ignoreCase = true)
            val tvType = if (isMovie) TvType.Movie else TvType.TvSeries

            if (isMovie) {
                newMovieSearchResponse(title, url, tvType) {
                    this.posterUrl = poster
                }
            } else {
                newTvSeriesSearchResponse(title, url, tvType) {
                    this.posterUrl = poster
                }
            }
        }

        return newHomePageResponse(listOf(HomePageList(request.name, results)), false)
    }

    data class SearchApiResponse(
        @JsonProperty("data") val data: List<SearchApiItem>? = null
    )

    data class SearchApiItem(
        @JsonProperty("id") val id: Any? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("slug") val slug: String? = null,
        @JsonProperty("is_movie") val isMovie: Boolean? = null,
        @JsonProperty("search_images") val searchImages: Map<String, Any>? = null,
        @JsonProperty("images") val images: Map<String, Any>? = null
    )

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$searchApi?query=${query.trim()}"
        val res = app.get(searchUrl).text
        val parsed = tryParseJson<SearchApiResponse>(res) ?: return emptyList()

        return parsed.data?.mapNotNull { item ->
            val title = item.name ?: return@mapNotNull null
            val slug = item.slug ?: return@mapNotNull null
            val url = if (slug.startsWith("http")) slug else "$mainUrl/$slug"
            val isMovie = item.isMovie == true
            val tvType = if (isMovie) TvType.Movie else TvType.TvSeries

            // Extract vertical image
            var poster: String? = null
            val verticalObj = (item.searchImages?.get("vertical") ?: item.images?.get("vertical")) as? Map<*, *>
            val mainImg = verticalObj?.get("main") as? String
            if (mainImg != null) {
                poster = if (mainImg.startsWith("http")) mainImg else "https://$mainImg"
            }

            if (isMovie) {
                newMovieSearchResponse(title, url, tvType) {
                    this.posterUrl = poster
                }
            } else {
                newTvSeriesSearchResponse(title, url, tvType) {
                    this.posterUrl = poster
                }
            }
        } ?: emptyList()
    }

    override suspend fun load(url: String): LoadResponse? {
        val html = app.get(url).text
        val json = extractNextData(html) ?: return null

        val rootNode = tryParseJson<Map<String, Any>>(json) ?: return null
        val props = rootNode["props"] as? Map<*, *>
        val pageProps = props?.get("pageProps") as? Map<*, *>
        val detailsWrap = (pageProps?.get("details") ?: pageProps?.get("watchDetails")) as? Map<*, *>
        val details = detailsWrap?.get("data") as? Map<*, *> ?: return null

        val title = details["name"] as? String ?: return null
        val poster = details["image"] as? String ?: details["background_image"] as? String
        val meta = details["meta"] as? Map<*, *>
        val plot = meta?.get("description") as? String ?: meta?.get("short_description") as? String
        val year = meta?.get("released_at")?.toString()?.toIntOrNull()
            ?: meta?.get("productionDate")?.toString()?.toIntOrNull()

        val typeStr = details["type"] as? String ?: ""
        val isMovie = typeStr.contains("movie", ignoreCase = true)

        if (isMovie) {
            val assetId = details["to_watch_asset_id"]
            val assetSlug = details["to_watch_asset_slug"] as? String
            val dataUrl = when {
                assetId != null -> "$mainUrl/api/assets/$assetId"
                assetSlug != null -> "$mainUrl/$assetSlug"
                else -> url
            }

            return newMovieLoadResponse(title, url, TvType.Movie, dataUrl) {
                this.posterUrl = poster
                this.plot      = plot
                this.year      = year
            }
        }

        // Handle TV Series
        val episodeData = pageProps?.get("episodeData") as? Map<*, *>
        val epDataInner = episodeData?.get("data") as? Map<*, *>
        val episodesList = epDataInner?.get("episodes") as? List<Map<String, Any>> ?: emptyList()

        val episodeObjs = episodesList.mapNotNull { ep ->
            val epName = ep["name"] as? String ?: "Bölüm"
            val videoId = ep["video_id"] as? String
            val slug = ep["slug"] as? String
            val epData = videoId ?: (if (slug != null) "$mainUrl/$slug" else null) ?: return@mapNotNull null

            val epMeta = ep["meta"] as? Map<*, *>
            val epNum = epMeta?.get("position")?.toString()?.toIntOrNull()
                ?: Regex("""\d+""").find(epName)?.value?.toIntOrNull()
            val seasonNum = ep["season_number"]?.toString()?.toIntOrNull() ?: 1
            val epPoster = ep["image"] as? String

            newEpisode(epData) {
                this.name      = epName
                this.episode   = epNum
                this.season    = seasonNum
                this.posterUrl = epPoster
            }
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodeObjs) {
            this.posterUrl = poster
            this.plot      = plot
            this.year      = year
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var videoId: String? = null

        when {
            data.startsWith("PUHU_") -> {
                videoId = data
            }
            data.contains("/api/assets/") -> {
                val assetJson = app.get(data).text
                val root = tryParseJson<Map<String, Any>>(assetJson)
                val assetData = root?.get("data") as? Map<*, *>
                videoId = assetData?.get("video_id") as? String
            }
            data.startsWith("http") -> {
                val html = app.get(data).text
                val json = extractNextData(html)
                if (json != null) {
                    val rootNode = tryParseJson<Map<String, Any>>(json)
                    val props = rootNode?.get("props") as? Map<*, *>
                    val pageProps = props?.get("pageProps") as? Map<*, *>
                    val watchDetails = pageProps?.get("watchDetails") as? Map<*, *>
                    val watchData = watchDetails?.get("data") as? Map<*, *>
                    val assetId = watchData?.get("to_watch_asset_id")
                    if (assetId != null) {
                        val assetJson = app.get("$mainUrl/api/assets/$assetId").text
                        val aRoot = tryParseJson<Map<String, Any>>(assetJson)
                        val aData = aRoot?.get("data") as? Map<*, *>
                        videoId = aData?.get("video_id") as? String
                    }
                }
            }
        }

        if (videoId.isNullOrBlank()) return false

        val infoUrl = "https://dygvideo.dygdigital.com/api/video_info?akamai=true&PublisherId=$publisherId&ReferenceId=$videoId&SecretKey=$dygSecret"
        val response = app.get(infoUrl, referer = "$mainUrl/").text

        val m3u8Url = Regex(""""hls"\s*:\s*"([^"]+)"""").find(response)?.groupValues?.get(1)?.replace("\\/", "/")
            ?: Regex(""""file_url_1"\s*:\s*"([^"]+)"""").find(response)?.groupValues?.get(1)?.replace("\\/", "/")
            ?: return false

        callback.invoke(
            newExtractorLink(
                source = name,
                name   = "$name 1080p",
                url    = m3u8Url,
                type   = ExtractorLinkType.M3U8
            ) {
                this.headers = mapOf("Referer" to "$mainUrl/")
                this.quality = Qualities.P1080.value
            }
        )

        return true
    }
}

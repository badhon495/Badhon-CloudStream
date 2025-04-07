package com.redowan

import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchQuality
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.addDubStatus
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.getDurationFromString
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newAnimeSearchResponse
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.utils.AppUtils
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities

//suspend fun main() {
//    val providerTester = com.lagradost.cloudstreamtest.ProviderTester(DhakaFlixFtpProvider())
////    providerTester.testAll()
////    providerTester.testMainPage(verbose = true)
//    providerTester.testSearch(query = "gun",verbose = true)
////    providerTester.testLoad("")
//}

class DhakaFlixFtpProvider : MainAPI() {
    override var mainUrl = "http://172.16.50.12/DHAKA-FLIX-12/" // Updated base URL
    override var name = "DhakaFlix FTP"
    override var lang = "bn"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val hasQuickSearch = false
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.Cartoon,
        TvType.AsianDrama,
        TvType.Documentary,
        TvType.OVA,
        TvType.Others
    )
    override val mainPage = mainPageOf(
        "80" to "Featured",
        "6" to "English Movies",
        "9" to "English & Foreign TV Series",
        "22" to "Dubbed TV Series",
        "2" to "Hindi Movies",
        "5" to "Hindi TV Series",
        "3" to "South Indian Dubbed Movie",
        "21" to "Anime Series",
        "1" to "Animation Movies",
        "85" to "Documentary",
        "15" to "WWE"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val json = try {
            app.get(
                "$mainApiUrl/api/posts?categoryExact=${request.data}&page=$page&order=desc&limit=10",
                verify = false,
                cacheTime = 60
            )
            // First try mainApiUrl
        } catch (_: Exception) {
            app.get(
                "$apiUrl/api/posts?categoryExact=${request.data}&page=$page&order=desc&limit=10",
                verify = false,
                cacheTime = 60
            )
            // Fallback to apiUrl
        }
        val home = AppUtils.parseJson<PageData>(json.text).posts.mapNotNull { post ->
            toSearchResult(post)
        }
        return newHomePageResponse(request.name, home, true)
    }

    private fun toSearchResult(post: Post): SearchResponse? {
        if (post.type == "singleVideo" || post.type == "series") {
            return newAnimeSearchResponse(post.title, "$mainUrl/content/${post.id}", TvType.Movie) {
                this.posterUrl = "$mainApiUrl/uploads/${post.imageSm}"
                val check = post.title.lowercase()
                this.quality = getSearchQuality(check)
                addDubStatus(
                    dubExist = when {
                        "dubbed" in check -> true
                        "dual audio" in check -> true
                        "multi audio" in check -> true
                        else -> false
                    },
                    subExist = false
                )
            }
        }
        return null
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val json = try {
            app.get(
                "$mainApiUrl/api/posts?searchTerm=$query&order=desc",
                verify = false,
                cacheTime = 60
            )
            // First try mainApiUrl
        } catch (_: Exception) {
            app.get(
                "$apiUrl/api/posts?searchTerm=$query&order=desc",
                verify = false,
                cacheTime = 60
            )
            // Fallback to apiUrl
        }
        return AppUtils.parseJson<PageData>(json.text).posts.mapNotNull { post ->
            toSearchResult(post)
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val json = try {
            app.get(
                url.replace("$mainUrl/content/", "$mainApiUrl/api/posts/"),
                verify = false,
                cacheTime = 60
            )
            // First try mainApiUrl
        } catch (_: Exception) {
            app.get(
                url.replace("$mainUrl/content/", "$apiUrl/api/posts/"),
                verify = false,
                cacheTime = 60
            )
            // Fallback to apiUrl
        }
        val urlCheck = json.url.contains(mainApiUrl)
        val loadData = AppUtils.parseJson<Data>(json.text)
        val title = loadData.title
        val poster = "$apiUrl/uploads/${loadData.image}"
        val description = loadData.metaData
        val year = selectUntilNonInt(loadData.year)

        if (loadData.type == "singleVideo") {
            val movieUrl = json.parsed<Movies>().content
            val link = if(urlCheck) movieUrl else linkToIp(movieUrl)
            val duration =
                getDurationFromString(loadData.watchTime)
            return newMovieLoadResponse(title, url, TvType.Movie, link) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.duration = duration
            }
        } else {
            val tvData = json.parsed<TvSeries>()
            val episodesData = mutableListOf<Episode>()
            var seasonNum = 0
            tvData.content.forEach { season ->
                seasonNum++
                var episodeNum = 0
                season.episodes.forEach {
                    episodeNum++
                    val episodeUrl = it.link
                    val link = if(urlCheck) episodeUrl else linkToIp(episodeUrl)
                    episodesData.add(
                        newEpisode(link){
                            //this.name = "Episode $episodeNum"
                            this.episode = episodeNum
                            this.season = seasonNum
                        }
                    )
                }
            }
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodesData) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
            }
        }
    }

    private fun linkToIp(data: String?): String {
        if (data != null) {
            return when {
                "DHAKA-FLIX-12" in data -> data.replace("DHAKA-FLIX-12", "172.16.50.12/DHAKA-FLIX-12")
                "DHAKA-FLIX-14" in data -> data.replace("DHAKA-FLIX-14", "172.16.50.14/DHAKA-FLIX-14")
                "DHAKA-FLIX-7" in data -> data.replace("DHAKA-FLIX-7", "172.16.50.7/DHAKA-FLIX-7")
                "DHAKA-FLIX-9" in data -> data.replace("DHAKA-FLIX-9", "172.16.50.9/DHAKA-FLIX-9")
                else -> data
            }
        }
        return ""
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        callback.invoke(
            ExtractorLink(
                mainApiUrl,
                this.name,
                url = data,
                mainApiUrl,
                quality = getVideoQuality(data),
                isM3u8 = false,
                isDash = false
            )
        )
        return true
    }

    /**
     * Extracts the initial numeric part of a string and returns it as an integer.
     *
     * @param string The input string.
     * @return The initial numeric part as an integer, or `null` if the string doesn't start with a number or is null.
     */
    private fun selectUntilNonInt(string: String?): Int? {
        return string?.let { Regex("^.*?(?=\\D|\$)").find(it)?.value?.toIntOrNull() }
    }

    /**
     * Determines the search quality based on the presence of specific keywords in the input string.
     *
     * @param check The string to check for keywords.
     * @return The corresponding `SearchQuality` enum value, or `null` if no match is found.
     */
    private fun getSearchQuality(check: String?): SearchQuality? {
        val lowercaseCheck = check?.lowercase()
        if (lowercaseCheck != null) {
            return when {
                lowercaseCheck.contains("webrip") || lowercaseCheck.contains("web-dl") -> SearchQuality.WebRip
                lowercaseCheck.contains("bluray") -> SearchQuality.BlueRay
                lowercaseCheck.contains("hdts") || lowercaseCheck.contains("hdcam") || lowercaseCheck.contains(
                    "hdtc"
                ) -> SearchQuality.HdCam

                lowercaseCheck.contains("dvd") -> SearchQuality.DVD
                lowercaseCheck.contains("cam") -> SearchQuality.Cam
                lowercaseCheck.contains("camrip") || lowercaseCheck.contains("rip") -> SearchQuality.CamRip
                lowercaseCheck.contains("hdrip") || lowercaseCheck.contains("hd") || lowercaseCheck.contains(
                    "hdtv"
                ) -> SearchQuality.HD

                lowercaseCheck.contains("telesync") -> SearchQuality.Telesync
                lowercaseCheck.contains("telecine") -> SearchQuality.Telecine
                else -> null
            }
        }
        return null
    }

    /**
     * Extracts the video resolution (in pixels) from a string.
     *
     * @param string The input string containing the resolution (e.g., "720p", "1080P").
     * @return The resolution as an integer, or `Qualities.Unknown.value` if no resolution is found.
     */
    private fun getVideoQuality(string: String?): Int {
        return Regex("(\\d{3,4})[pP]").find(string ?: "")?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: Qualities.Unknown.value
    }

    data class PageData(
        val posts: List<Post>
    )

    data class Post(
        val id: Int,
        val type: String,
        val imageSm: String,
        val title: String,
        val name: String?,
    )

    data class Data(
        val type: String,
        val imageSm: String,
        val title: String,
        val image: String,
        val metaData: String?,
        val name: String,
        val quality: String?,
        val year: String?,
        val watchTime: String?
    )

    data class TvSeries(
        val content: List<Content>,
    )

    data class Content(
        val episodes: List<EpisodeData>,
        val seasonName: String
    )

    data class EpisodeData(
        val link: String,
        val title: String
    )

    data class Movies(
        val content: String?
    )

}
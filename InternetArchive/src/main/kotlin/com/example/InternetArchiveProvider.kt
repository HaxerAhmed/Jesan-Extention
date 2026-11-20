package com.example

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.utils.AppUtils
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.net.URLEncoder

class InternetArchiveProvider : MainAPI() {
    override var mainUrl = "https://archive.org"
    override var name = "InternetArchive"
    override val hasMainPage = true
    override var lang = "en"
    override val supportedTypes = setOf(TvType.Movie, TvType.Documentary)

    override val mainPage = mainPageOf(
        "feature_films" to "Feature Films",
        "sci_fi_horror" to "Sci-Fi & Horror Classics",
        "film_noir" to "Classic Film Noir",
        "classic_cartoons" to "Classic Cartoons",
        "short_films" to "Short Films & Documentaries"
    )

    private val videoExtensionsPriority = listOf(".mp4", ".m3u8", ".mkv", ".webm", ".ogv")

    // Polymorphic JSON Shield: Archive.org returns title/description/year as String OR List<Any>
    private fun Any?.toSafeString(): String? = when (this) {
        is String -> this
        is List<*> -> this.firstOrNull()?.toString()
        else -> this?.toString()
    }

    // Smart URL Segment Encoder: prevents ExoPlayer crashes on spaces/special chars
    private fun encodeFilePath(fileName: String): String =
        fileName.trimStart('/').split("/").joinToString("/") { segment ->
            URLEncoder.encode(segment, "UTF-8").replace("+", "%20")
        }

    private fun throwBlackboxReport(
        stage: String,
        targetUrl: String,
        httpStatus: Int? = null,
        filesScanned: Int = 0,
        availableExtensions: List<String> = emptyList(),
        errorMessage: String
    ): Nothing {
        throw ErrorLoadingException(
            """
            [CLOUDSTREAM_BLACKBOX_REPORT]
            Provider: ${this.name}
            Stage: $stage
            Target_URL: $targetUrl
            HTTP_Status: ${httpStatus ?: "N/A"}
            Files_Scanned: $filesScanned
            Available_Extensions: ${if (availableExtensions.isEmpty()) "none" else availableExtensions.joinToString(", ")}
            Error: $errorMessage
            """.trimIndent()
        )
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val collection = request.data
        val searchUrl =
            "$mainUrl/advancedsearch.php?q=collection:($collection)+AND+mediatype:(movies)" +
                "&fl[]=identifier,title&sort[]=downloads+desc&rows=20&page=$page&output=json"
        try {
            val response = app.get(searchUrl)
            if (response.code !in 200..299) {
                throwBlackboxReport(
                    stage = "getMainPage",
                    targetUrl = searchUrl,
                    httpStatus = response.code,
                    errorMessage = "Non-2xx HTTP status from Archive.org advancedsearch"
                )
            }
            val parsed = AppUtils.parseJson<ArchiveSearchWrapper>(response.text)
            val items = parsed.response?.docs.orEmpty().mapNotNull { doc ->
                val id = doc.identifier.toSafeString()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val title = doc.title.toSafeString()?.takeIf { it.isNotBlank() } ?: id
                newMovieSearchResponse(
                    name = title,
                    url = "$mainUrl/details/$id",
                    type = TvType.Movie
                ) {
                    this.posterUrl = "$mainUrl/services/img/$id"
                }
            }
            return newHomePageResponse(
                list = HomePageList(request.name, items),
                hasNext = items.size >= 20
            )
        } catch (e: ErrorLoadingException) {
            throw e
        } catch (e: Exception) {
            throwBlackboxReport(
                stage = "getMainPage",
                targetUrl = searchUrl,
                errorMessage = e.message ?: e::class.java.simpleName
            )
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encodedQuery = URLEncoder.encode(query.trim(), "UTF-8")
        val searchUrl =
            "$mainUrl/advancedsearch.php?q=($encodedQuery)+AND+mediatype:(movies)" +
                "&fl[]=identifier,title&sort[]=downloads+desc&rows=20&page=1&output=json"
        try {
            val response = app.get(searchUrl)
            if (response.code !in 200..299) {
                throwBlackboxReport(
                    stage = "search",
                    targetUrl = searchUrl,
                    httpStatus = response.code,
                    errorMessage = "Non-2xx HTTP status during search"
                )
            }
            val parsed = AppUtils.parseJson<ArchiveSearchWrapper>(response.text)
            return parsed.response?.docs.orEmpty().mapNotNull { doc ->
                val id = doc.identifier.toSafeString()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val title = doc.title.toSafeString()?.takeIf { it.isNotBlank() } ?: id
                newMovieSearchResponse(
                    name = title,
                    url = "$mainUrl/details/$id",
                    type = TvType.Movie
                ) {
                    this.posterUrl = "$mainUrl/services/img/$id"
                }
            }
        } catch (e: ErrorLoadingException) {
            throw e
        } catch (e: Exception) {
            throwBlackboxReport(
                stage = "search",
                targetUrl = searchUrl,
                errorMessage = e.message ?: e::class.java.simpleName
            )
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val identifier = url.substringAfter("/details/").substringBefore("/").substringBefore("?")
        val metaUrl = "$mainUrl/metadata/$identifier"
        if (identifier.isBlank()) {
            throwBlackboxReport(
                stage = "load",
                targetUrl = url,
                errorMessage = "Could not extract Archive.org identifier from URL"
            )
        }
        try {
            val response = app.get(metaUrl)
            if (response.code !in 200..299) {
                throwBlackboxReport(
                    stage = "load",
                    targetUrl = metaUrl,
                    httpStatus = response.code,
                    errorMessage = "Failed to load Archive.org item metadata"
                )
            }
            val meta = AppUtils.parseJson<ArchiveMetadataResponse>(response.text)
            val info = meta.metadata
            val title = info?.title.toSafeString()?.takeIf { it.isNotBlank() } ?: identifier
            val descriptionText = info?.description.toSafeString()
                ?.replace(Regex("<[^>]*>"), "")
                ?.trim()
            val yearInt = info?.year.toSafeString()?.take(4)?.toIntOrNull()

            return newMovieLoadResponse(
                name = title,
                url = url,
                type = TvType.Movie,
                dataUrl = identifier
            ) {
                this.posterUrl = "$mainUrl/services/img/$identifier"
                this.plot = descriptionText
                this.year = yearInt
            }
        } catch (e: ErrorLoadingException) {
            throw e
        } catch (e: Exception) {
            throwBlackboxReport(
                stage = "load",
                targetUrl = metaUrl,
                errorMessage = e.message ?: e::class.java.simpleName
            )
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val identifier = data.trim()
        val metaUrl = "$mainUrl/metadata/$identifier"
        val detailsUrl = "$mainUrl/details/$identifier"
        var filesScanned = 0
        var distinctExts: List<String> = emptyList()
        var lastHttpStatus: Int? = null
        var linksFound = 0

        try {
            // Primary Path: Archive.org JSON Metadata API
            val metaResp = app.get(metaUrl)
            lastHttpStatus = metaResp.code
            if (metaResp.code in 200..299) {
                val meta = AppUtils.parseJson<ArchiveMetadataResponse>(metaResp.text)
                val files = meta.files.orEmpty()
                filesScanned = files.size
                distinctExts = files.mapNotNull { entry ->
                    entry.name?.substringAfterLast('.', "")?.lowercase()?.takeIf { it.isNotBlank() }
                }.distinct()

                // Extract external SRT / VTT subtitles
                files.filter { file ->
                    val lower = file.name.orEmpty().lowercase()
                    lower.endsWith(".srt") || lower.endsWith(".vtt")
                }.forEach { sub ->
                    val fileName = sub.name ?: return@forEach
                    val subUrl = "$mainUrl/download/$identifier/${encodeFilePath(fileName)}"
                    val langLabel = fileName.substringBeforeLast(".").substringAfterLast(".", "English")
                    subtitleCallback.invoke(SubtitleFile(lang = langLabel, url = subUrl))
                }

                // Multi-format video extraction ordered by priority (.mp4, .m3u8, .mkv, .webm, .ogv)
                val prioritizedVideos = files.mapNotNull { entry ->
                    val fileName = entry.name ?: return@mapNotNull null
                    val lower = fileName.lowercase()
                    val priorityIdx = videoExtensionsPriority.indexOfFirst { lower.endsWith(it) }
                    if (priorityIdx >= 0) Triple(priorityIdx, fileName, entry) else null
                }.sortedBy { it.first }

                prioritizedVideos.forEach { (_, fileName, entry) ->
                    val streamUrl = "$mainUrl/download/$identifier/${encodeFilePath(fileName)}"
                    val isHls = fileName.lowercase().endsWith(".m3u8")
                    val linkType = if (isHls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    val label = entry.format.toSafeString()?.takeIf { it.isNotBlank() }
                        ?: fileName.substringAfterLast('.', "VIDEO").uppercase()

                    callback.invoke(
                        newExtractorLink(
                            source = this.name,
                            name = "$name - $label",
                            url = streamUrl,
                            type = linkType
                        ) {
                            this.referer = detailsUrl
                            this.quality = inferQuality(entry.height.toSafeString(), fileName)
                        }
                    )
                    linksFound++
                }
            }

            // Self-Healing API-to-HTML Fallback via Jsoup if JSON yielded 0 streams
            if (linksFound == 0) {
                val htmlResp = app.get(detailsUrl)
                lastHttpStatus = htmlResp.code
                val doc = htmlResp.document
                val fallbackAnchors = doc.select("a[href$=.mp4], a[href$=.ogv], a[href$=.mkv], a[href$=.webm]")
                fallbackAnchors.forEach { anchor ->
                    val rawHref = anchor.attr("href").trim()
                    if (rawHref.isBlank()) return@forEach
                    val fullUrl = when {
                        rawHref.startsWith("http://") || rawHref.startsWith("https://") -> rawHref
                        rawHref.startsWith("/") -> "$mainUrl$rawHref"
                        else -> "$mainUrl/download/$identifier/${encodeFilePath(rawHref)}"
                    }
                    val extLabel = rawHref.substringAfterLast('.', "VIDEO").uppercase()
                    callback.invoke(
                        newExtractorLink(
                            source = this.name,
                            name = "$name Fallback ($extLabel)",
                            url = fullUrl,
                            type = ExtractorLinkType.VIDEO
                        ) {
                            this.referer = detailsUrl
                            this.quality = inferQuality(null, rawHref)
                        }
                    )
                    linksFound++
                }
            }

            if (linksFound == 0) {
                throwBlackboxReport(
                    stage = "loadLinks",
                    targetUrl = metaUrl,
                    httpStatus = lastHttpStatus,
                    filesScanned = filesScanned,
                    availableExtensions = distinctExts,
                    errorMessage = "0 playable video streams found after JSON metadata + HTML Jsoup fallback"
                )
            }
            return true
        } catch (e: ErrorLoadingException) {
            throw e
        } catch (e: Exception) {
            throwBlackboxReport(
                stage = "loadLinks",
                targetUrl = metaUrl,
                httpStatus = lastHttpStatus,
                filesScanned = filesScanned,
                availableExtensions = distinctExts,
                errorMessage = e.message ?: e::class.java.simpleName
            )
        }
    }

    private fun inferQuality(height: String?, fileName: String): Int {
        val h = height?.toIntOrNull()
        return when {
            h != null && h >= 1080 || fileName.contains("1080", true) -> Qualities.P1080.value
            h != null && h >= 720 || fileName.contains("720", true) -> Qualities.P720.value
            h != null && h >= 480 || fileName.contains("480", true) -> Qualities.P480.value
            h != null && h >= 360 || fileName.contains("360", true) -> Qualities.P360.value
            else -> Qualities.Unknown.value
        }
    }
}

data class ArchiveSearchWrapper(
    @JsonProperty("response") val response: ArchiveSearchResponse? = null
)

data class ArchiveSearchResponse(
    @JsonProperty("docs") val docs: List<ArchiveSearchDoc>? = null
)

data class ArchiveSearchDoc(
    @JsonProperty("identifier") val identifier: Any? = null,
    @JsonProperty("title") val title: Any? = null
)

data class ArchiveMetadataResponse(
    @JsonProperty("metadata") val metadata: ArchiveItemMetadata? = null,
    @JsonProperty("files") val files: List<ArchiveFileEntry>? = null
)

data class ArchiveItemMetadata(
    @JsonProperty("title") val title: Any? = null,
    @JsonProperty("description") val description: Any? = null,
    @JsonProperty("year") val year: Any? = null
)

data class ArchiveFileEntry(
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("format") val format: Any? = null,
    @JsonProperty("height") val height: Any? = null
)

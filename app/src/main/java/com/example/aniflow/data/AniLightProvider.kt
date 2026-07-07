package com.example.aniflow.data

import com.example.aniflow.data.model.Episode
import com.example.aniflow.data.model.EpisodeSourcesResponse
import com.example.aniflow.data.model.StreamingSource
import com.example.aniflow.data.model.SubtitleTrack
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.net.URLEncoder
import java.util.regex.Pattern

@Serializable
data class ProviderSearchResult(
    val title: String,
    val slug: String,
    val posterUrl: String
)

@Serializable
data class AniLightTitle(
    val english: String? = null,
    val romaji: String? = null,
    val native: String? = null
)

@Serializable
data class AniLightCoverImage(
    val large: String? = null,
    val extraLarge: String? = null
)

@Serializable
data class AniLightAnimeItem(
    val id: Int,
    val slug: String,
    val title: AniLightTitle,
    val coverImage: AniLightCoverImage? = null
)

@Serializable
data class AniLightWatchResponse(
    val id: Int? = null,
    val episodes: List<AniLightEpisode> = emptyList(),
    val servers: AniLightServers? = null
)

@Serializable
data class AniLightEpisode(
    val number: Int,
    val title: String? = null,
    val description: String? = null,
    val img: String? = null
)

@Serializable
data class AniLightServers(
    val dubProviders: List<AniLightServerItem> = emptyList(),
    val subProviders: List<AniLightServerItem> = emptyList()
)

@Serializable
data class AniLightServerItem(
    val id: String,
    val tip: String? = null,
    val default: Boolean = false
)

@Serializable
data class AniLightSourcesResponse(
    val sources: List<AniLightSource> = emptyList(),
    val tracks: List<AniLightTrack> = emptyList()
)

@Serializable
data class AniLightSource(
    val url: JsonElement,
    val quality: String? = null,
    val type: String? = null
)

@Serializable
data class AniLightTrack(
    val url: String? = null,
    val file: String? = null,
    val label: String? = null,
    val lang: String? = null,
    val kind: String? = null,
    val default: Boolean = false
)

class AniLightProvider(private val client: HttpClient) {
    private val json = NetworkModule.json
    private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    private val baseUrl = "https://api.anilight.live/api"

    suspend fun search(query: String): List<ProviderSearchResult> {
        return try {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val response = client.get("$baseUrl/search?q=$encoded") {
                header("User-Agent", userAgent)
            }
            if (response.status == HttpStatusCode.OK) {
                val list = json.decodeFromString<List<AniLightAnimeItem>>(response.bodyAsText())
                list.map { item ->
                    val title = item.title.english ?: item.title.romaji ?: item.title.native ?: "Unknown Title"
                    val poster = item.coverImage?.large ?: item.coverImage?.extraLarge ?: ""
                    ProviderSearchResult(
                        title = title,
                        slug = item.slug,
                        posterUrl = poster
                    )
                }
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            android.util.Log.e("AniLightProvider", "Search failed for '$query'", e)
            emptyList()
        }
    }

    suspend fun getEpisodeList(showId: String): List<Episode> {
        return try {
            val response = client.get("$baseUrl/watch/$showId") {
                header("User-Agent", userAgent)
            }
            if (response.status == HttpStatusCode.OK) {
                val watchData = json.decodeFromString<AniLightWatchResponse>(response.bodyAsText())
                val animeId = watchData.id ?: 0
                watchData.episodes.map { ep ->
                    // Construct a custom episode ID that carries slug, animeId, and epNum
                    val episodeId = "anilight:$showId|$animeId|${ep.number}"
                    Episode(
                        id = episodeId,
                        name = ep.title ?: "Episode ${ep.number}",
                        number = ep.number,
                        description = ep.description ?: "",
                        thumbnail = ep.img ?: ""
                    )
                }
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            android.util.Log.e("AniLightProvider", "GetEpisodeList failed for slug '$showId'", e)
            emptyList()
        }
    }

    private suspend fun <T> retryWithBackoff(retries: Int = 3, initialDelayMs: Long = 1000L, block: suspend () -> T?): T? {
        var currentDelay = initialDelayMs
        for (i in 0 until retries) {
            try {
                val res = block()
                if (res != null) return res
            } catch (e: Exception) {
                if (i == retries - 1) throw e
            }
            kotlinx.coroutines.delay(currentDelay)
            currentDelay *= 2
        }
        return null
    }

    suspend fun getStreamUrl(episodeId: String): EpisodeSourcesResponse {
        if (!episodeId.startsWith("anilight:")) {
            return EpisodeSourcesResponse(sources = emptyList())
        }
        
        try {
            val parts = episodeId.substringAfter("anilight:").split("|")
            if (parts.size < 3) return EpisodeSourcesResponse(sources = emptyList())
            val slug = parts[0]
            val animeId = parts[1]
            val epNum = parts[2].toIntOrNull() ?: 1
            
            // 1. Fetch servers list by hitting watch details
            val servers = retryWithBackoff(retries = 3) {
                val response = client.get("$baseUrl/watch/$slug") {
                    header("User-Agent", userAgent)
                }
                if (response.status == HttpStatusCode.OK) {
                    json.decodeFromString<AniLightWatchResponse>(response.bodyAsText()).servers
                } else null
            }
            
            // Default servers to try if fetch fails or servers is null
            val subServers = servers?.subProviders?.map { it.id } ?: listOf("light", "misa", "near", "raye", "rem", "ryu")
            val dubServers = servers?.dubProviders?.map { it.id } ?: listOf("light", "misa", "near", "raye", "ryu")
            
            val resolvedSources = mutableListOf<StreamingSource>()
            val resolvedSubtitles = mutableListOf<SubtitleTrack>()
            val customHeaders = mutableMapOf<String, String>()
            
            // Try resolving sources for each sub and dub provider
            val serverGroups = listOf(
                "sub" to subServers,
                "dub" to dubServers
            )
            
            for ((type, providersList) in serverGroups) {
                for (provId in providersList) {
                    try {
                        val sourcesUrl = "$baseUrl/sources?id=$animeId&epNum=$epNum&type=$type&providerId=$provId"
                        android.util.Log.d("AniLightProvider", "Fetching sources from: $sourcesUrl")
                        val res = retryWithBackoff(retries = 3) {
                            val sourcesResponse = client.get(sourcesUrl) {
                                header("User-Agent", userAgent)
                            }
                            if (sourcesResponse.status == HttpStatusCode.OK) {
                                json.decodeFromString<AniLightSourcesResponse>(sourcesResponse.bodyAsText())
                            } else null
                        }
                        if (res != null) {
                            // Map subtitles
                            res.tracks.forEach { track ->
                                val subtitleUrl = track.file ?: track.url ?: ""
                                if (subtitleUrl.isNotEmpty()) {
                                     val mappedSubUrl = mapSubtitlesUrl(subtitleUrl)
                                     val lang = track.lang ?: track.label ?: "English"
                                     val label = track.label ?: lang
                                     val exists = resolvedSubtitles.any { it.url == mappedSubUrl }
                                     if (!exists) {
                                         resolvedSubtitles.add(SubtitleTrack(url = mappedSubUrl, lang = lang, label = label))
                                     }
                                }
                            }
                            
                            // Map sources
                            res.sources.forEach { src ->
                                val urls = extractUrls(src.url)
                                val quality = src.quality ?: "Auto"
                                val isM3U8 = urls.any { it.contains(".m3u8") } || src.type == "hls"
                                
                                urls.forEach { rawUrl ->
                                    val mappedUrls = decryptUrl(rawUrl, provId)
                                    mappedUrls.forEach { finalUrl ->
                                        // Pick appropriate Referer header if needed
                                        val referrer = try {
                                            val parsed = io.ktor.http.Url(rawUrl)
                                            "${parsed.protocol.name}://${parsed.host}/"
                                        } catch (e: Exception) {
                                            "https://api.anilight.live/"
                                        }
                                        customHeaders["Referer"] = referrer
                                        
                                        resolvedSources.add(
                                            StreamingSource(
                                                url = finalUrl,
                                                quality = "$quality ($provId - ${type.uppercase()})",
                                                isM3U8 = isM3U8 || finalUrl.contains(".m3u8"),
                                                headers = mapOf(
                                                    "Referer" to referrer,
                                                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
                                                )
                                            )
                                        )
                                    }
                                }
                            }
                        }
                    } catch (e: Exception) {
                        android.util.Log.w("AniLightProvider", "Failed to fetch source for provider '$provId' ($type)", e)
                    }
                }
            }
            
            if (resolvedSources.isNotEmpty()) {
                return EpisodeSourcesResponse(
                    sources = resolvedSources,
                    subtitles = resolvedSubtitles,
                    headers = customHeaders
                )
            }
        } catch (e: Exception) {
            android.util.Log.e("AniLightProvider", "GetStreamUrl failed for episodeId '$episodeId'", e)
        }
        
        return EpisodeSourcesResponse(sources = emptyList())
    }

    private fun extractUrls(element: JsonElement): List<String> {
        return when (element) {
            is JsonArray -> element.mapNotNull { it.jsonPrimitive.contentOrNull }
            is JsonPrimitive -> listOfNotNull(element.contentOrNull)
            else -> emptyList()
        }
    }

    // --- Dynamic URL Decryption and Mapping Routines matching anilight.live ---

    private fun decryptUrl(url: String, providerId: String): List<String> {
        if (providerId == "raye" && url.isNotEmpty()) {
            try {
                val key = "aproxy2026".toByteArray(Charsets.UTF_8)
                val plaintext = (url + "\u0000https://kwik.cx").toByteArray(Charsets.UTF_8)
                val ciphertext = ByteArray(plaintext.size)
                for (i in plaintext.indices) {
                    ciphertext[i] = (plaintext[i].toInt() xor key[i % key.size].toInt()).toByte()
                }
                // Base64-url safe encoding without padding
                val b64 = android.util.Base64.encodeToString(ciphertext, android.util.Base64.NO_WRAP or android.util.Base64.URL_SAFE).trimEnd('=')
                val m = "https://cdn.animex.su/stream/$b64/index.txt"
                val finalUrl = "$baseUrl/lb/raye/proxy?url=${URLEncoder.encode(m, "UTF-8")}"
                return listOf(finalUrl)
            } catch (e: Exception) {
                android.util.Log.e("AniLightProvider", "Raye decryption failed", e)
            }
        }
        
        if (providerId == "ryu" && url.isNotEmpty()) {
            return listOf("$baseUrl/proxy/ryu?url=${URLEncoder.encode(url, "UTF-8")}")
        }
        
        return z3(url)
    }

    private fun z3(url: String): List<String> {
        var urls = listOf(url)
        
        // Rule 1: cdn.mewstream.buzz/ -> j3nd.voltara.click/
        urls = urls.flatMap { u ->
            if (u.contains("cdn.mewstream.buzz/")) {
                listOf(u.replace("cdn.mewstream.buzz/", "j3nd.voltara.click/"))
            } else listOf(u)
        }
        
        // Rule 2: s2.cinewave2.site/anime/ -> [j5b9s.streamzone1.site/anime/, 9hjkrt.nekostream.site/, p4m9q.cinewave2.site/anime/]
        urls = urls.flatMap { u ->
            if (u.contains("s2.cinewave2.site/anime/")) {
                listOf(
                    u.replace("s2.cinewave2.site/anime/", "j5b9s.streamzone1.site/anime/"),
                    u.replace("s2.cinewave2.site/anime/", "9hjkrt.nekostream.site/"),
                    u.replace("s2.cinewave2.site/anime/", "p4m9q.cinewave2.site/anime/")
                )
            } else listOf(u)
        }
        
        // Rule 3: s1.streamzone1.site/anime/ -> [j5b9s.streamzone1.site/anime/, 9hjkrt.nekostream.site/, p4m9q.cinewave2.site/anime/]
        urls = urls.flatMap { u ->
            if (u.contains("s1.streamzone1.site/anime/")) {
                listOf(
                    u.replace("s1.streamzone1.site/anime/", "j5b9s.streamzone1.site/anime/"),
                    u.replace("s1.streamzone1.site/anime/", "9hjkrt.nekostream.site/"),
                    u.replace("s1.streamzone1.site/anime/", "p4m9q.cinewave2.site/anime/")
                )
            } else listOf(u)
        }
        
        // Rule 4: vibeplayer.site -> vivibebe.site
        urls = urls.flatMap { u ->
            if (u.contains("vibeplayer.site")) {
                listOf(u.replace("vibeplayer.site", "vivibebe.site"))
            } else listOf(u)
        }
        
        // Apply Worker & Domain proxies ($9 function)
        return urls.map { u ->
            val resolvedUrl = if (u.startsWith("/lb/")) {
                "$baseUrl$u"
            } else {
                u
            }
            mapProxyUrl(resolvedUrl)
        }.distinct()
    }

    private fun mapProxyUrl(url: String): String {
        val workerDomains = listOf("cdn.mewstream.buzz", "j5b9s.streamzone1.site", "9hjkrt.nekostream.site", "j3nd.voltara.click", "p4m9q.cinewave2.site")
        val nearDomains = listOf("hls.anidb.app")
        val apiDomains = listOf("vivibebe.site", "vibeplayer.site", "bd.24stream.xyz")
        
        val encoded = URLEncoder.encode(url, "UTF-8")
        return when {
            workerDomains.any { url.contains(it) } -> "$baseUrl/lb/misa/proxy?url=$encoded"
            nearDomains.any { url.contains(it) } -> "$baseUrl/lb/near/proxy?url=$encoded"
            apiDomains.any { url.contains(it) } -> "$baseUrl/proxy?url=$encoded"
            else -> url
        }
    }

    private fun mapSubtitlesUrl(url: String): String {
        val captionDomains = listOf("1oe.lostproject.club")
        return if (captionDomains.any { url.contains(it) }) {
            "$baseUrl/proxy/captions?url=${URLEncoder.encode(url, "UTF-8")}"
        } else {
            url
        }
    }
}

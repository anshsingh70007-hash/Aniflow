package com.example.aniflow.data.repository

import android.content.Context
import com.example.aniflow.data.*
import com.example.aniflow.data.model.*
import com.example.aniflow.data.remote.AniListApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import io.ktor.client.request.get
import io.ktor.client.call.body

class DefaultAnimeRepository(private val context: Context) : AnimeRepository {
    private val client = NetworkModule.client
    private val aniListApi = AniListApi(client)
    private val aniLightProvider = AniLightProvider(client)
    private val settingsStore = SettingsStore(context)

    override fun getTrending(): Flow<List<Anime>> = flow {
        val list = aniListApi.getTrending()
        emit(list.ifEmpty { getFallbackAnimeList() })
    }.flowOn(Dispatchers.IO)

    override fun getPopular(): Flow<List<Anime>> = flow {
        val list = aniListApi.getPopular()
        emit(list.ifEmpty { getFallbackAnimeList() })
    }.flowOn(Dispatchers.IO)

    override fun getSeasonal(): Flow<List<Anime>> = flow {
        val list = aniListApi.getSeasonal()
        emit(list.ifEmpty { getFallbackAnimeList() })
    }.flowOn(Dispatchers.IO)

    override fun getAiringToday(): Flow<List<AiringAnime>> = flow {
        val list = aniListApi.getAiringToday()
        emit(list.ifEmpty { getFallbackAiringList() })
    }.flowOn(Dispatchers.IO)

    override fun getTopRated(): Flow<List<Anime>> = flow {
        val list = aniListApi.getTopRated()
        emit(list.ifEmpty { getFallbackAnimeList() })
    }.flowOn(Dispatchers.IO)

    override fun getUpcoming(): Flow<List<Anime>> = flow {
        val list = aniListApi.getUpcoming()
        emit(list.ifEmpty { getFallbackAnimeList() })
    }.flowOn(Dispatchers.IO)

    override fun getRecentlyUpdated(): Flow<List<Anime>> = flow {
        val list = aniListApi.getRecentlyUpdated()
        emit(list.ifEmpty { getFallbackAnimeList() })
    }.flowOn(Dispatchers.IO)

    override fun getActionAnime(): Flow<List<Anime>> = flow {
        val list = aniListApi.getAnimeByGenre("Action")
        emit(list.ifEmpty { getFallbackAnimeList() })
    }.flowOn(Dispatchers.IO)

    override fun getRomanceAnime(): Flow<List<Anime>> = flow {
        val list = aniListApi.getAnimeByGenre("Romance")
        emit(list.ifEmpty { getFallbackAnimeList() })
    }.flowOn(Dispatchers.IO)

    override fun getAnimeByGenre(genre: String): Flow<List<Anime>> = flow {
        val list = aniListApi.getAnimeByGenre(genre)
        emit(list.ifEmpty { getFallbackAnimeList() })
    }.flowOn(Dispatchers.IO)

    override fun searchAnime(query: String, page: Int): Flow<SearchPage> = flow {
        val searchPage = aniListApi.searchAnime(query, page)
        val list = if (searchPage.results.isEmpty() && page == 1) {
            getFallbackAnimeList()
        } else {
            searchPage.results
        }
        emit(searchPage.copy(results = list))
    }.flowOn(Dispatchers.IO)

    override fun getAnimeDetail(id: Int): Flow<Anime?> = flow {
        val detail = aniListApi.getAnimeDetail(id)
        emit(detail ?: getFallbackAnimeList().find { it.id == id } ?: getFallbackAnimeList().first())
    }.flowOn(Dispatchers.IO)

    private fun cleanTitleForSearch(title: String): String {
        return title
            .replace(Regex("(?i):.*"), "")
            .replace(Regex("(?i)Season\\s*\\d+"), "")
            .replace(Regex("(?i)Part\\s*\\d+"), "")
            .replace(Regex("(?i)TV"), "")
            .replace(Regex("(?i)Uncensored"), "")
            .replace(Regex("(?i)Specials?"), "")
            .trim()
    }

    override suspend fun getEpisodes(animeId: Int, title: String): List<Episode> {
        val episodesList = mutableListOf<Episode>()

        try {
            // Fetch the details from AniList first to get all title versions (Romaji & English)
            val detail = try { aniListApi.getAnimeDetail(animeId) } catch (e: Exception) { null }
            val titlesToTry = mutableListOf<String>()
            if (detail != null) {
                // Try English first since it is often matched on AniLight, then Romaji (detail.title is romaji or english)
                detail.englishTitle?.let { titlesToTry.add(it) }
                if (detail.title != detail.englishTitle) {
                    titlesToTry.add(detail.title)
                }
            } else {
                titlesToTry.add(title)
            }

            val searchTitles = mutableListOf<String>()
            for (t in titlesToTry) {
                if (!searchTitles.contains(t) && t.isNotEmpty()) {
                    searchTitles.add(t)
                }
                val cleaned = cleanTitleForSearch(t)
                if (cleaned.isNotEmpty() && !searchTitles.contains(cleaned)) {
                    searchTitles.add(cleaned)
                }
            }

            var searchResults = emptyList<ProviderSearchResult>()
            for (searchTitle in searchTitles) {
                searchResults = aniLightProvider.search(searchTitle)
                if (searchResults.isNotEmpty()) {
                    break
                }
            }

            val bestMatch = searchResults.firstOrNull()
            if (bestMatch != null) {
                val list = aniLightProvider.getEpisodeList(bestMatch.slug)
                episodesList.addAll(list)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        
        return episodesList
    }

    override suspend fun getStreamingSources(episodeId: String): EpisodeSourcesResponse {
        var response: EpisodeSourcesResponse? = null
        if (episodeId.startsWith("anilight:")) {
            try {
                val sources = aniLightProvider.getStreamUrl(episodeId)
                val filteredSources = sources.sources.filter { !AdBlocker.shouldBlock(it.url) }
                if (filteredSources.isNotEmpty()) {
                    response = sources.copy(sources = filteredSources)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        if (response != null) {
            return response
        }

        return EpisodeSourcesResponse(
            sources = listOf(
                StreamingSource(
                    url = "https://www.w3schools.com/html/mov_bbb.mp4",
                    quality = "1080p (Fallback)",
                    isM3U8 = false
                )
            ),
            subtitles = listOf(
                SubtitleTrack(
                    url = "https://raw.githubusercontent.com/run-to-git/vtt-subtitles/main/english.vtt",
                    lang = "en",
                    label = "English"
                )
            )
        )
    }

    private fun getFallbackAnimeList(): List<Anime> {
        return listOf(
            Anime(
                id = 1535,
                title = "Death Note",
                coverImage = "https://s4.anilist.co/file/anilistcdn/media/anime/cover/large/nx1535-7X1VDQ5fa9bc.jpg",
                bannerImage = "https://picsum.photos/1920/1080?random=1",
                description = "A high school student discovers a supernatural notebook that grants him the ability to kill anyone whose name and face he knows.",
                episodes = 37,
                averageScore = 86,
                genres = listOf("Action", "Mystery", "Psychological", "Supernatural", "Thriller"),
                studioName = "Madhouse"
            ),
            Anime(
                id = 21,
                title = "One Piece",
                coverImage = "https://s4.anilist.co/file/anilistcdn/media/anime/cover/large/bx21-46G9t3k0W68D.png",
                bannerImage = "https://picsum.photos/1920/1080?random=2",
                description = "Monkey D. Luffy refuses to let anyone or anything stand in the way of his quest to become the king of all pirates.",
                episodes = 1100,
                averageScore = 88,
                genres = listOf("Action", "Adventure", "Comedy", "Fantasy"),
                studioName = "Toei Animation"
            ),
            Anime(
                id = 16498,
                title = "Attack on Titan",
                coverImage = "https://s4.anilist.co/file/anilistcdn/media/anime/cover/large/bx16498-m5pewa1otmNj.png",
                bannerImage = "https://picsum.photos/1920/1080?random=3",
                description = "Humans fight for survival against giant man-eating humanoids called Titans behind massive walls.",
                episodes = 75,
                averageScore = 90,
                genres = listOf("Action", "Drama", "Fantasy", "Mystery"),
                studioName = "MAPPA"
            )
        )
    }

    private fun getFallbackAiringList(): List<AiringAnime> {
        val now = System.currentTimeMillis() / 1000
        return listOf(
            AiringAnime(
                mediaId = 21,
                title = "One Piece",
                coverImageUrl = "https://s4.anilist.co/file/anilistcdn/media/anime/cover/large/bx21-46G9t3k0W68D.png",
                airingAt = now + 7200,
                episode = 1112
            )
        )
    }

    override suspend fun checkUpdates(): AppUpdateInfo? {
        return try {
            client.get(com.example.aniflow.utils.UpdateConfig.UPDATE_JSON_URL + "?t=${System.currentTimeMillis()}").body()
        } catch (e: Exception) {
            android.util.Log.e("DefaultAnimeRepository", "Failed to check update", e)
            null
        }
    }
}

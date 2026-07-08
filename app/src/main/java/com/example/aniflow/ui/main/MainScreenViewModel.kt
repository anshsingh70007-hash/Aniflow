package com.example.aniflow.ui.main

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.aniflow.data.*
import com.example.aniflow.data.model.*
import com.example.aniflow.data.repository.AnimeRepository
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.joinAll

class MainScreenViewModel(
    private val repository: AnimeRepository,
    private val watchlistStore: WatchlistStore,
    private val watchHistoryStore: WatchHistoryStore,
    private val context: android.content.Context
) : ViewModel() {

    private val settingsStore = SettingsStore(context)

    private val _currentTab = MutableStateFlow(0)
    val currentTab = _currentTab.asStateFlow()

    private val _trending = MutableStateFlow<List<Anime>>(emptyList())
    val trending = _trending.asStateFlow()

    private val _popular = MutableStateFlow<List<Anime>>(emptyList())
    val popular = _popular.asStateFlow()

    private val _seasonal = MutableStateFlow<List<Anime>>(emptyList())
    val seasonal = _seasonal.asStateFlow()

    private val _airingToday = MutableStateFlow<List<AiringAnime>>(emptyList())
    val airingToday = _airingToday.asStateFlow()

    private val _topRated = MutableStateFlow<List<Anime>>(emptyList())
    val topRated = _topRated.asStateFlow()

    private val _upcoming = MutableStateFlow<List<Anime>>(emptyList())
    val upcoming = _upcoming.asStateFlow()

    private val _recentlyUpdated = MutableStateFlow<List<Anime>>(emptyList())
    val recentlyUpdated = _recentlyUpdated.asStateFlow()

    private val _actionAnime = MutableStateFlow<List<Anime>>(emptyList())
    val actionAnime = _actionAnime.asStateFlow()

    private val _romanceAnime = MutableStateFlow<List<Anime>>(emptyList())
    val romanceAnime = _romanceAnime.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery = _searchQuery.asStateFlow()

    private val _searchResults = MutableStateFlow<List<Anime>>(emptyList())
    val searchResults = _searchResults.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    val isLoading = _isLoading.asStateFlow()

    private val _watchlist = MutableStateFlow<List<Anime>>(emptyList())
    val watchlist = _watchlist.asStateFlow()

    private val _history = MutableStateFlow<List<WatchHistoryEntry>>(emptyList())
    val history = _history.asStateFlow()

    private val _updateInfo = MutableStateFlow<AppUpdateInfo?>(null)
    val updateInfo = _updateInfo.asStateFlow()

    init {
        loadData()
        observeWatchlist()
        observeHistory()
        setupSearchDebounce()
        checkForUpdates()
        startPeriodicRefresh()
    }

    private fun startPeriodicRefresh() {
        viewModelScope.launch {
            while (true) {
                kotlinx.coroutines.delay(15 * 60 * 1000L) // 15 minutes
                try {
                    val (airing, recent) = repository.refreshSchedule()
                    _airingToday.value = airing
                    _recentlyUpdated.value = recent
                    android.util.Log.d("MainScreenViewModel", "Periodic schedule refresh successful: ${airing.size} airing, ${recent.size} recent")
                } catch (e: Exception) {
                    android.util.Log.e("MainScreenViewModel", "Periodic schedule refresh failed", e)
                }
            }
        }
    }


    fun dismissUpdate() {
        _updateInfo.value = null
    }

    private fun checkForUpdates() {
        viewModelScope.launch {
            val autoCheckEnabled = settingsStore.checkUpdatesStartup.first()
            if (!autoCheckEnabled) return@launch

            // Wait for network connection to establish
            kotlinx.coroutines.delay(3000L)

            var retries = 5
            var info: AppUpdateInfo? = null
            while (retries > 0 && info == null) {
                try {
                    info = repository.checkUpdates()
                } catch (e: Exception) {
                    android.util.Log.w("MainScreenViewModel", "Update check failed, retries remaining: $retries", e)
                }
                if (info == null) {
                    retries--
                    if (retries > 0) {
                        kotlinx.coroutines.delay(5000L)
                    }
                }
            }

            if (info != null) {
                val currentVersionCode = try {
                    val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                        packageInfo.longVersionCode.toInt()
                    } else {
                        @Suppress("DEPRECATION")
                        packageInfo.versionCode
                    }
                } catch (e: Exception) {
                    1
                }
                android.util.Log.d("MainScreenViewModel", "checkForUpdates final: info=$info, current=$currentVersionCode")
                if (info.versionCode > currentVersionCode && !info.silentUpdate) {
                    _updateInfo.value = info
                }
            } else {
                android.util.Log.e("MainScreenViewModel", "Update check failed after all retries")
            }
        }
    }

    private fun observeHistory() {
        viewModelScope.launch {
            watchHistoryStore.historyFlow.collect { list ->
                _history.value = list
            }
        }
    }

    private fun observeWatchlist() {
        viewModelScope.launch {
            watchlistStore.watchlistFlow.collect { list ->
                _watchlist.value = list
            }
        }
    }

    private val _selectedGenre = MutableStateFlow<String?>(null)
    val selectedGenre = _selectedGenre.asStateFlow()

    private val _hasNextPage = MutableStateFlow(false)
    val hasNextPage = _hasNextPage.asStateFlow()

    private val _isSearchLoading = MutableStateFlow(false)
    val isSearchLoading = _isSearchLoading.asStateFlow()

    private var currentPage = 1

    @OptIn(FlowPreview::class)
    private fun setupSearchDebounce() {
        viewModelScope.launch {
            _searchQuery
                .debounce(300)
                .distinctUntilChanged()
                .collectLatest { query ->
                    if (query.length >= 2) {
                        _selectedGenre.value = null // clear genre when typing query
                        currentPage = 1
                        _hasNextPage.value = false
                        performSearch(query, 1, reset = true)
                    } else if (_selectedGenre.value == null) {
                        _searchResults.value = emptyList()
                        _hasNextPage.value = false
                        currentPage = 1
                    }
                }
        }
    }

    private suspend fun performSearch(query: String, page: Int, reset: Boolean) {
        if (_isSearchLoading.value && reset) return
        _isSearchLoading.value = true
        try {
            repository.searchAnime(query, page).collect { searchPage ->
                if (reset) {
                    _searchResults.value = searchPage.results
                } else {
                    _searchResults.value = _searchResults.value + searchPage.results
                }
                _hasNextPage.value = searchPage.hasNextPage
                currentPage = searchPage.currentPage
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            _isSearchLoading.value = false
        }
    }

    fun loadNextSearchPage() {
        val query = _searchQuery.value
        if (query.length < 2 || !_hasNextPage.value || _isSearchLoading.value) return
        viewModelScope.launch {
            performSearch(query, currentPage + 1, reset = false)
        }
    }

    private fun loadData() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                // Critical path: load in two batches to avoid rate limiting
                // Batch 1: Trending + Airing (most visible)
                val batch1 = listOf(
                    launch { repository.getTrending().catch { it.printStackTrace() }.collect { _trending.value = it } },
                    launch { repository.getAiringToday().catch { it.printStackTrace() }.collect { _airingToday.value = it } }
                )
                batch1.forEach { it.join() }

                // Batch 2: Popular + Seasonal
                val batch2 = listOf(
                    launch { repository.getPopular().catch { it.printStackTrace() }.collect { _popular.value = it } },
                    launch { repository.getSeasonal().catch { it.printStackTrace() }.collect { _seasonal.value = it } }
                )
                batch2.forEach { it.join() }
                _isLoading.value = false // Let user interact early!

                // If trending still looks like fallback data, retry once after a delay
                if (_trending.value.size <= 3 && _trending.value.firstOrNull()?.id == 1535) {
                    android.util.Log.w("MainScreenViewModel", "Trending appears to be fallback data, retrying...")
                    kotlinx.coroutines.delay(3000L)
                    launch { repository.getTrending().catch { it.printStackTrace() }.collect { _trending.value = it } }
                    launch { repository.getAiringToday().catch { it.printStackTrace() }.collect { _airingToday.value = it } }
                    kotlinx.coroutines.delay(1000L)
                    launch { repository.getPopular().catch { it.printStackTrace() }.collect { _popular.value = it } }
                    launch { repository.getSeasonal().catch { it.printStackTrace() }.collect { _seasonal.value = it } }
                }

                // Background loading for non-critical lists (staggered)
                kotlinx.coroutines.delay(500L)
                launch { repository.getRecentlyUpdated().catch { it.printStackTrace() }.collect { _recentlyUpdated.value = it } }
                launch { repository.getTopRated().catch { it.printStackTrace() }.collect { _topRated.value = it } }
                kotlinx.coroutines.delay(500L)
                launch { repository.getUpcoming().catch { it.printStackTrace() }.collect { _upcoming.value = it } }
                launch { repository.getActionAnime().catch { it.printStackTrace() }.collect { _actionAnime.value = it } }
                kotlinx.coroutines.delay(500L)
                launch { repository.getAnimeByGenre("Romance").catch { it.printStackTrace() }.collect { _romanceAnime.value = it } }
            } catch (e: Exception) {
                e.printStackTrace()
                _isLoading.value = false
            }
        }
    }

    fun onGenreSelected(genre: String?) {
        _selectedGenre.value = genre
        if (genre != null) {
            _searchQuery.value = "" // clear search input when selecting genre
            currentPage = 1
            _hasNextPage.value = false
            viewModelScope.launch {
                _isSearchLoading.value = true
                try {
                    repository.getAnimeByGenre(genre).collect { list ->
                        _searchResults.value = list
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                } finally {
                    _isSearchLoading.value = false
                }
            }
        } else {
            _searchResults.value = emptyList()
            _hasNextPage.value = false
            currentPage = 1
        }
    }

    fun setTab(index: Int) {
        _currentTab.value = index
    }

    fun onSearchQueryChanged(query: String) {
        _searchQuery.value = query
    }

    fun toggleWatchlist(anime: Anime) {
        viewModelScope.launch {
            if (watchlistStore.isBookmarked(anime.id)) {
                watchlistStore.removeFromWatchlist(anime.id)
            } else {
                watchlistStore.addToWatchlist(anime)
            }
        }
    }
}

package com.example.aniflow.ui.main

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.runtime.NavKey
import com.example.aniflow.Detail
import com.example.aniflow.Player
import com.example.aniflow.DeviceType
import com.example.aniflow.data.SettingsStore
import com.example.aniflow.data.WatchHistoryStore
import com.example.aniflow.data.WatchlistStore
import com.example.aniflow.data.repository.AnimeRepository
import com.example.aniflow.theme.*
import com.example.aniflow.ui.phone.*
import com.example.aniflow.ui.tv.*
import com.example.aniflow.ui.tv.components.TvSideNavRail

@Composable
fun MainScreen(
    onItemClick: (NavKey) -> Unit,
    deviceType: DeviceType,
    repository: AnimeRepository,
    modifier: Modifier = Modifier,
    watchlistStore: WatchlistStore? = null,
    watchHistoryStore: WatchHistoryStore? = null,
    settingsStore: SettingsStore? = null,
    viewModel: MainScreenViewModel = run {
        val context = LocalContext.current.applicationContext
        val watchList = watchlistStore ?: WatchlistStore(context)
        val watchHistory = watchHistoryStore ?: WatchHistoryStore(context)
        viewModel { MainScreenViewModel(repository, watchList, watchHistory, context) }
    }
) {
    val currentTab by viewModel.currentTab.collectAsStateWithLifecycle()
    val trending by viewModel.trending.collectAsStateWithLifecycle()
    val popular by viewModel.popular.collectAsStateWithLifecycle()
    val seasonal by viewModel.seasonal.collectAsStateWithLifecycle()
    val airingToday by viewModel.airingToday.collectAsStateWithLifecycle()
    val topRated by viewModel.topRated.collectAsStateWithLifecycle()
    val upcoming by viewModel.upcoming.collectAsStateWithLifecycle()
    val recentlyUpdated by viewModel.recentlyUpdated.collectAsStateWithLifecycle()
    val actionAnime by viewModel.actionAnime.collectAsStateWithLifecycle()
    val romanceAnime by viewModel.romanceAnime.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val selectedGenre by viewModel.selectedGenre.collectAsStateWithLifecycle()
    val searchResults by viewModel.searchResults.collectAsStateWithLifecycle()
    val hasNextPage by viewModel.hasNextPage.collectAsStateWithLifecycle()
    val isSearchLoading by viewModel.isSearchLoading.collectAsStateWithLifecycle()
    val watchlist by viewModel.watchlist.collectAsStateWithLifecycle()
    val history by viewModel.history.collectAsStateWithLifecycle()
    val updateInfo by viewModel.updateInfo.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()

    val context = LocalContext.current.applicationContext
    val wStore = remember { watchlistStore ?: WatchlistStore(context) }
    val hStore = remember { watchHistoryStore ?: WatchHistoryStore(context) }
    val sStore = remember { settingsStore ?: SettingsStore(context) }

    updateInfo?.let { info ->
        AlertDialog(
            onDismissRequest = { 
                if (!info.forceUpdate) {
                    viewModel.dismissUpdate()
                }
            },
            title = { Text("App Update Available", color = TextPrimary, fontWeight = FontWeight.Bold) },
            text = { 
                Column {
                    Text("Version ${info.versionName} is now available.", color = TextSecondary)
                    info.updateNotes?.let { notes ->
                        Spacer(Modifier.height(8.dp))
                        Text("Changelog: $notes", color = TextSecondary, fontSize = 12.sp)
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = { 
                        com.example.aniflow.utils.AppUpdater.downloadAndInstall(context, info.updateUrl, info.versionName)
                        if (!info.forceUpdate) {
                            viewModel.dismissUpdate()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = PrimaryAccent)
                ) {
                    Text("Download Update", color = TextPrimary)
                }
            },
            dismissButton = {
                if (!info.forceUpdate) {
                    TextButton(onClick = { viewModel.dismissUpdate() }) {
                        Text("Later", color = TextSecondary)
                    }
                }
            },
            containerColor = SurfaceCard,
            textContentColor = TextSecondary,
            titleContentColor = TextPrimary
        )
    }

    if (deviceType == DeviceType.TV) {
        Row(
            modifier = modifier
                .fillMaxSize()
                .background(PrimaryDark)
        ) {
            TvSideNavRail(
                selectedIndex = currentTab,
                items = listOf(
                    Icons.Default.Home to "Home",
                    Icons.Default.Search to "Browse",
                    Icons.Default.Favorite to "Library",
                    Icons.Default.Settings to "Settings"
                ),
                onSelect = { viewModel.setTab(it) }
            )

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .padding(horizontal = 24.dp)
            ) {
                if (isLoading) {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center), color = PrimaryAccent)
                } else {
                    when (currentTab) {
                        0 -> TvHomeScreen(
                            trending = trending,
                            popular = popular,
                            seasonal = seasonal,
                            airing = airingToday,
                            topRated = topRated,
                            upcoming = upcoming,
                            recentlyUpdated = recentlyUpdated,
                            actionAnime = actionAnime,
                            romanceAnime = romanceAnime,
                            history = history,
                            onAnimeClick = { onItemClick(Detail(it.id)) },
                            onHistoryClick = { onItemClick(Player(it.animeId, it.episodeNumber)) }
                        )
                        1 -> TvBrowseScreen(
                            query = searchQuery,
                            onQueryChange = { viewModel.onSearchQueryChanged(it) },
                            selectedGenre = selectedGenre,
                            onGenreSelect = { viewModel.onGenreSelected(it) },
                            results = searchResults,
                            hasNextPage = hasNextPage,
                            isSearchLoading = isSearchLoading,
                            onLoadMore = { viewModel.loadNextSearchPage() },
                            onAnimeClick = { onItemClick(Detail(it.id)) }
                        )
                        2 -> TvLibraryScreen(
                            watchlist = watchlist,
                            onAnimeClick = { onItemClick(Detail(it.id)) }
                        )
                        3 -> TvSettingsScreen(
                            watchlistStore = wStore,
                            watchHistoryStore = hStore,
                            settingsStore = sStore,
                            repository = repository
                        )
                    }
                }
            }
        }
    } else {
        Scaffold(
            bottomBar = {
                NavigationBar(
                    containerColor = PrimaryDarker,
                    tonalElevation = 8.dp
                ) {
                    NavigationBarItem(
                        selected = currentTab == 0,
                        onClick = { viewModel.setTab(0) },
                        icon = { Icon(Icons.Rounded.Home, contentDescription = "Home") },
                        label = { Text("Home", fontSize = 10.sp) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = PrimaryAccentLight,
                            selectedTextColor = PrimaryAccentLight,
                            unselectedIconColor = TextSecondary,
                            unselectedTextColor = TextSecondary,
                            indicatorColor = PrimaryAccent.copy(alpha = 0.2f)
                        )
                    )
                    NavigationBarItem(
                        selected = currentTab == 1,
                        onClick = { viewModel.setTab(1) },
                        icon = { Icon(Icons.Rounded.Search, contentDescription = "Browse") },
                        label = { Text("Browse", fontSize = 10.sp) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = PrimaryAccentLight,
                            selectedTextColor = PrimaryAccentLight,
                            unselectedIconColor = TextSecondary,
                            unselectedTextColor = TextSecondary,
                            indicatorColor = PrimaryAccent.copy(alpha = 0.2f)
                        )
                    )
                    NavigationBarItem(
                        selected = currentTab == 2,
                        onClick = { viewModel.setTab(2) },
                        icon = { Icon(Icons.Default.Favorite, contentDescription = "Library") },
                        label = { Text("Library", fontSize = 10.sp) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = PrimaryAccentLight,
                            selectedTextColor = PrimaryAccentLight,
                            unselectedIconColor = TextSecondary,
                            unselectedTextColor = TextSecondary,
                            indicatorColor = PrimaryAccent.copy(alpha = 0.2f)
                        )
                    )
                    NavigationBarItem(
                        selected = currentTab == 3,
                        onClick = { viewModel.setTab(3) },
                        icon = { Icon(Icons.Rounded.Settings, contentDescription = "Settings") },
                        label = { Text("Settings", fontSize = 10.sp) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = PrimaryAccentLight,
                            selectedTextColor = PrimaryAccentLight,
                            unselectedIconColor = TextSecondary,
                            unselectedTextColor = TextSecondary,
                            indicatorColor = PrimaryAccent.copy(alpha = 0.2f)
                        )
                    )
                }
            },
            containerColor = PrimaryDark,
            modifier = modifier
        ) { padding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                if (isLoading) {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center), color = PrimaryAccent)
                } else {
                    when (currentTab) {
                        0 -> PhoneHomeScreen(
                            trending = trending,
                            popular = popular,
                            seasonal = seasonal,
                            airing = airingToday,
                            topRated = topRated,
                            upcoming = upcoming,
                            recentlyUpdated = recentlyUpdated,
                            actionAnime = actionAnime,
                            romanceAnime = romanceAnime,
                            history = history,
                            onAnimeClick = { onItemClick(Detail(it.id)) },
                            onHistoryClick = { onItemClick(Player(it.animeId, it.episodeNumber)) }
                        )
                        1 -> PhoneBrowseScreen(
                            query = searchQuery,
                            onQueryChange = { viewModel.onSearchQueryChanged(it) },
                            selectedGenre = selectedGenre,
                            onGenreSelect = { viewModel.onGenreSelected(it) },
                            results = searchResults,
                            hasNextPage = hasNextPage,
                            isSearchLoading = isSearchLoading,
                            onLoadMore = { viewModel.loadNextSearchPage() },
                            onAnimeClick = { onItemClick(Detail(it.id)) }
                        )
                        2 -> PhoneLibraryScreen(
                            watchlist = watchlist,
                            onAnimeClick = { onItemClick(Detail(it.id)) }
                        )
                        3 -> PhoneSettingsScreen(
                            watchlistStore = wStore,
                            watchHistoryStore = hStore,
                            settingsStore = sStore,
                            repository = repository
                        )
                    }
                }
            }
        }
    }
}

package com.example.aniflow.ui.player

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioManager
import android.view.KeyEvent
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.annotation.OptIn
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.example.aniflow.DeviceType
import com.example.aniflow.LocalDeviceType
import com.example.aniflow.data.*
import com.example.aniflow.data.model.*
import com.example.aniflow.data.repository.AnimeRepository
import com.example.aniflow.theme.*
import com.example.aniflow.ui.player.components.QualitySelector
import com.example.aniflow.ui.player.components.SubtitleSelector
import com.example.aniflow.ui.player.components.SpeedSelector
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import io.ktor.client.request.header
import io.ktor.client.request.get
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers

@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(
    animeId: Int,
    episodeNumber: Int,
    repository: AnimeRepository,
    deviceType: DeviceType,
    watchHistoryStore: WatchHistoryStore,
    onBack: () -> Unit,
    viewModel: PlayerViewModel = run {
        val context = LocalContext.current.applicationContext
        viewModel { PlayerViewModel(repository, watchHistoryStore, SettingsStore(context)) }
    }
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    
    val anime by viewModel.anime.collectAsStateWithLifecycle()
    val episodeList by viewModel.episodeList.collectAsStateWithLifecycle()
    val currentEpisodeIndex by viewModel.currentEpisodeIndex.collectAsStateWithLifecycle()
    val streamingSources by viewModel.streamingSources.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val hasError by viewModel.hasError.collectAsStateWithLifecycle()
    val errorMessage by viewModel.errorMessage.collectAsStateWithLifecycle()
    val isBuffering by viewModel.isBuffering.collectAsStateWithLifecycle()
    
    val currentPosition by viewModel.currentPosition.collectAsStateWithLifecycle()
    val totalDuration by viewModel.totalDuration.collectAsStateWithLifecycle()
    val isPlaying by viewModel.isPlaying.collectAsStateWithLifecycle()
    val playbackSpeed by viewModel.playbackSpeed.collectAsStateWithLifecycle()
    
    val selectedSource by viewModel.selectedSource.collectAsStateWithLifecycle()
    val selectedSubtitle by viewModel.selectedSubtitle.collectAsStateWithLifecycle()

    val audioManager = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    var currentVolume by remember { mutableStateOf(audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)) }
    val maxVolume = remember { audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC) }

    val httpDataSourceFactory = remember {
        androidx.media3.datasource.DefaultHttpDataSource.Factory()
            .setUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            .setAllowCrossProtocolRedirects(true)
    }

    val exoPlayer = remember {
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(1500, 50000, 500, 1500)
            .build()
        val mediaSourceFactory = androidx.media3.exoplayer.source.DefaultMediaSourceFactory(AdBlockingDataSourceFactory(httpDataSourceFactory))
        
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(mediaSourceFactory)
            .setLoadControl(loadControl)
            .setSeekBackIncrementMs(10000)
            .setSeekForwardIncrementMs(10000)
            .build()
    }

    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                viewModel.isBuffering.value = state == Player.STATE_BUFFERING
                viewModel.totalDuration.value = exoPlayer.duration.coerceAtLeast(0L)
            }
            override fun onIsPlayingChanged(playing: Boolean) {
                viewModel.isPlaying.value = playing
            }
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                viewModel.errorMessage.value = "Playback error: ${error.errorCodeName}"
                viewModel.hasError.value = true
            }
        }
        exoPlayer.addListener(listener)
        onDispose {
            exoPlayer.removeListener(listener)
        }
    }

    LaunchedEffect(animeId, episodeNumber) {
        viewModel.loadAnimeDetails(animeId, episodeNumber)
    }

    LaunchedEffect(selectedSource) {
        val source = selectedSource
        if (source != null) {
            viewModel.isBuffering.value = true
            val headers = source.headers ?: streamingSources?.headers
            if (headers != null && headers.isNotEmpty()) {
                httpDataSourceFactory.setDefaultRequestProperties(headers)
            } else {
                httpDataSourceFactory.setDefaultRequestProperties(emptyMap())
            }

            val resolvedUrl = withContext(Dispatchers.IO) {
                if (source.url.contains("proxy") || source.url.contains("anilight.live/lb")) {
                    try {
                        val client = NetworkModule.client
                        val response = client.get(source.url) {
                            headers?.forEach { (k, v) -> header(k, v) }
                        }
                        response.call.request.url.toString()
                    } catch (e: Exception) {
                        android.util.Log.e("PlayerScreen", "Failed to resolve redirect", e)
                        source.url
                    }
                } else {
                    source.url
                }
            }

            val isHls = resolvedUrl.contains(".m3u8") || resolvedUrl.contains("m3u8") || source.url.contains("proxy") || source.url.contains("anilight")
            val mediaItemBuilder = MediaItem.Builder()
                .setUri(resolvedUrl)
                .apply {
                    if (isHls) {
                        setMimeType(MimeTypes.APPLICATION_M3U8)
                    }
                }
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(episodeList.getOrNull(currentEpisodeIndex)?.name ?: "Episode")
                        .setArtist(anime?.title ?: "")
                        .build()
                )

            val subtitles = streamingSources?.subtitles ?: emptyList()
            val subtitleConfigs = subtitles.map { sub ->
                MediaItem.SubtitleConfiguration.Builder(android.net.Uri.parse(sub.url))
                    .setMimeType(MimeTypes.TEXT_VTT)
                    .setLanguage(sub.lang)
                    .setLabel(sub.label)
                    .build()
            }
            mediaItemBuilder.setSubtitleConfigurations(subtitleConfigs)

            val currentPos = exoPlayer.currentPosition
            val resumePos = if (currentPos > 0) currentPos else viewModel.getSavedProgress(animeId)

            exoPlayer.setMediaItem(mediaItemBuilder.build())
            exoPlayer.setPlaybackSpeed(playbackSpeed)
            exoPlayer.prepare()

            if (resumePos > 0) {
                exoPlayer.seekTo(resumePos)
            }
            exoPlayer.playWhenReady = true
        }
    }

    LaunchedEffect(selectedSubtitle) {
        val sub = selectedSubtitle
        val parameters = exoPlayer.trackSelectionParameters
            .buildUpon()
            .setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_TEXT, sub == null)
            .apply {
                if (sub != null) {
                    setPreferredTextLanguage(sub.lang)
                }
            }
            .build()
        exoPlayer.trackSelectionParameters = parameters
    }

    LaunchedEffect(exoPlayer) {
        while (true) {
            delay(1000)
            if (exoPlayer.isPlaying) {
                viewModel.currentPosition.value = exoPlayer.currentPosition
                viewModel.totalDuration.value = exoPlayer.duration
                viewModel.saveProgress(animeId, exoPlayer.currentPosition, exoPlayer.duration)
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            val pos = exoPlayer.currentPosition
            val dur = exoPlayer.duration
            if (pos > 0 && dur > 0) {
                viewModel.saveProgress(animeId, pos, dur)
            }
            exoPlayer.release()
        }
    }

    LaunchedEffect(deviceType) {
        if (deviceType == DeviceType.PHONE) {
            val activity = context as? ComponentActivity
            activity?.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE
        }
    }
    
    DisposableEffect(deviceType) {
        onDispose {
            if (deviceType == DeviceType.PHONE) {
                val activity = context as? ComponentActivity
                activity?.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_USER
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        if (isLoading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = PrimaryAccent)
            }
        } else if (hasError) {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(Icons.Rounded.Warning, contentDescription = "Error", tint = TertiaryAccent, modifier = Modifier.size(64.dp))
                Spacer(Modifier.height(16.dp))
                Text(text = errorMessage, color = TextPrimary, fontSize = 18.sp)
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = { viewModel.loadStreamingSourcesForIndex(currentEpisodeIndex) },
                    colors = ButtonDefaults.buttonColors(containerColor = PrimaryAccent)
                ) {
                    Text("Retry")
                }
            }
        } else {
            Box(modifier = Modifier.fillMaxSize()) {
                AndroidView(
                    factory = { ctx ->
                        PlayerView(ctx).apply {
                            player = exoPlayer
                            useController = false
                            layoutParams = FrameLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT
                            )
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )

                if (isBuffering) {
                    Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.3f)), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = PrimaryAccent, modifier = Modifier.size(48.dp))
                    }
                }

                PlayerControlsOverlay(
                    player = exoPlayer,
                    viewModel = viewModel,
                    deviceType = deviceType,
                    volume = currentVolume,
                    maxVolume = maxVolume,
                    onVolumeChange = {
                        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, it, 0)
                        currentVolume = it
                    },
                    onBack = onBack
                )
            }
        }
    }
}

@Composable
fun PlayerControlsOverlay(
    player: Player,
    viewModel: PlayerViewModel,
    deviceType: DeviceType,
    volume: Int,
    maxVolume: Int,
    onVolumeChange: (Int) -> Unit,
    onBack: () -> Unit
) {
    val anime by viewModel.anime.collectAsStateWithLifecycle()
    val episodeList by viewModel.episodeList.collectAsStateWithLifecycle()
    val currentEpisodeIndex by viewModel.currentEpisodeIndex.collectAsStateWithLifecycle()
    val streamingSources by viewModel.streamingSources.collectAsStateWithLifecycle()
    val selectedSource by viewModel.selectedSource.collectAsStateWithLifecycle()
    val selectedSubtitle by viewModel.selectedSubtitle.collectAsStateWithLifecycle()
    val currentPosition by viewModel.currentPosition.collectAsStateWithLifecycle()
    val totalDuration by viewModel.totalDuration.collectAsStateWithLifecycle()
    val isPlaying by viewModel.isPlaying.collectAsStateWithLifecycle()
    val playbackSpeed by viewModel.playbackSpeed.collectAsStateWithLifecycle()

    var controlsVisible by remember { mutableStateOf(true) }
    val focusRequester = remember { FocusRequester() }
    
    var showQualitySelector by remember { mutableStateOf(false) }
    var showSubtitleSelector by remember { mutableStateOf(false) }
    var showSpeedSelector by remember { mutableStateOf(false) }

    val playPauseFocusRequester = remember { FocusRequester() }

    LaunchedEffect(controlsVisible, isPlaying) {
        if (controlsVisible && isPlaying) {
            delay(5000)
            controlsVisible = false
        }
    }

    LaunchedEffect(controlsVisible) {
        if (controlsVisible) {
            delay(100)
            try {
                playPauseFocusRequester.requestFocus()
            } catch (e: Exception) {
                // ignore
            }
        } else {
            try {
                focusRequester.requestFocus()
            } catch (e: Exception) {
                // ignore
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .focusRequester(focusRequester)
            .focusable()
            .onKeyEvent { event ->
                if (!controlsVisible) {
                    if (event.nativeKeyEvent.action == android.view.KeyEvent.ACTION_DOWN) {
                        controlsVisible = true
                        return@onKeyEvent true
                    }
                }
                false
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { controlsVisible = !controlsVisible },
                    onDoubleTap = { offset ->
                        val halfWidth = size.width / 2
                        if (offset.x < halfWidth) {
                            player.seekTo((player.currentPosition - 10000).coerceAtLeast(0))
                        } else {
                            player.seekTo((player.currentPosition + 10000).coerceAtMost(player.duration))
                        }
                    }
                )
            }
    ) {
        AnimatedVisibility(visible = controlsVisible, enter = fadeIn(), exit = fadeOut()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Black.copy(alpha = 0.6f), Color.Transparent, Color.Black.copy(alpha = 0.8f))
                        )
                    )
            )
        }

        AnimatedVisibility(
            visible = controlsVisible,
            enter = slideInVertically { -it / 5 } + fadeIn(tween(200)),
            exit = slideOutVertically { -it / 5 } + fadeOut(tween(300))
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                // Top Bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                        .align(Alignment.TopStart),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    var isBackFocused by remember { mutableStateOf(false) }
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(20.dp))
                            .background(if (isBackFocused) PrimaryAccent else Color.Transparent)
                            .border(
                                width = if (isBackFocused) 2.dp else 0.dp,
                                color = if (isBackFocused) SecondaryAccent else Color.Transparent,
                                shape = RoundedCornerShape(20.dp)
                            )
                            .clickable { onBack() }
                            .onFocusChanged { isBackFocused = it.isFocused }
                            .padding(8.dp)
                    ) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back", tint = TextPrimary)
                    }
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text(text = anime?.title ?: "Stream Player", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        Text(text = episodeList.getOrNull(currentEpisodeIndex)?.name ?: "Episode Info", color = TextSecondary, fontSize = 14.sp)
                    }
                }

                // Center Navigation (Phone playback jump keys)
                if (deviceType == DeviceType.PHONE) {
                    Row(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .wrapContentSize(),
                        horizontalArrangement = Arrangement.spacedBy(28.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = { viewModel.playPrevEpisode() }, enabled = currentEpisodeIndex > 0) {
                            Text("|<", color = if (currentEpisodeIndex > 0) TextPrimary else TextTertiary, fontWeight = FontWeight.Bold, fontSize = 24.sp)
                        }
                        IconButton(
                            onClick = { if (player.isPlaying) player.pause() else player.play() },
                            modifier = Modifier
                                .size(72.dp)
                                .background(PrimaryAccent, RoundedCornerShape(36.dp))
                        ) {
                            if (isPlaying) {
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Box(modifier = Modifier.width(6.dp).height(24.dp).background(TextPrimary))
                                    Box(modifier = Modifier.width(6.dp).height(24.dp).background(TextPrimary))
                                }
                            } else {
                                Icon(
                                    imageVector = Icons.Rounded.PlayArrow,
                                    contentDescription = "Play",
                                    modifier = Modifier.size(48.dp),
                                    tint = TextPrimary
                                )
                            }
                        }
                        IconButton(onClick = { viewModel.playNextEpisode() }, enabled = currentEpisodeIndex < episodeList.lastIndex) {
                            Text(">|", color = if (currentEpisodeIndex < episodeList.lastIndex) TextPrimary else TextTertiary, fontWeight = FontWeight.Bold, fontSize = 24.sp)
                        }
                    }
                }

                // Bottom timing & seek layout
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 16.dp)
                        .align(Alignment.BottomCenter)
                ) {
                    Slider(
                        value = currentPosition.toFloat(),
                        onValueChange = {
                            player.seekTo(it.toLong())
                            viewModel.currentPosition.value = it.toLong()
                        },
                        valueRange = 0f..(totalDuration.toFloat().coerceAtLeast(1f)),
                        colors = SliderDefaults.colors(
                            activeTrackColor = PrimaryAccent,
                            inactiveTrackColor = SurfaceBorder,
                            thumbColor = SecondaryAccent
                        ),
                        enabled = deviceType == DeviceType.PHONE,
                        modifier = Modifier.fillMaxWidth().height(16.dp)
                    )
                    Spacer(Modifier.height(8.dp))
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "${formatTime(currentPosition)} / ${formatTime(totalDuration)}",
                            color = TextSecondary,
                            fontSize = 12.sp
                        )

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (deviceType == DeviceType.TV) {
                                TvPlayerControlItem(
                                    text = if (isPlaying) "Pause" else "Play",
                                    focusRequester = playPauseFocusRequester,
                                    onClick = { if (player.isPlaying) player.pause() else player.play() }
                                )
                                TvPlayerControlItem(
                                    text = "Prev Ep",
                                    onClick = { viewModel.playPrevEpisode() }
                                )
                                TvPlayerControlItem(
                                    text = "-10s",
                                    onClick = { player.seekTo((player.currentPosition - 10000).coerceAtLeast(0)) }
                                )
                                TvPlayerControlItem(
                                    text = "+10s",
                                    onClick = { player.seekTo((player.currentPosition + 10000).coerceAtMost(player.duration)) }
                                )
                                TvPlayerControlItem(
                                    text = "Next Ep",
                                    onClick = { viewModel.playNextEpisode() }
                                )
                                TvPlayerControlItem(
                                    text = "Quality",
                                    onClick = { showQualitySelector = true }
                                )
                                TvPlayerControlItem(
                                    text = "Subtitles",
                                    onClick = { showSubtitleSelector = true }
                                )
                                TvPlayerControlItem(
                                    text = "Speed",
                                    onClick = { showSpeedSelector = true }
                                )
                            } else {
                                TextButton(onClick = { player.seekTo((player.currentPosition - 10000).coerceAtLeast(0)) }) {
                                    Text("-10s", color = TextPrimary, fontWeight = FontWeight.Bold)
                                }
                                TextButton(onClick = { player.seekTo((player.currentPosition + 10000).coerceAtMost(player.duration)) }) {
                                    Text("+10s", color = TextPrimary, fontWeight = FontWeight.Bold)
                                }
                                TextButton(onClick = { showQualitySelector = true }) {
                                    Text("Quality", color = TextPrimary, fontWeight = FontWeight.Bold)
                                }
                                TextButton(onClick = { showSubtitleSelector = true }) {
                                    Text("Sub", color = TextPrimary, fontWeight = FontWeight.Bold)
                                }
                                TextButton(onClick = { showSpeedSelector = true }) {
                                    Text("Speed", color = TextPrimary, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showQualitySelector && streamingSources != null) {
        QualitySelector(
            sources = streamingSources!!.sources,
            selectedSource = selectedSource,
            onSelect = { viewModel.selectSource(it) },
            onDismiss = { showQualitySelector = false }
        )
    }

    if (showSubtitleSelector && streamingSources != null) {
        SubtitleSelector(
            subtitles = streamingSources!!.subtitles,
            selectedSubtitle = selectedSubtitle,
            onSelect = { viewModel.selectSubtitle(it) },
            onDismiss = { showSubtitleSelector = false }
        )
    }

    if (showSpeedSelector) {
        SpeedSelector(
            selectedSpeed = playbackSpeed,
            onSelect = {
                player.setPlaybackSpeed(it)
                viewModel.playbackSpeed.value = it
            },
            onDismiss = { showSpeedSelector = false }
        )
    }

    LaunchedEffect(Unit) {
        if (deviceType == DeviceType.TV) {
            focusRequester.requestFocus()
        }
    }
}

@Composable
fun TvPlayerControlItem(
    text: String,
    focusRequester: FocusRequester? = null,
    onClick: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }
    val modifier = Modifier
        .let { if (focusRequester != null) it.focusRequester(focusRequester) else it }
        .clip(RoundedCornerShape(8.dp))
        .background(if (isFocused) PrimaryAccent else SurfaceCard)
        .border(
            width = if (isFocused) 2.dp else 0.dp,
            color = if (isFocused) SecondaryAccent else Color.Transparent,
            shape = RoundedCornerShape(8.dp)
        )
        .clickable { onClick() }
        .onFocusChanged { isFocused = it.isFocused }
        .padding(horizontal = 16.dp, vertical = 8.dp)

    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        Text(text = text, color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 13.sp)
    }
}

@SuppressLint("DefaultLocale")
private fun formatTime(ms: Long): String {
    val totalSecs = ms / 1000
    val hours = totalSecs / 3600
    val minutes = (totalSecs % 3600) / 60
    val seconds = totalSecs % 60
    return if (hours > 0) {
        String.format("%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format("%02d:%02d", minutes, seconds)
    }
}

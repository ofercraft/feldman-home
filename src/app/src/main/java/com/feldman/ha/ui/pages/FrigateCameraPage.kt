package com.feldman.ha.ui.pages

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.compose.rememberAsyncImagePainter
import coil.request.ImageRequest
import coil.imageLoader
import androidx.compose.material.icons.filled.Close
import com.feldman.ha.api.FrigateEvent
import com.feldman.ha.api.FrigateRecordingDay
import com.feldman.ha.api.getFrigateApiUrl
import com.feldman.ha.api.provideFrigateApi
import com.feldman.ha.ui.camera.videoplayer.VideoPlayer
import kotlinx.coroutines.delay
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.runtime.staticCompositionLocalOf
import java.text.SimpleDateFormat
import java.util.Date
import java.util.TimeZone
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import com.feldman.ha.ui.camera.CameraPreviewScene
import com.feldman.ha.ui.camera.RecordingTimeline
import com.feldman.ha.api.getFrigateCameraSnapshotUrl

private const val HistoryClipDurationSeconds = 5 * 60

data class CameraDetailPreviewData(
    val events: List<FrigateEvent>,
    val summary: List<FrigateRecordingDay> = emptyList()
)

val LocalCameraDetailPreviewData = staticCompositionLocalOf<CameraDetailPreviewData?> { null }

@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class)
@Composable
fun FrigateCameraPage(
    cameraName: String,
    frigateUrl: String,
    token: String,
    tokenProvider: (suspend () -> String)? = null,
    onBack: () -> Unit,
    onFullScreenChange: (Boolean) -> Unit = {},
    scaffoldPadding: PaddingValues = PaddingValues(0.dp)
) {
    val previewData = LocalCameraDetailPreviewData.current
    var events by remember(previewData) { mutableStateOf(previewData?.events.orEmpty()) }
    var summary by remember(previewData) { mutableStateOf<List<FrigateRecordingDay>?>(previewData?.summary) }
    var summaryLoadError by remember { mutableStateOf<String?>(null) }
    var selectedEvent by remember { mutableStateOf<FrigateEvent?>(null) }
    var selectedHistoryTime by remember { mutableLongStateOf(0L) }
    var tabIndex by remember { mutableIntStateOf(0) }
    var isSummaryLoading by remember { mutableStateOf(false) }
    var summaryLoadAttempt by remember { mutableIntStateOf(0) }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val urlBase = getFrigateApiUrl(frigateUrl)
    var displayToken by remember(token) { mutableStateOf(token) }

    suspend fun latestToken(): String =
        tokenProvider?.invoke()?.takeIf { it.isNotBlank() } ?: token

    var isFullScreen by remember { mutableStateOf(false) }
    var isInPip by remember { mutableStateOf(false) }

    LaunchedEffect(isFullScreen, isInPip) {
        onFullScreenChange(isFullScreen || isInPip)
    }

    var displayTimestamp by remember { mutableLongStateOf(System.currentTimeMillis()) }

    val encodedCamera =
        remember(cameraName) { URLEncoder.encode(cameraName, StandardCharsets.UTF_8.toString()) }
    val liveUrl =
        com.feldman.ha.api.getFrigateCameraSnapshotUrl(frigateUrl, cameraName, displayTimestamp)
    val painter = rememberAsyncImagePainter(
        model = ImageRequest.Builder(LocalContext.current)
            .data(liveUrl)
            .addHeader("Authorization", "Bearer $displayToken")
            .build()
    )

    LaunchedEffect(cameraName) {
        if (previewData != null) return@LaunchedEffect
        while (true) {
            delay(1000)
            val nextTs = System.currentTimeMillis()
            val nextUrl =
                com.feldman.ha.api.getFrigateCameraSnapshotUrl(frigateUrl, cameraName, nextTs)
            val requestToken = latestToken()
            displayToken = requestToken
            val request = ImageRequest.Builder(context)
                .data(nextUrl)
                .addHeader("Authorization", "Bearer $requestToken")
                .build()
            val result = context.imageLoader.execute(request)
            android.util.Log.d("FrigateCameraPage", "Pre-fetch: ${result::class.simpleName}")
            if (result is coil.request.SuccessResult) {
                displayTimestamp = nextTs
            }
        }
    }

    LaunchedEffect(cameraName) {
        if (previewData != null) return@LaunchedEffect
        try {
            displayToken = latestToken()
            val api = provideFrigateApi(tokenProvider = { latestToken() }, baseUrl = frigateUrl)
            events = api.getEvents(cameraName, limit = 200)
        } catch (e: Exception) {
        }
    }

    LaunchedEffect(cameraName, tabIndex, summaryLoadAttempt) {
        if (previewData != null) return@LaunchedEffect
        if (tabIndex == 1 && summary == null && !isSummaryLoading) {
            isSummaryLoading = true
            summaryLoadError = null
            try {
                displayToken = latestToken()
                val api = provideFrigateApi(tokenProvider = { latestToken() }, baseUrl = frigateUrl)
                summary = api.getRecordingSummary(cameraName, TimeZone.getDefault().id)
            } catch (e: Exception) {
                summaryLoadError = e.message ?: "Failed to load recording history"
                android.util.Log.e("FrigateCameraPage", "Failed to fetch summary", e)
            } finally {
                isSummaryLoading = false
            }
        }
    }

    val primaryLiveUrl = "${urlBase}api/go2rtc/api/stream.m3u8?src=$encodedCamera"
    val fallbackLiveUrl1 = "${urlBase}live/hls/$encodedCamera/index.m3u8"
    val fallbackLiveUrl2 = "${urlBase}api/$encodedCamera/live/m3u8"
    val fallbackLiveUrl3 = "${urlBase}api/go2rtc/stream.m3u8?src=$encodedCamera"
    val fallbackLiveUrls = listOf(fallbackLiveUrl1, fallbackLiveUrl2, fallbackLiveUrl3)
    val clipUrl = when {
        selectedEvent != null -> "${urlBase}api/events/${selectedEvent!!.id}/clip.mp4"
        selectedHistoryTime > 0 -> {
            val startTs = selectedHistoryTime / 1000
            val endTs = startTs + HistoryClipDurationSeconds
            "${urlBase}api/vod/$encodedCamera/start/$startTs/end/$endTs/index.m3u8"
        }

        else -> null
    }

    val isPlayingHistory = selectedHistoryTime > 0 || selectedEvent != null

    val shouldBeFullscreen = isFullScreen || isInPip

    Scaffold(
            contentWindowInsets = WindowInsets.systemBars.only(
                WindowInsetsSides.Top + WindowInsetsSides.Horizontal
            ),
            topBar = {
                if (!shouldBeFullscreen) {
                    TopAppBar(
                        title = { Text(cameraName) },
                        navigationIcon = {
                            IconButton(onClick = onBack) {
                                Icon(
                                    Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = "Back"
                                )
                            }
                        }
                    )
                }
            }
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(if (shouldBeFullscreen) PaddingValues(0.dp) else innerPadding)
                    .padding(horizontal = if (shouldBeFullscreen) 0.dp else 16.dp)
            ) {
                Card(
                    modifier = if (shouldBeFullscreen) Modifier.fillMaxSize() else Modifier.fillMaxWidth()
                        .height(240.dp),
                    elevation = CardDefaults.cardElevation(defaultElevation = if (shouldBeFullscreen) 0.dp else 8.dp),
                    shape = if (shouldBeFullscreen) androidx.compose.ui.graphics.RectangleShape else CardDefaults.shape
                ) {
                    Box(
                        if (shouldBeFullscreen) Modifier.fillMaxSize() else Modifier.fillMaxWidth()
                            .fillMaxHeight(), contentAlignment = Alignment.Center
                    ) {
                        val isDemo = urlBase.contains("demo")
                        if (isPlayingHistory) {
                            if (isDemo) {
                                val historyImageUrl = selectedEvent?.thumbnail
                                    ?: com.feldman.ha.api.getFrigateCameraSnapshotUrl(
                                        "demo",
                                        cameraName,
                                        0
                                    )
                                Image(
                                    painter = rememberAsyncImagePainter(model = historyImageUrl),
                                    contentDescription = "Event Snapshot",
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                                Surface(
                                    color = Color.Black.copy(alpha = 0.6f),
                                    shape = MaterialTheme.shapes.small,
                                    modifier = Modifier.align(Alignment.BottomStart).padding(12.dp)
                                ) {
                                    Text(
                                        text = "Event: ${selectedEvent?.label?.replaceFirstChar { it.uppercase() } ?: "Motion Detected"}",
                                        modifier = Modifier.padding(
                                            horizontal = 8.dp,
                                            vertical = 4.dp
                                        ),
                                        color = Color.White,
                                        style = MaterialTheme.typography.labelMedium
                                    )
                                }
                            } else if (clipUrl != null) {
                                val durationMs =
                                    selectedEvent?.end_time?.let { end -> ((end - selectedEvent!!.start_time) * 1000).toLong() }
                                VideoPlayer(
                                    url = clipUrl,
                                    isFullScreen = isFullScreen,
                                    onFullscreenChange = { isFullScreen = it },
                                    onPipChange = { isInPip = it },
                                    onProvidePause = {},
                                    onFirstPlay = {},
                                    allowPlay = true,
                                    modifier = if (shouldBeFullscreen) Modifier.fillMaxSize() else Modifier.fillMaxWidth(),
                                    isInPip = isInPip,
                                    durationMs = durationMs,
                                    showTimelineControls = selectedHistoryTime == 0L
                                )
                            }
                            if (!shouldBeFullscreen) {
                                IconButton(
                                    onClick = {
                                        selectedEvent = null
                                        selectedHistoryTime = 0L
                                    },
                                    modifier = Modifier.align(Alignment.TopStart).padding(8.dp),
                                    colors = IconButtonDefaults.iconButtonColors(
                                        containerColor = Color.Black.copy(
                                            alpha = 0.5f
                                        )
                                    )
                                ) {
                                    Icon(
                                        Icons.Default.Close,
                                        contentDescription = "Close Playback",
                                        tint = Color.White
                                    )
                                }
                            }
                        } else {
                            if (previewData != null) {
                                CameraDetailPreviewFrame(
                                    cameraName = cameraName,
                                    modifier = if (shouldBeFullscreen) Modifier.fillMaxSize() else Modifier.fillMaxWidth()
                                )
                            } else if (isDemo) {
                                Image(
                                    painter = painter,
                                    contentDescription = cameraName,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                            } else {
                                VideoPlayer(
                                    url = primaryLiveUrl,
                                    fallbackUrls = fallbackLiveUrls,
                                    isFullScreen = isFullScreen,
                                    onFullscreenChange = { isFullScreen = it },
                                    onPipChange = { isInPip = it },
                                    onProvidePause = {},
                                    onFirstPlay = {},
                                    allowPlay = true,
                                    modifier = if (shouldBeFullscreen) Modifier.fillMaxSize() else Modifier.fillMaxWidth(),
                                    isLive = true,
                                    isInPip = isInPip
                                )
                            }
                            if (!shouldBeFullscreen) {
                                Surface(
                                    color = MaterialTheme.colorScheme.error,
                                    shape = MaterialTheme.shapes.small,
                                    modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)
                                ) {
                                    Text(
                                        "LIVE",
                                        modifier = Modifier.padding(
                                            horizontal = 6.dp,
                                            vertical = 2.dp
                                        ),
                                        color = MaterialTheme.colorScheme.onError,
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))

                SecondaryTabRow(
                    selectedTabIndex = tabIndex,
                    containerColor = Color.Transparent,
                    divider = {}
                ) {
                    Tab(
                        selected = tabIndex == 0,
                        onClick = { tabIndex = 0 },
                        text = { Text("Events") }
                    )
                    Tab(
                        selected = tabIndex == 1,
                        onClick = { tabIndex = 1 },
                        text = { Text("History") }
                    )
                }

                Spacer(Modifier.height(16.dp))

                Box(Modifier.weight(1f)) {
                    if (tabIndex == 0) {
                        if (events.isEmpty()) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text(
                                    "No recent events found",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        } else {
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                                contentPadding = PaddingValues(bottom = 16.dp)
                            ) {
                                items(events) { event ->
                                    EventItem(event, urlBase, displayToken, onClick = {
                                        selectedEvent = it
                                        selectedHistoryTime = 0L
                                    })
                                }
                            }
                        }
                    } else {
                        if (isSummaryLoading) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator()
                            }
                        } else if (summaryLoadError != null) {
                            Column(
                                modifier = Modifier.fillMaxSize(),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Text(
                                    "Recording history failed to load",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(Modifier.height(8.dp))
                                Button(
                                    onClick = {
                                        summary = null
                                        summaryLoadError = null
                                        isSummaryLoading = false
                                        summaryLoadAttempt++
                                    }
                                ) {
                                    Text("Retry")
                                }
                            }
                        } else {
                            RecordingTimeline(
                                summary = summary.orEmpty(),
                                events = events,
                                selectedTime = selectedHistoryTime.takeIf { it > 0 },
                                onHourSelected = {
                                    selectedHistoryTime = it
                                    selectedEvent = null
                                }
                            )
                        }
                    }
                }
            }
        }
}
@Composable
private fun CameraDetailPreviewFrame(cameraName: String, modifier: Modifier = Modifier) {
    CameraPreviewScene(
        cameraName = cameraName,
        modifier = modifier.height(220.dp),
        showLabel = true
    )
}

@Composable
fun EventItem(event: FrigateEvent, urlBase: String, token: String, onClick: (FrigateEvent) -> Unit) {
    val sdf = SimpleDateFormat("HH:mm:ss", LocalLocale.current.platformLocale)
    val startTime = sdf.format(Date((event.start_time * 1000).toLong()))
    val thumbnailUrl = if (urlBase.contains("demo") || event.thumbnail != null) {
        event.thumbnail ?: com.feldman.ha.api.getFrigateCameraSnapshotUrl("demo", event.camera, 0)
    } else {
        "${urlBase}api/events/${event.id}/thumbnail.jpg"
    }

    Card(
        modifier = Modifier.fillMaxWidth().clickable { onClick(event) },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(
            modifier = Modifier.padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Card(
                modifier = Modifier.size(80.dp),
                shape = MaterialTheme.shapes.small
            ) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(thumbnailUrl)
                        .addHeader("Authorization", "Bearer $token")
                        .crossfade(true)
                        .build(),
                    contentDescription = "Event Thumbnail",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
            Spacer(Modifier.width(12.dp))
            Column {
                Text(event.label.replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.titleMedium)
                Text(startTime, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                event.top_score?.let {
                    Text("Score: ${(it * 100).toInt()}%", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

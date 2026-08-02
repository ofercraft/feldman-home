package com.feldman.ha.ui.camera

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import coil.compose.AsyncImage
import coil.imageLoader
import coil.request.ImageRequest
import com.feldman.ha.api.getFrigateApiUrl
import com.feldman.ha.ui.camera.videoplayer.VideoPlayer
import com.feldman.motion.rememberSymbolPainter
import kotlinx.coroutines.delay
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

// ---------------------------------------------------------------------------
// Snapshot URL helpers
// ---------------------------------------------------------------------------

/** haBaseUrl is the HA API base URL ending with "/api/", e.g. "http://ha.local:8123/api/" */
internal fun cameraSnapshotUrl(
    config: CameraCardConfig,
    haBaseUrl: String,
    frigateUrl: String,
    timestamp: Long,
): String = when (config.source) {
    CameraSource.FRIGATE -> com.feldman.ha.api.getFrigateCameraSnapshotUrl(frigateUrl, config.id, timestamp)
    CameraSource.HA      -> "${haBaseUrl}camera_proxy/${config.id}?t=$timestamp"
}

// ---------------------------------------------------------------------------
// HA live snapshot (rapid 1-second refresh — pseudo-live)
// ---------------------------------------------------------------------------

@Composable
internal fun HALiveSnapshotView(
    entityId: String,
    haBaseUrl: String,
    token: String,
    tokenProvider: (suspend () -> String)? = null,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var displayTs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var displayToken by remember(token) { mutableStateOf(token) }

    suspend fun latestToken(): String =
        tokenProvider?.invoke()?.takeIf { it.isNotBlank() } ?: token

    LaunchedEffect(entityId) {
        while (true) {
            delay(1_000L)
            val nextTs = System.currentTimeMillis()
            val requestToken = latestToken()
            displayToken = requestToken
            // Pre-fetch so Coil can serve from memory cache on the next recomposition
            runCatching {
                context.imageLoader.execute(
                    ImageRequest.Builder(context)
                        .data("${haBaseUrl}camera_proxy/$entityId?t=$nextTs")
                        .addHeader("Authorization", "Bearer $requestToken")
                        .build()
                )
            }
            displayTs = nextTs
        }
    }

    Box(modifier = modifier.background(Color.Black), contentAlignment = Alignment.Center) {
        AsyncImage(
            model = ImageRequest.Builder(context)
                .data("${haBaseUrl}camera_proxy/$entityId?t=$displayTs")
                .addHeader("Authorization", "Bearer $displayToken")
                .build(),
            contentDescription = entityId,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize()
        )
    }
}

// ---------------------------------------------------------------------------
// CameraCard
// ---------------------------------------------------------------------------

@androidx.annotation.OptIn(UnstableApi::class)
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CameraCard(
    config: CameraCardConfig,
    token: String,
    tokenProvider: (suspend () -> String)? = null,
    haBaseUrl: String,
    frigateUrl: String,
    isEdit: Boolean,
    modifier: Modifier = Modifier,
    onRemove: () -> Unit = {},
    onNavigateToDetail: () -> Unit,
) {
    val context = LocalContext.current
    var showLiveSheet by remember { mutableStateOf(false) }

    // Snapshot is served from the disk cache that CameraSnapshotWorker keeps warm in the background,
    // so a freshly-opened dashboard shows a recent frame instantly. While this card is composed we
    // also refresh on the card's own (slow, per-card) cadence so an open homescreen stays current
    // even if WorkManager runs are being throttled.
    val snapshotFile = remember(config.snapshotKey) {
        CameraSnapshotStore.fileFor(context, config.snapshotKey)
    }
    val version by CameraSnapshotStore.versionFlow(context, config.snapshotKey).collectAsState()
    var displayToken by remember(token) { mutableStateOf(token) }

    suspend fun latestToken(): String =
        tokenProvider?.invoke()?.takeIf { it.isNotBlank() } ?: token

    LaunchedEffect(config.snapshotKey, config.refreshIntervalSec) {
        while (true) {
            val requestToken = latestToken()
            displayToken = requestToken
            CameraSnapshotStore.fetchAndStore(context, config, requestToken, haBaseUrl, frigateUrl)
            delay(config.refreshIntervalSec * 1000L)
        }
    }

    // Once a frame has been stored show the file; until then fall back to a direct network fetch so
    // the card is never blank on first add.
    val snapshotModel: Any = remember(version) {
        if (version > 0L && snapshotFile.exists()) snapshotFile
        else cameraSnapshotUrl(config, haBaseUrl, frigateUrl, 0L)
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .then(if (!isEdit) Modifier.clickable { showLiveSheet = true } else Modifier)
    ) {
        // ── Snapshot thumbnail ─────────────────────────────────────────────
        AsyncImage(
            model = ImageRequest.Builder(context)
                .data(snapshotModel)
                .addHeader("Authorization", "Bearer $displayToken")
                .memoryCacheKey("${config.snapshotKey}:$version")
                .crossfade(true)
                .build(),
            contentDescription = config.name,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )

        // ── Bottom gradient + name label ───────────────────────────────────
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.7f))
                    )
                )
                .padding(horizontal = 10.dp, vertical = 8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    painter = rememberSymbolPainter("videocam"),
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.85f),
                    modifier = Modifier.size(13.dp)
                )
                Spacer(Modifier.width(5.dp))
                Text(
                    text = config.name,
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (config.source == CameraSource.FRIGATE) {
                    Spacer(Modifier.width(6.dp))
                    Box(
                        modifier = Modifier
                            .background(Color(0xFFE53935).copy(alpha = 0.9f), RoundedCornerShape(3.dp))
                            .padding(horizontal = 5.dp, vertical = 1.dp)
                    ) {
                        Text("LIVE", color = Color.White, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }

        // ── Play icon hint (non-edit) ──────────────────────────────────────
        if (!isEdit) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .align(Alignment.Center)
                    .background(Color.Black.copy(alpha = 0.35f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = rememberSymbolPainter("play_arrow"),
                    contentDescription = "View live",
                    tint = Color.White,
                    modifier = Modifier.size(26.dp)
                )
            }
        }


    }

    // ── Live-view bottom sheet ─────────────────────────────────────────────
    if (showLiveSheet) {
        val sheetState = rememberBottomSheetState(initialValue = SheetValue.Expanded)
        ModalBottomSheet(
            onDismissRequest = { showLiveSheet = false },
            sheetState = sheetState,
            containerColor = Color(0xFF0D0D0D),
            dragHandle = null,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(bottom = 20.dp)
            ) {
                // Header row
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 8.dp, top = 14.dp, bottom = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        painter = rememberSymbolPainter("videocam"),
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.8f),
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = config.name,
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White,
                        modifier = Modifier.weight(1f)
                    )
                    FilledTonalButton(
                        onClick = { showLiveSheet = false; onNavigateToDetail() },
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = Color.White.copy(alpha = 0.12f),
                            contentColor = Color.White
                        )
                    ) {
                        Icon(rememberSymbolPainter("open_in_full"), null, Modifier.size(15.dp))
                        Spacer(Modifier.width(5.dp))
                        Text("Full view", style = MaterialTheme.typography.labelMedium)
                    }
                }

                // Live content
                var isFs by remember { mutableStateOf(false) }
                when (config.source) {
                    CameraSource.FRIGATE -> {
                        val encoded = URLEncoder.encode(config.id, StandardCharsets.UTF_8.toString())
                        val baseUrl = getFrigateApiUrl(frigateUrl)
                        val primaryLiveUrl = "${baseUrl}api/go2rtc/api/stream.m3u8?src=$encoded"
                        val fallbackLiveUrl1 = "${baseUrl}live/hls/$encoded/index.m3u8"
                        val fallbackLiveUrl2 = "${baseUrl}api/$encoded/live/m3u8"
                        val fallbackLiveUrl3 = "${baseUrl}api/go2rtc/stream.m3u8?src=$encoded"
                        VideoPlayer(
                            url = primaryLiveUrl,
                            fallbackUrls = listOf(fallbackLiveUrl1, fallbackLiveUrl2, fallbackLiveUrl3),
                            isFullScreen = isFs,
                            onFullscreenChange = { isFs = it },
                            onPipChange = {},
                            onProvidePause = {},
                            onFirstPlay = {},
                            allowPlay = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(16f / 9f),
                            isLive = true,
                            showTimelineControls = false,
                        )
                    }
                    CameraSource.HA -> {
                        HALiveSnapshotView(
                            entityId = config.id,
                            haBaseUrl = haBaseUrl,
                            token = token,
                            tokenProvider = tokenProvider,
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(16f / 9f)
                        )
                    }
                }
            }
        }
    }
}

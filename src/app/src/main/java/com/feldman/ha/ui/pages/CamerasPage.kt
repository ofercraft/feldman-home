package com.feldman.ha.ui.pages

import android.content.res.Configuration
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import coil.compose.rememberAsyncImagePainter
import coil.compose.AsyncImagePainter
import coil.request.ImageRequest
import coil.imageLoader
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme.colorScheme
import androidx.compose.material3.MaterialTheme.typography
import androidx.compose.runtime.staticCompositionLocalOf
import com.feldman.ha.R
import com.feldman.ha.api.provideFrigateApi
import com.feldman.ha.api.getFrigateApiUrl
import com.feldman.ha.api.getFrigateCameraSnapshotUrl
import com.feldman.ha.api.FrigateConfig
import kotlinx.coroutines.delay
import com.feldman.ha.ui.camera.CameraPreviewScene
import com.feldman.ha.ui.components.AppPageTitle
import com.feldman.ha.ui.cards.CARD_CORNER_RADIUS
import com.feldman.ha.ui.cards.cardBackgroundColor
import com.feldman.ha.ui.cards.ExpressiveCanvasSetting
import kotlin.time.Duration.Companion.milliseconds

val LocalCameraGridPreviewConfig = staticCompositionLocalOf<FrigateConfig?> { null }

/**
 * The seam where a camera's name card meets its snapshot. Kept tight so the pair still reads as
 * one card split in two, while their outer corners follow [CARD_CORNER_RADIUS] like every other
 * card in the app.
 */
private val CAMERA_CARD_JOINT_RADIUS = 8.dp

@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class)
@Composable
fun CamerasPage(
    frigateUrl: String,
    token: String,
    tokenProvider: (suspend () -> String)? = null,
    scaffoldPadding: PaddingValues = PaddingValues(0.dp),
    onShowSettings: () -> Unit = {},
    onCameraClick: (String) -> Unit
) {
    val previewConfig = LocalCameraGridPreviewConfig.current
    var config by remember(previewConfig) { mutableStateOf<FrigateConfig?>(previewConfig) }
    var error by remember { mutableStateOf<String?>(null) }
    var retryTrigger by remember { mutableIntStateOf(0) }
    val context = LocalContext.current
    var displayToken by remember(token) { mutableStateOf(token) }

    suspend fun latestToken(): String =
        tokenProvider?.invoke()?.takeIf { it.isNotBlank() } ?: token

    LaunchedEffect(frigateUrl, token, retryTrigger) {
        if (previewConfig != null) return@LaunchedEffect
        if (frigateUrl.isBlank()) {
            error = "Frigate URL not set in settings"
            return@LaunchedEffect
        }
        error = null
        try {
            displayToken = latestToken()
            val api = provideFrigateApi(tokenProvider = { latestToken() }, baseUrl = frigateUrl)
            config = api.getConfig()
        } catch (e: Exception) {
            val msg = e.localizedMessage ?: "Unknown error"
            error = if (msg.contains("setLenient") || msg.contains("malformed") || msg.contains("Unexpected character")) {
                "Received HTML instead of JSON. This often happens with Nabu Casa UI URLs."
            } else {
                msg
            }
        }
    }

    // displayTimestamp only updates AFTER the new image is cached
    var displayTimestamp by remember { mutableLongStateOf(System.currentTimeMillis()) }

    LaunchedEffect(Unit) {
        if (previewConfig != null) return@LaunchedEffect
        // Initial load
        displayTimestamp = System.currentTimeMillis()
        while (true) {
            delay(15_000.milliseconds)
            val nextTs = System.currentTimeMillis()
            // Pre-fetch ALL camera snapshots into Coil's SINGLETON cache, then swap
            val cameras = config?.cameras?.keys?.toList() ?: emptyList()
            android.util.Log.d("CamerasPage", "Pre-fetching ${cameras.size} snapshots...")
            for (cam in cameras) {
                val requestToken = latestToken()
                displayToken = requestToken
                val nextUrl = getFrigateCameraSnapshotUrl(frigateUrl, cam, nextTs)
                val request = ImageRequest.Builder(context)
                    .data(nextUrl)
                    .addHeader("Authorization", "Bearer $requestToken")
                    .build()
                val result = context.imageLoader.execute(request)
                android.util.Log.d("CamerasPage", "Pre-fetch $cam: ${result::class.simpleName}")
            }
            android.util.Log.d("CamerasPage", "Pre-fetch done, swapping displayTimestamp to $nextTs")
            // Only swap the displayed timestamp after images are cached
            displayTimestamp = nextTs
        }
    }

    // Same expressive treatment as the home page: the tinted canvas is the Scaffold's own
    // background and shows through a transparent top bar, with the content riding on an inset,
    // top-rounded surface so the tint stays visible down both sides.
    val isExpressiveCanvas = ExpressiveCanvasSetting.isEnabled(context)
    // The app's card surface rather than a plain surfaceContainer: against the page's
    // surfaceContainerLow those two are nearly the same tone in dark theme, which left the camera
    // name strip blending into the background. This also matches the dashboard's cards.
    val cameraCardColor = cardBackgroundColor()
    val primaryContainerColor = MaterialTheme.colorScheme.primaryContainer
    val expressiveCanvas = remember(primaryContainerColor) {
        Brush.verticalGradient(
            colors = listOf(
                primaryContainerColor.copy(alpha = 0.4f),
                primaryContainerColor.copy(alpha = 0.4f)
            )
        )
    }

    Scaffold(
        modifier = if (isExpressiveCanvas) Modifier.background(expressiveCanvas) else Modifier,
        containerColor = if (isExpressiveCanvas) Color.Transparent else MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets.systemBars.only(
            WindowInsetsSides.Top + WindowInsetsSides.Horizontal
        ),
        topBar = {
            TopAppBar(
                // Shared with HomeAssistantTopBar so the two pages read as one app, and so both
                // drop the display face together when expressive design is off.
                title = { AppPageTitle("Cameras") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = if (isExpressiveCanvas) {
                        Color.Transparent
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerHigh
                    }
                ),
                actions = {
                    FilledIconButton(onClick = onShowSettings) {
                        Icon(
                            painterResource(R.drawable.ic_settings),
                            contentDescription = "Settings"
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = innerPadding.calculateTopPadding())
                .then(
                    if (isExpressiveCanvas) {
                        Modifier
                            .padding(horizontal = 12.dp)
                            .clip(RoundedCornerShape(topStart = 44.dp, topEnd = 44.dp))
                    } else Modifier
                ),
            color = if (isExpressiveCanvas) MaterialTheme.colorScheme.surfaceContainerLow else Color.Transparent
        ) {
        Box(Modifier.fillMaxSize()) {
            if (error != null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(16.dp)) {
                        Text("Error: $error", color = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.height(16.dp))
                        Button(onClick = { retryTrigger++ }) {
                            Text("Retry")
                        }
                    }
                }
            } else if (config == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                val cameras = config!!.cameras.keys.toList()
                if (cameras.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("No cameras found")
                    }
                } else {
                    val columns = if (
                        LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
                    ) 2 else 1
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(columns),
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            start = 12.dp,
                            end = 12.dp,
                            top = 12.dp,
                            bottom = 12.dp + PAGE_BOTTOM_SPACING + scaffoldPadding.calculateBottomPadding()
                        ),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        items(cameras, key = { it }) { cameraName ->
                            val displayName = config!!.cameras[cameraName]?.name ?: cameraName
                            val snapshotUrl = getFrigateCameraSnapshotUrl(frigateUrl, cameraName, displayTimestamp)

                            Column(modifier = Modifier.fillMaxWidth()) {
                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(
                                        topStart = CARD_CORNER_RADIUS,
                                        topEnd = CARD_CORNER_RADIUS,
                                        bottomStart = CAMERA_CARD_JOINT_RADIUS,
                                        bottomEnd = CAMERA_CARD_JOINT_RADIUS
                                    ),
                                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                                    colors = CardDefaults.cardColors(containerColor = cameraCardColor)
                                ) {
                                    Text(
                                        text = displayName,
                                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                        style = typography.titleMedium,
                                        color = colorScheme.onSurface
                                    )
                                }

                                Spacer(Modifier.height(2.dp))

                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(200.dp)
                                        .clickable { onCameraClick(cameraName) },
                                    shape = RoundedCornerShape(
                                        topStart = CAMERA_CARD_JOINT_RADIUS,
                                        topEnd = CAMERA_CARD_JOINT_RADIUS,
                                        bottomStart = CARD_CORNER_RADIUS,
                                        bottomEnd = CARD_CORNER_RADIUS
                                    ),
                                    colors = CardDefaults.cardColors(containerColor = cameraCardColor),
                                    elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
                                ) {
                                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                        if (previewConfig != null) {
                                            CameraPreviewScene(
                                                cameraName = cameraName,
                                                modifier = Modifier.fillMaxSize()
                                            )
                                        } else {
                                            var hasLoaded by remember { mutableStateOf(false) }
                                            val painter = rememberAsyncImagePainter(
                                                model = ImageRequest.Builder(context)
                                                    .data(snapshotUrl)
                                                    .addHeader("Authorization", "Bearer $displayToken")
                                                    .build()
                                            )

                                            if (painter.state is AsyncImagePainter.State.Success) {
                                                hasLoaded = true
                                            }

                                            Image(
                                                painter = painter,
                                                contentDescription = cameraName,
                                                contentScale = ContentScale.Crop,
                                                modifier = Modifier.fillMaxSize()
                                            )

                                            if (!hasLoaded && painter.state is AsyncImagePainter.State.Loading) {
                                                CircularWavyProgressIndicator(modifier = Modifier.size(48.dp))
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        }
    }
}

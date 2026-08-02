package com.feldman.ha.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.Icons
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.imageLoader
import coil.request.ImageRequest
import com.feldman.ha.data.HAEntity
import kotlinx.coroutines.delay
import com.feldman.ha.ui.camera.CameraPreviewScene

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeAssistantCameraPage(
    entityId: String,
    haBaseUrl: String,
    token: String,
    tokenProvider: (suspend () -> String)? = null,
    haEntity: HAEntity?,
    onBack: () -> Unit,
    onFullScreenChange: (Boolean) -> Unit = {},
) {
    val context = LocalContext.current
    val isPreview = LocalInspectionMode.current
    val cameraName = haEntity?.attributes?.get("friendly_name")?.toString() ?: entityId
    val cameraState = haEntity?.state?.replaceFirstChar { it.uppercase() } ?: ""

    var displayTs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var displayToken by remember(token) { mutableStateOf(token) }

    suspend fun latestToken(): String =
        tokenProvider?.invoke()?.takeIf { it.isNotBlank() } ?: token

    LaunchedEffect(entityId) {
        if (isPreview) return@LaunchedEffect
        while (true) {
            delay(1_000L)
            val nextTs = System.currentTimeMillis()
            val requestToken = latestToken()
            displayToken = requestToken
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

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        if (isPreview) {
            CameraPreviewScene(
                cameraName = cameraName,
                modifier = Modifier
                    .fillMaxSize()
                    .align(Alignment.Center),
                showLabel = true
            )
        } else {
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data("${haBaseUrl}camera_proxy/$entityId?t=$displayTs")
                    .addHeader("Authorization", "Bearer $displayToken")
                    .build(),
                contentDescription = cameraName,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().align(Alignment.Center)
            )
        }

        // Top bar overlay
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopStart)
                .background(Color.Black.copy(alpha = 0.4f))
                .statusBarsPadding()
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = Color.White
                )
            }
            Column(modifier = Modifier.weight(1f).padding(start = 4.dp)) {
                Text(
                    text = cameraName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White
                )
                if (cameraState.isNotBlank()) {
                    Text(
                        text = cameraState,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.7f)
                    )
                }
            }
        }
    }
}

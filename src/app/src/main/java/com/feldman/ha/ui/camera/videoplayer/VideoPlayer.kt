@file:Suppress("AssignedValueIsNeverRead")

package com.feldman.ha.ui.camera.videoplayer

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import com.feldman.ha.ui.camera.videoplayer.controller.rememberMediaController
import androidx.core.net.toUri
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch


@OptIn(UnstableApi::class)
@Composable
fun VideoPlayer(
    url: String,
    isFullScreen: Boolean,
    onFullscreenChange: (Boolean) -> Unit,
    onPipChange: (Boolean) -> Unit,
    onProvidePause: (suspend () -> Unit) -> Unit,
    onFirstPlay: () -> Unit,
    allowPlay: Boolean,
    modifier: Modifier = Modifier,
    bannerImageUrl: String? = null,
    isLive: Boolean = false,
    isInPip: Boolean = false,
    durationMs: Long? = null,
    showTimelineControls: Boolean = true,
    fallbackUrls: List<String> = emptyList()
) {
    val context = LocalContext.current

    val controller = rememberMediaController(context)
    val scope = rememberCoroutineScope()

    if (controller == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    val exo: Player = controller

    BackHandler(enabled = isFullScreen) {
        onFullscreenChange(false)
    }
    var blockFirstPlay by remember { mutableStateOf(!allowPlay) }

    LaunchedEffect(allowPlay) {
        if (allowPlay) {
            blockFirstPlay = false
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            exo.stop()
            exo.clearMediaItems()
        }
    }

    exo.addListener(object : Player.Listener {
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (playWhenReady && blockFirstPlay) {
                exo.playWhenReady = false
                onFirstPlay()
            }
        }
    })

    if (url.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    val allUrls = remember(url, fallbackUrls) {
        (listOf(url) + fallbackUrls).distinct().filter { it.isNotBlank() }
    }
    var activeUrlIndex by remember(url) { mutableIntStateOf(0) }
    val effectiveUrl = allUrls.getOrElse(activeUrlIndex) { url }

    val streamUrl = remember(effectiveUrl) {
        if (effectiveUrl.startsWith("https://stream.mux.com/")
            && !effectiveUrl.endsWith(".m3u8") && !effectiveUrl.endsWith(".mp4")) "$effectiveUrl.m3u8" else effectiveUrl
    }

    LaunchedEffect(streamUrl) {
        val mediaItemBuilder = MediaItem.Builder()
            .setUri(streamUrl)
        
        if (streamUrl.contains(".m3u8")) {
            mediaItemBuilder.setMimeType(MimeTypes.APPLICATION_M3U8)
            if (isLive) {
                mediaItemBuilder.setLiveConfiguration(
                    MediaItem.LiveConfiguration.Builder()
                        .setMaxPlaybackSpeed(1.04f)
                        .build()
                )
            }
        }

        bannerImageUrl?.takeIf { it.isNotBlank() }?.let { artUrl ->
            val metadata = MediaMetadata.Builder()
                .setArtworkUri(artUrl.toUri())
                .build()
            mediaItemBuilder.setMediaMetadata(metadata)
        }

        val mediaItem = mediaItemBuilder.build()

        val listener = object : Player.Listener {
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                super.onPlayerError(error)
                android.util.Log.e("VideoPlayer", "Playback error for $streamUrl: ${error.message}", error)
                val cause = error.cause
                val is404 = cause is androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException && cause.responseCode == 404

                if (is404 && activeUrlIndex < allUrls.size - 1) {
                    android.util.Log.i("VideoPlayer", "404 encountered for $streamUrl. Trying fallback: ${allUrls[activeUrlIndex + 1]}")
                    activeUrlIndex += 1
                } else {
                    scope.launch {
                        delay(3000)
                        exo.setMediaItem(mediaItem)
                        exo.prepare()
                        if (isLive) {
                            exo.seekToDefaultPosition()
                        }
                        exo.play()
                    }
                }
            }
        }
        
        try {
            exo.addListener(listener)
            exo.setMediaItem(mediaItem)
            exo.prepare()
            exo.playWhenReady = true
            
            // Disable audio for clips — Frigate event clips have broken audio timestamps
            if (!isLive) {
                exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
                    .setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_AUDIO, true)
                    .build()
            }
            
            // Suspend until streamUrl changes or composition is disposed
            kotlinx.coroutines.awaitCancellation()
        } finally {
            exo.removeListener(listener)
        }
    }



    LaunchedEffect(Unit) {
        onProvidePause {
            exo.pause()
            exo.stop()
            exo.clearVideoSurface()
            exo.clearMediaItems()
        }
    }


    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                exo.pause()
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(obs)
        }
    }

    VideoPlayerContent(
        exoPlayer = exo,
        modifier = if (isFullScreen || isInPip) {
            modifier.fillMaxSize().background(Color.Black)
        } else {
            modifier.fillMaxWidth()
        },
        isFullScreen = isFullScreen,
        onToggleFullscreen = { onFullscreenChange(it) },
        onPipChange = onPipChange,
        isLive = isLive,
        providedDurationMs = durationMs,
        showTimelineControls = showTimelineControls
    )
}

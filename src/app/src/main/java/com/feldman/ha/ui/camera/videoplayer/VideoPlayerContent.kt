package com.feldman.ha.ui.camera.videoplayer

import android.content.pm.ActivityInfo
import android.util.Rational
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
import androidx.media3.ui.PlayerView
import com.feldman.ha.MainActivity
import com.feldman.ha.R
import com.feldman.ha.ui.camera.videoplayer.utils.SeekDirection
import com.feldman.ha.ui.camera.videoplayer.utils.SeekFeedback
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

private fun clampPipAspectRatio(ar: Float): Float {
    val min = 1f / 2.39f
    val max = 2.39f
    return ar.coerceIn(min, max)
}

@OptIn(UnstableApi::class)
@kotlin.OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideoPlayerContent(
    exoPlayer: Player,
    modifier: Modifier = Modifier,
    isFullScreen: Boolean,
    onToggleFullscreen: (Boolean) -> Unit,
    onPipChange: (Boolean) -> Unit,
    isLive: Boolean = false,
    providedDurationMs: Long? = null,
    showTimelineControls: Boolean = true
) {

    val context = LocalContext.current
    val activity = remember(context) { context as? MainActivity }
    val window = activity?.window

    val playerViewRef = remember { mutableStateOf<PlayerView?>(null) }



    var duration by remember { mutableLongStateOf(0L) }
    var position by remember { mutableLongStateOf(0L) }
    var controlsVisible by remember { mutableStateOf(true) }
    var lastInteractionTime by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var isScrubbing by remember { mutableStateOf(false) }

    var videoAspectRatio by remember { mutableFloatStateOf(16f / 9f) }

    val pipRatio = remember(videoAspectRatio) {
        val arRaw = videoAspectRatio
            .takeIf { it.isFinite() && it > 0f }
            ?: (16f / 9f)

        val ar = clampPipAspectRatio(arRaw)

        // width/height = ar
        val h = 1000
        val w = (ar * h).roundToInt().coerceAtLeast(1)
        Rational(w, h)
    }

    var seekFeedback by remember { mutableStateOf<SeekFeedback?>(null) }
    var seekClusterDirection by remember { mutableStateOf<SeekDirection?>(null) }
    var lastSeekClusterTapTime by remember { mutableLongStateOf(0L) }
    val seekClusterTimeoutMs = 600L
    val isVertical by remember(videoAspectRatio) { mutableStateOf(videoAspectRatio < 1f) }
    var sliderValue by remember { mutableFloatStateOf(0f) }
    var scrubPosition by remember { mutableLongStateOf(0L) }

    fun jumpBy10s(direction: SeekDirection, isRightSide: Boolean) {
        android.util.Log.d("VideoPlayerContent", "jumpBy10s: direction=$direction, currentPos=${exoPlayer.currentPosition}, duration=${exoPlayer.duration}")
        
        if (direction == SeekDirection.Forward) {
            exoPlayer.seekForward()
        } else {
            exoPlayer.seekBack()
        }

        seekFeedback = SeekFeedback(
            direction = direction,
            amountMs = 10_000L,
            isRightSide = isRightSide
        )

        controlsVisible = true
        lastInteractionTime = System.currentTimeMillis()
    }


    LaunchedEffect(seekFeedback) {
        if (seekFeedback != null) {
            delay(600)
            seekFeedback = null
        }
    }

    LaunchedEffect(exoPlayer) {
        exoPlayer.addListener(
            object : Player.Listener {
                override fun onVideoSizeChanged(videoSize: VideoSize) {
                    if (videoSize.width > 0 && videoSize.height > 0) {
                        videoAspectRatio = videoSize.width.toFloat() / videoSize.height.toFloat()
                    }
                }
            }
        )
    }

    LaunchedEffect(exoPlayer) {
        while (true) {
            if (!isScrubbing) {
                val playerDuration = exoPlayer.duration
                duration = if (playerDuration > 0) playerDuration else (providedDurationMs ?: 0L)
                position = exoPlayer.currentPosition
                
                if (System.currentTimeMillis() % 2000 < 250) { // Log every ~2s
                    android.util.Log.d("VideoPlayerContent", "Player: duration=$playerDuration (effective=$duration), pos=$position, isLive=${exoPlayer.isCurrentMediaItemLive}, isSeekable=${exoPlayer.isCurrentMediaItemSeekable}")
                }
            }
            if (!isScrubbing && duration > 0) {
                sliderValue = position.toFloat() / duration
            }
            delay(250)
        }
    }

    LaunchedEffect(lastInteractionTime) {
        delay(3000)
        if (!isScrubbing && exoPlayer.isPlaying) controlsVisible = false
    }

    LaunchedEffect(isFullScreen, videoAspectRatio) {
        val controller = window?.let { WindowCompat.getInsetsController(it, it.decorView) }

        if (isFullScreen) {
            controller?.hide(WindowInsetsCompat.Type.systemBars())

            val isVertical = videoAspectRatio < 1f
            activity?.requestedOrientation =
                if (isVertical) {
                    ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                } else {
                    ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                }
        } else {
            controller?.show(WindowInsetsCompat.Type.systemBars())
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current

    var isInPip by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        val observer = LifecycleEventObserver { _, _ ->
            val pip = activity?.isInPictureInPictureMode == true
            isInPip = pip
            onPipChange(pip)

            if (!pip) {
                controlsVisible = true
            }
        }

        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    Box(
        modifier = modifier
            .background(Color.Black)
            .fillMaxWidth()
    ) {
        val ar = videoAspectRatio
            .takeIf { it.isFinite() && it > 0f }
            ?: (16f / 9f)

        // Cap preview height so vertical videos don't become massive in a scroll page
        val maxPreviewHeight = 520.dp
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = maxPreviewHeight)
                .aspectRatio(ar, matchHeightConstraintsFirst = true)
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { offset ->
                            val now = System.currentTimeMillis()
                            val boxWidth = size.width.toFloat()
                            val third = boxWidth / 3f

                            val tappedDirection: SeekDirection? = when {
                                offset.x < third -> SeekDirection.Backward
                                offset.x > 2f * third -> SeekDirection.Forward
                                else -> null
                            }
                            if (
                                tappedDirection != null &&
                                seekClusterDirection == tappedDirection &&
                                now - lastSeekClusterTapTime < seekClusterTimeoutMs
                            ) {
                                lastSeekClusterTapTime = now
                                jumpBy10s(tappedDirection, tappedDirection == SeekDirection.Forward)
                            } else {
                                seekClusterDirection = null
                                controlsVisible = !controlsVisible
                                lastInteractionTime = now
                            }
                        },
                    )
                }
        ) {
            AndroidView(
                factory = { ctx ->
                    (LayoutInflater.from(ctx)
                        .inflate(R.layout.player_view_texture, FrameLayout(ctx), false) as PlayerView).apply {
                        player = exoPlayer
                        playerViewRef.value = this
                        useController = false
                        resizeMode = RESIZE_MODE_FIT
                    }
                },
                modifier = Modifier.matchParentSize(),
                update = {
                    it.player = exoPlayer
                    it.visibility = View.VISIBLE
                    it.resizeMode = RESIZE_MODE_FIT
                }
            )

            DisposableEffect(exoPlayer) {
                onDispose {
                }
            }

            PlayerControls(
                player = exoPlayer,
                isFullScreen = isFullScreen,
                controlsVisible = controlsVisible,
                isInPip = isInPip,
                isVertical = isVertical,
                position = position,
                duration = duration,
                pipRatio = pipRatio,
                activity = activity,
                onToggleFullscreen = { onToggleFullscreen(!isFullScreen) },
                onSeek = { direction ->
                    jumpBy10s(direction, direction == SeekDirection.Forward)
                },
                onUserInteracted = { lastInteractionTime = System.currentTimeMillis() },
                onSliderChange = { value ->
                    sliderValue = value
                    isScrubbing = true
                    scrubPosition = (duration * value).toLong()
                    lastInteractionTime = System.currentTimeMillis()
                },
                onSliderRelease = {
                    isScrubbing = false
                    exoPlayer.seekTo(scrubPosition)
                },
                sliderValue = sliderValue,
                isScrubbing = isScrubbing,
                scrubPosition = scrubPosition,
                onPipChanged = onPipChange,
                isLive = isLive,
                showTimelineControls = showTimelineControls
            )

        }
    }

}

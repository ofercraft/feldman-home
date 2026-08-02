package com.feldman.ha.ui.camera.videoplayer

import android.app.PictureInPictureParams
import android.util.Log
import android.util.Rational
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme.typography
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import com.feldman.ha.MainActivity
import com.feldman.ha.R
import com.feldman.ha.ui.camera.videoplayer.controls.FullscreenButton
import com.feldman.ha.ui.camera.videoplayer.controls.PipButton
import com.feldman.ha.ui.camera.videoplayer.controls.PlayButton
import com.feldman.ha.ui.camera.videoplayer.controls.SeekButton
import com.feldman.ha.ui.camera.videoplayer.utils.SeekDirection
import com.feldman.ha.ui.camera.videoplayer.utils.formatTime

private const val TAG_EXO = "EXO_DBG"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BoxScope.PlayerControls(
    player: Player,
    isFullScreen: Boolean,
    controlsVisible: Boolean,
    isInPip: Boolean,
    isVertical: Boolean,
    position: Long,
    duration: Long,
    pipRatio: Rational,
    activity: MainActivity?,
    onToggleFullscreen: () -> Unit,
    onSeek: (SeekDirection) -> Unit,
    onUserInteracted: () -> Unit,
    sliderValue: Float,
    onSliderChange: (Float) -> Unit,
    onSliderRelease: () -> Unit,
    isScrubbing: Boolean,
    scrubPosition: Long,
    onPipChanged: (Boolean) -> Unit,
    isLive: Boolean = false,
    showTimelineControls: Boolean = true
) {
    AnimatedVisibility(
        visible = controlsVisible && !isInPip,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = Modifier.align(Alignment.Center)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier.padding(20.dp)
        ) {
            var rewindRotation by remember { mutableStateOf(0f) }
            var forwardRotation by remember { mutableStateOf(0f) }
            val animatedRewindRotation by animateFloatAsState(
                targetValue = rewindRotation,
                animationSpec = tween(500),
                label = "rewindAnim"
            )

            val animatedForwardRotation by animateFloatAsState(
                targetValue = forwardRotation,
                animationSpec = tween(500),
                label = "forwardAnim"
            )

            if (!isLive) {
                SeekButton(
                    iconRes = R.drawable.ic_replay_10,
                    rotation = animatedRewindRotation,
                    isFullScreen = isFullScreen,
                    isVertical = isVertical,
                    onSeek = {
                        rewindRotation -= 360f
                        onSeek(SeekDirection.Backward)
                        onUserInteracted()
                    }
                )

                Spacer(Modifier.width(12.dp))
            }

            if (!isLive) {
                PlayButton(
                    isPlaying = player.isPlaying,
                    isFullScreen = isFullScreen,
                    isVertical = isVertical,
                    onToggle = {
                        if (player.isPlaying) {
                            player.pause()
                        } else {
                            player.playWhenReady = true
                        }
                        onUserInteracted()
                        Log.d(TAG_EXO, "Play toggle -> isPlaying=${player.isPlaying}, playWhenReady=${player.playWhenReady}")
                    }
                )
            }

            if (!isLive) {
                Spacer(Modifier.width(12.dp))

                SeekButton(
                    iconRes = R.drawable.ic_forward_10,
                    rotation = animatedForwardRotation,
                    isFullScreen = isFullScreen,
                    isVertical = isVertical,
                    onSeek = {
                        forwardRotation += 360f
                        onSeek(SeekDirection.Forward)
                        onUserInteracted()
                    }
                )
            }
        }
    }



    if (controlsVisible) {
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, Color.Black.copy(alpha = 0.7f))
                    )
                )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (!isInPip) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = if (isFullScreen && !isVertical) 16.dp else 0.dp)
                            .padding(horizontal = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.Bottom
                    ) {
                        val displayPosition = if (isScrubbing) scrubPosition else position

                    if (!isLive && showTimelineControls) {
                        Text(
                            text = "${formatTime(displayPosition)} / ${formatTime(duration)}",
                            color = Color.White,
                            style = typography.labelLarge,
                            modifier = Modifier.padding(bottom=4.dp)
                        )
                    } else {
                        Spacer(modifier = Modifier.width(8.dp))
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        PipButton(
                            isFullScreen = isFullScreen,
                            isVertical = isVertical,
                            onEnterPip = {
                                activity?.enterPictureInPictureMode(
                                    PictureInPictureParams.Builder()
                                        .setAspectRatio(pipRatio)
                                        .build()
                                )

                                onPipChanged(true)
                                onUserInteracted()
                            }
                        )

                        Spacer(Modifier.width(8.dp))

                        // Existing fullscreen button
                        FullscreenButton(
                            isFullScreen = isFullScreen,
                            isVertical = isVertical,
                            onToggle = {
                                onToggleFullscreen()
                                onUserInteracted()
                            }
                        )
                    }



                }



                if (!isLive && showTimelineControls) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(
                                    bottom = if (isFullScreen && !isVertical) 20.dp else 0.dp,
                                    start = if (isFullScreen && !isVertical) 16.dp else 0.dp,
                                    end = if (isFullScreen && !isVertical) 16.dp else 0.dp
                                )
                        ) {
                            VideoScrubber(
                                value = sliderValue,
                                onValueChange = { new ->
                                    onSliderChange(new)
                                    onUserInteracted()
                                },
                                onValueChangeFinished = {
                                    onSliderRelease()
                                    onUserInteracted()
                                },
                                modifier = Modifier.fillMaxWidth().height(48.dp),
                                thumbColor = Color.White,
                                activeTrackColor = Color.White,
                                inactiveTrackColor = Color.White.copy(alpha = 0.3f)
                            )
                        }
                    }

                }
            }
        }
    }
}

@Composable
fun VideoScrubber(
    value: Float,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
    modifier: Modifier = Modifier,
    thumbColor: Color = Color.White,
    activeTrackColor: Color = Color.White,
    inactiveTrackColor: Color = Color.White.copy(alpha = 0.3f)
) {
    var isDragging by remember { mutableStateOf(false) }
    var dragProgress by remember { mutableFloatStateOf(value) }
    
    val displayValue = if (isDragging) dragProgress else value

    Canvas(
        modifier = modifier
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { offset ->
                        isDragging = true
                        dragProgress = (offset.x / size.width.toFloat()).coerceIn(0f, 1f)
                        onValueChange(dragProgress)
                    },
                    onDragEnd = {
                        isDragging = false
                        onValueChangeFinished()
                    },
                    onDragCancel = {
                        isDragging = false
                        onValueChangeFinished()
                    },
                    onHorizontalDrag = { change, _ ->
                        change.consume()
                        dragProgress = (change.position.x / size.width.toFloat()).coerceIn(0f, 1f)
                        onValueChange(dragProgress)
                    }
                )
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = { offset ->
                        isDragging = true
                        dragProgress = (offset.x / size.width.toFloat()).coerceIn(0f, 1f)
                        onValueChange(dragProgress)
                        
                        if (tryAwaitRelease()) {
                            isDragging = false
                            onValueChangeFinished()
                        } else {
                            isDragging = false
                            onValueChangeFinished()
                        }
                    }
                )
            }
    ) {
        val trackHeight = 4.dp.toPx()
        val thumbRadius = 8.dp.toPx()
        
        val startY = center.y
        val startX = 0f
        val endX = size.width
        
        val activeEndX = endX * displayValue
        
        // Inactive track
        drawLine(
            color = inactiveTrackColor,
            start = Offset(startX, startY),
            end = Offset(endX, startY),
            strokeWidth = trackHeight,
            cap = StrokeCap.Round
        )
        
        // Active track
        drawLine(
            color = activeTrackColor,
            start = Offset(startX, startY),
            end = Offset(activeEndX, startY),
            strokeWidth = trackHeight,
            cap = StrokeCap.Round
        )
        
        // Thumb
        drawCircle(
            color = thumbColor,
            radius = thumbRadius,
            center = Offset(activeEndX, startY)
        )
    }
}

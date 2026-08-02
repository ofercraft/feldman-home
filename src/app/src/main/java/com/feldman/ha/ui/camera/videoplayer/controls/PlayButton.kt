package com.feldman.ha.ui.camera.videoplayer.controls

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.feldman.ha.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun PlayButton(
    isPlaying: Boolean,
    onToggle: () -> Unit,
    isFullScreen: Boolean,
    isVertical: Boolean,
    modifier: Modifier = Modifier
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scope = rememberCoroutineScope()

    val pressScale by animateFloatAsState(
        targetValue = if (pressed) 0.90f else 1f,
        animationSpec = tween(120),
        label = "ppPressScale"
    )

    var pulse by remember { mutableStateOf(false) }
    val pulseScale by animateFloatAsState(
        targetValue = if (pulse) 1.10f else 1f,
        animationSpec = tween(180),
        label = "ppPulse"
    )

    val finalScale = pressScale * pulseScale

    Surface(
        shape = RoundedCornerShape(50),
        color = if (isSystemInDarkTheme()) Color.White else Color(0xff3a3a3a),
        modifier = modifier
            .height(if (isFullScreen && !isVertical) 72.dp else 56.dp)
            .width(if (isFullScreen && !isVertical) 100.dp else 84.dp)
            .scale(finalScale)
    ) {
        IconButton(
            interactionSource = interaction,
            onClick = {
                pulse = true
                onToggle()

                scope.launch {
                    delay(180)
                    pulse = false
                }
            },
            modifier = Modifier.fillMaxSize()
        ) {
            Icon(
                painterResource(if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play_arrow),
                contentDescription = "PlayPause",
                tint = if (isSystemInDarkTheme()) Color(0xff323232) else Color(0xFFC9C9C9),
                modifier = Modifier.size(40.dp)
            )
        }
    }
}

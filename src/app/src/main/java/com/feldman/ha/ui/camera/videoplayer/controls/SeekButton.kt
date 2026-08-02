package com.feldman.ha.ui.camera.videoplayer.controls

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme.colorScheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun SeekButton(
    iconRes: Int,
    rotation: Float,
    isFullScreen: Boolean,
    isVertical: Boolean,
    onSeek: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scope = rememberCoroutineScope()

    // Press scale
    val pressScale by animateFloatAsState(
        targetValue = if (pressed) 0.88f else 1f,
        animationSpec = tween(100), label = "pressScale"
    )

    // Pulse animation
    var pulse by remember { mutableStateOf(false) }
    val pulseScale by animateFloatAsState(
        targetValue = if (pulse) 1.10f else 1f,
        animationSpec = tween(150), label = "pulseScale"
    )

    val finalScale = pressScale * pulseScale

    Surface(
        shape = RoundedCornerShape(36.dp),
        color = colorScheme.surfaceVariant,
        modifier = modifier
            .size(if (isFullScreen && !isVertical) 72.dp else 56.dp)
            .scale(finalScale)
    ) {
        IconButton(
            interactionSource = interaction,
            onClick = {
                pulse = true
                onSeek()
                scope.launch {
                    delay(150)
                    pulse = false
                }
            },
            modifier = Modifier.fillMaxSize()
        ) {
            Icon(
                painterResource(iconRes),
                contentDescription = null,
                tint = colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .size(32.dp)
                    .graphicsLayer { rotationZ = rotation }   // <-- rotation applied here
            )
        }
    }
}

package com.feldman.ha.ui.camera.videoplayer.controls

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.size
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme.colorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.feldman.ha.R

@Composable
fun FullscreenButton(
    isFullScreen: Boolean,
    isVertical: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (pressed) 0.92f else 1f,
        animationSpec = tween(120),
        label = "fsPressScale"
    )

    // Animate rotation and colors as state changes
    val rotation by animateFloatAsState(
        targetValue = if (isFullScreen) 180f else 0f,
        animationSpec = tween(300),
        label = "fsRotation"
    )

    val containerColor by animateColorAsState(
        targetValue = if (isFullScreen) colorScheme.primary
        else colorScheme.surfaceVariant,
        animationSpec = tween(250),
        label = "fsContainer"
    )
    val contentColor by animateColorAsState(
        targetValue = if (isFullScreen) colorScheme.onPrimary
        else colorScheme.onSurfaceVariant,
        animationSpec = tween(250),
        label = "fsContent"
    )
    val buttonSize = if (isFullScreen && !isVertical) 48.dp else 36.dp
    val iconSize = if (isFullScreen && !isVertical) 30.dp else 24.dp

    FilledIconButton(
        onClick = onToggle,
        interactionSource = interactionSource,
        colors = IconButtonDefaults.filledIconButtonColors(
            containerColor = containerColor,
            contentColor = contentColor
        ),
        modifier = modifier
            .size(buttonSize)
            .scale(pressScale)
    ) {
        Icon(
            painter = painterResource(if (isFullScreen) R.drawable.ic_close_fullscreen else R.drawable.ic_open_in_full),
            contentDescription = null,
            modifier = Modifier
                .size(iconSize)
                .graphicsLayer { rotationZ = rotation }
        )
    }
}

package com.feldman.ha.ui.camera.videoplayer.controls

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme.colorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.unit.dp

@Composable
fun PipButton(
    isFullScreen: Boolean,
    isVertical: Boolean,
    onEnterPip: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()

    // Press animation
    val pressScale by animateFloatAsState(
        targetValue = if (pressed) 0.92f else 1f,
        animationSpec = tween(120),
        label = "pipPressScale"
    )

    // Animate colors similar to fullscreen button
    val containerColor = colorScheme.surfaceVariant

    val contentColor = colorScheme.onSurfaceVariant

    val buttonSize = if (isFullScreen && !isVertical) 48.dp else 36.dp
    val iconSize = if (isFullScreen && !isVertical) 30.dp else 24.dp

    FilledIconButton(
        onClick = onEnterPip,
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
            imageVector = Icons.Filled.PictureInPictureAlt,
            contentDescription = "Picture in picture",
            modifier = Modifier
                .size(iconSize)
        )
    }
}

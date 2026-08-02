package com.feldman.ha.ui.setup

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.painter.Painter
import com.feldman.motion.rememberSymbolPainter

@Composable
fun rememberRotatingSymbolPainter(name: String, isRotating: Boolean): Painter {
    val painter = rememberSymbolPainter(name)
    if (!isRotating) return painter

    val transition = rememberInfiniteTransition(label = "rotation")
    val angle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "angle"
    )

    return remember(painter, angle) {
        object : Painter() {
            override val intrinsicSize: Size = painter.intrinsicSize
            override fun DrawScope.onDraw() {
                rotate(angle) {
                    with(painter) {
                        draw(size)
                    }
                }
            }
        }
    }
}

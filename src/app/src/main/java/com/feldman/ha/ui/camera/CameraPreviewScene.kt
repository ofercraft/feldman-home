package com.feldman.ha.ui.camera

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.dp

@Composable
internal fun CameraPreviewScene(
    cameraName: String,
    modifier: Modifier = Modifier,
    showLabel: Boolean = false
) {
    val sceneKey = remember(cameraName) { cameraName.lowercase() }

    Box(modifier = modifier) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawBaseScene()
            when {
                "drive" in sceneKey -> drawDrivewayScene()
                "garden" in sceneKey || "yard" in sceneKey -> drawGardenScene()
                else -> drawFrontDoorScene()
            }
        }

        if (showLabel) {
            Surface(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(12.dp),
                color = Color.Black.copy(alpha = 0.42f),
                contentColor = Color.White,
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(
                    text = cameraName.replace('_', ' '),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.titleMedium
                )
            }
        }
    }
}

private fun DrawScope.drawBaseScene() {
    val w = size.width
    val h = size.height
    drawRect(Color(0xFF172033), size = size)
    drawCircle(
        color = Color(0xFFFFD166).copy(alpha = 0.72f),
        radius = w * 0.08f,
        center = Offset(w * 0.82f, h * 0.18f)
    )
    drawRect(
        color = Color(0xFF253A2F),
        topLeft = Offset(0f, h * 0.58f),
        size = Size(w, h * 0.42f)
    )
}

private fun DrawScope.drawFrontDoorScene() {
    val w = size.width
    val h = size.height
    drawRect(
        color = Color(0xFF334155),
        topLeft = Offset(w * 0.16f, h * 0.18f),
        size = Size(w * 0.46f, h * 0.52f)
    )
    drawRoundRect(
        color = Color(0xFF111827),
        topLeft = Offset(w * 0.31f, h * 0.35f),
        size = Size(w * 0.16f, h * 0.35f),
        cornerRadius = CornerRadius(8f, 8f)
    )
    drawCircle(Color(0xFFFFF7AD), radius = w * 0.018f, center = Offset(w * 0.43f, h * 0.52f))
    drawRoundRect(
        color = Color(0xFF6B7280),
        topLeft = Offset(w * 0.28f, h * 0.70f),
        size = Size(w * 0.25f, h * 0.22f),
        cornerRadius = CornerRadius(18f, 18f)
    )
    drawCircle(Color(0xFFE5E7EB), radius = w * 0.035f, center = Offset(w * 0.73f, h * 0.47f))
    drawRoundRect(
        color = Color(0xFFE5E7EB),
        topLeft = Offset(w * 0.70f, h * 0.52f),
        size = Size(w * 0.06f, h * 0.16f),
        cornerRadius = CornerRadius(16f, 16f)
    )
}

private fun DrawScope.drawDrivewayScene() {
    val w = size.width
    val h = size.height
    drawRect(
        color = Color(0xFF475569),
        topLeft = Offset(w * 0.08f, h * 0.24f),
        size = Size(w * 0.50f, h * 0.33f)
    )
    drawRoundRect(
        color = Color(0xFF1F2937),
        topLeft = Offset(w * 0.17f, h * 0.35f),
        size = Size(w * 0.32f, h * 0.22f),
        cornerRadius = CornerRadius(10f, 10f)
    )
    drawRect(
        color = Color(0xFF64748B),
        topLeft = Offset(w * 0.25f, h * 0.57f),
        size = Size(w * 0.55f, h * 0.43f)
    )
    drawRoundRect(
        color = Color(0xFFCBD5E1),
        topLeft = Offset(w * 0.42f, h * 0.60f),
        size = Size(w * 0.26f, h * 0.14f),
        cornerRadius = CornerRadius(22f, 22f)
    )
    drawCircle(Color(0xFF111827), radius = w * 0.035f, center = Offset(w * 0.47f, h * 0.75f))
    drawCircle(Color(0xFF111827), radius = w * 0.035f, center = Offset(w * 0.64f, h * 0.75f))
}

private fun DrawScope.drawGardenScene() {
    val w = size.width
    val h = size.height
    drawRoundRect(
        color = Color(0xFF374151),
        topLeft = Offset(w * 0.44f, h * 0.58f),
        size = Size(w * 0.12f, h * 0.38f),
        cornerRadius = CornerRadius(20f, 20f)
    )
    drawCircle(Color(0xFF16A34A), radius = w * 0.12f, center = Offset(w * 0.50f, h * 0.47f))
    drawCircle(Color(0xFF15803D), radius = w * 0.10f, center = Offset(w * 0.25f, h * 0.56f))
    drawCircle(Color(0xFF22C55E), radius = w * 0.09f, center = Offset(w * 0.72f, h * 0.60f))
    drawRoundRect(
        color = Color(0xFF94A3B8),
        topLeft = Offset(w * 0.28f, h * 0.78f),
        size = Size(w * 0.44f, h * 0.12f),
        cornerRadius = CornerRadius(30f, 30f)
    )
}

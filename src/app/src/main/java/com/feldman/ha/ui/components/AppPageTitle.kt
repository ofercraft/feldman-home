package com.feldman.ha.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MaterialTheme.colorScheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.feldman.ha.ui.cards.ExpressiveCanvasSetting
import com.feldman.motion.feldmanFont

/**
 * Title for a top-level page's app bar.
 *
 * The oversized accent wordmark belongs to the expressive canvas — it is sized and coloured to
 * carry a tinted header. With expressive design off there is no header for it to anchor, and a
 * 26sp primary-coloured display face just reads as shouting, so it falls back to the same plain
 * bold title the settings pages use.
 */
@Composable
fun AppPageTitle(text: String, color: Color? = null) {
    if (ExpressiveCanvasSetting.isEnabled(LocalContext.current)) {
        Text(
            text = text,
            color = color ?: colorScheme.primary,
            fontFamily = feldmanFont(width = 120f, weight = 900),
            fontSize = 26.sp
        )
    } else {
        Text(
            text = text,
            color = color ?: colorScheme.onSurface,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )
    }
}

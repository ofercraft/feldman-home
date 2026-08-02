package com.feldman.ha.ui.cards

import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.util.Calendar

// Config keys for the clock card (see the "clock" spec in WidgetDefinitions).
const val CLOCK_SHOW_SECONDS_KEY = "clock_show_seconds"
const val CLOCK_SHOW_DATE_KEY = "clock_show_date"

/**
 * Hosts can swap the clock face while keeping the card chrome, grid behavior and settings
 * identical (the Clock app renders its own standby clock design here). Receives the card
 * config so face options (seconds/date) still come from the shared card settings.
 */
val LocalClockCardFace = androidx.compose.runtime.compositionLocalOf<(@Composable (config: Map<String, Any>) -> Unit)?> { null }

/**
 * A standalone clock card: a synthetic "clock.card_<uuid>" entity rendered as a live
 * digital clock. All options live in the shared card-config map like every other card.
 */
@Composable
fun ClockCard(
    config: Map<String, Any>,
    modifier: Modifier = Modifier
) {
    val faceOverride = LocalClockCardFace.current
    if (faceOverride != null) {
        androidx.compose.foundation.layout.Box(
            modifier = modifier
                .fillMaxSize()
                .background(cardBackgroundColor())
        ) {
            faceOverride(config)
        }
        return
    }

    val context = LocalContext.current
    val showSeconds = config[CLOCK_SHOW_SECONDS_KEY] as? Boolean ?: false
    val showDate = config[CLOCK_SHOW_DATE_KEY] as? Boolean ?: false
    val is24h = DateFormat.is24HourFormat(context)

    var nowMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(showSeconds) {
        while (isActive) {
            nowMillis = System.currentTimeMillis()
            val periodMs = if (showSeconds) 1_000L else 60_000L
            delay(periodMs - (System.currentTimeMillis() % periodMs) + 5)
        }
    }

    val cal = remember(nowMillis) { Calendar.getInstance().apply { timeInMillis = nowMillis } }
    val hour = if (is24h) cal.get(Calendar.HOUR_OF_DAY) else {
        val h = cal.get(Calendar.HOUR)
        if (h == 0) 12 else h
    }
    val timeText = buildString {
        append(if (is24h) "%02d".format(hour) else hour.toString())
        append(":")
        append("%02d".format(cal.get(Calendar.MINUTE)))
        if (showSeconds) {
            append(":")
            append("%02d".format(cal.get(Calendar.SECOND)))
        }
    }
    val amPm = if (!is24h) (if (cal.get(Calendar.AM_PM) == Calendar.AM) " AM" else " PM") else ""
    val dateText = DateFormat.format("EEE, MMM d", cal).toString()

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(cardBackgroundColor())
    ) {
        // Scale the time to the card: fit the width (rough glyph width ≈ 0.62 em) and
        // leave room for the date line below when it is shown.
        val chars = timeText.length + amPm.length
        val widthLimitSp = (maxWidth.value * 0.86f) / (chars * 0.62f)
        val heightLimitSp = maxHeight.value * (if (showDate) 0.42f else 0.55f)
        val timeSize = minOf(widthLimitSp, heightLimitSp).coerceAtLeast(14f)

        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = timeText + amPm,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = timeSize.sp,
                lineHeight = (timeSize * 1.05f).sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                maxLines = 1,
                style = MaterialTheme.typography.displayLarge
            )
            if (showDate) {
                Text(
                    text = dateText.replaceFirstChar { it.titlecase(LocalLocale.current.platformLocale) },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = (timeSize * 0.28f).coerceAtLeast(11f).sp,
                    lineHeight = (timeSize * 0.32f).coerceAtLeast(13f).sp,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    style = MaterialTheme.typography.titleMedium
                )
            }
        }
    }
}

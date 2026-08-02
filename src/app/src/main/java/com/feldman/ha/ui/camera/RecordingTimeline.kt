package com.feldman.ha.ui.camera

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.feldman.ha.api.FrigateEvent
import com.feldman.ha.api.FrigateRecordingDay
import com.feldman.ha.api.FrigateRecordingHour
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.ceil

private const val BucketMinutes = 5
private const val BucketSeconds = BucketMinutes * 60
private const val BucketMillis = BucketSeconds * 1000L
private const val HourSeconds = 3_600
private const val MaxTimelineBuckets = 24 * 60 / BucketMinutes

@Composable
fun RecordingTimeline(
    summary: List<FrigateRecordingDay>,
    events: List<FrigateEvent>,
    onHourSelected: (Long) -> Unit,
    selectedTime: Long? = null,
    modifier: Modifier = Modifier
) {
    val colorScheme = MaterialTheme.colorScheme
    val colors = TimelineStatusColors(
        motion = Color(0xFF7C4DFF),
        speech = Color(0xFF00ACC1),
        other = colorScheme.tertiary,
        none = colorScheme.primary.copy(alpha = 0.45f),
        unavailable = colorScheme.outline.copy(alpha = 0.45f)
    )

    val buckets = remember(summary, events) {
        buildTimelineBuckets(summary, events)
    }

    if (buckets.isEmpty()) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No recording history available", style = MaterialTheme.typography.bodyMedium)
        }
        return
    }

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Recording History", style = MaterialTheme.typography.titleMedium)
            Text("$BucketMinutes min bars", style = MaterialTheme.typography.labelSmall, color = colorScheme.onSurfaceVariant)
        }

        Spacer(Modifier.height(8.dp))
        MotionLegend(colors)
        Spacer(Modifier.height(8.dp))

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            contentPadding = PaddingValues(bottom = 16.dp)
        ) {
            items(buckets, key = { it.timestamp }) { bucket ->
                TimelineBucketBar(
                    bucket = bucket,
                    colors = colors,
                    selected = selectedTime != null &&
                        bucket.timestamp <= selectedTime &&
                        selectedTime < bucket.timestamp + BucketMillis,
                    onClick = { if (bucket.isPlayable) onHourSelected(bucket.timestamp) }
                )
            }
        }
    }
}

@Composable
private fun MotionLegend(colors: TimelineStatusColors) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        LegendItem("Motion", colors.motion)
        LegendItem("Objects", colors.other)
        LegendItem("Recorded", colors.none)
    }
}

@Composable
private fun LegendItem(label: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(color)
        )
        Spacer(Modifier.width(4.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun TimelineBucketBar(
    bucket: TimelineBucket,
    colors: TimelineStatusColors,
    selected: Boolean,
    onClick: () -> Unit
) {
    val statusColor = colors[bucket.status]

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(24.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (selected) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                }
            )
            .clickable(enabled = bucket.isPlayable, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .width(if (bucket.hasMotion) 28.dp else 14.dp)
                .fillMaxHeight()
                .background(statusColor)
        )
        Spacer(Modifier.width(8.dp))
        Box(
            modifier = Modifier
                .weight(1f)
                .height(if (bucket.hasMotion) 12.dp else 6.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(statusColor.copy(alpha = if (bucket.hasMotion) 1f else 0.45f))
        )
    }
}

private fun buildTimelineBuckets(
    summary: List<FrigateRecordingDay>,
    events: List<FrigateEvent>
): List<TimelineBucket> {
    val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    val recordedHours = summary.flatMap { day ->
        val date = dateFormat.parse(day.day) ?: return@flatMap emptyList()
        day.hours.map { hour ->
            hourTimestamp(date, hour.hour) to hour
        }
    }.toMap()

    if (recordedHours.isEmpty()) {
        return emptyList()
    }

    val eventsByBucket = events.groupBy { event ->
        event.start_time.toBucketTimestamp()
    }

    return recordedHours
        .toList()
        .sortedByDescending { (timestamp, _) -> timestamp }
        .flatMap { (hourTimestamp, hour) ->
            val recordedBucketCount = recordedBucketCount(hour)
            (recordedBucketCount - 1 downTo 0).map { bucketIndex ->
                val timestamp = hourTimestamp + (bucketIndex * BucketMillis)
                val status = bucketStatus(hour, eventsByBucket[timestamp].orEmpty())

                TimelineBucket(
                    timestamp = timestamp,
                    status = status,
                    hasMotion = status == TimelineStatus.Motion,
                    isPlayable = true
                )
            }
        }
        .take(MaxTimelineBuckets)
}

private fun bucketStatus(
    hour: FrigateRecordingHour,
    events: List<FrigateEvent>
): TimelineStatus {
    if (events.any { it.label.toTimelineStatus() == TimelineStatus.Speech }) {
        return TimelineStatus.Speech
    }

    if (events.any { it.label.toTimelineStatus() == TimelineStatus.Other } || hour.objects > 0) {
        return TimelineStatus.Other
    }

    return if (hour.motion > 0) TimelineStatus.Motion else TimelineStatus.None
}

private fun hourTimestamp(date: Date, hour: Int): Long {
    return Calendar.getInstance().apply {
        time = date
        set(Calendar.HOUR_OF_DAY, hour)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis
}

private fun recordedBucketCount(hour: FrigateRecordingHour): Int {
    return ceil(hour.duration.coerceIn(0, HourSeconds).toDouble() / BucketSeconds)
        .toInt()
        .coerceIn(0, HourSeconds / BucketSeconds)
}

private fun Double.toBucketTimestamp(): Long {
    return Calendar.getInstance().apply {
        timeInMillis = (this@toBucketTimestamp * 1000).toLong()
        val bucketMinute = (get(Calendar.MINUTE) / BucketMinutes) * BucketMinutes
        set(Calendar.MINUTE, bucketMinute)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis
}

private fun String.toTimelineStatus(): TimelineStatus {
    val normalized = lowercase(Locale.getDefault())
    return when {
        "speech" in normalized || "audio" in normalized || "sound" in normalized -> TimelineStatus.Speech
        "other" in normalized -> TimelineStatus.Other
        else -> TimelineStatus.Motion
    }
}

private data class TimelineBucket(
    val timestamp: Long,
    val status: TimelineStatus,
    val hasMotion: Boolean,
    val isPlayable: Boolean
)

private data class TimelineStatusColors(
    val motion: Color,
    val speech: Color,
    val other: Color,
    val none: Color,
    val unavailable: Color
) {
    operator fun get(status: TimelineStatus): Color {
        return when (status) {
            TimelineStatus.Motion -> motion
            TimelineStatus.Speech -> speech
            TimelineStatus.Other -> other
            TimelineStatus.None -> none
            TimelineStatus.Unavailable -> unavailable
        }
    }
}

private enum class TimelineStatus {
    Motion,
    Speech,
    Other,
    None,
    Unavailable
}

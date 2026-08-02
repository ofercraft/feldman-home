package com.feldman.ha.ui.camera.videoplayer.utils


enum class SeekDirection { Forward, Backward }

data class SeekFeedback(
    val direction: SeekDirection,
    val amountMs: Long,
    val isRightSide: Boolean
)

fun formatTime(ms: Long): String {
    val totalSec = ms / 1000
    val min = totalSec / 60
    val sec = totalSec % 60
    return "%d:%02d".format(min, sec)
}

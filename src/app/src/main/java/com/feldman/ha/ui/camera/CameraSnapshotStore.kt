package com.feldman.ha.ui.camera

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/**
 * Disk-backed snapshot cache shared by the background [com.feldman.ha.widgets.CameraSnapshotWorker]
 * and the in-app [CameraCard].
 *
 * The worker (and, while the dashboard is open, the card itself) downloads the camera's latest JPEG
 * on a slow per-card cadence and writes it to `filesDir/cam_snapshots/<key>.jpg`. The card observes
 * [versionFlow] for a key so it can re-read the file the moment a fresher frame lands — this is what
 * lets a freshly-reopened homescreen show a recent frame instantly instead of a multi-second blank.
 */
object CameraSnapshotStore {

    private val versions = ConcurrentHashMap<String, MutableStateFlow<Long>>()

    private fun dir(context: Context): File =
        File(context.filesDir, "cam_snapshots").apply { if (!exists()) mkdirs() }

    fun fileFor(context: Context, key: String): File = File(dir(context), "$key.jpg")

    /** Last-write timestamp stream for a snapshot key; emits 0 until the first frame is stored. */
    fun versionFlow(context: Context, key: String): StateFlow<Long> =
        versions.getOrPut(key) {
            val f = fileFor(context, key)
            MutableStateFlow(if (f.exists()) f.lastModified() else 0L)
        }

    /**
     * Downloads the camera's current snapshot and atomically replaces the cached file.
     * Returns true on success. Safe to call off the main thread; performs blocking IO on [Dispatchers.IO].
     */
    suspend fun fetchAndStore(
        context: Context,
        config: CameraCardConfig,
        token: String,
        haBaseUrl: String,
        frigateUrl: String,
    ): Boolean = withContext(Dispatchers.IO) {
        val url = cameraSnapshotUrl(config, haBaseUrl, frigateUrl, System.currentTimeMillis())
        val bytes = runCatching { download(url, token) }.getOrNull() ?: return@withContext false
        if (bytes.isEmpty()) return@withContext false

        val target = fileFor(context, config.snapshotKey)
        val tmp = File(target.parentFile, "${config.snapshotKey}.tmp")
        runCatching {
            tmp.writeBytes(bytes)
            if (target.exists()) target.delete()
            tmp.renameTo(target)
        }.onFailure { tmp.delete(); return@withContext false }

        val now = System.currentTimeMillis()
        versions.getOrPut(config.snapshotKey) { MutableStateFlow(0L) }.value = now
        true
    }

    private fun download(url: String, token: String): ByteArray {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 15_000
            instanceFollowRedirects = true
            if (token.isNotBlank()) setRequestProperty("Authorization", "Bearer $token")
        }
        try {
            if (conn.responseCode !in 200..299) return ByteArray(0)
            return conn.inputStream.use { it.readBytes() }
        } finally {
            conn.disconnect()
        }
    }
}

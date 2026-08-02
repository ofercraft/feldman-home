package com.feldman.ha.widgets

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.feldman.ha.api.HomeAssistantAuth
import com.feldman.ha.ui.camera.CAMERA_REFRESH_MIN_SEC
import com.feldman.ha.ui.camera.CameraCardConfig
import com.feldman.ha.ui.camera.CameraSnapshotStore
import com.feldman.ha.ui.camera.loadConfiguredCameraCards
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

/**
 * Keeps each dashboard camera card's snapshot reasonably fresh in the background.
 *
 * A single self-rescheduling [CoroutineWorker] services every configured camera. Each run refreshes
 * only the cameras that are *due* (now - lastFetch >= the camera's own `refreshIntervalSec`), then
 * reschedules itself after the smallest configured interval. This honours per-card cadences below
 * WorkManager's 15-minute periodic floor without spawning one worker per camera.
 *
 * Caveat: while the app is backgrounded the OS (Doze/standby) may delay runs — acceptable for slow
 * snapshots; the in-app [com.feldman.ha.ui.camera.CameraCard] also refreshes on its own cadence
 * while the dashboard is visible.
 */
class CameraSnapshotWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val cameras = loadConfiguredCameraCards(ctx.getSharedPreferences(CARDS_PREFS, Context.MODE_PRIVATE))
        if (cameras.isEmpty()) return Result.success() // nothing to do; no reschedule

        val ha = ctx.getSharedPreferences(HA_PREFS, Context.MODE_PRIVATE)
        val baseUrl = ha.getString("url", "http://homeassistant.local:8123/api/").orEmpty()
        val frigateUrl = ha.getString("frigate_url", "").orEmpty()

        val state = ctx.getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()

        for (cam in cameras) {
            val last = state.getLong(lastKey(cam), 0L)
            if (now - last < cam.refreshIntervalSec * 1000L) continue
            try {
                val token = HomeAssistantAuth.currentAccessToken(ctx)
                val ok = CameraSnapshotStore.fetchAndStore(ctx, cam, token, baseUrl, frigateUrl)
                if (ok) state.edit().putLong(lastKey(cam), System.currentTimeMillis()).apply()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "snapshot fetch failed for ${cam.snapshotKey}", e)
            }
        }

        val nextDelaySec = cameras.minOf { it.refreshIntervalSec }
            .coerceAtLeast(CAMERA_REFRESH_MIN_SEC)
        reschedule(ctx, nextDelaySec.toLong())
        return Result.success()
    }

    companion object {
        private const val TAG = "CameraSnapshotWorker"
        private const val UNIQUE_NAME = "camera_snapshot_refresh"
        private const val CARDS_PREFS = "cards_prefs"
        private const val HA_PREFS = "ha_prefs"
        private const val STATE_PREFS = "camera_snapshot_state"

        private fun lastKey(cam: CameraCardConfig) = "last_${cam.snapshotKey}"

        private fun request(initialDelaySec: Long) =
            OneTimeWorkRequestBuilder<CameraSnapshotWorker>()
                .setInitialDelay(initialDelaySec, TimeUnit.SECONDS)
                .addTag(TAG)
                .build()

        /** Enqueues (or replaces) the refresh chain; call after camera cards change. */
        fun sync(context: Context) {
            val ctx = context.applicationContext
            val cameras = loadConfiguredCameraCards(ctx.getSharedPreferences(CARDS_PREFS, Context.MODE_PRIVATE))
            val wm = WorkManager.getInstance(ctx)
            if (cameras.isEmpty()) {
                wm.cancelUniqueWork(UNIQUE_NAME)
                return
            }
            // Run almost immediately, then the worker self-reschedules on the per-card cadence.
            wm.enqueueUniqueWork(UNIQUE_NAME, ExistingWorkPolicy.REPLACE, request(1))
        }

        private fun reschedule(context: Context, delaySec: Long) {
            WorkManager.getInstance(context.applicationContext)
                .enqueueUniqueWork(UNIQUE_NAME, ExistingWorkPolicy.REPLACE, request(delaySec))
        }
    }
}

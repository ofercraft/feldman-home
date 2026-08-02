package com.feldman.ha.mobile

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit

class MobileAppSensorUpdateWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val context = applicationContext
        val fullSync = inputData.getBoolean(KEY_FULL_SYNC, false)
        if (!MobileAppRegistration.isRegistered(context)) {
            Log.w(TAG, "worker: skipped, this phone is not registered with Home Assistant")
            return Result.success()
        }

        Log.d(TAG, "worker: starting (fullSync=$fullSync)")
        return try {
            MobileAppSensors.registerAllSensors(context)
            if (fullSync) {
                MobileAppSensors.updateAllKnownSensors(context)
            } else {
                MobileAppSensors.updateEnabledSensors(context)
            }
            Log.d(TAG, "worker: finished")
            Result.success()
        } catch (e: Exception) {
            Log.w(TAG, "worker: failed, will retry", e)
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "MobileAppSensors"
        private const val PERIODIC_WORK = "mobile_app_sensor_periodic_update"
        private const val ONE_SHOT_WORK = "mobile_app_sensor_one_shot_update"
        private const val KEY_FULL_SYNC = "full_sync"

        private val networkConstraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        fun schedule(context: Context) {
            if (!MobileAppRegistration.isRegistered(context.applicationContext)) {
                Log.i(TAG, "schedule: skipped, not registered")
                return
            }
            val request = PeriodicWorkRequestBuilder<MobileAppSensorUpdateWorker>(
                15,
                TimeUnit.MINUTES
            )
                .setConstraints(networkConstraints)
                .build()
            WorkManager.getInstance(context.applicationContext)
                .enqueueUniquePeriodicWork(
                    PERIODIC_WORK,
                    ExistingPeriodicWorkPolicy.UPDATE,
                    request
                )
        }

        /**
         * @param fullSync also push states for sensors switched off in the app, so that anything
         *   Home Assistant already knows about ends up with a value. Worth it at startup and when
         *   the sensor list is on screen; wasteful for a routine event like the charger being
         *   plugged in, which only needs the enabled set.
         */
        fun enqueueNow(context: Context, fullSync: Boolean = false) {
            if (!MobileAppRegistration.isRegistered(context.applicationContext)) {
                Log.i(TAG, "enqueueNow: skipped, not registered")
                return
            }
            Log.d(TAG, "enqueueNow: queued (fullSync=$fullSync)")
            val request = OneTimeWorkRequestBuilder<MobileAppSensorUpdateWorker>()
                .setConstraints(networkConstraints)
                .setInputData(workDataOf(KEY_FULL_SYNC to fullSync))
                .build()
            WorkManager.getInstance(context.applicationContext)
                // Queued behind a run already in progress rather than replacing it. REPLACE
                // cancels the running worker, and these fire in bursts — app start, then the
                // Phone page opening, then a broadcast — so a full sync would routinely be killed
                // part way through, leaving half the sensors registered.
                .enqueueUniqueWork(ONE_SHOT_WORK, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        }
    }
}

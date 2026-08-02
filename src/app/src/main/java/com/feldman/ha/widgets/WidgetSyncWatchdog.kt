package com.feldman.ha.widgets

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkManager
import androidx.work.WorkerParameters

class WidgetSyncWatchdog(context: Context, workerParams: WorkerParameters) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        WidgetSyncLogger.log(applicationContext, "Watchdog: disabled; visible Glance sessions own refresh")
        WidgetRefreshScheduler.cancel(applicationContext)
        WidgetRefreshScheduler.stopWebSocket(applicationContext)
        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "WidgetSyncWatchdog"

        fun schedule(context: Context) {
            cancel(context)
            WidgetSyncLogger.log(context, "Watchdog: periodic check disabled")
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }
}

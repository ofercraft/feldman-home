package com.feldman.ha.widgets

import android.content.Context
import android.util.Log
import androidx.glance.GlanceId
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.feldman.ha.api.getStoredApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class WidgetActionRetryWorker(
    context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        val appCtx = applicationContext
        if (!sendMutex.tryLock()) {
            Log.d(TAG, "Another queued action worker is already sending; latest payload is stored")
            return Result.success()
        }
        try {
            return sendLatestActions(appCtx)
        } finally {
            sendMutex.unlock()
        }
    }

    private suspend fun sendLatestActions(appCtx: Context): Result {
        var sentInThisRun = 0

        while (true) {
            val queued = readSettledQueuedAction(appCtx) ?: return Result.success()
            val widgetKey = queued.widgetKey
            val rowId = queued.rowId
            val domain = queued.domain
            val service = queued.service
            val appWidgetId = queued.appWidgetId

            Log.d(TAG, "Queued action worker sending $domain.$service widget=$widgetKey row=$rowId")

            try {
                val api = getStoredApi(appCtx) ?: throw IllegalStateException("No API credentials configured")
                val response = api.callService(domain, service, queued.body)
                if (!response.isSuccessful) {
                    throw IOException("HTTP ${response.code()} ${response.message()}")
                }

                WidgetNetworkGate.reportSuccess()
                WidgetSyncLogger.log(appCtx, "ActionWorker: sent $domain.$service for $widgetKey/$rowId")

                if (appWidgetId != null) {
                    val glanceId = runCatching { GlanceAppWidgetManager(appCtx).getGlanceIdBy(appWidgetId) }.getOrNull()
                    if (glanceId != null) {
                        FactoryGlanceAppWidget(widgetKey).update(appCtx, glanceId)
                    } else {
                        updateFactoryWidgets(appCtx, widgetKey, appWidgetId, refreshFromApi = false)
                    }
                    WidgetRefreshScheduler.requestProviderUpdate(appCtx, widgetKey, appWidgetId)
                } else {
                    updateFactoryWidgets(appCtx, widgetKey, refreshFromApi = false)
                    WidgetRefreshScheduler.requestProviderUpdate(appCtx, widgetKey)
                }

                if (queued.queueName == null) return Result.success()

                val latestRaw = readQueuedRaw(appCtx, queued.queueName)
                if (latestRaw == null || latestRaw == queued.raw) {
                    if (clearQueuedAction(appCtx, queued)) {
                        return Result.success()
                    }
                }

                sentInThisRun += 1
                if (sentInThisRun >= MAX_SENDS_PER_WORK) {
                    Log.d(TAG, "Queued action changed while sending; retrying latest later")
                    return Result.retry()
                }
                Log.d(TAG, "Queued action changed while sending; sending latest now")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                WidgetNetworkGate.reportFailure(appCtx, e, "queued action[$widgetKey:$rowId]")
                if (WidgetNetworkGate.isNetworkFailure(e)) {
                    Log.w(TAG, "Queued action network failure; retrying", e)
                    return Result.retry()
                }

                Log.e(TAG, "Queued action failed", e)
                clearQueuedAction(appCtx, queued)
                appWidgetId?.let {
                    rollbackWidgetAction(appCtx, queued)
                    setWidgetActionMessage(appCtx, widgetKey, it, "Action failed: ${e.localizedMessage ?: e.javaClass.simpleName}")
                }
                return Result.failure()
            }
        }
    }

    private suspend fun readSettledQueuedAction(appCtx: Context): QueuedAction? {
        var queued = readQueuedAction(appCtx) ?: return null
        val queueName = queued.queueName ?: return queued
        while (true) {
            delay(WIDGET_ACTION_SETTLE_DELAY_MS)
            val latestRaw = readQueuedRaw(appCtx, queueName) ?: return null
            if (latestRaw == queued.raw) return queued
            queued = queuedActionFromJson(queueName, latestRaw) ?: return null
        }
    }

    private suspend fun rollbackWidgetAction(appCtx: Context, queued: QueuedAction) {
        val appWidgetId = queued.appWidgetId ?: return
        val glanceId = runCatching { GlanceAppWidgetManager(appCtx).getGlanceIdBy(appWidgetId) }.getOrNull() ?: return
        updateAppWidgetState(appCtx, CachedPreferencesGlanceStateDefinition, glanceId) { prefs ->
            prefs.toMutablePreferences().apply {
                remove(FACT_OPTIMISTIC_ROW)
                remove(FACT_OPTIMISTIC_INT)
                remove(FACT_OPTIMISTIC_STRING)
                remove(FACT_OPTIMISTIC_ROLLBACK_INT)
                remove(FACT_OPTIMISTIC_ROLLBACK_STRING)
                queued.rollbackInt?.let { this[rowKey(queued.rowId)] = it }
                queued.rollbackString?.let { this[rowStringKey(queued.rowId)] = it }
            }
        }
        FactoryGlanceAppWidget(queued.widgetKey).update(appCtx, glanceId)
    }

    companion object {
        private const val TAG = "WidgetActionWorker"
        private const val PREFS_NAME = "widget_action_queue"
        private const val MAX_SENDS_PER_WORK = 3
        private const val WIDGET_ACTION_SETTLE_DELAY_MS = 450L
        private const val KEY_QUEUE_NAME = "queue_name"
        private const val KEY_WIDGET_KEY = "widget_key"
        private const val KEY_APP_WIDGET_ID = "app_widget_id"
        private const val KEY_ROW_ID = "row_id"
        private const val KEY_DOMAIN = "domain"
        private const val KEY_SERVICE = "service"
        private const val KEY_BODY = "body"
        private const val KEY_ROLLBACK_INT = "rollback_int"
        private const val KEY_ROLLBACK_STRING = "rollback_string"
        private val sendMutex = Mutex()

        fun enqueue(
            context: Context,
            widgetKey: String,
            appWidgetId: Int?,
            rowId: String,
            domain: String,
            service: String,
            body: Map<String, Any>,
            rollbackInt: Int? = null,
            rollbackString: String? = null
        ) {
            val suffix = rowId.replace(Regex("[^A-Za-z0-9_.-]"), "_")
            val name = "WidgetAction_${appWidgetId ?: widgetKey}_$suffix"
            val payload = payloadToJson(widgetKey, appWidgetId, rowId, domain, service, body, rollbackInt, rollbackString)
            context.applicationContext
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(name, payload)
                .apply()

            val data = Data.Builder()
                .putString(KEY_QUEUE_NAME, name)
                .putString(KEY_WIDGET_KEY, widgetKey)
                .putInt(KEY_APP_WIDGET_ID, appWidgetId ?: -1)
                .putString(KEY_ROW_ID, rowId)
                .putString(KEY_DOMAIN, domain)
                .putString(KEY_SERVICE, service)
                .putString(KEY_BODY, bodyToJson(body))
            rollbackInt?.let { data.putInt(KEY_ROLLBACK_INT, it) }
            rollbackString?.let { data.putString(KEY_ROLLBACK_STRING, it) }
            val workData = data.build()

            val foregroundStarted = WidgetActionForegroundService.start(context.applicationContext, name, payload)

            val requestBuilder = OneTimeWorkRequestBuilder<WidgetActionRetryWorker>()
                .setInputData(workData)
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .setBackoffCriteria(BackoffPolicy.LINEAR, 10, TimeUnit.SECONDS)
                .addTag(TAG)
            if (foregroundStarted) {
                requestBuilder.setInitialDelay(15, TimeUnit.SECONDS)
            }
            val request = requestBuilder.build()

            WorkManager.getInstance(context.applicationContext).enqueue(request)
            WidgetSyncLogger.log(context, "ActionWorker: queued $domain.$service for $widgetKey/$rowId")
        }

        suspend fun setWidgetActionMessage(
            context: Context,
            widgetKey: String,
            glanceId: GlanceId,
            message: String
        ) {
            updateAppWidgetState(context.applicationContext, CachedPreferencesGlanceStateDefinition, glanceId) { prefs ->
                prefs.toMutablePreferences().apply {
                    this[FACT_ERROR] = message
                }
            }
            FactoryGlanceAppWidget(widgetKey).update(context.applicationContext, glanceId)
        }

        private fun bodyToJson(body: Map<String, Any>): String {
            val json = JSONObject()
            body.forEach { (key, value) -> json.put(key, value) }
            return json.toString()
        }

        private fun payloadToJson(
            widgetKey: String,
            appWidgetId: Int?,
            rowId: String,
            domain: String,
            service: String,
            body: Map<String, Any>,
            rollbackInt: Int?,
            rollbackString: String?
        ): String {
            val json = JSONObject()
            json.put(KEY_WIDGET_KEY, widgetKey)
            json.put(KEY_APP_WIDGET_ID, appWidgetId ?: -1)
            json.put(KEY_ROW_ID, rowId)
            json.put(KEY_DOMAIN, domain)
            json.put(KEY_SERVICE, service)
            json.put(KEY_BODY, bodyToJson(body))
            rollbackInt?.let { json.put(KEY_ROLLBACK_INT, it) }
            rollbackString?.let { json.put(KEY_ROLLBACK_STRING, it) }
            return json.toString()
        }

        private fun bodyFromJson(raw: String): Map<String, Any> {
            if (raw.isBlank()) return emptyMap()
            val json = JSONObject(raw)
            val body = mutableMapOf<String, Any>()
            val keys = json.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val value = json.get(key)
                if (value != JSONObject.NULL) {
                    body[key] = value
                }
            }
            return body
        }
    }

    private data class QueuedAction(
        val queueName: String?,
        val raw: String?,
        val widgetKey: String,
        val appWidgetId: Int?,
        val rowId: String,
        val domain: String,
        val service: String,
        val body: Map<String, Any>,
        val rollbackInt: Int?,
        val rollbackString: String?
    )

    private fun readQueuedAction(context: Context): QueuedAction? {
        val queueName = inputData.getString(KEY_QUEUE_NAME)
        if (queueName != null) {
            val raw = readQueuedRaw(context, queueName)
            if (raw != null) {
                return queuedActionFromJson(queueName, raw)
            }
            return null
        }
        return queuedActionFromInputData()
    }

    private fun queuedActionFromInputData(): QueuedAction? {
        val widgetKey = inputData.getString(KEY_WIDGET_KEY) ?: return null
        val domain = inputData.getString(KEY_DOMAIN) ?: return null
        val service = inputData.getString(KEY_SERVICE) ?: return null
        return QueuedAction(
            queueName = null,
            raw = null,
            widgetKey = widgetKey,
            appWidgetId = inputData.getInt(KEY_APP_WIDGET_ID, -1).takeIf { it > 0 },
            rowId = inputData.getString(KEY_ROW_ID) ?: "unknown",
            domain = domain,
            service = service,
            body = bodyFromJson(inputData.getString(KEY_BODY).orEmpty()),
            rollbackInt = inputData.getInt(KEY_ROLLBACK_INT, Int.MIN_VALUE).takeIf { it != Int.MIN_VALUE },
            rollbackString = inputData.getString(KEY_ROLLBACK_STRING)
        )
    }

    private fun queuedActionFromJson(queueName: String, raw: String): QueuedAction? {
        return runCatching {
            val json = JSONObject(raw)
            val widgetKey = json.optString(KEY_WIDGET_KEY).takeIf { it.isNotBlank() } ?: return null
            val domain = json.optString(KEY_DOMAIN).takeIf { it.isNotBlank() } ?: return null
            val service = json.optString(KEY_SERVICE).takeIf { it.isNotBlank() } ?: return null
            QueuedAction(
                queueName = queueName,
                raw = raw,
                widgetKey = widgetKey,
                appWidgetId = json.optInt(KEY_APP_WIDGET_ID, -1).takeIf { it > 0 },
                rowId = json.optString(KEY_ROW_ID, "unknown"),
                domain = domain,
                service = service,
                body = bodyFromJson(json.optString(KEY_BODY)),
                rollbackInt = if (json.has(KEY_ROLLBACK_INT)) json.optInt(KEY_ROLLBACK_INT) else null,
                rollbackString = json.optString(KEY_ROLLBACK_STRING).takeIf { it.isNotBlank() }
            )
        }.getOrNull()
    }

    private fun readQueuedRaw(context: Context, queueName: String): String? {
        return context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(queueName, null)
    }

    private fun clearQueuedAction(context: Context, queued: QueuedAction): Boolean {
        val queueName = queued.queueName ?: return true
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val currentRaw = prefs.getString(queueName, null)
        if (currentRaw == null) return true
        if (currentRaw == queued.raw) {
            prefs.edit().remove(queueName).apply()
            return true
        }
        return false
    }
}

private suspend fun setWidgetActionMessage(
    context: Context,
    widgetKey: String,
    appWidgetId: Int,
    message: String
) {
    val appCtx = context.applicationContext
    val glanceId = runCatching { GlanceAppWidgetManager(appCtx).getGlanceIdBy(appWidgetId) }.getOrNull() ?: return
    WidgetActionRetryWorker.setWidgetActionMessage(appCtx, widgetKey, glanceId, message)
}

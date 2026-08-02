package com.feldman.ha.widgets

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.updateAppWidgetState
import com.feldman.ha.api.getStoredApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.IOException

class WidgetActionForegroundService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sendMutex = Mutex()

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIFICATION_ID,
                    createNotification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(NOTIFICATION_ID, createNotification())
            }
        } catch (e: Exception) {
            Log.w(TAG, "startForeground failed; WorkManager fallback will handle queued action", e)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action != ACTION_SEND) {
            stopSelf(startId)
            return START_NOT_STICKY
        }

        val queueName = intent.getStringExtra(EXTRA_QUEUE_NAME)
        val fallbackPayload = intent.getStringExtra(EXTRA_PAYLOAD)
        serviceScope.launch {
            try {
                sendMutex.withLock {
                    sendQueuedActions(queueName, fallbackPayload)
                }
            } finally {
                stopSelf(startId)
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    private suspend fun sendQueuedActions(queueName: String?, fallbackPayload: String?) {
        var sent = 0
        while (sent < MAX_SENDS_PER_START) {
            val raw = readSettledQueuedRaw(queueName, fallbackPayload) ?: return
            val action = queuedActionFromJson(queueName, raw) ?: return

            Log.d(TAG, "Foreground action service sending ${action.domain}.${action.service} widget=${action.widgetKey} row=${action.rowId}")
            try {
                val api = getStoredApi(applicationContext) ?: throw IllegalStateException("No API credentials configured")
                val response = api.callService(action.domain, action.service, action.body)
                if (!response.isSuccessful) {
                    throw IOException("HTTP ${response.code()} ${response.message()}")
                }

                WidgetNetworkGate.reportSuccess()
                WidgetSyncLogger.log(applicationContext, "ActionService: sent ${action.domain}.${action.service} for ${action.widgetKey}/${action.rowId}")
                refreshWidget(action)

                val latestRaw = action.queueName?.let { readQueuedRaw(it) }
                if (latestRaw == null || latestRaw == raw) {
                    clearQueuedAction(action)
                    return
                }

                sent += 1
                Log.d(TAG, "Queued action changed while foreground service was sending; sending latest now")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                WidgetNetworkGate.reportFailure(applicationContext, e, "foreground action[${action.widgetKey}:${action.rowId}]")
                if (WidgetNetworkGate.isNetworkFailure(e)) {
                    Log.w(TAG, "Foreground action network failure; WorkManager will retry", e)
                    return
                }

                Log.e(TAG, "Foreground action failed", e)
                clearQueuedAction(action)
                action.appWidgetId?.let {
                    rollbackWidgetAction(action)
                    setWidgetActionMessage(action.widgetKey, it, "Action failed: ${e.localizedMessage ?: e.javaClass.simpleName}")
                }
                return
            }
        }
    }

    private suspend fun readSettledQueuedRaw(queueName: String?, fallbackPayload: String?): String? {
        if (queueName == null) return fallbackPayload
        var raw = readQueuedRaw(queueName) ?: return null
        while (true) {
            delay(WIDGET_ACTION_SETTLE_DELAY_MS)
            val latestRaw = readQueuedRaw(queueName) ?: return null
            if (latestRaw == raw) return latestRaw
            raw = latestRaw
        }
    }

    private suspend fun refreshWidget(action: QueuedAction) {
        val appWidgetId = action.appWidgetId
        if (appWidgetId != null) {
            val glanceId = runCatching { GlanceAppWidgetManager(applicationContext).getGlanceIdBy(appWidgetId) }.getOrNull()
            if (glanceId != null) {
                FactoryGlanceAppWidget(action.widgetKey).update(applicationContext, glanceId)
            } else {
                updateFactoryWidgets(applicationContext, action.widgetKey, appWidgetId, refreshFromApi = false)
            }
            WidgetRefreshScheduler.requestProviderUpdate(applicationContext, action.widgetKey, appWidgetId)
        } else {
            updateFactoryWidgets(applicationContext, action.widgetKey, refreshFromApi = false)
            WidgetRefreshScheduler.requestProviderUpdate(applicationContext, action.widgetKey)
        }
    }

    private suspend fun rollbackWidgetAction(action: QueuedAction) {
        val appWidgetId = action.appWidgetId ?: return
        val glanceId = runCatching { GlanceAppWidgetManager(applicationContext).getGlanceIdBy(appWidgetId) }.getOrNull() ?: return
        updateAppWidgetState(applicationContext, CachedPreferencesGlanceStateDefinition, glanceId) { prefs ->
            prefs.toMutablePreferences().apply {
                remove(FACT_OPTIMISTIC_ROW)
                remove(FACT_OPTIMISTIC_INT)
                remove(FACT_OPTIMISTIC_STRING)
                remove(FACT_OPTIMISTIC_ROLLBACK_INT)
                remove(FACT_OPTIMISTIC_ROLLBACK_STRING)
                action.rollbackInt?.let { this[rowKey(action.rowId)] = it }
                action.rollbackString?.let { this[rowStringKey(action.rowId)] = it }
            }
        }
        FactoryGlanceAppWidget(action.widgetKey).update(applicationContext, glanceId)
    }

    private suspend fun setWidgetActionMessage(widgetKey: String, appWidgetId: Int, message: String) {
        val glanceId = runCatching { GlanceAppWidgetManager(applicationContext).getGlanceIdBy(appWidgetId) }.getOrNull() ?: return
        WidgetActionRetryWorker.setWidgetActionMessage(applicationContext, widgetKey, glanceId, message)
    }

    private fun readQueuedRaw(queueName: String): String? {
        return applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(queueName, null)
    }

    private fun clearQueuedAction(action: QueuedAction) {
        val queueName = action.queueName ?: return
        val prefs = applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val currentRaw = prefs.getString(queueName, null)
        if (currentRaw == action.raw) {
            prefs.edit().remove(queueName).apply()
        }
    }

    private fun queuedActionFromJson(queueName: String?, raw: String): QueuedAction? {
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

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Widget Actions",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Sends Home Assistant widget actions"
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Sending Home Assistant action")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private data class QueuedAction(
        val queueName: String?,
        val raw: String,
        val widgetKey: String,
        val appWidgetId: Int?,
        val rowId: String,
        val domain: String,
        val service: String,
        val body: Map<String, Any>,
        val rollbackInt: Int?,
        val rollbackString: String?
    )

    companion object {
        private const val TAG = "WidgetActionService"
        private const val CHANNEL_ID = "widget_action_channel"
        private const val NOTIFICATION_ID = 102
        private const val MAX_SENDS_PER_START = 3
        private const val WIDGET_ACTION_SETTLE_DELAY_MS = 450L
        private const val PREFS_NAME = "widget_action_queue"
        private const val ACTION_SEND = "com.feldman.ha.action.SEND_WIDGET_ACTION"
        private const val EXTRA_QUEUE_NAME = "queue_name"
        private const val EXTRA_PAYLOAD = "payload"
        private const val KEY_WIDGET_KEY = "widget_key"
        private const val KEY_APP_WIDGET_ID = "app_widget_id"
        private const val KEY_ROW_ID = "row_id"
        private const val KEY_DOMAIN = "domain"
        private const val KEY_SERVICE = "service"
        private const val KEY_BODY = "body"
        private const val KEY_ROLLBACK_INT = "rollback_int"
        private const val KEY_ROLLBACK_STRING = "rollback_string"

        fun start(context: Context, queueName: String, payload: String): Boolean {
            val appCtx = context.applicationContext
            val intent = Intent(appCtx, WidgetActionForegroundService::class.java).apply {
                action = ACTION_SEND
                putExtra(EXTRA_QUEUE_NAME, queueName)
                putExtra(EXTRA_PAYLOAD, payload)
            }
            return try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    appCtx.startForegroundService(intent)
                } else {
                    appCtx.startService(intent)
                }
                true
            } catch (e: Exception) {
                Log.w(TAG, "Unable to start foreground action service; WorkManager will retry", e)
                false
            }
        }
    }
}

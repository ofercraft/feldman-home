package com.feldman.ha.widgets

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.updateAll
import com.feldman.ha.widgets.generated.*
import kotlinx.coroutines.launch

object WidgetRefreshScheduler {
    const val TAG = "WidgetRefresh"
    const val ACTION_REFRESH = "com.feldman.ha.action.REFRESH_WIDGETS"
    const val EXTRA_WIDGET_KEY = "widget_key"
    const val EXTRA_APP_WIDGET_ID = "app_widget_id"
    const val EXTRA_REFRESH_FROM_API = "refresh_from_api"
    const val EXTRA_INTERNAL_PROVIDER_UPDATE = "internal_provider_update"
    const val REFRESH_REQUEST_CODE = 42_020
    const val REFRESH_INTERVAL_MS = 60_000L

    private var lastLoopStartTime = 0L
    private var lastScheduleTime = 0L

    fun hasWidgets(context: Context): Boolean {
        val manager = AppWidgetManager.getInstance(context)
        val counts = FactoryWidgetProviderCatalog.entries.associate { entry ->
            val provider = ComponentName(context.packageName, entry.receiverClassName)
            val ids = manager.getAppWidgetIds(provider)
            Log.d(TAG, "hasWidgets[${entry.widgetKey}]: found ${ids.size} ids for provider=${entry.receiverClassName}")
            entry.widgetKey to ids.size
        }
        return counts.values.any { it > 0 }
    }

    fun schedule(context: Context, delayMillis: Long = REFRESH_INTERVAL_MS) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, FactoryWidgetRefreshReceiver::class.java).apply {
            action = ACTION_REFRESH
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context, REFRESH_REQUEST_CODE, intent, 
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val canScheduleExact = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            alarmManager.canScheduleExactAlarms()
        } else {
            true
        }

        val now = System.currentTimeMillis()
        if (now - lastScheduleTime < 5000L) {
            Log.v(TAG, "schedule: skipped (throttled)")
            return
        }
        lastScheduleTime = now

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (canScheduleExact) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    System.currentTimeMillis() + delayMillis,
                    pendingIntent
                )
            } else {
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    System.currentTimeMillis() + delayMillis,
                    pendingIntent
                )
            }
        } else {
            if (canScheduleExact) {
                alarmManager.setExact(
                    AlarmManager.RTC_WAKEUP,
                    System.currentTimeMillis() + delayMillis,
                    pendingIntent
                )
            } else {
                alarmManager.set(
                    AlarmManager.RTC_WAKEUP,
                    System.currentTimeMillis() + delayMillis,
                    pendingIntent
                )
            }
        }
    }

    fun cancel(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, FactoryWidgetRefreshReceiver::class.java).apply {
            action = ACTION_REFRESH
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            REFRESH_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )
        if (pendingIntent != null) {
            alarmManager.cancel(pendingIntent)
            pendingIntent.cancel()
            Log.d(TAG, "schedule: cancelled")
        }
    }

    fun actionMatches(intent: Intent): Boolean {
        return intent.action == ACTION_REFRESH
    }
    
    fun requestImmediate(context: Context, widgetKey: String? = null, appWidgetId: Int? = null, refreshFromApi: Boolean = true) {
        if (refreshFromApi && !WidgetNetworkGate.canAttemptNetwork(context.applicationContext, "requestImmediate")) {
            return
        }

        val intent = Intent(context, FactoryWidgetRefreshReceiver::class.java).apply {
            action = ACTION_REFRESH
            putExtra(EXTRA_WIDGET_KEY, widgetKey)
            if (appWidgetId != null) putExtra(EXTRA_APP_WIDGET_ID, appWidgetId)
            putExtra(EXTRA_REFRESH_FROM_API, refreshFromApi)
        }
        context.sendBroadcast(intent)
    }

    fun requestProviderUpdate(context: Context, widgetKey: String? = null, appWidgetId: Int? = null) {
        val appWidgetManager = AppWidgetManager.getInstance(context)
        FactoryWidgetProviderCatalog.entries.forEach { entry ->
            if (widgetKey != null && entry.widgetKey != widgetKey) return@forEach
            val provider = ComponentName(context, entry.receiverClassName)
            val ids = if (appWidgetId != null) intArrayOf(appWidgetId) else appWidgetManager.getAppWidgetIds(provider)
            if (ids.isNotEmpty()) {
                try {
                    val intent = Intent(context, Class.forName(entry.receiverClassName)).apply {
                        action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                        putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
                        putExtra(EXTRA_INTERNAL_PROVIDER_UPDATE, true)
                    }
                    context.sendBroadcast(intent)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to send broadcast to \${entry.receiverClassName}", e)
                }
            }
        }
    }

    /**
     * Force every existing widget of every type to recompose with the current rendering code
     * and the latest captured colors. This is the canonical Glance refresh — used after an app
     * upgrade / boot and on app start, since Android does NOT re-render home-screen widgets when
     * the APK is replaced (they keep stale RemoteViews until something calls update()).
     */
    suspend fun updateAllProviders(context: Context) {
        val appCtx = context.applicationContext
        FactoryWidgetProviderCatalog.entries.forEach { entry ->
            runCatching {
                FactoryGlanceAppWidget(entry.widgetKey).updateAll(appCtx)
            }.onFailure {
                Log.w(TAG, "updateAllProviders[${entry.widgetKey}] failed", it)
            }
        }
    }

    @Volatile private var screenReceiver: ScreenStateReceiver? = null

    private fun registerScreenReceiver(context: Context) {
        if (screenReceiver == null) {
            screenReceiver = ScreenStateReceiver()
            val filter = android.content.IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_USER_PRESENT)
            }
            context.applicationContext.registerReceiver(screenReceiver, filter)
        }
    }


    fun stopWebSocket(context: Context) {
        val appCtx = context.applicationContext
        WidgetSyncLogger.log(appCtx, "Scheduler: stopWebSocket → sleeping service (not stopping)")
        WidgetSyncEngine.stop(appCtx, "scheduler stop")
        // Send ACTION_SLEEP rather than stopService. Stopping the service requires
        // startForegroundService to restart it, which is blocked on Android 12+ when called
        // from a BroadcastReceiver (background context). Keeping the service alive means
        // startService (no foreground privilege needed) suffices on screen-on.
        runCatching {
            appCtx.startService(
                Intent(appCtx, WidgetSyncService::class.java).apply {
                    action = WidgetSyncService.ACTION_SLEEP
                }
            )
        }.onFailure {
            Log.d(TAG, "stopWebSocket: service not running, nothing to sleep")
        }
    }

    fun startInProcessLoop(context: Context) {
        val appCtx = context.applicationContext
        registerScreenReceiver(appCtx)
        WidgetSyncLogger.log(appCtx, "Scheduler: startInProcessLoop → waking service")
        val wakeIntent = Intent(appCtx, WidgetSyncService::class.java).apply {
            action = WidgetSyncService.ACTION_WAKE
        }
        // If the service is already running as a foreground service (the normal case after
        // stopWebSocket now sends ACTION_SLEEP), startService delivers the intent without
        // needing foreground-start privileges. If it was killed by the OS, fall back to
        // startForegroundService; if that also fails (Android 12+ background restriction),
        // trigger an immediate broadcast-based REST refresh as a best-effort fallback.
        val sentToRunning = runCatching { appCtx.startService(wakeIntent) }.isSuccess
        if (!sentToRunning) {
            val started = runCatching { appCtx.startForegroundService(wakeIntent) }.isSuccess
            if (!started) {
                Log.w(TAG, "startInProcessLoop: service unreachable, using broadcast fallback")
                requestImmediate(appCtx, null, null, refreshFromApi = true)
            }
        }
    }

    fun reloadWebSocket(context: Context) {
        val appCtx = context.applicationContext
        WidgetSyncLogger.log(appCtx, "Scheduler: reloadWebSocket → reloading WidgetSyncService")
        val reloadIntent = Intent(appCtx, WidgetSyncService::class.java).apply {
            action = WidgetSyncService.ACTION_RELOAD
        }
        val sentToRunning = runCatching { appCtx.startService(reloadIntent) }.isSuccess
        if (!sentToRunning) {
            runCatching { appCtx.startForegroundService(reloadIntent) }.onFailure {
                Log.w(TAG, "reloadWebSocket: service unreachable: ${it.message}")
            }
        }
    }

    fun requestForceUpdate(context: Context, widgetKey: String? = null, appWidgetId: Int? = null) {
        val appCtx = context.applicationContext
        WidgetSyncLogger.log(appCtx, "Scheduler: requestForceUpdate key=$widgetKey id=$appWidgetId")
        requestImmediate(appCtx, widgetKey, appWidgetId, refreshFromApi = true)
    }
}

abstract class FactoryWidgetReceiver : GlanceAppWidgetReceiver() {
    abstract val widgetKey: String
    private var isInternalProviderUpdate = false

    override val glanceAppWidget: GlanceAppWidget
        get() = FactoryGlanceAppWidget(widgetKey)

    override fun onReceive(context: Context, intent: Intent) {
        val previous = isInternalProviderUpdate
        isInternalProviderUpdate =
            intent.getBooleanExtra(WidgetRefreshScheduler.EXTRA_INTERNAL_PROVIDER_UPDATE, false)
        try {
            super.onReceive(context, intent)
        } finally {
            isInternalProviderUpdate = previous
        }
    }

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        Log.d("WidgetRefresh", "receiver[\$widgetKey]: onEnabled")
        WidgetRefreshScheduler.cancel(context)
    }

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        val internalUpdate = isInternalProviderUpdate
        Log.d("WidgetRefresh", "receiver[$widgetKey]: onUpdate ids=${appWidgetIds.toList()} internal=$internalUpdate")
        
        if (!internalUpdate) {
            // Push initial state to the new widget IDs immediately
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO).launch {
                updateFactoryWidgets(context.applicationContext, widgetKey, refreshFromApi = false)
            }

            Log.d("WidgetRefresh", "receiver[$widgetKey]: external update uses visible Glance sync")
        }

        WidgetRefreshScheduler.startInProcessLoop(context)
        super.onUpdate(context, appWidgetManager, appWidgetIds)
        WidgetRefreshScheduler.cancel(context)
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle
    ) {
        Log.d("WidgetRefresh", "receiver[$widgetKey]: onOptionsChanged id=$appWidgetId")
        super.onAppWidgetOptionsChanged(context, appWidgetManager, appWidgetId, newOptions)
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        Log.d("WidgetRefresh", "receiver[\$widgetKey]: onDisabled")
        WidgetRefreshScheduler.cancel(context)
        // All widgets of this type removed — fully stop the service (unlike screen-off
        // which only sleeps it). The service's own getAllConfiguredEntityIds check will
        // also stop it on next wake if no other widget types remain.
        runCatching { context.stopService(Intent(context, WidgetSyncService::class.java)) }
    }
}

class FactoryWidgetRefreshReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        WidgetSyncLogger.log(context, "Broadcast: received ${intent.action}")
        if (!WidgetRefreshScheduler.actionMatches(intent)) return

        val appCtx = context.applicationContext
        
        val pendingResult = goAsync()
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO).launch {
            try {
                // If this is an immediate refresh request
                val refreshFromApi = intent.getBooleanExtra(WidgetRefreshScheduler.EXTRA_REFRESH_FROM_API, true)
                val widgetKey = intent.getStringExtra(WidgetRefreshScheduler.EXTRA_WIDGET_KEY)
                val appWidgetId = if (intent.hasExtra(WidgetRefreshScheduler.EXTRA_APP_WIDGET_ID)) intent.getIntExtra(WidgetRefreshScheduler.EXTRA_APP_WIDGET_ID, 0) else null
                
                Log.d("WidgetRefresh", "Receiver: starting refresh key=$widgetKey id=$appWidgetId fromApi=$refreshFromApi")
                
                if (refreshFromApi) {
                    updateFactoryWidgets(appCtx, widgetKey, appWidgetId, refreshFromApi = true)
                    WidgetRefreshScheduler.requestProviderUpdate(appCtx, widgetKey, appWidgetId)
                }
                
                WidgetRefreshScheduler.cancel(appCtx)
            } finally {
                pendingResult.finish()
            }
        }
    }
}

/**
 * Re-renders all existing widgets after the app is updated or the device reboots. Without this,
 * widgets already on the home screen keep the RemoteViews produced by the previous app build,
 * so design/color changes only appear on newly-added widgets.
 */
class WidgetUpgradeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_BOOT_COMPLETED -> {
                Log.d(WidgetRefreshScheduler.TAG, "WidgetUpgradeReceiver: ${intent.action} → refresh all widgets")
                val appCtx = context.applicationContext
                val pendingResult = goAsync()
                kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO).launch {
                    try {
                        WidgetRefreshScheduler.updateAllProviders(appCtx)
                    } finally {
                        pendingResult.finish()
                    }
                }
            }
        }
    }
}

class ScreenStateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        Log.d(WidgetRefreshScheduler.TAG, "ScreenStateReceiver: action=$action")
        when (action) {
            Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT -> {
                WidgetSyncLogger.log(context, "Receiver: Screen ON")
                WidgetRefreshScheduler.startInProcessLoop(context)
            }
            Intent.ACTION_SCREEN_OFF -> {
                WidgetSyncLogger.log(context, "Receiver: Screen OFF")
                WidgetRefreshScheduler.stopWebSocket(context)
            }
        }
    }
}
